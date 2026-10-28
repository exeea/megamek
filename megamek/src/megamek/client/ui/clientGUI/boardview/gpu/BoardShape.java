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
import megamek.common.Configuration;
import megamek.common.board.BoardDecoration;

/** Shared CPU geometry for the terrain-rock and scatter kits; no GL resources or placement policy. */
record BoardShape(List<BoardShape.Polygon> polygons, float height) {
    /** One flat face, counter-clockwise seen from outside; linear {@code color}, and its source {@code material}. */
    record Polygon(Vector3[] points, Vector3 normal, Color color, String material) { }

    static Map<String, BoardShape> loadKit(String asset) {
        File root = new File(Configuration.dataDir(), "models/board");
        return shapes(RigidGlb.load(new FileHandle(megamek.common.board.BoardModelBounds.file(root, asset)), root.toPath()));
    }

    /** CPU support uses the same finest authored level as the visible rigid model. */
    static BoardShape loadModel(String asset) {
        var shapes = loadNodes(asset, BoardDecoration.Colours.NONE);
        if (shapes.size() != 1) { throw new IllegalArgumentException("Expected one shape in " + asset); }
        return shapes.values().iterator().next();
    }

    /**
     * Every root node of the asset's finest authored level by name, its colour slots in {@code colours} by the one
     * recolour rule ({@link RigidGlb#recolour}): the CPU twin of {@link GpuAssets#model(String, BoardDecoration.Colours)}.
     */
    static Map<String, BoardShape> loadNodes(String asset, BoardDecoration.Colours colours) {
        File root = new File(Configuration.dataDir(), "models/board");
        var data = RigidGlb.loadLods(new FileHandle(megamek.common.board.BoardModelBounds.file(root, asset)), root.toPath()).getFirst();
        RigidGlb.recolour(data, colours);
        return shapes(data);
    }

    static Map<String, BoardShape> shapes(ModelData data) {
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
                    color.r *= mesh.vertices[offset + 3];
                    color.g *= mesh.vertices[offset + 4];
                    color.b *= mesh.vertices[offset + 5];
                    polygons.add(new Polygon(points, normal, color, part.materialId));
                }
            }
            if (polygons.isEmpty() || height <= 0) { throw new IllegalArgumentException("Empty kit shape: " + node.id); }
            if (result.put(node.id, new BoardShape(List.copyOf(polygons), height)) != null) {
                throw new IllegalArgumentException("Duplicate kit shape: " + node.id);
            }
        }
        return Map.copyOf(result);
    }
}
