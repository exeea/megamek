/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Compare cached packed depth with the original full shadow pass, including removed and occluded units. */
@Tag("on-demand")
class GpuShadowCacheSmokeTest {
    @Test
    void movingUnitsReuseStaticDepthAndInvalidationMatchesTheFullPass() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Grasslands BattleMats/32x17 Grasslands A BattleMat.board"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            BoardScene scene = fixture.source.takeFrame().scene();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1280, 900);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try { check(fixture, scene); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Static shadow reuse", failure.get()); }
    }

    private void check(GpuBoardFixture fixture, BoardScene scene) throws Exception {
        GpuTerrain terrain = new GpuTerrain();
        Model model = new ModelBuilder().createBox(20, 20, 20, new Material(ColorAttribute.createDiffuse(Color.WHITE)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        profiler.enable();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(1280, 900);
            camera.fit(scene);
            terrain.update(scene);
            var lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
            terrain.setAtmosphere(lighting);
            terrain.renderShadows(camera.camera, List.of());
            assertNull(field(terrain, "staticShadow"), "Map-only views keep one shadow framebuffer");
            camera.zoom(.6f);
            // The zoom changes tree levels, which the map picks up at its next redraw: start from that redraw.
            terrain.refreshShadows();
            terrain.renderShadows(camera.camera, List.of());
            byte[] stationary = depth(terrain);
            for (int pan = 0; pan < 5; pan++) {
                camera.pan(1, 0);
                profiler.reset();
                terrain.renderShadows(camera.camera, List.of());
                assertEquals(0, profiler.getDrawCalls(), "Small camera pans reuse the existing shadow map");
                assertArrayEquals(stationary, depth(terrain), "Camera reuse preserves the shadow texture and projection");
                fullPass(terrain, List.of());
                assertArrayEquals(stationary, depth(terrain), "A fresh pass at the reused projection remains exact");
            }
            ModelInstance unit = new ModelInstance(model);
            Coords at = scene.tiles().stream().filter(t -> t.elevation() == 0 && !t.liquid().present()).skip(30).findFirst().orElseThrow().coords();
            var center = BoardGeometry.center(at, 0);
            unit.transform.setToTranslation(center.x, center.y, 10);
            terrain.renderShadows(camera.camera, List.of(unit));
            for (int step = 0; step < 9; step++) {
                List<ModelInstance> units = (step == 4 || step == 8) ? List.of() : List.of(unit);
                unit.transform.setToTranslation(center.x + step * 3, center.y, step == 3 ? 0 : 10);
                if (step == 1 || step == 2) { camera.pan(1, -1); }
                if (step == 5) { camera.zoom(.7f); camera.pan(30, 15); }
                if (step == 6) { terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(9, 0, 0, 0, 0, 1))); }
                if (step == 7) {
                    javax.swing.SwingUtilities.invokeAndWait(() -> {
                        Hex raised = fixture.game.getBoard().getHex(at).duplicate();
                        raised.setLevel(raised.getLevel() + 1);
                        fixture.game.getBoard().setHex(at, raised);
                        fixture.source.refresh();
                    });
                    terrain.update(fixture.source.takeFrame().scene());
                }
                profiler.reset();
                terrain.renderShadows(camera.camera, units);
                int cached = profiler.getDrawCalls();
                byte[] actual = depth(terrain);
                profiler.reset();
                fullPass(terrain, units);
                int full = profiler.getDrawCalls();
                assertArrayEquals(depth(terrain), actual, "Packed depth must match the original full pass at step " + step);
                if (step < 3) {
                    assertTrue(cached < full / 2, "Unit motion must not redraw the static board: " + cached + " / " + full);
                }
                if (step == 4) { assertEquals(1, cached, "GLProfiler counts the framebuffer blit; removing the last unit needs only that copy"); }
                if (step >= 5) {
                    assertEquals(full, cached, "Camera, light and terrain changes must not pay an extra cache copy");
                }
                System.out.printf("SHADOW step=%d cached=%d full=%d%n", step, cached, full);
            }
            profiler.disable();
            double[][] times = new double[2][60];
            for (int frame = -10; frame < times[0].length; frame++) {
                unit.transform.setToTranslation(center.x + (frame & 3), center.y, 10);
                // Pair the two paths and alternate their order to reduce clock/power-state bias.
                for (int pass = 0; pass < 2; pass++) {
                    int variant = (frame + pass) & 1;
                    Gdx.gl.glFinish();
                    long started = System.nanoTime();
                    if (variant == 1) { terrain.renderShadows(camera.camera, List.of(unit)); }
                    else { fullPass(terrain, List.of(unit)); }
                    Gdx.gl.glFinish();
                    if (frame >= 0) { times[variant][frame] = (System.nanoTime() - started) / 1e6; }
                }
            }
            for (int variant = 0; variant < times.length; variant++) {
                java.util.Arrays.sort(times[variant]);
                System.out.printf("SHADOW isolated cached=%s median=%.3fms p95=%.3fms%n", variant == 1,
                      times[variant][30], times[variant][57]);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            profiler.disable();
            model.dispose();
            terrain.dispose();
        }
    }

    private static Object field(GpuTerrain terrain, String name) throws ReflectiveOperationException {
        var field = GpuTerrain.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(terrain);
    }

    private static void fullPass(GpuTerrain terrain, List<ModelInstance> units) throws ReflectiveOperationException {
        var shadow = (DirectionalShadowLight) terrain.environment().shadowMap;
        var draw = GpuTerrain.class.getDeclaredMethod("renderDepth", Camera.class, List.class, ModelBatch.class, boolean.class);
        draw.setAccessible(true);
        shadow.begin();
        try { draw.invoke(terrain, shadow.getCamera(), units, field(terrain, "depthBatch"), true); }
        finally { shadow.end(); }
    }

    private static byte[] depth(GpuTerrain terrain) {
        var shadow = (DirectionalShadowLight) terrain.environment().shadowMap;
        shadow.getFrameBuffer().begin();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, GpuTerrain.SHADOW_RESOLUTION, GpuTerrain.SHADOW_RESOLUTION);
        try {
            byte[] bytes = new byte[pixels.getPixels().remaining()];
            pixels.getPixels().get(bytes);
            return bytes;
        } finally { pixels.dispose(); shadow.getFrameBuffer().end(); }
    }
}
