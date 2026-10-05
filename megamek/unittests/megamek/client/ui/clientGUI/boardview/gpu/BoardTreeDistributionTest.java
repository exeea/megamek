/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTreeDistributionTest {
    static final List<String> BASIC_TREES = List.of("tree", "tree-broad", "tree-slender", "tree-forked", "tree-layered",
          "birch", "birch-tall", "birch-spreading", "birch-young", "willow", "willow-broad", "pine", "pine-tall",
          "pine-broad", "pine-slender", "pine-layered");

    static List<BoardScene.Feature> capture(Hex hex, Coords coords, boolean natural) {
        return BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null, BoardArtwork.Scenery.EMPTY, natural)
              .stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).toList();
    }

    @Test
    void standsFavorNearbyFamiliesWithoutLosingCatalogVarietyOrTreeCounts() {
        int[] related = new int[2];
        var selected = new HashSet<String>();
        int pairs = 0;
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 24; y++) {
                var coords = new Coords(x, y);
                for (int mode = 0; mode < 2; mode++) {
                    var hex = new Hex(0, "woods:2;foliage_elev:2", "", coords);
                    var trees = capture(hex, coords, mode == 1);
                    assertEquals(9, trees.size());
                    assertEquals(trees, capture(hex, coords, mode == 1), "Capture order cannot reroll a stand");
                    if (mode == 1) { trees.forEach(t -> selected.add(t.asset())); }
                    for (int i = 1; i < trees.size(); i++) {
                        if (family(trees.getFirst().asset()).equals(family(trees.get(i).asset()))) { related[mode]++; }
                        if (mode == 0) { pairs++; }
                    }
                }
            }
        }
        assertEquals(14, selected.size(), "Temperate woods use twice the original seven shapes");
        assertTrue(related[1] > related[0] + pairs * .20f,
              "Nearby trees must form stands, not merely a different random mix: " + related[0] + " / " + related[1]);
    }

    @Test
    void standSelectionIsContinuousAcrossEveryHexEdge() {
        var random = new Random(842);
        for (int x = -4; x < 4; x++) {
            for (int direction = 0; direction < 6; direction++) {
                var a = new Coords(x, 5);
                var b = a.translated(direction);
                var delta = BoardGeometry.center(b, 0).sub(BoardGeometry.center(a, 0)).scl(.5f);
                long individual = random.nextLong();
                assertEquals(BoardTreeDistribution.species(BASIC_TREES, a, delta.x, delta.y, individual),
                      BoardTreeDistribution.species(BASIC_TREES, b, -delta.x, -delta.y, individual),
                      "The same point must not change species at a hex seam");
            }
        }
    }

    @Test
    void groupingKeepsCoverHeightsFootprintsAndSpecialVegetation() {
        var coords = new Coords(7, 4);
        for (int density = 1; density <= 3; density++) {
            var hex = new Hex(0, "woods:" + density + ";foliage_elev:2", "", coords);
            var trees = capture(hex, coords, true);
            assertEquals(density == 1 ? 3 : density == 2 ? 9 : 16, trees.size());
            assertTrue(trees.stream().allMatch(t -> t.height() >= 2 && t.height() <= 2.2f
                  && Math.hypot(t.x(), t.y()) <= 29), "Keep authoritative cover and the existing trunk footprint");
        }
        for (String cover : List.of("", "woods:1;foliage_elev:1", "woods:1;foliage_elev:2;fluff:12")) {
            var hex = new Hex(0, cover, "", coords);
            assertEquals(capture(hex, coords, false), capture(hex, coords, true), "Clear ground, shrubs and orchard rows");
        }
        for (String theme : List.of("mars", "fungus")) {
            var hex = new Hex(0, "woods:2;foliage_elev:2", theme, coords);
            assertEquals(capture(hex, coords, false), capture(hex, coords, true), "Keep alien colony layouts");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void doubledCatalogHasDistinctTrunksBoundedLodsAndSharedLeafTextures(boolean snow) {
        File root = new File(Configuration.dataDir(), "models/board");
        var trunks = new HashSet<List<Float>>();
        var pigments = new HashSet<Integer>();
        for (String tree : BASIC_TREES) {
            String name = tree + (snow ? "-snow" : "");
            var levels = RigidGlb.loadLods(new FileHandle(new File(root, name + ".glb")), root.toPath());
            assertEquals(4, levels.size(), name);
            var materials = new java.util.HashMap<String, String>();
            BoardFoliageTest.partMaterials(levels.getFirst().nodes, materials);
            var trunk = new ArrayList<Float>();
            var mesh = levels.getFirst().meshes.first();
            for (var part : mesh.parts) {
                if (materials.get(part.id).startsWith("bark")) {
                    for (short index : part.indices) {
                        int v = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                        trunk.add(mesh.vertices[v]); trunk.add(mesh.vertices[v + 1]); trunk.add(mesh.vertices[v + 2]);
                    }
                } else {
                    int v = Short.toUnsignedInt(part.indices[0]) * RigidGlb.STRIDE;
                    pigments.add(Math.round(mesh.vertices[v + 6] / mesh.vertices[v + 7] * 1000));
                }
            }
            trunks.add(trunk);
            for (int lod = 0; lod < 4; lod++) {
                var model = levels.get(lod);
                int triangles = 0;
                float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
                for (var m : model.meshes) {
                    for (var part : m.parts) { triangles += part.indices.length / 3; }
                    for (int v = 0; v < m.vertices.length; v += RigidGlb.STRIDE) {
                        low = Math.min(low, m.vertices[v + 2]); high = Math.max(high, m.vertices[v + 2]);
                    }
                }
                assertTrue(triangles > 0 && triangles <= List.of(480, 240, 96, 12).get(lod), name);
                assertEquals(0, low, .001f, name);
                assertEquals(30, high, .001f, name);
                for (var material : model.materials) {
                    if (!material.id.endsWith("-cutout")) { continue; }
                    assertEquals(.5f, ((RigidGlb.Data) model).alphaTests.get(material.id), .0001f);
                    var texture = ((RigidGlb.Data) model).images.get(material.textures.first().fileName);
                    assertTrue(texture.file().endsWith((tree.startsWith("pine") ? "conifer" : "broadleaf")
                          + (snow ? "-snow" : "") + "-cutout.png"), "Variants borrow the existing texture");
                }
            }
        }
        assertEquals(16, trunks.size(), "Different trunks and branching, not recolored or resized crown copies");
        assertTrue(snow || pigments.size() >= 8, "Summer variants have distinct subtle leaf pigments");
    }

    private static String family(String asset) {
        return asset.split("-")[0];
    }
}
