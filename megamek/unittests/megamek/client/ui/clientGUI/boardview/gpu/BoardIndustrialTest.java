/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardIndustrialTest {
    static final Coords CENTER = new Coords(4, 4);

    static BoardScene scene(Map<Coords, Integer> sites, int height, int neighborGround) {
        return BoardSurfaceBlendTest.scene(coords -> {
            int elevation = coords.equals(CENTER) ? 0 : neighborGround;
            var tile = BoardSurfaceBlendTest.tile(coords, BoardScene.Surface.CONCRETE, elevation, -1, 0);
            Hex hex = new Hex(elevation);
            hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, height));
            String asset = "buildings/saxarba/misc/heavy_industrial_" + (char) ('a' + sites.getOrDefault(coords, 0));
            return new BoardScene.Tile(coords, elevation, -1, false, 0, tile.surface(), tile.ground(), null, null, null, null,
                  sites.containsKey(coords) ? BoardFeatures.capture(hex, coords, Map.of(Terrains.INDUSTRIAL, asset)) : List.of(),
                  List.of(), BoardLiquid.NONE, null, true);
        });
    }

    static BoardIndustrial.Layout layout(BoardScene scene, Coords coords) {
        var tile = scene.tile(coords);
        return BoardIndustrial.layout(scene, tile, tile.features().getFirst());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3 })
    void eachFamilyHasUnequalEquipmentHeightsExactCoverHeightAndOpenGround(int family) {
        for (int height : new int[] { 1, 7, 10 }) {
            var scene = scene(Map.of(CENTER, family), height, 0);
            var layout = layout(scene, CENTER);
            assertEquals(layout, layout(scene(Map.of(CENTER, family), height, 0), CENTER));
            assertTrue(layout.ports().isEmpty(), "An isolated installation has no outgoing pipe stubs");
            assertEquals(layout.equipment().size(), layout.equipment().stream().map(BoardIndustrial.Equipment::height).distinct().count());
            var points = BoardIndustrial.triangles(layout);
            var bounds = new BoundingBox().inf();
            points.forEach(bounds::ext);
            assertEquals(0, bounds.min.z, .001f);
            assertEquals(height * 18, bounds.max.z, .001f, "Caps count toward cover height, not extra storeys");
            for (float x : new float[] { -5, 0, 5 }) {
                for (float y : new float[] { -12, 0, 12 }) {
                    Vector3 hit = new Vector3();
                    assertFalse(Intersector.intersectRayTriangles(new Ray(new Vector3(x, y, 300), new Vector3(0, 0, -1)),
                          points, hit), "The central ground passage must remain empty: family " + family + ", height "
                                + height + ", ray " + x + "," + y + ", hit " + hit);
                }
            }
            if (family == 1) {
                assertEquals(Set.of(BoardIndustrial.Cap.DOME, BoardIndustrial.Cap.CONE, BoardIndustrial.Cap.FLAT),
                      layout.equipment().stream().map(BoardIndustrial.Equipment::cap).collect(java.util.stream.Collectors.toSet()));
            }
            if (height >= 7) {
                assertTrue(layout.connections().stream().map(BoardIndustrial.Connection::height).distinct().count() >= 2,
                      "Equipment connects at different heights inside the installation");
                for (var connection : layout.connections()) {
                    assertTrue(connection.height() < Math.min(connection.from().height(), connection.to().height()));
                    Vector3 hit = new Vector3();
                    assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(0, connection.routeY() - 3, connection.height()),
                          new Vector3(0, 1, 0)), points, hit), "The planned elevated cross-pipe exists in the actual mesh");
                    assertEquals(connection.routeY() - .85f, hit.y, .03f);
                }
            }
        }
    }

    @Test
    void neighborsAgreeOnEveryConnectionAndRemovalRetiresOnlyThePipe() {
        for (int direction = 0; direction < 6; direction++) {
            Coords next = CENTER.translated(direction);
            var scene = scene(Map.of(CENTER, 1, next, 2), 7, direction % 3);
            var first = layout(scene, CENTER);
            var second = layout(scene, next);
            assertEquals(2, first.ports().size());
            assertEquals(2, second.ports().size());
            assertTrue(first.ports().getLast().z() - first.ports().getFirst().z() > 12,
                  "Tall neighbors have two clearly separated connection heights");
            for (int index = 0; index < first.ports().size(); index++) {
                Vector3 a = world(scene, CENTER, first.ports().get(index));
                Vector3 b = world(scene, next, second.ports().get(index));
                assertEquals(0, a.dst(b), .001f, "Opposite pipe ends meet across different equipment and ground heights");
            }
            for (var entry : Map.of(CENTER, first, next, second).entrySet()) {
                for (var p : entry.getValue().ports()) {
                    Vector3 outward = new Vector3(p.x(), p.y(), 0).nor();
                    Vector3 end = new Vector3(p.x(), p.y(), p.z()), hit = new Vector3();
                    assertTrue(Intersector.intersectRayTriangles(new Ray(new Vector3(end).mulAdd(outward, 2), outward.scl(-1)),
                          BoardIndustrial.triangles(entry.getValue()), hit));
                    assertEquals(0, hit.dst(end), .02f, "The actual mesh reaches the agreed joint");
                }
            }
            var isolated = layout(scene(Map.of(CENTER, 1), 7, 0), CENTER);
            assertEquals(first.equipment(), isolated.equipment(), "Neighbor edits do not rearrange machinery");
            assertEquals(first.connections(), isolated.connections());
            assertTrue(isolated.ports().isEmpty());
        }
        assertTrue(layout(scene(Map.of(CENTER, 1, CENTER.translated(1), 2), 1, 3), CENTER).ports().isEmpty(),
              "Connections never extend above the permitted terrain height to bridge a steep cliff");
    }

    @Test
    void crowdedTallInstallationsStayWithinTheRigidMeshBudgetAndVaryTheirPortsByNeighbor() {
        for (int family = 0; family < 4; family++) {
            var sites = new java.util.HashMap<Coords, Integer>();
            sites.put(CENTER, family);
            for (int direction = 0; direction < 6; direction++) { sites.put(CENTER.translated(direction), (family + direction) % 4); }
            var layout = layout(scene(sites, 30, 0), CENTER);
            assertEquals(12, layout.ports().size());
            assertTrue(layout.ports().stream().map(BoardIndustrial.Port::z).distinct().count() > 6,
                  "Connections are chosen per neighbor, not one common floor around the hex");
            assertTrue(BoardIndustrial.model(layout).meshes.first().vertices.length / RigidGlb.STRIDE <= 65_535);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3 })
    void equipmentTopsHaveOneVisibleSurfaceWithoutCoplanarOverlays(int family) {
        for (int height : new int[] { 1, 7 }) {
            float[] vertices = BoardIndustrial.model(layout(scene(Map.of(CENTER, family), height, 0), CENTER)).meshes.first().vertices;
            // Jitter avoids triangulation edges. Test actual uppermost surfaces, including shallow fan recesses.
            for (float x = -34.713f; x < 34; x += 1.31f) {
                for (float y = -30.319f; y < 31; y += 1.27f) {
                    float top = Float.NEGATIVE_INFINITY;
                    int count = 0;
                    for (int i = 0; i < vertices.length; i += 3 * RigidGlb.STRIDE) {
                        float ax = vertices[i], ay = vertices[i + 1], bx = vertices[i + 12], by = vertices[i + 13],
                              cx = vertices[i + 24], cy = vertices[i + 25];
                        float area = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
                        if (area <= .00001f) { continue; }
                        float a = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / area;
                        float b = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / area;
                        if (a <= .00001f || b <= .00001f || a + b >= .99999f) { continue; }
                        float z = a * vertices[i + 2] + b * vertices[i + 14] + (1 - a - b) * vertices[i + 26];
                        if (z > top + .005f) { top = z; count = 1; }
                        else if (Math.abs(z - top) < .005f) { count++; }
                    }
                    assertTrue(count <= 1, "Overlapping visible top faces: family " + family + ", height " + height
                          + ", position " + x + "," + y + ", z " + top + ", count " + count);
                }
            }
        }
    }

    private static Vector3 world(BoardScene scene, Coords coords, BoardIndustrial.Port port) {
        return BoardGeometry.center(coords, 0).add(port.x() * BoardGeometry.hexScale(), port.y() * BoardGeometry.hexScale(),
              BoardGeometry.groundZ(scene.tile(coords)) + port.z() * BoardGeometry.level() / 18);
    }
}
