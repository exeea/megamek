/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardRiverTest {
    @Test
    void narrowStreamsKeepUnitsInWaterAndMeetAcrossBends() {
        var original = BoardRelief.tuning();
        try {
            Coords center = new Coords(3, 3);
            for (float width : new float[] { 1, .5f, .05f }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                for (int depth : new int[] { 0, 1 }) {
                    for (int from : new int[] { 0, 2, 4 }) {
                        for (int turn : new int[] { 2, 3 }) {
                            int to = (from + turn) % 6;
                            BoardScene scene = scene(Map.of(center, depth, center.translated(from), depth,
                                  center.translated(to), depth));
                            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                            String label = "width=" + width + ", depth=" + depth + ", from=" + from + ", turn=" + turn;
                            for (Vector3 point : surface.outline) {
                                assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y), "Finite shore: " + label);
                            }
                            Vector3 p = BoardGeometry.center(center, 0);
                            assertEquals(BoardGeometry.groundZ(scene.tile(center)), surface.height(p.x, p.y), .001f, label);
                            if (depth > 0) {
                                for (int a = 0; a < 12; a++) {
                                    float x = p.x + 8 * BoardGeometry.HEX_SCALE * (float) Math.cos(a * Math.PI / 6);
                                    float y = p.y + 8 * BoardGeometry.HEX_SCALE * (float) Math.sin(a * Math.PI / 6);
                                    assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.waterFaces, x, y,
                                          Float.NEGATIVE_INFINITY)), "Unit footprint stays in water: " + label);
                                }
                            }
                            for (var face : surface.faces) {
                                if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP
                                      && face.finish() != BoardSurface.Finish.SHORE) { continue; }
                                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                                assertTrue(normal.z >= -.001f * normal.len(), "No folded ground: " + label + ": " + face);
                            }
                            for (int direction : new int[] { from, to }) {
                                BoardSurface next = new BoardSurface(scene, scene.tile(center.translated(direction)));
                                int e = Math.floorMod(1 - direction, 6), opposite = (e + 3) % 6, n = BoardSurface.SHORE_SEGMENTS;
                                for (int i = 0; i <= n; i++) {
                                    Vector3 a = surface.outline.get((e * n + i) % (6 * n));
                                    Vector3 b = next.outline.get(((opposite + 1) * n - i) % (6 * n));
                                    assertTrue(a.epsilonEquals(b, .002f), "Shared mouth: " + label + " at " + i);
                                }
                                float opening = surface.outline.get(e * n).dst(surface.outline.get((e + 1) * n % (6 * n)));
                                assertTrue(opening > .3f * BoardGeometry.HEX_SCALE, "Connected stream stays open: " + label);
                                if (width == .05f && depth == 0) {
                                    assertTrue(opening < 8 * BoardGeometry.HEX_SCALE, "Minimum is a rivulet: " + label + ": " + opening);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void minimumWidthHandlesEveryRiverJunctionWithoutMovingTheUnitAnchor() {
        var original = BoardRelief.tuning();
        try {
            GpuRiverTerrainSmokeTest.setWidth(.05f);
            Coords center = new Coords(3, 3);
            for (int mask = 0; mask < 64; mask++) {
                Map<Coords, Integer> water = new HashMap<>(Map.of(center, 1));
                for (int d = 0; d < 6; d++) {
                    if ((mask & 1 << d) != 0) { water.put(center.translated(d), 1); }
                }
                BoardScene scene = scene(water);
                BoardSurface surface = new BoardSurface(scene, scene.tile(center));
                Vector3 p = BoardGeometry.center(center, 0);
                assertEquals(BoardGeometry.groundZ(scene.tile(center)), surface.height(p.x, p.y), .001f, "Mask " + mask);
                for (Vector3 point : surface.outline) {
                    assertTrue(Float.isFinite(point.x) && Float.isFinite(point.y), "Finite junction " + mask);
                }
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP) { continue; }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z >= -.001f * normal.len(), "Junction bank faces up: " + mask);
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void aNarrowStreamSharesItsMouthWithWaterUsingSpecialArtwork() {
        var original = BoardRelief.tuning();
        try {
            GpuRiverTerrainSmokeTest.setWidth(.05f);
            Coords center = new Coords(3, 3), north = center.translated(0);
            BoardScene initial = scene(Map.of(center, 1, north, 1, center.translated(3), 1));
            List<BoardScene.Tile> tiles = initial.tiles().stream().map(t -> new BoardScene.Tile(t.coords(), t.elevation(),
                  t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(), t.normals(), t.decals(), t.decalsWithoutLimbs(),
                  t.tactical(), t.features(), t.text(), t.liquid(), t.foliage(), !t.coords().equals(north))).toList();
            BoardScene scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
            BoardSurface a = new BoardSurface(scene, scene.tile(center)), b = new BoardSurface(scene, scene.tile(north));
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int i = 0; i <= n; i++) {
                assertTrue(a.outline.get(n + i).epsilonEquals(b.outline.get(5 * n - i), .002f));
            }
            Vector3 p = BoardGeometry.center(north, 0);
            assertEquals(BoardGeometry.groundZ(scene.tile(north)), b.height(p.x, p.y), .001f);
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void continuingRiverHexesChangeTheBendBeforeThem() {
        Coords a = new Coords(3, 4), b = a.translated(0), c = b.translated(0);
        BoardScene straight = scene(Map.of(a, 1, b, 1, c, 1));
        BoardScene turning = scene(Map.of(a, 1, b, 1, b.translated(1), 1));
        float x = BoardGeometry.centerX(a), y = (BoardGeometry.centerY(a) + BoardGeometry.centerY(b)) / 2;
        float left = new BoardRiver(straight, BoardRelief.tuning()).field(x - 15, y);
        float curved = new BoardRiver(turning, BoardRelief.tuning()).field(x - 15, y);
        assertTrue(Math.abs(left - curved) > .5f, "The following hex changes the approaching stream's direction");
    }

    @Test
    void widthChangesTheWholeChannelWithoutPinchingAtHexEdges() {
        var original = BoardRelief.tuning();
        try {
            for (int depth : new int[] { 0, 1 }) {
                Map<Coords, Integer> water = new HashMap<>();
                for (int y = 0; y < 7; y++) { water.put(new Coords(3, y), depth); }
                BoardScene scene = scene(water);
                float[] previous = new float[33];
                for (float width : new float[] { .05f, .25f, .5f, 1 }) {
                    GpuRiverTerrainSmokeTest.setWidth(width);
                    List<BoardSurface> surfaces = water.keySet().stream()
                          .map(c -> new BoardSurface(scene, scene.tile(c))).toList();
                    float from = BoardGeometry.centerY(new Coords(3, 1)), to = BoardGeometry.centerY(new Coords(3, 5));
                    float narrowest = Float.POSITIVE_INFINITY, widest = 0;
                    for (int i = 0; i < previous.length; i++) {
                        float y = from + (to - from) * i / (previous.length - 1);
                        float[] section = section(surfaces, y);
                        float size = section[1] - section[0];
                        String label = "depth=" + depth + ", width=" + width + ", sample=" + i;
                        assertTrue(size > previous[i], "Widen the entire channel: " + label);
                        narrowest = Math.min(narrowest, size);
                        widest = Math.max(widest, size);
                        previous[i] = size;
                    }
                    assertTrue(narrowest > .65f * widest,
                          "No sequence of pools and pinched crossings: depth=" + depth + ", width=" + width
                                + ", narrowest=" + narrowest + ", widest=" + widest);
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void narrowStraightRunsWanderSidewaysAcrossHexes() {
        var original = BoardRelief.tuning();
        try {
            Map<Coords, Integer> water = new HashMap<>();
            for (int y = 0; y < 7; y++) { water.put(new Coords(3, y), 1); }
            BoardScene scene = scene(water);
            for (float width : new float[] { .5f, .05f }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                List<BoardSurface> surfaces = water.keySet().stream()
                      .map(c -> new BoardSurface(scene, scene.tile(c))).toList();
                float from = BoardGeometry.centerY(new Coords(3, 1)), to = BoardGeometry.centerY(new Coords(3, 5));
                float[] middle = new float[65];
                for (int i = 0; i < middle.length; i++) {
                    float y = from + (to - from) * i / (middle.length - 1);
                    float[] section = section(surfaces, y);
                    middle[i] = (section[0] + section[1]) / 2;
                }
                float bend = 0;
                for (int i = 1; i < middle.length - 1; i++) {
                    float straight = middle[0] + (middle[middle.length - 1] - middle[0]) * i / (middle.length - 1);
                    bend = Math.max(bend, Math.abs(middle[i] - straight));
                }
                assertTrue(bend > (width == .05f ? .2f : 1.5f) * BoardGeometry.HEX_SCALE,
                      "The centreline bends, rather than only changing width: " + width + ", bend " + bend);
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static float[] section(List<BoardSurface> surfaces, float y) {
        float left = Float.POSITIVE_INFINITY, right = Float.NEGATIVE_INFINITY;
        for (BoardSurface surface : surfaces) {
            for (int j = 0; j < surface.outline.size(); j++) {
                Vector3 a = surface.outline.get(j), b = surface.outline.get((j + 1) % surface.outline.size());
                if ((a.y > y) == (b.y > y)) { continue; }
                float x = a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y);
                left = Math.min(left, x);
                right = Math.max(right, x);
            }
        }
        assertTrue(Float.isFinite(left) && right > left, "The stream stays connected at " + y);
        return new float[] { left, right };
    }

    @Test
    void riverJunctionsHaveRoundedInsideCorners() {
        var original = BoardRelief.tuning();
        try {
            GpuRiverTerrainSmokeTest.setWidth(.5f);
            Coords center = new Coords(3, 3);
            Map<Coords, Integer> water = new HashMap<>(Map.of(center, 1));
            for (int d : new int[] { 0, 2, 4 }) {
                water.put(center.translated(d), 1);
                water.put(center.translated(d, 2), 1);
            }
            BoardScene scene = scene(water);
            List<Vector3> outline = new BoardSurface(scene, scene.tile(center)).outline;
            float sharpest = 0;
            for (int i = 0; i < outline.size(); i++) {
                Vector3 a = new Vector3(outline.get(i)).sub(outline.get((i + outline.size() - 1) % outline.size()));
                Vector3 b = new Vector3(outline.get((i + 1) % outline.size())).sub(outline.get(i));
                float turn = (float) Math.atan2(a.x * b.y - a.y * b.x, a.x * b.x + a.y * b.y);
                sharpest = Math.max(sharpest, -turn);
            }
            assertTrue(sharpest < Math.PI / 4, "No sharp land point between the branches: " + Math.toDegrees(sharpest));
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void depthZeroCanExposeSandButDeepWaterKeepsItsBedSubmerged() {
        Coords center = new Coords(3, 3);
        for (int depth : new int[] { 0, 1 }) {
            BoardScene scene = scene(Map.of(center, depth, center.translated(0), depth, center.translated(3), depth));
            BoardSurface surface = new BoardSurface(scene, scene.tile(center));
            float highest = Float.NEGATIVE_INFINITY;
            for (var face : surface.faces) {
                if (face.finish() != BoardSurface.Finish.BED) { continue; }
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) { highest = Math.max(highest, p.z); }
            }
            float water = BoardGeometry.waterZ(scene.tile(center));
            if (depth == 0) { assertTrue(highest > water, "Shallows have visible sand bars"); }
            else { assertTrue(highest <= water, "Deep-water beds stay under water"); }
        }
    }

    @Test
    void shallowBarsJoinTheBankAndTheirExposedEdgesDefineTheWaterline() {
        Coords center = new Coords(3, 3);
        BoardScene scene = scene(Map.of(center, 0, center.translated(0), 0, center.translated(3), 0));
        BoardSurface surface = new BoardSurface(scene, scene.tile(center));
        List<BoardSurface.Face> bank = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
        float water = BoardGeometry.waterZ(surface.tile);
        List<Float> edges = new ArrayList<>();
        int joins = 0;
        for (var face : surface.faces) {
            if (face.finish() != BoardSurface.Finish.BED) { continue; }
            GpuWaterShader.waterline(edges, surface, face);
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.z <= water + .1f || surface.outline.stream().noneMatch(q -> Math.hypot(p.x - q.x, p.y - q.y) < .001f)) {
                    continue;
                }
                assertEquals(p.z, BoardSurface.sampleHeight(bank, p.x, p.y, Float.NaN), .002f,
                      "Emerging sand joins the bank above the former shoreline");
                assertNotNull(surface.relief.shade(p), "The bed and bank share their lighting normal");
                joins++;
            }
        }
        assertTrue(joins > 0, "Sand bars reach the dry bank without an artificial groove");
        assertFalse(edges.isEmpty(), "Exposed sand has its own waterline");
        assertEquals(0, edges.size() % 4, "Every contour is a complete segment");
        for (int i = 0; i < edges.size(); i += 2) {
            assertEquals(water, surface.height(edges.get(i), edges.get(i + 1)), .003f,
                  "Foam follows the ground's actual intersection with water");
        }
    }

    private static BoardScene scene(Map<Coords, Integer> water) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int depth = water.getOrDefault(coords, -1);
                tiles.add(new BoardScene.Tile(coords, depth >= 0 ? 0 : 1, depth, false, 0, BoardScene.Surface.SAND,
                      null, null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
