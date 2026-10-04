/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector3;
import megamek.common.game.Game;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real banks, including road, water and cliff junctions, in the same native scene from both cameras. */
@Tag("on-demand")
class GpuBankTurfSmokeTest {
    @Test
    void drawsGrasslandBanksWithoutStretchedJunctionTufts() throws Exception {
        var scenes = new ArrayList<BoardScene>();
        SwingUtilities.invokeAndWait(() -> {
            for (int number : new int[] { 2, 3 }) {
                var game = new Game();
                game.setBoard(BoardGroundCaptureTest.grassland(number));
                try (var source = new GpuMapSource(game, null, null)) { scenes.add(source.takeFrame().scene()); }
            }
        });
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 1000);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var camera = new BoardCamera();
                camera.resize(1600, 1000);
                var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "bank-turf");
                try {
                    assertTrue(output.isDirectory() || output.mkdirs());
                    for (int index = 0; index < scenes.size(); index++) {
                        var scene = scenes.get(index);
                        var tile = scene.tiles().stream().filter(t -> bankScore(scene, t) > 0)
                              .max(Comparator.comparingInt(t -> bankScore(scene, t))).orElseThrow();
                        var center = BoardGeometry.center(tile.coords(), tile.elevation());
                        Vector3 outward = null;
                        for (int direction = 0; direction < 6; direction++) {
                            var lower = scene.tile(tile.coords().translated(direction));
                            if (lower != null && !lower.liquid().present() && tile.elevation() - lower.elevation() == 2) {
                                outward = BoardGeometry.center(lower.coords(), tile.elevation()).sub(center).nor();
                                break;
                            }
                        }
                        assertNotNull(outward);
                        var target = center.cpy().mulAdd(outward, BoardGeometry.width() * .38f);
                        target.z -= BoardGeometry.level();
                        camera.camera.zoom = .145f;
                        camera.camera.near = 1;
                        camera.camera.far = 3000;
                        camera.camera.up.set(Vector3.Z);
                        camera.camera.position.set(target).mulAdd(outward, 650).add(0, 0, 350);
                        camera.camera.lookAt(target);
                        camera.camera.update();
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        for (int i = 0; i < 3; i++) { frame.render(terrain, camera, scene); }
                        Thread.sleep(600);
                        frame.render(terrain, camera, scene);
                        String name = "grassland" + (index + 2) + "-hex-" + tile.coords().getBoardNum();
                        GpuReviewFrame.save(new File(output, name + "-oblique.png"));
                        camera.camera.position.set(target).add(0, 0, 800);
                        camera.camera.up.set(0, 1, 0);
                        camera.camera.lookAt(target);
                        camera.camera.update();
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, name + "-top.png"));
                        var plants = terrain.planted(tile.coords());
                        assertNotNull(plants);
                        assertNotNull(plants.turf(), name + " must exercise visible turf");
                        checkUpload(plants);
                        int clumps = 0;
                        for (var candidate : scene.tiles()) {
                            var planted = terrain.planted(candidate.coords());
                            if (planted != null && planted.turf() != null) {
                                clumps += BoardGrassBankTest.checkTurf(planted.turf());
                            }
                        }
                        assertTrue(clumps > 30, "Exercise junctions across the real board");
                        System.out.println(name + ": checked " + clumps + " bounded turf clumps");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Native bank turf", failure.get()); }
    }

    private static int bankScore(BoardScene scene, BoardScene.Tile tile) {
        if (!BoardSurfaceBlend.natural(tile) || tile.surface() != BoardScene.Surface.GRASS
              || BoardBiome.kind(tile) != BoardScene.Biome.NONE) { return 0; }
        int banks = 0, junctions = 0;
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.tile(tile.coords().translated(direction));
            if (next == null) { continue; }
            if (!next.liquid().present() && tile.elevation() - next.elevation() == 2
                  && (tile.cliffTopExits() & (1 << direction)) == 0) { banks++; }
            if (next.liquid().present() || next.elevation() > tile.elevation()) { junctions++; }
        }
        return banks == 0 ? 0 : banks + 3 * junctions;
    }

    private static void checkUpload(BoardPlants plants) {
        var batch = new GpuBankTurf();
        var texture = new Texture(1, 1, Pixmap.Format.RGBA8888);
        try {
            batch.add(plants.turf());
            var drawn = batch.upload(texture);
            assertNotNull(drawn);
            assertEquals(plants.turf().size / GpuBankTurf.STRIDE, drawn.model.meshParts.first().size,
                  "Unindexed turf must submit vertices, not an empty index range");
            assertSame(drawn, batch.upload(texture), "Stationary frames reuse the same mesh");
            batch.begin();
            assertNull(batch.upload(texture), "An edit removing the bank removes its old turf");
        } finally { batch.dispose(); texture.dispose(); }
    }
}
