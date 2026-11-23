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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationConverter;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/** Ground Formation record-sheet adjustments during setup and End Phase (IO:BF pp. 172, 198). */
final class SBFFormationCommand extends ServerCommand {
    static final String HELP = "/sbfform detach <formation> <Unit numbers, 1-based> | split <formation> | reform <formation>"
          + " | rendezvous <formation> <column> <row> | assemble <receiving formation> <other formation>:<Unit number> ... | done.";
    private final SBFGameManager manager;

    SBFFormationCommand(Server server, SBFGameManager manager) {
        super(server, "sbfform", HELP);
        this.manager = manager;
    }

    @Override
    public void run(int owner, String[] args) {
        SBFGame game = manager.getGame();
        if (args.length == 2 && args[1].equalsIgnoreCase("done") && game.getPhase().isEnd() && mayOrder(game, owner)) {
            game.getPlayer(owner).setDone(true);
            manager.checkSetupReady();
            manager.sendPendingPackets();
            return;
        }
        try {
            if (args.length == 5 && args[1].equalsIgnoreCase("rendezvous")) {
                var member = game.getFormation(Integer.parseInt(args[2])).orElse(null);
                if (member != null && member.occupiesGroundHex()) {
                    var destination = megamek.common.board.BoardLocation.of(new megamek.common.board.Coords(
                          Integer.parseInt(args[3]) - 1, Integer.parseInt(args[4]) - 1), member.getPosition().boardId());
                    if (planReunion(game, owner, member, destination)) {
                        manager.entityAllUpdate();
                        manager.sendPendingPackets();
                        return;
                    }
                }
            }
            var formation = args.length >= 3 ? game.getFormation(Integer.parseInt(args[2])).orElse(null) : null;
            if (canAdjust(game, owner, formation)) {
                List<String> details = Arrays.asList(args).subList(3, args.length);
                boolean accepted = switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "detach" -> detach(game, owner, formation, details.stream().map(Integer::parseInt).toList(), false);
                    case "split" -> details.isEmpty() && detach(game, owner, formation, List.of(), true);
                    case "reform" -> details.isEmpty() && reform(game, owner, formation);
                    case "assemble" -> assemble(game, owner, formation, details);
                    default -> false;
                };
                if (accepted) {
                    manager.entityAllUpdate();
                    manager.sendPendingPackets();
                    return;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // No Units are transferred until the complete order has been checked.
        }
        server.sendServerChat(owner, HELP);
    }

    private static boolean mayOrder(SBFGame game, int owner) {
        var player = game.getPlayer(owner);
        return game.getOptions().booleanOption(SBFRuleOptions.BASE_ADJUST_FORMATIONS)
              && player != null && !player.isObserver() && (game.getPhase() == GamePhase.LOUNGE
              || (game.getPhase().isEnd() && !player.isDone() && game.engagementOrders().remainingEnds().isEmpty()));
    }

    private static boolean canAdjust(SBFGame game, int owner, SBFFormation f) {
        return mayOrder(game, owner) && eligibleCounter(game, owner, f) && f.getEngagedOpponents().isEmpty();
    }

    private static boolean eligibleCounter(SBFGame game, int owner, SBFFormation f) {
        return f != null && f.getOwnerId() == owner && !f.isAerospace() && !f.isEmbarked()
              && !f.isOffMapArtillery() && game.passengersOf(f).isEmpty()
              && !f.inactiveAfterAssembly(game.getCurrentRound()) && !f.inactiveAfterAssembly(game.getCurrentRound() + 1)
              && (game.getPhase().isLounge() || f.occupiesGroundHex());
    }

    static boolean planReunion(SBFGame game, int owner, SBFFormation member, megamek.common.board.BoardLocation destination) {
        if (!game.getOptions().booleanOption(SBFRuleOptions.BASE_ADJUST_FORMATIONS) || !game.getPhase().isMovement()
              || member.getOwnerId() != owner || !member.occupiesGroundHex() || !game.hasBoardLocation(destination)) {
            return false;
        }
        var family = game.originalFamily(member);
        if (family.size() < 2 || family.stream().anyMatch(f -> !f.occupiesGroundHex() || f.inactiveAfterAssembly(game.getCurrentRound())
              || !f.getPosition().isSameBoardAs(destination))) {
            return false;
        }
        var anchor = family.getFirst();
        var old = anchor.reunionIn(game.getCurrentRound());
        if (old != null && !old.equals(destination)) {
            return false;
        }
        family.forEach(f -> f.planReunion(destination, game.getCurrentRound()));
        return true;
    }

    static boolean finishReunions(SBFGame game) {
        if (!game.getPhase().isEnd() || !game.getOptions().booleanOption(SBFRuleOptions.BASE_ADJUST_FORMATIONS)) {
            return false;
        }
        boolean changed = false;
        for (var anchor : game.getActiveFormations()) {
            var destination = anchor.reunionIn(game.getCurrentRound());
            if (destination == null || !destination.equals(anchor.getPosition())) {
                continue;
            }
            var arrived = game.originalFamily(anchor).stream().filter(f -> destination.equals(f.getPosition())
                  && eligibleCounter(game, anchor.getOwnerId(), f)).toList();
            if (arrived.size() < 2) {
                continue;
            }
            if (game.hasOriginalSurvivors(arrived)) {
                mergeOriginal(game, arrived, anchor);
            } else {
                var receiver = arrived.getFirst();
                Map<SBFFormation, List<SBFUnit>> transfers = new java.util.LinkedHashMap<>();
                arrived.subList(1, arrived.size()).forEach(f -> transfers.put(f, List.copyOf(f.getUnits())));
                assemble(game, receiver, transfers);
            }
            changed = true;
        }
        return changed;
    }

    static boolean detach(SBFGame game, int owner, SBFFormation parent, List<Integer> numbers, boolean split) {
        if (!canAdjust(game, owner, parent) || parent.originalFormationId() != parent.getId() || parent.getUnits().size() < 2) {
            return false;
        }
        var relatives = game.originalFamily(parent);
        int prior = relatives.stream().filter(f -> f != parent).mapToInt(f -> f.getUnits().size()).sum();
        List<SBFUnit> units = parent.getUnits();
        if ((!split && (numbers.isEmpty() || numbers.size() != numbers.stream().distinct().count()
              || numbers.stream().anyMatch(n -> n < 1 || n > units.size())
              || prior + numbers.size() > (units.size() + prior) / 2)) || (split && prior > 0)) {
            return false;
        }
        List<List<SBFUnit>> groups = split ? units.subList(1, units.size()).stream().map(List::of).toList()
              : List.of(numbers.stream().map(n -> units.get(n - 1)).toList());
        for (var group : groups) {
            separate(game, parent, group, !split);
        }
        return true;
    }

    /** Cargo handling can remove one Unit without changing the state of the rest of its Formation (IO:BF p. 199). */
    static SBFFormation separateUnit(SBFGame game, SBFFormation parent, SBFUnit unit) {
        return parent.getUnits().size() == 1 ? parent : separate(game, parent, List.of(unit), false);
    }

    private static SBFFormation separate(SBFGame game, SBFFormation parent, List<SBFUnit> group, boolean movesWithParent) {
        int historicalPV = parent.getPointValue() - parent.getUnits().stream().mapToInt(SBFUnit::getPointValue).sum();
        parent.setUnits(new ArrayList<>(parent.getUnits()));
        var child = new SBFFormation();
        child.setId(SBFScenarioResolution.roster(game).stream().mapToInt(SBFFormation::getId).max().orElse(-1) + 1);
        child.setName(parent.getName() + " / " + group.getFirst().getName());
        child.setOwnerId(parent.getOwnerId());
        child.setPosition(parent.getPosition());
        child.setDeployed(parent.isDeployed());
        child.setDeployRound(parent.getDeployRound());
        child.setHidden(parent.isHidden());
        child.setMoraleStatus(parent.moraleStatus());
        child.assignOrigin(parent.originalFormationId(), movesWithParent);
        child.setUnits(new ArrayList<>(group));
        if (movesWithParent) {
            child.setMovementGroup(parent.movementGroup());
        }
        group.forEach(parent::removeUnit);
        recalculate(game, child, 0);
        recalculate(game, parent, historicalPV);
        game.addUnit(child);
        if (game.getForces().contains(parent.getForceId())) {
            game.getForces().addEntity(child, parent.getForceId());
        }
        for (Player player : game.getPlayersList()) {
            game.visibilityHelper().setVisibility(player.getId(), child.getId(),
                  game.visibilityHelper().getObservedVisibility(player.getId(), parent.getId()));
        }
        return child;
    }

    static boolean reform(SBFGame game, int owner, SBFFormation member) {
        if (!game.getPhase().isEnd() || !canAdjust(game, owner, member)) {
            return false;
        }
        var family = game.originalFamily(member);
        if (family.size() < 2 || !game.hasOriginalSurvivors(family) || family.stream().anyMatch(f -> !canAdjust(game, owner, f)
              || !java.util.Objects.equals(member.getPosition(), f.getPosition()))) {
            return false;
        }
        mergeOriginal(game, family, member);
        return true;
    }

    private static void mergeOriginal(SBFGame game, List<SBFFormation> family, SBFFormation member) {
        SBFFormation receiver = family.stream().filter(f -> f.getId() == member.originalFormationId())
              .findFirst().orElse(member);
        receiver.setUnits(new ArrayList<>(receiver.getUnits()));
        int pv = family.stream().mapToInt(SBFFormation::getPointValue).sum();
        for (var other : family) {
            if (other != receiver) {
                retainMorale(receiver, other);
                other.getUnits().forEach(receiver::addUnit);
                retireCounter(game, other, receiver);
            }
        }
        recalculate(game, receiver, 0);
        receiver.setPointValue(pv);
        receiver.markReformed(game.getCurrentRound() + 1);
        game.reanchorMovementGroups(family, null);
    }

    static boolean assemble(SBFGame game, int owner, SBFFormation receiver, List<String> choices) {
        if (!game.getPhase().isEnd() || !canAdjust(game, owner, receiver) || choices.isEmpty() || receiver.getPosition() == null) {
            return false;
        }
        Map<SBFFormation, List<SBFUnit>> transfers = new java.util.LinkedHashMap<>();
        for (String choice : choices) {
            String[] pair = choice.split(":", -1);
            if (pair.length != 2) {
                return false;
            }
            var source = game.getFormation(Integer.parseInt(pair[0])).orElse(null);
            int number = Integer.parseInt(pair[1]);
            if (!canAdjust(game, owner, source) || source == receiver || !game.onSameBoard(receiver, source)
                  || receiver.getPosition().coords().distance(source.getPosition().coords()) > 1
                  || number < 1 || number > source.getUnits().size()) {
                return false;
            }
            var proposed = transfers.computeIfAbsent(source, unused -> new ArrayList<>());
            var unit = source.getUnits().get(number - 1);
            if (proposed.contains(unit)) {
                return false;
            }
            proposed.add(unit);
        }
        assemble(game, receiver, transfers);
        return true;
    }

    private static void assemble(SBFGame game, SBFFormation receiver, Map<SBFFormation, List<SBFUnit>> transfers) {
        var counters = new ArrayList<>(transfers.keySet());
        counters.addFirst(receiver);
        if (transfers.entrySet().stream().allMatch(e -> receiver.getPosition().equals(e.getKey().getPosition())
              && e.getValue().size() == e.getKey().getUnits().size()) && game.hasOriginalSurvivors(counters)) {
            mergeOriginal(game, counters, receiver);
            return;
        }
        var oldSheets = counters.stream().flatMap(f -> game.originalFamily(f).stream()).distinct().toList();
        // Receive into the chosen superior. In a tie, selecting a receiver resolves the owner's choice.
        Map<Integer, Integer> elementsByForce = new HashMap<>();
        countElements(elementsByForce, receiver, receiver.getUnits());
        transfers.forEach((f, units) -> countElements(elementsByForce, f, units));
        int majority = elementsByForce.values().stream().mapToInt(Integer::intValue).sum() / 2;
        int superior = elementsByForce.entrySet().stream().filter(e -> e.getValue() > majority).map(Map.Entry::getKey)
              .findFirst().orElse(receiver.getForceId());
        receiver.setUnits(new ArrayList<>(receiver.getUnits()));
        int oldPV = receiver.getPointValue() - receiver.getUnits().stream().mapToInt(SBFUnit::getPointValue).sum();
        for (var entry : transfers.entrySet()) {
            var source = entry.getKey();
            retainMorale(receiver, source);
            source.setUnits(new ArrayList<>(source.getUnits()));
            int extraPV = source.getPointValue() - source.getUnits().stream().mapToInt(SBFUnit::getPointValue).sum();
            for (var unit : entry.getValue()) {
                if (!receiver.getPosition().equals(source.getPosition())) {
                    game.scenarioProgress().interruptOccupation(unit);
                }
                source.removeUnit(unit);
                receiver.addUnit(unit);
            }
            if (source.getUnits().isEmpty()) {
                oldPV += extraPV;
                retireCounter(game, source, receiver);
            } else {
                recalculate(game, source, extraPV);
            }
        }
        if (game.getForces().contains(superior)) {
            game.getForces().addEntity(receiver, superior);
        }
        game.retireSheets(oldSheets, receiver);
        receiver.markAdHoc(game.getCurrentRound() + 1);
        recalculate(game, receiver, oldPV);
    }

    private static void retainMorale(SBFFormation receiver, SBFFormation source) {
        if (source.moraleStatus().compareTo(receiver.moraleStatus()) > 0) {
            receiver.setMoraleStatus(source.moraleStatus());
        }
    }

    private static void countElements(Map<Integer, Integer> counts, SBFFormation source, List<SBFUnit> units) {
        counts.merge(source.getForceId(), units.stream().mapToInt(u -> Math.max(1, u.getElements().size())).sum(), Integer::sum);
    }

    private static void retireCounter(SBFGame game, SBFFormation f, SBFFormation receiver) {
        for (int enemyId : f.getEngagedOpponents()) {
            game.getFormation(enemyId).ifPresent(enemy -> {
                SBFEngagementControl.setEngaged(f, enemy, false);
                SBFEngagementControl.setEngaged(receiver, enemy, true);
            });
        }
        game.getForces().removeEntityFromForces(f);
        f.clearReunion();
        game.forget(f.getId());
    }

    private static void recalculate(SBFGame game, SBFFormation f, int historicalPV) {
        f.getSpecialAbilities().getInternalRepr().clear();
        SBFFormationConverter.calculateStatsFromUnits(f);
        f.setPointValue(f.getPointValue() + Math.max(0, historicalPV));
        if (game.getOptions().booleanOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL)) {
            f.updateForcedWithdrawalAfterAdjustment();
        }
    }
}
