/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Entity;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** The menu's line-of-sight toast and the LOS card use the ruler's result, and a sensor contact stays a bare hex. */
@Timeout(120)
class GpuLosResultTest {
    private static final Coords OWN = new Coords(5, 5);
    private static final Coords ENEMY = new Coords(5, 3);
    private static final Coords BEHIND_HILL = new Coords(5, 7);
    private static final String INFANTRY = "Foot Platoon (AFFS) (Laser 3067+).blk";
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixture needs the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    /**
     * A clear 8 x 8 board with light woods between the fixture's own Atlas at 0606 and a prone enemy Atlas at 0604,
     * and a level 4 hill at 0607 in front of the clear hex 0608.
     */
    private static GpuBoardFixture battle() throws Exception {
        StringBuilder data = new StringBuilder("size 8 8\n");
        for (int y = 1; y <= 8; y++) {
            for (int x = 1; x <= 8; x++) {
                String terrain = x == 6 && y == 5 ? "woods:1;foliage_elev:2" : "";
                data.append(String.format("hex %02d%02d %d \"%s\" \"\"%n", x, y, x == 6 && y == 7 ? 4 : 0, terrain));
            }
        }
        GpuBoardFixture fixture = GpuBoardFixture.create(BoardLoader.initializeBoard(data.append("end").toString()));
        SwingUtilities.invokeAndWait(() -> {
            Player enemy = new Player(1, "Opponent");
            enemy.setTeam(2);
            fixture.game.addPlayer(enemy.getId(), enemy);
            add(fixture, enemy, "Atlas AS7-D.mtf", 2, ENEMY).setProne(true);
        });
        return fixture;
    }

    /** Makes the enemy Atlas a sensor contact: double blind with TacOps sensors, detected but never seen. */
    private static void detectOnlyBySensors(GpuBoardFixture fixture) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
            fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
            fixture.game.getEntity(2).addBeenDetectedBy(fixture.player);
        });
    }

    @Test
    void theMenuLineOfSightToastsTheRulerResultFromTheActingUnit() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean toasts = preferences.getToastEnabled();
        try (GpuBoardFixture fixture = battle()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            try {
                GpuLosResult los = source.los();
                // Both ends hold an identified unit: the fire phase line of sight, the prone target included.
                los.lineOfSight(1, 2, ENEMY);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).addToast(ToastLevel.INFO, "Line of sight clear · 1 (1 intervening light woods)"
                      + " + 1 (target prone (range)) · 2 hex");

                los.lineOfSight(1, Entity.NONE, BEHIND_HILL);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).addToast(ToastLevel.INFO, "No line of sight · LOS blocked by terrain.");

                // A sensor contact is measured as the bare hex at height 1, even from its own menu: neither its
                // height nor its prone state reaches the result.
                detectOnlyBySensors(fixture);
                los.lineOfSight(1, 2, ENEMY);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui).addToast(ToastLevel.INFO, "Line of sight clear · 1 (1 intervening light woods)"
                      + " · 2 hex");

                // ClientGUI drops toasts while they are switched off, so the request opens the card instead.
                SwingUtilities.invokeAndWait(() -> preferences.setToastEnabled(false));
                los.lineOfSight(1, Entity.NONE, BEHIND_HILL);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui, times(1)).addToast(ToastLevel.INFO, "No line of sight · LOS blocked by terrain.");
                assertEquals(new GpuLosResult.Card(1, OWN, 1, 2, false, BEHIND_HILL, Entity.NONE, 1, false, 2,
                      blocked("LOS blocked by terrain."), blocked("LOS blocked by terrain.")), card(source));

                // LOS settings without a ruler measurement keep that card; with no card open they toast a hint.
                los.open();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, card(source).id(), "LOS settings keep the open card");
                los.closeCard();
                los.open();
                SwingUtilities.invokeAndWait(() -> { });
                assertNull(card(source));
                verify(gui).addToast(ToastLevel.INFO, Messages.getString("GpuBoard.hud.los.hint"));
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    preferences.setToastEnabled(toasts);
                    source.close();
                });
            }
        }
    }

    @Test
    void aSecondaryBoardIsNeverMeasuredOnTheDefaultBoard() throws Exception {
        // MegaMek's ruler line of sight reads the default board only; board 1 is larger than the 8 x 8 board 0.
        ClientGUI gui = mock(ClientGUI.class);
        Coords own = new Coords(10, 12);
        Coords far = new Coords(10, 14);
        try (GpuBoardFixture fixture = battle()) {
            AtomicReference<BoardClientState> second = new AtomicReference<>();
            AtomicReference<GpuBoardSource> source = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                second.set(secondBoard(fixture, gui));
                fixture.entity.setBoardId(1);
                fixture.entity.setPosition(own);
                second.get().drawRuler(own, far, Color.CYAN, Color.PINK);
                source.set(new GpuBoardSource(second.get(), () -> fixture.panel));
            });
            try {
                assertNull(card(source.get()), "A ruler on board 1 opens no card");
                // The menu's line of sight and LOS settings say why nothing is measured; the height buttons do nothing.
                source.get().los().lineOfSight(1, Entity.NONE, far);
                source.get().los().measure(own, far, 2, 1);
                source.get().los().open();
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui, times(2)).addToast(any(ToastLevel.class), anyString());
                verify(gui, times(2)).addToast(ToastLevel.INFO,
                      Messages.getString("GpuBoard.hud.los.defaultBoardOnly"));
                assertNull(card(source.get()));
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    source.get().close();
                    second.get().close();
                });
            }
        }
    }

    @Test
    void aBoardSwitchClosesTheCardAndNeverReopensAClosedMeasurement() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = battle()) {
            BoardClientState first = spy(fixture.view);
            doReturn(gui).when(first).getClientgui();
            doReturn(Optional.of(first)).when(gui).getCurrentBoardState();
            BoardClientState second = onSwing(() -> secondBoard(fixture, gui));
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(first, () -> fixture.panel));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    first.drawRuler(OWN, ENEMY, Color.CYAN, Color.PINK);
                    source.refresh();
                });
                assertNotNull(card(source));
                source.los().closeCard();
                SwingUtilities.invokeAndWait(() -> { });
                show(gui, source, second);
                show(gui, source, first);
                assertNull(card(source), "Board 0's closed measurement does not come back after a board switch");

                // A card opened without a ruler measurement (both boards' rulers are empty) closes on board 1.
                SwingUtilities.invokeAndWait(() -> {
                    first.drawRuler(null, null, Color.CYAN, Color.PINK);
                    source.refresh();
                });
                source.los().measure(OWN, BEHIND_HILL, 2, 1);
                SwingUtilities.invokeAndWait(() -> { });
                assertNotNull(card(source));
                show(gui, source, second);
                assertNull(card(source), "Board 0's card does not stay over board 1");
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    source.close();
                    second.close();
                });
            }
        }
    }

    @Test
    void aRulerMeasurementOpensTheCardAndItsHeightsRemeasureTheHexes() throws Exception {
        try (GpuBoardFixture fixture = battle()) {
            GpuBoardSource source = fixture.source;
            ruler(fixture, OWN, ENEMY);
            assertEquals(new GpuLosResult.Card(1, OWN, 1, 2, false, ENEMY, 2, 1, false, 2,
                  seen(2, "1 (1 intervening light woods) + 1 (target prone (range))"),
                  seen(1, "1 (1 intervening light woods)")), card(source));

            // The height buttons remeasure: the level 4 hill blocks at the default heights, not from height 5 to 5.
            source.los().measure(OWN, BEHIND_HILL, 2, 1);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(new GpuLosResult.Card(2, OWN, 1, 2, false, BEHIND_HILL, Entity.NONE, 1, false, 2,
                  blocked("LOS blocked by terrain."), blocked("LOS blocked by terrain.")), card(source));
            source.los().measure(OWN, BEHIND_HILL, 5, 5);
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(new GpuLosResult.Card(3, OWN, 1, 5, false, BEHIND_HILL, Entity.NONE, 5, false, 2, seen(0, ""),
                  seen(1, "1 (target has partial cover)")), card(source));

            source.los().closeCard();
            SwingUtilities.invokeAndWait(source::refresh);
            assertNull(card(source), "Closing hides the card");
            SwingUtilities.invokeAndWait(source::refresh);
            assertNull(card(source), "The same measurement does not reopen it");
            source.los().open();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(4, card(source).id(), "LOS settings reopen the card for the ruler's measurement");
            assertEquals(ENEMY, card(source).to());

            // The ruler starts over (one point), then measures again: the card follows the new measurement.
            ruler(fixture, ENEMY, null);
            assertNull(card(source));
            source.los().open();
            SwingUtilities.invokeAndWait(() -> { });
            assertNull(card(source), "Without a complete measurement LOS settings open no card");
            detectOnlyBySensors(fixture);
            ruler(fixture, OWN, ENEMY);
            assertEquals(new GpuLosResult.Card(5, OWN, 1, 2, false, ENEMY, Entity.NONE, 1, false, 2,
                  seen(1, "1 (1 intervening light woods)"), seen(1, "1 (1 intervening light woods)")), card(source),
                  "The sensor contact's hex is measured bare at height 1");
        }
    }

    @Test
    void theMenuAndTheHeightButtonsMeasureTheUnitEachEndWasOpenedFor() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean toasts = preferences.getToastEnabled();
        try (GpuBoardFixture fixture = battle()) {
            // Infantry shares the own Atlas's hex and the standing enemy Atlas's hex; each Atlas is the taller unit.
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getEntity(2).setProne(false);
                add(fixture, fixture.player, INFANTRY, 3, OWN);
                add(fixture, fixture.game.getPlayer(1), INFANTRY, 4, ENEMY);
                preferences.setToastEnabled(false);
            });
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            try {
                // The enemy infantry's menu measures that infantry, not the taller Atlas in its hex.
                source.los().lineOfSight(1, 4, ENEMY);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(new GpuLosResult.Card(1, OWN, 1, 2, false, ENEMY, 4, 1, false, 2,
                      seen(1, "1 (1 intervening light woods)"), seen(1, "1 (1 intervening light woods)")),
                      card(source));

                // The acting infantry's card keeps the infantry through the height buttons, never the Atlas.
                source.los().lineOfSight(3, Entity.NONE, BEHIND_HILL);
                source.los().measure(OWN, BEHIND_HILL, 2, 1);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(new GpuLosResult.Card(3, OWN, 3, 2, false, BEHIND_HILL, Entity.NONE, 1, false, 2,
                      blocked("LOS blocked by terrain."), blocked("LOS blocked by terrain.")), card(source));
                source.los().measure(OWN, BEHIND_HILL, 1, 1);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(new GpuLosResult.Card(4, OWN, 3, 1, false, BEHIND_HILL, Entity.NONE, 1, false, 2,
                      blocked("LOS blocked by terrain."), blocked("LOS blocked by terrain.")), card(source));

                // The heights stop at the Swing ruler's spinner bounds.
                source.los().measure(OWN, BEHIND_HILL, RulerDialog.MAX_HEIGHT + 1, RulerDialog.MIN_HEIGHT - 1);
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(List.of(RulerDialog.MAX_HEIGHT, RulerDialog.MIN_HEIGHT),
                      List.of(card(source).fromHeight(), card(source).toHeight()));
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    preferences.setToastEnabled(toasts);
                    source.close();
                });
            }
        }
    }

    @Test
    void aLargeUnitIsMeasuredAtTheClickedHexOfItsFootprint() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        Coords centre = new Coords(2, 2);
        Coords side = centre.translated(5);
        try (GpuBoardFixture fixture = battle()) {
            SwingUtilities.invokeAndWait(() -> {
                Entity dropShip = unit("Union (3055).blk", 5, fixture.player);
                dropShip.setAltitude(0);
                fixture.game.addEntity(dropShip, false);
                // A grounded DropShip occupies its hex and the six around it once it is in the game.
                dropShip.setPosition(centre);
            });
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            try {
                SwingUtilities.invokeAndWait(() -> {
                    view.drawRuler(OWN, side, Color.CYAN, Color.PINK);
                    source.refresh();
                });
                GpuLosResult.Card card = card(source);
                assertEquals(List.of(side, 5, 6), List.of(card.to(), card.toUnit(), card.range()),
                      "The card keeps the ruler's point and its range, not the DropShip's centre");

                // A hex of the acting DropShip's own footprint is not measured.
                source.los().lineOfSight(5, Entity.NONE, side);
                SwingUtilities.invokeAndWait(() -> { });
                verify(gui, never()).addToast(any(ToastLevel.class), anyString());
                assertEquals(card.id(), card(source).id(), "Nor does it open the card");
            } finally {
                SwingUtilities.invokeAndWait(source::close);
            }
        }
    }

    /** EDT: adds the 16 x 17 board 1 beside the 8 x 8 board 0 and a view of it whose client is {@code gui}. */
    private static BoardClientState secondBoard(GpuBoardFixture fixture, ClientGUI gui) {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        board.setBoardId(1);
        fixture.game.setBoard(1, board);
        try {
            BoardClientState view = spy(new BoardClientState(fixture.game, null, null, 1, null));
            doReturn(gui).when(view).getClientgui();
            view.setLocalPlayer(fixture.player.getId());
            return view;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Makes the client show that board view, as its board tabs do, then captures. */
    private static void show(ClientGUI gui, GpuBoardSource source, BoardClientState view) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            doReturn(Optional.of(view)).when(gui).getCurrentBoardState();
            source.refresh();
        });
    }

    /** Draws MegaMek's ruler as the Swing ruler dialog does after a Ctrl or Alt click, then captures. */
    private static void ruler(GpuBoardFixture fixture, Coords start, Coords end) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            fixture.view.drawRuler(start, end, Color.CYAN, Color.PINK);
            fixture.source.refresh();
        });
    }

    private static Entity unit(String file, int id, Player owner) {
        try {
            Entity unit = new MekFileParser(new File("testresources/megamek/common/units/" + file)).getEntity();
            unit.setId(id);
            unit.setOwner(owner);
            unit.setDeployed(true);
            return unit;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    /** EDT: adds a deployed unit at a hex. */
    private static Entity add(GpuBoardFixture fixture, Player owner, String file, int id, Coords hex) {
        Entity unit = unit(file, id, owner);
        unit.setPosition(hex);
        fixture.game.addEntity(unit, false);
        return unit;
    }

    /** A ruler view with a line of sight: its to-hit total and modifiers. */
    static RulerDialog.LosView seen(int total, String modifiers) {
        return new RulerDialog.LosView(total, modifiers);
    }

    /** A ruler view without a line of sight, and the reason. */
    static RulerDialog.LosView blocked(String reason) {
        return new RulerDialog.LosView(TargetRoll.IMPOSSIBLE, reason);
    }

    private static GpuLosResult.Card card(GpuBoardSource source) {
        return source.takeFrame().panels().los().card();
    }

    private static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
