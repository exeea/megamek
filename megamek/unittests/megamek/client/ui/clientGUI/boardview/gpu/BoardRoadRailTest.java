/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

/** The road's width, paint and rail inset all follow its shared corridor footprint. */
class BoardRoadRailTest {
    private static final Coords AT = new Coords(2, 2);

    private static BoardRoad road(int exits, RailRoute.Corridor corridor) {
        return BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).corridor(corridor);
    }

    @Test
    void middleHasTwoFullLanesAndBallastWithContinuousBorderWidths() {
        var road = road(9, new RailRoute.Corridor(9, 0, 0));
        float y = RailRoute.border(0)[1];
        for (float at : new float[] { -y, -20, 0, 20, y }) {
            assertEquals(0, road.distance(13.4f, at), .02f);
            assertEquals(0, road.distance(-13.4f, at), .02f);
            assertFalse(road.street(0, at), "The central track retains ballast");
            assertTrue(road.street(9.5f, at), "Each side remains a driveable lane");
        }
        assertTrue(road.medianLines().contains(6, 20));
        assertFalse(road.markings(AT, 9).contains(0, 20), "No dashes under the central track");
        assertEquals(1, road.wheelCoverage(9.5f, 20), .001f);
        assertEquals(0, road.wheelCoverage(3, 20), .001f, "Unsealed wheel wear stays outside the ballast");
        assertTrue(road.tracks(9).contains(9.5f, 20));
        assertEquals(0, BoardRoad.clearance(AT, 9, new RailRoute.Corridor(9, 0, 0)).distance(13.9f, y), .02f);
    }

    @Test
    void sideRoadCrossesTheTrackWithoutPaintOrBallastBlockingIt() {
        var corridor = new RailRoute.Corridor(9, 0, 0);
        var road = road(11, corridor);
        assertTrue(road.street(0, 0), "The road's side arm crosses the median at its hub");
        assertFalse(road.medianLines().contains(6, 0));
        assertEquals(1, road.crossings(RailRoute.lines(9, RailRoute.pieces(9), corridor)).size());
        float[] side = RailRoute.border(1);
        float length = (float) Math.hypot(side[0], side[1]);
        assertEquals(0, road.distance(side[0] + side[1] / length * 7.5f, side[1] - side[0] / length * 7.5f), .05f,
              "A side road stays ordinary width");
        assertFalse(road.street(0, 25), "Away from the crossing the ballast resumes");
    }

    @Test
    void besideKeepsRoadDashesAndMovesBothCoursesToOppositeSides() {
        var corridor = new RailRoute.Corridor(9, 9, 9);
        var road = road(9, corridor);
        float y = RailRoute.border(0)[1];
        for (float at : new float[] { -y, 0, y }) {
            assertEquals(0, road.distance(-13.85f, at), .02f);
            assertEquals(0, road.distance(1.15f, at), .02f);
        }
        assertFalse(road.markings(AT, 9).isEmpty());
        assertTrue(road.medianLines().isEmpty());
        var line = RailRoute.lines(9, RailRoute.pieces(9), corridor).getFirst();
        assertEquals(8.25f, line.at(.5f)[0], .001f);
        assertTrue(8.25f - 8.9f / 2 - (-6.35f + 4.8f / 2) >= 1.68f,
              "An ordinary car and train leave at least 0.6m between them");
        assertFalse(road.street(line.at(.5f)[0], 0), "The beside track is on ballast");
    }

    @Test
    void medianFollowsTheAuthoredJunctionRatherThanInventingACrossing() {
        int tracks = 1 << RailRoute.piece(0, 4) | 1 << RailRoute.piece(1, 3);
        assertTrue(RailRoute.valid(27, tracks));
        assertTrue(road(27, new RailRoute.Corridor(27, 0, 0)).medianBand().contains(0, 0));
        assertFalse(road(27, new RailRoute.Corridor(27, 0, 0, tracks)).medianBand().contains(0, 0));
    }

    @Test
    void ordinaryRoadIsUnchangedAndASingleSharedArmDoesNotWidenTheOtherBorder() {
        var original = road(9, RailRoute.Corridor.NONE);
        assertEquals(0, original.distance(7.5f, 20), .001f);
        assertTrue(original.street(0, 0));
        assertTrue(original.medianLines().isEmpty());
        var end = road(9, new RailRoute.Corridor(1, 0, 0));
        assertEquals(0, end.distance(13.4f, RailRoute.border(0)[1]), .02f);
        assertEquals(0, end.distance(7.5f, RailRoute.border(3)[1]), .02f);
    }

    @Test
    void shippedSlopedBesideRailsMeetAtTheSameCarrierHeight() throws Exception {
        var view = GpuRoadRailSmokeTest.load("unofficial/VictorMorson/48x48 The Trainjob.board", new Coords(37, 29), true);
        var scene = view.scene();
        // The 3529 -> 3629 grade immediately before the bridge, within the even-column 12x12 crop.
        var a = scene.tile(2, 4);
        var b = scene.tile(3, 4);
        var first = BoardRail.layout(scene, a, BoardRail.route(a), new BoardSurface(scene, a), at -> null);
        var second = BoardRail.layout(scene, b, BoardRail.route(b), new BoardSurface(scene, b), at -> null);
        float distance = Float.POSITIVE_INFINITY;
        Vector3 from = null, to = null;
        for (var pathA : first.paths()) {
            for (var pathB : second.paths()) {
                for (var pointA : java.util.List.of(pathA.getFirst(), pathA.getLast())) {
                    for (var pointB : java.util.List.of(pathB.getFirst(), pathB.getLast())) {
                        var wa = new Vector3(pointA).scl(BoardGeometry.hexScale()).add(BoardGeometry.center(a.coords(), a.elevation()));
                        var wb = new Vector3(pointB).scl(BoardGeometry.hexScale()).add(BoardGeometry.center(b.coords(), b.elevation()));
                        float separation = (float) Math.hypot(wa.x - wb.x, wa.y - wb.y);
                        if (separation < distance) { distance = separation; from = wa; to = wb; }
                    }
                }
            }
        }
        assertEquals(0, distance, .001f, "The offset rail meets its neighbour in plan");
        assertEquals(from.z, to.z, .001f, "The offset rail meets its neighbour vertically: " + from + " / " + to);
    }
}
