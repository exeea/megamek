/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.renderReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual tileset artwork on plain columns, flat placement, input and reversible camera switching. */
@Tag("on-demand")
class GpuOverviewIconsSmokeTest {
    @Test
    void cameraToggleReplacesUnitsAndTerrainWithoutResettingPoses() throws Exception {
        Hex[] hexes = new Hex[12 * 12];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                Hex hex = new Hex(x >= 8 ? 1 : 0);
                hex.setTheme(x >= 8 ? "snow" : "grass");
                if (y >= 3 && y <= 7 && x % 3 != 0) {
                    hex.addTerrain(new Terrain(x < 6 ? Terrains.WOODS : Terrains.JUNGLE, 1 + x % 3));
                    hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                }
                hexes[y * 12 + x] = hex;
            }
        }
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(new Board(12, 12, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.entity.setFacing(2);
                fixture.entity.setSecondaryFacing(2);
                fixture.source.refresh();
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        renderReady(view);
                        verify(view, fixture);
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Overview icons failed", failure.get()); }
            assertEquals(0, fixture.clicks.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        var icons = (GpuUnitIcons) field(view, "unitIcons");
        var terrain = (GpuTerrain) field(view, "terrain");
        var ui = (GpuBoardUi) field(view, "ui");
        var scene = (BoardScene) field(view, "scene");
        var unit = scene.units().getFirst();
        var models = (Map<String, ModelInstance>) field(view, "unitInstances");
        var original = models.get("1:-1");
        assertNotNull(original);
        for (var tile : scene.tiles()) { assertNotNull(tile.tileset(), "Every hex carries its whole tileset art"); }
        verifyTilesetArt(scene);
        view.boardCamera.setIsometric(true);
        view.boardCamera.zoom(2 / view.boardCamera.camera.zoom);
        renderReady(view);
        assertFalse(icons.active(), "The option is opt-in");
        GpuBoardTestUi.click("camera");
        GpuBoardTestUi.click("camera-overview-icons");
        assertTrue(ui.overviewIcons());
        assertTrue(GpuBoardTestUi.stage().getRoot().<CheckBox>findActor("tuning-overview-icons").isChecked());
        GpuBoardTestUi.click("camera");
        renderReady(view);
        assertTrue(view.boardCamera.isTopDown(), "Entering the Tactical View switches to the top view");
        assertTrue(icons.active());
        assertTrue((boolean) field(terrain, "tacticalView"));
        for (var face : terrain.tacticalSurface(unit.location().coords()).top()) {
            assertEquals(BoardGeometry.surfaceZ(scene.tile(unit.location().coords())), face.a().z, .0001f,
                  "Overlays drape on the flat column the view draws");
        }
        assertSame(original, models.get("1:-1"), "Camera switches preserve the animated 3D instance");
        var icon = icons.instance(unit);
        assertNotNull(icon);
        assertEquals(0, UnitBounds.local(icon).getDepth(), .0001f);
        assertEquals(1, new Vector3(Vector3.Z).rot(icon.transform).nor().z, .0001f);
        assertEquals(0.25f * BoardGeometry.HEX_SCALE, icon.transform.getTranslation(new Vector3()).z, .0001f);
        capture("overview-icons-top.png");

        view.boardCamera.orbit(75, GpuTactical.FLAT_TILT_DEGREES - 1);
        renderReady(view);
        assertTrue(icons.active());
        assertEquals(1, new Vector3(Vector3.Z).rot(icon.transform).nor().z, .0001f,
              "The icon lies on the board instead of following the camera tilt");
        Vector3 point = view.boardCamera.camera.project(icon.transform.getTranslation(new Vector3()), 0, ui.bottomPixels(),
              view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pick", int.class, int.class);
        pick.setAccessible(true);
        assertEquals(unit.location().coords(), pick.invoke(input, Math.round(point.x), Gdx.graphics.getHeight() - Math.round(point.y)));
        capture("overview-icons-tilted.png");

        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var moved = new HashMap<>(poses);
        var position = poses.get(unit).position().cpy().add(12, -9, 200);
        moved.put(unit, new UnitFootprint.Pose(unit, position, 240));
        var anchors = new HashMap<BoardScene.Unit, Vector3>();
        icons.update(true, 56, view.boardCamera.camera, scene, moved, anchors);
        assertEquals(position.x, icon.transform.getTranslation(new Vector3()).x, .0001f);
        assertEquals(position.y, icon.transform.getTranslation(new Vector3()).y, .0001f);
        assertTrue(icon.transform.getTranslation(new Vector3()).z < BoardGeometry.LEVEL,
              "Even airborne animation poses project onto the hex surface");

        view.boardCamera.orbit(0, 30);
        renderReady(view);
        assertFalse(icons.active(), "Beyond the top view the units show their models");
        assertTrue((boolean) field(terrain, "tacticalView"), "The terrain keeps the Tactical View at any tilt");
        assertFalse(((List<?>) field(terrain, "shadowModels")).isEmpty(), "The models shadow the columns");
        assertSame(original, models.get("1:-1"));
        capture("tactical-models-oblique.png");

        view.boardCamera.setIsometric(false);
        view.boardCamera.zoom(.6f / view.boardCamera.camera.zoom);
        renderReady(view);
        assertTrue(icons.active(), "The default tactical policy has no zoom cutoff");
        view.boardCamera.zoom(2 / view.boardCamera.camera.zoom);
        renderReady(view);
        assertTrue(icons.active());
        GpuBoardTestUi.click("tuning");
        Slider threshold = GpuBoardTestUi.stage().getRoot().findActor("Icon switch hex px");
        threshold.setValue(56);
        renderReady(view);
        assertTrue(icons.active());
        view.boardCamera.zoom(.6f / view.boardCamera.camera.zoom);
        renderReady(view);
        assertFalse(icons.active(), "A finite zoom cutoff keeps full models at close range");
        assertTrue((boolean) field(terrain, "tacticalView"), "The terrain keeps the Tactical View at any zoom");
        view.boardCamera.zoom(2 / view.boardCamera.camera.zoom);
        renderReady(view);
        assertTrue(icons.active());
        threshold.setValue(24);
        renderReady(view);
        assertFalse(icons.active());
        threshold.setValue(90);
        renderReady(view);
        assertTrue(icons.active());
        GpuBoardTestUi.click("tuning-defaults");
        renderReady(view);
        assertFalse(icons.active());
        assertFalse((boolean) field(terrain, "tacticalView"));
        assertFalse(((List<?>) field(terrain, "shadowModels")).isEmpty(), "The 3D view's units cast shadows again");
        assertEquals(GpuUnitIcons.DEFAULT_HEX_PIXELS, ui.overviewHexPixels());
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(new Coords(5, 5), fixture.entity.getPosition());
            assertEquals(2, fixture.entity.getFacing());
        });
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }

    /**
     * The Tactical View draws the hex's own tileset art on its column's top, lit and shadowed like the 3D terrain:
     * clear noon light gives level ground its albedo (BoardAtmosphere), and the column does not shadow its own top.
     */
    private static void verifyTilesetArt(BoardScene scene) {
        var coords = new Coords(0, 0);
        var source = scene.tile(coords);
        var art = source.tileset();
        var column = new BoardScene.Tile(coords, 0, -1, false, 0, source.surface(), source.ground(),
              null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, art);
        var isolated = new BoardScene(0, 1, 1, List.of(column), List.of(), List.of(), -1, "", List.of());
        var terrain = new GpuTerrain();
        var target = new FrameBuffer(Pixmap.Format.RGBA8888, art.width(), art.height(), true);
        var camera = new OrthographicCamera(BoardGeometry.WIDTH, BoardGeometry.HEIGHT);
        camera.position.set(BoardGeometry.center(coords, 0)).add(0, 0, 50);
        camera.direction.set(0, 0, -1);
        camera.up.set(0, 1, 0);
        camera.update();
        Pixmap rendered = null;
        try {
            terrain.update(isolated);
            terrain.setTacticalView(true);
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(12, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.renderShadows(List.of());
            target.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            terrain.render(camera, false);
            rendered = Pixmap.createFromFrameBuffer(0, 0, art.width(), art.height());
            target.end();
            PixmapIO.writePNG(Gdx.files.absolute(new File(System.getProperty("megamek.gpu.screenshots"),
                  "tactical-tileset-column.png").getAbsolutePath()), rendered, -1, true);
            // The middle of the hex, averaged: the art's texels and the drawn pixels differ only by filtering.
            long[] expected = new long[3], drawn = new long[3];
            for (int y = art.height() / 2 - 12; y < art.height() / 2 + 12; y++) {
                for (int x = art.width() / 2 - 12; x < art.width() / 2 + 12; x++) {
                    int texel = art.rgba(y * art.width() + x), pixel = rendered.getPixel(x, art.height() - y - 1);
                    for (int channel = 0; channel < 3; channel++) {
                        expected[channel] += texel >>> 24 - 8 * channel & 255;
                        drawn[channel] += pixel >>> 24 - 8 * channel & 255;
                    }
                }
            }
            for (int channel = 0; channel < 3; channel++) {
                assertEquals(expected[channel] / 576f, drawn[channel] / 576f, 6, "Channel " + channel + " of the art");
            }
        } finally {
            if (rendered != null) { rendered.dispose(); }
            target.dispose();
            terrain.dispose();
        }
    }
}
