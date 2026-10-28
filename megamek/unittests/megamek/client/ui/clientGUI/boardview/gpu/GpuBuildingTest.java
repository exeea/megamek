/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import megamek.common.board.TilesetScenery;
import org.junit.jupiter.api.Test;

class GpuBuildingTest {
    static final String ASSET = "buildings/saxarba/fortress_light/fortress_light_a_52";

    static FileHandle file() {
        return new FileHandle(TilesetScenery.customBuildingFile(ASSET));
    }

    /** The kit's module roles, each with its core node ({@link ConnectedKit#parts}). */
    private static Map<String, String> roles(ModelData data) {
        Map<String, String> result = new java.util.HashMap<>();
        ConnectedKit.parts(data).forEach(piece -> result.put(piece.role(), piece.node()));
        return result;
    }

    private static ModelData modules(String... names) {
        ModelData data = new ModelData();
        for (String name : names) {
            ModelNode node = new ModelNode();
            node.id = name;
            node.parts = new ModelNodePart[] { new ModelNodePart() };
            data.nodes.add(node);
        }
        return data;
    }

    @Test
    void aFiveLevelBuildingHasOneBaseFourFloorsAndOneRoof() {
        var parts = roles(modules("kit-base0", "kit-floor0", "kit-roof0"));
        assertEquals(List.of("base0", "floor0", "floor0", "floor0", "floor0", "roof0"),
              GpuBuilding.select(parts, 5, 41));
        assertEquals(List.of("base0", "roof0"), GpuBuilding.select(parts, 1, 41));
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.select(parts, 0, 41));
    }

    @Test
    void variantNumbersDoNotNeedToStartAtZero() {
        var parts = roles(modules("kit-base4", "kit-floor7", "kit-roof3"));
        assertEquals(List.of("base4", "floor7", "roof3"), GpuBuilding.select(parts, 2, 41));
    }

    @Test
    void randomVariantsAreStableAcrossCameraLodAndHeightChanges() {
        var parts = roles(modules("kit-base0", "kit-base4", "kit-floor0", "kit-floor7", "kit-roof0", "kit-roof3"));
        var reordered = roles(modules("far-roof3", "far-floor7", "far-base4", "far-floor0", "far-roof0", "far-base0"));
        Set<String> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 80; seed++) {
            var one = GpuBuilding.select(parts, 1, seed);
            var five = GpuBuilding.select(parts, 5, seed);
            var ten = GpuBuilding.select(parts, 10, seed);
            assertEquals(five, GpuBuilding.select(reordered, 5, seed));
            assertEquals(List.of(five.getFirst(), five.getLast()), one);
            assertEquals(five.subList(0, 5), ten.subList(0, 5));
            assertEquals(five.getLast(), ten.getLast());
            assertTrue(Set.of("base0", "base4").contains(five.getFirst()));
            assertTrue(five.subList(1, 5).stream().allMatch(Set.of("floor0", "floor7")::contains));
            assertTrue(Set.of("roof0", "roof3").contains(five.getLast()));
            seen.addAll(five);
        }
        assertEquals(Set.of("base0", "base4", "floor0", "floor7", "roof0", "roof3"), seen);
    }

    @Test
    void rejectsIncompleteOrAmbiguousKits() {
        for (String[] names : new String[][] {
              { "kit-floor0", "kit-floor1", "kit-roof0" }, { "kit-base0", "kit-roof0" },
              { "kit-base0", "kit-floor0" }, { "kit-base0", "other-base0", "kit-floor0", "kit-roof0" },
              { "kit-base0", "kit-floor0", "other-floor0", "kit-roof0" },
              { "kit-base0", "kit-floor0", "kit-roof0", "other-roof0" },
              { "kit-base0", "kit-floor0", "kit-roof0", "kit-extra" } }) {
            assertThrows(IllegalArgumentException.class, () -> ConnectedKit.parts(modules(names)));
        }
    }

    @Test
    void simpleBuildingsUseOneLodAndAdditionalAuthoredLodsRemainAvailable() {
        ModelData near = new ModelData(), far = new ModelData(), third = new ModelData();
        near.id = "kit-lod0";
        far.id = "kit-lod1";
        third.id = "kit-lod2";
        assertEquals(List.of(near), GpuBuilding.lods(List.of(near, near, near)));
        assertEquals(List.of(near, far), GpuBuilding.lods(List.of(near, far, far)));
        assertEquals(List.of(near, far, third), GpuBuilding.lods(List.of(near, far, third)));
        assertEquals(List.of(near, near, third), GpuBuilding.lods(List.of(near, near, third)),
              "A missing middle level keeps LOD0 until the third level's distance threshold");
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.lods(List.of(far, far, far)));
    }

    @Test
    void shippedDistantLodStaysWithinItsSmallGeometryBudget() {
        var distant = RigidGlb.loadLods(file()).get(1);
        int triangles = 0;
        for (var mesh : distant.meshes) {
            for (var part : mesh.parts) { triangles += part.indices.length / 3; }
        }
        assertTrue(triangles <= 300, "The complete distant kit must remain lightweight: " + triangles);
    }

    @Test
    void shippedGlbHasSuperimposedLodsWithStackedModulesAndMatchingVariants() {
        var lods = RigidGlb.loadLods(file()).stream().distinct().toList();
        assertEquals(2, lods.size());
        for (int lod = 0; lod < lods.size(); lod++) {
            Map<String, String> names = roles(lods.get(lod));
            assertEquals(Set.of("base0", "floor0", "floor1", "roof0"), names.keySet());
            int current = lod;
            IntStream.range(0, lods.get(lod).nodes.size).forEach(index -> {
                var node = lods.get(current).nodes.get(index);
                String role = names.entrySet().stream().filter(entry -> entry.getValue().equals(node.id))
                      .findFirst().orElseThrow().getKey();
                assertEquals(Map.of("base0", 0f, "floor0", 18f, "floor1", 36f, "roof0", 54f).get(role),
                      node.translation.z, .001f);
                assertEquals(0, node.translation.x, .001f, "Both LODs share the authored XY origin");
                assertEquals(0, node.translation.y, .001f, "Both LODs share the authored XY origin");
            });
        }
    }

    @Test
    void shippedLightBuildingHasCentredVariantsInBothLods() {
        var file = new FileHandle(TilesetScenery.customBuildingFile("buildings/saxarba/building_light/building_light_00"));
        var lods = GpuBuilding.lods(RigidGlb.loadLods(file));
        assertEquals(2, lods.size());
        for (var lod : lods) {
            assertEquals(Set.of("base0", "base1", "floor0", "floor1", "roof0"), roles(lod).keySet());
            for (var node : lod.nodes) {
                var bounds = new com.badlogic.gdx.math.collision.BoundingBox().inf();
                var mesh = lod.meshes.first();
                for (var nodePart : node.parts) {
                    for (var part : mesh.parts) {
                        if (!part.id.equals(nodePart.meshPartId)) { continue; }
                        for (short index : part.indices) {
                            int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                            bounds.ext(mesh.vertices[at] + node.translation.x, mesh.vertices[at + 1] + node.translation.y, 0);
                        }
                    }
                }
                // Variants replace each other on one hex: every module stands on the shared origin.
                assertEquals(0, bounds.getCenterX(), .01f, node.id);
                assertEquals(0, bounds.getCenterY(), .01f, node.id);
            }
        }
    }
}
