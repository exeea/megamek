/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import megamek.common.ResolvedAttack;
import megamek.logging.MMLogger;

/**
 * Turns the firing results' burst into attack runs. Between the burst's open and close markers ({@link FiringBurst}) it
 * takes each released aircraft's move and that aircraft's own shots, and puts one {@link AttackRun} for each aircraft at
 * the front, in the order the moves were made. Everything else keeps its order: the ground units' attacks, the deaths
 * and the scene checkpoints. Each checkpoint already holds every earlier result, so leaving them in place means no ground
 * result shows before it plays; a run's damage shows on the target when the checkpoint after it applies.
 */
final class AttackRunAssembler {
    private static final MMLogger LOGGER = MMLogger.create(AttackRunAssembler.class);

    private AttackRunAssembler() {
    }

    /**
     * Assembles the burst at the head of the queue, once its close marker is queued.
     *
     * @param pending the playback queue, with an open {@link FiringBurst} at its head
     *
     * @return {@code true} if the burst was assembled (its markers are gone); {@code false} if its close marker is not
     *       queued yet, and the queue is unchanged
     */
    static boolean assemble(ArrayDeque<BoardScene.Animation> pending) {
        List<BoardScene.Animation> items = new ArrayList<>(pending);
        if (items.isEmpty() || !(items.getFirst() instanceof FiringBurst open) || !open.open()) {
            return false;
        }
        int close = -1;
        for (int index = 1; index < items.size(); index++) {
            if (items.get(index) instanceof FiringBurst marker && !marker.open()) {
                close = index;
                break;
            }
        }
        if (close < 0) {
            return false;
        }
        List<BoardScene.Animation> burst = new ArrayList<>(items.subList(1, close));
        List<BoardScene.Animation> rest = new ArrayList<>(burst);
        List<BoardScene.Animation> runs = new ArrayList<>();
        for (BoardScene.Animation event : burst) {
            if (event instanceof BoardScene.Movement flight && open.aircraft().contains(flight.entityId())) {
                AttackRun run = run(flight, burst, open.drop(flight.entityId()));
                if (run != null) {
                    rest.remove(flight);
                    rest.removeAll(run.shots());
                    runs.add(run);
                }
            }
        }
        pending.clear();
        pending.addAll(runs);
        pending.addAll(rest);
        pending.addAll(items.subList(close + 1, items.size()));
        return true;
    }

    /** @return this aircraft's run, or {@code null} if its move cannot be one */
    private static AttackRun run(BoardScene.Movement flight, List<BoardScene.Animation> burst, float drop) {
        int moves = 0;
        List<BoardScene.Combat> shots = new ArrayList<>();
        for (BoardScene.Animation event : burst) {
            if (event instanceof BoardScene.Movement movement && movement.entityId() == flight.entityId()) {
                moves++;
            } else if (event instanceof BoardScene.Combat combat && isOwnShot(combat, flight.entityId())) {
                shots.add(combat);
            }
        }
        if (moves > 1) {
            LOGGER.info("[AirRun] unit {} plays its move and shots apart: its move is split in {} parts (a change of form)",
                  flight.entityId(), moves);
            return null;
        }
        LOGGER.info("[AirRun] unit {}: an attack run over {} path points with {} shot(s)", flight.entityId(),
              flight.path().size(), shots.size());
        return new AttackRun(RollIn.shape(flight, shots, drop), shots);
    }

    /** A shot this aircraft fired at something, not its point defense answering another unit's missiles. */
    private static boolean isOwnShot(BoardScene.Combat combat, int aircraftId) {
        ResolvedAttack result = combat.result();
        boolean defensive = (result.shot() != null) && result.shot().defensive();
        return (combat.entityId() == aircraftId) && (result.kind() == ResolvedAttack.Kind.SHOT) && !defensive;
    }
}
