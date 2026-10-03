/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardRoadTurnTest {
    @Test
    void fireAndIceTightTurnsRetainClearanceAndSharedRoadMouths() throws Exception {
        var file = new File("data/boards/unofficial/Drewbacca/16x17 Fire And Ice 2.board");
        var board = new Board();
        board.load(file);
        var scene = BoardCliffSeamTest.scene(file);
        for (var tile : scene.tiles()) {
            if (!BoardRoad.rendered(tile)) { continue; }
            var at = tile.coords();
            var road = BoardRoad.of(scene, tile);
            var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
            int exits = tile.roadExits() & 63;
            if (Integer.bitCount(exits) == 2 && (exits & ((exits << 1) | (exits >> 5))) != 0) {
                float radius = assertBroad(road);
                if (at.equals(new Coords(5, 7))) {
                    assertTrue(radius > 2 * (BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER),
                          "The reported 0608 kink must turn across the level hex instead of at its edge: " + radius);
                }
            }
            for (float x = -35; x <= 35; x += 2.5f) {
                for (float y = -32; y <= 32; y += 2.5f) {
                    assertEquals(road.distance(x, y), captured.distance(x, y), .002f);
                }
            }
            for (int d = 0; d < 6; d++) {
                if ((exits & (1 << d)) == 0) { continue; }
                var next = scene.tile(at.translated(d));
                if (next == null || !BoardRoad.rendered(next)) { continue; }
                var adjacent = BoardRoad.of(scene, next);
                var middle = gate(at, d);
                var lateral = new Vector2(-middle.y, middle.x).nor();
                for (float offset = -7; offset <= 7; offset += .5f) {
                    var point = new Vector2(middle).mulAdd(lateral, offset);
                    var other = new Vector2(middle).scl(-1).mulAdd(lateral, offset);
                    assertTrue(road.distance(point.x, point.y) < 0 && adjacent.distance(other.x, other.y) < 0,
                          at + " -> " + next.coords() + " must cover the same full-width mouth at offset " + offset);
                }
                var outward = BoardRoad.bends(at, c -> BoardRoad.Node.of(scene.tile(c))).apply(d);
                var inward = BoardRoad.bends(next.coords(), c -> BoardRoad.Node.of(scene.tile(c))).apply((d + 3) % 6);
                if (outward == null) {
                    assertNull(inward);
                } else {
                    assertNotNull(inward);
                    assertTrue(outward.epsilonEquals(new Vector2(inward).scl(-1), .00001f));
                }
            }
        }
    }

    @Test
    void tightTurnsLeaveRoomForTheInsideShoulder() throws Exception {
        for (int x : new int[] { 3, 4 }) {
            var at = new Coords(x, 4);
            for (int direction = 0; direction < 6; direction++) {
                int first = direction, last = (direction + 1) % 6;
                int exits = (1 << first) | (1 << last);
                for (int full : new int[] { -1, first, last }) {
                    var road = BoardRoad.layout(at, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED,
                          d -> d == full ? gate(at, d).nor() : null);
                    assertBroad(road);
                    for (int d : new int[] { first, last }) {
                        var gate = gate(at, d);
                        var lateral = new Vector2(-gate.y, gate.x).nor();
                        for (int offset : new int[] { -9, -7, 0, 7, 9 }) {
                            var point = new Vector2(gate).mulAdd(lateral, offset);
                            assertEquals(Math.abs(offset) - 7.5f, road.distance(point.x, point.y), .01f,
                                  "A rounded turn retains the entire shared road mouth");
                        }
                    }
                }
            }
        }
    }

    @Test
    void mesaBridgeHairpinAndCapturedClearanceFollowTheSameBroadCourse() throws Exception {
        var file = new File("data/boards/unofficial/SimonLandmine/64x51/64x51 MesaCity1 N - Mesas.board");
        var board = new Board();
        board.load(file);
        var scene = BoardCliffSeamTest.scene(file);
        var at = new Coords(26, 21);
        assertEquals(3, scene.tile(at).roadExits());
        var road = BoardRoad.of(scene, scene.tile(at));
        var captured = BoardRoad.clearance(at, board.getHex(at), board::getHex);
        assertBroad(road);
        for (float x = -35; x <= 35; x += 1.25f) {
            for (float y = -32; y <= 32; y += 1.25f) {
                assertEquals(road.distance(x, y), captured.distance(x, y), .002f);
            }
        }
        var next = at.translated(1);
        var adjacent = BoardRoad.of(scene, scene.tile(next));
        var middle = gate(at, 1);
        var lateral = new Vector2(-middle.y, middle.x).nor();
        for (float offset = -9; offset <= 9; offset += .5f) {
            var point = new Vector2(middle).mulAdd(lateral, offset);
            var other = new Vector2(middle).scl(-1).mulAdd(lateral, offset);
            assertEquals(road.distance(point.x, point.y), adjacent.distance(other.x, other.y), .002f,
                  "The bridge approach keeps its shared width and tangent");
        }
    }

    private static Vector2 gate(Coords at, int direction) {
        Vector3 delta = BoardGeometry.center(at.translated(direction), 0).sub(BoardGeometry.center(at, 0))
              .scl(.5f / BoardGeometry.hexScale());
        return new Vector2(delta.x, delta.y);
    }

    private static float assertBroad(BoardRoad road) throws Exception {
        var field = BoardRoad.class.getDeclaredField("paths");
        field.setAccessible(true);
        var path = (List<?>) ((List<?>) field.get(road)).getFirst();
        var points = new ArrayList<Vector2>();
        for (var point : path) {
            var x = point.getClass().getDeclaredMethod("x");
            var y = point.getClass().getDeclaredMethod("y");
            x.setAccessible(true);
            y.setAccessible(true);
            points.add(new Vector2((float) x.invoke(point), (float) y.invoke(point)));
        }
        float minimum = Float.POSITIVE_INFINITY;
        for (int i = 1; i + 1 < points.size(); i++) {
            var a = points.get(i - 1);
            var b = points.get(i);
            var c = points.get(i + 1);
            var ab = new Vector2(b).sub(a);
            var bc = new Vector2(c).sub(b);
            float area = Math.abs(ab.crs(bc));
            if (area < 1e-4f) { continue; }
            float radius = ab.len() * bc.len() * a.dst(c) / (2 * area);
            minimum = Math.min(minimum, radius);
        }
        assertTrue(Float.isFinite(minimum));
        assertTrue(minimum > BoardRoad.Kind.PAVED.halfWidth + BoardRoad.SHOULDER,
              "The inside shoulder must have a positive turn radius: " + minimum);
        assertTrue(points.size() <= 26, "Roundness does not require denser sampling");
        return minimum;
    }
}
