/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import megamek.client.ui.Messages;
import megamek.common.Player;
import megamek.common.actions.ArtilleryAttackAction;
import megamek.common.actions.EnemyArtilleryInbound;
import megamek.common.board.Coords;
import megamek.common.equipment.AmmoType.Munitions;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;

/**
 * The artillery rounds in the air the local player may see, as the 3D log lists them: who fired each, the turns until
 * it lands, its target hex and warhead. Sourced from {@link Game#getArtilleryAttacks()}, which the server has already
 * filtered to the local player's team (a Game Master or observer sees all).
 */
final class RoundsInAir {
    private RoundsInAir() {
    }

    /**
     * The rows this window lists, sorted so the soonest-landing rounds are at the top. Own and allied rounds are shown
     * in full; enemy rounds (a redacted, team-safe feed from the server) show only their landing time, with the target
     * hex and warhead listed as "Unknown". The GPU battle log lists the same rows.
     *
     * @param game  The client's game
     * @param local The local player, who decides which rounds are an enemy's
     *
     * @return One row per artillery round in the air that the local player is entitled to see
     */
    public static List<Row> rows(Game game, Player local) {
        List<Row> rows = new ArrayList<>();

        // Own and allied rounds: full detail.
        for (Enumeration<ArtilleryAttackAction> attackEnumeration = game.getArtilleryAttacks();
              attackEnumeration.hasMoreElements(); ) {
            ArtilleryAttackAction attack = attackEnumeration.nextElement();
            rows.add(new Row(attack.getTurnsTilHit(),
                  teamName(game, attack.getPlayerId()),
                  playerName(game, attack.getPlayerId()),
                  firingUnitName(game, attack.getEntityId()),
                  landsInText(attack.getTurnsTilHit()),
                  targetHexText(game, attack),
                  warheadName(attack),
                  GpuBattleStatus.enemy(game.getPlayer(attack.getPlayerId()), local)));
        }

        // Enemy rounds: redacted - landing time only; the target hex and warhead are withheld (shown as "Unknown").
        String unknown = Messages.getString("RoundsInAirDialog.unknown");
        for (EnemyArtilleryInbound inbound : game.getEnemyArtilleryInbound()) {
            rows.add(new Row(inbound.turnsTilHit(),
                  teamName(game, inbound.playerId()),
                  playerName(game, inbound.playerId()),
                  firingUnitName(game, inbound.firingEntityId()),
                  landsInText(inbound.turnsTilHit()),
                  unknown,
                  unknown,
                  true));
        }

        // Sort allied and enemy rounds together so the soonest-landing round is always at the top, regardless of side.
        rows.sort(Comparator.comparingInt(Row::turnsTilHit));
        return rows;
    }

    /**
     * One row of the window: the cell texts in column order, with the landing time separately so allied and enemy
     * rounds can be sorted together by soonest landing.
     *
     * @param turnsTilHit Turns until this round lands (the sort key)
     * @param team        The firing player's team
     * @param player      The firing player
     * @param firedBy     The firing unit
     * @param landsIn     When the round lands
     * @param targetHex   The target hex or its counter-battery or off-board label; "Unknown" for an enemy round
     * @param warhead     The warhead; "Unknown" for an enemy round
     * @param enemy       Whether an enemy of the local player fired it
     */
    public record Row(int turnsTilHit, String team, String player, String firedBy, String landsIn, String targetHex,
          String warhead, boolean enemy) {}

    private static String teamName(Game game, int playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            return Messages.getString("RoundsInAirDialog.unknownPlayer");
        }

        return player.getTeamName();
    }

    private static String firingUnitName(Game game, int firingEntityId) {
        Entity firingEntity = game.getEntity(firingEntityId);
        return (firingEntity != null) ? firingEntity.getShortName()
              : Messages.getString("RoundsInAirDialog.unknownUnit");
    }

    private static String playerName(Game game, int playerId) {
        Player player = game.getPlayer(playerId);
        return (player != null) ? player.getName() : Messages.getString("RoundsInAirDialog.unknownPlayer");
    }

    private static String landsInText(int turns) {
        return (turns <= 0) ? Messages.getString("RoundsInAirDialog.landsThisTurn")
              : Messages.getString("RoundsInAirDialog.landsInTurns", turns);
    }

    private static String targetHexText(Game game, ArtilleryAttackAction attack) {
        Targetable target = attack.getTarget(game);
        // A counter-battery shot aims at an off-board enemy battery, whose virtual board number is meaningless as a
        // grid square, so label it instead of printing that number (or losing the value entirely).
        if ((target != null) && target.isOffBoard()) {
            return Messages.getString("RoundsInAirDialog.counterBattery");
        }
        if ((target != null) && (target.getPosition() != null)) {
            return target.getPosition().getBoardNum();
        }
        Coords coords = attack.getCoords();
        return (coords != null) ? coords.getBoardNum() : Messages.getString("RoundsInAirDialog.unknownHex");
    }

    /**
     * @param attack The in-flight artillery attack
     *
     * @return A short, human-readable warhead label derived from the recorded munition type (e.g. {@code Homing},
     *       {@code Cluster}), defaulting to {@code Standard (HE)} when no special munition is flagged
     */
    private static String warheadName(ArtilleryAttackAction attack) {
        var munitions = attack.getAmmoMunitionType();
        if (munitions.contains(Munitions.M_HOMING)) {
            return Messages.getString("RoundsInAirDialog.warhead.homing");
        } else if (munitions.contains(Munitions.M_CLUSTER)) {
            return Messages.getString("RoundsInAirDialog.warhead.cluster");
        } else if (munitions.contains(Munitions.M_FASCAM)) {
            return Messages.getString("RoundsInAirDialog.warhead.fascam");
        } else if (munitions.contains(Munitions.M_INFERNO_IV)) {
            return Messages.getString("RoundsInAirDialog.warhead.inferno");
        } else if (munitions.contains(Munitions.M_FLARE)) {
            return Messages.getString("RoundsInAirDialog.warhead.flare");
        } else if (munitions.contains(Munitions.M_SMOKE)) {
            return Messages.getString("RoundsInAirDialog.warhead.smoke");
        } else if (munitions.contains(Munitions.M_ADA)) {
            return Messages.getString("RoundsInAirDialog.warhead.ada");
        }
        return Messages.getString("RoundsInAirDialog.warhead.standard");
    }
}
