/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.Configuration;

/**
 * Shared CPU geometry from the rock and scatter GLB kits in mm-data. Terrain rocks are closed solids; tiny stones
 * have eight triangles and an open base. Placement, geology and batching all use these same cached shapes.
 */
final class BoardRocks {
    static final int BLOCKS = 8;
    static final int BOULDERS = 8;
    static final int BUSHES = 8;

    /** One flat face, counter-clockwise seen from outside. */
    record Polygon(Vector3[] points, Vector3 normal) { }

    /** A shape in board coordinates. Rocks have unit horizontal extent and a base at zero. */
    record Rock(List<Polygon> polygons, float height) { }

    private static final Map<String, List<Rock>> ROCKS = loadRocks();
    private static final Map<String, List<Rock>> SCATTER = loadLevels("scatter");

    private BoardRocks() { }

    /** One geology rule for rough, rim, slope, cliff and scattered rocks. */
    static Rock rock(BoardScene.Surface surface, int variant, TerrainLod detail) {
        return rock(blocks(surface), variant, detail);
    }

    /** Cosmetic stones always select the dedicated eight-triangle open-base meshes. */
    static Rock scatter(BoardScene.Surface surface, int variant, boolean slab) {
        return scatter("stone-" + name(slab || blocks(surface), variant));
    }

    private static boolean blocks(BoardScene.Surface surface) {
        return surface == BoardScene.Surface.SAND || surface == BoardScene.Surface.CONCRETE;
    }

    static Rock rock(boolean block, int variant) {
        return rock(block, variant, TerrainLod.FULL);
    }

    static Rock rock(boolean block, int variant, TerrainLod detail) {
        // Terrain's first two sampling levels share rock LOD0, preserving the existing transition distances.
        int level = Math.max(0, detail.ordinal() - 1);
        return ROCKS.get(name(block, variant)).get(level);
    }

    private static String name(boolean block, int variant) {
        return (block ? "block-" : "boulder-") + Math.floorMod(variant, block ? BLOCKS : BOULDERS);
    }

    static Rock bush(int variant) {
        return scatter("bush-" + Math.floorMod(variant, BUSHES));
    }

    static Rock scatter(String shape) {
        return SCATTER.get(shape).getFirst();
    }

    private static Map<String, List<Rock>> loadRocks() {
        Map<String, List<Rock>> rocks = new HashMap<>();
        for (boolean block : new boolean[] { true, false }) {
            for (int variant = 0; variant < (block ? BLOCKS : BOULDERS); variant++) {
                String shape = name(block, variant);
                Map<String, Rock> levels = loadKit("rocks/" + shape);
                for (String node : levels.keySet()) {
                    if (!node.matches(shape + "-lod[0-2]")) {
                        throw new IllegalArgumentException("Unexpected mesh in rock " + shape + ": " + node);
                    }
                }
                rocks.put(shape, MeshLod.load(shape, 3, levels::get));
            }
        }
        return Map.copyOf(rocks);
    }

    private static Map<String, List<Rock>> loadLevels(String asset) {
        Map<String, Rock> shapes = loadKit(asset);
        Map<String, List<Rock>> levels = new HashMap<>();
        for (String name : shapes.keySet()) {
            if (!name.matches(".+-lod[0-2]")) { throw new IllegalArgumentException("Invalid kit LOD name: " + name); }
            String shape = name.substring(0, name.lastIndexOf("-lod"));
            levels.computeIfAbsent(shape, key -> MeshLod.load(key, 3, shapes::get));
        }
        return Map.copyOf(levels);
    }

    /** Shared by terrain rocks and the tiny vegetation kit; no GL allocation, including on worker threads. */
    static Map<String, Rock> loadKit(String asset) {
        var data = RigidGlb.load(new FileHandle(new File(Configuration.dataDir(), "models/board/" + asset + ".glb")));
        var mesh = data.meshes.first();
        Map<String, short[]> indices = new HashMap<>();
        for (var part : mesh.parts) { indices.put(part.id, part.indices); }
        Map<String, Rock> result = new HashMap<>();
        for (var node : data.nodes) {
            if (node.children.length != 0) { throw new IllegalArgumentException("Kit shapes must be root nodes"); }
            Matrix4 transform = new Matrix4(node.translation, node.rotation, node.scale);
            Matrix3 normals = new Matrix3().set(transform).inv().transpose();
            Map<Vector3, Vector3> corners = new HashMap<>();
            List<Polygon> polygons = new ArrayList<>();
            float height = 0;
            for (var part : node.parts) {
                short[] triangles = indices.get(part.meshPartId);
                for (int i = 0; i < triangles.length; i += 3) {
                    Vector3[] points = new Vector3[3];
                    for (int j = 0; j < 3; j++) {
                        int offset = Short.toUnsignedInt(triangles[i + j]) * RigidGlb.STRIDE;
                        Vector3 point = new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1],
                              mesh.vertices[offset + 2]).mul(transform);
                        // Flat normals split glTF vertices. Restore shared position identity for ground sampling.
                        points[j] = corners.computeIfAbsent(point, key -> key);
                        height = Math.max(height, point.z);
                    }
                    int offset = Short.toUnsignedInt(triangles[i]) * RigidGlb.STRIDE + 3;
                    Vector3 normal = new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1],
                          mesh.vertices[offset + 2]).mul(normals).nor();
                    polygons.add(new Polygon(points, normal));
                }
            }
            if (polygons.isEmpty() || height <= 0) { throw new IllegalArgumentException("Empty kit shape: " + node.id); }
            if (result.put(node.id, new Rock(List.copyOf(polygons), height)) != null) {
                throw new IllegalArgumentException("Duplicate kit shape: " + node.id);
            }
        }
        return Map.copyOf(result);
    }
}
