/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/** The rock kit: closed library solids, rim formations scaled by drop, fallen rock below, anchors kept clear. */
class BoardRocksTest {
    /** A three-level mesa beside open ground, and a one-level step elsewhere. */
    private static final String[] LAYOUT = {
          "0000000",
          "0033000",
          "0033300",
          "0003000",
          "0000011",
          "0000011",
    };

    private static BoardScene scene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < LAYOUT[0].length(); x++) {
            for (int y = 0; y < LAYOUT.length; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), LAYOUT[y].charAt(x) - '0', -1, false, 0, family, null,
                      null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, LAYOUT[0].length(), LAYOUT.length, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private record Key(long x, long y, long z) {
        static Key of(Vector3 p) {
            return new Key(Math.round(p.x * 1e5), Math.round(p.y * 1e5), Math.round(p.z * 1e5));
        }
    }

    @Test
    void libraryRocksAreClosedOutwardSolids() {
        // Blocks, boulders and the masses of shrubs.
        for (int kind = 0; kind < 3; kind++) {
            int count = kind == 0 ? BoardRocks.BLOCKS : kind == 1 ? BoardRocks.BOULDERS : BoardRocks.BUSHES;
            for (int variant = 0; variant < count; variant++) {
                BoardRocks.Rock rock = kind == 2 ? BoardRocks.bush(variant) : BoardRocks.rock(kind == 0, variant);
                Map<List<Key>, Integer> edges = new HashMap<>();
                double volume = 0;
                int triangles = 0;
                for (BoardRocks.Polygon polygon : rock.polygons()) {
                    Vector3[] p = polygon.points();
                    for (int i = 0; i < p.length; i++) {
                        assertTrue(Float.isFinite(p[i].x) && Float.isFinite(p[i].y) && Float.isFinite(p[i].z));
                        assertTrue(Math.abs(p[i].x) <= .5001f && Math.abs(p[i].y) <= .5001f && p[i].z >= -.0001f);
                        edges.merge(List.of(Key.of(p[i]), Key.of(p[(i + 1) % p.length])), 1, Integer::sum);
                    }
                    for (int i = 1; i + 1 < p.length; i++) {
                        volume += p[0].dot(new Vector3(p[i]).crs(p[i + 1])) / 6.0;
                        triangles++;
                    }
                }
                for (List<Key> edge : edges.keySet()) {
                    assertEquals(1, edges.getOrDefault(List.of(edge.get(1), edge.get(0)), 0),
                          "Each directed edge meets its reverse exactly once: a closed, consistently wound solid");
                }
                assertTrue(volume > .02, "Faces wind outward and enclose a real volume");
                assertTrue(triangles <= 120, "A rock keeps a small triangle budget");
                assertTrue(kind != 2 || triangles <= 48, "A shrub's many masses each cost well under a boulder");
                assertTrue(rock.height() > .2f && rock.height() < 1.2f);
            }
        }
    }

    private static List<BoardSurface.Face> rocks(BoardScene scene, Coords coords) {
        return new BoardSurface(scene, scene.tile(coords)).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).toList();
    }

    private static float tallest(List<BoardSurface.Face> faces, float level) {
        float height = 0;
        for (BoardSurface.Face face : faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) { height = Math.max(height, p.z - level); }
        }
        return height;
    }

    @Test
    void deepRimsCarryMoreProminentFormationsThanShallowSteps() {
        // Without hex transitions, whose slopes shed fewer rim formations.
        BoardSculptTest.withTransitions(false, this::compareRims);
    }

    private void compareRims() {
        BoardScene scene = scene(BoardScene.Surface.SAND);
        float deep = 0, shallow = 0;
        int deepRocks = 0, shallowRocks = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            List<BoardSurface.Face> faces = rocks(scene, tile.coords());
            if (tile.elevation() == 3) {
                deep = Math.max(deep, tallest(faces, 3 * BoardGeometry.LEVEL));
                deepRocks += faces.size();
            } else if (tile.elevation() == 1) {
                shallow = Math.max(shallow, tallest(faces, BoardGeometry.LEVEL));
                shallowRocks += faces.size();
            }
        }
        assertTrue(deepRocks > shallowRocks, "Deep rims have more formations: " + deepRocks + " vs " + shallowRocks);
        assertTrue(deep > shallow * 1.3f, "Deep rim formations stand taller: " + deep + " vs " + shallow);
    }

    @Test
    void rocksKeepAnchorsClearStayInBoundsAndMatchPickingAndSupport() {
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            BoardScene scene = scene(family);
            float floor = BoardGeometry.floor(scene);
            int rocks = 0;
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
                assertEquals(center.z, surface.height(center.x, center.y), .001f, "Rocks never lift a unit's anchor");
                BoardSurface.Face highest = null;
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                    rocks++;
                    if (family == BoardScene.Surface.CONCRETE) {
                        assertTrue(belowSlab(scene, tile), "Paved rims and open slabs stay clear; only the bedrock under"
                              + " a slab sheds rubble: " + tile.coords());
                    }
                    for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                        assertTrue(Math.hypot(p.x - center.x, p.y - center.y) > BoardGeometry.WIDTH * .2f,
                              "The standing area stays clear");
                        assertTrue(p.z <= center.z + BoardRelief.headroom(tile) + .001f, "Picking bounds hold rocks");
                        assertTrue(p.z >= floor);
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    if (normal.z > .5f && (highest == null || centroid(face).z > centroid(highest).z)) { highest = face; }
                }
                if (highest == null || highest.a().z < center.z + .01f) { continue; }
                Vector3 point = centroid(highest);
                Ray ray = new Ray(new Vector3(point.x, point.y, center.z + BoardGeometry.WIDTH), new Vector3(0, 0, -1));
                BoardGeometry.Hit hit = BoardGeometry.hit(scene, ray, List.of(tile), floor);
                assertNotNull(hit);
                float distance = ray.origin.z - surface.height(point.x, point.y);
                assertEquals(distance * distance, hit.distance(), .05f, "Picking and support meet the same rock");
            }
            if (family != BoardScene.Surface.CONCRETE) {
                assertTrue(rocks > 100, family + " has rim and fallen rock");
            } else {
                assertTrue(rocks > 0, "The bedrock under a concrete slab sheds rubble");
            }
        }
    }

    /** Whether a hex lies at the foot of a cliff of three levels or more. */
    private static boolean belowSlab(BoardScene scene, BoardScene.Tile tile) {
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor != null && neighbor.elevation() - tile.elevation() >= 3) { return true; }
        }
        return false;
    }

    private static Vector3 centroid(BoardSurface.Face face) {
        return new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
    }

    @Test
    void placementsAreCanonicalAndNeverMutateTheLibrary() {
        List<Vector3> before = new ArrayList<>();
        BoardRocks.rock(true, 0).polygons().forEach(polygon -> before.addAll(List.of(polygon.points())));
        List<Vector3> copy = before.stream().map(Vector3::cpy).toList();
        BoardScene scene = scene(BoardScene.Surface.ROCK);
        for (BoardScene.Tile tile : scene.tiles()) {
            List<BoardSurface.Face> first = rocks(scene, tile.coords()), second = rocks(scene, tile.coords());
            assertEquals(first.size(), second.size());
            for (int i = 0; i < first.size(); i++) {
                assertEquals(first.get(i).a(), second.get(i).a(), "The same board always yields the same rocks");
            }
        }
        assertEquals(copy, before, "Placement transforms copies, never the shared unit rocks");
    }
}
