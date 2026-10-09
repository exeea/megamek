/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import megamek.common.Hex;

/**
 * One cosmetic route marker per hex, at its centre. The marker stores the sides the route leaves by
 * ({@link BoardDecoration#connections()}); a side joins the neighbour only when the neighbour's marker has the opposite
 * side, so parallel routes in adjacent hexes stay apart.
 */
public final class MaglevRoute {
    public static final String ASSET = "scenery/maglev/route";

    private MaglevRoute() { }

    public static boolean isRoute(BoardDecoration object) {
        return object != null && object.kind().equals("prop") && object.asset().equals(ASSET);
    }

    public static BoardDecoration find(Hex hex) {
        if (hex == null) { return null; }
        return hex.getDecorations().stream().filter(MaglevRoute::isRoute).findFirst().orElse(null);
    }

    /** The side bit of a {@link Coords#translated(int)} direction. */
    public static int side(int direction) { return 1 << direction; }

    /** True when {@code route} leaves by {@code direction} and {@code neighbour}, the marker that way, leaves back. */
    public static boolean joined(BoardDecoration route, int direction, BoardDecoration neighbour) {
        return isRoute(route) && isRoute(neighbour) && (route.connections() & side(direction)) != 0
              && (neighbour.connections() & side((direction + 3) % 6)) != 0;
    }
}
