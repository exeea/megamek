/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verify actual weather placement on raised, negative-level and deep-water boards. */
@Tag("on-demand")
class GpuWeatherBaselineSmokeTest {
    @Test
    void waterDepthCannotMoveRainSnowOrHailBelowTheBoardSurface() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuWeatherParticles particles = new GpuWeatherParticles();
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    for (int level : new int[] { 0, 4, -3 }) {
                        BoardScene dry = scene(level, -1);
                        BoardScene deepWater = scene(level, 20);
                        camera.fit(dry);
                        camera.center(BoardGeometry.center(new Coords(2, 2), level));
                        for (int kind = 0; kind < 3; kind++) {
                            var effects = effects(kind);
                            Pixmap reference = particleFrame(particles, camera, dry, effects);
                            Pixmap actual = particleFrame(particles, camera, deepWater, effects);
                            try {
                                int visible = 0;
                                for (int y = 0; y < reference.getHeight(); y++) {
                                    for (int x = 0; x < reference.getWidth(); x++) {
                                        if ((reference.getPixel(x, y) & 0xFFFFFF00) != 0) {
                                            visible++;
                                        }
                                    }
                                }
                                assertTrue(visible > 100, "Weather must render above level " + level + ", kind " + kind);
                                assertEquals(reference.getPixels(), actual.getPixels(),
                                      "Changing only water depth must not move weather: level " + level + ", kind " + kind);
                            } finally {
                                reference.dispose();
                                actual.dispose();
                            }
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    particles.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void conditionEditsAreIsolatedAndSharedStagesReloadTogether() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkReload(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkReload(GpuShaderManager manager) {
        var particles = new GpuWeatherParticles();
        try {
            BoardScene scene = scene(0, -1);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(true);
            camera.fit(scene);
            byte[][] original = frames(particles, camera, scene);
            for (var kind : GpuWeatherParticles.Kind.values()) {
                String source = GpuShaderSource.readDisk(kind.source);
                for (String edit : List.of(source.replace("particleDisc(uv)", "particleDisc(uv) * 0.5"),
                      source.replace("const float FALL_SPEED = ", "const float FALL_SPEED = 0.5 * "))) {
                    var result = manager.apply(Map.of(kind.source, edit));
                    assertTrue(result.success(), result.message());
                    assertEquals(1, result.updated(), "A condition edit replaces only its own program");
                    byte[][] changed = frames(particles, camera, scene);
                    for (int index = 0; index < original.length; index++) {
                        if (index == kind.ordinal()) {
                            assertFalse(Arrays.equals(original[index], changed[index]), kind + " edit must reach rendered pixels");
                        } else {
                            assertArrayEquals(original[index], changed[index], "Other conditions retain their appearance");
                        }
                    }
                    assertFalse(manager.apply(Map.of(kind.source, "unfinished shader edit")).success());
                    byte[][] retained = frames(particles, camera, scene);
                    for (int index = 0; index < changed.length; index++) {
                        assertArrayEquals(changed[index], retained[index], "Invalid edits retain all working programs");
                    }
                }
                assertTrue(manager.apply(Map.of()).success());
            }

            String fragment = GpuShaderSource.readDisk("weather-particles.frag");
            String vertex = GpuShaderSource.readDisk("weather-particles.vert");
            var sharedEdits = Map.of("weather-particles.frag", fragment.replace("color * u_light", "color * u_light * 0.5"),
                  "weather-particles.vert", vertex.replace("FALL_SPEED * u_level", "FALL_SPEED * u_level * 0.5"));
            for (var edit : sharedEdits.entrySet()) {
                var shared = manager.apply(Map.of(edit.getKey(), edit.getValue()));
                assertTrue(shared.success(), shared.message());
                assertEquals(3, shared.updated(), "Shared stage edits replace all precipitation programs");
                byte[][] changed = frames(particles, camera, scene);
                for (int index = 0; index < original.length; index++) {
                    assertFalse(Arrays.equals(original[index], changed[index]), "Shared edits reach every condition");
                }
                assertTrue(manager.apply(Map.of()).success());
            }
            byte[][] restored = frames(particles, camera, scene);
            for (int index = 0; index < original.length; index++) { assertArrayEquals(original[index], restored[index]); }

            ScreenUtils.clear(0, 0, 0, 1, true);
            for (int kind = 0; kind < 3; kind++) { particles.render(camera.camera, scene, effects(kind), Color.WHITE, 3); }
            byte[] sequential = pixels();
            ScreenUtils.clear(0, 0, 0, 1, true);
            particles.render(camera.camera, scene, new BoardAtmosphere.Effects(1, 1, 1, 0, 0, .6f, 60), Color.WHITE, 3);
            assertArrayEquals(sequential, pixels(), "Mixed weather preserves the same draws and blending order");

            var atmosphere = new GpuAtmosphere();
            try {
                String sand = GpuShaderSource.readDisk("weather-sand.glsl");
                var result = manager.apply(Map.of("weather-sand.glsl", sand.replace("0.12", "0.06")));
                assertTrue(result.success(), result.message());
                assertEquals(1, result.updated(), "Sand edits reload the existing composite, leaving fog and particles alone");
                assertTrue(manager.apply(Map.of()).success());
            } finally { atmosphere.dispose(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { particles.dispose(); }
        assertEquals(0, ShaderProgram.getNumManagedShaderPrograms(), "Reloaded weather programs are released");
    }

    private static byte[][] frames(GpuWeatherParticles particles, BoardCamera camera, BoardScene scene) {
        byte[][] frames = new byte[3][];
        for (int kind = 0; kind < frames.length; kind++) {
            ScreenUtils.clear(0, 0, 0, 1, true);
            particles.render(camera.camera, scene, effects(kind), Color.WHITE, 3);
            frames[kind] = pixels();
        }
        return frames;
    }

    private static byte[] pixels() {
        return ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), false);
    }

    private static BoardAtmosphere.Effects effects(int kind) {
        return new BoardAtmosphere.Effects(kind == 0 ? 1 : 0, kind == 1 ? 1 : 0, kind == 2 ? 1 : 0, 0, 0, .6f, 60);
    }

    private static Pixmap particleFrame(GpuWeatherParticles particles, BoardCamera camera, BoardScene scene,
          BoardAtmosphere.Effects effects) {
        ScreenUtils.clear(0, 0, 0, 1, true);
        particles.render(camera.camera, scene, effects, Color.WHITE, 3);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static BoardScene scene(int level, int depth) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, BoardScene.Surface.GRASS,
                      null, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
