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

import static megamek.common.alphaStrike.BattleForceSUA.*;
import static megamek.common.options.SBFRuleOptions.*;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import megamek.common.Player;
import megamek.common.units.Terrains;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.options.OptionsConstants;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.IntelBonus;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.ScenarioRole;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.InitialIntelligence;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.InitiativeOrders;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility;
import megamek.common.strategicBattleSystems.SBFUnitConverter;

/** Side-wide initiative, IO:BF pp. 194–195, 206–207 and 223. Evaluated before the round counter advances. */
final class SBFInitiativeModifiers {
    private final SBFGame game;
    private final Player side;
    private final List<SBFFormation> roster;
    private final List<SBFFormation> active;
    final InitiativeOrders orders;

    SBFInitiativeModifiers(SBFGame game, Player member) {
        this.game = game;
        side = game.getPlayer(SBFScenarioProgress.sideId(game, member));
        roster = SBFScenarioResolution.roster(game).stream().filter(f -> !game.areHostile(f, side)).toList();
        active = game.getActiveFormations().stream().filter(f -> !game.areHostile(f, side))
              .sorted(Comparator.comparingInt(SBFFormation::getId)).toList();
        orders = game.scenarioProgress().initiativeOrders(side.getId());
    }

    int total() {
        boolean advanced = option(INIT_MODIFIERS);
        boolean intel = option(INIT_BATTLEFIELD_INT);
        int result = intel ? intelBonus() : 0;
        if (advanced) {
            long recon = units().mapToLong(u -> u.getElements().isEmpty() ? (u.hasSUA(RCN) ? 2 : 0)
                  : u.getElements().stream().filter(SBFUnitConverter::isReconElement).count()).sum();
            if (!intel) {
                int mhq = units().mapToInt(SBFUnit::getMHQ).sum();
                result += (mhq >= 7 ? 2 : mhq >= 3 ? 1 : 0) * (recon >= 4 ? 2 : 1);
            }
            result += battlefieldBonus() + leadershipBonus() + statusPenalty(recon);
        }
        if (option(INIT_SPECIALIZATION)) {
            result += specialization();
        }
        if (option(INIT_BANKING) || option(INIT_FORCING) || option(INIT_SPECIALIZATION)) {
            if (game.scenarioProgress().losses().stream().anyMatch(loss -> !loss.withdrawn() && loss.unit().hasSUA(COM)
                  && !side.isEnemyOf(game.getPlayer(loss.ownerId())))) {
                result -= 2;
            }
        }
        if (orders.declaration() == SBFScenarioProgress.Declaration.FORCE && hasAbility(InitiativeAbility.FORCING)) {
            result += game.scenarioProgress().forcingModifier(game, side);
        }
        return result;
    }

    private int specialization() {
        int result = 0;
        for (var unit : units().filter(u -> u.hasAnySUAOf(COM, SCOM)).toList()) {
            var environment = unit.getEnvironmentSpecialization();
            if (environment != null) {
                result += environmentModifier(environment);
            }
            var enemy = unit.getEnemySpecialization();
            if (enemy != null) {
                boolean matches = game.getTeams().stream().filter(t -> t.nonObserverPlayers().stream().anyMatch(side::isEnemyOf))
                      .anyMatch(t -> enemy.enemies().stream().anyMatch(t.getFaction()::equalsIgnoreCase))
                      || game.getForces().getTopLevelForces().stream().filter(f -> side.isEnemyOf(game.getPlayer(f.getOwnerId())))
                            .anyMatch(f -> enemy.enemies().stream().anyMatch(f.getName()::equalsIgnoreCase));
                result += matches ? enemy.levels() : -Math.max(1, enemy.levels());
            }
            var tactical = unit.getTacticalSpecialization();
            if (tactical != null) {
                ScenarioRole role = game.scenarioProgress().role(side.getId());
                if (role != null) {
                    result += (role == ScenarioRole.ATTACKER ? 1 : -1) * (tactical.attack() - tactical.defense());
                }
                String scenario = game.scenarioProgress().scenarioType();
                if (tactical.scenarioLevels() > 0 && !scenario.isEmpty()) {
                    result += tactical.scenarioLevels() * (scenario.equalsIgnoreCase(tactical.scenario()) ? 1 : -1);
                }
            }
        }
        return result;
    }

    private int environmentModifier(SBFUnit.EnvironmentSpecialization spec) {
        if (spec.condition() != null && !spec.condition().isBlank()) {
            var conditions = game.getPlanetaryConditions();
            boolean present = Stream.of(conditions.getWeather().getExternalId(), conditions.getWind().getExternalId(),
                  conditions.getLight().getExternalId(), conditions.getFog().getExternalId(), conditions.getAtmosphere().getExternalId())
                  .anyMatch(spec.condition()::equalsIgnoreCase);
            return present ? spec.levels() : -1;
        }
        int terrain = Terrains.getType(spec.terrain().toLowerCase(Locale.ROOT));
        int present = 0;
        int total = 0;
        for (var board : game.getBoards().values()) {
            if (!board.isGround()) {
                continue;
            }
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    var hex = board.getHex(x, y);
                    if (hex != null) {
                        total++;
                        if (terrain == 0 ? hex.isClear() : hex.containsTerrain(terrain)
                              && (spec.terrainLevel() == null || hex.terrainLevel(terrain) == spec.terrainLevel())) {
                            present++;
                        }
                    }
                }
            }
        }
        return present == 0 ? -1 : present * 2 >= total ? spec.levels() : 0;
    }

    private boolean option(String option) {
        return game.getOptions().booleanOption(option);
    }

    private Stream<SBFUnit> units() {
        return active.stream().flatMap(f -> f.getUnits().stream()).filter(u -> u.getCurrentArmor() > 0);
    }

    boolean hasAbility(InitiativeAbility ability) {
        boolean inPlay = switch (ability) {
            case BANKING -> option(INIT_BANKING);
            case FORCING -> option(INIT_FORCING);
            default -> true;
        };
        return inPlay && units().anyMatch(u -> u.hasAnySUAOf(COM, SCOM) && u.getInitiativeAbilities().contains(ability));
    }

    private int battlefieldBonus() {
        int round = game.getCurrentRound();
        if (round < 1) {
            return 0;
        }
        int destroyed = game.scenarioProgress().losses().stream().filter(loss -> loss.round() == round
              && !loss.withdrawn() && side.isEnemyOf(game.getPlayer(loss.ownerId())))
              .mapToInt(loss -> Math.max(0, loss.unit().getCommandTier() - 2)).max().orElse(0);
        return Math.max(destroyed, game.scenarioProgress().objectiveInitiative(side.getId(), round));
    }

    private int leadershipBonus() {
        int tier = units().mapToInt(SBFUnit::getCommandTier).max().orElse(0);
        boolean computer = game.usesDesignQuirks() && active.stream()
              .filter(f -> f.moraleStatus() == SBFFormation.MoraleStatus.NORMAL)
              .flatMap(f -> f.getUnits().stream()).anyMatch(u -> u.getCurrentArmor() > 0 && u.hasSUA(COM)
                    && u.getElements().stream().anyMatch(e -> e.hasQuirk(OptionsConstants.QUIRK_POS_BATTLE_COMP)));
        return Math.max(tier >= 2 ? tier : 0, computer ? 2 : 0);
    }

    private int statusPenalty(long recon) {
        if (roster.isEmpty()) {
            return 0;
        }
        int threshold = (roster.size() + 1) / 2;
        int penalty = recon == 0 ? -1 : 0;
        if (active.stream().filter(SBFFormation::isBroken).count() >= threshold) {
            penalty = -1;
        }
        if (roster.stream().filter(SBFFormation::isRouted).count() >= threshold) {
            penalty = -2;
        }
        if (roster.stream().filter(f -> !f.hasWithdrawn() && !active.contains(f)).count() >= threshold) {
            penalty = -3;
        }
        return Math.min(penalty, game.scenarioProgress().headquartersPenalty(game, side));
    }

    /** A round-zero deployment puts the initial contingent in the playing area for the pregame BI comparison. */
    private boolean onField(SBFFormation formation) {
        return formation.occupiesGroundHex() || formation.isOnRadarMap()
              || (game.getCurrentRound() <= 0 && formation.getDeployRound() <= 0 && !formation.isEmbarked());
    }

    static Map<Integer, InitialIntelligence> initialSurvey(SBFGame game) {
        var saved = game.scenarioProgress().initialIntelligence();
        if (!saved.isEmpty()) {
            return saved;
        }
        Map<Integer, InitialIntelligence> forces = new LinkedHashMap<>();
        for (Player side : SBFScenarioResolution.sides(game)) {
            var force = new SBFInitiativeModifiers(game, side);
            int artilleryUnits = (int) force.units().filter(u -> u.getSpecialAbilities().getInternalRepr().keySet().stream()
                  .anyMatch(SBFArtillery::isArtilleryWeapon)).count();
            forces.put(side.getId(), new InitialIntelligence(force.intelligenceRating(), force.active.size(),
                  (int) force.units().count(), (int) force.active.stream().filter(f -> f.hasSUA(RCN)).count(), artilleryUnits));
        }
        return forces;
    }

    int intelligenceRating() {
        int score = 0;
        for (SBFFormation formation : active) {
            List<SBFUnit> live = formation.getUnits().stream().filter(u -> u.getCurrentArmor() > 0).toList();
            if (live.isEmpty()) {
                continue;
            }
            List<AlphaStrikeElement> elements = live.stream().flatMap(u -> u.getElements().stream()).toList();
            boolean dropShip = elements.stream().anyMatch(AlphaStrikeElement::isDropShip);
            if (formation.isAerospace() && dropShip) {
                score += onField(formation) ? 1 : 0;
            } else if (formation.hasSUA(RCN)) {
                score += 2;
            } else if (formation.isAerospace()) {
                score++;
            }
            if (onField(formation)) {
                score += (int) elements.stream().filter(e -> e.hasSUA(MHQ)).count();
            }
        }
        return score;
    }

    static boolean canCarry(IntelBonus bonus, SBFUnit unit) {
        return switch (bonus) {
            case HQ -> unit.getMHQ() >= 3;
            case RECON -> unit.hasSUA(RCN);
            case NETWORK -> unit.getMHQ() >= 1;
        };
    }

    /** Printed p. 207 rule uses one MHQ1+ Unit per Formation; its worked example uses a different divisor. */
    private int intelBonus() {
        if (active.isEmpty()) {
            return 0;
        }
        boolean recon = active.stream().filter(f -> f.getUnits().stream()
              .anyMatch(u -> u.getCurrentArmor() > 0 && u.hasSUA(RCN))).count() * 4 >= active.size();
        boolean network = units().filter(u -> u.getMHQ() >= 1).count() >= active.size();
        int bonus = 0;
        for (IntelBonus type : IntelBonus.values()) {
            if ((type == IntelBonus.RECON && !recon) || (type == IntelBonus.NETWORK && !network)) {
                continue;
            }
            if (orders.assignment(type) == null) {
                // Default only an unplaced token. A destroyed carrier never silently transfers its token.
                for (SBFFormation f : active) {
                    var unit = f.getUnits().stream().filter(u -> u.getCurrentArmor() > 0 && canCarry(type, u)).findFirst();
                    if (onField(f) && unit.isPresent()) {
                        orders.assign(type, f.getId(), unit.get());
                        break;
                    }
                }
            }
            var token = orders.assignment(type);
            if (token != null && game.getFormation(token.formationId()).filter(this::onField)
                  .filter(f -> f.getUnits().contains(token.unit()) && token.unit().getCurrentArmor() > 0)
                  .filter(f -> !blockedTokens(f)).isPresent()) {
                bonus++;
            }
        }
        return bonus;
    }

    private boolean blockedTokens(SBFFormation formation) {
        // Losing a granting Unit suppresses all tokens stacked on the same Formation (p. 207).
        for (IntelBonus token : IntelBonus.values()) {
            var assigned = orders.assignment(token);
            if (assigned != null && assigned.formationId() == formation.getId()
                  && (!formation.getUnits().contains(assigned.unit()) || assigned.unit().getCurrentArmor() <= 0)) {
                return true;
            }
        }
        return formation.occupiesGroundHex() && game.getActiveFormationsAt(formation.getPosition()).stream()
              .anyMatch(f -> f.occupiesGroundHex() && game.areHostile(f, side) && f.hasAnySUAOf(ECM, AECM, LECM));
    }
}
