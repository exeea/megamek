/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Coords;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.planetaryConditions.Wind;
import megamek.common.planetaryConditions.WindDirection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fog over swaying grass: blades must be fogged like the ground they stand on, never cut out of the layer. */
@Tag("on-demand")
class GpuGrassFogSmokeTest {
    private static final int WIDTH = 1280, HEIGHT = 800;
    private final File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));

    @Test
    void fogCoversEveryGrassPixel() throws Exception {
        assertTrue(output.isDirectory() || output.mkdirs());
        var failure = new AtomicReference<Throwable>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(WIDTH, HEIGHT);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try { check(fixture.source.takeFrame().scene()); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Grass under fog", failure.get()); }
    }

    private void check(BoardScene scene) throws Exception {
        var camera = new BoardCamera();
        camera.resize(WIDTH, HEIGHT);
        camera.setIsometric(false);
        camera.setPerspective(true);
        camera.tilt(55);
        camera.camera.zoom = BoardGeometry.width() / 260f;
        camera.center(BoardGeometry.center(new Coords(11, 8), 0));
        var terrain = new GpuTerrain();
        var atmosphere = new GpuAtmosphere();
        try {
            terrain.update(scene, camera.camera);
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (terrain.refine(camera.camera) || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "Terrain did not settle");
                Thread.sleep(2);
            }
            // Uniform fog: without banks or thin patches every visible ground and blade pixel lies inside the layer.
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, GpuClouds.MIN_SHADOW_STRENGTH,
                  GpuClouds.MAX_SHADOW_STRENGTH, 0, 0, 0));
            var wind = new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0.7f, 60);
            Pixmap clear = frame(atmosphere, terrain, camera, scene, new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, wind));
            int width = clear.getWidth(), height = clear.getHeight();
            FloatBuffer depth = BufferUtils.newFloatBuffer(width * height);
            Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
            Pixmap fog = frame(atmosphere, terrain, camera, scene, new BoardAtmosphere.Settings(13, 0, 0.6f,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0, wind));
            try {
                int board = 0, unfogged = 0;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        // Pixmaps are top-down, the depth read-back bottom-up.
                        if (depth.get((height - 1 - y) * width + x) >= 1) { continue; }
                        board++;
                        if (rgbDifference(clear.getPixel(x, y), fog.getPixel(x, y)) <= 6) { unfogged++; }
                    }
                }
                GpuReviewFrame.save(new File(output, "grass-fog.png"));
                System.out.println("Grass under fog: " + unfogged + "/" + board + " board pixels unfogged");
                assertTrue(board > width * height / 2, "The view must show the board: " + board);
                assertTrue(unfogged < board / 1000, "Fog must cover grass blades: " + unfogged + "/" + board + " unfogged");
            } finally {
                clear.dispose();
                fog.dispose();
            }
            measureCost(atmosphere, terrain, camera, scene);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    /** What fog adds to this frame: interleaved modes, the game's fog options, stages as in the battle view. */
    private void measureCost(GpuAtmosphere atmosphere, GpuTerrain terrain, BoardCamera camera, BoardScene scene)
          throws Exception {
        atmosphere.setOptions(GpuAtmosphere.Options.DEFAULTS);
        // The game's own presets: heavy fog also brings haze, stratus cloud shadows and light shafts.
        String[] names = { "clear", "light fog", "heavy fog" };
        BoardAtmosphere.Settings[] modes = new BoardAtmosphere.Settings[names.length];
        for (int mode = 0; mode < modes.length; mode++) {
            var conditions = new PlanetaryConditions();
            conditions.setFog(mode == 0 ? Fog.FOG_NONE : mode == 1 ? Fog.FOG_LIGHT : Fog.FOG_HEAVY);
            conditions.setWind(Wind.MOD_GALE);
            conditions.setWindDirection(WindDirection.NORTHEAST);
            modes[mode] = BoardAtmosphere.fromScenario(conditions, false, 0.5);
        }
        var report = new StringBuilder("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + "\n" + WIDTH + "x" + HEIGHT
              + " Grassland 2, perspective tilt 55 at 260 px/hex, moderate gale; 16 warmups, 60 interleaved frames per mode\n");
        try (var timings = new GpuStageTimings()) {
            for (int round = 0; round < 76; round++) {
                for (int mode = 0; mode < modes.length; mode++) {
                    timings.beginFrame(round >= 16);
                    timings.stage(names[mode] + ": light, shadows, clouds");
                    atmosphere.configure(modes[mode]);
                    atmosphere.updateLight(camera.camera);
                    terrain.setAtmosphere(atmosphere.lighting());
                    terrain.animate(1f / 60, List.of());
                    terrain.renderShadows(camera.camera, List.of());
                    atmosphere.prepareClouds(terrain, scene, 1f / 60);
                    timings.stage(names[mode] + ": scene");
                    atmosphere.begin(WIDTH, HEIGHT, 1f / 60);
                    terrain.render(camera.camera, false);
                    terrain.renderTransparent(camera.camera);
                    timings.stage(names[mode] + ": fog and composite");
                    atmosphere.end(camera.camera, terrain, scene, 0);
                    timings.stage(null);
                    Gdx.gl.glFinish();
                }
            }
            timings.appendReport(report, "Stages per mode");
        }
        Files.writeString(new File(output, "grass-fog-timing.txt").toPath(), report);
        System.out.print(report);
    }

    private static Pixmap frame(GpuAtmosphere atmosphere, GpuTerrain terrain, BoardCamera camera, BoardScene scene,
          BoardAtmosphere.Settings settings) {
        atmosphere.configure(settings);
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(camera.camera, List.of());
        atmosphere.prepareClouds(terrain, scene, 0);
        atmosphere.begin(WIDTH, HEIGHT, 0);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int rgbDifference(int a, int b) {
        return Math.abs((a >>> 24) - (b >>> 24)) + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255))
              + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
    }
}
