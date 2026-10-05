/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Rendered slope, picking and edit integration using the same installed bridge geometry. */
@Tag("on-demand")
class GpuBridgeSlopeSmokeTest {
    @Test
    void steppedSpansRenderAndPickTheirSlopesAfterAnElevationEdit() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots"), "bridge-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var at = new Coords(4, TerrainLod.CHUNK_SIZE - 1);
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.camera.zoom = .19f;
                    camera.orbit(70, 15);
                    camera.center(BoardGeometry.center(at, 1));
                    for (int exits : new int[] { 9, 63 }) {
                        var scene = BoardBridgeSlopeTest.scene(at, exits, true);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "manufactured-" + exits + ".png"));
                        float lift = GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
                        var p = BoardGeometry.center(at, 2).lerp(BoardGeometry.center(at.translated(3), 2), .4f);
                        float height = 1.7f * BoardGeometry.level() + lift;
                        assertPick(terrain, scene, at, p, height);
                        var edited = BoardNaturalBridgeTest.replace(scene,
                              BoardBridgeSlopeTest.bridge(at.translated(3), -2, 6, 9));
                        terrain.update(edited);
                        GpuTerrainLodSmokeTest.settle(terrain, null, edited, camera);
                        assertPick(terrain, edited, at, p, 2 * BoardGeometry.level() + lift);
                    }
                    var natural = BoardBridgeSlopeTest.scene(at, 9, false);
                    frame.prepare(terrain, camera, natural);
                    terrain.update(natural);
                    GpuTerrainLodSmokeTest.settle(terrain, null, natural, camera);
                    frame.render(terrain, camera, natural);
                    GpuReviewFrame.save(new File(output, "natural.png"));
                    assertPick(terrain, natural, at, BoardGeometry.center(at, 2), 2 * BoardGeometry.level());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    frame.dispose(); terrain.dispose(); Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Stepped bridge rendering and picking", failure.get()); }
    }

    private static void assertPick(GpuTerrain terrain, BoardScene scene, Coords at, Vector3 p, float height) {
        var ray = new Ray(new Vector3(p.x, p.y, height + 20), new Vector3(0, 0, -1));
        var hit = terrain.hit(scene, ray);
        assertNotNull(hit);
        assertEquals(at, hit.coords());
        assertEquals(400, hit.distance(), .05f, "Picking must hit the drawn slope, including after edits");
        assertNotNull(terrain.roofBounds(at));
    }
}
