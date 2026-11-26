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

import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;

import megamek.common.Player;
import megamek.common.Team;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFEngagementOrders;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.AirDefense;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.AirResponse;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.EndPair;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Exchange;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;

/** Standard Engagement Control and Ending Engagements (IO:BF pp. 167–168, 172). */
record SBFEngagementControl(SBFGameManager manager, IntSupplier dice) {
    SBFEngagementControl(SBFGameManager manager) {
        this(manager, () -> Compute.d6(2));
    }

    /** Returns the route actually traveled, or null while its owners are being asked for orders. */
    SBFMovePath resolveMovement(SBFFormation mover, SBFMovePath route) {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        orders.startRound(game.getCurrentRound());
        if (route.getSteps().isEmpty()) {
            SBFMovementHazards.stops(manager, mover, mover.getPosition(), route, 0, dice);
            return route;
        }
        SBFMovePath traveled = new SBFMovePath(mover.getId(), mover.getPosition(), game);
        for (int i = 0; i <= route.getSteps().size(); i++) {
            if (i > 0) {
                SBFMovePath extended = SBFMovePath.createMovePathShallow(traveled);
                extended.addStep(route.getSteps().get(i - 1));
                // Immediate criticals can reduce the remaining Move while traversing the submitted route.
                if ((i > orders.lastResolvedStep()) && (extended.getMpUsed() + transportCostThrough(route, i)
                      > SBFMovePath.maximumMovementPoints(mover, game))) {
                    traveled.setJumpUsed(route.getJumpUsed());
                    return traveled;
                }
                mover.setHullDown(false);
                traveled = extended;
            }
            boolean stoppedByTerrain = SBFMovementHazards.stops(manager, mover, traveled.getLastPosition(), route, i, dice);
            if (game.getFormation(mover.getId()).isEmpty() || (i == 0 && stoppedByTerrain)) {
                return traveled;
            }
            if (i > 0 && !orders.alreadyVisited(i)) {
                if (!resolveAmbushes(mover, route, traveled.getLastPosition(), i)) {
                    return null;
                }
                orders.finishEntry(i);
                if (game.getFormation(mover.getId()).isEmpty()) {
                    return traveled;
                }
                SBFHiddenCommand.detectOnEntry(manager, mover, traveled.getLastPosition(), dice);
            }
            if ((i < route.getSteps().size()) && !orders.alreadyVisited(i + 1)) {
                route.refreshDeparture(i, traveled.getLastPosition());
            }
            for (SBFFormation other : opponentsAt(mover, traveled.getLastPosition())) {
                if (orders.alreadyDeclined(mover.getId(), other.getId())) {
                    continue;
                }
                int exitCost = i < route.getSteps().size() ? route.getSteps().get(i).getMpUsed() : Integer.MAX_VALUE;
                Order ownChoice = validIntent(mover, other, traveled.getMpUsed() + transportCostThrough(route, i), exitCost);
                Order otherChoice = validIntent(other, mover, other.getMpSpentThisTurn(), Integer.MAX_VALUE);
                if ((ownChoice == null) || (otherChoice == null)) {
                    orders.awaitOrders(route, other.getId());
                    if (ownChoice == null) {
                        prompt(mover, other);
                    }
                    if (otherChoice == null) {
                        prompt(other, mover);
                    }
                    return null;
                }
                if (game.usesDoubleBlind() && (ownChoice.seeksEngagement() || otherChoice.seeksEngagement())) {
                    manager.detectionHelper.noteCombat(mover, traveled.getLastPosition());
                    manager.detectionHelper.noteCombat(other, traveled.getLastPosition());
                }
                Boolean contest = movementOutcome(mover, other, ownChoice, otherChoice, traveled.getLastPosition(),
                      route, i);
                if (contest == null) {
                    orders.awaitOrders(route, other.getId());
                    return null;
                }
                if ((ownChoice == Order.OVERRUN) || (ownChoice == Order.EVADE)) {
                    orders.recordResolvedStep(i);
                }
                if (game.getFormation(mover.getId()).isEmpty()) {
                    return traveled;
                }
                if (contest && game.getFormation(other.getId()).isPresent()) {
                    setEngaged(mover, other, true);
                    other.setDone(true);
                    manager.sendUnitUpdate(other);
                    traveled.setJumpUsed(route.getJumpUsed());
                    return traveled;
                }
                orders.decline(mover.getId(), other.getId());
            }
            if (stoppedByTerrain) {
                traveled.setJumpUsed(route.getJumpUsed());
                return traveled;
            }
        }
        return route;
    }

    /** IO:BF p. 214. Each hidden owner may fire immediately on entry, including before a probe reveals them. */
    private boolean resolveAmbushes(SBFFormation mover, SBFMovePath route, BoardLocation location, int step) {
        SBFGame game = manager.getGame();
        var orders = game.engagementOrders();
        var resolved = orders.resolvedAmbushes(step);
        var damage = new SBFContactDamage(manager, dice);
        for (SBFFormation other : game.getActiveFormationsAt(location)) {
            if (resolved.contains(other.getId()) || !game.areHostile(other, game.getPlayer(mover.getOwnerId()))) {
                continue;
            }
            var exchange = orders.exchange();
            if (exchange == null) {
                if (!other.isHidden() || (!SBFContactDamage.canAmbush(game, other, ASRange.SHORT)
                      && !SBFContactDamage.canAmbush(game, other, ASRange.MEDIUM))) {
                    continue;
                }
                exchange = new Exchange(mover.getId(), other.getId(), Order.AMBUSH, true, -2, null);
                orders.setExchange(exchange);
            }
            if (exchange.order() != Order.AMBUSH || exchange.opponent() != other.getId()) {
                continue;
            }
            if (!damage.resolve(mover, other, location, route, step)) {
                orders.awaitOrders(route, other.getId());
                return false;
            }
            resolved.add(other.getId());
            if (game.getFormation(mover.getId()).isEmpty()) {
                break;
            }
        }
        return true;
    }

    boolean chooseAmbush(int player, int formation, SBFEngagementOrders.Ambush choice) {
        var orders = manager.getGame().engagementOrders();
        var exchange = orders.exchange();
        if (exchange == null || exchange.order() != Order.AMBUSH || exchange.opponent() != formation
              || exchange.shooter() != -2 || manager.getGame().getFormation(formation)
                    .filter(f -> f.getOwnerId() == player && f.isHidden() && (choice == SBFEngagementOrders.Ambush.HOLD
                          || SBFContactDamage.canAmbush(manager.getGame(), f, choice == SBFEngagementOrders.Ambush.SHORT
                                ? ASRange.SHORT : ASRange.MEDIUM))).isEmpty()) {
            return false;
        }
        int selection = switch (choice) {
            case HOLD -> -1;
            case SHORT -> 0;
            case MEDIUM -> 1;
        };
        orders.setExchange(exchange.withShooter(selection));
        resume(formation);
        return true;
    }

    /** Air-to-Air Engagements, IO:BF pp. 176–177. There is no extra Thrust cost to leave a hostile zone. */
    Boolean airContact(SBFFormation mover, SBFFormation opponent, SBFMovePath route) {
        SBFEngagementOrders orders = manager.getGame().engagementOrders();
        if (manager.getGame().usesDoubleBlind()) {
            // Same-zone airborne Squadrons always see each other, including an encounter during transit (IO:BF p. 177).
            revealAirContact(mover, opponent);
            revealAirContact(opponent, mover);
        }
        if (mover.isEngagedWith(opponent.getId())) {
            return !mover.passesAirOpponent(opponent.getId());
        }
        if (orders.alreadyDeclined(mover.getId(), opponent.getId())) {
            return false;
        }
        AirDefense defense = orders.airDefense();
        if (defense == null) {
            Order own = mover.mayAttackAirOpponent(opponent.getId()) ? intent(mover, opponent) : Order.AVOID;
            Order other = opponent.mayAttackAirOpponent(mover.getId()) ? intent(opponent, mover) : Order.AVOID;
            if (!airOrder(own) || !airOrder(other)) {
                orders.awaitOrders(route, opponent.getId());
                if (!airOrder(own)) {
                    prompt(mover, opponent);
                }
                if (!airOrder(other)) {
                    prompt(opponent, mover);
                }
                return null;
            }
            SBFFormation winner;
            boolean engage;
            if (orders.isWinnerPair(mover.getId(), opponent.getId())) {
                winner = orders.decisionWinner() == mover.getId() ? mover : opponent;
                Boolean chosen = orders.takeDecision();
                if (chosen == null) {
                    orders.awaitOrders(route, opponent.getId());
                    return null;
                }
                engage = chosen;
            } else {
                winner = (own == Order.AVOID) && (other == Order.AVOID) ? null : rollWinner(mover, opponent, 0, 0);
                Order choice = winner == mover ? own : other;
                if ((winner != null) && (choice == Order.ASK)) {
                    SBFFormation loser = winner == mover ? opponent : mover;
                    orders.awaitOrders(route, opponent.getId());
                    orders.awaitWinner(winner.getId(), loser.getId());
                    promptWinner(winner, loser);
                    return null;
                }
                engage = (winner != null) && choice.seeksEngagement();
            }
            if (!engage) {
                orders.decline(mover.getId(), opponent.getId());
                return false;
            }
            SBFFormation defender = winner == mover ? opponent : mover;
            defense = new AirDefense(winner.getId(), defender.getId(), defender.inHighDrop() ? AirResponse.RESERVE : null);
            orders.setAirDefense(defense);
        }
        SBFFormation attacker = defense.attacker() == mover.getId() ? mover : opponent;
        SBFFormation defender = attacker == mover ? opponent : mover;
        if (defense.response() == null) {
            orders.awaitOrders(route, opponent.getId());
            promptAirDefense(defender, attacker);
            return null;
        }
        attacker.commitAirAttack(defender.getId());
        if (defense.response() == AirResponse.RETURN) {
            defender.commitAirAttack(attacker.getId());
        } else {
            defender.declineAirReturn(attacker.getId(), defense.response() == AirResponse.PASS);
        }
        setEngaged(mover, opponent, true);
        orders.setAirDefense(null);
        if (!opponent.passesAirOpponent(mover.getId())) {
            opponent.setDone(true);
        }
        manager.sendUnitUpdate(opponent);
        return !mover.passesAirOpponent(opponent.getId());
    }

    private void revealAirContact(SBFFormation viewer, SBFFormation contact) {
        var visibility = manager.getGame().visibilityHelper();
        var old = visibility.getObservedVisibility(viewer.getOwnerId(), contact.getId());
        var seen = old.bestOf(SBFVisibilityStatus.EYES_ON_TARGET);
        if (old != seen) {
            visibility.setVisibility(viewer.getOwnerId(), contact.getId(), seen);
            manager.sendUnitUpdate(contact); // Recipient-specific placeholders preserve other sides' fog of war.
        }
    }

    private void promptAirDefense(SBFFormation defender, SBFFormation attacker) {
        manager.sendServerChat(defender.getOwnerId(), "Air engagement: choose /sbfaero return, reserve, or pass followed by "
              + defender.getId() + " " + attacker.getId() + ". Reserve saves your attack, pass also allows movement.");
    }

    boolean airResponse(int player, int own, int other, AirResponse choice) {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        AirDefense defense = orders.airDefense();
        SBFFormation defender = game.getFormation(own).orElse(null);
        if (!game.getPhase().isMovement() || (orders.pendingMove() == null) || (defense == null)
              || (defense.defender() != own) || (defense.attacker() != other) || (defense.response() != null)
              || (defender == null) || (defender.getOwnerId() != player)
              || ((choice == AirResponse.RETURN) && !defender.mayAttackAirOpponent(other))) {
            return false;
        }
        orders.setAirDefense(new AirDefense(other, own, choice));
        resume(own);
        return true;
    }

    private boolean airOrder(Order order) {
        return (order == Order.ENGAGE) || (order == Order.AVOID) || (order == Order.ASK);
    }

    private int transportCostThrough(SBFMovePath route, int step) {
        return (int) route.getTransportOrders().stream().filter(order -> order.afterSteps() <= step).count();
    }

    private List<SBFFormation> opponentsAt(SBFFormation mover, BoardLocation location) {
        SBFGame game = manager.getGame();
        Player owner = game.getPlayer(mover.getOwnerId());
        return game.getActiveFormationsAt(location).stream()
              .filter(f -> f.isDeployed() && !f.isHidden())
              .filter(other -> game.areHostile(other, owner))
              .sorted(Comparator.comparingInt(SBFFormation::getId))
              .toList();
    }

    private Order intent(SBFFormation side, SBFFormation opponent) {
        if (side.isMoraleRestricted() || side.mustWithdraw() || side.wasOverrunThisTurn()) {
            return Order.AVOID;
        }
        return manager.getGame().engagementOrders().intent(side.getId(), opponent.getId());
    }

    private Order validIntent(SBFFormation side, SBFFormation opponent, int spent, int exitCost) {
        Order order = intent(side, opponent);
        if ((order == Order.OVERRUN) || (order == Order.EVADE)) {
            if ((exitCost == Integer.MAX_VALUE) || ((long) spent + exitCost > SBFMovePath.normalMovementPoints(side, manager.getGame()))
                  || ((order == Order.OVERRUN) && (side.hasOverrunThisTurn()
                  || (SBFContactDamage.formationDamage(side, ASRange.SHORT) < 2)))) {
                manager.sendServerChat(side.getOwnerId(), "Overrun and Evade require sufficient Move to exit. "
                      + "Overrun also requires Short damage 2 and is limited to once a turn.");
                return null;
            }
        }
        if (order == Order.FORCE) {
            boolean hasLongDamage = side.getUnits().stream().anyMatch(unit -> unit.getCurrentDamage().L().damage > 0);
            if (!hasLongDamage || (spent >= SBFMovePath.normalMovementPoints(side, manager.getGame()))) {
                manager.sendServerChat(side.getOwnerId(), "Forced Engagement requires Long damage and 1 MP remaining.");
                return null;
            }
        }
        return order;
    }

    private Boolean movementOutcome(SBFFormation first, SBFFormation second, Order ownOrder, Order otherOrder,
          BoardLocation contact, SBFMovePath route, int step) {
        SBFEngagementOrders orders = manager.getGame().engagementOrders();
        if ((ownOrder == Order.OVERRUN) || (ownOrder == Order.EVADE)) {
            Exchange exchange = orders.exchange();
            if (exchange == null) {
                int modifier = ownOrder == Order.OVERRUN ? second.getSize() - first.getSize() : -3;
                SBFFormation winner = rollWinner(first, second, modifier, otherOrder == Order.FORCE ? -3 : 0);
                exchange = new Exchange(first.getId(), second.getId(), ownOrder, winner == first, -2, null);
                orders.setExchange(exchange);
                if ((winner == second) && (otherOrder == Order.FORCE)) {
                    second.setForcedOpponent(first.getId());
                }
            }
            if (!new SBFContactDamage(manager, dice).resolve(first, second, contact, route, step)) {
                return null;
            }
            if (ownOrder == Order.OVERRUN) {
                first.recordOverrun(false);
                if (exchange.succeeded()) {
                    second.recordOverrun(true);
                }
            } else {
                first.recordEvade(exchange.succeeded());
            }
            return !exchange.succeeded();
        }
        if (orders.isWinnerPair(first.getId(), second.getId())) {
            return orders.takeDecision();
        }
        if ((ownOrder == Order.AVOID) && (otherOrder == Order.AVOID)) {
            return false;
        }
        SBFFormation winner = rollWinner(first, second, ownOrder == Order.FORCE ? -3 : 0,
              otherOrder == Order.FORCE ? -3 : 0);
        if (winner == null) {
            return false;
        }
        SBFFormation loser = winner == first ? second : first;
        Order choice = winner == first ? ownOrder : otherOrder;
        if (choice == Order.ASK) {
            orders.awaitWinner(winner.getId(), loser.getId());
            promptWinner(winner, loser);
            return null;
        }
        if (choice == Order.FORCE) {
            winner.setForcedOpponent(loser.getId());
        }
        return choice.seeksEngagement();
    }

    void remind(int playerId) {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        if (game.getPhase().isEnd() && !orders.remainingEnds().isEmpty()) {
            if (orders.decisionWinner() != Player.PLAYER_NONE) {
                SBFFormation winner = game.getFormation(orders.decisionWinner()).orElseThrow();
                if (winner.getOwnerId() == playerId) {
                    EndPair pair = orders.selectedEnd();
                    int other = pair.first() == winner.getId() ? pair.second() : pair.first();
                    promptWinner(winner, game.getFormation(other).orElseThrow());
                }
            } else if (playerId == initiativeWinner()) {
                promptOrder(playerId, winnerPairs(playerId));
            }
            return;
        }
        if (orders.pendingMove() == null) {
            return;
        }
        if (orders.exchange() != null) {
            new SBFContactDamage(manager, dice).remind(playerId, orders.exchange());
            return;
        }
        SBFFormation mover = game.getFormation(orders.pendingMove().getEntityId()).orElse(null);
        SBFFormation other = game.getFormation(orders.contactId()).orElse(null);
        if ((mover != null) && (other != null)) {
            if (orders.airDefense() != null) {
                SBFFormation defender = orders.airDefense().defender() == mover.getId() ? mover : other;
                if (defender.getOwnerId() == playerId) {
                    promptAirDefense(defender, defender == mover ? other : mover);
                }
                return;
            }
            if (orders.isWinnerPair(mover.getId(), other.getId())) {
                SBFFormation winner = orders.decisionWinner() == mover.getId() ? mover : other;
                if (winner.getOwnerId() == playerId) {
                    promptWinner(winner, winner == mover ? other : mover);
                }
                return;
            }
            if ((mover.getOwnerId() == playerId) && (intent(mover, other) == null)) {
                prompt(mover, other);
            } else if ((other.getOwnerId() == playerId) && (intent(other, mover) == null)) {
                prompt(other, mover);
            }
        }
    }

    private void promptWinner(SBFFormation winner, SBFFormation loser) {
        manager.sendServerChat(winner.getOwnerId(), "You won Engagement Control. Use /sbfengage " + winner.getId()
              + " engage " + loser.getId() + " or choose avoid to permit movement.");
    }

    private void prompt(SBFFormation side, SBFFormation other) {
        manager.sendServerChat(side.getOwnerId(), "Movement is waiting for your engagement order. Use /sbfengage "
              + side.getId() + " engage " + other.getId() + " or /sbfengage " + side.getId() + " avoid "
              + other.getId() + ". Use ask to choose after winning."
              + (side.combatZone() != null ? "" : " Ground orders also include force, overrun and evade."));
    }

    private SBFFormation rollWinner(SBFFormation first, SBFFormation second, int firstModifier, int secondModifier) {
        first.setForcedOpponent(null);
        second.setForcedOpponent(null);
        int firstTarget = first.getTactics() + firstModifier + atmosphereModifier(first, second) + moraleModifier(first)
              + zoneOfControlModifier(first, second);
        int secondTarget = second.getTactics() + secondModifier + atmosphereModifier(second, first) + moraleModifier(second)
              + zoneOfControlModifier(second, first);
        while (true) {
            int firstRoll = dice.getAsInt();
            int secondRoll = dice.getAsInt();
            int firstMargin = firstRoll - firstTarget;
            int secondMargin = secondRoll - secondTarget;
            boolean firstSucceeds = (firstRoll != 2) && (firstMargin >= 0);
            boolean secondSucceeds = (secondRoll != 2) && (secondMargin >= 0);
            String rolls = "Engagement control " + first.getId() + " rolled " + firstRoll + " against "
                  + firstTarget + ", " + second.getId() + " rolled " + secondRoll + " against "
                  + secondTarget + ". ";
            if (!firstSucceeds && !secondSucceeds) {
                notifyBoth(first, second, rolls + "Both failed.");
                return null;
            }
            int comparison = Boolean.compare(firstSucceeds, secondSucceeds);
            if (comparison == 0) {
                comparison = Integer.compare(firstMargin, secondMargin);
            }
            if (comparison == 0) {
                comparison = Integer.compare(second.getTactics(), first.getTactics());
            }
            if (comparison != 0) {
                SBFFormation winner = comparison > 0 ? first : second;
                notifyBoth(first, second, rolls + "Formation " + winner.getId() + " won.");
                return winner;
            }
            notifyBoth(first, second, rolls + "Tied margin and Tactics. Rerolling.");
        }
    }

    /**
     * Zone of Control, IO:BF p. 226: "a -1 Target Number modifier to all Engagement Control Rolls when Establishing
     * Engagement Control during the Movement Phase (see p. 167)", while a commander Unit carrying it survives.
     */
    private int zoneOfControlModifier(SBFFormation formation, SBFFormation other) {
        SBFGame game = manager.getGame();
        Player owner = game.getPlayer(formation.getOwnerId());
        return game.getPhase().isMovement() && (owner != null) && !formation.sharesAerospaceMap(other)
              && new SBFInitiativeModifiers(game, owner)
              .hasAbility(megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility.ZONE_OF_CONTROL) ? -1 : 0;
    }

    /** Engagement Control Roll Modifiers Table (IO:BF p. 168), including attempts to break off at End. */
    private int moraleModifier(SBFFormation formation) {
        return switch (formation.moraleStatus()) {
            case SHAKEN -> 1;
            case BROKEN -> 2;
            case ROUTED -> 3;
            default -> 0;
        };
    }

    private int atmosphereModifier(SBFFormation squadron, SBFFormation other) {
        if (!squadron.sharesAerospaceMap(other)) {
            return 0;
        }
        SBFGame game = manager.getGame();
        if (game.getPhase().isEnd()) {
            if (game.combatOrders().isBehind(squadron, other)) {
                return 0;
            } else if (game.combatOrders().isBehind(other, squadron)) {
                return 4;
            }
        }
        return 2;
    }

    private void notifyBoth(SBFFormation first, SBFFormation second, String text) {
        manager.sendServerChat(first.getOwnerId(), text);
        manager.sendServerChat(second.getOwnerId(), text);
    }

    static void setEngaged(SBFFormation first, SBFFormation second, boolean engaged) {
        first.setEngagedWith(second.getId(), engaged);
        second.setEngagedWith(first.getId(), engaged);
    }

    void endEngagements() {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        // Pairs that break off here can move apart next round without rolling again on departure (p. 172).
        orders.startRound(game.getCurrentRound() + 1);
        for (SBFFormation first : game.getActiveFormations()) {
            for (int opponentId : first.getEngagedOpponents()) {
                SBFFormation second = game.getFormation(opponentId).orElse(null);
                boolean sameLocation = (second != null) && (first.combatZone() != null
                      ? first.sharesAerospaceMap(second) && first.combatZone() == second.combatZone()
                      : ((first.getPosition() != null) && first.getPosition().equals(second.getPosition())));
                if (!sameLocation) {
                    first.setEngagedWith(opponentId, false);
                } else if (first.getId() < opponentId) {
                    orders.remainingEnds().add(new EndPair(first.getId(), opponentId));
                }
            }
        }
        resolveEnds();
    }

    private int initiativeWinner() {
        SBFGame game = manager.getGame();
        Team highest = game.getTeams().stream().filter(team -> !team.nonObserverPlayers().isEmpty())
              .max(Comparator.comparing(Team::getInitiative)).orElse(null);
        List<Player> winningPlayers = highest == null ? game.getPlayersList() : highest.nonObserverPlayers();
        return winningPlayers.stream().filter(player -> !player.isObserver())
              .max(Comparator.comparing((Player player) -> winnerPairs(player.getId()).size() > 1)
                    .thenComparing(Player::getInitiative))
              .map(Player::getId).orElse(Player.PLAYER_NONE);
    }

    private List<EndPair> winnerPairs(int playerId) {
        SBFGame game = manager.getGame();
        return game.engagementOrders().remainingEnds().stream()
              .filter(pair -> game.getFormation(pair.first()).orElseThrow().getOwnerId() == playerId
                    || game.getFormation(pair.second()).orElseThrow().getOwnerId() == playerId)
              .toList();
    }

    private void promptOrder(int playerId, List<EndPair> candidates) {
        manager.sendServerChat(playerId, "You won initiative. Select the next ending engagement using "
              + "/sbfengage <your formation ID> next <opponent ID>. Remaining pairs " + candidates + ".");
    }

    void chooseEnd(int player, int own, int opponent) {
        SBFGame game = manager.getGame();
        if (!game.getPhase().isEnd() || (player != initiativeWinner())
              || game.getFormation(own).filter(formation -> formation.getOwnerId() == player).isEmpty()
              || (game.engagementOrders().selectedEnd() != null)) {
            manager.sendServerChat(player, "There is no engagement order for you to select.");
            return;
        }
        EndPair chosen = winnerPairs(player).stream().filter(pair -> pair.matches(own, opponent)).findFirst().orElse(null);
        if (chosen == null) {
            manager.sendServerChat(player, "Choose one of your remaining engagement pairs.");
            return;
        }
        game.engagementOrders().selectEnd(chosen);
        resumeEnds();
    }

    private void resolveEnds() {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        while (!orders.remainingEnds().isEmpty()) {
            EndPair pair = orders.selectedEnd();
            if (pair == null) {
                int player = initiativeWinner();
                List<EndPair> candidates = winnerPairs(player);
                if (candidates.size() > 1) {
                    promptOrder(player, candidates);
                    return;
                }
                pair = candidates.isEmpty() ? orders.remainingEnds().getFirst() : candidates.getFirst();
                orders.selectEnd(pair);
            }
            SBFFormation first = game.getFormation(pair.first()).orElseThrow();
            SBFFormation second = game.getFormation(pair.second()).orElseThrow();
            Boolean keep;
            if (orders.isWinnerPair(first.getId(), second.getId())) {
                keep = orders.takeDecision();
                if (keep == null) {
                    return;
                }
            } else {
                boolean firstContinues = continuesAtEnd(first, second);
                boolean secondContinues = continuesAtEnd(second, first);
                keep = firstContinues;
                if (firstContinues != secondContinues) {
                    SBFFormation winner = rollWinner(first, second, 0, 0);
                    SBFFormation other = winner == first ? second : first;
                    if ((winner != null) && (intent(winner, other) == Order.ASK)) {
                        orders.awaitWinner(winner.getId(), other.getId());
                        promptWinner(winner, other);
                        return;
                    }
                    keep = (winner != null) && (winner == first ? firstContinues : secondContinues);
                }
            }
            setEngaged(first, second, keep);
            if (!keep) {
                orders.decline(first.getId(), second.getId());
            }
            orders.remainingEnds().remove(pair);
            orders.selectEnd(null);
        }
    }

    private void resumeEnds() {
        resolveEnds();
        manager.entityAllUpdate();
        if (manager.getGame().engagementOrders().remainingEnds().isEmpty() && !manager.getGame().takingAdjustmentOrders()) {
            manager.endCurrentPhase();
        }
        manager.sendPendingPackets();
    }

    private boolean continuesAtEnd(SBFFormation side, SBFFormation other) {
        Order order = intent(side, other);
        return (order != null) && order.seeksEngagement();
    }

    void submitOrder(int playerId, int formationId, int opponentId, boolean engage) {
        submitOrder(playerId, formationId, opponentId, engage ? Order.ENGAGE : Order.AVOID);
    }

    void submitOrder(int playerId, int formationId, int opponentId, Order order) {
        SBFGame game = manager.getGame();
        SBFFormation side = game.getFormation(formationId).orElse(null);
        if ((order == Order.AMBUSH) || (side == null) || (side.getOwnerId() != playerId) || !side.isDeployed()) {
            manager.sendServerChat(playerId, "Choose one of your deployed formations.");
            return;
        }
        SBFEngagementOrders orders = game.engagementOrders();
        SBFMovePath pending = orders.pendingMove();
        boolean pendingPair = pending != null
              && (((pending.getEntityId() == formationId) && (orders.contactId() == opponentId))
              || ((pending.getEntityId() == opponentId) && (orders.contactId() == formationId)));
        if ((opponentId != Player.PLAYER_NONE) && !pendingPair && !side.isEngagedWith(opponentId)) {
            manager.sendServerChat(playerId, "Choose a current engagement opponent or omit the opponent ID.");
            return;
        }
        if (side.combatZone() != null && !airOrder(order)) {
            manager.sendServerChat(playerId, "Air-to-air orders are engage, avoid or ask.");
            return;
        }
        if ((order != Order.AVOID) && (side.isMoraleRestricted() || side.mustWithdraw() || side.wasOverrunThisTurn())) {
            manager.sendServerChat(playerId, "This formation's morale or withdrawal requires it to avoid engagement.");
            return;
        }
        if (orders.exchange() != null) {
            new SBFContactDamage(manager, dice).remind(playerId, orders.exchange());
            return;
        }
        boolean endDecision = game.getPhase().isEnd() && (orders.selectedEnd() != null)
              && orders.selectedEnd().matches(formationId, opponentId);
        if ((pendingPair || endDecision) && orders.isWinnerPair(formationId, opponentId)) {
            if ((formationId != orders.decisionWinner()) || ((order != Order.ENGAGE) && (order != Order.AVOID))) {
                manager.sendServerChat(playerId, "The winner chooses engage or avoid for this contact.");
                return;
            }
            orders.decide(order == Order.ENGAGE);
            if (endDecision) {
                resumeEnds();
                return;
            }
        } else {
            orders.order(formationId, opponentId, order);
            manager.sendServerChat(playerId, "Formation " + formationId + " recorded " + order
                  + " for future engagement decisions.");
        }
        resume(formationId);
    }

    void selectUnit(int player, int own, int target, boolean shoot, int unitNumber) {
        if (!new SBFContactDamage(manager, dice).select(player, own, target, shoot, unitNumber)) {
            manager.sendServerChat(player, "That Unit selection is unavailable.");
            return;
        }
        resume(own);
    }

    private void resume(int formationId) {
        SBFGame game = manager.getGame();
        SBFEngagementOrders orders = game.engagementOrders();
        SBFMovePath pending = orders.pendingMove();
        if (pending != null && game.getPhase().isMovement()
              && ((formationId == pending.getEntityId()) || (formationId == orders.contactId()))) {
            SBFFormation mover = game.getFormation(pending.getEntityId()).orElse(null);
            if ((mover != null) && (game.getTurn() != null) && (pending.radarFlight() != null
                  ? game.getTurn().playerId() == mover.getOwnerId() : game.getTurn().isValid(mover.getOwnerId(), mover, game))) {
                orders.clearPendingMove();
                if (pending.radarFlight() != null) {
                    new SBFAerospace(manager).fly(mover, pending);
                } else {
                    manager.movementProcessor.resumeMovement(pending, mover);
                }
                manager.sendPendingPackets();
            }
        }
    }
}
