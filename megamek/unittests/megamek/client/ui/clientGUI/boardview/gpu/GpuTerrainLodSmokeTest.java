/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

/** Real GL ownership across asynchronous detail changes, cache reuse, and an edit superseding an in-flight job. */
@Tag("on-demand")
class GpuTerrainLodSmokeTest {
    @Test
    void zoomRestoresDetailAndEditsDiscardObsoletePreparation() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuTactical tactical = new GpuTactical(terrain::tacticalSurface);
                boolean previousLod = TerrainLod.enabled();
                try {
                    TerrainLod.setEnabled(true);
                    verifiesPreparedWaterTextureUpload();
                    BoardScene scene = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.SAND);
                    Coords at = new Coords(4, 4);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(1280, 900);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    view(camera, 30);
                    terrain.update(scene, camera.camera);
                    var distant = terrain.tacticalSurface(at);
                    assertEquals(TerrainLod.DISTANT, firstLod(terrain));

                    view(camera, .6f);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.FULL, firstLod(terrain));
                    assertNotSame(distant, terrain.tacticalSurface(at));
                    capture(terrain, camera, "terrain-lod-close.png");
                    var close = terrain.tacticalSurface(at);
                    assertTrue(!terrain.refine(camera.camera));
                    assertSame(close, terrain.tacticalSurface(at), "A settled camera must not rebuild meshes");

                    view(camera, 30);
                    settle(terrain, tactical, scene, camera);
                    assertSame(distant, terrain.tacticalSurface(at), "Returning to cached detail reuses its installed geometry");

                    // Toggling detail at a fixed camera uses the same incremental/cached path as zooming.
                    int revision = BoardGeometry.terrainRevision();
                    TerrainLod.setEnabled(false);
                    settle(terrain, tactical, scene, camera);
                    assertEquals(TerrainLod.FULL, firstLod(terrain));
                    assertSame(close, terrain.tacticalSurface(at));
                    TerrainLod.setEnabled(true);
                    settle(terrain, tactical, scene, camera);
                    assertSame(distant, terrain.tacticalSurface(at));
                    assertEquals(revision, BoardGeometry.terrainRevision());

                    // Request an uncached tier, then edit before that CPU preparation is installed.
                    view(camera, 2.5f);
                    terrain.refine(camera.camera);
                    assertTrue(field(terrain, "detailJob") != null);
                    List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                    BoardScene.Tile before = scene.tile(at);
                    tiles.set(tiles.indexOf(before), new BoardScene.Tile(at, before.elevation() + 2, -1, false, 0,
                          before.surface(), before.ground(), null, null, null, null, List.of(), List.of(),
                          BoardLiquid.NONE, null, true));
                    BoardScene edited = new BoardScene(0, scene.width(), scene.height(), tiles, List.of(), List.of(), -1, "", List.of());
                    terrain.update(edited, camera.camera);
                    var installed = terrain.tacticalSurface(at);
                    settle(terrain, tactical, edited, camera);
                    assertSame(installed, terrain.tacticalSurface(at), "The stale job must not overwrite the edit");
                    Vector3 center = BoardGeometry.center(at, before.elevation() + 2);
                    double height = terrain.tacticalSurface(at).top().stream()
                          .mapToDouble(face -> face.height(center.x, center.y)).max().orElseThrow();
                    assertTrue(Math.abs(height - center.z) < BoardRelief.metres(.3f));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    TerrainLod.setEnabled(previousLod);
                    tactical.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain detail lifecycle", failure.get()); }
    }

    private static void view(BoardCamera camera, float zoom) {
        camera.camera.zoom = zoom;
        camera.center(BoardGeometry.center(new Coords(4, 4), 1));
    }

    private static void verifiesPreparedWaterTextureUpload() {
        byte[] rgba = { -1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, 17, 29, 41, -1 };
        var field = new GpuWaterShader.Field.Prepared(2, 2, 1, 0, 0, rgba, null).upload();
        try {
            field.texture.bind();
            var pixels = BufferUtils.newByteBuffer(rgba.length);
            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
            byte[] actual = new byte[rgba.length];
            pixels.get(actual);
            assertArrayEquals(rgba, actual, "Uploading prepared field pixels must start at the buffer's first byte");
        } finally { field.dispose(); }
    }

    private static void settle(GpuTerrain terrain, GpuTactical tactical, BoardScene scene, BoardCamera camera) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        double maximum = 0;
        while (true) {
            long start = System.nanoTime();
            boolean changed = terrain.refine(camera.camera);
            double elapsed = (System.nanoTime() - start) / 1e6;
            maximum = Math.max(maximum, elapsed);
            if (changed) { System.out.printf("LOD install %.3f ms%n", elapsed); }
            tactical.update(scene, changed);
            if (!changed && field(terrain, "detailJob") == null) {
                System.out.printf("LOD maximum render-thread handoff %.3f ms%n", maximum);
                return;
            }
            assertTrue(System.nanoTime() < deadline, "Visible terrain detail must settle");
            Thread.sleep(5);
        }
    }

    private static TerrainLod firstLod(GpuTerrain terrain) throws Exception {
        return (TerrainLod) field(((List<?>) field(terrain, "chunks")).getFirst(), "lod");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void capture(GpuTerrain terrain, BoardCamera camera, String name) throws Exception {
        terrain.renderShadows(camera.camera, List.of());
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(.2f, .26f, .31f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        GpuBoardTestUi.capture(new File(output, name));
    }
}
