/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Tuning panel's Tessellation switch (GpuTerrain#setTessellation) at runtime, on Grassland 2's grassy banks: switched
 * on, the relief draws rounded; switched off again, the frame is the frame drawn before, bit for bit. Captures
 * tessellation-{off,on}.png. Skipped where the context has no tessellation stages (before OpenGL 4.0).
 */
@Tag("on-demand")
class GpuTessellationSmokeTest {
    @Test
    void switchesOnAndBackAtRuntime() throws Exception {
        BoardScene scene = GpuDepthPrepassBaselineSmokeTest.scene(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        AtomicBoolean available = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Files.createDirectories(GpuDepthPrepassBaselineSmokeTest.OUTPUT.toPath());
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 800);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(GpuDepthPrepassBaselineSmokeTest.NOON);
                var camera = new BoardCamera();
                try {
                    available.set(GpuTessellation.available());
                    if (!available.get()) { return; }
                    GpuDepthPrepassBaselineSmokeTest.prepare(terrain, frame, camera, scene);
                    // Close enough for full detail, the only chunks that tessellate.
                    camera.setIsometric(false);
                    camera.orbit(15, 55);
                    camera.camera.zoom = .09f;
                    camera.center(BoardGeometry.center(new Coords(12, 8), scene.tile(new Coords(12, 8)).elevation()));
                    byte[] off = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                          new File(GpuDepthPrepassBaselineSmokeTest.OUTPUT, "tessellation-off.png"));
                    // As the battle view does each frame: the switch, then the update that rebuilds for it.
                    terrain.setTessellation(true);
                    terrain.update(scene);
                    byte[] on = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene,
                          new File(GpuDepthPrepassBaselineSmokeTest.OUTPUT, "tessellation-on.png"));
                    terrain.setTessellation(false);
                    terrain.update(scene);
                    byte[] again = GpuAmbientOcclusionSmokeTest.draw(terrain, frame, camera, scene, null);
                    int changed = 0;
                    for (int i = 0; i < off.length; i += 4) {
                        if (Math.abs(off[i] - on[i]) + Math.abs(off[i + 1] - on[i + 1]) + Math.abs(off[i + 2] - on[i + 2]) > 6) {
                            changed++;
                        }
                    }
                    assertTrue(changed > off.length / 4 / 200, "Tessellated banks must draw rounded: " + changed + " pixels changed");
                    assertArrayEquals(off, again, "Switched off again, the frame must be the frame before it was on");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tessellation switch", failure.get()); }
        assumeTrue(available.get(), "No tessellation stages before OpenGL 4.0");
    }
}
