/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import megamek.common.Configuration;
import org.junit.jupiter.api.Test;

/** Structural scenery must stay grounded without placing a replacement floor over the biome. */
class BoardSceneryStructureTest {
    @Test
    void parkingBarrierPaintIsPartOfItsLowConcreteSurface() {
        var data = load("scenery/vehicles/parking-barrier");
        assertEquals(1, data.materials.size);
        assertEquals(1, data.meshes.first().parts.length);
        assertTrue(data.meshes.first().parts[0].indices.length / 3 <= 80);
        int paint = 0, ground = 0;
        float[] values = data.meshes.first().vertices;
        for (int i = 0; i < values.length; i += RigidGlb.STRIDE) {
            float x = Math.abs(values[i] - 24), y = values[i + 1], z = values[i + 2];
            assertTrue(x <= 1.4001f && Math.abs(y) <= 12.5001f && z >= 0 && z <= 2.1001f);
            if (z == 0) { ground++; }
            if (values[i + 6] < .2f || values[i + 6] > .8f) {
                paint++;
                assertTrue(Math.abs(z - 2.1f) < .0001f ||
                      z >= .2799f && Math.abs(x - (1.4f - (z - .28f) * .85f / 1.82f)) < .0001f,
                      "Hazard paint belongs on the top or sloped concrete, with no floating panels");
            }
        }
        assertTrue(paint > 30 && ground >= 4);
    }

    @Test
    void suburbsUseSharedFurnitureAndLeaveBiomeGroundUncovered() {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        for (int variant = 1; variant <= 3; variant++) {
            String name = "scenery/fluff/suburb" + variant;
            var layout = BoardSceneryLayouts.layout(name);
            assertFalse(Files.exists(root.resolve(name + ".glb")), "No residual slab remains");
            for (var component : layout.components()) {
                assertFalse(component.asset().equals(name));
                if (component.kind() != BoardScene.FeatureKind.SCENERY) { continue; }
                float lowFloorArea = 0;
                for (var mesh : load(component.asset()).meshes) {
                    float[] values = mesh.vertices;
                    for (var part : mesh.parts) {
                        for (int i = 0; i < part.indices.length; i += 3) {
                            int a = (part.indices[i] & 0xFFFF) * RigidGlb.STRIDE;
                            int b = (part.indices[i + 1] & 0xFFFF) * RigidGlb.STRIDE;
                            int c = (part.indices[i + 2] & 0xFFFF) * RigidGlb.STRIDE;
                            if (values[a + 5] < .99f || Math.max(values[a + 2],
                                  Math.max(values[b + 2], values[c + 2])) > .61f) { continue; }
                            lowFloorArea += Math.abs((values[b] - values[a]) * (values[c + 1] - values[a + 1])
                                  - (values[b + 1] - values[a + 1]) * (values[c] - values[a])) / 2;
                        }
                    }
                }
                assertTrue(lowFloorArea * component.scale() * component.scale() < 40,
                      "Only small structural feet/steps may cover the ground: " + component.asset());
            }
        }
        var grandstand = BoardSceneryLayouts.layout("scenery/fluff/suburb1").components().stream()
              .filter(c -> c.asset().equals("scenery/parks/grandstand")).toList();
        assertEquals(1, grandstand.size());
        assertTrue(load(grandstand.getFirst().asset()).meshes.first().parts[0].indices.length / 3 <= 400);
        var courtyard = BoardSceneryLayouts.layout("scenery/fluff/suburb2").components();
        assertEquals(1, courtyard.stream().filter(c -> c.asset().equals("scenery/parks/picnic-table")).count());
        var pipes = BoardSceneryLayouts.layout("scenery/fluff/suburb3").components().stream()
              .filter(c -> c.asset().equals("scenery/construction/concrete-pipe")).toList();
        assertEquals(3, pipes.size());
        for (int i = 0; i < pipes.size(); i++) {
            assertEquals(10 + i * 4, pipes.get(i).x());
            assertEquals(-7 + i * 2, pipes.get(i).y());
            assertEquals(0, pipes.get(i).z());
        }
    }

    private static ModelData load(String asset) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        return RigidGlb.loadLods(new FileHandle(root.resolve(asset + ".glb").toFile()), root).getFirst();
    }
}
