/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Map;
import java.util.Set;

/**
 * Marks the firing results' burst in the playback queue. The open marker goes in when the first firing result releases
 * held aircraft moves; the close marker when the phase leaves Firing, after the last result. Playback waits at an open
 * marker until its close is queued, then turns each released aircraft's move and its shots into an {@link AttackRun}.
 * Markers sit in packet order, so no result can slip past them.
 *
 * @param open     whether this opens the burst; a close marker carries no aircraft
 * @param aircraft the aircraft whose held moves the burst released
 * @param drops    for each released aircraft, the levels its attacks cost it (a strike 1, a dive bomb 2): how far below
 *                 its held move's end it is now; missing means none
 * @param boardId  the board
 */
record FiringBurst(boolean open, Set<Integer> aircraft, Map<Integer, Float> drops, int boardId)
      implements BoardScene.Animation {

    FiringBurst {
        aircraft = Set.copyOf(aircraft);
        drops = Map.copyOf(drops);
    }

    /** A burst marker whose aircraft lost no altitude. */
    FiringBurst(boolean open, Set<Integer> aircraft, int boardId) {
        this(open, aircraft, Map.of(), boardId);
    }

    /** @return the levels this aircraft's attacks cost it, none if it lost no altitude */
    float drop(int entityId) {
        return drops.getOrDefault(entityId, 0f);
    }

    @Override
    public int entityId() {
        return -1;
    }
}
