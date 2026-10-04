/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import megamek.client.event.BoardViewEvent;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.Test;

/** Characterizes the ruler's two LOS rows for scripted measurements across one light-woods hex. */
class RulerDialogLosTest {
    private static final String ATLAS = "testresources/megamek/common/units/Atlas AS7-D.mtf";

    @Test
    void collapsedDiagramAndNativeRayShareHeightsBlockerFlipAndClear() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "RulerDialog needs a display");
        Game game = new Game();
        game.setBoard(Board.createEmptyBoard(1, 7));
        Coords start = new Coords(0, 0), end = new Coords(0, 6);
        game.getBoard().getHex(new Coords(0, 2)).setLevel(2);
        game.getBoard().getHex(new Coords(0, 4)).setLevel(2);
        BoardClientState view = mock(BoardClientState.class);
        AtomicReference<BoardTactical.Ruler> published = new AtomicReference<>();
        doAnswer(call -> { published.set(call.getArgument(0)); return null; }).when(view).drawRuler(any());
        FutureTask<Void> task = new FutureTask<>(() -> {
            RulerDialog dialog = new RulerDialog(null, view, game);
            try {
                Field expanded = RulerDialog.class.getDeclaredField("diagramExpanded");
                expanded.setAccessible(true);
                expanded.setBoolean(dialog, false);
                dialog.measure(start, end);
                dialog.setHeight(start, 2);
                dialog.setHeight(end, 2);
                assertEquals(new Coords(0, 2), published.get().blockedAt());
                assertEquals(2, published.get().startHeight());
                assertShared(dialog, published.get());
                assertTrue(row(dialog, "tf_los1").contains("blocked"));
                dialog.butFlip_actionPerformed();
                assertEquals(end, published.get().start());
                assertEquals(new Coords(0, 4), published.get().blockedAt());
                assertShared(dialog, published.get());
                dialog.setHeight(start, 5);
                dialog.setHeight(end, 5);
                assertNull(published.get().blockedAt());
                assertShared(dialog, published.get());
                dialog.butClose_actionPerformed();
                assertNull(published.get());
            } finally {
                dialog.dispose();
            }
            return null;
        });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }

    private static void assertShared(RulerDialog dialog, BoardTactical.Ruler ruler) throws Exception {
        Field panel = RulerDialog.class.getDeclaredField("diagramPanel");
        panel.setAccessible(true);
        Field data = LOSElevationDiagramPanel.class.getDeclaredField("diagramData");
        data.setAccessible(true);
        LOSDiagramData diagram = (LOSDiagramData) data.get(panel.get(dialog));
        assertEquals(ruler.start(), diagram.attackPos());
        assertEquals(ruler.end(), diagram.targetPos());
        assertEquals(ruler.startHeight(), diagram.attackerAbsHeight());
        assertEquals(ruler.endHeight(), diagram.targetAbsHeight());
        assertEquals(ruler.blockedAt(), diagram.blockingHex());
        assertEquals(ruler.blockedAt() != null, diagram.losBlocked());
    }

    /** A strip of four hexes: the local Atlas, light woods, a prone enemy Atlas, a clear hex. */
    private static Game game() throws Exception {
        Game game = new Game();
        game.setBoard(BoardLoader.initializeBoard("""
              size 1 4
              hex 0101 0 "" ""
              hex 0102 0 "woods:1;foliage_elev:2" ""
              hex 0103 0 "" ""
              hex 0104 0 "" ""
              end"""));
        Player local = new Player(0, "Local");
        local.setTeam(1);
        game.addPlayer(local.getId(), local);
        Player enemy = new Player(1, "Enemy");
        enemy.setTeam(2);
        game.addPlayer(enemy.getId(), enemy);
        addAtlas(game, local, 1, new Coords(0, 0));
        addAtlas(game, enemy, 2, new Coords(0, 2)).setProne(true);
        return game;
    }

    private static Entity addAtlas(Game game, Player owner, int id, Coords position) throws Exception {
        Entity atlas = new MekFileParser(new File(ATLAS)).getEntity();
        atlas.setId(id);
        atlas.setOwner(owner);
        atlas.setPosition(position);
        atlas.setDeployed(true);
        game.addEntity(atlas, false);
        return atlas;
    }

    /** The dialog's attacker and target rows after measuring from {@code from} to {@code to}. */
    private static List<String> measure(Game game, Coords from, Coords to) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "RulerDialog needs a display");
        BoardClientState view = mock(BoardClientState.class);
        Player local = game.getPlayer(0);
        when(view.getLocalPlayer()).thenReturn(local);
        FutureTask<List<String>> task = new FutureTask<>(() -> {
            RulerDialog dialog = new RulerDialog(null, view, game);
            try {
                dialog.firstLOSHex(new BoardViewEvent(view, from, BoardViewEvent.BOARD_FIRST_LOS_HEX, 0));
                dialog.secondLOSHex(new BoardViewEvent(view, to, BoardViewEvent.BOARD_SECOND_LOS_HEX, 0));
                return List.of(row(dialog, "tf_los1"), row(dialog, "tf_los2"));
            } finally {
                dialog.dispose();
            }
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static String row(RulerDialog dialog, String field) throws ReflectiveOperationException {
        Field row = RulerDialog.class.getDeclaredField(field);
        row.setAccessible(true);
        return ((JTextField) row.get(dialog)).getText();
    }

    @Test
    void twoUnitsAcrossLightWoodsUseTheFirePhaseLineOfSight() throws Exception {
        // Both ends hold a unit: the fire phase line of sight, with the prone target's own modifier.
        assertEquals(List.of("2 = 1 (1 intervening light woods) + 1 (target prone (range))",
              "1 = 1 (1 intervening light woods)"), measure(game(), new Coords(0, 0), new Coords(0, 2)));
    }

    @Test
    void aUnitAndAnEmptyHexAcrossLightWoodsUseTheRulerHeights() throws Exception {
        // The far end is an empty hex: the ruler measures at its default height of 1.
        assertEquals(List.of("1 = 1 (1 intervening light woods)", "1 = 1 (1 intervening light woods)"),
              measure(game(), new Coords(0, 0), new Coords(0, 3)));
    }

    @Test
    void aSensorReturnIsMeasuredAsItsBareHex() throws Exception {
        // Double blind with TacOps sensors: the prone enemy is only detected, so its height and prone state stay
        // out of the rows, which are those of the bare hex at the generic height of 1.
        Game game = game();
        game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
        game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
        game.getEntity(2).addBeenDetectedBy(game.getPlayer(0));
        assertEquals(List.of("1 = 1 (1 intervening light woods)", "1 = 1 (1 intervening light woods)"),
              measure(game, new Coords(0, 0), new Coords(0, 2)));
    }
}
