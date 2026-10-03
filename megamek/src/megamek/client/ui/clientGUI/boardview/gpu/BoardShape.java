/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.Configuration;

/** Shared CPU geometry for the terrain-rock and scatter kits; no GL resources or placement policy. */
record BoardShape(List<BoardShape.Polygon> polygons, float height) {
    /** One flat face, counter-clockwise seen from outside. */
    record Polygon(Vector3[] points, Vector3 normal, Color color) { }

    static Map<String, BoardShape> loadKit(String asset) {
        File root = new File(Configuration.dataDir(), "models/board");
        return shapes(RigidGlb.load(new FileHandle(new File(root, asset + ".glb")), root.toPath()));
    }

    /** CPU support uses the same finest authored level as the visible rigid model. */
    static BoardShape loadModel(String asset) {
        File root = new File(Configuration.dataDir(), "models/board");
        var shapes = shapes(RigidGlb.loadLods(new FileHandle(new File(root, asset + ".glb")), root.toPath()).getFirst());
        if (shapes.size() != 1) { throw new IllegalArgumentException("Expected one shape in " + asset); }
        return shapes.values().iterator().next();
    }

    static Map<String, BoardShape> shapes(ModelData data) {
        return shapes(data, false);
    }

    /** Modular structures use their geometry bounds as origins, even when authored below Z=0. */
    static Map<String, BoardShape> shapes(ModelData data, boolean centered) {
        var mesh = data.meshes.first();
        Map<String, short[]> indices = new HashMap<>();
        for (var part : mesh.parts) { indices.put(part.id, part.indices); }
        Map<String, Color> colors = new HashMap<>();
        for (var material : data.materials) { colors.put(material.id, material.diffuse); }
        Map<String, BoardShape> result = new HashMap<>();
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
                        // Restore shared position identity for ground sampling across flat-shaded faces.
                        points[j] = corners.computeIfAbsent(point, key -> key);
                        height = Math.max(height, point.z);
                    }
                    int offset = Short.toUnsignedInt(triangles[i]) * RigidGlb.STRIDE + 3;
                    Vector3 normal = new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1],
                          mesh.vertices[offset + 2]).mul(normals).nor();
                    Color color = new Color(colors.get(part.materialId));
                    color.mul(mesh.vertices[offset + 3], mesh.vertices[offset + 4], mesh.vertices[offset + 5], 1);
                    polygons.add(new Polygon(points, normal, color));
                }
            }
            if (centered && !polygons.isEmpty()) {
                BoundingBox bounds = new BoundingBox().inf();
                corners.values().forEach(bounds::ext);
                Vector3 offset = GpuBuilding.moduleOffset(bounds);
                corners.values().forEach(point -> point.add(offset));
                height = bounds.getDepth();
            }
            if (polygons.isEmpty() || height <= 0) { throw new IllegalArgumentException("Empty kit shape: " + node.id); }
            if (result.put(node.id, new BoardShape(List.copyOf(polygons), height)) != null) {
                throw new IllegalArgumentException("Duplicate kit shape: " + node.id);
            }
        }
        return Map.copyOf(result);
    }
}
