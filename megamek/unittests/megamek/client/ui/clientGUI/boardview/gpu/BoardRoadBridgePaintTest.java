/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

/** The bridge's bank extension carries the same road, ballast and markings as the span. */
class BoardRoadBridgePaintTest {
    private static final Coords AT = new Coords(2, 2);
    private static final float REACH = 24;

    private static BoardRoad road(int exits, RailRoute.Corridor corridor) {
        return BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).corridor(corridor);
    }

    private static BoardRoad extend(BoardRoad road, int direction) {
        var lengths = new ArrayList<>(List.of(0f, 0f, 0f, 0f, 0f, 0f));
        lengths.set(direction, REACH);
        return road.extended(lengths);
    }

    @Test
    void middleBallastAndBothYellowLinesContinueThroughEveryRampDirection() {
        for (int d = 0; d < 6; d++) {
            int exits = 1 << d | 1 << (d + 3) % 6;
            var road = extend(road(exits, new RailRoute.Corridor(exits, 0, 0)), d);
            var lines = road.medianLines();
            float[] edge = RailRoute.border(d);
            float length = (float) Math.hypot(edge[0], edge[1]);
            float nx = edge[0] / length, ny = edge[1] / length;
            for (float along : new float[] { 4, 12, 22 }) {
                float x = edge[0] + nx * along, y = edge[1] + ny * along;
                assertTrue(road.medianBand().contains(x, y), "Ballast continues beyond border " + d);
                assertFalse(road.street(x, y), "The ramp's center remains reserved for rail");
                for (int side : new int[] { -1, 1 }) {
                    assertTrue(lines.contains(x + side * ny * 6, y - side * nx * 6),
                          "Both median lines continue on ramp " + d + " at " + along);
                }
            }
            assertFalse(lines.contains(edge[0] + nx * (REACH + 3) + ny * 6,
                  edge[1] + ny * (REACH + 3) - nx * 6), "Paint ends with the carrier");
        }
    }

    @Test
    void besideRoadKeepsItsOffsetAndFullWidthAcrossEveryRampDirection() {
        for (int d = 0; d < 6; d++) {
            int exits = 1 << d | 1 << (d + 3) % 6;
            for (int right : new int[] { 0, exits }) {
                var corridor = new RailRoute.Corridor(exits, exits, right);
                var road = extend(road(exits, corridor), d);
                float[] edge = RailRoute.border(d);
                float length = (float) Math.hypot(edge[0], edge[1]);
                float nx = edge[0] / length, ny = edge[1] / length;
                float[] shift = RailRoute.borderOffset(d, -6.35f * corridor.lateral(d));
                for (float along : new float[] { 4, 12, 22 }) {
                    float x = edge[0] + nx * along + shift[0], y = edge[1] + ny * along + shift[1];
                    assertEquals(-BoardRoad.Kind.PAVED.halfWidth, road.distance(x, y), .02f,
                          "The road follows its offset all the way down ramp " + d);
                    for (int side : new int[] { -1, 1 }) {
                        assertTrue(road.footprint(0).contains(x + side * ny * 7, y - side * nx * 7),
                              "Both lanes remain paved at " + along);
                    }
                }
                assertFalse(road.markings(AT, exits).isEmpty(), "The offset road retains its center dashes");
            }
        }
    }

    @Test
    void structuralCorridorIsOneCenteredWidthForEitherRailPlacement() {
        var base = BoardRoad.layout(AT, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED);
        for (var corridor : List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var slab = base.bridgeCorridor(corridor);
            for (float y : new float[] { -30, 0, 30 }) {
                assertEquals(0, slab.distance(BoardRoad.CORRIDOR_HALF, y), .01f);
                assertEquals(0, slab.distance(-BoardRoad.CORRIDOR_HALF, y), .01f);
                assertTrue(slab.footprint(0).contains(1.8f, y), "No structural slot between beside road and rail");
            }
        }
    }
}
