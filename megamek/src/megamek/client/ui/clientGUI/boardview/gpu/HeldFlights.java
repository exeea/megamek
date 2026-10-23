/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import megamek.common.annotations.Nullable;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.logging.MMLogger;

/**
 * Holds back the moves of aircraft over a ground map. An aircraft moves in the Movement phase but its attacks resolve
 * along its flight path at the end of the Firing phase, so its model waits at the start of its move until the firing
 * results begin, and then flies the whole path ahead of the ground units' attacks. Until then a faded copy marks its
 * destination, which is its game position for every rule, click and panel.
 *
 * <p>Only the Swing side uses this; it hands the GL side an immutable {@link BoardAirspace} each frame. Every
 * decision is logged with {@code [AirMove]}, including why a move was not held.</p>
 */
final class HeldFlights {
    private static final MMLogger LOGGER = MMLogger.create(HeldFlights.class);

    /** The phases from an aircraft's move up to its firing results: its model waits through all of them. */
    private static final Set<GamePhase> WAITING_PHASES = EnumSet.of(GamePhase.MOVEMENT, GamePhase.MOVEMENT_REPORT,
          GamePhase.OFFBOARD, GamePhase.OFFBOARD_REPORT, GamePhase.PRE_FIRING, GamePhase.FIRING);

    private final Map<Integer, HeldFlight> held = new LinkedHashMap<>();
    /** Units whose held move was already played or dropped this round, so a refresh never holds them again. */
    private final Set<Integer> finishedThisRound = new HashSet<>();
    private int round = Integer.MIN_VALUE;
    /** Whether the firing results' burst is open: held moves were released and the phase is still Firing. */
    private boolean burstOpen;

    /**
     * Holds an aircraft's move instead of playing it now.
     *
     * @param entity the moving unit, as the client now has it
     * @param phase  the current phase
     * @param flight the move to hold
     * @param origin where the move came from, for the log: a move packet or a rebuild from the flight path
     *
     * @return {@code true} if the move is held; {@code false} if it should play now as any other move
     */
    boolean hold(Entity entity, GamePhase phase, HeldFlight flight, String origin) {
        startRound(flight.round());
        String refusal = refusal(entity, phase, flight);
        if (refusal != null) {
            LOGGER.info("[AirMove] {} plays now ({}): {}", entity.getShortName(), origin, refusal);
            return false;
        }
        held.put(entity.getId(), flight);
        LOGGER.info("[AirMove] hold {} ({}): {} to {} over {} points", entity.getShortName(), origin,
              flight.start().coords().getBoardNum(), flight.destination().coords().getBoardNum(), flight.path().size());
        return true;
    }

    /**
     * Whether an aircraft that is not held should be held from the flight path it carries: a game loaded, or a client
     * connected, after it moved this round.
     *
     * @param entity the unit
     * @param phase  the current phase
     * @param round  the current round
     */
    boolean needsRebuild(Entity entity, GamePhase phase, int round) {
        startRound(round);
        return !held.containsKey(entity.getId())
              && !finishedThisRound.contains(entity.getId())
              && WAITING_PHASES.contains(phase)
              && entity.isAirborneAeroOnGroundMap()
              && (entity.moved != EntityMovementType.MOVE_NONE)
              && (entity.getPassedThrough().size() >= 2)
              && (entity.getPassedThroughBoardId() == entity.getBoardId());
    }

    /**
     * This frame's housekeeping: plays the held moves whose wait ended without firing results, drops those whose unit
     * is gone, hidden, or no longer airborne over this board, and holds again, from the flight path they carry, moves
     * made before this board saw them (a loaded game or a reconnect).
     *
     * @param game    the game
     * @param boardId the board shown
     * @param shown   whether the board shows a unit: visible and not just a sensor contact
     * @param rebuild a held move from the flight path a unit carries, for a round
     *
     * @return the moves to play now
     */
    List<HeldFlight> update(Game game, int boardId, Predicate<Entity> shown, Function<Entity, HeldFlight> rebuild) {
        GamePhase phase = game.getPhase();
        int round = game.getRoundCount();
        List<HeldFlight> finished = releaseFinished(phase, round);
        for (int id : heldIds()) {
            Entity entity = game.getEntity(id);
            if (entity == null) {
                drop(id, "removed");
            } else if (!shown.test(entity) || !entity.isAirborneAeroOnGroundMap() || (entity.getBoardId() != boardId)) {
                drop(id, "hidden, a sensor contact, or no longer airborne over this board");
            }
        }
        for (Entity entity : game.getEntitiesVector()) {
            if (entity.isAero() && (entity.getBoardId() == boardId) && shown.test(entity)
                  && needsRebuild(entity, phase, round)) {
                hold(entity, phase, rebuild.apply(entity), "rebuilt from its flight path");
            }
        }
        return finished;
    }

    /**
     * Opens the firing results' burst for the moves just released, so playback waits for every result and then plays
     * each aircraft's move and shots as one attack run.
     *
     * @param released the moves {@link #releaseForFiringResults()} released; not empty
     * @param boardId  the board
     * @param drop     how many levels below its held move's end each aircraft is now: its attacks' altitude loss
     *
     * @return the open marker to queue ahead of the released moves
     */
    FiringBurst openBurst(List<HeldFlight> released, int boardId, Function<HeldFlight, Float> drop) {
        Set<Integer> aircraft = new HashSet<>();
        Map<Integer, Float> drops = new HashMap<>();
        for (HeldFlight flight : released) {
            aircraft.add(flight.entityId());
            float levels = drop.apply(flight);
            if (levels > 0) {
                drops.put(flight.entityId(), levels);
            }
        }
        burstOpen = true;
        LOGGER.info("[AirMove] the firing results open with {} aircraft run(s); altitude lost: {}", aircraft.size(),
              drops);
        return new FiringBurst(true, aircraft, drops, boardId);
    }

    /**
     * Closes the firing results' burst once the phase has left Firing: every result is queued by then.
     *
     * @param phase   the current phase
     * @param boardId the board
     *
     * @return the close marker to queue, or {@code null} while the burst stays open or none is open
     */
    @Nullable FiringBurst closeBurst(GamePhase phase, int boardId) {
        if (!burstOpen || phase.isFiring()) {
            return null;
        }
        burstOpen = false;
        LOGGER.info("[AirMove] the firing results end in the {} phase", phase);
        return new FiringBurst(false, Set.of(), boardId);
    }

    /**
     * Releases every held move once the firing results begin, so they play first, in the order they were made.
     *
     * @return the moves to play now
     */
    List<HeldFlight> releaseForFiringResults() {
        return releaseAll("the firing results begin");
    }

    /**
     * Releases held moves whose wait is over without any firing results: the phase moved past Firing (to play them)
     * or a new round began (to drop them, as their moment has passed).
     *
     * @param phase the current phase
     * @param round the current round
     *
     * @return the moves to play now
     */
    List<HeldFlight> releaseFinished(GamePhase phase, int round) {
        if (round != this.round) {
            startRound(round);
            return List.of();
        }
        if (!held.isEmpty() && !WAITING_PHASES.contains(phase)) {
            return releaseAll("the " + phase + " phase began with no firing results");
        }
        return List.of();
    }

    /**
     * Drops a unit's held move without playing it: the unit was removed, was hidden, or is no longer an airborne
     * aircraft over a ground map.
     */
    void drop(int entityId, String reason) {
        HeldFlight flight = held.remove(entityId);
        if (flight != null) {
            finishedThisRound.add(entityId);
            LOGGER.info("[AirMove] drop unit {} without playing it: {}", entityId, reason);
        }
    }

    /** Drops every held move: the board or the view changed. */
    void dropAll(String reason) {
        if (!held.isEmpty()) {
            LOGGER.info("[AirMove] drop {} held move(s) without playing them: {}", held.size(), reason);
            finishedThisRound.addAll(held.keySet());
            held.clear();
        }
    }

    /** @return the held move of this unit, or {@code null} */
    @Nullable HeldFlight flight(int entityId) {
        return held.get(entityId);
    }

    /** @return the units whose moves are held now */
    Set<Integer> heldIds() {
        return Set.copyOf(held.keySet());
    }

    /** @return what the board draws this frame */
    BoardAirspace snapshot() {
        return held.isEmpty() ? BoardAirspace.EMPTY : new BoardAirspace(new ArrayList<>(held.values()));
    }

    private List<HeldFlight> releaseAll(String reason) {
        if (held.isEmpty()) {
            return List.of();
        }
        List<HeldFlight> released = new ArrayList<>(held.values());
        LOGGER.info("[AirMove] release {} held move(s) to play: {}", released.size(), reason);
        finishedThisRound.addAll(held.keySet());
        held.clear();
        return released;
    }

    /** A new round drops the last round's held moves unplayed: their moment has passed. */
    private void startRound(int round) {
        if (round != this.round) {
            dropAll("a new round began");
            this.round = round;
            finishedThisRound.clear();
        }
    }

    /** @return why this move should play now, or {@code null} if it is held */
    private @Nullable String refusal(Entity entity, GamePhase phase, HeldFlight flight) {
        if (!entity.isAirborneAeroOnGroundMap()) {
            return "not an airborne aircraft over a ground map";
        }
        if (!WAITING_PHASES.contains(phase)) {
            return "the " + phase + " phase does not wait for firing results";
        }
        if (flight.path().size() < 2) {
            return "no path to fly";
        }
        if (!flight.startKnown()) {
            return "the player did not see where it started";
        }
        if (finishedThisRound.contains(entity.getId())) {
            return "its held move already played this round";
        }
        return null;
    }
}
