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
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.utils.ScreenUtils;
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
 * The Tactical View on the real Saxarba capture: the T key swaps every mesh for tileset sprites and framed unit icons,
 * and T again restores the 3D view, which keeps its meshes at every angle and zoom.
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
    void replacesEveryMeshWithTilesetArtFromAboveAndRestoresTheThreeDimensionalView() throws Exception {
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
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        // One logical HUD unit per window pixel, as in the 1920x1080 prototype captures.
                        float preference = .1f / DisplayScale.read(.1f, new GpuDisplayScale().contentScale());
                        SwingUtilities.invokeAndWait(() -> preferences.setValue(GUIPreferences.GUI_SCALE, preference));
                        view = new GpuBattleView(fixture.source);
                        view.create();
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
        for (Coords coords : List.of(BUILDING, BRIDGE, FUEL_TANK, new Coords(10, 5), new Coords(1, 2))) {
            assertNotNull(scene.tile(coords).foliage(), "Tileset art from above for the mesh at " + coords);
        }
        verifySpriteSilhouette(scene);
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
        assertTrue((boolean) field(terrain, "flatFeatures"));
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
        assertFalse((boolean) field(terrain, "flatFeatures"));
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
        assertFalse((boolean) field(terrain, "flatFeatures"), "No zoom or angle turns feature meshes into sprites");
        assertFalse(((List<?>) field(terrain, "shadowModels")).isEmpty());
        assertSame(model, models.get("1:-1"));
        verifyPasses(scene);
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
     * Every unit is a framed icon lying on the board, turned with its animated facing and colored by its side; its
     * head, where labels hang, is hud-v3's: the icon's centre lifted up the screen by .62 of its side.
     */
    @SuppressWarnings("unchecked")
    private static void verifyIcons(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        var poses = (Map<BoardScene.Unit, UnitFootprint.Pose>) field(view, "unitFootprints");
        var anchors = (Map<BoardScene.Unit, Vector3>) field(view, "unitAnchors");
        for (BoardScene.Unit unit : scene.units()) {
            ModelInstance placed = icons.instance(unit);
            Vector3 centre = screen(view, placed.transform.getTranslation(new Vector3()));
            Vector3 head = screen(view, anchors.get(unit));
            float side = screen(view, new Vector3(-.5f, 0, 0).mul(placed.transform))
                  .dst(screen(view, new Vector3(.5f, 0, 0).mul(placed.transform)));
            assertEquals(centre.x, head.x, .5f, "The head of " + unit.name() + " is straight above its icon");
            assertEquals(GpuUnitIcons.HEAD_LIFT * side, centre.y - head.y, .5f, "The head of " + unit.name()
                  + " is .62 of the icon's side above its centre");
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
            assertTrue(part(icon, "frame").enabled && part(icon, "tick").enabled && !part(icon, "bold").enabled);
        }
        ModelInstance own = icon(scene, icons, OWN);
        ModelInstance enemy = icon(scene, icons, ENEMY);
        assertEquals(0, poses.get(unit(scene, OWN)).facing(), .01f);
        assertEquals(ENEMY_FACING * 60, poses.get(unit(scene, ENEMY)).facing(), .01f);
        assertEquals(frameColor(view, OWN, UiTheme.MINT), color(own, "frame"), "Own units are framed in mint");
        assertEquals(UiTheme.MINT, color(own, "tick"));
        assertEquals(frameColor(view, ENEMY, UiTheme.CORAL), color(enemy, "frame"), "Enemies are framed in coral");
        assertEquals(UiTheme.CORAL, color(enemy, "tick"));
        assertNotEquals(color(own, "fill"), color(enemy, "fill"), "Each side has its own tint under the sprite");
    }

    /** A sensor contact is blip orange with a dashed frame and no tick; a unit the status does not list is grey. */
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
              (GpuBattleStatus.Snapshot) field(icons, "status"), unit -> false, unit -> false, shown, new HashMap<>(),
              new BoardSurface.Cache());
        ModelInstance blip = icons.instance(contact);
        assertTrue(part(blip, "dashed").enabled && !part(blip, "frame").enabled && !part(blip, "tick").enabled,
              "A sensor contact has a dashed frame and no facing tick");
        assertEquals(UiTheme.BLIP, color(blip, "frame"));
        assertEquals(UiTheme.ACCENT, color(icons.instance(unlisted), "frame"), "A unit of no listed side is grey");
        assertFalse(part(icons.instance(unlisted), "cross").enabled, "and whole while its pose is alive");
        render(view);
        assertEquals(UiTheme.CORAL, color(icon(scene, icons, ENEMY), "tick"), "The next frame restores the enemy");
    }

    /** A unit under the real, uncontrolled mouse pointer has a white frame; otherwise its side's color. */
    private static Color frameColor(GpuBattleView view, Coords coords, Color side) throws Exception {
        return coords.equals(field(view, "hovered")) ? Color.WHITE : side;
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

    /** Zoomed in, the frames show the sides' colors on screen, full-bright over the lit board. */
    private static void verifyCloseup(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        view.boardCamera.center(BoardGeometry.center(OWN, 0).lerp(BoardGeometry.center(ENEMY, 0), .5f));
        view.boardCamera.zoom(BoardGeometry.HEIGHT / 300 / view.boardCamera.camera.zoom);
        render(view);
        capture("tactical-view-icons-closeup.png");
        OrthographicCamera camera = view.boardCamera.camera;
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            for (Coords coords : List.of(OWN, ENEMY)) {
                ModelInstance icon = icon(scene, icons, coords);
                // The middle of the square's left side: clear of the tick, the name label and the hex numbers.
                Vector3 side = camera.project(new Vector3(-.5f, 0, 0).mul(icon.transform), 0, 0,
                      camera.viewportWidth, camera.viewportHeight);
                int rgba = pixel(frame, side.x, side.y);
                Color color = coords.equals(ENEMY) ? UiTheme.CORAL : UiTheme.MINT;
                Color expected = frameColor(view, coords, color);
                System.out.printf("Frame pixel at %s: #%08X, expected #%08X%n", coords, rgba, Color.rgba8888(expected));
                for (int shift : new int[] { 24, 16, 8 }) {
                    assertEquals((Color.rgba8888(expected) >>> shift) & 255, (rgba >>> shift) & 255, 8,
                          "The frame of the unit at " + coords + " shows its side's color");
                }
            }
        } finally {
            frame.dispose();
        }
    }

    /**
     * Zooming scales each icon with its hex, like the classic 2D board's units: on hexes from 20 to 800 HUD pixels
     * wide, the icon's side stays 0.7 hex heights on screen.
     */
    private static void verifyScreenSize(GpuBattleView view, BoardScene scene, GpuUnitIcons icons) throws Exception {
        float scale = layoutScale(view);
        float share = GpuUnitIcons.SIZE_IN_HEXES * BoardGeometry.HEIGHT / BoardGeometry.WIDTH;
        for (float hexPixels : new float[] { 60, 300, 800, 20 }) {
            for (Coords coords : List.of(OWN, ENEMY)) {
                view.boardCamera.center(BoardGeometry.center(coords, 0));
                view.boardCamera.zoom(BoardGeometry.WIDTH / (hexPixels * scale) / view.boardCamera.camera.zoom);
                render(view);
                ModelInstance icon = icon(scene, icons, coords);
                float side = screen(view, new Vector3(-.5f, 0, 0).mul(icon.transform))
                      .dst(screen(view, new Vector3(.5f, 0, 0).mul(icon.transform)));
                float width = screen(view, BoardGeometry.corner(coords, 0, 3))
                      .dst(screen(view, BoardGeometry.corner(coords, 0, 0)));
                System.out.printf("Hex at %s %.1f pixels wide: icon side %.1f pixels (%.3f of the hex)%n", coords,
                      width, side, side / width);
                assertEquals(hexPixels * scale, width, .01f * hexPixels * scale, "The camera zooms to the hex size");
                assertEquals(share, side / width, .01f * share,
                      "The icon at " + coords + " keeps its share of a hex " + hexPixels + " HUD pixels wide");
            }
            // Both captures are centred on the enemy's icon.
            if (hexPixels == 800) { capture("tactical-view-zoom-in.png"); }
            if (hexPixels == 20) { capture("tactical-view-zoom-out.png"); }
        }
    }

    /**
     * The client's selection, an own unit that has already moved and a doomed enemy, through the real capture: the
     * selected unit's frame is bold and white, the moved unit is veiled, and the doomed one is crossed out and faded.
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
        assertTrue(part(own, "bold").enabled && !part(own, "frame").enabled, "The selected unit's frame is bold");
        assertEquals(Color.WHITE, color(own, "bold"), "The selected unit's frame is white");
        assertEquals(UiTheme.MINT, color(own, "tick"), "The tick keeps the side's color");
        assertTrue(part(own, "veil").enabled && !part(own, "cross").enabled, "An own unit that has moved is veiled");
        ModelInstance doomed = icon(scene, icons, ENEMY);
        assertTrue(part(doomed, "cross").enabled && !part(doomed, "veil").enabled, "A doomed unit is crossed out");
        assertEquals(.45f, color(doomed, "tick").a, .001f, "A doomed unit's icon fades");
        assertEquals(.88f * .45f, color(doomed, "fill").a, .001f);
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
        assertTrue(part(own, "frame").enabled && !part(own, "bold").enabled && !part(own, "veil").enabled);
        assertTrue(!part(doomed, "cross").enabled && color(doomed, "tick").a == 1, "The enemy is whole again");
    }

    /**
     * The game removes the destroyed enemy and leaves its wreck, through the real capture: the battle status no longer
     * lists it, so its icon is grey, and its dead pose crosses the icon out and fades it, as hud-v3 shows a destroyed
     * unit.
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
        assertTrue(part(wreck, "cross").enabled && !part(wreck, "veil").enabled, "The wreck is crossed out");
        Color frame = color(wreck, "frame");
        Color side = frameColor(view, ENEMY, UiTheme.ACCENT);
        assertEquals(side.r, frame.r, .001f, "A wreck of no listed side is grey");
        assertEquals(side.a * .45f, frame.a, .001f, "and faded");
        assertEquals(.45f, color(wreck, "sprite").a, .001f);
        assertFalse(part(icon(scene, icons, OWN), "cross").enabled, "The own unit stays whole");
        capture("tactical-view-wreck.png");
    }

    private static float layoutScale(GpuBattleView view) throws Exception {
        return (float) field(view, "layoutScale");
    }

    /** Window coordinates (y down) of a world point. */
    private static Vector3 screen(GpuBattleView view, Vector3 world) {
        OrthographicCamera camera = view.boardCamera.camera;
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

    /** Real tileset canopy pixels outside the hex must survive the lit flat-sprite draw. */
    private static void verifySpriteSilhouette(BoardScene scene) {
        var coords = new Coords(0, 0);
        var tile = scene.tiles().stream().filter(item -> item.foliage() != null && !item.liquid().present()
                    && item.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.TREE))
              .findFirst().orElseThrow();
        var art = tile.foliage();
        var flat = new BoardScene.Tile(coords, 0, -1, false, 0, tile.surface(), tile.ground(),
              null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, art);
        var isolated = new BoardScene(0, 1, 1, List.of(flat), List.of(), List.of(), -1, "", List.of());
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
            terrain.setFlatFeatures(true);
            target.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            terrain.renderTransparent(camera);
            rendered = Pixmap.createFromFrameBuffer(0, 0, art.width(), art.height());
            target.end();
            int overhang = 0;
            int missing = 0;
            for (int y = 0; y < art.height(); y++) {
                for (int x = 0; x < art.width(); x++) {
                    float worldX = (x + .5f) / art.width() * BoardGeometry.WIDTH;
                    float worldY = -(y + .5f) / art.height() * BoardGeometry.HEIGHT;
                    if (opaque(art, x, y) && !BoardGeometry.contains(coords, worldX, worldY)) {
                        overhang++;
                        if ((rendered.getPixel(x, art.height() - y - 1) & 255) < 200) { missing++; }
                    }
                }
            }
            PixmapIO.writePNG(Gdx.files.absolute(new File(System.getProperty("megamek.gpu.screenshots",
                  "build/gpu-board-review"), "tactical-view-sprite-full.png").getAbsolutePath()), rendered, -1, true);
            assertTrue(overhang > 0, "The real artwork must exercise canopies extending outside the hex");
            assertEquals(0, missing, "Canopy pixels outside the hex must survive the flat render");
        } finally {
            if (rendered != null) { rendered.dispose(); }
            target.dispose();
            terrain.dispose();
        }
    }

    /** Sprites are lit before the atmosphere composite, so the shared field of view darkens only unseen art. */
    private static void verifyFieldOfView(GpuBattleView view, BoardScene scene) {
        Slider darkness = GpuBoardTestUi.tuning(view, "FoV darkness");
        float[] darkened = spriteLuminance(view, scene);
        darkness.setValue(0);
        render(view);
        float[] plain = spriteLuminance(view, scene);
        darkness.setValue(GpuFieldOfView.FOV_DARKNESS * 100);
        render(view);
        System.out.printf("Sprite luminance with/without FoV darkness: seen %.3f/%.3f, unseen %.3f/%.3f on %s%n",
              darkened[0], plain[0], darkened[1], plain[1], Gdx.gl.glGetString(GL20.GL_RENDERER));
        assertTrue(plain[0] > 0 && plain[1] > 0, "Both seen and unseen sprites must be sampled");
        assertEquals(plain[0], darkened[0], .02f, "Seen sprites keep their lit colors");
        assertTrue(darkened[1] < plain[1] * .9f, "Unseen sprites darken like the ground");
        assertTrue(darkened[1] < darkened[0], "An unseen sprite is darker than a seen one");
    }

    /** Mean luminance of opaque sprite pixels inside their own hexes: {seen, unseen}, away from units and labels. */
    private static float[] spriteLuminance(GpuBattleView view, BoardScene scene) {
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            float[] sum = new float[2];
            int[] count = new int[2];
            for (BoardScene.Tile tile : scene.tiles()) {
                var art = tile.foliage();
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

    /** Board offset from the hex centre of a sprite's art pixel; the flat quad spans one hex's tile rectangle. */
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
            var art = scene.tile(coords).foliage();
            // The opaque sprite pixel farthest from the centre that still lies well inside its own hex.
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
            assertEquals(coords, pick(view, screen(view, coords, edge)), "A sprite pixel picks its own hex");
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
        // The own unit is hovered; verifyStates covers the client's selection.
        icons.update(true, view.boardCamera.camera, scene, status, unit -> false,
              unit -> unit != enemy, moved, new HashMap<>(), new BoardSurface.Cache());
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
        for (float x : new float[] { -.4f, .4f }) {
            for (float y : new float[] { -.4f, .4f }) {
                Vector3 point = new Vector3(x, y, 0).mul(icon.transform);
                if (corner == null || point.dst2(hexCenter) > corner.dst2(hexCenter)) { corner = point; }
            }
        }
        ground = terrain.hit(scene, new Ray(new Vector3(corner.x, corner.y, 1000), new Vector3(0, 0, -1)));
        assertNotEquals(ENEMY, ground.coords());
        assertEquals(ENEMY, pick(view, screen(view, corner)), "A click off the icon's centre picks its unit");
        assertEquals(UiTheme.CORAL, color(icon, "frame"), "A unit neither marked nor hovered keeps its color");
        ModelInstance own = icon(scene, icons, OWN);
        assertTrue(part(own, "frame").enabled && !part(own, "bold").enabled, "Hovering keeps the thin frame");
        assertEquals(Color.WHITE, color(own, "frame"), "A hovered unit's frame turns white");
        render(view);
        assertEquals(frameColor(view, OWN, UiTheme.MINT), color(own, "frame"), "The next frame restores it");

        // A 3D feature mesh that overhangs a neighbouring hex takes the vertical pick there; the Tactical View hides
        // it, so the hex under the pointer takes the pick.
        Overhang overhang = overhang(terrain, scene);
        assertNotNull(overhang, "A 3D feature mesh overhangs a neighbouring hex");
        System.out.printf("The 3D mesh of %s overhangs %s%n", overhang.feature(), overhang.under());
        assertEquals(overhang.under(), pick(view, screen(view, overhang.under(), overhang.offset())),
              "The hex under the pointer, not the hidden mesh of " + overhang.feature() + ", takes the pick");
    }

    private record Overhang(Coords under, Vector3 offset, Coords feature) { }

    /**
     * A point over a hex's own ground, away from units, where a 3D feature mesh of another hex takes the vertical
     * pick. Leaves the terrain flat.
     */
    private static Overhang overhang(GpuTerrain terrain, BoardScene scene) {
        for (BoardScene.Tile tile : scene.tiles()) {
            if (scene.units().stream().anyMatch(unit -> unit.location().coords().distance(tile.coords()) <= 1)) {
                continue;
            }
            for (int step = 0; step < 400; step++) {
                Vector3 offset = new Vector3((step % 20 / 19f - .5f) * BoardGeometry.WIDTH,
                      (step / 20 / 19f - .5f) * BoardGeometry.HEIGHT, 0);
                Ray ray = new Ray(BoardGeometry.center(tile.coords(), 0).add(offset).add(0, 0, 1000),
                      new Vector3(0, 0, -1));
                terrain.setFlatFeatures(false);
                var mesh = terrain.hit(scene, ray);
                terrain.setFlatFeatures(true);
                var ground = terrain.hit(scene, ray);
                if (mesh != null && ground != null && tile.coords().equals(ground.coords())
                      && !tile.coords().equals(mesh.coords())) {
                    return new Overhang(tile.coords(), offset, mesh.coords());
                }
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

    /**
     * Flat mode leaves exactly the featureless board in the colour, camera-depth and shadow passes, and adds one
     * lit quad per sprite hex after the water.
     */
    private static void verifyPasses(BoardScene captured) throws Exception {
        var light = new BoardScene.Light(-24, -30);
        var lit = new BoardScene(0, WIDTH, HEIGHT, captured.tiles(), List.of(), List.of(), -1, "", List.of(), light);
        List<BoardScene.Tile> bareTiles = new ArrayList<>();
        for (var tile : captured.tiles()) {
            bareTiles.add(featureless(tile, tile.foliage()));
        }
        var bareScene = new BoardScene(0, WIDTH, HEIGHT, bareTiles, List.of(), List.of(), -1, "", List.of(), light);
        GpuTerrain full = new GpuTerrain();
        GpuTerrain flat = new GpuTerrain();
        GpuTerrain bare = new GpuTerrain();
        ModelBatch depth = new ModelBatch(new DepthShaderProvider());
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        try {
            full.update(lit);
            flat.update(lit);
            flat.setFlatFeatures(true);
            bare.update(bareScene);
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(false);
            camera.fit(lit);
            profiler.enable();
            int[] shadows = new int[3];
            int[] colour = new int[3];
            int[] depths = new int[3];
            int[] transparent = new int[3];
            List<GpuTerrain> terrains = List.of(full, flat, bare);
            for (int index = 0; index < 3; index++) {
                GpuTerrain terrain = terrains.get(index);
                shadows[index] = vertices(profiler, () -> terrain.renderShadows(camera.camera, List.of()));
                colour[index] = vertices(profiler, () -> terrain.render(camera.camera, false));
                depths[index] = vertices(profiler, () -> terrain.renderDepth(camera.camera, List.of(), depth));
                transparent[index] = vertices(profiler, () -> terrain.renderTransparent(camera.camera));
            }
            long sprites = captured.tiles().stream().filter(tile -> tile.foliage() != null).count();
            assertEquals(shadows[2], shadows[1], "Only the featureless board casts shadows");
            assertEquals(depths[2], depths[1], "Only the featureless board writes camera depth");
            assertEquals(colour[2], colour[1], "No feature mesh or scatter enters the colour pass");
            assertTrue(shadows[0] > shadows[1] && depths[0] > depths[1] && colour[0] > colour[1],
                  "The 3D board draws its feature meshes");
            assertEquals(6 * sprites, transparent[1] - transparent[2], "One lit quad per sprite hex");
            assertEquals(transparent[2], transparent[0], "The 3D board draws no sprite");
            profiler.disable();
            int[] bridge = coveredBridgePixels(flat, camera, captured.tile(BRIDGE));
            // Filtering may blend a few edge texels with their transparent neighbors; the banks would hide far more.
            assertTrue(bridge[1] > 100 && bridge[0] * 100 < bridge[1],
                  "The river banks cover " + bridge[0] + " of " + bridge[1] + " bridge pixels");

            // A destroyed bridge leaves the sprite atlas layout alone, so it does not rebuild the whole board.
            Texture page = spritePage(flat, BUILDING);
            var destroyed = new BoardScene(0, WIDTH, HEIGHT, captured.tiles().stream()
                  .map(tile -> tile.coords().equals(BRIDGE) ? featureless(tile, null) : tile).toList(),
                  List.of(), List.of(), -1, "", List.of(), light);
            flat.update(destroyed);
            assertSame(page, spritePage(flat, BUILDING), "The sprite atlas keeps its pages");
            profiler.enable();
            assertEquals(transparent[1] - 6, vertices(profiler, () -> flat.renderTransparent(camera.camera)),
                  "The bridge art leaves with the bridge");
        } finally {
            profiler.disable();
            depth.dispose();
            full.dispose();
            flat.dispose();
            bare.dispose();
        }
    }

    private static BoardScene.Tile featureless(BoardScene.Tile tile, BoardScene.Pixels sprite) {
        return new BoardScene.Tile(tile.coords(), tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
              tile.surface(), tile.ground(), tile.normals(), tile.decals(), tile.decalsWithoutLimbs(), tile.tactical(),
              List.of(), tile.text(), tile.liquid(), sprite);
    }

    /** The atlas page that holds a hex's sprite art; a full terrain rebuild replaces every page. */
    @SuppressWarnings("unchecked")
    private static Texture spritePage(GpuTerrain terrain, Coords coords) throws Exception {
        return ((GpuTextures<Coords>) field(terrain, "foliage")).region(coords).getTexture();
    }

    /** {covered, tested}: bright opaque bridge art pixels left black when water and sprites draw over the depth. */
    private static int[] coveredBridgePixels(GpuTerrain flat, BoardCamera camera, BoardScene.Tile bridge) {
        ScreenUtils.clear(0, 0, 0, 0, true);
        flat.render(camera.camera, false);
        ScreenUtils.clear(0, 0, 0, 0, false);
        flat.renderTransparent(camera.camera);
        Pixmap frame = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight());
        try {
            var art = bridge.foliage();
            int covered = 0;
            int tested = 0;
            for (int y = 0; y < art.height(); y++) {
                for (int x = 0; x < art.width(); x++) {
                    int rgba = art.rgba(y * art.width() + x);
                    if (!opaque(art, x, y) || ((rgba >>> 24) & 255) + ((rgba >>> 16) & 255) < 80) { continue; }
                    tested++;
                    Vector3 world = BoardGeometry.center(bridge.coords(), 0).add(offset(art, x, y));
                    Vector3 point = camera.camera.project(world, 0, 0, Gdx.graphics.getWidth(),
                          Gdx.graphics.getHeight());
                    int shown = pixel(frame, point.x, point.y);
                    if (((shown >>> 24) & 255) + ((shown >>> 16) & 255) + ((shown >>> 8) & 255) < 12) { covered++; }
                }
            }
            return new int[] { covered, tested };
        } finally {
            frame.dispose();
        }
    }

    private static int vertices(GLProfiler profiler, Runnable draw) {
        profiler.reset();
        draw.run();
        return (int) profiler.getVertexCount().total;
    }
}
