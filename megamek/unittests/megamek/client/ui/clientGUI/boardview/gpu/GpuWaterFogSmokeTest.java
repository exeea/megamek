/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/** A submerged shoreline is not the display plinth: fog must cover it through the nearest water surface. */
@Tag("on-demand")
class GpuWaterFogSmokeTest {
    private static final int WIDTH = 1280, HEIGHT = 800;
    private final File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));

    @Test
    void archipelagoShorelinesRemainInsideFogInBothProjections() throws Exception {
        assertTrue(output.isDirectory() || output.mkdirs());
        Board board = new Board();
        board.load(new File("data/boards/Map Set 7/16x17 Archipelago 1.board"));
        assertEquals(16, board.getWidth());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(WIDTH, HEIGHT);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try { check(fixture.source.takeFrame().scene()); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, configuration);
        }
        if (failure.get() != null) { throw new AssertionError("Water under fog", failure.get()); }
    }

    private void check(BoardScene scene) throws Exception {
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        BoardCamera camera = new BoardCamera();
        camera.resize(WIDTH, HEIGHT);
        camera.tilt(70);
        camera.fit(scene);
        List<String> failures = new ArrayList<>();
        StringBuilder report = new StringBuilder("Renderer: " + Gdx.gl.glGetString(GL20.GL_RENDERER) + "\n");
        try {
            terrain.update(scene, camera.camera);
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (terrain.refine(camera.camera) || terrain.busy()) {
                assertTrue(System.nanoTime() < deadline, "Terrain did not settle");
                Thread.sleep(2);
            }
            // Uniform banks isolate coverage from the deliberate clear spaces between moving fog banks.
            atmosphere.setOptions(new GpuAtmosphere.Options(0, false, GpuClouds.MIN_SHADOW_STRENGTH,
                  GpuClouds.MAX_SHADOW_STRENGTH, 0, 0, 0));
            for (boolean perspective : new boolean[] { false, true }) {
                camera.setPerspective(perspective);
                camera.fit(scene);
                for (AtmospherePreset preset : new AtmospherePreset[] { AtmospherePreset.LIGHT_FOG, AtmospherePreset.HEAVY_FOG }) {
                    BoardAtmosphere.Settings settings = preset.settings(.98);
                    // Keep surface lighting identical: clouds depend on fog too, so this comparison disables clouds.
                    BoardAtmosphere.Settings clearSettings = new BoardAtmosphere.Settings(settings.hour(), 0, 0,
                          settings.groundLayerHeight(), 0, 0);
                    BoardAtmosphere.Settings fogSettings = new BoardAtmosphere.Settings(settings.hour(), 0, settings.fog(),
                          settings.groundLayerHeight(), settings.haze(), 0);
                    String name = (perspective ? "perspective-" : "orthographic-") + preset.name().toLowerCase();
                    Pixmap clear = frame(atmosphere, terrain, camera, scene, clearSettings);
                    FloatBuffer opaque = readDepth(atmosphere.depthTexture());
                    FloatBuffer water = readDepth(terrain.waterDepth());
                    Pixmap fog = frame(atmosphere, terrain, camera, scene, fogSettings);
                    FloatBuffer resultDepth = readScreenDepth();
                    try {
                        int waterPixels = 0, unchangedWater = 0, plinthPixels = 0, changedPlinth = 0, frontWater = 0;
                        Vector3 surface = new Vector3(), bed = new Vector3();
                        float base = BoardGeometry.weatherBase(scene);
                        for (int y = 2; y < HEIGHT - 2; y++) {
                            for (int x = 2; x < WIDTH - 2; x++) {
                                int i = y * WIDTH + x;
                                assertEquals(opaque.get(i), resultDepth.get(i), .0000001f,
                                      "Fog must preserve opaque depth for overlays and submerged units");
                                float depth = opaque.get(i), nearest = water.get(i);
                                int difference = rgbDifference(clear.getPixel(x, y), fog.getPixel(x, y));
                                if (nearest < depth && nearest < 1) {
                                    frontWater++;
                                    world(camera, surface, x, y, nearest);
                                    world(camera, bed, x, y, depth);
                                    // The surface lies inside the board; skip the visible board-edge water sections.
                                    if (surface.z < base - BoardGeometry.hexScale() - .05f * BoardGeometry.level()
                                          || surface.x < BoardGeometry.width()
                                          || surface.x > (scene.width() - 1) * BoardGeometry.width() * .75f
                                          || surface.y > -BoardGeometry.height()
                                          || surface.y < -(scene.height() - 1) * BoardGeometry.height()
                                          || bed.z >= surface.z - .1f * BoardGeometry.level()) { continue; }
                                    waterPixels++;
                                    if (difference <= 2) { unchangedWater++; }
                                } else if (depth < 1) {
                                    world(camera, surface, x, y, depth);
                                    if (surface.z < BoardGeometry.floor(scene) + BoardGeometry.level() * .2f) {
                                        plinthPixels++;
                                        if (difference > 2) { changedPlinth++; }
                                    }
                                }
                            }
                        }
                        String result = name + ": " + unchangedWater + "/" + waterPixels + " water pixels unfogged; "
                              + changedPlinth + "/" + plinthPixels + " bottom-plinth pixels fogged; " + frontWater
                              + " visible water pixels; baseline=" + base + "\n";
                        report.append(result);
                        if (waterPixels < 500 || unchangedWater > waterPixels / 500) { failures.add(result); }
                        if (plinthPixels < 100 || changedPlinth > Math.max(4, plinthPixels / 200)) { failures.add(result); }
                        PixmapIO.writePNG(new FileHandle(new File(output, name + "-clear.png")), clear, -1, true);
                        PixmapIO.writePNG(new FileHandle(new File(output, name + ".png")), fog, -1, true);
                    } finally {
                        clear.dispose();
                        fog.dispose();
                    }
                }
            }
            Files.writeString(new File(output, "water-fog-coverage.txt").toPath(), report);
            System.out.print(report);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            assertTrue(failures.isEmpty(), "Submerged shores must remain covered by fog:\n" + String.join("", failures));
        } finally {
            atmosphere.dispose();
            terrain.dispose();
        }
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
        return Pixmap.createFromFrameBuffer(0, 0, WIDTH, HEIGHT);
    }

    private static FloatBuffer readDepth(Texture texture) {
        FloatBuffer result = BufferUtils.newFloatBuffer(WIDTH * HEIGHT);
        texture.bind(0);
        GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, result);
        return result;
    }

    private static FloatBuffer readScreenDepth() {
        FloatBuffer result = BufferUtils.newFloatBuffer(WIDTH * HEIGHT);
        Gdx.gl.glReadPixels(0, 0, WIDTH, HEIGHT, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, result);
        return result;
    }

    private static void world(BoardCamera camera, Vector3 point, int x, int y, float depth) {
        point.set((x + .5f) / WIDTH * 2 - 1, (y + .5f) / HEIGHT * 2 - 1, depth * 2 - 1).prj(camera.camera.invProjectionView);
    }

    private static int rgbDifference(int a, int b) {
        return Math.abs((a >>> 24) - (b >>> 24)) + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255))
              + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
    }
}
