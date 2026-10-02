/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import megamek.common.annotations.Nullable;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.logging.MMLogger;

/**
 * Puts one unit of a game into a hypothetical state for the length of a try-with-resources block, so unchanged rules
 * code sees it there. Writes the unit's fields and location exposure directly (no events, no rule guards, no setter
 * cascades), keeps the game's position lookup in step, and close() restores all of them. Only FirePreview uses it.
 * Owning thread only (the Swing EDT for a client game); never on a thread whose game other threads read (never a bot's
 * calculation thread).
 */
final class HypotheticalState implements AutoCloseable {
    private static final MMLogger logger = MMLogger.create(HypotheticalState.class);

    /** Units whose pose setters maintain no derived state the raw writes would skip. A new subclass fails closed. */
    static final Set<Class<? extends Entity>> SUPPORTED = Set.of(BipedMek.class, QuadMek.class, TripodMek.class,
          Tank.class, SupportTank.class, SuperHeavyTank.class, ConvInfantry.class, BattleArmor.class, ProtoMek.class);

    /** Twist rotations from the facing, in the order FiringDisplay's twist commands reach them. */
    private static final int[] ROTATIONS = { 0, -1, 1, -2, 2, 3 };

    private final Entity unit;
    private final Thread owner = Thread.currentThread();
    private final Entity.MovementState saved;
    private final BoardLocation savedMemo;
    private final int[] savedExposure;
    private final int forward;
    private Entity.MovementState lastWritten;
    private int[] lastExposure;
    private boolean closed;

    private HypotheticalState(Entity unit, Entity.MovementState applied, int forward) {
        this.unit = unit;
        this.forward = forward;
        saved = unit.movementState();
        savedMemo = unit.boardLocationCache();
        savedExposure = exposure(unit);
        lastExposure = savedExposure;
        write(applied);
    }

    /** Why this unit cannot be put into a hypothetical position, or null. */
    static @Nullable FirePreview.Reason refusal(Entity unit) {
        if (!SUPPORTED.contains(unit.getClass()) || unit.getMovementMode().isVTOL()
              || unit.getMovementMode().isWiGE()) {
            return FirePreview.Reason.UNSUPPORTED_UNIT;
        }
        Game game = unit.getGame();
        if ((game == null) || (unit.getPosition() == null)) {
            return FirePreview.Reason.UNSUPPORTED_STATE;
        }
        Map<Integer, Coords> secondaryPositions = unit.getSecondaryPositions();
        if (((secondaryPositions != null) && !secondaryPositions.isEmpty()) || game.useVectorMove()) {
            return FirePreview.Reason.UNSUPPORTED_UNIT;
        }
        // The lookup is built lazily on first use; an out-of-sync lookup could not be restored exactly.
        game.getEntitiesVector(unit.getPosition(), true);
        if (!game.getEntityPositions(unit).equals(unit.getOccupiedCoords())) {
            return FirePreview.Reason.UNSUPPORTED_STATE;
        }
        return null;
    }

    /**
     * The mover at the end of a plan, torso or turret forward. The posture follows the side effects of the setters
     * the server calls (going prone is voluntary; standing up and going hull-down clear the prone cause and fall side).
     * The location exposure is the one MovePathHandler's last update of a move sets: the end hex at the end elevation,
     * never as a jump, so a unit that lands a jump in water gets wet too. It rolls nothing; a move whose water breaches
     * a location is refused by FirePreview.unsupportedReason.
     *
     * @throws IllegalStateException if {@link #refusal(Entity)} refuses the mover
     */
    static HypotheticalState atEndOf(Entity mover, FirePreview.End end) {
        FirePreview.Reason refused = refusal(mover);
        if (refused != null) {
            throw new IllegalStateException(mover.getDisplayName() + " cannot be previewed: " + refused);
        }
        Entity.MovementState live = mover.movementState();
        int forward = mover.secondaryFacingAfterMove(end.facing());
        ProneCause proneCause = live.proneCause();
        FallSide fallSide = live.fallSide();
        if (end.prone() && !live.prone()) {
            proneCause = ProneCause.VOLUNTARY;
            fallSide = null;
        } else if ((!end.prone() && live.prone()) || (end.hullDown() && !live.hullDown())) {
            proneCause = ProneCause.NONE;
            fallSide = null;
        }
        HypotheticalState scope = new HypotheticalState(mover, new Entity.MovementState(end.position(), end.boardId(),
              end.facing(), forward, end.elevation(), end.prone(), proneCause, fallSide, end.hullDown(), end.evading(),
              end.braceLocation(), end.moved(), end.hexesMoved(), end.mpUsed()), forward);
        try {
            Game game = mover.getGame();
            WaterExposure.apply(mover, game.getBoard(end.boardId()).getHex(end.position()), false, end.elevation(),
                  game.getPlanetaryConditions());
            scope.lastExposure = exposure(mover);
        } catch (RuntimeException error) {
            scope.close();
            throw error;
        }
        return scope;
    }

    /** A unit where it stands with its secondary facing forward (as after its own move); any unit kind. */
    static HypotheticalState inPlace(Entity unit) {
        int forward = unit.secondaryFacingAfterMove(unit.getFacing());
        return new HypotheticalState(unit, unit.movementState().withSecondaryFacing(forward), forward);
    }

    int forwardSecondaryFacing() {
        checkOwner();
        return forward;
    }

    /**
     * The secondary facings the unit may choose in the applied state, forward first: when it may twist now, every
     * valid direction by rotation from its facing (0, -1, +1, -2, +2, 3); otherwise only forward.
     */
    List<Integer> legalSecondaryFacings() {
        checkOwner();
        if (!unit.canTwistNow()) {
            return List.of(forward);
        }
        List<Integer> result = new ArrayList<>();
        for (int rotation : ROTATIONS) {
            int direction = (unit.getFacing() + rotation + 6) % 6;
            if (unit.isValidSecondaryFacing(direction)) {
                result.add(direction);
            }
        }
        return List.copyOf(result);
    }

    /** @throws IllegalArgumentException if the direction is not one of {@link #legalSecondaryFacings()} */
    void setSecondaryFacing(int secondaryFacing) {
        if (!legalSecondaryFacings().contains(secondaryFacing)) {
            throw new IllegalArgumentException("Not a legal secondary facing: " + secondaryFacing);
        }
        write(lastWritten.withSecondaryFacing(secondaryFacing));
    }

    /**
     * Restores the saved state, exposure, memo and position lookup; logs an error if something else changed the unit.
     */
    @Override
    public void close() {
        checkOwner();
        if (closed) {
            return;
        }
        closed = true;
        Entity.MovementState current = unit.movementState();
        int[] currentExposure = exposure(unit);
        if (!current.equals(lastWritten) || !Arrays.equals(currentExposure, lastExposure)) {
            logger.error("{} changed inside a hypothetical state: expected {} {} but found {} {}",
                  unit.getDisplayName(), lastWritten, Arrays.toString(lastExposure), current,
                  Arrays.toString(currentExposure));
        }
        HashSet<Coords> during = unit.getOccupiedCoords();
        unit.writeMovementState(saved);
        for (int location = 0; location < savedExposure.length; location++) {
            unit.setLocationStatus(location, savedExposure[location], true);
        }
        unit.restoreBoardLocationCache(savedMemo);
        updateLookup(during);
    }

    /** The exposure status of every location (wet, breached, or what the air gives it). */
    private static int[] exposure(Entity unit) {
        int[] result = new int[unit.locations()];
        for (int location = 0; location < result.length; location++) {
            result[location] = unit.getLocationStatus(location);
        }
        return result;
    }

    private void write(Entity.MovementState state) {
        checkOwner();
        if (closed) {
            throw new IllegalStateException("Hypothetical state already closed");
        }
        HashSet<Coords> before = unit.getOccupiedCoords();
        unit.writeMovementState(state);
        lastWritten = state;
        updateLookup(before);
    }

    private void updateLookup(HashSet<Coords> before) {
        Game game = unit.getGame();
        if ((game != null) && !Objects.equals(before, unit.getOccupiedCoords())) {
            game.updateEntityPositionLookup(unit, before);
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("A hypothetical state may only be used on the thread that created it");
        }
    }
}
