/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardScatterTest {
    @Test
    void wildBushesHaveOpenSilhouettesAndSeparateWoodAndLeaves() {
        for (var surface : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK)) {
            for (int variant = 0; variant < BoardScatter.BUSHES / 2; variant++) {
                var bush = BoardScatter.bush(surface, variant);
                assertTrue(bush.polygons().size() <= 800, "Keep these full-detail decorations bounded");
                assertTrue(bush.polygons().stream().anyMatch(p -> p.color().r > p.color().g * 1.2f),
                      "Authored brown stems must survive the CPU loader");
                assertTrue(bush.polygons().stream().anyMatch(p -> p.color().g > p.color().r),
                      "Leaf colour must remain distinct from the woody stems");
                float low = Float.POSITIVE_INFINITY;
                for (var face : bush.polygons()) {
                    for (var point : face.points()) {
                        assertTrue(Math.hypot(point.x, point.y) <= BoardScatter.BUSH_RADIUS, "Stay within placement clearance");
                        low = Math.min(low, point.z);
                    }
                }
                assertEquals(0, low, .00001f, "Rooted at ground level");
                assertEquals(2.4f, bush.height(), .00001f);
                // An overhead view must see through the bush rather than find another solid green rock.
                int hits = 0;
                for (int x = 0; x < 30; x++) {
                    for (int y = 0; y < 30; y++) {
                        Ray ray = new Ray(new Vector3(((x + .5f) / 30 - .5f) * 3,
                              ((y + .5f) / 30 - .5f) * 3, 3),
                              new Vector3(0, 0, -1));
                        if (bush.polygons().stream().anyMatch(p -> Intersector.intersectRayTriangle(ray,
                              p.points()[0], p.points()[1], p.points()[2], null))) { hits++; }
                    }
                }
                assertTrue(hits > 15 && hits < 450, surface + " overhead coverage: " + hits + "/900");
            }
        }
    }

    @Test
    void bothBushPathsStandAboveGrassButBelowHalfALevel() {
        var original = BoardGeometry.tuning();
        try {
            for (int level : new int[] { 1, 6, 18, 48 }) {
                for (float hexScale : new float[] { .5f, 1, 2 }) {
                    BoardGeometry.tune(new BoardGeometry.Tuning(hexScale, 1, 1, level, .8f));
                    for (var surface : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.ROCK)) {
                        for (var shape : List.of(BoardScatter.bush(surface, 0), BoardScatter.plant(surface))) {
                            for (float variation : new float[] { .7f, 1, 1.2f }) {
                                float scale = BoardScatter.bushScale(shape, variation);
                                float height = shape.height() * scale;
                                assertTrue(height <= BoardGeometry.level() * .45001f);
                                if (level >= BoardGeometry.MODEL_LEVEL_HEIGHT) {
                                    float grass = BoardGeometry.width() * GpuGroundCover.MAX_HEIGHT_FRACTION;
                                    assertTrue(height - .02f * scale > grass * 1.1f,
                                          "Even the smallest rooted bush stands above the tallest grass");
                                    assertTrue(height <= grass * 1.301f, "Keep bushes only slightly taller than grass");
                                }
                            }
                        }
                    }
                }
            }
        } finally { BoardGeometry.tune(original); }
    }

    @Test
    void naturalSurfacesKeepVariedClustersAndStayStableAcrossSnapshots() {
        for (String theme : List.of("grass", "lunar", "desert", "dirt", "snow", "mars", "volcanic")) {
            Hex hex = new Hex(0);
            hex.setTheme(theme);
            int occupied = 0;
            Set<Integer> sizes = new HashSet<>();
            Set<String> shapes = new HashSet<>();
            Set<Float> rotations = new HashSet<>();
            for (int x = 0; x < 64; x++) {
                for (int y = 0; y < 64; y++) {
                    Coords coords = new Coords(x, y);
                    var features = scatter(hex, coords);
                    assertTrue(features.size() <= 6);
                    assertEquals(features, scatter(hex, coords));
                    // Collectable limbs must not change the cosmetic ground layout.
                    hex.addTerrain(new Terrain(Terrains.ARMS, 1));
                    assertEquals(features, scatter(hex, coords));
                    hex.removeTerrain(Terrains.ARMS);
                    if (!features.isEmpty()) {
                        occupied++;
                        sizes.add(features.size());
                    }
                    for (var feature : features) {
                        shapes.add(feature.asset());
                        rotations.add(feature.rotation());
                        assertTrue(Math.hypot(feature.x(), feature.y()) + 4 * feature.scale() < 33,
                              "The entire detail stays clear of hex edges and cliffs");
                        assertTrue(Math.hypot(feature.x(), feature.y()) - 4 * feature.scale() > 10,
                              "Scatter leaves the unit standing area clear");
                        assertTrue(feature.height() < .24f, "Scatter cannot resemble gameplay-height obstacles");
                    }
                }
            }
            double baseline = switch (theme) {
                case "grass" -> .16;
                case "lunar", "volcanic" -> .18;
                case "dirt", "mars" -> .12;
                case "desert" -> .10;
                default -> .06;
            };
            double expected = Math.clamp(baseline * BoardScatter.DENSITY_MULTIPLIER, 0, 1);
            assertEquals(expected, occupied / 4096.0, .03, theme + ": " + occupied + " occupied of 4096 hexes");
            if (occupied == 0) {
                continue;
            }
            assertTrue(sizes.size() >= 3, "Clusters must vary in size");
            assertTrue(rotations.size() > occupied, "No small repeating rotation set");
            assertTrue(shapes.containsAll(List.of("scatter-rock", "scatter-slab")), theme);
            if (theme.equals("grass")) {
                assertTrue(shapes.containsAll(List.of("scatter-grass", "scatter-plant")));
            } else if (theme.equals("desert")) {
                assertTrue(shapes.contains("scatter-plant"));
                assertFalse(shapes.contains("scatter-grass"));
            } else if (theme.equals("dirt")) {
                assertTrue(shapes.contains("scatter-dry-grass"));
            } else {
                assertEquals(Set.of("scatter-rock", "scatter-slab"), shapes);
            }
        }
    }

    @Test
    void tundraUsesDryTuftsInsteadOfLushPlants() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.TUNDRA, 1));
        Set<String> shapes = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                scatter(hex, new Coords(x, y)).forEach(feature -> shapes.add(feature.asset()));
            }
        }
        assertEquals(BoardScatter.DENSITY_MULTIPLIER <= 0 ? Set.of()
              : Set.of("scatter-dry-grass", "scatter-rock", "scatter-slab"), shapes);
    }

    @Test
    void structuresRoadsWaterAndExistingVegetationStayClear() {
        // Check every protected type over many coordinates, including hexes that would otherwise get scatter.
        for (int type : new int[] { Terrains.WATER, Terrains.ICE, Terrains.ROAD, Terrains.PAVEMENT, Terrains.BRIDGE,
              Terrains.BUILDING, Terrains.FUEL_TANK, Terrains.INDUSTRIAL, Terrains.FIELDS, Terrains.WOODS, Terrains.JUNGLE,
              Terrains.SPACE, Terrains.SKY, Terrains.MAGMA, Terrains.FIRE, Terrains.GEYSER, Terrains.SWAMP, Terrains.MUD,
              Terrains.HAZARDOUS_LIQUID, Terrains.FORTIFIED }) {
            Hex hex = new Hex(0);
            hex.addTerrain(new Terrain(type, 1));
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    assertTrue(scatter(hex, new Coords(x, y)).isEmpty(), "Protected terrain " + type);
                }
            }
        }
    }

    @Test
    void shallowWaterCanCaptureGrassAndBushesWhileDeeperWaterStaysClear() {
        Hex shallow = new Hex(0);
        shallow.setTheme("grass");
        shallow.addTerrain(new Terrain(Terrains.WATER, 0));
        Hex deep = new Hex(0);
        deep.setTheme("grass");
        deep.addTerrain(new Terrain(Terrains.WATER, 1));
        Set<String> shapes = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                Coords coords = new Coords(x, y);
                scatter(shallow, coords).forEach(feature -> shapes.add(feature.asset()));
                assertTrue(scatter(deep, coords).isEmpty(), "Positive-depth water rejects cosmetics during capture");
            }
        }
        assertTrue(BoardScatter.DENSITY_MULTIPLIER <= 0 || shapes.containsAll(List.of("scatter-grass", "scatter-plant")),
              "Depth-zero water remains eligible for the ordinary grass/bush population");
    }

    private static List<BoardScene.Feature> scatter(Hex hex, Coords coords) {
        return BoardFeatures.capture(hex, coords, Map.of()).stream()
              .filter(feature -> feature.kind() == BoardScene.FeatureKind.SCATTER).toList();
    }
}
