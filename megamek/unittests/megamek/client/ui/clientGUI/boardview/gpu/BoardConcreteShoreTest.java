/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardConcreteShoreTest {
    private static final Coords CENTER = new Coords(3, 3);

    @Test
    void noneKeepsSharpHexEdgesAgainstWater() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            BoardConcrete.tune(BoardConcrete.Mode.OFF);
            Set<Coords> land = Set.of(CENTER, CENTER.translated(0), CENTER.translated(1));
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            for (BoardScene.Tile tile : scene.tiles()) {
                if (tile.liquid().present() && tile.coords().distance(CENTER) > 2) { continue; }
                BoardSurface surface = new BoardSurface(scene, tile);
                if (!tile.liquid().present()) {
                    for (int k = 0; k < 6; k++) {
                        assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .001f);
                    }
                    assertStraightSamples(surface, tile.coords());
                } else {
                    for (int e = 0; e < 6; e++) {
                        if (!land.contains(tile.coords().translated(BoardGeometry.edgeDirection(e)))) { continue; }
                        Vector3 a = BoardGeometry.corner(tile.coords(), 0, e);
                        Vector3 b = BoardGeometry.corner(tile.coords(), 0, e + 1);
                        for (int s = 0; s < BoardSurface.SHORE_SEGMENTS; s++) {
                            Vector3 expected = new Vector3(a).lerp(b, s / (float) BoardSurface.SHORE_SEGMENTS);
                            Vector3 actual = surface.outline.get(e * BoardSurface.SHORE_SEGMENTS + s);
                            assertEquals(expected.x, actual.x, .001f, "None follows the exact hex border beside water");
                            assertEquals(expected.y, actual.y, .001f);
                        }
                    }
                }
                assertUnfolded(surface, "Sharp hex boundaries in None mode");
            }
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void broadConcretePatchesFitFourStraightSidesInAllOrientations() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            BoardConcrete.tune(BoardConcrete.Mode.EVERYWHERE);
            for (int direction = 0; direction < 3; direction++) {
                BoardScene scene = pavedPatch(direction);
                BoardConcrete shape = BoardConcrete.of(scene);
                Coords origin = new Coords(8, 8);
                Vector3 axis = BoardGeometry.center(origin.translated(direction), 0)
                      .sub(BoardGeometry.center(origin, 0)).nor();
                List<Vector3> points = new ArrayList<>();
                for (BoardScene.Tile tile : scene.tiles()) {
                    if (tile.surface() != BoardScene.Surface.CONCRETE) { continue; }
                    for (int e = 0; e < 6; e++) {
                        if (scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(e))).surface()
                              == BoardScene.Surface.CONCRETE) { continue; }
                        for (int k : new int[] { e, (e + 1) % 6 }) {
                            Vector3 p = BoardGeometry.corner(tile.coords(), 0, k);
                            var shift = shape.corners(tile.coords()).get(k);
                            p.add(shift.x(), shift.y(), 0);
                            points.add(new Vector3(p.x * axis.x + p.y * axis.y, -p.x * axis.y + p.y * axis.x, 0));
                        }
                    }
                }
                float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                for (Vector3 p : points) {
                    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
                    minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
                }
                int corners = 0;
                for (Vector3 p : points) {
                    boolean xSide = Math.min(Math.abs(p.x - minX), Math.abs(p.x - maxX)) < .003f;
                    boolean ySide = Math.min(Math.abs(p.y - minY), Math.abs(p.y - maxY)) < .003f;
                    assertTrue(xSide || ySide, "Every boundary point lies on the rectangle, orientation=" + direction);
                    if (xSide && ySide) { corners |= 1 << ((Math.abs(p.x - minX) < .003f ? 0 : 1)
                          + (Math.abs(p.y - minY) < .003f ? 0 : 2)); }
                }
                assertEquals(15, corners, "All four square corners are present");
            }
        } finally { BoardConcrete.tune(original); }
    }

    private static BoardScene pavedPatch(int direction) {
        Coords origin = new Coords(8, 8);
        Vector3 center = BoardGeometry.center(origin, 0);
        Vector3 axis = BoardGeometry.center(origin.translated(direction), 0).sub(center).nor();
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 17; x++) {
            for (int y = 0; y < 17; y++) {
                Coords at = new Coords(x, y);
                Vector3 relative = BoardGeometry.center(at, 0).sub(center);
                boolean land = Math.abs(relative.dot(axis)) <= 280 * BoardGeometry.HEX_SCALE
                      && Math.abs(relative.x * -axis.y + relative.y * axis.x) <= 85 * BoardGeometry.HEX_SCALE;
                tiles.add(new BoardScene.Tile(at, 0, -1, false, 0,
                      land ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, null, null, null, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 17, 17, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void concreteModesSwitchBothGeometryAndCachedSupport() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        BoardScene scene = GpuRiverTerrainSmokeTest.pavedGroundScene();
        BoardSurface.Cache cache = new BoardSurface.Cache();
        Coords at = new Coords(3, 4);
        try {
            BoardConcrete.tune(BoardConcrete.Mode.OFF);
            BoardSurface off = cache.get(scene, scene.tile(at));
            for (int k = 0; k < 6; k++) { assertArrayEquals(new float[] { 0, 0 }, off.relief.shoreShift(k), .001f); }
            BoardConcrete.tune(BoardConcrete.Mode.WATER_ONLY);
            BoardSurface waterOnly = cache.get(scene, scene.tile(at));
            for (int k = 0; k < 6; k++) { assertArrayEquals(new float[] { 0, 0 }, waterOnly.relief.shoreShift(k), .001f); }
            BoardConcrete.tune(BoardConcrete.Mode.EVERYWHERE);
            BoardSurface fitted = cache.get(scene, scene.tile(at));
            assertNotSame(waterOnly, fitted, "Changing the scope invalidates picking and support as well as drawing");
            float movement = 0;
            for (int k = 0; k < 6; k++) { movement += moved(fitted, at, k).dst(BoardGeometry.corner(at, 0, k)); }
            assertTrue(movement > 10 * BoardGeometry.HEX_SCALE, "A broad concrete patch beside grass becomes a rectangle");
            BoardConcrete.tune(BoardConcrete.Mode.OFF);
            BoardSurface restored = cache.get(scene, scene.tile(at));
            for (int k = 0; k < 6; k++) { assertArrayEquals(off.relief.shoreShift(k), restored.relief.shoreShift(k), .001f); }
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void rectanglesKeepTheirWidthWhereTheyMeetTheQuay() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        BoardConcrete coast = BoardConcrete.of(scene);
        Coords tip = new Coords(10, 2);
        for (int y = 2; y <= 5; y++) {
            Coords at = new Coords(10, y);
            for (int k = 0; k < 6; k++) {
                Vector3 p = BoardGeometry.corner(at, 0, k);
                var shift = coast.corners(at).get(k);
                p.add(shift.x(), shift.y(), 0);
                assertEquals(21 * BoardGeometry.HEX_SCALE, Math.abs(p.x - BoardGeometry.centerX(tip)), .002f,
                      "Every pier vertex remains on its parallel sides, including the landing end");
            }
        }
    }

    @Test
    void straightConcreteFingersHaveParallelSidesInEveryDirection() {
        for (int direction = 0; direction < 6; direction++) {
            Set<Coords> land = new HashSet<>();
            Coords at = CENTER;
            for (int i = 0; i < 3; i++) { land.add(at); at = at.translated(direction); }
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            var decks = new HashMap<Coords, BoardSurface>();
            Vector3 a = BoardGeometry.center(CENTER, 0);
            Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(a).nor();
            int clipped = 0;
            Set<Coords> waters = new HashSet<>();
            for (Coords coords : land) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                assertStraightSamples(surface, coords);
                decks.put(coords, surface);
                for (int k = 0; k < 6; k++) {
                    Vector3 corner = BoardGeometry.corner(coords, 0, k);
                    float[] shift = surface.relief.shoreShift(k);
                    if (Math.hypot(shift[0], shift[1]) > 10 * BoardGeometry.HEX_SCALE) { clipped++; }
                    corner.add(shift[0], shift[1], 0);
                    float across = (corner.x - a.x) * -axis.y + (corner.y - a.y) * axis.x;
                    assertTrue(Math.abs(across) <= 21.01f * BoardGeometry.HEX_SCALE,
                          "A dock follows its whole chain, direction=" + direction);
                }
                Vector3 center = BoardGeometry.center(coords, 0);
                for (int angle = 0; angle < 12; angle++) {
                    float x = center.x + 12 * BoardGeometry.HEX_SCALE * (float) Math.cos(angle * Math.PI / 6);
                    float y = center.y + 12 * BoardGeometry.HEX_SCALE * (float) Math.sin(angle * Math.PI / 6);
                    assertEquals(0, BoardSurface.sampleHeight(surface.faces, x, y, Float.NaN), .001f,
                          "A land unit retains solid support on a dock");
                }
                for (int d = 0; d < 6; d++) {
                    Coords next = coords.translated(d);
                    if (!land.contains(next)) { waters.add(next); }
                }
            }
            assertEquals(6, clipped, "Only the two side tips of each dock hex need clipping");
            for (Coords coords : waters) {
                BoardSurface water = new BoardSurface(scene, scene.tile(coords));
                assertWaterCentre(water, coords);
                assertUnfolded(water, "Dock water in direction " + direction);
                // The water's canonical boundary must share every moved land corner exactly.
                for (Coords dry : land) {
                    BoardSurface deck = decks.get(dry);
                    for (int k = 0; k < 6; k++) {
                        Vector3 corner = BoardGeometry.corner(dry, 0, k);
                        for (int w = 0; w < 6; w++) {
                            if (corner.dst2(BoardGeometry.corner(coords, 0, w)) < .0001f) {
                                assertArrayEquals(deck.relief.shoreShift(k), water.relief.shoreShift(w), .001f,
                                      "Deck and adjoining water agree on the moved corner");
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void isolatedConcreteAlwaysKeepsItsHexFootprint() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            for (BoardConcrete.Mode mode : BoardConcrete.Mode.values()) {
                BoardConcrete.tune(mode);
                for (boolean building : new boolean[] { false, true }) {
                    BoardScene scene = scene(Set.of(CENTER), BoardScene.Surface.CONCRETE, building);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                    for (int k = 0; k < 6; k++) {
                        assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .001f,
                              "Six water neighbours always leave a hexagonal platform: " + mode);
                    }
                    assertStraightSamples(surface, CENTER);
                    assertUnfolded(surface, "Island platform");
                }
            }
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void dockAndQuaySidesExtendToOneLandingCorner() {
        BoardScene scene = GpuRiverTerrainSmokeTest.dockScene();
        Coords pier = new Coords(5, 5), quay = new Coords(8, 9);
        BoardSurface pierSurface = new BoardSurface(scene, scene.tile(pier));
        BoardSurface quaySurface = new BoardSurface(scene, scene.tile(quay));
        Vector3 a = moved(pierSurface, pier, 3), b = moved(pierSurface, pier, 4);
        Vector3 c = moved(quaySurface, quay, 3), d = moved(quaySurface, quay, 4);
        for (int[] vertex : new int[][] { { 7, 6, 3 }, { 7, 6, 4 }, { 7, 6, 5 }, { 8, 7, 4 },
              { 8, 8, 3 }, { 8, 8, 4 } }) {
            Coords at = new Coords(vertex[0], vertex[1]);
            BoardSurface surface = new BoardSurface(scene, scene.tile(at));
            Vector3 p = moved(surface, at, vertex[2]);
            assertTrue(Math.min(distance(a, b, p), distance(c, d, p)) < .002f,
                  "The landing follows the two straight sides without an extra hex-shaped step");
            if (vertex[0] == 8 && vertex[1] == 7) {
                assertEquals(0, distance(a, b, p), .002f, "The corner is on the pier side");
                assertEquals(0, distance(c, d, p), .002f, "The same corner is on the quay side");
            }
            assertStraightSamples(surface, at);
        }
    }

    @Test
    void concreteKeepsSharpHexEdgesBesideHigherAndLowerGround() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            BoardConcrete.tune(BoardConcrete.Mode.OFF);
            for (int elevation : new int[] { -3, -1, 1, 3 }) {
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 7; x++) {
                    for (int y = 0; y < 7; y++) {
                        Coords at = new Coords(x, y);
                        boolean concrete = at.equals(CENTER);
                        tiles.add(new BoardScene.Tile(at, concrete ? 0 : elevation, -1, false, 0,
                              concrete ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS,
                              null, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                assertStraightSamples(new BoardSurface(scene, scene.tile(CENTER)), CENTER);
            }
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void concreteAroundAnEnclosedTerrainPatchHasThreeStraightSides() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            BoardConcrete.tune(BoardConcrete.Mode.EVERYWHERE);
            BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(1);
            Set<Coords> island = new HashSet<>();
            var queue = new ArrayDeque<Coords>();
            queue.add(new Coords(8, 9));
            while (!queue.isEmpty()) {
                Coords at = queue.remove();
                if (!island.add(at)) { continue; }
                for (int direction = 0; direction < 6; direction++) {
                    Coords next = at.translated(direction);
                    assertNotNull(scene.tile(next), "The terrain island stays enclosed");
                    if (scene.tile(next).surface() != BoardScene.Surface.CONCRETE && !island.contains(next)) {
                        queue.add(next);
                    }
                }
            }
            var neighbors = new HashMap<Vector3, List<Vector3>>();
            for (Coords at : island) {
                BoardSurface surface = new BoardSurface(scene, scene.tile(at));
                Vector3 center = BoardGeometry.center(at, 0);
                assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.faces, center.x, center.y, Float.NaN)),
                      "Fitting concrete leaves the enclosed terrain centres intact");
                assertUnfolded(surface, "Fitted terrain island");
                for (int edge = 0; edge < 6; edge++) {
                    if (island.contains(at.translated(BoardGeometry.edgeDirection(edge)))) { continue; }
                    Vector3 a = moved(surface, at, edge), b = moved(surface, at, (edge + 1) % 6);
                    neighbors.computeIfAbsent(a, ignored -> new ArrayList<>()).add(b);
                    neighbors.computeIfAbsent(b, ignored -> new ArrayList<>()).add(a);
                }
            }
            int turns = 0;
            for (var entry : neighbors.entrySet()) {
                assertEquals(2, entry.getValue().size(), "The island boundary is a closed loop");
                Vector3 a = new Vector3(entry.getValue().getFirst()).sub(entry.getKey()).nor();
                Vector3 b = new Vector3(entry.getValue().getLast()).sub(entry.getKey()).nor();
                if (Math.abs(a.x * b.y - a.y * b.x) > .001f) { turns++; }
            }
            assertEquals(3, turns, "The triangular island has three straight sides without hex teeth");
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void broadJunctionExtendsItsDiagonalToTheVerticalSide() {
        BoardConcrete.Mode original = BoardConcrete.mode();
        try {
            BoardConcrete.tune(BoardConcrete.Mode.EVERYWHERE);
            BoardScene scene = GpuRiverTerrainSmokeTest.pavedMapScene(1);
            Coords diagonal = new Coords(1, 1), vertical = new Coords(4, 1);
            BoardSurface arm = new BoardSurface(scene, scene.tile(diagonal));
            BoardSurface trunk = new BoardSurface(scene, scene.tile(vertical));
            Vector3 a = moved(arm, diagonal, 1), b = moved(arm, diagonal, 2);
            Vector3 c = moved(trunk, vertical, 3), d = moved(trunk, vertical, 4);
            for (int[] vertex : new int[][] { { 2, 2, 0 }, { 2, 2, 1 }, { 2, 2, 2 }, { 3, 2, 1 }, { 4, 2, 2 } }) {
                Coords at = new Coords(vertex[0], vertex[1]);
                BoardSurface surface = new BoardSurface(scene, scene.tile(at));
                Vector3 p = moved(surface, at, vertex[2]);
                assertTrue(Math.min(distance(a, b, p), distance(c, d, p)) < .002f,
                      "No extra flat section between the diagonal and vertical sides: " + at + " " + p);
                assertStraightSamples(surface, at);
            }
        } finally { BoardConcrete.tune(original); }
    }

    @Test
    void angledConnectionsKeepTheParallelSidesOfBothArms() {
        for (int first = 0; first < 6; first++) {
            for (int gap : new int[] { 1, 2 }) {
                int second = (first + gap) % 6;
                Set<Coords> land = Set.of(CENTER, CENTER.translated(first), CENTER.translated(second));
                BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
                BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                assertStraightSamples(surface, CENTER);
                Vector3 center = BoardGeometry.center(CENTER, 0);
                for (int e = 0; e < 6; e++) {
                    if (land.contains(CENTER.translated(BoardGeometry.edgeDirection(e)))) { continue; }
                    Vector3 a = moved(surface, CENTER, e), b = moved(surface, CENTER, (e + 1) % 6);
                    boolean parallel = false;
                    for (int direction : new int[] { first, second }) {
                        Vector3 axis = BoardGeometry.center(CENTER.translated(direction), 0).sub(center).nor();
                        float ca = (a.x - center.x) * -axis.y + (a.y - center.y) * axis.x;
                        float cb = (b.x - center.x) * -axis.y + (b.y - center.y) * axis.x;
                        parallel |= Math.abs(ca - cb) < .002f && Math.abs(Math.abs(ca) / BoardGeometry.HEX_SCALE - 21) < .2f;
                    }
                    assertTrue(parallel, "Each angled bank follows a rectangular arm: " + first + ", " + second + ", edge " + e);
                }
                assertUnfolded(surface, "Angled dock");
                for (int d = 0; d < 6; d++) {
                    Coords at = CENTER.translated(d);
                    if (land.contains(at)) { continue; }
                    BoardSurface water = new BoardSurface(scene, scene.tile(at));
                    assertWaterCentre(water, at);
                    assertUnfolded(water, "Water beside an angled dock");
                }
            }
        }
    }

    private static Vector3 moved(BoardSurface surface, Coords coords, int k) {
        float[] shift = surface.relief.shoreShift(k);
        return BoardGeometry.corner(coords, 0, k).add(shift[0], shift[1], 0);
    }

    private static void assertStraightSamples(BoardSurface surface, Coords coords) {
        for (int e = 0; e < 6; e++) {
            Vector3 a = moved(surface, coords, e), b = moved(surface, coords, (e + 1) % 6);
            for (int s = 0; s <= 12; s++) {
                Vector3 expected = new Vector3(a).lerp(b, s / 12f);
                Vector3 actual = surface.relief.seam(e, e, s / 12f);
                assertEquals(expected.x, actual.x, .001f, "Every rendered concrete edge sample lies on its straight side");
                assertEquals(expected.y, actual.y, .001f);
            }
        }
    }

    @Test
    void neighboringConcreteBanksJoinInAStraightLineWhenTheWaterCentreIsClear() {
        var original = BoardRelief.tuning();
        try {
            for (float width : new float[] { .05f, .5f, 1 }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene scene = scene(Set.of(CENTER.translated(direction), CENTER.translated((direction + 1) % 6)),
                          BoardScene.Surface.CONCRETE, false);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                    int edge = Math.floorMod(-direction, 6), n = BoardSurface.SHORE_SEGMENTS;
                    Vector3 a = surface.outline.get(edge * n), b = surface.outline.get((edge + 2) % 6 * n);
                    for (int i = 1; i < 2 * n; i++) {
                        Vector3 p = surface.outline.get((edge * n + i) % (6 * n));
                        assertEquals(0, distance(a, b, p), .002f, "Straight concrete edge, direction=" + direction);
                    }
                    assertWaterCentre(surface);
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void longConcreteQuayHasNoHexByHexDentsBesideItsBuildings() {
        BoardScene scene = GpuRiverTerrainSmokeTest.quayScene(BoardScene.Surface.CONCRETE);
        float line = Float.NaN;
        for (int y = 4; y <= 7; y++) {
            BoardSurface water = new BoardSurface(scene, scene.tile(new Coords(3, y)));
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int i = 0; i <= 2 * n; i++) {
                Vector3 p = water.outline.get((5 * n + i) % (6 * n));
                if (Float.isNaN(line)) { line = p.x; }
                assertEquals(line, p.x, .002f, "One continuous constructed edge at row " + y);
            }
        }
    }

    @Test
    void concreteNeverClosesAnInletAcrossItsWaterCentreOrFoldsItsBed() {
        for (int mask = 0; mask < 64; mask++) {
            Set<Coords> land = new HashSet<>();
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & 1 << direction) != 0) { land.add(CENTER.translated(direction)); }
            }
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertWaterCentre(surface);
            assertUnfolded(surface, "Unfolded concrete shore, mask=" + mask);
        }
    }

    @Test
    void buildingsUsingTheConcreteEngineKeepTheirWholeFoundationAndConcreteBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, true);
        assertTrue(land.stream().allMatch(coords -> scene.tile(coords).detailedGround()));
        BoardSurface water = new BoardSurface(scene, scene.tile(CENTER));
        assertEquals(BoardScene.Surface.CONCRETE.ordinal(), water.relief.family(),
              "The building's ground and adjoining bank remain concrete");
        for (Coords coords : land) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            Vector3 center = BoardGeometry.center(coords, 0);
            for (int k = 0; k < 6; k++) {
                Vector3 corner = BoardGeometry.corner(coords, 0, k);
                assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .0001f,
                      "No recession into a concrete building hex");
                for (float radius : new float[] { 0, .25f, .5f, .75f, .99f }) {
                    Vector3 p = new Vector3(center).lerp(corner, radius);
                    assertEquals(0, BoardSurface.sampleHeight(surface.faces, p.x, p.y, Float.NaN), .001f,
                          "The complete foundation stays at its original level");
                }
            }
        }
    }

    @Test
    void naturalMaterialsKeepTheirOwnCurvedBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            if (family == BoardScene.Surface.CONCRETE) { continue; }
            BoardScene scene = scene(land, family, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertEquals(family.ordinal(), surface.relief.family());
            int n = BoardSurface.SHORE_SEGMENTS;
            Vector3 a = surface.outline.get(5 * n), b = surface.outline.get(n);
            float bend = 0;
            for (int i = 1; i < 2 * n; i++) {
                bend = Math.max(bend, distance(a, b, surface.outline.get((5 * n + i) % (6 * n))));
            }
            assertTrue(bend > BoardGeometry.HEX_SCALE, "Natural banks still curve: " + family);
        }
    }

    private static float distance(Vector3 a, Vector3 b, Vector3 p) {
        return Math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x))
              / (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private static void assertWaterCentre(BoardSurface surface) {
        assertWaterCentre(surface, CENTER);
    }

    private static void assertWaterCentre(BoardSurface surface, Coords coords) {
        Vector3 center = BoardGeometry.center(coords, 0);
        for (int angle = 0; angle < 12; angle++) {
            float x = center.x + 8 * BoardGeometry.HEX_SCALE * (float) Math.cos(angle * Math.PI / 6);
            float y = center.y + 8 * BoardGeometry.HEX_SCALE * (float) Math.sin(angle * Math.PI / 6);
            assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.waterFaces, x, y, Float.NaN)),
                  "A water unit's footprint remains in water");
        }
    }

    private static void assertUnfolded(BoardSurface surface, String message) {
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP) { continue; }
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            assertTrue(normal.z >= -.001f * normal.len(), () -> message + " " + face);
        }
    }

    private static BoardScene scene(Set<Coords> land, BoardScene.Surface family, boolean buildings) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean dry = land.contains(coords);
                List<BoardScene.Feature> features = dry && buildings
                      ? List.of(new BoardScene.Feature("building", 0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                tiles.add(new BoardScene.Tile(coords, 0, dry ? -1 : 2, false, 0,
                      dry ? family : BoardScene.Surface.GRASS, null, null, null, null, null, features, List.of(),
                      dry ? BoardLiquid.NONE : BoardLiquid.WATER, null,
                      features.isEmpty() || family == BoardScene.Surface.CONCRETE));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
