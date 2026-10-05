/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Sculpted slopes retain continuous LOS shading, and visibility snapshots never rebuild their terrain. */
@Tag("on-demand")
class GpuFieldOfViewSurfaceSmokeTest {
    @Test
    void shadingFollowsSculptedTerrainAndVisibilityChangesReuseItsMeshes() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(960, 640);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { checkSurface(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Terrain-following LOS", failure.get()); }
    }

    private static void checkSurface() throws Exception {
        Coords raised = new Coords(2, 2);
        BoardScene scene = BoardPlaneConnectionsTest.scene(raised, 4);
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuFieldOfView field = new GpuFieldOfView();
        BoardCamera camera = new BoardCamera();
        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.setIsometric(true);
        camera.fit(scene);
        camera.center(BoardGeometry.center(raised, 2));
        camera.zoom(.4f);
        BoardFieldOfView hidden = new BoardFieldOfView(scene.width(), scene.height(), scene.tiles().stream().map(tile ->
              new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.BLOCKED, 0)).toList(), 255, 0, true, false, false);
        try {
            field.configure(GpuFieldOfView.Style.GRAYSCALE, .5f, GpuFieldOfView.Style.GRAYSCALE, .5f);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            terrain.update(scene);
            field.update(hidden);
            Object generation = member(terrain, "meshGeneration");
            List<?> chunks = List.copyOf((List<?>) member(terrain, "chunks"));
            Object texture = member(field, "mask");
            assertFalse(chunks.isEmpty());
            int checked = 0;
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (int angle = 0; angle < 12; angle++) {
                    camera.orbit(30, 0);
                    BoardFieldOfView preferences = new BoardFieldOfView(hidden.width(), hidden.height(), hidden.hexes(),
                          angle % 2 == 0 ? 255 : 120, angle * 10, true, false, angle % 2 == 0);
                    update(scene, terrain, field, camera, preferences, generation, chunks);
                    ScreenUtils.clear(0, 0, 0, 1, true);
                    atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
                    terrain.render(camera.camera, false);
                    atmosphere.end(camera.camera, terrain, scene, 0, field);
                    if (angle % 3 == 0) {
                        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                              "fov-surface-" + perspective + "-" + angle + ".png"));
                    }
                    int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
                    Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
                    FloatBuffer depths = BufferUtils.newFloatBuffer(width * height);
                    Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depths);
                    try {
                        for (int x = 1; x < width; x += 4) {
                            for (int y = 1; y < height; y += 4) {
                                Vector3 surface = new Vector3((x + .5f) / width * 2 - 1, (y + .5f) / height * 2 - 1,
                                      depths.get(y * width + x) * 2 - 1).prj(camera.camera.invProjectionView);
                                var owner = BoardGeometry.tile(scene, surface.x, surface.y);
                                if (owner == null || owner.coords().distance(raised) > 1 || surface.z < 0) { continue; }
                                checked++;
                                int rgb = pixels.getPixel(x, y);
                                assertEquals(rgb >>> 24, rgb >>> 16 & 255, 1, "No gaps in shaded slopes at " + surface);
                                assertEquals(rgb >>> 24, rgb >>> 8 & 255, 1, "No gaps in shaded slopes at " + surface);
                            }
                        }
                    } finally { pixels.dispose(); }
                }
            }
            assertTrue(checked > 10_000, "Sample the raised hex and its sloped neighbors in both projections");
            assertEquals(1, field.uploads(), "Orbits and appearance changes reuse the visibility texture");
            assertSame(texture, member(field, "mask"));

            BoardFieldOfView mixed = new BoardFieldOfView(scene.width(), scene.height(), scene.tiles().stream().map(tile ->
                  tile.coords().equals(raised) ? BoardFieldOfView.Hex.VISIBLE
                        : new BoardFieldOfView.Hex(BoardFieldOfView.Visibility.SENSOR, 0, true)).toList(),
                  255, 0, true, false, false);
            update(scene, terrain, field, camera, mixed, generation, chunks);
            assertEquals(2, field.uploads(), "Changed LOS and sensor classifications upload only the small mask");
            assertSame(texture, member(field, "mask"), "Same-size masks update the existing texture");
            update(scene, terrain, field, camera, BoardFieldOfView.EMPTY, generation, chunks);
            assertFalse(field.active());
            assertEquals(2, field.uploads(), "Disabling LOS requires no upload");
            update(scene, terrain, field, camera, hidden, generation, chunks);
            assertTrue(field.active());
            assertEquals(3, field.uploads());
            assertSame(texture, member(field, "mask"));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            field.dispose();
            atmosphere.dispose();
            terrain.dispose();
        }
    }

    private static void update(BoardScene scene, GpuTerrain terrain, GpuFieldOfView field, BoardCamera camera,
          BoardFieldOfView mask, Object generation, List<?> chunks) throws Exception {
        BoardScene next = new BoardScene(scene.boardId(), scene.width(), scene.height(), scene.tiles(), scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(), scene.firingLines(),
              scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(), mask);
        terrain.update(next, camera.camera);
        field.update(next.fieldOfView());
        assertEquals(generation, member(terrain, "meshGeneration"), "LOS must not request a terrain rebuild");
        assertFalse(terrain.busy(), "LOS updates must not schedule terrain work");
        List<?> current = (List<?>) member(terrain, "chunks");
        assertEquals(chunks.size(), current.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertSame(chunks.get(i), current.get(i), "LOS must retain the installed terrain chunks");
        }
    }

    private static Object member(Object owner, String name) throws ReflectiveOperationException {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }
}
