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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.equipment.ICarryable;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.game.IGame;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.Claim;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.Loss;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.SalvageRoll;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.server.victory.VictoryResult;

/** End Phase objectives, suggested Victory Points, and final End Phase salvage (IO:BF p. 174). */
final class SBFScenarioResolution {
    private SBFScenarioResolution() {
    }

    static void updateObjectives(SBFGameManager manager) {
        SBFGame game = manager.getGame();
        boolean changed = false;
        for (var entry : game.getGroundObjects().entrySet()) {
            Coords coords = entry.getKey();
            BoardLocation location = BoardLocation.of(coords, IGame.DEFAULT_BOARD_ID);
            for (var object : new ArrayList<>(entry.getValue())) {
                if (!(object instanceof ObjectiveMarker marker) || marker.isDestroyed()) {
                    continue;
                }
                Map<SBFUnit, Integer> occupants = new HashMap<>();
                for (SBFFormation f : game.getActiveFormationsAt(location)) {
                    if (f.occupiesGroundHex() && !f.isMoraleRestricted()) {
                        int side = SBFScenarioProgress.sideId(game, game.getPlayer(f.getOwnerId()));
                        f.getUnits().stream().filter(u -> u.getCurrentArmor() > 0).forEach(u -> occupants.put(u, side));
                    }
                }
                var objective = game.scenarioProgress().objective(marker);
                Claim claim = objective.observe(game.getCurrentRound(), occupants);
                if (claim == Claim.CAPTURED) {
                    game.removeGroundObject(coords, marker);
                    manager.addReport(new SBFReportEntry(3511).add(sideName(game.getPlayer(objective.capturedSide())))
                          .add(marker.generalName()));
                    changed = true;
                } else if (claim == Claim.CONTESTED) {
                    manager.addReport(new SBFReportEntry(3512).add(marker.generalName()));
                }
            }
        }
        if (changed) {
            manager.send(markerPacket(game));
        }
    }

    /** Only the public SBF marker data belongs in the ground-object packet. Scenario notes remain server-side. */
    static Packet markerPacket(SBFGame game) {
        Map<Coords, List<ICarryable>> visible = new HashMap<>();
        game.getGroundObjects().forEach((coords, objects) -> {
            for (var object : objects) {
                if ((object instanceof ObjectiveMarker marker) && !marker.isDestroyed()) {
                    ObjectiveMarker copy = new ObjectiveMarker();
                    copy.setId(marker.getId());
                    copy.setName(marker.generalName());
                    copy.setOwnerId(marker.getOwnerId());
                    copy.setVictoryPointValue(marker.getVictoryPointValue());
                    copy.setHeadquarters(marker.isHeadquarters());
                    visible.computeIfAbsent(coords, ignored -> new ArrayList<>()).add(copy);
                }
            }
        });
        return new Packet(PacketCommand.UPDATE_GROUND_OBJECTS, visible);
    }

    static List<SBFFormation> roster(SBFGame game) {
        return Stream.concat(game.getActiveFormations().stream(), game.getGraveyard().stream()
              .filter(SBFFormation.class::isInstance).map(SBFFormation.class::cast)).toList();
    }

    static List<Player> sides(SBFGame game) {
        return roster(game).stream().map(f -> game.getPlayer(f.getOwnerId())).filter(java.util.Objects::nonNull)
              .map(p -> SBFScenarioProgress.sideId(game, p)).distinct().sorted().map(game::getPlayer).toList();
    }

    static VictoryResult pointsResult(SBFGame game, VictoryResult fallback) {
        if (!game.getOptions().booleanOption(SBFRuleOptions.BASE_VICTORY_POINTS)) {
            return fallback;
        }
        List<Player> sides = sides(game);
        if (sides.size() < 2) {
            return fallback;
        }
        Map<Integer, Long> scores = game.scenarioProgress().scores(game, sides);
        long best = scores.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<Player> winners = sides.stream().filter(p -> scores.get(p.getId()) == best).toList();
        return winners.size() == 1 ? VictoryResult.forPlayerOrTeam(winners.getFirst()) : VictoryResult.drawResult();
    }

    static void reportFinal(SBFGameManager manager, VictoryResult result, IntSupplier roll2d6) {
        manager.addReport(new SBFReportHeader(3502));
        SBFGame game = manager.getGame();
        if (game.getOptions().booleanOption(SBFRuleOptions.BASE_VICTORY_POINTS)) {
            List<Player> sides = sides(game);
            Map<Integer, Long> scores = game.scenarioProgress().scores(game, sides);
            for (Player side : sides) {
                manager.addReport(new SBFReportEntry(3513).add(sideName(side))
                      .add(java.math.BigDecimal.valueOf(scores.get(side.getId()) * 5, 2).stripTrailingZeros().toPlainString()));
            }
            if ((sides.size() == 2) && !result.isDraw()) {
                Player first = sides.getFirst();
                Player second = sides.getLast();
                Player high = scores.get(first.getId()) > scores.get(second.getId()) ? first : second;
                boolean wonOnPoints = (high.getId() == result.getWinningPlayer())
                      || ((high.getTeam() > Player.TEAM_NONE) && (high.getTeam() == result.getWinningTeam()));
                long margin = Math.abs(scores.get(first.getId()) - scores.get(second.getId()));
                if (wonOnPoints && (margin > 0)) {
                    // SBF Formation PV remains the printed purchase value when constituent Units are lost.
                    long budget = roster(game).stream().filter(f -> !high.isEnemyOf(game.getPlayer(f.getOwnerId())))
                          .mapToLong(SBFFormation::getPointValue).sum();
                    manager.addReport(new SBFReportEntry(margin > budget * 20L ? 3514 : 3515).add(sideName(high)));
                }
            }
        }
        if (game.getOptions().booleanOption(SBFRuleOptions.BASE_SALVAGE)) {
            salvage(manager, roll2d6);
        }
    }

    private static String sideName(Player player) {
        return player.getTeam() > Player.TEAM_NONE ? Player.TEAM_NAMES[player.getTeam()] : player.getName();
    }

    private static void salvage(SBFGameManager manager, IntSupplier roll2d6) {
        SBFGame game = manager.getGame();
        for (Loss loss : game.scenarioProgress().losses()) {
            if ((loss.unit().getType() == SBFElementType.CI) || (game.scenarioProgress().salvageRoll(loss.unit()) != null)
                  || ((loss.cause() != LossCause.COMBAT) && (loss.cause() != LossCause.MISSION_KILL))) {
                continue;
            }
            SalvageRoll check = new SalvageRoll(roll2d6.getAsInt(), loss.cause() == LossCause.MISSION_KILL ? 2 : 0);
            game.scenarioProgress().recordSalvageRoll(loss.unit(), check);
            var report = new SBFReportEntry(check.salvageable() ? 3516 : 3517)
                  .add(loss.unit().getName()).add(check.roll()).add(check.modifier());
            roster(game).stream().filter(f -> f.getId() == loss.formationId()).findFirst()
                  .ifPresent(f -> manager.addReportFor(report, f));
        }
    }
}
