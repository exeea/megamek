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
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.compute.Compute;
import megamek.common.strategicBattleSystems.SBFEngagementOrders;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Exchange;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.common.strategicBattleSystems.SBFFormation;
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
                traveled = extended;
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
                Boolean contest = movementOutcome(mover, other, ownChoice, otherChoice, traveled.getLastPosition());
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
        }
        return route;
    }

    private int transportCostThrough(SBFMovePath route, int step) {
        return (int) route.getTransportOrders().stream().filter(order -> order.afterSteps() <= step).count();
    }

    private List<SBFFormation> opponentsAt(SBFFormation mover, BoardLocation location) {
        SBFGame game = manager.getGame();
        Player owner = game.getPlayer(mover.getOwnerId());
        return game.getActiveFormationsAt(location).stream()
              .filter(SBFFormation::isDeployed)
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
          BoardLocation contact) {
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
            if (!new SBFContactDamage(manager, dice).resolve(first, second, contact)) {
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
              + other.getId() + ". Use ask to choose after winning, force to pin the opponent, or declare overrun "
              + "or evade while moving through the hex.");
    }

    private SBFFormation rollWinner(SBFFormation first, SBFFormation second, int firstModifier, int secondModifier) {
        first.setForcedOpponent(null);
        second.setForcedOpponent(null);
        int firstTarget = first.getTactics() + firstModifier;
        int secondTarget = second.getTactics() + secondModifier;
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

    private void notifyBoth(SBFFormation first, SBFFormation second, String text) {
        manager.sendServerChat(first.getOwnerId(), text);
        manager.sendServerChat(second.getOwnerId(), text);
    }

    private void setEngaged(SBFFormation first, SBFFormation second, boolean engaged) {
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
                if ((second == null) || (first.getPosition() == null)
                      || !first.getPosition().equals(second.getPosition())) {
                    first.setEngagedWith(opponentId, false);
                } else if (first.getId() < opponentId) {
                    boolean firstContinues = continuesAtEnd(first, second);
                    boolean secondContinues = continuesAtEnd(second, first);
                    boolean keep = firstContinues;
                    if (firstContinues != secondContinues) {
                        SBFFormation winner = rollWinner(first, second, 0, 0);
                        keep = (winner != null) && (winner == first ? firstContinues : secondContinues);
                    }
                    setEngaged(first, second, keep);
                    if (!keep) {
                        orders.decline(first.getId(), second.getId());
                    }
                }
            }
        }
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
        if ((side == null) || (side.getOwnerId() != playerId) || !side.isDeployed()) {
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
        if ((order != Order.AVOID) && (side.isMoraleRestricted() || side.mustWithdraw() || side.wasOverrunThisTurn())) {
            manager.sendServerChat(playerId, "This formation's morale or withdrawal requires it to avoid engagement.");
            return;
        }
        if (orders.exchange() != null) {
            new SBFContactDamage(manager, dice).remind(playerId, orders.exchange());
            return;
        }
        if (pendingPair && orders.isWinnerPair(formationId, opponentId)) {
            if ((formationId != orders.decisionWinner()) || ((order != Order.ENGAGE) && (order != Order.AVOID))) {
                manager.sendServerChat(playerId, "The winner chooses engage or avoid for this contact.");
                return;
            }
            orders.decide(order == Order.ENGAGE);
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
            if ((mover != null) && (game.getTurn() != null) && game.getTurn().isValid(mover.getOwnerId(), mover, game)) {
                orders.clearPendingMove();
                manager.movementProcessor.resumeMovement(pending, mover);
                manager.sendPendingPackets();
            }
        }
    }
}
