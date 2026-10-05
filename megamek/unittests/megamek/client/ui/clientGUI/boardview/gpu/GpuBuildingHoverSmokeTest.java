/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pointer routing and the visible column joining a building hit to its owning hex. */
@Tag("on-demand")
class GpuBuildingHoverSmokeTest {
    private static final Coords BUILDING = new Coords(3, 3);

    @Test
    void hoverFollowsBuildingHitWhileKeepingItsHex() throws Exception {
        Board board = new Board(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) { board.setHex(new Coords(x, y), new Hex(0)); }
        }
        board.setHex(BUILDING, new Hex(0, "building:2;bldg_elev:5;bldg_cf:40", ""));
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(board)) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;
                private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(2);

                @Override
                public void render() {
                    try {
                        assertTrue(System.nanoTime() < deadline, "Building hover fixture must finish");
                        super.render();
                        if (frames() == 0) { return; }
                        if (++tick == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.setPerspective(false);
                            boardCamera.camera.zoom = .4f;
                            boardCamera.center(BoardGeometry.center(BUILDING, 2));
                        } else {
                            checkHover(this);
                            if (tick == 2) { boardCamera.setPerspective(true); }
                            else { Gdx.app.exit(); }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Building hover column", failure.get()); }
    }

    private static void checkHover(GpuBattleView view) throws Exception {
        var scene = (BoardScene) field(view, "scene");
        var terrain = (GpuTerrain) field(view, "terrain");
        var ui = (GpuBoardUi) field(view, "ui");
        var camera = view.boardCamera.camera;
        checkRoofHover(view, terrain, scene, ui);
        checkCutaway(terrain);
        checkRenderedStoreys(view, terrain, ui);
        checkModularStoreys(view, ui);
        assertTrue(scene.tile(BUILDING).text().stream().anyMatch(label -> label.text().equals("HEIGHT 5")),
              "Captured HEIGHT text includes its space");
        Vector3 center = camera.project(BoardGeometry.center(BUILDING, 3), 0, ui.bottomPixels(),
              camera.viewportWidth, camera.viewportHeight);
        int x = Math.round(center.x);
        float lowest = Float.POSITIVE_INFINITY, highest = Float.NEGATIVE_INFINITY;
        int wallY = -1;
        Input original = Gdx.input;
        Input pointer = mock(Input.class);
        Gdx.input = pointer;
        try {
            for (int y = Gdx.graphics.getHeight() - Math.round(center.y) + 100;
                  y > Gdx.graphics.getHeight() - Math.round(center.y) - 150; y -= 10) {
                if (ui.hit(x, y)) { continue; }
                var ray = camera.getPickRay(x, y, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                var hit = terrain.selectionHit(scene, ray);
                if (hit == null || !BUILDING.equals(hit.coords())) { continue; }
                float z = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance())).z;
                if (z < BoardGeometry.level()) { continue; }
                when(pointer.getX()).thenReturn(x);
                when(pointer.getY()).thenReturn(y);
                original.getInputProcessor().mouseMoved(x, y);
                assertEquals(BUILDING, field(view, "hovered"));
                assertEquals(z, (float) field(view, "hoverZ"), .001f,
                      "Moving within one hex must update the hover height from the same picking ray");
                lowest = Math.min(lowest, z);
                highest = Math.max(highest, z);
                float level = z / BoardGeometry.level();
                if (Math.abs(level - Math.round(level)) > .25f) { wallY = y; }
            }
            assertTrue(highest - lowest > BoardGeometry.level(), "The pointer sweep must cover wall hits at different heights");
            assertTrue(wallY >= 0, "The render check must use a wall hit well between floors, not an already-aligned roof");
            when(pointer.getY()).thenReturn(wallY);
            original.getInputProcessor().mouseMoved(x, wallY);
            var draw = GpuBattleView.class.getDeclaredMethod("renderSelectionOutlines");
            draw.setAccessible(true);
            HdpiUtils.glViewport(0, ui.bottomPixels(), (int) camera.viewportWidth, (int) camera.viewportHeight);
            ScreenUtils.clear(0, 0, 0, 1, true);
            draw.invoke(view);
            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            try {
                float base = BoardTacticalGeometry.floatingZ(scene, BUILDING);
                float level = (float) Math.floor((float) field(view, "hoverZ") / BoardGeometry.level() + .0001f);
                float top = level * BoardGeometry.level() + .5f * BoardGeometry.hexScale();
                Vector3 origin = BoardGeometry.center(BUILDING, 0);
                int brightEdges = 0, faintEdges = 0, verticals = 0;
                for (int edge = 0; edge < 6; edge++) {
                    Vector3 a = BoardGeometry.inset(BoardGeometry.corner(BUILDING, 0, edge), origin, GpuBattleView.HOVER_HEX_INSET);
                    Vector3 b = BoardGeometry.inset(BoardGeometry.corner(BUILDING, 0, edge + 1), origin, GpuBattleView.HOVER_HEX_INSET);
                    Vector3 midpoint = a.cpy().lerp(b, .5f);
                    if (brightness(pixels, view, ui, midpoint.cpy().add(0, 0, top)) > 240) { brightEdges++; }
                    int baseBrightness = brightness(pixels, view, ui, midpoint.cpy().add(0, 0, base));
                    if (baseBrightness > 40 && baseBrightness < 100) { faintEdges++; }
                    int verticalBrightness = brightness(pixels, view, ui, a.cpy().add(0, 0, (base + top) / 2));
                    if (verticalBrightness > 40 && verticalBrightness < 100) { verticals++; }
                }
                assertEquals(6, brightEdges, "All upper hex edges snap to the supporting floor at full hover brightness");
                assertEquals(6, faintEdges, "All base edges are faint");
                assertEquals(6, verticals, "All six corners connect to the base with faint lines");
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            } finally { pixels.dispose(); }
        } finally { Gdx.input = original; }
    }

    private static void checkRoofHover(GpuBattleView view, GpuTerrain terrain, BoardScene scene, GpuBoardUi ui) throws Exception {
        var camera = view.boardCamera.camera;
        var bounds = terrain.roofBounds(BUILDING);
        float roofZ = (scene.tile(BUILDING).elevation() + 5) * BoardGeometry.level();
        Input original = Gdx.input;
        Input pointer = mock(Input.class);
        Gdx.input = pointer;
        var floor = GpuBattleView.class.getDeclaredMethod("hoverFloorZ");
        floor.setAccessible(true);
        int checked = 0, wrong = 0;
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        try {
            for (int dx = 1; dx < 12; dx++) {
                for (int dy = 1; dy < 12; dy++) {
                    var vertical = new Ray(new Vector3(bounds.min.x + bounds.getWidth() * dx / 12,
                          bounds.min.y + bounds.getHeight() * dy / 12, bounds.max.z + 100), new Vector3(0, 0, -1));
                    var roof = terrain.selectionHit(scene, vertical);
                    if (roof == null || !BUILDING.equals(roof.coords())) { continue; }
                    Vector3 point = vertical.getEndPoint(new Vector3(), (float) Math.sqrt(roof.distance()));
                    if (point.z < roofZ - BoardGeometry.level() / 2) { continue; }
                    var screen = camera.project(point.cpy(), 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                    int x = Math.round(screen.x), y = Gdx.graphics.getHeight() - Math.round(screen.y);
                    var ray = camera.getPickRay(x, y, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                    var hit = terrain.selectionHit(scene, ray);
                    if (hit == null || !BUILDING.equals(hit.coords())) { continue; }
                    Vector3 visible = ray.getEndPoint(new Vector3(), (float) Math.sqrt(hit.distance()));
                    if (visible.dst2(point) > 4) { continue; }
                    when(pointer.getX()).thenReturn(x);
                    when(pointer.getY()).thenReturn(y);
                    original.getInputProcessor().mouseMoved(x, y);
                    float hover = (float) field(view, "hoverZ");
                    low = Math.min(low, hover); high = Math.max(high, hover);
                    checked++;
                    if ((float) floor.invoke(view) != roofZ) { wrong++; }
                }
            }
            System.out.println("ROOF hover samples=" + checked + " wrong=" + wrong + " range=" + low + ".." + high + " nominal=" + roofZ);
            assertTrue(checked > 20, "Sweep must cover the visible roof");
            assertEquals(0, wrong, "All visible roof points must select the roof, including near its edge");
        } finally { Gdx.input = original; }
    }

    private static void checkCutaway(GpuTerrain terrain) throws Exception {
        var floors = GpuModularBuildingSmokeTest.floors(terrain, BUILDING);
        assertEquals(5, floors.size());
        for (int level : new int[] { 0, 2, 4, 5, 1 }) {
            terrain.animate(0, List.of(), level == 1 ? 1 : .5f, BUILDING, level * BoardGeometry.level());
            GpuModularBuildingSmokeTest.checkInteriorCache(terrain, level < 5 && level != 1);
            for (int floor = 0; floor < floors.size(); floor++) {
                assertFalse(
                      floors.get(floor).materials.first().has(BlendingAttribute.Type),
                      "Interior hover reveals its floor; roof hover leaves the whole building opaque");
            }
        }
        var unitModel = new ModelBuilder().createBox(3, 3, 3,
              new Material(), VertexAttributes.Usage.Position);
        try {
            var unit = new ModelInstance(unitModel,
                  BoardGeometry.center(BUILDING, 4).add(0, 0, 2));
            for (boolean occupied : new boolean[] { false, true, false, true }) {
                terrain.animate(0, occupied ? List.of(unit) : List.of(), .5f,
                      BUILDING, 2 * BoardGeometry.level());
                for (Object chunk : (List<?>) field(terrain, "chunks")) {
                    for (Object prop : (List<?>) field(chunk, "cutaways")) {
                        if (!BUILDING.equals(field(prop, "coords")) || !Float.isNaN((float) field(prop, "floorZ"))) { continue; }
                        assertEquals(!occupied, field(prop, "hoverOpaque") != null,
                              "Unit arrival/departure must restore the correct opacity outside the hover opening");
                        var shell = (ModelInstance) field(prop, "instance");
                        for (Material material : shell.materials) {
                            assertEquals(.5f, material.get(BlendingAttribute.class, BlendingAttribute.Type).opacity,
                                  "Hover and unit cutaways share the chosen wall opacity");
                            var clip = material.get(GpuBuildingCutaway.class, GpuBuildingCutaway.TYPE);
                            assertTrue(occupied ? clip == null : clip != null && clip.inside,
                                  "Hover fades only its storey; unit occupancy fades the whole shell");
                        }
                    }
                }
            }
        } finally { unitModel.dispose(); }
        terrain.animate(0, List.of(), .5f, null, Float.NaN);
        GpuModularBuildingSmokeTest.checkInteriorCache(terrain, false);
    }

    private static void checkModularStoreys(GpuBattleView view, GpuBoardUi ui) throws Exception {
        BoardScene scene = BoardSurfaceBlendTest.scene(coords -> {
            var tile = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, 0, -1, 0);
            var hex = new Hex(0, "building:2;bldg_elev:5;bldg_cf:40", "");
            return new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
                  coords.equals(BUILDING) ? BoardFeatures.capture(hex, coords,
                        Map.of(Terrains.BUILDING, GpuBuildingTest.ASSET)) : List.of(),
                  List.of(), BoardLiquid.NONE, null, true);
        });
        var terrain = new GpuTerrain();
        try {
            terrain.update(scene);
            checkCutaway(terrain);
            checkRenderedStoreys(view, terrain, ui);
        } finally { terrain.dispose(); }
    }

    private static void checkRenderedStoreys(GpuBattleView view, GpuTerrain terrain, GpuBoardUi ui) throws Exception {
        var camera = view.boardCamera.camera;
        HdpiUtils.glViewport(0, ui.bottomPixels(), (int) camera.viewportWidth, (int) camera.viewportHeight);
        ScreenUtils.clear(0, 0, 0, 1, true);
        terrain.render(camera, false);
        var pages = (GpuPropBatch) field(terrain, "propBatch");
        long pageBuilds = pages.rebuilds();
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var buildingBounds = terrain.roofBounds(BUILDING);
        Pixmap baseline = Pixmap.createFromFrameBuffer(0, 0, width, height);
        var depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        try {
            for (int level : new int[] { 2, 4, 0, 5, 1, 2 }) {
                terrain.animate(0, List.of(), level == 1 ? 1 : .5f, BUILDING, level * BoardGeometry.level());
                ScreenUtils.clear(0, 0, 0, 1, true);
                terrain.render(camera, false);
                assertEquals(pageBuilds, pages.rebuilds(),
                      "Opening/closing a building storey must not rebuild static prop pages");
                Pixmap hiddenWalls = Pixmap.createFromFrameBuffer(0, 0, width, height);
                terrain.renderTransparent(camera);
                Pixmap opened = Pixmap.createFromFrameBuffer(0, 0, width, height);
                try {
                    int changedInside = 0, visibleWalls = 0, unchangedOutside = 0, changedOutside = 0;
                    String outsideSample = "";
                    for (int x = 0; x < width; x += 4) {
                        for (int y = ui.bottomPixels(); y < ui.bottomPixels() + camera.viewportHeight; y += 4) {
                            Vector3 point = new Vector3((x + .5f) / camera.viewportWidth * 2 - 1,
                                  (y + .5f - ui.bottomPixels()) / camera.viewportHeight * 2 - 1,
                                  depth.get(y * width + x) * 2 - 1).prj(camera.invProjectionView);
                            if (!buildingBounds.contains(point) || point.z <= buildingBounds.min.z + .01f) { continue; }
                            float z = point.z / BoardGeometry.level();
                            if (Math.abs(z - level) < .04f || Math.abs(z - level - 1) < .04f) { continue; }
                            int a = baseline.getPixel(x, y), b = opened.getPixel(x, y);
                            int difference = Math.abs((a >>> 24) - (b >>> 24))
                                  + Math.abs((a >>> 16 & 255) - (b >>> 16 & 255)) + Math.abs((a >>> 8 & 255) - (b >>> 8 & 255));
                            if (level < 5 && level != 1 && z > level && z < level + 1) {
                                if (difference > 10) { changedInside++; }
                                int hidden = hiddenWalls.getPixel(x, y);
                                int wallContribution = Math.abs((hidden >>> 24) - (b >>> 24))
                                      + Math.abs((hidden >>> 16 & 255) - (b >>> 16 & 255))
                                      + Math.abs((hidden >>> 8 & 255) - (b >>> 8 & 255));
                                if (wallContribution > 10) { visibleWalls++; }
                            } else {
                                if (difference > 10) {
                                    changedOutside++;
                                    outsideSample = " at pixel " + x + "," + y + " world=" + point + " delta=" + difference;
                                }
                                else { unchangedOutside++; }
                            }
                        }
                    }
                    if (level == 2) {
                        PixmapIO.writePNG(new FileHandle(
                              "build/gpu-board-review/hover-floor-" + (camera.projection.val[Matrix4.M33] == 0) + ".png"), opened, -1, true);
                    }
                    assertTrue(unchangedOutside > 100, "Other storeys and roof must remain visible");
                    assertEquals(0, changedOutside, "Hover must not alter walls outside the highlighted storey: " + level + outsideSample);
                    if (level < 5 && level != 1) {
                        assertTrue(changedInside > 10, "The highlighted storey's walls must become translucent: " + level);
                        assertTrue(visibleWalls > 10, "Hovered walls must remain visible rather than disappearing: " + level);
                    }
                    if (level == 2) { checkDepth(view, terrain, ui); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } finally { opened.dispose(); hiddenWalls.dispose(); }
            }
        } finally {
            baseline.dispose();
            terrain.animate(0, List.of(), .5f, null, Float.NaN);
        }
    }

    private static void checkDepth(GpuBattleView view, GpuTerrain terrain, GpuBoardUi ui) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        var buildingBounds = terrain.roofBounds(BUILDING);
        var colorDepth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, colorDepth);
        var config = new DepthShader.Config();
        config.depthBufferOnly = true;
        config.defaultCullFace = GL20.GL_BACK;
        var pass = new ModelBatch(GpuTreeInstances.depthProvider(config));
        try {
            Gdx.gl.glDepthMask(true);
            ScreenUtils.clear(0, 0, 0, 1, true);
            terrain.renderDepth(view.boardCamera.camera, List.of(), pass);
            var depth = BufferUtils.newFloatBuffer(width * height);
            Gdx.gl.glReadPixels(0, 0, width, height, GL30.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
            int checked = 0;
            for (int x = 450; x < 750; x += 4) {
                for (int y = 350; y < 550; y += 4) {
                    var camera = view.boardCamera.camera;
                    Vector3 point = new Vector3((x + .5f) / camera.viewportWidth * 2 - 1,
                          (y + .5f - ui.bottomPixels()) / camera.viewportHeight * 2 - 1,
                          colorDepth.get(y * width + x) * 2 - 1).prj(camera.invProjectionView);
                    // Ground exposed through the opening uses surface displacement only in the colour pass.
                    if (!buildingBounds.contains(point)
                          || point.z < 2 * BoardGeometry.level() - .1f * BoardGeometry.hexScale()) { continue; }
                    checked++;
                    assertEquals(colorDepth.get(y * width + x), depth.get(y * width + x), .00001f,
                          "Depth must preserve the storey opening at " + point);
                }
            }
            assertTrue(checked > 50, "Depth comparison must cover the building's visible surfaces");
        } finally { pass.dispose(); }
    }

    private static int brightness(Pixmap pixels, GpuBattleView view, GpuBoardUi ui, Vector3 world) {
        var camera = view.boardCamera.camera;
        Vector3 screen = camera.project(world, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
        int result = 0;
        for (int x = Math.round(screen.x) - 2; x <= Math.round(screen.x) + 2; x++) {
            for (int y = Math.round(screen.y) - 2; y <= Math.round(screen.y) + 2; y++) {
                result = Math.max(result, pixels.getPixel(x, y) >>> 24);
            }
        }
        return result;
    }
}
