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
import java.util.Collections;
import java.util.List;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.actions.EntityAction;
import megamek.common.board.BoardLocation;
import megamek.logging.MMLogger;

public class SBFMovePath implements EntityAction, Serializable {
    private static final MMLogger logger = MMLogger.create(SBFMovePath.class);

    private final int formationId;
    private final List<SBFMoveStep> steps = new ArrayList<>();
    private final BoardLocation startLocation;
    private boolean routeIllegal;
    private boolean overstacked;
    public record TransportOrder(int afterSteps, int passengerId, boolean mount) implements Serializable { }

    private List<TransportOrder> transportOrders;
    private int processedTransportOrders;
    private int spentTransportMP;
    private int mpUsed;
    private int jumpUsed = 0;

    /** A declaration of atmospheric zone movement and its optional ground attack flight path (IO:BF p. 176). */
    public record RadarFlight(List<SBFRadarZone> zones, List<BoardLocation> groundPath, boolean retreat)
          implements Serializable {
        public RadarFlight {
            zones = List.copyOf(zones);
            groundPath = List.copyOf(groundPath);
        }
    }

    private RadarFlight radarFlight;

    public RadarFlight radarFlight() {
        return radarFlight;
    }

    public void setRadarFlight(RadarFlight flight) {
        radarFlight = flight;
    }

    // The game is used mainly during creation of the move path and shouldn't be
    // sent in packets
    private transient SBFGame game;

    public SBFMovePath(int formationId, BoardLocation startLocation, SBFGame game) {
        this.formationId = formationId;
        this.startLocation = startLocation;
        this.game = game;
        game.getFormation(formationId).ifPresent(formation -> overstacked = exceedsStackingLimit(game, formation, startLocation));
    }

    private SBFMovePath(SBFMovePath original) {
        formationId = original.formationId;
        startLocation = original.startLocation;
        game = original.game;
        routeIllegal = original.routeIllegal;
        overstacked = original.overstacked;
        mpUsed = original.mpUsed;
        jumpUsed = original.jumpUsed;
        radarFlight = original.radarFlight;
        transportOrders = original.transportOrders == null ? null : new ArrayList<>(original.transportOrders);
    }

    /**
     * Creates a new move path that is a copy of the given original. Note that the steps are not copied, i.e. the step
     * list is only a shallow copy!
     *
     * @param original The move path to copy
     *
     * @return A new move path that is equal to the original
     */
    public static SBFMovePath createMovePathShallow(SBFMovePath original) {
        SBFMovePath newPath = new SBFMovePath(original);
        newPath.steps.addAll(original.steps);
        return newPath;
    }

    /**
     * Creates a new move path that is a copy of the given original. Note that the steps are copied, i.e. the step list
     * is a deep copy. The returned move path is completely independent of the original.
     *
     * @param original The move path to copy
     *
     * @return A new move path that is equal to the original
     */
    @Deprecated(since = "0.51.0", forRemoval = true)
    public static SBFMovePath createMovePathDeep(SBFMovePath original) {
        SBFMovePath newPath = new SBFMovePath(original);
        newPath.steps.addAll(original.steps.stream().map(SBFMoveStep::copy).toList());
        return newPath;
    }

    @Override
    public int getEntityId() {
        return formationId;
    }

    /** Set only on the rebuilt server path when an immediate combat interrupts the movement. */
    public int processedTransportOrders() {
        return processedTransportOrders;
    }

    public int spentTransportMP() {
        return spentTransportMP;
    }

    public void recordTransportProgress(int processed, int spent) {
        processedTransportOrders = processed;
        spentTransportMP += spent;
    }

    public List<TransportOrder> getTransportOrders() {
        return transportOrders == null ? List.of() : Collections.unmodifiableList(transportOrders);
    }

    public void addTransportOrder(TransportOrder order) {
        if (transportOrders == null) {
            transportOrders = new ArrayList<>();
        }
        transportOrders.add(order);
    }

    public static boolean canOccupyTerrain(SBFGame game, SBFFormation formation, BoardLocation location) {
        Hex hex = game.getHex(location);
        return (hex != null) && SBFTerrainMovement.of(formation).allMatch(mover -> mover.canOccupy(hex, game.usesExpandedWoods()));
    }

    public int getMpUsed() {
        return mpUsed;
    }

    /** The extra paved MP requires an entirely paved turn for every constituent (IO:BF pp. 165–166). */
    boolean earnsRoadBonus() {
        return !steps.isEmpty() && steps.stream().allMatch(step ->
              step instanceof SurfaceSBFMoveStep surface && surface.earnsRoadBonus());
    }

    /** Terrain cost before the minimum movement and paved movement allowances (IO:BF pp. 164–165). */
    int getTerrainCost() {
        return steps.stream().mapToInt(SBFMoveStep::getMpUsed).sum();
    }

    /**
     * Returns the most movement points the formation may use this turn: its Move, or with Sprinting (IO:BF p.199) its
     * Move multiplied by 1.5, rounding up.
     */
    public static int maximumMovementPoints(SBFFormation formation, SBFGame game) {
        int movement = normalMovementPoints(formation, game);
        return game.usesSprintingMove() ? movement + ((movement + 1) / 2) : movement;
    }

    /** Transported Infantry and Extended Mechanized, IO:BF pp. 167, 268. */
    public static int normalMovementPoints(SBFFormation formation, SBFGame game) {
        boolean carriesXmec = game.passengersOf(formation).stream()
              .anyMatch(rider -> rider.isExternallyMounted() && rider.hasSUA(BattleForceSUA.XMEC));
        return Math.max(0, formation.getMovement() - formation.getReconMpSpent()
                - (formation.dismountedIn(game.getCurrentRound()) ? 1 : 0)
              - (carriesXmec ? 1 : 0));
    }

    public void addStep(SBFMoveStep step) {
        steps.add(step);
        compile();
    }

    public SBFMoveStep getLastStep() {
        return steps.get(steps.size() - 1);
    }

    public BoardLocation getLastPosition() {
        return steps.isEmpty() ? startLocation : getLastStep().getLastPosition();
    }

    public boolean isIllegal() {
        return routeIllegal || overstacked;
    }

    /** A friendly stack may be crossed even when the formation cannot stop there (IO:BF p. 164). */
    boolean isTraversable() {
        return !routeIllegal;
    }

    /**
     * Assembles and computes all data for this move path, especially if it is legal.
     */
    private void compile() {
        if (game == null) {
            logger.error("Trying to compile but game is null. Call restore after serialization!");
        }

        SBFFormation formation = game.getFormation(formationId).orElseThrow();

        int move = normalMovementPoints(formation, game);
        mpUsed = getTerrainCost();
        if ((steps.size() == 1) && (move > 0)) {
            // Minimum Movement (IO:BF p. 164) allows one otherwise legal hex on the formation's normal Move.
            mpUsed = Math.min(mpUsed, move);
        } else if ((move > 0) && (mpUsed > move) && earnsRoadBonus()) {
            mpUsed--;
        }
        routeIllegal = steps.stream().anyMatch(SBFMoveStep::isIllegal)
              || (mpUsed > maximumMovementPoints(formation, game));

        // Opposed movement is resolved by the server. Even transit must respect capacity in a hostile hex (p. 164).
        for (SBFMoveStep step : steps) {
            if (game.isHostileActiveFormationAt(step.destination, formation)
                  && exceedsStackingLimit(game, formation, step.destination)) {
                routeIllegal = true;
            }
        }
        routeIllegal |= !steps.isEmpty() && !formation.getEngagedOpponents().isEmpty();

        overstacked = exceedsStackingLimit(game, formation, getLastPosition());
    }

    /** Stacking (IO:BF p. 164): one formation of any type plus one infantry formation per side. */
    public static boolean exceedsStackingLimit(SBFGame game, SBFFormation formation, BoardLocation destination) {
        Player owner = game.getPlayer(formation.getOwnerId());
        List<SBFFormation> others = game.getActiveFormationsAt(destination).stream()
              .filter(SBFFormation::isDeployed)
              .filter(f -> (f.getId() != formation.getId()) && !game.areHostile(f, owner))
              .toList();
        return exceedsStackingLimit(formation, others);
    }

    public static boolean exceedsStackingLimit(SBFFormation formation, List<SBFFormation> others) {
        return (others.size() >= 2) || ((others.size() == 1)
              && !formation.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA)
              && !others.getFirst().isAnyTypeOf(SBFElementType.CI, SBFElementType.BA));
    }

    /**
     * Restores the move path after serialization. This is unnecessary unless the {@link #compile()} method is used.
     *
     * @param game The SBFGame
     */
    public void restore(SBFGame game) {
        this.game = game;
    }

    /**
     * Returns the number of hexes moved
     */
    public int getHexesMoved() {
        return startLocation.coords().distance(getLastPosition().coords());
    }

    @Override
    public String toString() {
        return "[SBFMovePath]: ID: " + formationId + "; steps: " + steps;
    }

    /**
     * @return The steps of this move path as an unmodifiable list.
     */
    public List<SBFMoveStep> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean isEndStep(SBFMoveStep step) {
        return (step != null) && steps.contains(step) && step.destination.equals(getLastPosition());
    }

    /**
     * Returns the number of mp used up to and including the given step. Returns -1 if the step is not part of this move
     * path.
     *
     * @param step The last step to include in the cost
     *
     * @return The total mp up to the given step
     */
    public int getMpUpTo(SBFMoveStep step) {
        if (!steps.isEmpty() && (steps.getLast() == step)) {
            return getMpUsed();
        }
        if (steps.contains(step)) {
            int mpUsed = 0;
            for (SBFMoveStep step2 : steps) {
                mpUsed += step2.getMpUsed();
                if (step.equals(step2)) {
                    return mpUsed;
                }
            }
        }
        logger.error("Tried to find the mp used with a step that is not part of this move path!");
        return -1;
    }

    public void setJumpUsed(int jumpUsed) {
        this.jumpUsed = jumpUsed;
    }

    public int getJumpUsed() {
        return jumpUsed;
    }
}
