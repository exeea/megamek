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

package megamek.common.strategicBattleSystems;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.equipment.ObjectiveMarker;

/** Server-side objective and Unit records for IO:BF p. 174, retained across rounds and saved games. */
public final class SBFScenarioProgress implements Serializable {
    public enum LossCause {
        COMBAT, MISSION_KILL, ARTILLERY_OR_BOMB, CRASH, ROUT, SCATTER, TRANSPORT, FORCED_WITHDRAWAL, VOLUNTARY_WITHDRAWAL
    }

    public record Loss(int formationId, int ownerId, int attackerOwnerId, SBFUnit unit, LossCause cause) implements Serializable { }

    public enum Claim { UNCHANGED, OCCUPIED, CAPTURED, CONTESTED }

    public static final class Objective implements Serializable {
        private final Map<SBFUnit, Integer> occupants = new HashMap<>();
        private final Set<Integer> occupiedSides = new HashSet<>();
        private Integer capturedSide;
        private Integer lastRound;

        /** Values are side identifiers, so allied formations never contest each other. */
        public Claim observe(int round, Map<SBFUnit, Integer> present) {
            if ((capturedSide != null) || ((lastRound != null) && (lastRound == round))) {
                return Claim.UNCHANGED;
            }
            Set<Integer> continuous = new HashSet<>();
            if ((lastRound != null) && (lastRound == round - 1)) {
                present.forEach((unit, side) -> {
                    if (side.equals(occupants.get(unit))) {
                        continuous.add(side);
                    }
                });
            }
            occupants.clear();
            occupants.putAll(present);
            lastRound = round;
            if (continuous.size() == 1) {
                capturedSide = continuous.iterator().next();
                return Claim.CAPTURED;
            }
            if (continuous.size() > 1) {
                return Claim.CONTESTED;
            }
            return occupiedSides.addAll(present.values()) ? Claim.OCCUPIED : Claim.UNCHANGED;
        }

        public Integer capturedSide() {
            return capturedSide;
        }
    }

    private final Map<ObjectiveMarker, Objective> objectives = new LinkedHashMap<>();
    private final Map<SBFUnit, Loss> unitLosses = new LinkedHashMap<>();
    public record SalvageRoll(int roll, int modifier) implements Serializable {
        public boolean salvageable() {
            return roll + modifier >= 8;
        }
    }

    private final Map<SBFUnit, SalvageRoll> salvageRolls = new LinkedHashMap<>();

    public Objective objective(ObjectiveMarker marker) {
        return objectives.computeIfAbsent(marker, ignored -> new Objective());
    }

    /** Scenario objectives are selected by their authored marker name, including after removal on capture. */
    public boolean hasCaptured(String markerName, Integer side) {
        return objectives.entrySet().stream().anyMatch(entry -> markerName.equals(entry.getKey().generalName())
              && entry.getValue().capturedSide != null && (side == null || side.equals(entry.getValue().capturedSide)));
    }

    /** Leaving and returning to the same hex between End Phases interrupts every constituent Unit's claim. */
    public void interruptOccupation(SBFFormation formation) {
        objectives.values().forEach(o -> formation.getUnits().forEach(o.occupants::remove));
    }

    public void recordLoss(SBFFormation formation, SBFUnit unit, int attackerOwnerId, LossCause cause) {
        unitLosses.putIfAbsent(unit, new Loss(formation.getId(), formation.getOwnerId(), attackerOwnerId, unit, cause));
    }

    public List<Loss> losses() {
        return List.copyOf(unitLosses.values());
    }

    public void recordSalvageRoll(SBFUnit unit, SalvageRoll roll) {
        salvageRolls.putIfAbsent(unit, roll);
    }

    public SalvageRoll salvageRoll(SBFUnit unit) {
        return salvageRolls.get(unit);
    }

    /** Count in twentieths of a VP, preserving ties without rounding intermediate 0.1, 0.2 and 0.25 awards. */
    public Map<Integer, Long> scores(SBFGame game, List<Player> sides) {
        Map<Integer, Long> totals = sides.stream().collect(Collectors.toMap(Player::getId, p -> 0L));
        objectives.forEach((marker, claim) -> {
            if (claim.capturedSide != null) {
                totals.merge(claim.capturedSide, 20L * marker.getVictoryPointValue(), Long::sum);
            } else {
                claim.occupiedSides.forEach(side -> totals.merge(side, 5L * marker.getVictoryPointValue(), Long::sum));
            }
        });
        for (Loss loss : unitLosses.values()) {
            if (loss.cause() == LossCause.VOLUNTARY_WITHDRAWAL) {
                continue;
            }
            Player owner = game.getPlayer(loss.ownerId());
            Player attacker = game.getPlayer(loss.attackerOwnerId());
            if ((attacker == null) || !attacker.isEnemyOf(owner)) {
                List<Player> enemies = sides.stream().filter(owner::isEnemyOf).toList();
                if (enemies.size() != 1) {
                    continue; // No specified beneficiary for neutral events in a multi-sided scenario.
                }
                attacker = enemies.getFirst();
            }
            int side = sideId(game, attacker);
            long award = (loss.cause() == LossCause.FORCED_WITHDRAWAL ? 2L : 4L) * loss.unit().getPointValue();
            totals.merge(side, award, Long::sum);
        }
        return totals;
    }

    public static int sideId(SBFGame game, Player player) {
        return game.getPlayersList().stream().filter(p -> !p.isObserver() && !player.isEnemyOf(p))
              .mapToInt(Player::getId).min().orElse(player.getId());
    }
}
