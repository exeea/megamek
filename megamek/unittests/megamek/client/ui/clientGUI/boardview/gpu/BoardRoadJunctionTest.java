/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;

import com.badlogic.gdx.math.Vector2;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRoadJunctionTest {
    @Test
    void everyThreeAndFourWayJunctionHasASolidHubAndStopsOnlyItsIncomingLanes() {
        for (int column : new int[] { 3, 4 }) {
            Coords at = new Coords(column, 4);
            for (int exits = 0; exits < 64; exits++) {
                int count = Integer.bitCount(exits);
                var road = BoardRoad.clearance(at, exits);
                var approaches = road.stopApproaches(exits);
                Area stops = road.stopLines(exits);
                if (count < 3 || count > 4) {
                    assertTrue(approaches.isEmpty(), "Through roads, dead ends and roundabouts retain their existing layout");
                    assertTrue(stops.isEmpty());
                    continue;
                }
                assertEquals(count, approaches.size());
                Area footprint = road.footprint(0), dashes = road.markings(at, exits);
                for (int degrees = 0; degrees < 360; degrees += 15) {
                    double angle = Math.toRadians(degrees);
                    assertTrue(footprint.contains(7 * Math.cos(angle), 7 * Math.sin(angle)), "A crossing has no central island");
                    for (int radius = 0; radius < 24; radius++) {
                        assertFalse(dashes.contains(radius * Math.cos(angle), radius * Math.sin(angle)),
                              "Every approach leaves the shared turning area clear of centre dashes");
                    }
                }
                for (var approach : approaches) {
                    assertTrue((exits & (1 << approach.direction())) != 0);
                    assertEquals(1, Math.hypot(approach.nx(), approach.ny()), .0001);
                    for (float across = approach.inner() + .2f; across < approach.outer(); across += .5f) {
                        float x = approach.x() - approach.ny() * across;
                        float y = approach.y() + approach.nx() * across;
                        assertTrue(stops.contains(x, y), "One continuous transverse bar covers the incoming lane");
                        assertTrue(footprint.contains(x, y), "Stop paint stays on its carriageway");
                        assertFalse(stops.contains(x + approach.nx(), y + approach.ny()), "The bar is a narrow line");
                        assertFalse(stops.contains(approach.x() + approach.ny() * across,
                              approach.y() - approach.nx() * across), "The outgoing lane is not stopped");
                    }
                }
                stops.subtract(footprint);
                assertTrue(stops.isEmpty());
            }
        }
    }

    @Test
    void crossedRoadsRoundTheirTightCornersWithoutAddingUnconnectedEntrances() {
        for (int axis = 0; axis < 3; axis++) {
            int first = axis, second = (axis + 1) % 6;
            int exits = (1 << first) | (1 << second) | (1 << ((first + 3) % 6)) | (1 << ((second + 3) % 6));
            var road = BoardRoad.clearance(BoardRoadTest.CENTER, exits);
            float[] a = RailRoute.border(first), b = RailRoute.border(second);
            var corner = new Vector2(a[0], a[1]).nor().add(new Vector2(b[0], b[1]).nor()).nor().scl(17);
            assertTrue(road.footprint(0).contains(corner.x, corner.y), "The tight X corner has a rounded turning apron");
            assertTrue(road.distance(corner.x, corner.y) < 0, "Scenery clearance follows that same apron");
            for (int direction = 0; direction < 6; direction++) {
                float[] border = RailRoute.border(direction);
                assertEquals((exits & (1 << direction)) != 0, road.footprint(0).contains(border[0], border[1]));
            }
        }
    }

    @Test
    void unmarkedMaterialJoinsDoNotAcquireStopPaintAndOnlyPavedRoadsRenderIt() {
        Coords at = BoardRoadTest.CENTER;
        var road = BoardRoad.layout(at, 27, BoardRoad.Kind.PAVED,
              direction -> direction == 1 ? BoardRoad.Kind.ALLEY : direction == 4 ? BoardRoad.Kind.DIRT : BoardRoad.Kind.PAVED);
        assertEquals(java.util.List.of(0, 3), road.stopApproaches(27).stream().map(BoardRoad.StopApproach::direction).toList());
        var sample = road.stopApproaches(27).getFirst();
        float across = (sample.inner() + sample.outer()) / 2;
        float x = sample.x() - sample.ny() * across, y = sample.y() + sample.nx() * across;
        for (var kind : BoardRoad.Kind.values()) {
            if (kind == BoardRoad.Kind.NONE) { continue; }
            var tile = BoardRoadTest.tile(at, kind, 27, 0, BoardScene.Surface.GRASS);
            var patches = GpuRoads.patches(tile, BoardRoad.layout(at, 27, kind, direction -> kind));
            assertEquals(kind == BoardRoad.Kind.PAVED,
                  patches.stream().anyMatch(patch -> patch.texture().equals("concrete") && patch.shape().contains(x, y)),
                  "Only painted roads get stop-line material");
        }
    }

    @Test
    void stopBarsFollowWidenedOrOffsetRailCorridorsAndLeaveTheirBallastClear() {
        for (var corridor : java.util.List.of(new RailRoute.Corridor(9, 0, 0), new RailRoute.Corridor(9, 9, 9))) {
            var road = BoardRoad.layout(BoardRoadTest.CENTER, 27, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
                  .corridor(corridor);
            Area paint = road.stopLines(27);
            assertEquals(4, road.stopApproaches(27).size());
            for (var approach : road.stopApproaches(27)) {
                float across = (approach.inner() + approach.outer()) / 2;
                assertTrue(paint.contains(approach.x() - approach.ny() * across,
                      approach.y() + approach.nx() * across), "The stop bar follows the fitted road lane");
            }
            paint.intersect(road.medianBand());
            assertTrue(paint.isEmpty(), "Road markings leave the railway's ballast clear");
        }
    }
}
