/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import megamek.common.board.Coords;
import megamek.common.board.CubeCoords;

/**
 * The waypoints of a route drawn by hand (the user's design of 2026-10-11): the press's hex, then each hex the drag
 * enters. A move that skips hexes fills them with a straight hex line from the last waypoint. Stepping back into the hex
 * before the last waypoint (or a fast move back exactly along its border) takes that waypoint back, so backing along the
 * path removes waypoint after waypoint; entering any other hex of the path adds it again, so one stroke may cross or loop
 * over itself. Consecutive waypoints are neighbours, and a path always holds its first hex.
 */
record RoutePath(List<Coords> hexes) {
    RoutePath {
        hexes = List.copyOf(hexes);
    }

    static RoutePath start(Coords at) { return new RoutePath(List.of(at)); }

    Coords end() { return hexes.getLast(); }

    /**
     * The path once the pointer has entered {@code at}: each hex of the straight line from the last waypoint to it
     * (cube coordinates, so a move along a row takes no zigzag) taken as if entered in turn. Where that line runs exactly
     * between two hexes, it takes the one that lies on the board ({@code board} says which do), and the previous waypoint
     * over the other, so such a move back retraces alike in every direction.
     */
    RoutePath enter(Coords at, Predicate<Coords> board) {
        CubeCoords from = end().toCube(), to = at.toCube();
        List<Coords> line = from.intervening(to).stream().map(CubeCoords::toOffset).toList();
        List<Coords> result = new ArrayList<>(hexes);
        for (int i = 1; i < line.size(); i++) {
            Coords cell = line.get(i);
            int last = result.size() - 1;
            Coords back = last > 0 ? result.get(last - 1) : null;
            if (i + 1 < line.size()) {
                Coords other = across(line.get(i - 1), cell, line.get(i + 1));
                if (!board.test(cell) || other.equals(back) && between(from, to, i, line.size() - 1, cell, other)) {
                    cell = other;
                }
            }
            if (cell.equals(back)) {
                result.remove(last);
            } else {
                result.add(cell);
            }
        }
        return new RoutePath(result);
    }

    /**
     * Whether step {@code step} of {@code steps} along the straight line from {@code from} to {@code to} lies exactly on
     * the border of the neighbouring hexes {@code one} and {@code other}, as near the one as the other: exact, as the
     * point and the two hexes' middle are taken {@code 2 * steps} times, which keeps them whole.
     */
    private static boolean between(CubeCoords from, CubeCoords to, int step, int steps, Coords one, Coords other) {
        CubeCoords a = one.toCube(), b = other.toCube();
        double q = 2 * (from.q() * (steps - step) + to.q() * step) - steps * (a.q() + b.q());
        double r = 2 * (from.r() * (steps - step) + to.r() * step) - steps * (a.r() + b.r());
        double s = 2 * (from.s() * (steps - step) + to.s() * step) - steps * (a.s() + b.s());
        return q * (a.q() - b.q()) + r * (a.r() - b.r()) + s * (a.s() - b.s()) == 0;
    }

    /** The other hex that neighbours both {@code before} and {@code after}, which lie two apart, besides {@code tie}. */
    private static Coords across(Coords before, Coords tie, Coords after) {
        for (int direction = 0; direction < 6; direction++) {
            Coords other = before.translated(direction);
            if (!other.equals(tie) && other.distance(after) == 1) { return other; }
        }
        return tie;
    }
}
