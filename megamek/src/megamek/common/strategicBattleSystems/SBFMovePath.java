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
    private Integer boardingCarrier;
    private boolean enterHullDown;
    private boolean enterBuildings;

    public boolean entersHullDown() {
        return enterHullDown;
    }

    public void enterHullDownAtEnd() {
        enterHullDown = true;
    }

    /** Urban Hexes, IO:BF p. 217: the Formation moves into the buildings of the urban hex where this route ends. */
    public boolean entersBuildings() {
        return enterBuildings;
    }

    public void enterBuildingsAtEnd() {
        enterBuildings = true;
    }


    public Integer boardingCarrier() {
        return boardingCarrier;
    }

    public void boardCarrierAtEnd(int id) {
        boardingCarrier = id;
    }

    // Terrain checks belong to the authoritative rebuilt route, including across a paused engagement.
    private int terrainChecksCompleted;
    private Integer stoppedByTerrainAt;

    public boolean needsTerrainCheck(int step) {
        return step >= terrainChecksCompleted;
    }

    public void completeTerrainCheck(int step, boolean stopped) {
        terrainChecksCompleted = step + 1;
        if (stopped) {
            stoppedByTerrainAt = step;
        }
    }

    public boolean stoppedByTerrainAt(int step) {
        return stoppedByTerrainAt != null && stoppedByTerrainAt == step;
    }

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
        game.getFormation(formationId).ifPresent(formation -> {
            overstacked = exceedsStackingLimit(game, formation, startLocation);
            mpUsed = (formation.getMovement() > 0) && formation.usesWiGE() ? 1 : 0;
        });
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
        boardingCarrier = original.boardingCarrier;
        enterHullDown = original.enterHullDown;
        enterBuildings = original.enterBuildings;
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
        return (hex != null) && SBFTerrainMovement.of(formation).allMatch(mover -> mover.canOccupy(hex, game));
    }

    public static boolean canOccupyTerrain(SBFGame game, SBFFormation formation, SBFUnit unit, BoardLocation site) {
        Hex hex = game.getHex(site);
        return hex != null && SBFTerrainMovement.of(formation, unit).allMatch(mover -> mover.canOccupy(hex, game));
    }

    public int getMpUsed() {
        return mpUsed;
    }

    public static int roadBonus(SBFGame game) {
        return game.usesAdvancedTerrain() ? 2 : 1;
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
        return Math.max(0, formation.getMovement() - formation.getReconMpSpent() - (formation.slowedByInferno(game.getCurrentRound()) ? 1 : 0)
                - formation.dismountCost(game.getCurrentRound()) - formation.reformMovementPenalty(game.getCurrentRound())
              - (carriesXmec ? 1 : 0) - (formation.ambushedIn(game.getCurrentRound()) ? 2 : 0));
    }

    /** A newly revealed hostile Formation changes the next departure's MP, even on a route already submitted. */
    public void refreshDeparture(int index, BoardLocation current) {
        if (steps.get(index) instanceof SurfaceSBFMoveStep step
              && step.hadHostileDeparture() != game.isHostileActiveFormationAt(current, game.getFormation(formationId).orElseThrow())) {
            int entrance = passageEntranceBefore(index);
            steps.set(index, step.fromPassage(game, entrance));
            compile();
        }
    }

    private int passageEntranceBefore(int index) {
        if (index == 0) {
            return game.getFormation(formationId).map(SBFFormation::passageEntrance).orElse(-1);
        }
        return steps.get(index - 1) instanceof SurfaceSBFMoveStep surface ? surface.arrivalSide() : -1;
    }

    public int passageEntrance() {
        return passageEntranceBefore(steps.size());
    }

    public void addStep(SBFMoveStep step) {
        int entrance = passageEntrance();
        steps.add(entrance >= 0 && step instanceof SurfaceSBFMoveStep surface ? surface.fromPassage(game, entrance) : step);
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
        if (steps.isEmpty() && (formation.getMovement() > 0) && formation.usesWiGE()) {
            mpUsed = 1; // A stationary WiGE still expends 1 MP to remain airborne (IO:BF p. 166).
        }
        if ((steps.size() == 1) && (move > 0)) {
            // Minimum Movement (IO:BF p. 164) allows one otherwise legal hex on the formation's normal Move.
            mpUsed = Math.min(mpUsed, move);
        } else if ((move > 0) && (mpUsed > move) && earnsRoadBonus()) {
            mpUsed = Math.max(move, mpUsed - roadBonus(game));
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
        if (!others.isEmpty() && game.getOptions().booleanOption(megamek.common.options.SBFRuleOptions.BASE_ADJUST_FORMATIONS)) {
            var family = game.originalFamily(formation);
            // IO:BF p. 198. Count the declared arrivals as the single counter they will form in the End Phase.
            if (destination.equals(formation.reunionIn(game.getCurrentRound()))) {
                others = others.stream().filter(f -> !family.contains(f)
                      || !destination.equals(f.reunionIn(game.getCurrentRound()))).toList();
            }
        }
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
