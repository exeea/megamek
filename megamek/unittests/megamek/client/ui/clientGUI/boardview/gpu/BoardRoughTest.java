/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardRoughTest {
    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void fluffSelectsConcreteRowsOrFallenWoodAtBothRoughLevels(int fluff) {
        Coords coords = new Coords(2, 2);
        int count = 0;
        for (int level : new int[] { 1, 2 }) {
            Hex hex = hex(fluff, level);
            var features = BoardFeatures.capture(hex, coords, Map.of());
            assertTrue(features.size() > count, "Ultra rough has denser obstacles");
            count = features.size();
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.ROUGH));
            assertEquals(fluff == 1 ? Set.of("rough/dragon-tooth")
                        : Set.of("rough/felled-trunk", "rough/charred-stump", "rough/fallen-stump"),
                  new HashSet<>(features.stream().map(BoardScene.Feature::asset).toList()));
            assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            if (fluff == 1) {
                assertEquals(1, features.stream().map(BoardScene.Feature::rotation).distinct().count());
                var rows = features.stream().map(BoardScene.Feature::y).distinct().sorted().toList();
                for (int row = 1; row < rows.size(); row++) {
                    float previousY = rows.get(row - 1), currentY = rows.get(row);
                    var previous = features.stream().filter(f -> f.y() == previousY)
                          .map(BoardScene.Feature::x).sorted().toList();
                    var current = features.stream().filter(f -> f.y() == currentY).map(BoardScene.Feature::x).toList();
                    assertEquals(5, previous.size());
                    assertEquals(5, current.size());
                    for (float x : current) {
                        assertEquals((previous.get(1) - previous.get(0)) / 2,
                              previous.stream().mapToDouble(p -> Math.abs(p - x)).min().orElseThrow(), .001,
                              "Adjacent rows put their teeth halfway between each other's teeth");
                    }
                }
            }
            hex.removeTerrain(Terrains.ROUGH);
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream().noneMatch(f -> f.kind() == BoardScene.FeatureKind.ROUGH));
            assertFalse(BoardFeatures.detailedGround(hex, Map.of()), "Unrelated fluff keeps its original artwork");
        }
    }

    @Test
    void teethKeepIdenticalDimensionsAcrossRowsHexesAndRoughLevels() {
        Set<Float> widths = new HashSet<>(), heights = new HashSet<>();
        for (int level : new int[] { 1, 2 }) {
            for (Coords coords : List.of(new Coords(0, 0), new Coords(2, 2), new Coords(9, 7))) {
                for (var feature : BoardFeatures.capture(hex(1, level), coords, Map.of())) {
                    widths.add(feature.scale());
                    heights.add(feature.height());
                }
            }
        }
        assertEquals(1, widths.size(), "Concrete teeth share one width");
        assertEquals(1, heights.size(), "Rear rows and ultra rough must not grow taller teeth");
    }

    @Test
    void absentOrUnknownFluffKeepsOrdinaryBoulders() {
        for (int fluff : new int[] { 0, 3, 17 }) {
            var features = BoardFeatures.capture(hex(fluff, 1), new Coords(2, 2), Map.of());
            assertFalse(features.isEmpty());
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void variantsKeepRoadsAndBridgeApproachesClear(int fluff) {
        Coords coords = new Coords(2, 2);
        for (int type : new int[] { Terrains.ROAD, Terrains.BRIDGE }) {
            for (int exits : new int[] { 1, 9, 18, 21, 63 }) {
                Hex hex = hex(fluff, 2);
                hex.addTerrain(new Terrain(type, 1, true, exits));
                BoardRoad road = BoardRoad.clearance(coords, exits);
                List<BoardSurface.Face> deck = type == Terrains.BRIDGE ? bridgeDeck(exits) : List.of();
                for (var feature : BoardFeatures.capture(hex, coords, Map.of())) {
                    if (feature.kind() != BoardScene.FeatureKind.ROUGH) { continue; }
                    var shape = BoardShape.loadKit(feature.asset()).values().iterator().next();
                    double turn = Math.toRadians(feature.rotation());
                    for (var polygon : shape.polygons()) {
                        for (Vector3 p : polygon.points()) {
                            float x = feature.x() + feature.scale() * (float) (Math.cos(turn) * p.x - Math.sin(turn) * p.y);
                            float y = feature.y() + feature.scale() * (float) (Math.sin(turn) * p.x + Math.cos(turn) * p.y);
                            assertTrue(type == Terrains.ROAD ? road.distance(x, y) >= BoardRoad.SHOULDER
                                        : !Float.isFinite(BoardSurface.sampleHeight(deck, x, y, Float.NaN)),
                                  "Obstacle intersects route: type=" + type + " exits=" + exits + " " + feature
                                        + " at " + x + "," + y + " distance=" + road.distance(x, y));
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({ "1,0", "2,0", "1,1", "2,1" })
    void renderedModelsShareTheirTrianglesWithPickingAndFooting(int fluff, int step) {
        BoardScene scene = scene(fluff, step, false);
        Coords coords = new Coords(2, 2);
        BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
        assertFalse(surface.roughModels().isEmpty());
        if (fluff == 2 && step == 0) {
            assertTrue(surface.roughModels().stream().anyMatch(p -> p.asset().equals("rough/charred-stump")));
            assertTrue(surface.roughModels().stream().anyMatch(p -> p.asset().equals("rough/fallen-stump")),
                  "Level ground keeps both stump poses; an elevation edge may omit unsupported pieces");
        }
        Set<Vector3> rendered = new HashSet<>();
        for (var placement : surface.roughModels()) {
            for (var shape : BoardShape.loadKit(placement.asset()).values()) {
                for (var polygon : shape.polygons()) {
                    for (Vector3 point : polygon.points()) { rendered.add(new Vector3(point).mul(placement.transform())); }
                }
            }
        }
        Set<Vector3> support = new HashSet<>();
        int checked = 0;
        for (var face : surface.rough) {
            assertEquals(BoardSurface.Finish.ROUGH, face.finish());
            support.addAll(List.of(face.a(), face.b(), face.c()));
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
            if (normal.z < .5f) { continue; }
            Vector3 p = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
            float height = surface.height(p.x, p.y);
            assertTrue(height >= p.z - .001f);
            Ray ray = new Ray(new Vector3(p.x, p.y, 200), new Vector3(0, 0, -1));
            var hit = BoardGeometry.hit(scene, ray, List.of(scene.tile(coords)), BoardGeometry.floor(scene));
            assertNotNull(hit);
            assertEquals(coords, hit.coords());
            assertEquals(height, 200 - Math.sqrt(hit.distance()), .002,
                  "Ray intersection and barycentric height agree within float precision");
            assertEquals(height, UnitLandingSupports.ground(scene, p.x, p.y), .001f);
            checked++;
        }
        assertEquals(rendered, support, "Support uses the exact transformed authored mesh");
        assertTrue(checked > 0);
        assertTrue(surface.groundFaces().stream().noneMatch(f -> f.finish() == BoardSurface.Finish.ROUGH));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    void waterSnapshotRetainsVariantPlacementsAndSeparateGround(int fluff) {
        BoardScene scene = scene(fluff, 0, true);
        var tile = scene.tile(new Coords(2, 2));
        BoardSurface original = new BoardSurface(scene, tile);
        BoardSurface restored = original.waterGeometry().surface(scene);
        assertFalse(original.roughModels().isEmpty());
        assertEquals(original.roughModels(), restored.roughModels());
        assertEquals(original.rough, restored.rough);
        assertEquals(original.groundFaces(), restored.groundFaces());
    }

    @Test
    void horizontalRayCanHitTeethAboveThePavedGroundBounds() {
        BoardScene scene = scene(1, 0, false, BoardScene.Surface.CONCRETE);
        Coords coords = new Coords(2, 2);
        Vector3 origin = BoardGeometry.center(coords, 0).add(BoardGeometry.width(), 0, 3);
        var hit = BoardGeometry.hit(scene, new Ray(origin, new Vector3(-1, 0, 0)),
              List.of(scene.tile(coords)), BoardGeometry.floor(scene));
        assertNotNull(hit, "Picking bounds must include the obstacles above a flat slab");
        assertEquals(coords, hit.coords());
    }

    private static List<BoardSurface.Face> bridgeDeck(int exits) {
        File root = new File(Configuration.dataDir(), "models/board");
        var file = new FileHandle(new File(root, BoardBridge.asset(exits) + ".glb"));
        var data = RigidGlb.loadLods(file, root.toPath()).getFirst();
        List<BoardSurface.Face> faces = new ArrayList<>();
        // Board bridge nodes are identity transforms; use the same authored vertices as the renderer.
        for (var mesh : data.meshes) {
            for (var part : mesh.parts) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    Vector3[] points = new Vector3[3];
                    for (int p = 0; p < 3; p++) {
                        int at = Short.toUnsignedInt(part.indices[i + p]) * RigidGlb.STRIDE;
                        points[p] = new Vector3(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2]);
                    }
                    faces.add(new BoardSurface.Face(points[0], points[1], points[2], BoardSurface.Finish.TOP));
                }
            }
        }
        return faces;
    }

    private static Hex hex(int fluff, int rough) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.ROUGH, rough));
        if (fluff != 0) { hex.addTerrain(new Terrain(Terrains.FLUFF, fluff)); }
        return hex;
    }

    private static BoardScene scene(int fluff, int step, boolean water) {
        return scene(fluff, step, water, BoardScene.Surface.GRASS);
    }

    private static BoardScene scene(int fluff, int step, boolean water, BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = x == 2 && y == 2 ? hex(fluff, 1) : new Hex(0);
                tiles.add(new BoardScene.Tile(coords, x > 2 ? step : 0, water ? 1 : -1, false, 0,
                      family, null, null, null, null, null,
                      BoardFeatures.capture(hex, coords, Map.of()), List.of(), water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
