/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import megamek.common.annotations.Nullable;

/**
 * The aircraft whose moves are held back in one frame of the scene: the board draws each at the start of its path with
 * a faded copy at its destination and a path line between them, instead of its model at the destination.
 *
 * @param flights the held moves, in the order they were made
 */
record BoardAirspace(List<HeldFlight> flights) {

    /** No aircraft held: map previews, the editor, tests and every phase outside Movement to Firing. */
    static final BoardAirspace EMPTY = new BoardAirspace(List.of());

    BoardAirspace {
        flights = List.copyOf(flights);
    }

    /** @return the held move of this unit, or {@code null} if its model is drawn normally */
    @Nullable HeldFlight flight(int entityId) {
        for (HeldFlight flight : flights) {
            if (flight.entityId() == entityId) {
                return flight;
            }
        }
        return null;
    }

    /** @return whether this unit's model waits at the start of its move rather than standing at its position */
    boolean isHeld(int entityId) {
        return flight(entityId) != null;
    }
}
