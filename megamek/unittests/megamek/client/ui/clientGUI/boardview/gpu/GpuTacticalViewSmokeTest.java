/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.gdx.DisplayScale;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.client.ui.util.PlayerColour;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.interfaces.IEntityRemovalConditions;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Tactical View on the real Saxarba capture: the T key swaps the shaded terrain for the tileset columns and the
 * unit meshes for classic sprites, and T again restores the 3D view, which keeps its meshes at every angle and zoom.
 */
@Tag("on-demand")
class GpuTacticalViewSmokeTest {
    private static final int WIDTH = 12;
    private static final int HEIGHT = 10;
    private static final Coords VIEWER = new Coords(1, 5);
    private static final Coords BUILDING = new Coords(2, 7);
    private static final Coords BRIDGE = new Coords(7, 3);
    private static final Coords FUEL_TANK = new Coords(5, 1);
    /** The fixture's own Atlas, facing north. */
    private static final Coords OWN = new Coords(5, 5);
    /** The enemy Warwolf, facing south-east. */
    private static final Coords ENEMY = new Coords(8, 6);
    private static final int ENEMY_FACING = 2;

    @Test
    void swapsInTilesetColumnsAndUnitIconsAndRestoresTheThreeDimensionalView() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GUIPreferences preferences = GUIPreferences.getInstance();
        float originalScale = preferences.getGUIScale();
        try (var options = new GpuFieldOfViewTest.Options(); var fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Player enemy = new Player(2, "Enemy");
                    enemy.setTeam(2);
                    enemy.setColour(PlayerColour.RED);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    Entity warwolf = new MekFileParser(new File("testresources/megamek/common/units/Warwolf A.mtf"))
                          .getEntity();
                    warwolf.setId(2);
                    warwolf.setOwner(enemy);
                    warwolf.setPosition(ENEMY);
                    warwolf.setFacing(ENEMY_FACING);
                    warwolf.setSecondaryFacing(ENEMY_FACING);
                    warwolf.setDeployed(true);
                    fixture.game.addEntity(warwolf, false);
                    fixture.entity.setFacing(0);
                    fixture.entity.setSecondaryFacing(0);
                    fixture.view.select(VIEWER);
                    fixture.source.setVisibleArea(new Rectangle(0, 0, WIDTH, HEIGHT));
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1920, 1080);
            configuration.setInitialVisible(false);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        // One logical HUD unit per window pixel, as in the 1920x1080 prototype captures.
                        float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                        SwingUtilities.invokeAndWait(() -> preferences.setValue(GUIPreferences.GUI_SCALE, preference));
                        view = new GpuBattleView(fixture.source);
                        view.create();
                        // The board arrives once its terrain is built behind the loading screen.
                        GpuBoardTestUi.present(view);
                        verify(view, fixture);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setValue(GUIPreferences.GUI_SCALE, originalScale));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    /** Woods, jungle, buildings, a fuel tank, industry, a bridged river and two height steps. */
    private static Board board() {
        Hex[] hexes = new Hex[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Hex hex = new Hex(x == 4 ? 3 : x >= 9 ? 1 : 0);
                if (x == 7) {
                    hex.addTerrain(new Terrain(Terrains.WATER, 1));
                }
                hexes[y * WIDTH + x] = hex;
            }
        }
        forest(hexes, 1, 2, Terrains.WOODS, 2);
        forest(hexes, 2, 2, Terrains.WOODS, 1);
        forest(hexes, 6, 8, Terrains.WOODS, 2);
        forest(hexes, 10, 8, Terrains.JUNGLE, 2);
        building(hexes, BUILDING, 2, 2, 40);
        building(hexes, new Coords(6, 5), 1, 1, 15);
        building(hexes, new Coords(9, 2), 3, 3, 90);
        Hex tank = hexes[FUEL_TANK.getY() * WIDTH + FUEL_TANK.getX()];
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK, 2, true, 0));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
        hexes[5 * WIDTH + 10].addTerrain(new Terrain(Terrains.INDUSTRIAL, 3));
        Hex bridge = hexes[BRIDGE.getY() * WIDTH + BRIDGE.getX()];
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, (1 << 1) | (1 << 4)));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        return new Board(WIDTH, HEIGHT, hexes);
    }

    private static void forest(Hex[] hexes, int x, int y, int type, int density) {
        hexes[y * WIDTH + x].addTerrain(new Terrain(type, density));
        hexes[y * WIDTH + x].addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
    }

    private static void building(Hex[] hexes, Coords coords, int type, int height, int cf) {
        Hex hex = hexes[coords.getY() * WIDTH + coords.getX()];
        hex.addTerrain(new Terrain(Terrains.BUILDING, type, true, 0));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, height));
        hex.addTerrain(new Terrain(Terrains.BLDG_CF, cf));
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        view.render();
        GpuBoardTestUi.<Slider>tuning(view, "Time of day").setValue(13);
        render(view);
        var icons = (GpuUnitIcons) field(view, "unitIcons");
        var terrain = (GpuTerrain) field(view, "terrain");
        var scene = (BoardScene) field(view, "scene");
        var models = (Map<String, ModelInstance>) field(view, "unitInstances");
        var model = models.get("1:-1");
        assertNotNull(model, "The own Atlas is a 3D model");
        assertEquals(2, scene.units().size());
        assertFalse(icons.active());
        var roof = terrain.roofBounds(BUILDING);
        assertNotNull(roof, "The 3D board raises the building mesh");
        boolean isometricPreference = GUIPreferences.getInstance().getIsometricEnabled();
        // A manual 3D pose away from the fit: leaving must return this saved pose, not fit the board again.
        view.boardCamera.orbit(20, 5);
        view.boardCamera.pan(40, -25);
        view.boardCamera.zoom(.8f);
        render(view);
        assertFalse((boolean) field(view.boardCamera, "fitToWindow"), "The saved 3D pose is not a fit");
        Vector3 focus = view.boardCamera.focus.cpy();
        float zoom = view.boardCamera.camera.zoom;
        float azimuth = view.boardCamera.azimuth();
        float tilt = view.boardCamera.tilt();
        assertTrue(tilt > 0, "The 3D view starts at an angle");
        capture("tactical-view-3d.png");

        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertTrue(view.boardCamera.tactical(), "T enters the Tactical View");
        view.boardCamera.fit(scene);
        render(view);
        assertTrue(view.boardCamera.camera.direction.epsilonEquals(0, 0, -1, .0001f));
        assertTrue(view.boardCamera.camera.up.epsilonEquals(0, 1, 0, .0001f), "North stays up");
        assertTrue(icons.active(), "The Tactical View always shows unit icons");
        assertTrue(terrain.tacticalView(), "The tileset columns replace the shaded terrain");
        assertTrue(((List<?>) field(terrain, "shadowModels")).isEmpty(), "Hidden unit models cast no shadows");
        assertSame(model, models.get("1:-1"), "The hidden 3D model keeps its animated instance");
        assertEquals(roof.getCenter(new Vector3()), terrain.roofBounds(BUILDING).getCenter(new Vector3()),
              "Height labels keep their roof position, whichever view last cached them");
        capture("tactical-view-top.png");
        verifyIcons(view, scene, icons);
        verifyContactAndUnlistedIcons(view, scene, icons);
        verifyFieldOfView(view, scene);
        verifyPicking(view, terrain, scene, icons);
        verifyScreenSize(view, scene, icons);
        verifyCloseup(view, scene, icons);
        verifyStates(view, fixture, icons);

        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        render(view);
        assertFalse(view.boardCamera.tactical(), "T again returns to the 3D view");
        assertEquals(focus, view.boardCamera.focus, "The exact 3D pose returns, not a new fit of the board");
        assertEquals(zoom, view.boardCamera.camera.zoom);
        assertEquals(azimuth, view.boardCamera.azimuth());
        assertEquals(tilt, view.boardCamera.tilt());
        assertFalse(icons.active());
        assertFalse(terrain.tacticalView());
        assertFalse(((List<?>) field(terrain, "shadowModels")).isEmpty(), "Unit models cast shadows again");
        capture("tactical-view-restored.png");

        // Straight down and zoomed far out, the 3D view still shows its meshes: only T switches to the icons.
        view.boardCamera.orbit(0, -BoardCamera.MAX_TILT);
        render(view);
        assertEquals(0, view.boardCamera.tilt());
        assertFalse(icons.active(), "Straight down, the 3D view keeps its models");
        capture("tactical-view-3d-overhead.png");
        view.boardCamera.zoom(20 / view.boardCamera.camera.zoom);
        render(view);
        assertFalse(view.boardCamera.tactical());
        assertFalse(icons.active(), "No zoom or angle turns the 3D models into icons");
        assertFalse(terrain.tacticalView(), "No zoom or angle turns the terrain into tileset columns");
        assertFalse(((List<?>) field(terrain, "shadowModels")).isEmpty());
        assertSame(model, models.get("1:-1"));
        assertEquals(isometricPreference, GUIPreferences.getInstance().getIsometricEnabled(),
              "T stays in the GPU view: the classic board's isometric setting is untouched");
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(OWN, fixture.entity.getPosition());
            assertEquals(0, fixture.entity.getFacing());
        });
        verifyWreck(view, fixture, icons);
        assertEquals(0, fixture.clicks.get(), "Switching views and picking issue no game command");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /**
     * Classic sprites lie on the board and turn with their animated facing. The facing tick carries the side colour;
     * labels sit above the centre by .62 of the nominal sprite size, which is .9 hex heights.
     */
    @SuppressWarnings("unchecked")
    private static void verifyIcons(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var anchors = (Map<BoardScene.Unit, Vector3>) field(view, "unitAnchors");
        for (BoardScene.Unit unit : scene.units()) {
            ModelInstance placed = icons.instance(unit);
            Vector3 centre = screen(view, placed.transform.getTranslation(new Vector3()));
            Vector3 head = screen(view, anchors.get(unit));
            float hexHeight = screen(view, new Vector3(0, -.5f, 0).mul(placed.transform))
                  .dst(screen(view, new Vector3(0, .5f, 0).mul(placed.transform)));
            assertEquals(centre.x, head.x, .5f, "The head of " + unit.name() + " is straight above its icon");
            assertEquals(GpuUnitIcons.HEAD_LIFT * GpuUnitIcons.SIZE_IN_HEXES * hexHeight, centre.y - head.y, .5f,
                  "The head of " + unit.name() + " is .62 of the nominal sprite size above its centre");
        }
        for (BoardScene.Unit unit : scene.units()) {
            ModelInstance icon = icons.instance(unit);
            assertNotNull(icon, "An icon for " + unit.name());
            float facing = poses.get(unit).facing();
            float turn = icon.transform.getRotation(new Quaternion(), true).getAngleAround(Vector3.Z);
            System.out.printf("%s at %s: animated facing %.1f, icon turned %.1f degrees%n", unit.name(),
                  unit.location().coords(), facing, turn);
            // Facings run clockwise seen from above; rotations about +Z run counterclockwise.
            assertEquals((360 - facing) % 360, turn, .01f, "The whole icon turns with the facing of " + unit.name());
            assertEquals(0, UnitBounds.local(icon).getDepth(), .0001f, "Icons are flat");
            assertEquals(1, new Vector3(Vector3.Z).rot(icon.transform).nor().z, .0001f, "Icons lie on the board");
            assertEquals(3, icon.nodes.first().parts.size, "Icons contain only the sprite, facing tick and wreck cross");
            assertTrue(part(icon, "sprite").enabled && part(icon, "tick").enabled && !part(icon, "cross").enabled);
            assertEquals(Color.WHITE, color(icon, "sprite"), "The classic sprite keeps its colours");
        }
        ModelInstance own = icon(scene, icons, OWN);
        ModelInstance enemy = icon(scene, icons, ENEMY);
        assertEquals(0, poses.get(unit(scene, OWN)).facing(), .01f);
        assertEquals(ENEMY_FACING * 60, poses.get(unit(scene, ENEMY)).facing(), .01f);
        assertEquals(UiTheme.MINT, color(own, "tick"));
        assertEquals(UiTheme.CORAL, color(enemy, "tick"));
    }

    /** Sensor contacts and units without a status keep their supplied sprite but reveal no facing tick. */
    @SuppressWarnings("unchecked")
    private static void verifyContactAndUnlistedIcons(GpuBattleView view, BoardScene scene, GpuUnitIcons icons)
          throws Exception {
        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var enemy = unit(scene, ENEMY);
        var own = unit(scene, OWN);
        var contact = new BoardScene.Unit(enemy.id(), enemy.part(), enemy.name(), enemy.location(), enemy.image(), true,
              enemy.annotations(), enemy.height(), enemy.airborne(), enemy.model(), enemy.outlineRgb(),
              enemy.footprint());
        var unlisted = new BoardScene.Unit(99, own.part(), own.name(), own.location(), own.image(), false,
              own.annotations(), own.height(), own.airborne(), own.model(), own.outlineRgb(), own.footprint());
        var shown = new HashMap<>(poses);
        shown.put(contact, new UnitFootprint.Pose(contact, poses.get(enemy).position(), 0));
        shown.put(unlisted, new UnitFootprint.Pose(unlisted, poses.get(own).position(), 0));
        icons.update(true, view.boardCamera.camera, scene.withUnits(List.of(contact, unlisted)),
              (GpuBattleStatus.Snapshot) field(icons, "status"), shown, new HashMap<>());
        ModelInstance blip = icons.instance(contact);
        assertTrue(part(blip, "sprite").enabled && !part(blip, "tick").enabled,
              "A sensor contact's sprite reveals no facing");
        assertEquals(Color.WHITE, color(blip, "sprite"));
        assertFalse(part(icons.instance(unlisted), "tick").enabled, "A unit without a status has no facing tick");
        assertEquals(Color.WHITE, color(icons.instance(unlisted), "sprite"));
        assertFalse(part(icons.instance(unlisted), "cross").enabled, "and whole while its pose is alive");
        render(view);
        assertEquals(UiTheme.CORAL, color(icon(scene, icons, ENEMY), "tick"), "The next frame restores the enemy");
    }

    private static BoardScene.Unit unit(BoardScene scene, Coords coords) {
        return scene.units().stream().filter(unit -> unit.location().coords().equals(coords)).findFirst().orElseThrow();
    }

    private static ModelInstance icon(BoardScene scene, GpuUnitIcons icons, Coords coords) {
        return icons.instance(unit(scene, coords));
    }

    private static NodePart part(ModelInstance icon, String id) {
        for (NodePart part : icon.nodes.first().parts) {
            if (part.meshPart.id.equals(id)) { return part; }
        }
        throw new AssertionError("No icon part " + id);
    }

    private static Color color(ModelInstance icon, String id) {
        return ((ColorAttribute) part(icon, id).material.get(ColorAttribute.Diffuse)).color;
    }

    /** Zoomed in, facing ticks show the sides' colours on screen, full-bright over the lit board. */
    private static void verifyCloseup(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        view.boardCamera.center(BoardGeometry.center(OWN, 0).lerp(BoardGeometry.center(ENEMY, 0), .5f));
        view.boardCamera.zoom(BoardGeometry.HEIGHT / 300 / view.boardCamera.camera.zoom);
        render(view);
        capture("tactical-view-icons-closeup.png");
        BoardProjectionCamera camera = view.boardCamera.camera;
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            for (Coords coords : List.of(OWN, ENEMY)) {
                ModelInstance icon = icon(scene, icons, coords);
                // Inside the facing triangle, away from its edges and the name label.
                Vector3 side = camera.project(new Vector3(0, .4f, 0).mul(icon.transform), 0, 0,
                      camera.viewportWidth, camera.viewportHeight);
                int rgba = pixel(frame, side.x, side.y);
                Color expected = coords.equals(ENEMY) ? UiTheme.CORAL : UiTheme.MINT;
                System.out.printf("Facing tick at %s: #%08X, expected #%08X%n", coords, rgba, Color.rgba8888(expected));
                for (int shift : new int[] { 24, 16, 8 }) {
                    assertEquals((Color.rgba8888(expected) >>> shift) & 255, (rgba >>> shift) & 255, 8,
                          "The facing tick of the unit at " + coords + " shows its side's colour");
                }
            }
        } finally {
            frame.dispose();
        }
    }

    /**
     * Zooming scales each icon with its hex, like the classic 2D board's units: on hexes from 20 to 800 HUD pixels
     * wide, the sprite retains its aspect ratio and fills .9 of the hex in its limiting direction.
     */
    private static void verifyScreenSize(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        float scale = layoutScale(view);
        for (float hexPixels : new float[] { 60, 300, 800, 20 }) {
            for (Coords coords : List.of(OWN, ENEMY)) {
                view.boardCamera.center(BoardGeometry.center(coords, 0));
                view.boardCamera.zoom(BoardGeometry.WIDTH / (hexPixels * scale) / view.boardCamera.camera.zoom);
                render(view);
                ModelInstance icon = icon(scene, icons, coords);
                float width = screen(view, BoardGeometry.corner(coords, 0, 3))
                      .dst(screen(view, BoardGeometry.corner(coords, 0, 0)));
                assertEquals(hexPixels * scale, width, .01f * hexPixels * scale, "The camera zooms to the hex size");
                assertSpriteSize(view, unit(scene, coords), icon, width);
            }
            // Both captures are centred on the enemy's icon.
            if (hexPixels == 800) { capture("tactical-view-zoom-in.png"); }
            if (hexPixels == 20) { capture("tactical-view-zoom-out.png"); }
        }
    }

    /** Measures the rendered sprite quad, whose size is independent of the facing tick and placement transform. */
    private static Vector3 spriteSize(ModelInstance icon) {
        var mesh = part(icon, "sprite").meshPart;
        mesh.update();
        return mesh.halfExtents.cpy().scl(2);
    }

    static void assertSpriteSize(GpuBattleView view, BoardScene.Unit unit, ModelInstance icon, float hexWidth) {
        Vector3 size = spriteSize(icon);
        float width = screen(view, new Vector3(-size.x / 2, 0, 0).mul(icon.transform))
              .dst(screen(view, new Vector3(size.x / 2, 0, 0).mul(icon.transform)));
        float height = screen(view, new Vector3(0, -size.y / 2, 0).mul(icon.transform))
              .dst(screen(view, new Vector3(0, size.y / 2, 0).mul(icon.transform)));
        float hexHeight = hexWidth * BoardGeometry.height() / BoardGeometry.width();
        assertEquals(.9f, Math.max(width / hexWidth, height / hexHeight), .009f,
              "The sprite fills 90% of its hex in the limiting direction at every zoom");
        assertEquals(unit.image().width() / (float) unit.image().height(), width / height, .001f,
              "The classic sprite retains its aspect ratio");
    }

    /**
     * The client's selection, an own unit that has already moved and a doomed enemy, through the real capture: the
     * selected and moved units retain their sprite colours, and the doomed one is crossed out and faded.
     */
    private static void verifyStates(GpuBattleView view, GpuBoardFixture fixture, GpuUnitIcons icons)
          throws Exception {
        JComponent phase = fixture.panel;
        Entity enemy = fixture.game.getEntity(2);
        SwingUtilities.invokeAndWait(() -> {
            var moving = mock(MovementDisplay.class);
            when(moving.currentEntity()).thenReturn(fixture.entity);
            when(moving.getComponents()).thenReturn(new Component[0]);
            when(moving.getActionButtons()).thenReturn(List.of());
            when(moving.getCompletionButtons()).thenReturn(List.of());
            fixture.panel = moving;
            fixture.entity.setDone(true);
            enemy.setDoomed(true);
            fixture.source.refresh();
        });
        render(view);
        var scene = (BoardScene) field(view, "scene");
        assertEquals(fixture.entity.getId(), scene.selectedId(), "The client selects the Atlas");
        ModelInstance own = icon(scene, icons, OWN);
        assertEquals(Color.WHITE, color(own, "sprite"), "Selection and having moved do not tint the classic sprite");
        assertEquals(UiTheme.MINT, color(own, "tick"), "The tick keeps the side's color");
        assertFalse(part(own, "cross").enabled, "The own unit is still alive");
        ModelInstance doomed = icon(scene, icons, ENEMY);
        assertTrue(part(doomed, "cross").enabled, "A doomed unit is crossed out");
        assertEquals(.45f, color(doomed, "tick").a, .001f, "A doomed unit's icon fades");
        assertEquals(.45f, color(doomed, "sprite").a, .001f);
        assertEquals(.45f, color(doomed, "cross").a, .001f);
        capture("tactical-view-icon-states.png");

        SwingUtilities.invokeAndWait(() -> {
            fixture.panel = phase;
            fixture.entity.setDone(false);
            enemy.setDoomed(false);
            fixture.source.refresh();
        });
        render(view);
        scene = (BoardScene) field(view, "scene");
        own = icon(scene, icons, OWN);
        doomed = icon(scene, icons, ENEMY);
        assertEquals(Color.WHITE, color(own, "sprite"));
        assertTrue(!part(doomed, "cross").enabled && color(doomed, "tick").a == 1, "The enemy is whole again");
        assertEquals(Color.WHITE, color(doomed, "sprite"));
    }

    /**
     * The game removes the destroyed enemy and leaves its wreck, through the real capture: the battle status no longer
     * lists it, so it has no facing tick, and its dead pose crosses the sprite out and fades it.
     */
    private static void verifyWreck(GpuBattleView view, GpuBoardFixture fixture, GpuUnitIcons icons)
          throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.removeEntity(2, IEntityRemovalConditions.REMOVE_SALVAGEABLE);
            fixture.source.refresh();
        });
        GpuBoardTestUi.press(KeyCommandBind.TOGGLE_ISO);
        view.boardCamera.center(BoardGeometry.center(OWN, 0).lerp(BoardGeometry.center(ENEMY, 0), .5f));
        view.boardCamera.zoom(BoardGeometry.WIDTH / (160 * layoutScale(view)) / view.boardCamera.camera.zoom);
        render(view);
        assertTrue(icons.active());
        var scene = (BoardScene) field(view, "scene");
        var status = (GpuBattleStatus.Snapshot) field(icons, "status");
        assertTrue(status.units().stream().noneMatch(listed -> listed.id() == 2), "The status no longer lists it");
        ModelInstance wreck = icon(scene, icons, ENEMY);
        assertTrue(part(wreck, "cross").enabled, "The wreck is crossed out");
        assertFalse(part(wreck, "tick").enabled, "The wreck has no facing tick");
        assertEquals(.45f, color(wreck, "cross").a, .001f);
        assertEquals(.45f, color(wreck, "sprite").a, .001f);
        assertFalse(part(icon(scene, icons, OWN), "cross").enabled, "The own unit stays whole");
        capture("tactical-view-wreck.png");
    }

    private static float layoutScale(GpuBattleView view) throws Exception {
        return (float) field(view, "layoutScale");
    }

    /** Window coordinates (y down) of a world point. */
    private static Vector3 screen(GpuBattleView view, Vector3 world) {
        BoardProjectionCamera camera = view.boardCamera.camera;
        Vector3 point = camera.project(world.cpy(), 0, 0, camera.viewportWidth, camera.viewportHeight);
        point.y = Gdx.graphics.getHeight() - point.y;
        return point;
    }

    private static void render(GpuBattleView view) {
        for (int frame = 0; frame < 3; frame++) {
            view.render();
        }
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }

    /** The columns are lit before the atmosphere composite, so the shared field of view darkens only unseen art. */
    private static void verifyFieldOfView(GpuBattleView view, BoardScene scene) {
        Slider darkness = GpuBoardTestUi.tuning(view, "FoV darkness");
        float[] darkened = artLuminance(view, scene);
        darkness.setValue(0);
        render(view);
        float[] plain = artLuminance(view, scene);
        darkness.setValue(GpuFieldOfView.FOV_DARKNESS * 100);
        render(view);
        System.out.printf("Tileset art luminance with/without FoV darkness: seen %.3f/%.3f, unseen %.3f/%.3f on %s%n",
              darkened[0], plain[0], darkened[1], plain[1], Gdx.gl.glGetString(GL20.GL_RENDERER));
        assertTrue(plain[0] > 0 && plain[1] > 0, "Both seen and unseen hexes must be sampled");
        assertEquals(plain[0], darkened[0], .02f, "Seen hexes keep their lit colors");
        assertTrue(darkened[1] < plain[1] * .9f, "Unseen hexes darken");
        assertTrue(darkened[1] < darkened[0], "An unseen hex is darker than a seen one");
    }

    /** Mean luminance of opaque tileset art pixels inside their hexes: {seen, unseen}, away from units and labels. */
    private static float[] artLuminance(GpuBattleView view, BoardScene scene) {
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            float[] sum = new float[2];
            int[] count = new int[2];
            for (BoardScene.Tile tile : scene.tiles()) {
                var art = tile.tileset();
                var visibility = GpuFieldOfViewTest.at(scene.fieldOfView(), tile.coords()).visibility();
                boolean seen = visibility == BoardFieldOfView.Visibility.VISIBLE;
                if (art == null || (!seen && visibility != BoardFieldOfView.Visibility.BLOCKED) || scene.units()
                      .stream().anyMatch(unit -> unit.location().coords().distance(tile.coords()) <= 1)) {
                    continue;
                }
                for (int y = 0; y < art.height() * 2 / 3; y += 2) {
                    for (int x = 0; x < art.width(); x += 2) {
                        Vector3 offset = offset(art, x, y);
                        if (!opaque(art, x, y) || !inside(tile.coords(), offset, 1)) {
                            continue;
                        }
                        Vector3 point = screen(view, tile.coords(), offset);
                        int rgba = pixel(frame, point.x, Gdx.graphics.getHeight() - point.y);
                        int index = seen ? 0 : 1;
                        sum[index] += (.2126f * ((rgba >>> 24) & 255) + .7152f * ((rgba >>> 16) & 255)
                              + .0722f * ((rgba >>> 8) & 255)) / 255f;
                        count[index]++;
                    }
                }
            }
            return new float[] { count[0] == 0 ? 0 : sum[0] / count[0], count[1] == 0 ? 0 : sum[1] / count[1] };
        } finally {
            frame.dispose();
        }
    }

    /** Board offset from the hex centre of a pixel of its tileset art, which spans the hex's tile rectangle. */
    private static Vector3 offset(BoardScene.Pixels art, int x, int y) {
        return new Vector3(((x + .5f) / art.width() - .5f) * BoardGeometry.WIDTH,
              (.5f - (y + .5f) / art.height()) * BoardGeometry.HEIGHT, 0);
    }

    private static boolean opaque(BoardScene.Pixels art, int x, int y) {
        return (art.rgba(y * art.width() + x) & 255) >= 240;
    }

    private static boolean inside(Coords coords, Vector3 offset, float margin) {
        return BoardGeometry.contains(coords, BoardGeometry.centerX(coords) + offset.x * margin,
              BoardGeometry.centerY(coords) + offset.y * margin);
    }

    /** The back-buffer pixel that contains a point in window coordinates with y up. */
    private static int pixel(Pixmap frame, float x, float y) {
        return frame.getPixel((int) (x * frame.getWidth() / Gdx.graphics.getWidth()),
              (int) (y * frame.getHeight() / Gdx.graphics.getHeight()));
    }

    /** Window coordinates (y down) of a board offset from a hex centre; the north-up top view maps it linearly. */
    private static Vector3 screen(GpuBattleView view, Coords coords, Vector3 offset) {
        float zoom = view.boardCamera.camera.zoom;
        return view.screenPosition(coords).add(offset.x / zoom, -offset.y / zoom, 0);
    }

    @SuppressWarnings("unchecked")
    private static void verifyPicking(GpuBattleView view, GpuTerrain terrain, BoardScene scene, GpuUnitIcons icons)
          throws Exception {
        for (Coords coords : List.of(BUILDING, BRIDGE, FUEL_TANK)) {
            var art = scene.tile(coords).tileset();
            // The opaque art pixel farthest from the centre that still lies well inside its own hex.
            Vector3 edge = null;
            for (int y = 0; y < art.height(); y++) {
                for (int x = 0; x < art.width(); x++) {
                    Vector3 offset = offset(art, x, y);
                    boolean farther = edge == null || offset.len2() > edge.len2();
                    if (farther && opaque(art, x, y) && inside(coords, offset, 1.1f)) {
                        edge = offset;
                    }
                }
            }
            assertNotNull(edge, "Opaque tileset art at " + coords);
            assertEquals(coords, pick(view, screen(view, coords, edge)), "An art pixel picks its own hex");
            assertEquals(coords, pick(view, view.screenPosition(coords)));
        }
        for (Coords coords : List.of(OWN, ENEMY)) {
            assertEquals(coords, pick(view, view.screenPosition(coords)), "A click on the icon picks its unit");
        }
        // At rest an icon lies inside its own hex. Mid-move, the enemy's icon lies over the river west of its hex,
        // and high above it, as during a jump: the icon, drawn on the ground, not the river, takes the pick.
        var enemy = unit(scene, ENEMY);
        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var moved = new HashMap<>(poses);
        Vector3 offset = new Vector3(-.6f * BoardGeometry.WIDTH, .1f * BoardGeometry.HEIGHT, 0);
        Vector3 position = poses.get(enemy).position().cpy().add(offset).add(0, 0, 200);
        moved.put(enemy, new UnitFootprint.Pose(enemy, position, poses.get(enemy).facing()));
        var status = (GpuBattleStatus.Snapshot) field(icons, "status");
        icons.update(true, view.boardCamera.camera, scene, status, moved, new HashMap<>());
        ModelInstance icon = icons.instance(enemy);
        Vector3 center = icon.transform.getTranslation(new Vector3());
        assertEquals(position.x, center.x, .0001f);
        assertEquals(position.y, center.y, .0001f);
        assertTrue(center.z < BoardGeometry.LEVEL, "Even airborne animation poses project onto the hex surface");
        var ground = terrain.hit(scene, new Ray(new Vector3(center.x, center.y, 1000), new Vector3(0, 0, -1)));
        assertNotEquals(ENEMY, ground.coords());
        assertEquals(ENEMY, pick(view, screen(view, ENEMY, offset)), "The unit icon takes the pick");
        // Off the icon's centre too: the icon corner farthest from the enemy's hex, over another hex's ground.
        Vector3 hexCenter = BoardGeometry.center(ENEMY, 0);
        Vector3 corner = null;
        Vector3 size = spriteSize(icon);
        for (float x : new float[] { -.4f * size.x, .4f * size.x }) {
            for (float y : new float[] { -.4f * size.y, .4f * size.y }) {
                Vector3 point = new Vector3(x, y, 0).mul(icon.transform);
                if (corner == null || point.dst2(hexCenter) > corner.dst2(hexCenter)) { corner = point; }
            }
        }
        ground = terrain.hit(scene, new Ray(new Vector3(corner.x, corner.y, 1000), new Vector3(0, 0, -1)));
        assertNotEquals(ENEMY, ground.coords());
        assertEquals(ENEMY, pick(view, screen(view, corner)), "A click off the icon's centre picks its unit");
        assertEquals(UiTheme.CORAL, color(icon, "tick"), "The moving unit keeps its side's colour");
        render(view);
        assertEquals(UiTheme.CORAL, color(icon(scene, icons, ENEMY), "tick"), "The next frame restores its normal pose");

        // A 3D feature mesh that overhangs a neighbouring hex takes the vertical pick there; the Tactical View hides
        // it, so the hex under the pointer takes the pick.
        Overhang overhang = overhang(view, terrain, scene);
        assertNotNull(overhang, "A 3D feature mesh overhangs a neighbouring hex");
        System.out.printf("The 3D mesh of %s overhangs %s%n", overhang.feature(), overhang.under());
        assertEquals(overhang.under(), pick(view, screen(view, overhang.under(), overhang.offset())),
              "The hex under the pointer, not the hidden mesh of " + overhang.feature() + ", takes the pick");
    }

    private record Overhang(Coords under, Vector3 offset, Coords feature) { }

    /**
     * A point over a hex's own ground, away from units, where a 3D feature mesh of another hex takes the vertical
     * pick. Leaves the Tactical View on.
     */
    private static Overhang overhang(GpuBattleView view, GpuTerrain terrain, BoardScene scene) {
        List<Coords> under = new ArrayList<>();
        List<Vector3> offsets = new ArrayList<>();
        List<Ray> rays = new ArrayList<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (scene.units().stream().anyMatch(unit -> unit.location().coords().distance(tile.coords()) <= 1)) {
                continue;
            }
            for (int step = 0; step < 400; step++) {
                Vector3 offset = new Vector3((step % 20 / 19f - .5f) * BoardGeometry.WIDTH,
                      (step / 20 / 19f - .5f) * BoardGeometry.HEIGHT, 0);
                under.add(tile.coords());
                offsets.add(offset);
                // Probe the same rounded screen pixel that pick() will click, including at hex boundaries.
                Vector3 pointer = screen(view, tile.coords(), offset);
                var camera = view.boardCamera.camera;
                rays.add(new Ray().set(camera.getPickRay(Math.round(pointer.x), Math.round(pointer.y), 0, 0,
                      camera.viewportWidth, camera.viewportHeight)));
            }
        }
        // Switching the view recaches every section's props, so each view answers all the rays in turn.
        terrain.setTacticalView(false);
        var meshes = rays.stream().map(ray -> terrain.hit(scene, ray)).toList();
        terrain.setTacticalView(true);
        for (int index = 0; index < rays.size(); index++) {
            var mesh = meshes.get(index);
            var ground = terrain.hit(scene, rays.get(index));
            Coords coords = under.get(index);
            if (mesh != null && ground != null && coords.equals(ground.coords()) && !coords.equals(mesh.coords())) {
                return new Overhang(coords, offsets.get(index), mesh.coords());
            }
        }
        return null;
    }

    private static Coords pick(GpuBattleView view, Vector3 point) throws Exception {
        Object input = field(view, "boardInput");
        var pick = input.getClass().getDeclaredMethod("pick", int.class, int.class);
        pick.setAccessible(true);
        return (Coords) pick.invoke(input, Math.round(point.x), Math.round(point.y));
    }
}
