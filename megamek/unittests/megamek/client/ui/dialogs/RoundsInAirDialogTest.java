/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Vector;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.common.HexTarget;
import megamek.common.Player;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EnemyArtilleryInbound;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType.Munitions;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;

class RoundsInAirDialogTest {
    /** The rows the window and the GPU battle log list (K13): own rounds in full, enemy rounds without their aim. */
    @Test
    void rowsListOwnRoundsInFullAndEnemyRoundsWithoutTheirAimSoonestFirst() {
        assertEquals(List.of(
                    new RoundsInAirDialog.Row(0, "Team 1", "Raven's Nest", "Sniper Artillery Vehicle", "This turn",
                          "Counter-battery (off-board)", "Standard (HE)"),
                    new RoundsInAirDialog.Row(1, "Team 2", "OpFor", "Thumper Artillery Vehicle", "1 turn(s)",
                          "Unknown", "Unknown"),
                    new RoundsInAirDialog.Row(2, "Team 1", "Raven's Nest", "Long Tom Artillery Vehicle", "2 turn(s)",
                          "0607", "Homing"),
                    new RoundsInAirDialog.Row(3, "Team 2", "OpFor", "(unknown unit)", "3 turn(s)", "Unknown",
                          "Unknown")),
              RoundsInAirDialog.rows(game()));
    }

    /** What the window's table shows; written against the window before its rows were extracted, and unchanged. */
    @Test
    void theTableListsEveryRoundSoonestFirstAndWithholdsTheEnemyAim() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "The dialog needs a display");
        Game game = game();
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(game);
        List<String> shown = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            // refresh() fills the table only while the window shows; this one is never shown.
            RoundsInAirDialog dialog = new RoundsInAirDialog(null, client) {
                @Override
                public boolean isVisible() {
                    return true;
                }
            };
            try {
                dialog.refresh();
                JTable table = (JTable) ((JScrollPane) dialog.getContentPane().getComponent(0)).getViewport()
                      .getView();
                shown.add(IntStream.range(0, table.getColumnCount()).mapToObj(table::getColumnName)
                      .collect(Collectors.joining(" | ")));
                for (int row = 0; row < table.getRowCount(); row++) {
                    int line = row;
                    shown.add(IntStream.range(0, table.getColumnCount())
                          .mapToObj(column -> String.valueOf(table.getValueAt(line, column)))
                          .collect(Collectors.joining(" | ")));
                }
            } finally {
                dialog.dispose();
            }
        });

        assertEquals(List.of("Team | Player | Fired By | Lands In | Target Hex | Warhead",
              "Team 1 | Raven's Nest | Sniper Artillery Vehicle | This turn | Counter-battery (off-board) | "
                    + "Standard (HE)",
              "Team 2 | OpFor | Thumper Artillery Vehicle | 1 turn(s) | Unknown | Unknown",
              "Team 1 | Raven's Nest | Long Tom Artillery Vehicle | 2 turn(s) | 0607 | Homing",
              "Team 2 | OpFor | (unknown unit) | 3 turn(s) | Unknown | Unknown"), shown);
    }

    /**
     * The client's game with two own rounds (at hex 0607, and counter-battery at the enemy's off-board battery) and
     * two enemy rounds, which the server sends without their aim (one from a unit the client does not know).
     */
    static Game game() {
        Player local = new Player(0, "Raven's Nest");
        local.setTeam(1);
        Player enemy = new Player(1, "OpFor");
        enemy.setTeam(2);
        Game game = mock(Game.class);
        when(game.getPlayer(0)).thenReturn(local);
        when(game.getPlayer(1)).thenReturn(enemy);
        unit(game, 10, "Long Tom Artillery Vehicle");
        unit(game, 11, "Sniper Artillery Vehicle");
        unit(game, 20, "Thumper Artillery Vehicle");
        Entity battery = unit(game, 21, "Long Tom Artillery Vehicle");
        when(battery.isOffBoard()).thenReturn(true);
        List<ArtilleryAttackAction> own = List.of(
              round(game, 10, 2, new HexTarget(new Coords(5, 6), 0, Targetable.TYPE_HEX_ARTILLERY),
                    EnumSet.of(Munitions.M_HOMING)),
              round(game, 11, 0, battery, EnumSet.noneOf(Munitions.class)));
        when(game.getArtilleryAttacks()).thenAnswer(invocation -> new Vector<>(own).elements());
        when(game.getEnemyArtilleryInbound()).thenReturn(List.of(new EnemyArtilleryInbound(20, 1, 1),
              new EnemyArtilleryInbound(22, 1, 3)));
        return game;
    }

    private static Entity unit(Game game, int id, String name) {
        Entity unit = mock(Entity.class);
        when(unit.getShortName()).thenReturn(name);
        when(game.getEntity(id)).thenReturn(unit);
        return unit;
    }

    /** An own round of the local player's unit {@code entityId}. */
    private static ArtilleryAttackAction round(Game game, int entityId, int turns, Targetable target,
          EnumSet<Munitions> munitions) {
        ArtilleryAttackAction round = mock(ArtilleryAttackAction.class);
        when(round.getEntityId()).thenReturn(entityId);
        when(round.getPlayerId()).thenReturn(0);
        when(round.getTurnsTilHit()).thenReturn(turns);
        when(round.getTarget(game)).thenReturn(target);
        when(round.getAmmoMunitionType()).thenReturn(munitions);
        return round;
    }
}
