/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.gdx.UiButton;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.preference.PreferenceManager;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The G4 components in the component harness: the top-right utilities, the Tactical View chip and north mark, the hint
 * line and the minimap over the shipped board in 3D and in the Tactical View, beside shots 01, 07, 08 and 15; their
 * layout at the three reference sizes; and their commands: the utilities and the minimap's close button run the
 * camera, the View menu's minimap item and the HUD's own toggles, the minimap takes its presses and its drag moves
 * only the camera, up to the board's edge, its hexes show their tileset art's colours, and the Tactical View zooms
 * within the 3D view's limits.
 */
@Tag("on-demand")
class GpuUtilityMinimapSmokeTest {
    private static final int[][] SIZES = { { 900, 600 }, { 1280, 720 }, { 1920, 1080 } };
    /** GpuHud puts the developer tuning utility after the row, one gap on: 62 wide, 54 at W <= 1350. */
    private static final float TUNING_WIDE = 62;
    private static final float TUNING_NARROW = 54;
    /** The Atlas's planned route of the fixture: three hexes north. */
    private static final List<GpuMovePlan.Step> ROUTE = Stream.of(new Coords(14, 13), new Coords(14, 12),
          new Coords(14, 11), new Coords(14, 10))
          .map(coords -> new GpuMovePlan.Step(coords, 0, 0, 0, GpuMovePlan.Band.WALK, false)).toList();
    private static final GpuBoardSource.UiPreferences PREFERENCES = preferences();

    /** The three components as GpuHud builds them, with the View menu's minimap item they run. */
    private static final class Parts {
        final GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        final GpuBoardSource source = mock(GpuBoardSource.class);
        final AtomicInteger minimapRuns = new AtomicInteger();
        final List<BoardScene.Command> global;
        final GpuUtilityBar utilities;
        final GpuHintLine hint;
        final GpuMinimap minimap;
        /** The client's preferences the frames carry. */
        GpuBoardSource.UiPreferences preferences = PREFERENCES;

        Parts(GpuHudTestStage hud, BoardCamera camera) {
            BoardScene.Command item = new BoardScene.Command("View:View/" + ClientGUI.VIEW_MINI_MAP + ":Minimap",
                  "Minimap", "", true, false, false, List.of(), minimapRuns::incrementAndGet, "Ctrl+M", true);
            global = List.of(new BoardScene.Command("View:View", "View", "", true, false, false, List.of(item),
                  () -> { }, "", null));
            utilities = new GpuUtilityBar(hud.kit, source, state, camera, new GpuBoardTuning(hud.kit.ui.skin));
            hint = new GpuHintLine(hud.kit, source, state, camera);
            minimap = new GpuMinimap(hud.kit, source, state, camera);
            for (Actor actor : List.of(utilities.north(), utilities.actor(), utilities.chip(), hint.actor(),
                  minimap.actor())) {
                hud.window.addActor(actor);
            }
        }

        /** One frame's snapshots, as GpuHud hands them to every component. */
        GpuHud.Inputs update(BoardScene scene, GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move,
              GpuReportLog.Snapshot reports, boolean tactical, int width, int height) {
            // As GpuBattleView before the HUD: the history takes the log and presents the steps no shot waits for.
            state.history.accept(List.of(), scene, reports, unit -> false);
            state.history.advance(0, playback -> true);
            state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
            GpuBoardSource.Frame frame = new GpuBoardSource.Frame(scene, List.of(), null, global, "", null, 0,
                  "", null, reports, status, GpuHudInputTest.panels(move, GpuFireOrders.Snapshot.EMPTY,
                        GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY));
            GpuHud.Inputs inputs = new GpuHud.Inputs(frame, new GpuHud.HudView(tactical, false, Map.of(), Map.of(),
                  Map.of(), null, Entity.NONE, 0), null, preferences, GpuHud.Metrics.of(width, height), List.of());
            utilities.update(inputs);
            hint.update(inputs);
            minimap.update(inputs);
            return inputs;
        }

        /**
         * Places the components at the prototype's anchors (r1 section 2), which GpuHud's layout uses: utilities at
         * the right gap and top 18, the minimap at top 90 in the right column's width, the chip centred at top 20, the
         * Tactical View's north mark centred at its own top, and the hint line centred at bottom 7, hidden at
         * W <= 1350. {@code tuning} reserves the tuning utility's room.
         */
        void place(GpuHud.Metrics metrics, boolean tuning) {
            float width = metrics.width();
            float height = metrics.height();
            Table bar = (Table) utilities.actor();
            bar.pack();
            float room = tuning ? GpuUtilityBar.GAP + (metrics.narrow() ? TUNING_NARROW : TUNING_WIDE) : 0;
            bar.setPosition(width - metrics.gap() - room - bar.getWidth(), height - 18 - bar.getHeight());
            Table map = (Table) minimap.actor();
            map.setSize(metrics.right(), map.getPrefHeight());
            map.validate();
            map.setPosition(width - metrics.gap() - metrics.right(), height - 90 - map.getHeight());
            Table chip = (Table) utilities.chip();
            chip.pack();
            chip.setPosition(Math.round((width - chip.getWidth()) / 2), height - 20 - chip.getHeight());
            Label north = (Label) utilities.north();
            north.pack();
            north.setPosition(Math.round((width - north.getWidth()) / 2),
                  height - GpuUtilityBar.NORTH_TOP - north.getHeight());
            Table line = (Table) hint.actor();
            line.pack();
            line.setPosition(Math.round((width - line.getWidth()) / 2), 7);
            line.setVisible(!metrics.narrow());
        }
    }

    @Test
    void utilitiesChipHintAndMinimapBesideTheMock() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        System.out.println("Tileset " + PreferenceManager.getClientPreferences().getMapTileset());
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera);
            try {
                GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
                GpuBattleStatus.Snapshot initiative = status(fixture, GamePhase.INITIATIVE_REPORT, false, Entity.NONE);
                GpuBattleStatus.Snapshot firing = status(fixture, GamePhase.FIRING, true, GpuHudFixtures.ATLAS);

                GpuMovePlan.Snapshot none = GpuMovePlan.Snapshot.EMPTY;
                GpuReportLog.Snapshot quiet = GpuReportLog.Snapshot.EMPTY;
                // Shot 01: initiative in 3D, the minimap filling its panel, Map and Contacts pressed.
                render(hud, board, parts, scene, initiative, none, quiet, false, 1920, 1080,
                      "g4-initiative-1920x1080", "01-initiative.jpg", true);
                // Shot 03: the own movement turn with a planned route on the minimap and the planner's hint.
                render(hud, board, parts, scene, fixture, move(), quiet, false, 1920, 1080,
                      "g4-movement-1920x1080", "03-waypoint-route.jpg", true);
                // Shot 07: the Tactical View while declaring attacks, with the chip and the Map overview.
                render(hud, board, parts, scene, firing, none, quiet, true, 1920, 1080,
                      "g4-tactical-1920x1080", "07-tactical-view.jpg", true);
                // Shot 08: the Log utility's badge counts the round's reviewable events the board has presented,
                // none while the volley's shots wait in the playback.
                GpuBattleStatus.Snapshot report = status(fixture, GamePhase.FIRING_REPORT, false, Entity.NONE);
                List<BoardScene.Combat> shots = shots(scene, 4);
                GpuReportLog.Snapshot volley = volley(shots);
                parts.state.history.accept(List.copyOf(shots), scene, volley, unit -> false);
                parts.update(scene, report, none, volley, false, 1920, 1080);
                assertEquals(null, badge(parts.utilities), "No badge before the shots land");
                for (int step = 0; step < 200 && parts.state.history.running(); step++) {
                    parts.state.history.advance(.1, playback -> true);
                }
                render(hud, board, parts, scene, report, none, volley, false, 1920, 1080, "g4-playback-1920x1080",
                      "08-weapon-playback.jpg", false);
                assertEquals("4", badge(parts.utilities), "The Log utility counts the presented events");
                // Shot 15: the compact 1280 x 720 window: narrow utilities and a 120-unit canvas (GpuHud hides the
                // hint line there).
                render(hud, board, parts, scene, firing, none, quiet, false, 1280, 720,
                      "g4-compact-1280x720", "15-compact-1280x720.jpg", true);

                // E rule 6: every part in the window, clear of the others and of the tuning utility's room.
                for (int[] size : SIZES) {
                    hud.size(size[0], size[1]);
                    GpuHud.Metrics metrics = GpuHud.Metrics.of(size[0], size[1]);
                    for (boolean tactical : new boolean[] { false, true }) {
                        parts.update(scene, firing, move(), GpuReportLog.Snapshot.EMPTY, tactical, size[0], size[1]);
                        parts.place(metrics, true);
                        assertLayout(hud, parts, metrics, tactical);
                    }
                }
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void theCommandsMoveOnlyTheCameraAndRunTheMenuItemAndTheMinimapShowsTheTileArt() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera);
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                board.view(false);
                show(hud, board, parts, scene, moving, move(), false);
                assertEquals(null, badge(parts.utilities), "No badge without reviewable events");
                checkUtilities(hud, board, parts, scene, moving);
                checkMinimapDrag(hud, board, parts, scene, moving);
                checkTileColours(hud, board, parts, scene, moving);
                checkTacticalZoom(board);
                checkHint(parts, scene);
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    /**
     * Item 33.4: the Contacts utility shows MekBay's enemy icon and is pressed while the client's contacts preference
     * shows the panel; the row is captured pressed and released, and the minimap and chip as the user asked them.
     */
    @Test
    void theContactsUtilityFollowsThePreferenceWithTheEnemyIcon() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            Parts parts = new Parts(hud, board.camera);
            try {
                UiButton contacts = hud.stage.getRoot().findActor("utility-contacts");
                assertSame(hud.kit.ui.skin.getDrawable("icon-enemy"), ((Image) contacts.icons.getFirst())
                      .getDrawable(), "MekBay's enemy icon");
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                for (boolean enabled : new boolean[] { true, false }) {
                    parts.preferences = contacts(PREFERENCES, enabled);
                    board.view(false);
                    board.camera.zoom(.55f);
                    show(hud, board, parts, scene, moving, move(), false);
                    assertEquals(enabled, contacts.isChecked(), "pressed while the panel shows");
                    hud.capture("ux2-utilities-contacts-" + (enabled ? "on" : "off")).dispose();
                }
                parts.preferences = PREFERENCES;
                board.view(true);
                show(hud, board, parts, scene, moving, move(), true);
                hud.capture("ux2-tactical-chip").dispose();
            } finally {
                parts.minimap.dispose();
                board.dispose();
            }
        });
    }

    /** The preferences with the contacts panel shown or hidden. */
    private static GpuBoardSource.UiPreferences contacts(GpuBoardSource.UiPreferences p, boolean enabled) {
        return new GpuBoardSource.UiPreferences(p.scale(), p.reportKeywords(), p.reportFilterKeywords(),
              p.minimapEnabled(), enabled, p.conditionsVisible(), p.turnDetails(), p.binds(),
              p.moveSprintRgb());
    }

    /** The utilities run the camera's Tactical View, the View menu's minimap item and the HUD's panel toggles. */
    private static void checkUtilities(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        click(hud, "utility-map");
        assertEquals(1, parts.minimapRuns.get(), "Map runs the View menu's minimap item");
        click(hud, "minimap-close");
        assertEquals(2, parts.minimapRuns.get(), "The minimap's close button runs the same item");
        click(hud, "utility-log");
        assertTrue(parts.state.logOpen());
        click(hud, "utility-help");
        assertEquals(GpuHudState.Dialog.HELP, parts.state.dialog);
        click(hud, "utility-menu");
        assertEquals(GpuHudState.Dialog.MENU, parts.state.dialog, "Menu replaces Help");
        click(hud, "utility-menu");
        assertEquals(GpuHudState.Dialog.NONE, parts.state.dialog);
        click(hud, "utility-log");
        assertFalse(parts.state.logOpen());
        List<String> row = new ArrayList<>();
        ((Table) parts.utilities.actor()).getChildren().forEach(child -> row.add(child.getName()));
        assertEquals(List.of("utility-tactical", "utility-wireframe", "utility-map", "utility-contacts",
              "utility-log", "utility-help", "utility-menu"), row, "Wireframe follows Tactical view, Menu takes the "
              + "place of Settings, Contacts follows Map, and there is no Home");

        click(hud, "utility-tactical");
        assertTrue(board.camera.tactical(), "Tactical view enters the Tactical View");
        show(hud, board, parts, scene, status, move(), true);
        assertTrue(parts.utilities.chip().isVisible());
        // The chip names the mode only (the user's decision of 2026-10-02): the pressed utility switches back.
        List<String> chip = new ArrayList<>();
        boolean button = false;
        for (Actor child : ((Table) parts.utilities.chip()).getChildren()) {
            if (child instanceof Label label) {
                chip.add(label.getText().toString());
            }
            button |= child instanceof Button;
        }
        assertEquals(List.of(UiTheme.upper(text("GpuBoard.hud.util.tactical"))), chip, "The chip's caption");
        assertFalse(button, "No Back to 3D button");
        click(hud, "utility-tactical");
        assertFalse(board.camera.tactical(), "Tactical view again returns to the 3D view");
        show(hud, board, parts, scene, status, move(), false);
        assertFalse(parts.utilities.chip().isVisible());
        verifyNoInteractions(parts.source);
        // The Contacts utility posts the switch of the client's contacts preference to the Swing thread.
        click(hud, "utility-contacts");
        verify(parts.source).command(any());
        clearInvocations(parts.source);
    }

    /**
     * A press on the minimap centres the camera on that ground point and a drag follows the pointer, in both views,
     * up to the board's edge. The canvas takes the press and the drag, so the board's click path, which would plan a
     * route, never gets them, and the minimap calls nothing of the client: the planned route stays a draft.
     */
    private static void checkMinimapDrag(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        for (boolean tactical : new boolean[] { false, true }) {
            board.view(tactical);
            show(hud, board, parts, scene, status, move(), tactical);
            Actor canvas = hud.stage.getRoot().findActor("minimap-canvas");
            Rectangle area = GpuHudTestStage.bounds(canvas);
            float azimuth = board.camera.azimuth();
            float tilt = board.camera.tilt();
            float z = board.camera.focus.z;
            Vector2 middle = new Vector2(area.x + area.width / 2, area.y + area.height / 2);
            // GpuHud.hit keeps a press from the board wherever its stage finds an actor: here the canvas.
            assertSame(canvas, hud.stage.hit(middle.x, middle.y, true), "The canvas takes presses on it");
            Vector2 screen = hud.stage.stageToScreenCoordinates(middle.cpy());
            int x = Math.round(screen.x);
            int y = Math.round(screen.y);
            assertTrue(hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT), "The canvas consumes the press");
            Vector2 pressed = hud.stage.screenToStageCoordinates(new Vector2(x, y));
            Vector3 centre = board.camera.focus.cpy();
            // The board is centred on the canvas, so its middle lies under the canvas's middle.
            float units = (BoardCamera.boardWidth(scene) + BoardCamera.boardHeight(scene)) / (area.width + area.height);
            assertEquals(BoardCamera.boardWidth(scene) / 2, centre.x, 2 * units, "Press centres the camera on x");
            assertEquals(-BoardCamera.boardHeight(scene) / 2, centre.y, 2 * units, "Press centres the camera on y");
            assertEquals(z, centre.z, "The camera keeps its focus plane");
            assertTrue(hud.stage.touchDragged(x + 40, y, 0), "The canvas consumes the drag");
            assertTrue(board.camera.focus.x > centre.x + units, "A drag east moves the camera east");
            assertEquals(centre.y, board.camera.focus.y, .01f * units, "A drag east keeps the camera's north");
            // Far past the canvas's lower left, the camera stops at the board's west and south edges.
            assertTrue(hud.stage.touchDragged(x - 1000, y + 1000, 0), "The canvas keeps the drag off the canvas");
            assertEquals(0, board.camera.focus.x, .001f, "A drag off the canvas stops at the west edge");
            assertEquals(-BoardCamera.boardHeight(scene), board.camera.focus.y, .001f,
                  "A drag off the canvas stops at the south edge");
            hud.stage.touchUp(x - 1000, y + 1000, 0, Input.Buttons.LEFT);
            assertEquals(azimuth, board.camera.azimuth(), "The drag pans only");
            assertEquals(tilt, board.camera.tilt(), "The drag pans only");
            assertEquals(z, board.camera.focus.z, "The drag keeps the focus plane");
            // The wheel over the map zooms the camera by the board's step, about the view's centre (the user's
            // decision of 2026-10-03); off the map the wheel is not the map's.
            // The stage fires enter and exit in act, as each frame does.
            hud.stage.mouseMoved(x, y);
            hud.stage.act(0);
            Vector3 focus = board.camera.focus.cpy();
            float zoom = board.camera.camera.zoom;
            assertTrue(hud.stage.scrolled(0, 2), "The map takes the wheel under the pointer");
            assertEquals(zoom * GpuBattleView.wheelZoom(2), board.camera.camera.zoom, zoom * 1e-4f,
                  "Two notches zoom out by the board's step");
            assertTrue(focus.epsilonEquals(board.camera.focus, .001f), "The wheel keeps the view's centre");
            hud.stage.mouseMoved(x - 1000, y);
            hud.stage.act(0);
            assertFalse(hud.stage.scrolled(0, 1), "Off the map the wheel is not the map's");
            System.out.printf("Minimap press at %s centred the camera at %s (%s view)%n", pressed, centre,
                  tactical ? "tactical" : "3D");
        }
        board.view(false);
        verifyNoInteractions(parts.source);
    }

    /**
     * Each hex shows its art's colour, found through the minimap's own mapping: a press gives the ground point under
     * the pointer. A hex without trees shows the mean of its tileset art; a hex with woods differs from it; a water
     * hex shows the first frame of its water animation as the board decodes it, and a hazardous liquid's hex the same
     * in the tint of the board's liquid material; a hex whose liquid art cannot be read shows its tileset art. The
     * captures have no route or units, and the sampled hexes lie three hexes inside the board, clear of the frustum.
     */
    private static void checkTileColours(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts,
          BoardScene scene, GpuBattleStatus.Snapshot status) {
        // The board's decoder, timed here before the minimap's first liquid.
        long start = System.nanoTime();
        GpuAssets.Animation<BoardScene.Pixels> water = GpuAssets.readWater(
              GpuAssets.tilesetFile("saxarba/anim_water_1.gif"));
        System.out.printf("GpuAssets.readWater(saxarba/anim_water_1.gif): %d frames in %.1f ms%n",
              water.frames().size(), (System.nanoTime() - start) / 1e6);
        show(hud, board, parts, scene, withoutUnits(status), GpuMovePlan.Snapshot.EMPTY, false);
        Pixmap image = hud.capture("g4-minimap-colours");
        try {
            Rectangle area = GpuHudTestStage.bounds(hud.stage.getRoot().findActor("minimap-canvas"));
            Vector2 first = new Vector2(area.x + area.width / 2, area.y + area.height / 2);
            Vector2 second = first.cpy().add(40, 20);
            Vector3 origin = press(hud, board, first);
            Vector3 other = press(hud, board, second);
            Vector2 scale = new Vector2((second.x - first.x) / (other.x - origin.x),
                  (second.y - first.y) / (other.y - origin.y));
            List<BoardScene.Tile> inner = scene.tiles().stream().filter(tile -> tile.coords().getX() >= 3
                  && tile.coords().getY() >= 3 && tile.coords().getX() < scene.width() - 3
                  && tile.coords().getY() < scene.height() - 3).toList();
            BoardScene.Tile open = inner.stream().filter(tile -> !wooded(tile) && !tile.water()
                  && tile.bridge() == null && tile.tileset() != null).findFirst().orElseThrow();
            BoardScene.Tile wooded = inner.stream().filter(GpuUtilityMinimapSmokeTest::wooded).findFirst()
                  .orElseThrow();
            int openColour = sample(image, first, origin, scale, open.coords());
            int expected = mean(open.tileset());
            System.out.printf("Open hex %s: minimap #%08X, tileset art mean #%08X%n", open.coords(), openColour,
                  expected);
            for (int shift : new int[] { 24, 16, 8 }) {
                assertEquals(expected >>> shift & 255, openColour >>> shift & 255, 6,
                      "A hex without trees shows its tileset art's mean colour");
            }
            int woodedColour = sample(image, first, origin, scale, wooded.coords());
            System.out.printf("Wooded hex %s: minimap #%08X%n", wooded.coords(), woodedColour);
            assertNotEquals(openColour, woodedColour, "A wooded hex shows its trees");

            // Three more bare hexes made depth-1 liquid: water shows the first frame of its water animation, not its
            // ground; a hazardous liquid shows the same frame in the board's tint; a liquid that names art that does
            // not exist keeps the ground.
            List<BoardScene.Tile> dry = inner.stream().filter(tile -> !tile.water()
                  && tile.coords().distance(open.coords()) >= 3).toList();
            BoardScene.Tile wet = dry.getFirst();
            BoardScene.Tile unreadable = dry.stream().filter(tile -> tile.coords().distance(wet.coords()) >= 3)
                  .findFirst().orElseThrow();
            BoardScene.Tile hazardous = dry.stream().filter(tile -> tile.coords().distance(wet.coords()) >= 3
                  && tile.coords().distance(unreadable.coords()) >= 3).findFirst().orElseThrow();
            board.view(false);
            BoardScene flooded = withLiquid(withLiquid(withLiquid(scene, wet.coords(), BoardLiquid.WATER),
                  unreadable.coords(), new BoardLiquid(BoardLiquid.Kind.WATER, "missing", 0)), hazardous.coords(),
                  new BoardLiquid(BoardLiquid.Kind.HAZARDOUS, "", 0));
            show(hud, board, parts, flooded, withoutUnits(status), GpuMovePlan.Snapshot.EMPTY, false);
            Pixmap liquids = hud.capture("g4-minimap-water");
            try {
                int waterColour = sample(liquids, first, origin, scale, wet.coords());
                int waterArt = mean(water.frames().getFirst());
                System.out.printf("Water hex %s: minimap #%08X, first water frame mean #%08X%n", wet.coords(),
                      waterColour, waterArt);
                int hazardousColour = sample(liquids, first, origin, scale, hazardous.coords());
                int tinted = Color.rgba8888(new Color(waterArt).mul(BoardLiquid.HAZARDOUS_TINT));
                System.out.printf("Hazardous liquid hex %s: minimap #%08X, tinted water frame mean #%08X%n",
                      hazardous.coords(), hazardousColour, tinted);
                int unreadableColour = sample(liquids, first, origin, scale, unreadable.coords());
                int art = mean(unreadable.tileset());
                System.out.printf("Unreadable liquid hex %s: minimap #%08X, tileset art mean #%08X%n",
                      unreadable.coords(), unreadableColour, art);
                for (int shift : new int[] { 24, 16, 8 }) {
                    assertEquals(waterArt >>> shift & 255, waterColour >>> shift & 255, 6,
                          "The water hex shows its water art's mean colour");
                    assertEquals(tinted >>> shift & 255, hazardousColour >>> shift & 255, 6,
                          "The hazardous liquid's hex shows its water art in the board's tint");
                    assertEquals(art >>> shift & 255, unreadableColour >>> shift & 255, 6,
                          "A hex whose liquid art cannot be read shows its tileset art");
                }
            } finally {
                liquids.dispose();
            }
        } finally {
            image.dispose();
        }
    }

    private static boolean wooded(BoardScene.Tile tile) {
        return tile.features().stream().anyMatch(feature -> feature.kind() == BoardScene.FeatureKind.TREE);
    }

    /** The scene with one hex turned into open liquid of depth 1, as the source captures a water hex. */
    private static BoardScene withLiquid(BoardScene scene, Coords coords, BoardLiquid liquid) {
        List<BoardScene.Tile> tiles = scene.tiles().stream().map(tile -> !tile.coords().equals(coords) ? tile
              : new BoardScene.Tile(tile.coords(), tile.elevation(), 1, false, tile.roadExits(), tile.surface(),
                    tile.ground(), tile.normals(), tile.decals(), tile.decalsWithoutLimbs(), tile.tactical(),
                    tile.features(), tile.text(), liquid, tile.tileset())).toList();
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), tiles, scene.units(),
              scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(),
              scene.firingLines(), scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(),
              scene.fieldOfView());
    }

    /**
     * The Tactical View zooms within the 3D view's limits, one rule that does not depend on the board, so a big board
     * zooms in as far as in 3D: here well past four times the fitted board, the prototype's flat-view limit.
     */
    private static void checkTacticalZoom(GpuBoardSpaceHarness board) {
        board.view(false);
        float[] threeD = zoomLimits(board.camera);
        board.view(true);
        float fitted = board.camera.camera.zoom;
        float[] tactical = zoomLimits(board.camera);
        assertEquals(threeD[0], tactical[0], "The Tactical View zooms in as far as the 3D view");
        assertEquals(threeD[1], tactical[1], "The Tactical View zooms out as far as the 3D view");
        assertTrue(tactical[0] < fitted / 4, "Zooming in goes past four times the fitted board");
        board.view(false);
    }

    /** The camera's zoom after zooming all the way in, then after zooming all the way out. */
    private static float[] zoomLimits(BoardCamera camera) {
        for (int step = 0; step < 200; step++) {
            camera.zoom(1 / 1.25f);
        }
        float in = camera.camera.zoom;
        for (int step = 0; step < 200; step++) {
            camera.zoom(1.25f);
        }
        return new float[] { in, camera.camera.zoom };
    }

    /** The hint line's gestures follow the view, the phase and the local turn, with the current keys. */
    private static void checkHint(Parts parts, BoardScene scene) {
        GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
        GpuHud.Inputs tactical = parts.update(scene, status(fixture, GamePhase.FIRING, true, GpuHudFixtures.ATLAS),
              move(), GpuReportLog.Snapshot.EMPTY, true, 1920, 1080);
        assertEquals(List.of(text("GpuBoard.hud.mouse.leftClick"), text("GpuBoard.hud.hint.selectTarget"),
              text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.panMap"), text("GpuBoard.hud.mouse.wheel"),
              text("GpuBoard.hud.hint.zoom"), "T", text("GpuBoard.hud.util.backTo3d")), GpuHintLine.items(tactical));
        GpuHud.Inputs planning = parts.update(scene, fixture, move(), GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        assertEquals(List.of(text("GpuBoard.hud.mouse.leftClick"), text("GpuBoard.hud.hint.selectPlan"),
              text("GpuBoard.hud.mouse.ctrlClick"), text("GpuBoard.hud.hint.waypoint"),
              text("GpuBoard.hud.mouse.shiftClick"), text("GpuBoard.hud.hint.orientation"),
              text("GpuBoard.hud.mouse.rightDrag"), text("GpuBoard.hud.hint.pan"),
              text("GpuBoard.hud.mouse.orbitShort"), text("GpuBoard.hud.hint.orbit"), "WASD QE",
              text("GpuBoard.hud.hint.camera")),
              GpuHintLine.items(planning));
        GpuHud.Inputs waiting = parts.update(scene, status(fixture, GamePhase.MOVEMENT, false, 6), move(),
              GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        // User item 29c: outside the local turn an own unit's click selects it, any other unit's inspects it.
        assertEquals("Select / inspect", GpuHintLine.items(waiting).get(1));
        GpuHud.Inputs review = parts.update(scene, status(fixture, GamePhase.END_REPORT, false, Entity.NONE), move(),
              GpuReportLog.Snapshot.EMPTY, false, 1920, 1080);
        assertEquals("Select / inspect", GpuHintLine.items(review).get(1));
    }

    /** Updates, places and draws the parts over the board in one view, at 1920 x 1080. */
    private static void show(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts, BoardScene scene,
          GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move, boolean tactical) {
        hud.size(1920, 1080);
        parts.update(scene, status, move, GpuReportLog.Snapshot.EMPTY, tactical, 1920, 1080);
        parts.place(GpuHud.Metrics.of(1920, 1080), false);
        board.draw(camera -> { });
        hud.drawStage();
    }

    /**
     * Renders one screen of the prototype and writes it with each part beside the same area of the mock. The 3D view
     * is zoomed in from the fitted board, as the prototype's camera, so the minimap's frustum lies on the board.
     */
    private static void render(GpuHudTestStage hud, GpuBoardSpaceHarness board, Parts parts, BoardScene scene,
          GpuBattleStatus.Snapshot status, GpuMovePlan.Snapshot move, GpuReportLog.Snapshot reports,
          boolean tactical, int width, int height, String name, String mock, boolean everything) {
        board.view(tactical);
        if (!tactical) {
            board.camera.zoom(.55f);
        }
        hud.size(width, height);
        parts.update(scene, status, move, reports, tactical, width, height);
        parts.place(GpuHud.Metrics.of(width, height), false);
        board.draw(camera -> { });
        hud.drawStage();
        Pixmap image = hud.capture(name);
        try {
            List<Actor> shown = new ArrayList<>(List.of(parts.utilities.actor()));
            if (everything) {
                shown.add(parts.minimap.actor());
                shown.add(parts.utilities.chip());
                shown.add(parts.utilities.north());
                shown.add(parts.hint.actor());
            }
            for (Actor actor : shown) {
                if (actor.isVisible()) {
                    Rectangle area = GpuHudTestStage.bounds(actor);
                    hud.compare(name + "-" + actor.getName(), image, actor, mock, Math.round(area.x),
                          Math.round(height - area.y - area.height));
                    System.out.println(name + " " + actor.getName() + " at " + area);
                }
            }
        } finally {
            image.dispose();
        }
    }

    /** E rule 6 for one window size and view: in the window, clear of each other and of the tuning utility. */
    private static void assertLayout(GpuHudTestStage hud, Parts parts, GpuHud.Metrics metrics, boolean tactical) {
        Actor bar = parts.utilities.actor();
        Actor map = parts.minimap.actor();
        Actor chip = parts.utilities.chip();
        Rectangle barArea = GpuHudTestStage.bounds(bar);
        Rectangle tuning = new Rectangle(barArea.x + barArea.width + GpuUtilityBar.GAP, barArea.y,
              metrics.narrow() ? TUNING_NARROW : TUNING_WIDE, barArea.height);
        List<Rectangle> others = new ArrayList<>(List.of(tuning, GpuHudTestStage.bounds(map)));
        // GpuHud hides the chip where the top row has no room for it (below about 1000 units wide).
        boolean chipShown = tactical && metrics.width() >= 1280;
        assertEquals(tactical, chip.isVisible());
        if (chipShown) {
            others.add(GpuHudTestStage.bounds(chip));
        }
        hud.assertLayout(bar, others.toArray(new Rectangle[0]));
        hud.assertLayout(map, barArea, tuning);
        if (chipShown) {
            hud.assertLayout(chip, barArea, tuning, GpuHudTestStage.bounds(map));
        }
        // The north mark shows in the Tactical View whether or not the chip has room.
        Actor north = parts.utilities.north();
        assertEquals(tactical, north.isVisible());
        if (tactical) {
            others.add(barArea);
            hud.assertLayout(north, others.toArray(new Rectangle[0]));
        }
        if (parts.hint.actor().isVisible()) {
            hud.assertLayout(parts.hint.actor(), barArea, GpuHudTestStage.bounds(map));
        }
        System.out.printf("%.0f x %.0f %s: utilities %s, minimap %s, chip %s, north %s, hint %s%n", metrics.width(),
              metrics.height(), tactical ? "tactical" : "3D", barArea, GpuHudTestStage.bounds(map),
              chipShown ? GpuHudTestStage.bounds(chip) : "hidden",
              tactical ? GpuHudTestStage.bounds(north) : "hidden",
              parts.hint.actor().isVisible() ? GpuHudTestStage.bounds(parts.hint.actor()) : "hidden");
    }

    /** Presses the minimap at a stage point and returns where the camera centred. */
    private static Vector3 press(GpuHudTestStage hud, GpuBoardSpaceHarness board, Vector2 point) {
        Vector2 screen = hud.stage.stageToScreenCoordinates(point.cpy());
        int x = Math.round(screen.x);
        int y = Math.round(screen.y);
        point.set(hud.stage.screenToStageCoordinates(new Vector2(x, y)));
        hud.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        hud.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        return board.camera.focus.cpy();
    }

    /** The captured colour where the minimap draws the centre of {@code coords}. */
    private static int sample(Pixmap image, Vector2 pressed, Vector3 origin, Vector2 scale, Coords coords) {
        float x = pressed.x + (BoardGeometry.centerX(coords) - origin.x) * scale.x;
        float y = pressed.y + (BoardGeometry.centerY(coords) - origin.y) * scale.y;
        return image.getPixel(Math.round(x), Math.round(y));
    }

    /** The alpha-weighted mean colour of an image, opaque. */
    private static int mean(BoardScene.Pixels pixels) {
        double red = 0;
        double green = 0;
        double blue = 0;
        double weight = 0;
        for (int index = 0; index < pixels.width() * pixels.height(); index++) {
            int rgba = pixels.rgba(index);
            double alpha = (rgba & 255) / 255.0;
            red += (rgba >>> 24) * alpha;
            green += (rgba >>> 16 & 255) * alpha;
            blue += (rgba >>> 8 & 255) * alpha;
            weight += alpha;
        }
        return Color.rgba8888((float) (red / weight / 255), (float) (green / weight / 255),
              (float) (blue / weight / 255), 1);
    }

    private static void click(GpuHudTestStage hud, String name) {
        Actor actor = hud.stage.getRoot().findActor(name);
        Vector2 screen = hud.stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        hud.stage.touchDown(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
        hud.stage.touchUp(Math.round(screen.x), Math.round(screen.y), 0, Input.Buttons.LEFT);
    }

    /** The text of the Log utility's shown count badge (its label other than the caption), or null. */
    private static String badge(GpuUtilityBar utilities) {
        UiButton log = ((Table) utilities.actor()).findActor("utility-log");
        for (Actor child : log.getChildren()) {
            if (child instanceof Label label && label != log.getLabel() && label.isVisible()) {
                return label.getText().toString();
            }
        }
        return null;
    }

    private static GpuBattleStatus.Snapshot status(GpuBattleStatus.Snapshot fixture, GamePhase phase, boolean myTurn,
          int actor) {
        return new GpuBattleStatus.Snapshot(fixture.round(), phase, myTurn, fixture.localPlayerId(), actor,
              fixture.turns(), fixture.turnIndex(), fixture.units(), List.of(), false);
    }

    private static GpuBattleStatus.Snapshot withoutUnits(GpuBattleStatus.Snapshot fixture) {
        return new GpuBattleStatus.Snapshot(fixture.round(), fixture.phase(), fixture.myTurn(),
              fixture.localPlayerId(), fixture.actorId(), fixture.turns(), fixture.turnIndex(), List.of(), List.of(),
              false);
    }

    /** The Atlas's plan: planner movement with the fixture route. */
    private static GpuMovePlan.Snapshot move() {
        GpuMovePlan.Snapshot empty = GpuMovePlan.Snapshot.EMPTY;
        return new GpuMovePlan.Snapshot(true, true, false, GpuHudFixtures.ATLAS, GpuMovePlan.Mode.AUTO, false, "",
              ROUTE, List.of(), List.of(), ROUTE.getLast().coords(), 0, 3, 3, EntityMovementType.MOVE_WALK, "",
              true, 1, 1, true, List.of(), true, true, empty.envelope(), 0);
    }

    /** A volley of {@code count} weapon attacks of this round, as the report log keeps them for review. */
    private static GpuReportLog.Snapshot volley(List<BoardScene.Combat> shots) {
        List<GpuReportLog.CombatEvent> combat = new ArrayList<>();
        for (BoardScene.Combat shot : shots) {
            combat.add(new GpuReportLog.CombatEvent(shot.result().id(), 3, GamePhase.FIRING,
                  ResolvedAttack.Kind.SHOT, shot.attacker().id(), shot.target().id(), "Medium Laser", "LA",
                  shot.result().hit(), 5, List.of(), null, 0));
        }
        return new GpuReportLog.Snapshot(3, GamePhase.FIRING_REPORT, List.of(), Map.of(), combat, List.of(),
              List.of(), List.of());
    }

    /** The Atlas's {@code count} medium laser shots at the King Crab, every other one a hit. */
    private static List<BoardScene.Combat> shots(BoardScene scene, int count) {
        BoardScene.Unit atlas = scene.units().stream().filter(unit -> unit.id() == GpuHudFixtures.ATLAS)
              .findFirst().orElseThrow();
        BoardScene.Unit enemy = scene.units().stream().filter(unit -> unit.id() == 7).findFirst().orElseThrow();
        List<BoardScene.Combat> shots = new ArrayList<>();
        for (int shot = 0; shot < count; shot++) {
            shots.add(UnitPlaybackTest.attack(atlas, enemy, ResolvedAttack.Kind.SHOT, shot % 2 == 0));
        }
        return shots;
    }

    /** MegaMek's default key of every bind with its display text; the minimap shown. */
    private static GpuBoardSource.UiPreferences preferences() {
        List<GpuBoardSource.Bind> binds = Stream.of(KeyCommandBind.values())
              .map(bind -> new GpuBoardSource.Bind(bind, bind.keyDefault, bind.modifiersDefault,
                    KeyCommandBind.getDesc(bind.keyDefault, bind.modifiersDefault))).toList();
        return new GpuBoardSource.UiPreferences(1, "", "", true, true, false, false, binds, 0);
    }

    private static String text(String key) {
        return Messages.getString(key);
    }
}
