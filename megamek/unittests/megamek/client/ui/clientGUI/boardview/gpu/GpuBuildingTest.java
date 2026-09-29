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
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import org.junit.jupiter.api.Test;

class GpuBuildingTest {
    static final String ASSET = "buildings/saxarba/fortress_light/fortress_light_a_52";

    static FileHandle file() {
        return new FileHandle(BoardArtwork.customBuildingFile(ASSET));
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
    void aFiveLevelBuildingHasOneGroundFourUpperFloorsAndOneRoof() {
        var parts = GpuBuilding.parts(modules("kit-floor0", "kit-floor1", "kit-roof0"));
        assertEquals(List.of("floor0", "floor1", "floor1", "floor1", "floor1", "roof0"),
              GpuBuilding.select(parts, 5, 41));
        assertEquals(List.of("floor0", "roof0"), GpuBuilding.select(parts, 1, 41));
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.select(parts, 0, 41));
    }

    @Test
    void randomVariantsAreStableAcrossCameraLodAndHeightChanges() {
        var parts = GpuBuilding.parts(modules("kit-floor0", "kit-floor1", "kit-floor7", "kit-roof0", "kit-roof3"));
        var reordered = GpuBuilding.parts(modules("far-roof3", "far-floor7", "far-floor0", "far-roof0", "far-floor1"));
        Set<String> seen = new java.util.HashSet<>();
        for (long seed = 0; seed < 80; seed++) {
            var five = GpuBuilding.select(parts, 5, seed);
            var ten = GpuBuilding.select(parts, 10, seed);
            assertEquals(five, GpuBuilding.select(reordered, 5, seed));
            assertEquals(five.subList(0, 5), ten.subList(0, 5));
            assertEquals(five.getLast(), ten.getLast());
            seen.addAll(five);
        }
        assertEquals(Set.of("floor0", "floor1", "floor7", "roof0", "roof3"), seen);
    }

    @Test
    void rejectsIncompleteOrAmbiguousKits() {
        for (String[] names : new String[][] {
              { "kit-floor1", "kit-roof0" }, { "kit-floor0", "kit-roof0" },
              { "kit-floor0", "kit-floor1" }, { "kit-floor0", "other-floor0", "kit-floor1", "kit-roof0" },
              { "kit-floor0", "kit-floor1", "kit-roof0", "kit-extra" } }) {
            assertThrows(IllegalArgumentException.class, () -> GpuBuilding.parts(modules(names)));
        }
    }

    @Test
    void simpleBuildingsUseOneLodAndAdditionalAuthoredLodsRemainAvailable() {
        ModelData near = new ModelData(), far = new ModelData(), third = new ModelData();
        near.id = "kit-lod0";
        far.id = "kit-lod1";
        third.id = "kit-lod2";
        assertEquals(List.of(near), GpuBuilding.lods("kit", List.of(near, near, near)));
        assertEquals(List.of(near, far), GpuBuilding.lods("kit", List.of(near, far, far)));
        assertEquals(List.of(near, far, third), GpuBuilding.lods("kit", List.of(near, far, third)));
        assertEquals(List.of(near, near, third), GpuBuilding.lods("kit", List.of(near, near, third)),
              "A missing middle level keeps LOD0 until the third level's distance threshold");
        assertThrows(IllegalArgumentException.class, () -> GpuBuilding.lods("kit", List.of(far, far, far)));
    }

    @Test
    void shippedDistantLodStaysWithinItsSmallGeometryBudget() {
        var distant = RigidGlb.loadLods(file()).get(1);
        int triangles = 0;
        for (var mesh : distant.meshes) {
            for (var part : mesh.parts) { triangles += part.indices.length / 3; }
        }
        assertTrue(triangles <= 800, "The complete distant kit must remain lightweight: " + triangles);
    }

    @Test
    void shippedGlbOpensAsStackedBuildingsWithMatchingLodVariants() {
        var lods = RigidGlb.loadLods(file()).stream().distinct().toList();
        assertEquals(2, lods.size());
        for (int lod = 0; lod < lods.size(); lod++) {
            Map<String, String> names = GpuBuilding.parts(lods.get(lod));
            assertEquals(Set.of("roof0", "floor0", "floor1", "floor2"), names.keySet());
            int current = lod;
            IntStream.range(0, lods.get(lod).nodes.size).forEach(index -> {
                var node = lods.get(current).nodes.get(index);
                String role = names.entrySet().stream().filter(entry -> entry.getValue().equals(node.id))
                      .findFirst().orElseThrow().getKey();
                assertEquals(Map.of("floor0", 0f, "floor1", 18f, "floor2", 36f, "roof0", 54f).get(role),
                      node.translation.z, .001f);
                assertEquals(current * 110, node.translation.x, .001f, "Viewer LODs stand beside one another");
            });
        }
    }
}

