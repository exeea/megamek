/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.badlogic.gdx.math.ConvexHull;
import com.badlogic.gdx.math.DelaunayTriangulator;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;

/** Render-only wave sampling. The canonical shoreline, bed, flowing bulge and picking surface remain unchanged. */
final class GpuWaterWaves {
    private GpuWaterWaves() { }

    static List<BoardSurface.Face> faces(List<BoardSurface.Face> source, TerrainLod lod) {
        if (source.isEmpty()) { return source; }
        // A sloping channel already samples its rounded descent. Concave shores retain their exact triangulation.
        float height = source.getFirst().a().z;
        var boundary = new LinkedHashSet<Vector3>();
        double area = 0;
        for (var face : source) {
            for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                if (Math.abs(point.z - height) > .0001f) { return source; }
                boundary.add(point);
            }
            area += Math.abs(cross(face.a(), face.b(), face.c()));
        }
        var points = new FloatArray(boundary.size() * 2);
        for (Vector3 point : boundary) { points.add(point.x, point.y); }
        float[] hull = new ConvexHull().computePolygon(points, false).toArray();
        double hullArea = 0;
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (int i = 0; i < hull.length - 2; i += 2) {
            hullArea += (double) hull[i] * hull[i + 3] - (double) hull[i + 2] * hull[i + 1];
            minX = Math.min(minX, hull[i]); maxX = Math.max(maxX, hull[i]);
            minY = Math.min(minY, hull[i + 1]); maxY = Math.max(maxY, hull[i + 1]);
        }
        if (Math.abs(Math.abs(hullArea) - area) > Math.max(.01, area * .0001)) { return source; }
        if (lod == TerrainLod.DISTANT) {
            // Displacement fades before this LOD's refinement threshold, including its hysteresis. Edge samples cannot affect
            // its flat silhouette, so a distant open hex needs four triangles rather than seventy.
            points.clear();
            points.addAll(hull, 0, hull.length - 2);
        }
        // Keep every boundary sample at every LOD. A regular interior avoids subdividing the ear clipper's long,
        // thin triangles thousands of times. Its points lie strictly inside the existing convex footprint.
        float spacing = BoardRelief.metres(switch (lod) {
            case FULL -> 4;
            case MEDIUM -> 6;
            case COARSE -> 12;
            case DISTANT -> 24;
        });
        for (float y = minY + spacing * .5f; lod != TerrainLod.DISTANT && y < maxY; y += spacing) {
            for (float x = minX + spacing * .5f; x < maxX; x += spacing) {
                if (Intersector.isPointInPolygon(hull, 0, hull.length, x, y)) { points.add(x, y); }
            }
        }
        var indices = new DelaunayTriangulator().computeTriangles(points, false);
        var vertices = new Vector3[points.size / 2];
        for (int i = 0; i < vertices.length; i++) { vertices[i] = new Vector3(points.get(i * 2), points.get(i * 2 + 1), height); }
        var result = new ArrayList<BoardSurface.Face>(indices.size / 3);
        for (int i = 0; i < indices.size; i += 3) {
            Vector3 a = vertices[indices.get(i)], b = vertices[indices.get(i + 1)], c = vertices[indices.get(i + 2)];
            float turn = cross(a, b, c);
            if (Math.abs(turn) > .00001f) {
                result.add(new BoardSurface.Face(a, turn > 0 ? b : c, turn > 0 ? c : b, BoardSurface.Finish.TOP));
            }
        }
        return result;
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 c) { return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x); }

    static String vertex(String source) {
        String waves = GpuShaderSource.read("water-waves.vert")
              .replace("// water-wave-functions", GpuShaderSource.read("water-wave.glsl"));
        return source.replace("void main() {", waves + "\nvoid main() {")
              .replace("gl_Position = u_projViewTrans * pos;", "waterDisplace(pos);\ngl_Position = u_projViewTrans * pos;");
    }
}
