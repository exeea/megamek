/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.server.sbf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Breakup and planetary entry after falling from capital Central into atmospheric Outer, IO:BF pp. 185–186. */
final class SBFSpaceGravity {
    static boolean chooseAim(SBFGame game, int player, SBFFormation squadron, BoardLocation aim) {
        if (squadron == null || squadron.getOwnerId() != player || !game.getPhase().isMovement()
              || !squadron.fallingFromOrbit() || !game.hasBoardLocation(aim) || !game.getBoard(aim.boardId()).isGround()) {
            return false;
        }
        squadron.setCrashAim(aim);
        return true;
    }

    static List<FormationLosses> fallInward(SBFGameManager manager, SBFFormation squadron) {
        var game = manager.getGame();
        List<FormationLosses> losses = new ArrayList<>();
        var ships = squadron.getUnits().stream().filter(u -> u.getMovementMode() == SBFMovementMode.WARSHIP).toList();
        boolean escaped = !ships.isEmpty() && Compute.d6(2) >= squadron.getSkill() + 6 && squadron.capitalThrust() >= 4;
        var lost = squadron.getUnits().stream().filter(u -> u.getMovementMode() == SBFMovementMode.STATION_KEEPING
              || (!escaped && ships.contains(u))).toList();
        if (!ships.isEmpty() && !escaped) {
            warshipImpact(manager, squadron);
        }
        if (lost.size() == squadron.getUnits().size()) {
            for (var passenger : game.passengersOf(squadron)) {
                losses.add(SBFAerospace.crash(game, passenger));
            }
            losses.add(SBFAerospace.crash(game, squadron));
        } else {
            if (!lost.isEmpty()) {
                for (var flight : lost) {
                    game.scenarioProgress().recordLoss(game.getCurrentRound(), squadron, flight, Player.PLAYER_NONE, LossCause.CRASH);
                    flight.setCurrentArmor(0);
                }
                losses.add(new FormationLosses(squadron, true, lost.size(), lost.stream().anyMatch(u -> u.hasSUA(BattleForceSUA.LEAD)),
                      lost.stream().anyMatch(u -> u.hasSUA(BattleForceSUA.COM)), false));
                SBFCombatDamage.removeDestroyedUnits(manager, squadron);
            }
            if (escaped) {
                squadron.enterCapitalAtmosphere(squadron.getRadarZone());
            }
            manager.sendUnitUpdate(squadron);
        }
        return losses;
    }

    private static void warshipImpact(SBFGameManager manager, SBFFormation squadron) {
        var game = manager.getGame();
        var aim = squadron.crashAim();
        if (aim == null) {
            // Default for an owner who did not submit a target before the End Phase.
            aim = game.getBoards().entrySet().stream().filter(e -> e.getValue().isGround())
                  .min(Comparator.comparingInt(Map.Entry::getKey))
                  .map(e -> BoardLocation.of(e.getValue().getCenter(), e.getKey())).orElse(null);
        }
        if (aim == null) {
            return;
        }
        int miss = squadron.getSkill() + 6 - Compute.d6(2);
        if (miss > 0) {
            aim = BoardLocation.of(aim.coords().translated(Compute.randomInt(6), miss), aim.boardId());
        }
        if (!game.hasBoardLocation(aim)) {
            return;
        }
        var hex = game.getHex(aim);
        hex.addTerrain(new Terrain(Terrains.RUBBLE, 6));
        if (Compute.d6(2) >= 6) {
            for (var occupant : game.getActiveFormationsAt(aim)) {
                SBFMinefieldControl.damageSusceptible(manager, occupant, squadron.getOwnerId());
            }
            SBFMinefieldControl.ignite(manager, aim, squadron.getOwnerId());
        }
        manager.send(manager.getPacketHelper().createBoardsPacket());
    }
}
