/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Atmosphere;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL20C;

/** Material readiness is a draw boundary, including board replacement and changes to lighting flags. */
@Tag("on-demand")
class GpuTerrainPreparationSmokeTest {
    @Test
    void preparesOnlyRequiredVariantsBeforeDrawingIncludingBoardAndLightingChanges() throws Exception {
        var captured = new AtomicReference<BoardScene>();
        var uniform = new AtomicReference<BoardScene>();
        SwingUtilities.invokeAndWait(() -> {
            Board board = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 4; y++) {
                    board.setHex(new Coords(x, y), new Hex(x > 2 ? 1 : 0, x == 3 ? "ice:1" : "",
                          x == 4 ? "snow" : x < 2 ? "dirt" : "grass"));
                }
            }
            var game = new Game();
            game.setBoard(board);
            try (var source = new GpuMapSource(game, null, null)) { captured.set(source.takeFrame().scene()); }
            Board plain = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 4; y++) { plain.setHex(new Coords(x, y), new Hex(0, "", "grass")); }
            }
            game.setBoard(plain);
            try (var source = new GpuMapSource(game, null, null)) { uniform.set(source.takeFrame().scene()); }
        });
        var report = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "terrain-preparation.log").toPath();
        Files.createDirectories(report.getParent());
        Files.writeString(report, "");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(800, 600);
        new Lwjgl3Application(new ApplicationAdapter() {
            private GpuTerrain terrain;
            private GpuReviewFrame frame;
            private BoardCamera camera;
            private GL20 original;
            private BoardScene scene = uniform.get();
            private int phase;
            private long deadline;
            private long started;
            private boolean drawing;
            private final Set<String> variants = new HashSet<>();
            private final Set<String> sources = new HashSet<>();
            private final Set<String> compiledSources = new HashSet<>();

            @Override
            public void create() {
                try {
                    original = Gdx.gl20;
                    Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                          (proxy, method, args) -> {
                              if (method.getName().equals("glShaderSource")) { checkTerrainSource((String) args[1], false); }
                              Object result;
                              try { result = method.invoke(original, args); }
                              catch (InvocationTargetException error) { throw error.getCause(); }
                              // Both compiler paths validate/adopt on this thread; shared-context source uploads
                              // intentionally bypass Gdx globals. Inspect the actual driver source at publication.
                              if (method.getName().equals("glGetShaderiv") && (int) args[1] == GL20.GL_COMPILE_STATUS) {
                                  checkTerrainSource(GL20C.glGetShaderSource((int) args[0]), true);
                              }
                              return result;
                          });
                    terrain = new GpuTerrain();
                    frame = new GpuReviewFrame(BoardAtmosphere.DEFAULTS);
                    camera = new BoardCamera();
                    camera.resize(800, 600);
                    nextPhase();
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            private void checkTerrainSource(String text, boolean compiled) throws IOException {
                if (!text.contains("vec3 groundToneFor(")) { return; }
                assertFalse(drawing, "An expensive terrain variant reached visible draw in phase " + phase);
                for (String flag : new String[] { "iceFlag", "volcanicFlag", "terrainBlendFlag", "cloudShadowFlag" }) {
                    if (text.contains("#define " + flag)) { variants.add(flag); }
                }
                if (!text.contains("#define shadowMapFlag")) { variants.add("unlit"); }
                boolean first = sources.add(text);
                if (compiled ? compiledSources.add(text) : first) {
                    Files.writeString(report, String.format(Locale.ROOT,
                          "phase %d terrain source %08x %s after %.3f ms; blend=%s ice=%s volcanic=%s cloud=%s shadow=%s%n",
                          phase, text.hashCode(), compiled ? "compiled" : "submitted", (System.nanoTime() - started) / 1_000_000.0,
                          text.contains("#define terrainBlendFlag"), text.contains("#define iceFlag"),
                          text.contains("#define volcanicFlag"), text.contains("#define cloudShadowFlag"),
                          text.contains("#define shadowMapFlag")), StandardOpenOption.APPEND);
                }
            }

            private void nextPhase() {
                started = System.nanoTime();
                deadline = started + 240_000_000_000L;
                camera.fit(scene);
                frame.configure(phase == 2 ? new BoardAtmosphere.Settings(0, 0, 0, 2, 0, 0,
                      BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25, false)
                      : new BoardAtmosphere.Settings(13, phase == 3 ? .7f : 0, 0, 2, 0, 0));
            }

            @Override
            public void render() {
                if (failure.get() != null) { return; }
                try {
                    assertTrue(System.nanoTime() < deadline, "Terrain preparation timed out in phase " + phase);
                    frame.prepare(terrain, camera, scene);
                    terrain.update(scene, camera.camera);
                    terrain.refine(camera.camera);
                    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
                    if (!terrain.ready(scene)) { return; }
                    Files.writeString(report, String.format(Locale.ROOT, "phase %d ready after %.3f ms%n",
                          phase, (System.nanoTime() - started) / 1_000_000.0), StandardOpenOption.APPEND);
                    if (phase == 0) {
                        assertEquals(1, sources.size(), "Uniform grass needs only its ordinary sculpt program");
                        assertFalse(variants.contains("terrainBlendFlag"), "A uniform board must not prepare unused blends");
                    }
                    drawing = true;
                    try { frame.render(terrain, camera, scene); }
                    finally { drawing = false; }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Prepared draw in phase " + phase);
                    if (++phase == 5) {
                        assertTrue(variants.containsAll(Set.of("iceFlag", "volcanicFlag", "terrainBlendFlag", "cloudShadowFlag", "unlit")),
                              "The fixture must exercise every required variant: " + variants);
                        Gdx.app.exit();
                    } else {
                        if (phase == 1) { scene = captured.get(); terrain.boardChanged(); }
                        if (phase == 4) { scene = GpuMagmaSmokeTest.scene(false); terrain.boardChanged(); }
                        nextPhase();
                    }
                } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
            }

            @Override
            public void dispose() {
                if (frame != null) { frame.dispose(); }
                if (terrain != null) { terrain.dispose(); }
                if (original != null) { Gdx.gl20 = original; }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain variant preparation", failure.get()); }
    }
}
