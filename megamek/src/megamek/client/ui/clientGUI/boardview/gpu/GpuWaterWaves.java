/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * A level surface resampled on a world-aligned triangular lattice, so waves keep their shape across every hex
     * and chunk. Every boundary sample is kept at every LOD but the most distant, so neighbours still share their
     * edges; concave bays keep only triangles inside their outline. Sloping channels keep their rounded descent.
     */
    static List<BoardSurface.Face> faces(List<BoardSurface.Face> source, TerrainLod lod) {
        if (source.isEmpty()) { return source; }
        float height = source.getFirst().a().z;
        var boundary = new LinkedHashSet<Vector3>();
        var edges = new HashMap<Segment, Boolean>();
        double area = 0;
        for (var face : source) {
            Vector3[] corners = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                if (Math.abs(corners[i].z - height) > .0001f) { return source; }
                boundary.add(corners[i]);
                Vector3 from = corners[i], to = corners[(i + 1) % 3];
                // An edge used by one face only lies on the outline; shared interior edges cancel out.
                if (edges.remove(Segment.of(from, to)) == null) { edges.put(Segment.of(from, to), Boolean.TRUE); }
            }
            area += Math.abs(cross(face.a(), face.b(), face.c()));
        }
        var points = new FloatArray(boundary.size() * 2);
        for (Vector3 point : boundary) { points.add(point.x, point.y); }
        float[] hull = new ConvexHull().computePolygon(points, false).toArray();
        boolean convex = Math.abs(Math.abs(hullArea(hull)) - area) <= Math.max(.01, area * .0001);
        if (lod == TerrainLod.DISTANT) {
            if (!convex) { return source; }
            // Displacement fades before this LOD's refinement threshold, including its hysteresis. Edge samples cannot
            // affect its flat silhouette, so a distant open hex needs four triangles rather than seventy.
            points.clear();
            points.addAll(hull, 0, hull.length - 2);
        } else {
            addLattice(points, source, edges.keySet(), BoardRelief.metres(switch (lod) {
                case FULL -> 3;
                case MEDIUM -> 6;
                default -> 12;
            }));
        }
        var indices = new DelaunayTriangulator().computeTriangles(points, false);
        var vertices = new Vector3[points.size / 2];
        for (int i = 0; i < vertices.length; i++) { vertices[i] = new Vector3(points.get(i * 2), points.get(i * 2 + 1), height); }
        var result = new ArrayList<BoardSurface.Face>(indices.size / 3);
        double covered = 0;
        for (int i = 0; i < indices.size; i += 3) {
            Vector3 a = vertices[indices.get(i)], b = vertices[indices.get(i + 1)], c = vertices[indices.get(i + 2)];
            float turn = cross(a, b, c);
            if (Math.abs(turn) <= .00001f
                  || !convex && !inside(source, (a.x + b.x + c.x) / 3, (a.y + b.y + c.y) / 3)) { continue; }
            result.add(new BoardSurface.Face(a, turn > 0 ? b : c, turn > 0 ? c : b, BoardSurface.Finish.TOP));
            covered += Math.abs(turn);
        }
        // A bay whose outline the triangulation does not follow keeps its canonical faces rather than a gap or a lip.
        return Math.abs(covered - area) <= Math.max(.01, area * .0001) ? result : source;
    }

    /**
     * Lattice points strictly inside the surface and clear of its outline. The lattice is fixed in world space,
     * rows half a spacing apart in x, so neighbouring hexes sample the same wave field in the same pattern.
     */
    private static void addLattice(FloatArray points, List<BoardSurface.Face> source, Iterable<Segment> outline,
          float spacing) {
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (int i = 0; i < points.size; i += 2) {
            minX = Math.min(minX, points.get(i)); maxX = Math.max(maxX, points.get(i));
            minY = Math.min(minY, points.get(i + 1)); maxY = Math.max(maxY, points.get(i + 1));
        }
        float rowStep = spacing * .8660254f, clearance = spacing * .35f;
        for (int row = (int) Math.ceil(minY / rowStep); row * rowStep < maxY; row++) {
            float y = row * rowStep, offset = (row & 1) * spacing * .5f;
            for (int column = (int) Math.ceil((minX - offset) / spacing); column * spacing + offset < maxX; column++) {
                float x = column * spacing + offset;
                if (!inside(source, x, y)) { continue; }
                boolean clear = true;
                for (Segment edge : outline) {
                    if (Intersector.distanceSegmentPoint(edge.ax, edge.ay, edge.bx, edge.by, x, y) < clearance) {
                        clear = false;
                        break;
                    }
                }
                if (clear) { points.add(x, y); }
            }
        }
    }

    private static boolean inside(List<BoardSurface.Face> faces, float x, float y) {
        for (var face : faces) {
            if (Intersector.isPointInTriangle(x, y, face.a().x, face.a().y, face.b().x, face.b().y,
                  face.c().x, face.c().y)) { return true; }
        }
        return false;
    }

    private static double hullArea(float[] hull) {
        double area = 0;
        for (int i = 0; i < hull.length - 2; i += 2) { area += (double) hull[i] * hull[i + 3] - (double) hull[i + 2] * hull[i + 1]; }
        return area;
    }

    /** An undirected edge: the same segment whichever face lists it and in which direction. */
    private record Segment(float ax, float ay, float bx, float by) {
        static Segment of(Vector3 a, Vector3 b) {
            boolean ordered = a.x < b.x || a.x == b.x && a.y <= b.y;
            return ordered ? new Segment(a.x, a.y, b.x, b.y) : new Segment(b.x, b.y, a.x, a.y);
        }
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 c) { return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x); }

    static String vertex(String source) {
        String waves = GpuShaderSource.read("water-waves.vert")
              .replace("// water-wave-functions", GpuShaderSource.read("water-wave.glsl"));
        return source.replace("void main() {", waves + "\nvoid main() {")
              .replace("gl_Position = u_projViewTrans * pos;", "waterDisplace(pos);\ngl_Position = u_projViewTrans * pos;");
    }
}
