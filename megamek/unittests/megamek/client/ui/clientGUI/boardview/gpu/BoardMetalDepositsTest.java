/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardMetalDepositsTest {
    @Test
    void authoredMineralGlbsHaveRealTextureMapsAndBoundedCrystalGeometry() {
        File root = new File(Configuration.dataDir(), "models/board");
        for (int variant = 0; variant < BoardMetalDeposits.VARIANTS; variant++) {
            var file = new FileHandle(new File(root, "minerals/deposit-" + variant + ".glb"));
            var levels = RigidGlb.loadLods(file, root.toPath());
            assertEquals(RigidGlb.LEVELS, levels.size());
            assertSame(levels.get(1), levels.get(2), "Only LOD0 and LOD1 are authored; farther slots reuse LOD1");
            assertSame(levels.get(2), levels.get(3));
            var triangles = new ArrayList<Integer>();
            for (var model : levels.stream().distinct().toList()) {
                var data = (RigidGlb.Data) model;
                var names = new HashSet<String>();
                for (var material : data.materials) {
                    names.add(material.id);
                    var usages = new HashSet<Integer>();
                    for (var texture : material.textures) { usages.add(texture.usage); }
                    assertTrue(usages.containsAll(Set.of(ModelTexture.USAGE_DIFFUSE, ModelTexture.USAGE_NORMAL)),
                          "Every authored mineral material has its own colour and normal texture mapping");
                    if (!material.id.equals("mineral-matrix")) {
                        assertNotNull(data.surfaces.get(material.id).map(), "Quartz and ore use authored roughness/metal maps");
                    }
                }
                assertEquals(Set.of("mineral-quartz", "mineral-ore", "mineral-matrix"), names);
                assertEquals(8, data.images.size(), "Both LODs share each authored texture inside this GLB");
                var imageBytes = new HashSet<ByteBuffer>();
                for (var image : data.images.values()) {
                    assertNull(image.file(), "The mineral GLB has no external texture dependency");
                    assertNotNull(image.encoded());
                    assertTrue(image.encoded().length > 0);
                    assertTrue(imageBytes.add(ByteBuffer.wrap(image.encoded())), "Identical image bytes must not be duplicated");
                }
                var bounds = new BoundingBox().inf();
                int faces = 0;
                for (var mesh : data.meshes) {
                    for (var part : mesh.parts) { faces += part.indices.length / 3; }
                    for (int at = 0; at < mesh.vertices.length; at += RigidGlb.STRIDE) {
                        float x = mesh.vertices[at], y = mesh.vertices[at + 1], z = mesh.vertices[at + 2];
                        bounds.ext(x, y, z);
                        assertTrue(Math.hypot(x, y) <= .55, "Shared clearance encloses all authored GLB geometry");
                        for (int field = 0; field < RigidGlb.STRIDE; field++) { assertTrue(Float.isFinite(mesh.vertices[at + field])); }
                    }
                }
                assertTrue(bounds.getDepth() > .3f, "Minerals have actual crystalline height, rather than flat pads");
                assertTrue(bounds.getWidth() > .3f);
                triangles.add(faces);
            }
            assertEquals(2, triangles.size());
            assertTrue(triangles.get(1) <= 100 && triangles.get(1) * 10 < triangles.getFirst(),
                  "The distant cluster keeps its crystal silhouette with at most 100 triangles and under 10% of LOD0");
        }
    }

    @Test
    void capturedMineralsPreserveTheRulesAndGrowWithoutMovingExistingFragments() {
        var coords = new Coords(4, 8);
        var hex = new Hex(2);
        assertTrue(BoardMetalDeposits.capture(hex, coords).isEmpty());
        hex.addTerrain(new Terrain(Terrains.METAL_CONTENT, 0));
        assertFalse(BoardMetalDeposits.capture(hex, coords).isEmpty(), "Legacy level-zero markers remain visible");
        hex.addTerrain(new Terrain(Terrains.METAL_CONTENT, 2));
        var low = BoardMetalDeposits.capture(hex, coords);
        hex.addTerrain(new Terrain(Terrains.METAL_CONTENT, 8));
        var high = BoardMetalDeposits.capture(hex, coords);
        assertTrue(high.size() > low.size());
        assertEquals(low, high.subList(0, low.size()), "Content changes expose more of the same stable deposit");
        assertEquals(high, BoardMetalDeposits.capture(hex.duplicate(), coords));
        assertNotEquals(high, BoardMetalDeposits.capture(hex, new Coords(5, 8)));
        assertEquals(8, hex.terrainLevel(Terrains.METAL_CONTENT));
        assertFalse(hex.containsTerrain(Terrains.ROUGH), "Sensor-interference markings never add movement terrain");
        assertTrue(high.stream().allMatch(feature -> feature.kind() == BoardScene.FeatureKind.METAL
              && feature.decoration() == null && feature.height() < 1 && feature.asset().startsWith("minerals/deposit-")));
    }

    @Test
    void enlargedClustersAndTheirSoilStillFitInsideTheirOwnHex() {
        var hex = new Hex(0, "metal_deposit:10", "");
        boolean spreadBeyondOldRadius = false;
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (var feature : BoardMetalDeposits.capture(hex, new Coords(x, y))) {
                    assertTrue(feature.scale() >= 10.5f && feature.scale() < 15);
                    assertTrue(feature.height() >= .225f && feature.height() < .375f,
                          "The full 3D cluster grows, including its height");
                    double distance = Math.hypot(feature.x(), feature.y());
                    spreadBeyondOldRadius |= distance > 15;
                    assertTrue(distance + feature.scale() * .72f < BoardGeometry.TILE_HEIGHT / 2,
                          "The conservative model/soil footprint remains inside the hex's inscribed circle");
                }
            }
        }
        assertTrue(spreadBeyondOldRadius, "The larger clusters also spread farther apart");
    }
}
