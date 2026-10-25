/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;

import megamek.logging.MMLogger;

/**
 * Holds playback at the firing results' burst ({@link FiringBurst}) until all of it is queued, then lets it through as
 * attack runs ({@link AttackRunAssembler}). It never holds for long: after {@link #TIMEOUT_SECONDS} without a close
 * marker the burst plays as it came.
 */
final class FiringBurstGate {
    private static final MMLogger LOGGER = MMLogger.create(FiringBurstGate.class);
    /** Real seconds to wait for a burst's close before playing it unassembled. */
    static final double TIMEOUT_SECONDS = 8;

    private double waited;

    /**
     * Called with a {@link FiringBurst} at the head of the queue.
     *
     * @param pending the playback queue
     * @param seconds the real seconds since the last call
     *
     * @return {@code true} if the marker is dealt with and playback may go on; {@code false} to wait
     */
    boolean letThrough(ArrayDeque<BoardScene.Animation> pending, double seconds) {
        if (!(pending.peekFirst() instanceof FiringBurst burst) || !burst.open()) {
            // A close marker without its open (the burst was played already, or cleared): nothing to wait for.
            pending.removeFirst();
            return true;
        }
        if (AttackRunAssembler.assemble(pending)) {
            waited = 0;
            return true;
        }
        waited += Math.max(0, seconds);
        if (waited < TIMEOUT_SECONDS) {
            return false;
        }
        LOGGER.info("[AirRun] the firing results never closed their burst; playing them as they came");
        pending.removeFirst();
        waited = 0;
        return true;
    }

    /** Forgets any wait: the queue was cleared. */
    void reset() {
        waited = 0;
    }
}
