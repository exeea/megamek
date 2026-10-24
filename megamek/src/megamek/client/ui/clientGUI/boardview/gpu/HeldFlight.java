/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import megamek.common.units.EntityMovementType;

/**
 * An aircraft's move this round, held back from playback: its model waits at the start of the path while a faded copy
 * shows the destination, and the move plays when the firing results begin. It keeps what the board needs to play the
 * move later and to draw the wait.
 *
 * @param entityId    the aircraft
 * @param round       the round the move was made in
 * @param path        the drawn path, from the start of the move to its end; at least two points
 * @param startKnown  whether the first point is where the aircraft really began; {@code false} when the player only
 *                    saw it arrive, and then the move is not held: it plays at once, as any other
 * @param type        the move's movement type, for its playback
 * @param jumpMP      jump MP, for its playback
 * @param movementMP  movement MP, for its playback
 */
record HeldFlight(int entityId, int round, List<BoardScene.Waypoint> path, boolean startKnown,
      EntityMovementType type, int jumpMP, int movementMP) {

    HeldFlight {
        path = List.copyOf(path);
    }

    /** Where the aircraft's model waits. */
    BoardScene.Waypoint start() {
        return path.getFirst();
    }

    /** Where the move ends: the aircraft's game position. */
    BoardScene.Waypoint destination() {
        return path.getLast();
    }
}
