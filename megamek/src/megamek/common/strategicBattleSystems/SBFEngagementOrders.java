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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Player;

/** Private orders and unresolved movement contacts, retained in SBF saves (IO:BF pp. 167, 172). */
public class SBFEngagementOrders implements Serializable {
    public record EndPair(int first, int second) implements Serializable {
        public boolean matches(int a, int b) {
            return ((first == a) && (second == b)) || ((first == b) && (second == a));
        }
    }

    private List<EndPair> remainingEnds = new ArrayList<>();
    private EndPair selectedEnd;

    public List<EndPair> remainingEnds() {
        if (remainingEnds == null) {
            remainingEnds = new ArrayList<>();
        }
        return remainingEnds;
    }

    public EndPair selectedEnd() {
        return selectedEnd;
    }

    public void selectEnd(EndPair selected) {
        selectedEnd = selected;
    }

    /** A negative targetUnit awaits the Tactics winner's allocation. */
    public record ContactHit(int attacker, int target, int damage, int chooser, int targetUnit) implements Serializable { }

    /** A free shot uses -2 for a pending shooter, -1 for a declined shot. Null hits await preparation. */
    public record Exchange(int mover, int opponent, Order order, boolean succeeded, int shooter,
          List<ContactHit> hits) implements Serializable {
        public Exchange withShooter(int selected) {
            return new Exchange(mover, opponent, order, succeeded, selected, hits);
        }

        public Exchange withHits(List<ContactHit> prepared) {
            return new Exchange(mover, opponent, order, succeeded, shooter, List.copyOf(prepared));
        }
    }

    private Exchange exchange;

    public Exchange exchange() {
        return exchange;
    }

    public void setExchange(Exchange exchange) {
        this.exchange = exchange;
    }

    public enum AirResponse { RETURN, RESERVE, PASS }

    /** Defender's election is made when a new air engagement is initiated (IO:BF p. 177). */
    public record AirDefense(int attacker, int defender, AirResponse response) implements Serializable { }

    private AirDefense airDefense;

    public AirDefense airDefense() {
        return airDefense;
    }

    public void setAirDefense(AirDefense defense) {
        airDefense = defense;
    }

    public enum Order {
        ENGAGE, AVOID, ASK, FORCE, OVERRUN, EVADE, AMBUSH;

        public boolean seeksEngagement() {
            return (this == ENGAGE) || (this == FORCE) || (this == ASK);
        }
    }

    private final Map<Integer, Map<Integer, Order>> orders = new HashMap<>();
    private int decisionWinner = Player.PLAYER_NONE;
    private int decisionOpponent = Player.PLAYER_NONE;
    private Boolean decision;
    private final Set<Long> declinedPairs = new HashSet<>();
    private int round = -1;
    private int lastResolvedStep;

    public void recordResolvedStep(int step) {
        lastResolvedStep = Math.max(lastResolvedStep, step);
    }

    public int lastResolvedStep() {
        return lastResolvedStep;
    }

    public enum Ambush { SHORT, MEDIUM, HOLD }

    // At most one mover is active. Keep entry decisions until its route has passed this hex.
    private Set<Integer> resolvedAmbushes;
    private int ambushStep;

    public Set<Integer> resolvedAmbushes(int step) {
        if (resolvedAmbushes == null || step != ambushStep) {
            resolvedAmbushes = new HashSet<>();
            ambushStep = step;
        }
        return resolvedAmbushes;
    }

    private int lastHiddenEntry;

    public boolean alreadyVisited(int step) {
        return step <= lastHiddenEntry;
    }

    public void finishEntry(int step) {
        lastHiddenEntry = step;
    }

    public void finishMovement() {
        resolvedAmbushes = null;
        lastHiddenEntry = 0;
        lastResolvedStep = 0;
        exchange = null;
        airDefense = null;
        clearPendingMove();
        clearDecision();
    }
    private SBFMovePath pendingMove;
    private int contactId = Player.PLAYER_NONE;

    public void order(int formationId, int opponentId, boolean engage) {
        order(formationId, opponentId, engage ? Order.ENGAGE : Order.AVOID);
    }

    public void order(int formationId, int opponentId, Order order) {
        orders.computeIfAbsent(formationId, unused -> new HashMap<>()).put(opponentId, order);
    }

    public Order intent(int formationId, int opponentId) {
        Map<Integer, Order> instructions = orders.getOrDefault(formationId, Map.of());
        return instructions.getOrDefault(opponentId, instructions.get(Player.PLAYER_NONE));
    }

    public void startRound(int currentRound) {
        if (round != currentRound) {
            declinedPairs.clear();
            finishMovement();
            round = currentRound;
        }
    }

    public boolean alreadyDeclined(int a, int b) {
        return declinedPairs.contains(pairKey(a, b));
    }

    public void decline(int a, int b) {
        declinedPairs.add(pairKey(a, b));
    }

    private long pairKey(int a, int b) {
        return ((long) Math.min(a, b) << 32) | Integer.toUnsignedLong(Math.max(a, b));
    }

    public void awaitOrders(SBFMovePath path, int opponentId) {
        pendingMove = path;
        contactId = opponentId;
    }

    public SBFMovePath pendingMove() {
        return pendingMove;
    }

    public int contactId() {
        return contactId;
    }

    public void awaitWinner(int winnerId, int opponentId) {
        decisionWinner = winnerId;
        decisionOpponent = opponentId;
        decision = null;
    }

    public int decisionWinner() {
        return decisionWinner;
    }

    public boolean isWinnerPair(int a, int b) {
        return ((decisionWinner == a) && (decisionOpponent == b))
              || ((decisionWinner == b) && (decisionOpponent == a));
    }

    public void decide(boolean engage) {
        decision = engage;
    }

    public Boolean takeDecision() {
        Boolean chosen = decision;
        if (chosen != null) {
            clearDecision();
        }
        return chosen;
    }

    public void clearDecision() {
        decisionWinner = Player.PLAYER_NONE;
        decisionOpponent = Player.PLAYER_NONE;
        decision = null;
    }

    public void clearPendingMove() {
        pendingMove = null;
        contactId = Player.PLAYER_NONE;
    }
}
