/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;

class GpuBuildingInteriorTest {
    @Test
    void lightBuildingWallsEncloseItsCentreWithoutTheRoofBorder() {
        var file = new com.badlogic.gdx.files.FileHandle(megamek.client.ui.clientGUI.boardview.BoardArtwork
              .customBuildingFile("buildings/saxarba/building_light/building_light_00"));
        var shapes = BoardShape.shapes(RigidGlb.loadLods(file).getFirst(), true);
        for (var entry : shapes.entrySet()) {
            List<Vector3> geometry = new ArrayList<>();
            for (var polygon : entry.getValue().polygons()) { geometry.addAll(List.of(polygon.points())); }
            var area = entry.getKey().contains("roof") ? GpuBuildingInterior.area(geometry)
                  : GpuBuildingInterior.walls(geometry, 9);
            assertTrue(area.contains(0, 0), entry.getKey());
        }
    }

    @Test
    void fortressFootprintRemainsCompact() {
        var data = RigidGlb.loadLods(GpuBuildingTest.file()).get(1);
        for (var mesh : data.meshes) {
            for (int i = 0; i < mesh.vertices.length; i += RigidGlb.STRIDE) {
                mesh.vertices[i] += 71; mesh.vertices[i + 1] -= 23; mesh.vertices[i + 2] += 43;
            }
        }
        for (var node : data.nodes) { node.translation.add(900, -400, 257); }
        var shapes = BoardShape.shapes(data, true);
        java.awt.geom.Area volume = null;
        for (var entry : shapes.entrySet()) {
            List<Vector3> geometry = new ArrayList<>();
            for (var polygon : entry.getValue().polygons()) { geometry.addAll(List.of(polygon.points())); }
            var area = entry.getKey().contains("roof") ? GpuBuildingInterior.area(geometry)
                  : GpuBuildingInterior.walls(geometry, 9);
            assertTrue(area.contains(0, 0), "The wall outline must enclose usable volume");
            if (volume == null) { volume = area; } else { volume.intersect(area); }
        }
        var footprint = GpuBuildingInterior.triangles(volume);
        assertTrue(footprint.size() < 300, "The footprint must not inherit facade tessellation");
    }

    @Test
    void wallSectionsKeepExternalThicknessAndExcludeLedgesAndOpenPanels() {
        List<Vector3> walls = new ArrayList<>();
        rectangle(walls, -13, -25, 13, 25);
        rectangle(walls, -12, -24, 12, 24);
        // A projecting sidewalk at the base and a disconnected decorative panel must not expand the storey.
        walls.addAll(List.of(new Vector3(-18, -30, 0), new Vector3(18, -30, 0), new Vector3(18, 30, 0)));
        wall(walls, 16, -20, 16, 20);
        // Double-sided imports and repeated material seams retain the same outline.
        var original = List.copyOf(walls);
        for (int i = 0; i < original.size(); i += 3) {
            walls.add(original.get(i + 2)); walls.add(original.get(i + 1)); walls.add(original.get(i));
        }
        var footprint = GpuBuildingInterior.walls(walls, 9);
        assertTrue(footprint.contains(12.5, 0), "The wall's thickness belongs to the external footprint");
        assertTrue(footprint.contains(0, 24.5));
        assertTrue(footprint.contains(0, 0));
        assertFalse(footprint.contains(13.1, 0));
        assertFalse(footprint.contains(0, 25.1));
        assertFalse(footprint.contains(16, 0));
    }

    @Test
    void wallSectionsPreserveConcaveOutlinesAndDisconnectedWings() {
        List<Vector3> walls = new ArrayList<>();
        float[][] outline = { { 0, 0 }, { 30, 0 }, { 30, 10 }, { 10, 10 }, { 10, 30 }, { 0, 30 } };
        for (int i = 0; i < outline.length; i++) {
            float[] a = outline[i], b = outline[(i + 1) % outline.length];
            wall(walls, a[0], a[1], b[0], b[1]);
        }
        rectangle(walls, 40, 0, 50, 10);
        var footprint = GpuBuildingInterior.walls(walls, 9);
        assertTrue(footprint.contains(5, 25));
        assertTrue(footprint.contains(25, 5));
        assertTrue(footprint.contains(45, 5));
        assertFalse(footprint.contains(20, 20));
        assertFalse(footprint.contains(35, 5));
    }

    private static void rectangle(List<Vector3> triangles, float x1, float y1, float x2, float y2) {
        wall(triangles, x1, y1, x2, y1);
        wall(triangles, x2, y1, x2, y2);
        wall(triangles, x2, y2, x1, y2);
        wall(triangles, x1, y2, x1, y1);
    }

    private static void wall(List<Vector3> triangles, float x1, float y1, float x2, float y2) {
        Vector3 a = new Vector3(x1, y1, 0), b = new Vector3(x2, y2, 0);
        Vector3 c = new Vector3(x2, y2, 18), d = new Vector3(x1, y1, 18);
        triangles.addAll(List.of(a, b, c, a, c, d));
    }
}
