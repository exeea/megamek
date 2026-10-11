/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.Predicate;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** The waypoints of a route drawn by hand: retrace by the previous hex only, crossings, and gap fill. */
class RoutePathTest {
    /** A 16 x 17 board. */
    private static final Predicate<Coords> BOARD = at -> at.getX() >= 0 && at.getY() >= 0 && at.getX() < 16 && at.getY() < 17;

    private static RoutePath walk(Coords start, int... directions) {
        RoutePath path = RoutePath.start(start);
        Coords at = start;
        for (int direction : directions) {
            at = at.translated(direction);
            path = path.enter(at, BOARD);
        }
        return path;
    }

    private static List<Coords> line(Coords start, int... directions) {
        List<Coords> result = new java.util.ArrayList<>(List.of(start));
        for (int direction : directions) { result.add(result.getLast().translated(direction)); }
        return result;
    }

    @Test void steppingBackIntoThePreviousHexTakesTheLastWaypointBackOnceOrAlongThePath() {
        Coords start = new Coords(5, 5);
        RoutePath path = walk(start, 3, 3, 2, 2);
        assertEquals(line(start, 3, 3, 2, 2), path.hexes());
        Coords end = path.end();
        // Back into the previous hex: the last waypoint goes.
        path = path.enter(end.translated(5), BOARD);
        assertEquals(line(start, 3, 3, 2), path.hexes());
        // And again, waypoint after waypoint along the path.
        path = path.enter(path.end().translated(5), BOARD);
        path = path.enter(path.end().translated(0), BOARD);
        assertEquals(line(start, 3), path.hexes());
        // Back to the start: the path keeps its first hex.
        path = path.enter(start, BOARD);
        assertEquals(List.of(start), path.hexes());
        // A fast move back over several hexes takes them all back at once.
        path = walk(start, 3, 3, 3, 3).enter(start.translated(3), BOARD);
        assertEquals(line(start, 3), path.hexes());
    }

    @Test void enteringAnOlderHexOfThePathAddsItAgainSoTheStrokeCrossesItself() {
        Coords start = new Coords(5, 5);
        // South three, then round to the north-east and back south-west into the second hex: a loop over itself.
        RoutePath path = walk(start, 3, 3, 3, 1, 0, 4);
        Coords crossing = start.translated(3).translated(3);
        assertEquals(line(start, 3, 3, 3, 1, 0, 4), path.hexes());
        assertEquals(crossing, path.end(), "The loop comes back over its own hex");
        assertEquals(2, path.hexes().stream().filter(crossing::equals).count(), "That hex is a waypoint twice: no cut");
        // Going on through it keeps everything before it.
        path = path.enter(crossing.translated(4), BOARD);
        assertEquals(line(start, 3, 3, 3, 1, 0, 4, 4), path.hexes());
    }

    @Test void aMoveThatSkipsHexesFillsThemWithAStraightLine() {
        Coords start = new Coords(3, 3);
        // Straight south over four hexes at once.
        assertEquals(line(start, 3, 3, 3, 3), RoutePath.start(start).enter(new Coords(3, 7), BOARD).hexes());
        // Along a row (east, where the line runs between hexes): a straight zigzag-free line of neighbours.
        RoutePath row = RoutePath.start(new Coords(2, 6)).enter(new Coords(8, 6), BOARD);
        assertEquals(7, row.hexes().size(), "Six steps, one waypoint each");
        for (int i = 1; i < row.hexes().size(); i++) {
            assertEquals(1, row.hexes().get(i - 1).distance(row.hexes().get(i)));
            assertEquals(i, new Coords(2, 6).distance(row.hexes().get(i)), "Never a step back or sideways");
        }
        // Along the board's bottom row the line keeps to the board.
        RoutePath edge = RoutePath.start(new Coords(1, 16)).enter(new Coords(7, 16), BOARD);
        assertEquals(7, edge.hexes().size());
        edge.hexes().forEach(at -> assertEquals(true, BOARD.test(at), at + " lies on the board"));
        for (int i = 1; i < edge.hexes().size(); i++) { assertEquals(1, edge.hexes().get(i - 1).distance(edge.hexes().get(i))); }
    }

    /**
     * A fast move back that runs exactly along the border of the hex before the last waypoint takes that waypoint back,
     * alike in every direction and its mirror image: past the previous hex it leaves no hairpin, and back along a zigzag
     * it takes the whole zigzag back.
     */
    @Test void aFastMoveBackAlongThePreviousHexsBorderRetracesAlikeInEveryDirection() {
        Coords start = new Coords(7, 8);
        for (int direction = 0; direction < 6; direction++) {
            for (int bend : new int[] { 1, 5 }) {
                // Out one hex, then two hexes back past the start, bending one way or the other.
                Coords past = start.translated((direction + 3 + bend) % 6);
                assertEquals(List.of(start, past), walk(start, direction).enter(past, BOARD).hexes(), direction + " back " + bend);
                // A zigzag, and straight back to its start in one move.
                int turn = (direction + bend) % 6;
                assertEquals(List.of(start), walk(start, direction, turn, direction, turn).enter(start, BOARD).hexes(),
                      direction + " zigzag " + bend);
            }
        }
    }
}
