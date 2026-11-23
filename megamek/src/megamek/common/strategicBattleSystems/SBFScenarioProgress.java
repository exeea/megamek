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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.options.SBFRuleOptions;

/** Server-side objective and Unit records for IO:BF p. 174, retained across rounds and saved games. */
public final class SBFScenarioProgress implements Serializable {
    /** Player selections for IO:BF pp. 207 and 223, retained across saves. */
    public enum IntelBonus { HQ, RECON, NETWORK }
    public enum Declaration { NORMAL, YIELD, SPEND, SPEND_TWO, FORCE }

    public static final class InitiativeOrders implements Serializable {
        public record Assignment(int formationId, SBFUnit unit) implements Serializable { }
        private final Map<IntelBonus, Assignment> assignments = new EnumMap<>(IntelBonus.class);
        private final Set<BoardLocation> preplotted = new LinkedHashSet<>();
        private Declaration declaration = Declaration.NORMAL;
        private int yieldedTurns;

        public boolean selectPreplot(BoardLocation hex, int allowance, boolean remove) {
            if (remove) {
                return preplotted.remove(hex);
            }
            return preplotted.size() < allowance && preplotted.add(hex);
        }

        public Set<BoardLocation> preplots() {
            return Set.copyOf(preplotted);
        }

        private void limitPreplots(int allowance) {
            var retained = preplotted.stream().limit(allowance).toList();
            preplotted.retainAll(retained);
        }

        public Assignment assignment(IntelBonus bonus) {
            return assignments.get(bonus);
        }

        public void assign(IntelBonus bonus, int formationId, SBFUnit unit) {
            assignments.put(bonus, new Assignment(formationId, unit));
        }

        public Declaration declaration() {
            return declaration;
        }

        public void declare(Declaration declaration) {
            this.declaration = declaration;
        }

        public int bankedPoints() {
            return yieldedTurns / 2;
        }

        public void creditYield() {
            yieldedTurns = Math.min(4, yieldedTurns + 1);
        }

        public void spend(int points) {
            yieldedTurns -= 2 * points;
        }
    }

    public enum ScenarioRole { ATTACKER, DEFENDER }

    private String scenarioType;
    private Map<Integer, ScenarioRole> roles;

    public String scenarioType() {
        return scenarioType == null ? "" : scenarioType;
    }

    public void setScenarioType(String type) {
        scenarioType = type.strip();
    }

    public void setRole(int side, ScenarioRole role) {
        if (roles == null) {
            roles = new HashMap<>();
        }
        ScenarioRole previous = roles.putIfAbsent(side, role);
        if (previous != null && previous != role) {
            throw new IllegalArgumentException("Allied players must have the same scenario role.");
        }
    }

    public ScenarioRole role(int side) {
        return roles == null ? null : roles.get(side);
    }

    /** Fixed pregame values for p. 207. Later losses only change the current BI rating and INIT bonuses. */
    public record InitialIntelligence(int rating, int formations, int units, int reconFormations, int artilleryUnits)
          implements Serializable { }

    private Map<Integer, InitialIntelligence> initialIntelligence;

    public void saveInitialIntelligence(Map<Integer, InitialIntelligence> forces) {
        if (initialIntelligence == null) {
            initialIntelligence = new HashMap<>(forces);
            forces.keySet().forEach(side -> initiativeOrders(side).limitPreplots(preplotAllowance(forces, side)));
        }
    }

    public Map<Integer, InitialIntelligence> initialIntelligence() {
        return initialIntelligence == null ? Map.of() : Map.copyOf(initialIntelligence);
    }

    public static int preplotAllowance(Map<Integer, InitialIntelligence> forces, int side) {
        if (forces.size() != 2 || !forces.containsKey(side)) {
            return 0;
        }
        InitialIntelligence own = forces.get(side);
        int opponent = forces.entrySet().stream().filter(e -> e.getKey() != side).findFirst().orElseThrow().getValue().rating();
        return Math.clamp(own.rating() - opponent, 0, own.artilleryUnits());
    }

    /** The defender increases only the BI comparison by 50%. Half the Force is a cap on whole Formations. */
    public int areaKnowledgeAllowance(int side) {
        var forces = initialIntelligence();
        if (forces.size() != 2 || !forces.containsKey(side)) {
            return 0;
        }
        var opponent = forces.entrySet().stream().filter(e -> e.getKey() != side).findFirst().orElseThrow();
        InitialIntelligence own = forces.get(side);
        if (own.reconFormations() == 0 || (own.rating() * (role(side) == ScenarioRole.DEFENDER ? 3 : 2)
              <= opponent.getValue().rating() * (role(opponent.getKey()) == ScenarioRole.DEFENDER ? 3 : 2))) {
            return 0;
        }
        return Math.min(own.formations() / 2, Math.round((float) own.units() / own.reconFormations()));
    }

    public boolean isPreplotted(SBFGame game, int owner, BoardLocation target) {
        Player player = game.getPlayer(owner);
        return player != null && game.getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)
              && game.getOptions().booleanOption(SBFRuleOptions.ADVANCED_ARTILLERY)
              && initiativeOrders(sideId(game, player)).preplotted.contains(target);
    }

    private Map<Integer, InitiativeOrders> initiativeOrders;

    public InitiativeOrders initiativeOrders(int side) {
        if (initiativeOrders == null) {
            initiativeOrders = new HashMap<>();
        }
        return initiativeOrders.computeIfAbsent(side, ignored -> new InitiativeOrders());
    }

    private record ArmorCrossing(int owner, int attacker) implements Serializable { }
    private Map<SBFUnit, ArmorCrossing> armorCrossings;
    private Integer crossingsRound;

    /** Count distinct Units first driven strictly below half armor in this turn, IO:BF p. 223. */
    public void crossedHalfArmor(int round, SBFUnit unit, int owner, int attacker) {
        if (!Integer.valueOf(round).equals(crossingsRound) || armorCrossings == null) {
            armorCrossings = new HashMap<>();
            crossingsRound = round;
        }
        armorCrossings.putIfAbsent(unit, new ArmorCrossing(owner, attacker));
    }

    public int forcingModifier(SBFGame game, Player side) {
        if (!Integer.valueOf(game.getCurrentRound()).equals(crossingsRound) || armorCrossings == null) {
            return 0;
        }
        int net = 0;
        for (ArmorCrossing loss : armorCrossings.values()) {
            Player owner = game.getPlayer(loss.owner);
            Player attacker = game.getPlayer(loss.attacker);
            if (attacker != null && attacker.isEnemyOf(owner)) {
                if (!side.isEnemyOf(attacker)) {
                    net++;
                } else if (!side.isEnemyOf(owner)) {
                    net--;
                }
            }
        }
        return net;
    }

    public enum LossCause {
        COMBAT, MISSION_KILL, ARTILLERY_OR_BOMB, CRASH, ROUT, SCATTER, TRANSPORT, FORCED_WITHDRAWAL, VOLUNTARY_WITHDRAWAL
    }

    public record Loss(int formationId, int ownerId, int attackerOwnerId, SBFUnit unit, LossCause cause, int round)
          implements Serializable {
        public boolean withdrawn() {
            return cause == LossCause.FORCED_WITHDRAWAL || cause == LossCause.VOLUNTARY_WITHDRAWAL;
        }
    }

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

    /** IO:BF p. 195. A capture is dated by its final observation even after the marker leaves the board. */
    public int objectiveInitiative(int side, int previousRound) {
        int best = 0;
        for (Objective objective : objectives.values()) {
            if (Integer.valueOf(previousRound).equals(objective.lastRound)) {
                if (Integer.valueOf(side).equals(objective.capturedSide)) {
                    return 2;
                }
                if (objective.capturedSide == null && objective.occupants.containsValue(side)) {
                    best = 1;
                }
            }
        }
        return best;
    }

    public int headquartersPenalty(SBFGame game, Player side) {
        int worst = 0;
        for (var entry : objectives.entrySet()) {
            Player owner = game.getPlayer(entry.getKey().getBelongsToPlayerId());
            if (!entry.getKey().isHeadquarters() || owner == null || side.isEnemyOf(owner)) {
                continue;
            }
            Objective claim = entry.getValue();
            if (claim.capturedSide != null && side.isEnemyOf(game.getPlayer(claim.capturedSide))) {
                return -4;
            }
            if (claim.capturedSide == null && claim.occupants.values().stream()
                  .anyMatch(occupier -> side.isEnemyOf(game.getPlayer(occupier)))) {
                worst = -2;
            }
        }
        return worst;
    }

    /** Leaving and returning to the same hex between End Phases interrupts every constituent Unit's claim. */
    public void interruptOccupation(SBFFormation formation) {
        objectives.values().forEach(o -> formation.getUnits().forEach(o.occupants::remove));
    }

    public void recordLoss(int round, SBFFormation formation, SBFUnit unit, int attackerOwnerId, LossCause cause) {
        unitLosses.putIfAbsent(unit, new Loss(formation.getId(), formation.getOwnerId(), attackerOwnerId, unit, cause, round));
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
