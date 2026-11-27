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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.Team;
import megamek.common.enums.GamePhase;
import megamek.common.game.AbstractGame;
import megamek.common.game.InGameObject;
import megamek.common.options.SBFRuleOptions;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.Declaration;
import megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility;
import megamek.common.turns.TurnOrdered;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFInitiativeRollReportEntry;
import megamek.common.strategicBattleSystems.SBFPlayerNameReportEntry;
import megamek.common.strategicBattleSystems.SBFPlayerTurn;
import megamek.common.strategicBattleSystems.SBFPublicReportEntry;
import megamek.common.strategicBattleSystems.SBFReportEntry;
import megamek.common.strategicBattleSystems.SBFReportHeader;
import megamek.common.strategicBattleSystems.SBFTurn;
import megamek.common.units.Deployable;

public record SBFInitiativeHelper(SBFGameManager gameManager) implements SBFGameManagerHelper {

    /**
     * Determines the turn order for a given phase, setting the game's turn list and sending it to the Clients. Also
     * resets the turn index.
     *
     * @param phase The phase to find the turns for
     *
     * @see AbstractGame#resetTurnIndex()
     */
    void determineTurnOrder(GamePhase phase) {
        final List<SBFTurn> turns;
        if (phase == GamePhase.PREMOVEMENT || phase == GamePhase.PRE_FIRING) {
            // A fixed opportunity per player keeps the number and ownership of hidden counters private.
            turns = game().getActiveFormations().stream().anyMatch(f -> f.isDeployed() && f.isHidden())
                  ? game().getPlayersList().stream().filter(p -> !p.isObserver()).<SBFTurn>map(p -> new SBFPlayerTurn(p.getId())).toList()
                  : List.of();
        } else if (phase.isDeployMinefields()) {
            turns = game().getPlayersList()
                  .stream()
                  .filter(Player::hasMinefields)
                  .map(p -> new SBFPlayerTurn(p.getId()))
                  .collect(Collectors.toList());

        } else if (phase.isDeployment() && game().getCurrentRound() == 0) {
            List<SBFTurn> winnerFirst = formationTurns(phase, true);
            turns = winnerFirst.isEmpty() ? List.of() : List.of(new SBFPlayerTurn(winnerFirst.getFirst().playerId()));
        } else {
            turns = formationTurns(phase);
        }

        game().setTurns(turns);
        game().resetTurnIndex();
        send(packetHelper().createTurnListPacket());
    }

    /** IO:BF, “Unequal Number of Formations” and “Combat Phase”, p. 164. */
    List<SBFTurn> formationTurns(GamePhase phase) {
        return formationTurns(phase, false);
    }

    List<SBFTurn> formationTurns(GamePhase phase, boolean winnerFirst) {
        boolean reconMovement = phase.isMovement() && game().usesDoubleBlind();
        if (phase.isMovement()) {
            game().visibilityHelper().beginMovement();
        }
        if ((phase.isMovement() || phase.isFiring()) && game().getOptions().booleanOption(SBFRuleOptions.CAPITAL_AEROSPACE)
              && game().getActiveFormations().stream().anyMatch(f -> f.capitalSector() != null)) {
            var areas = game().getActiveFormations().stream().map(f -> f.assignPhaseCombatArea(game().getCurrentRound(),
                        f.carrierId() == null ? f : game().getFormation(f.carrierId()).orElse(f)))
                  .distinct().sorted().toList();
            return areas.stream().flatMap(area -> formationTurns(phase, winnerFirst, reconMovement, area).stream()).toList();
        }
        return formationTurns(phase, winnerFirst, reconMovement, null);
    }

    private List<SBFTurn> formationTurns(GamePhase phase, boolean winnerFirst, boolean reconMovement, String area) {
        java.util.Set<Integer> movementGroups = new java.util.HashSet<>();
        Map<Integer, List<SBFFormationTurn>> byOwner = game().getActiveFormations().stream()
              .filter(f -> f.assignedToCombatArea(area))
              .filter(f -> !reconMovement || !game().visibilityHelper().isUncountedMover(f.getId()))
              .filter(f -> SBFFormationTurn.isEligible(f, phase, game().getCurrentRound()))
              .filter(f -> !phase.isFiring() || !f.overrunsIn(game().getCurrentRound()))
              .filter(f -> !phase.isMovement() || movementGroups.add(f.movementGroup()))
              .map(f -> new SBFFormationTurn(f.getOwnerId(), area))
              .collect(Collectors.groupingBy(SBFFormationTurn::playerId));
        List<ArrayDeque<SBFTurn>> sides = new ArrayList<>();
        List<SBFTurn> remainingMovers = new ArrayList<>();
        // Overrun Combat, IO:BF p. 224: "the overrunning Formations fall outside of the normal sequence"
        List<SBFTurn> overrunning = new ArrayList<>();
        if (phase.isFiring()) {
            game().getActiveFormations().stream().filter(f -> f.overrunsIn(game().getCurrentRound()))
                  .filter(f -> f.assignedToCombatArea(area))
                  .filter(f -> SBFFormationTurn.isEligible(f, phase, game().getCurrentRound()))
                  .forEach(f -> overrunning.add(new SBFFormationTurn(f.getOwnerId(), area, true)));
        }
        List<Team> ranked = game().getTeams().stream().filter(team -> !team.isObserverTeam())
              .sorted(Comparator.comparing(Team::getInitiative).reversed()).toList();
        int overrunTurns = phase.isMovement() && !reconMovement ? overrunCount(ranked) : 0;
        Team overrunWinner = (overrunTurns > 0) ? ranked.getFirst() : null;
        Comparator<Team> initiative = Comparator.comparing(Team::getInitiative);
        for (Team team : game().getTeams().stream().sorted(winnerFirst ? initiative.reversed() : initiative).toList()) {
            ArrayDeque<SBFTurn> side = new ArrayDeque<>();
            team.nonObserverPlayers().stream().sorted(Comparator.comparing(Player::getInitiative))
                  .forEach(player -> {
                      side.addAll(byOwner.getOrDefault(player.getId(), List.of()));
                      if (reconMovement) {
                          // One opportunity per owner, independent of how many private counters exist.
                          remainingMovers.add(new SBFFormationTurn(player.getId(), area));
                      }
                  });
            if (team == overrunWinner) {
                for (int count = Math.min(overrunTurns, side.size()); count > 0; count--) {
                    overrunning.add(new SBFFormationTurn(side.removeFirst().playerId(), area, true));
                }
            }
            if (!side.isEmpty()) {
                sides.add(side);
            }
        }

        List<SBFTurn> turns = new ArrayList<>(overrunning);
        while (!sides.isEmpty()) {
            int smallestSide = sides.stream().mapToInt(ArrayDeque::size).min().orElseThrow();
            for (var side : sides) {
                int groupSize = phase.isFiring() ? side.size() : side.size() / smallestSide;
                for (int i = 0; i < groupSize; i++) {
                    turns.add(side.removeFirst());
                }
            }
            sides.removeIf(ArrayDeque::isEmpty);
        }
        turns.addAll(remainingMovers);
        return turns;
    }

    /**
     * Overrun Combat, IO:BF p. 224: a Force with this ability that "wins an Initiative roll by a margin of 2 points or
     * more ... divides the number of points by which he has won the Initiative roll by 2, rounding all results down ...
     * This becomes the number of Formations with which the player may move and attack before any other Formations can
     * act in the current turn." The ability is carried by a commander Unit (p. 224).
     *
     * @param ranked the non-observer teams, best Initiative first
     * @return the number of Formations the top team moves and attacks with first; 0 when it has no overrun
     */
    private int overrunCount(List<Team> ranked) {
        if ((ranked.size() < 2) || ranked.getFirst().nonObserverPlayers().isEmpty()) {
            return 0;
        }
        var first = ranked.getFirst().getInitiative();
        var second = ranked.get(1).getInitiative();
        int index = Math.min(first.size(), second.size()) - 1;
        int margin = (index < 0) ? 0 : first.getRoll(index) - second.getRoll(index);
        return (margin >= 2) && new SBFInitiativeModifiers(game(), ranked.getFirst().nonObserverPlayers().getFirst())
              .hasAbility(InitiativeAbility.OVERRUN_COMBAT) ? margin / 2 : 0;
    }

    /** IO:BF p. 195. The winning side completes reconnaissance first. Ready votes are saved with the game. */
    boolean hasReconPriority(int playerId) {
        Player player = game().getPlayer(playerId);
        if (player == null || player.isObserver() || player.isGhost() || player.isDone()) {
            return false;
        }
        return game().getTeams().stream()
              .filter(team -> team.nonObserverPlayers().stream().anyMatch(member -> !member.isDone() && !member.isGhost()))
              .max(Comparator.comparing(Team::getInitiative))
              .map(team -> team.nonObserverPlayers().contains(player)).orElse(false);
    }

    void rollInitiative() {
        if (!game().getPhase().isInitiative()) {
            // The separate deployment roll is unmodified 2D6 (IO:BF p. 163).
            Map<Team, Integer> noBonuses = game().getTeams().stream().collect(Collectors.toMap(team -> team, team -> 0));
            TurnOrdered.rollInitiative(game().getTeams(), false, Map.of(), noBonuses);
            return;
        }
        Map<Team, SBFInitiativeModifiers> forces = new LinkedHashMap<>();
        Map<Team, Integer> bonuses = new LinkedHashMap<>();
        for (Team team : game().getTeams()) {
            if (!team.isObserverTeam()) {
                var force = new SBFInitiativeModifiers(game(), team.nonObserverPlayers().getFirst());
                forces.put(team, force);
                int modifier = force.total();
                if (modifier != 0) {
                    bonuses.put(team, team.getTotalInitBonus(false) + modifier);
                }
            }
        }
        if (forces.size() == 2) {
            List<Team> sides = List.copyOf(forces.keySet());
            var a = forces.get(sides.getFirst());
            var b = forces.get(sides.getLast());
            int first = bankingBid(a);
            int second = bankingBid(b);
            // A second point is spent only to break opposing automatic successes.
            a.orders.spend(Math.clamp(first, 0, second > 0 ? 2 : 1));
            b.orders.spend(Math.clamp(second, 0, first > 0 ? 2 : 1));
            if (first != second) {
                // Subordinate owners still roll for their places within their own side.
                game().getTeams().forEach(team -> team.clearInitiative(false, Map.of()));
                sides.getFirst().getInitiative().automaticResult(first > second);
                sides.getLast().getInitiative().automaticResult(second > first);
            } else {
                TurnOrdered.rollInitiative(game().getTeams(), false, Map.of(), bonuses);
            }
            if (first == -1 && sides.getFirst().getInitiative().compareTo(sides.getLast().getInitiative()) < 0) {
                a.orders.creditYield();
            }
            if (second == -1 && sides.getLast().getInitiative().compareTo(sides.getFirst().getInitiative()) < 0) {
                b.orders.creditYield();
            }
        } else {
            TurnOrdered.rollInitiative(game().getTeams(), false, Map.of(), bonuses);
        }
        forces.values().forEach(force -> force.orders.declare(Declaration.NORMAL));
    }

    private static int bankingBid(SBFInitiativeModifiers force) {
        if (!force.hasAbility(InitiativeAbility.BANKING)) {
            return 0;
        }
        return switch (force.orders.declaration()) {
            case YIELD -> -1;
            case SPEND -> Math.min(1, force.orders.bankedPoints());
            case SPEND_TWO -> Math.min(2, force.orders.bankedPoints());
            default -> 0;
        };
    }

    void writeInitiativeReport() {
        writeHeader();
        writeInitiativeRolls();
        writeTurnOrder();
        writeFutureDeployment();
        writeWeatherReport();
    }

    private void writeTurnOrder() {
        if (!gameManager.usesDoubleBlind()) {
            addReport(new SBFReportEntry(1020));

            for (SBFTurn turn : game().getTurnsList()) {
                Player player = game().getPlayer(turn.playerId());
                addReport(new SBFPlayerNameReportEntry(player).indent().addNL());
            }
        }
    }

    private void writeFutureDeployment() {
        // remaining deployments
        Comparator<Deployable> comp = Comparator.comparingInt(Deployable::getDeployRound);
        List<Deployable> futureDeployments = game().getInGameObjects()
              .stream()
              .filter(unit -> unit instanceof Deployable)
              .map(unit -> (Deployable) unit)
              .filter(unit -> !unit.isDeployed())
              .sorted(comp)
              .toList();

        if (!futureDeployments.isEmpty()) {
            addReport(new SBFPublicReportEntry(1060));
            int round = -1;

            for (Deployable deployable : futureDeployments) {
                if (round != deployable.getDeployRound()) {
                    round = deployable.getDeployRound();
                    addReport(new SBFPublicReportEntry(1065).add(round));
                }

                SBFReportEntry r = new SBFReportEntry(1066).subject(((InGameObject) deployable).getId());
                r.add(((InGameObject) deployable).generalName());
                r.add("1");
                r.add("2");
                addReport(r);
            }
            addReport(new SBFPublicReportEntry(1210).newLines(2));
        }
    }

    private void writeWeatherReport() {
        PlanetaryConditions conditions = game().getPlanetaryConditions();
        addReport(new SBFPublicReportEntry(1025).add(conditions.getWindDirection().toString()));
        addReport(new SBFPublicReportEntry(1030).add(conditions.getWind().toString()));
        addReport(new SBFPublicReportEntry(1031).add(conditions.getWeather().toString()));
        addReport(new SBFPublicReportEntry(1032).add(conditions.getLight().toString()));
        addReport(new SBFPublicReportEntry(1033).add(conditions.getFog().toString()));
    }

    private void writeInitiativeRolls() {
        for (Team team : game().getTeams()) {
            // Teams with no active players can be ignored
            if (team.isObserverTeam()) {
                continue;
            }

            if (game().getOptions().booleanOption(SBFRuleOptions.INIT_BATTLEFIELD_INT)) {
                Player member = team.nonObserverPlayers().getFirst();
                addReport(new SBFPublicReportEntry(1067).add(team.getNonObserverSize() == 1 ? member.getName()
                      : Player.TEAM_NAMES[team.getId()]).add(new SBFInitiativeModifiers(game(), member).intelligenceRating()));
            }
            // If there is only one non-observer player, list them as the 'team', and use the team initiative
            if (team.getNonObserverSize() == 1) {
                final Player player = team.nonObserverPlayers().getFirst();
                addReport(new SBFPlayerNameReportEntry(player));
                addReport(new SBFPublicReportEntry(1015).noNL());
                addReport(new SBFInitiativeRollReportEntry(team.getInitiative()));
            } else {
                // Multiple players. List the team, then break it down.
                SBFReportEntry r = new SBFPublicReportEntry(1068).add(Player.TEAM_NAMES[team.getId()]);
                r.add(team.getInitiative().toString());
                addReport(r);
                for (Player player : team.nonObserverPlayers()) {
                    addReport(new SBFPublicReportEntry(1068).indent()
                          .add(player.getName())
                          .add(player.getInitiative().toString()));
                }
            }
        }
    }

    private void writeHeader() {
        if (game().getLastPhase().isDeployment() || game().isDeploymentComplete() || !game().shouldDeployThisRound()) {
            addReport(new SBFReportHeader(1000).add(game().getCurrentRound()));
        } else {
            if (game().getCurrentRound() == 0) {
                addReport(new SBFReportHeader(1005));
            } else {
                addReport(new SBFReportHeader(1010).add(game().getCurrentRound()));
            }
        }
    }
}
