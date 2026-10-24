/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

/**
 * A running pick of board hexes by clicking them: a bot order's target hexes, or the Move Order editor's waypoints. One
 * runs per board at a time ({@link BoardClientState#startHexPick}). The native battle window's HUD shows its
 * instructions and status, gives it the board's left clicks, and ends it with its Done and Cancel keys.
 */
public interface BoardHexPick {
    /** @return what the clicked hexes are for */
    String instructions();

    /** @return the hexes picked so far, shown once {@link #hasHexes} */
    String status();

    /** @return whether a hex is picked */
    boolean hasHexes();

    /** Ends the pick as its Done does. */
    void done();

    /** Ends the pick as its Cancel does. */
    void cancel();
}
