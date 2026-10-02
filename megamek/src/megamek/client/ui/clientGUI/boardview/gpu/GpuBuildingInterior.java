/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.g3d.utils.shapebuilders.BoxShapeBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;

/** Simple box columns and two-sided floor sheets, clipped to the authored roof's actual footprint. */
final class GpuBuildingInterior {
    private static final float SPACING = 22;
    private static final float STRUT_WIDTH = 1.6f;
    private static final long ATTRIBUTES = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

    private GpuBuildingInterior() { }

    static Model build(Model shell, int levels) {
        float height = shell.calculateBoundingBox(new BoundingBox()).max.z;
        return build(plane(GpuTerrain.triangles(shell), height, true), height, levels);
    }

    /** Horizontal cap faces delimit occupied volume; roof furniture and bounding boxes cannot fill courtyards. */
    static List<Vector3> plane(List<Vector3> triangles, float z, boolean upward) {
        List<Vector3> result = new ArrayList<>();
        for (int index = 0; index < triangles.size(); index += 3) {
            Vector3 a = triangles.get(index), b = triangles.get(index + 1), c = triangles.get(index + 2);
            float normal = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
            if (Math.abs(a.z - z) < .001f && Math.abs(b.z - z) < .001f && Math.abs(c.z - z) < .001f
                  && (upward ? normal > .0001f : normal < -.0001f)) {
                // Always return upward-facing triangles for the shared interior generator.
                result.add(a);
                result.add(upward ? b : c);
                result.add(upward ? c : b);
            }
        }
        return List.copyOf(result);
    }

    /** The roof underside extruded through the wall stack is the interior volume for a modular building. */
    static Model build(List<Vector3> roof, float height, int levels) {
        if (roof.isEmpty() || height <= 0 || levels < 1) {
            throw new IllegalArgumentException("Building interior needs a footprint and positive height");
        }
        BoundingBox bounds = new BoundingBox().inf();
        roof.forEach(bounds::ext);
        List<Vector3> columns = new ArrayList<>();
        int across = Math.max(1, (int) (bounds.getWidth() / SPACING));
        int along = Math.max(1, (int) (bounds.getHeight() / SPACING));
        for (int x = 0; x < across; x++) {
            for (int y = 0; y < along; y++) {
                addColumn(columns, roof, bounds.min.x + bounds.getWidth() * (x + 0.5f) / across,
                      bounds.min.y + bounds.getHeight() * (y + 0.5f) / along, height);
            }
        }
        // Small or disconnected wings can miss the grid. Give uncovered roof sections a nearby support.
        for (int index = 0; index < roof.size(); index += 3) {
            Vector3 center = new Vector3(roof.get(index)).add(roof.get(index + 1)).add(roof.get(index + 2)).scl(1f / 3);
            if (columns.stream().noneMatch(column -> (column.x - center.x) * (column.x - center.x)
                  + (column.y - center.y) * (column.y - center.y) < SPACING * SPACING)) {
                addColumn(columns, roof, center.x, center.y, height);
            }
        }
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        builder.node().id = "struts";
        MeshPartBuilder struts = builder.part("struts", GL20.GL_TRIANGLES, ATTRIBUTES,
              new Material("struts", ColorAttribute.createDiffuse(0.29f, 0.31f, 0.33f, 1)));
        for (Vector3 column : columns) {
            BoxShapeBuilder.build(struts, column.x, column.y, 0.499f * height, STRUT_WIDTH, STRUT_WIDTH, height);
        }
        builder.node().id = "floors";
        Material floorMaterial = new Material("floors", ColorAttribute.createDiffuse(0.58f, 0.56f, 0.52f, 1));
        for (int level = 0; level < levels; level++) {
            // Separate parts let ModelBuilder split tall structures before a mesh exceeds its vertex limit.
            MeshPartBuilder floors = builder.part("floor-" + level, GL20.GL_TRIANGLES, ATTRIBUTES, floorMaterial);
            // Ground floor clears the terrain; upper floors sit at the game's level boundaries.
            float z = (level == 0 ? 0.002f : level) * height / levels;
            for (int index = 0; index < roof.size(); index += 3) {
                Vector3 a = roof.get(index), b = roof.get(index + 1), c = roof.get(index + 2);
                floors.triangle(vertex(a, z, 1), vertex(b, z, 1), vertex(c, z, 1));
                floors.triangle(vertex(c, z, -1), vertex(b, z, -1), vertex(a, z, -1));
            }
        }
        return builder.end();
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, float z, float normalZ) {
        return new MeshPartBuilder.VertexInfo().setPos(point.x, point.y, z).setNor(0, 0, normalZ);
    }

    private static void addColumn(List<Vector3> columns, List<Vector3> roof, float x, float y, float height) {
        float half = STRUT_WIDTH / 2;
        // Checking the footprint keeps columns out of courtyards, gaps and concave outside corners.
        if (inside(roof, x, y) && inside(roof, x - half, y - half) && inside(roof, x + half, y - half)
              && inside(roof, x - half, y + half) && inside(roof, x + half, y + half)) {
            columns.add(new Vector3(x, y, height));
        }
    }

    private static boolean inside(List<Vector3> roof, float x, float y) {
        for (int index = 0; index < roof.size(); index += 3) {
            Vector3 a = roof.get(index), b = roof.get(index + 1), c = roof.get(index + 2);
            if (Intersector.isPointInTriangle(x, y, a.x, a.y, b.x, b.y, c.x, c.y)) {
                return true;
            }
        }
        return false;
    }
}
