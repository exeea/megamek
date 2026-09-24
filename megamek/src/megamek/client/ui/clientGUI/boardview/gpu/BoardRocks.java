/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.badlogic.gdx.math.Vector3;

/**
 * The rock kit: a small, fixed library of faceted convex rocks, generated once from fixed seeds. Jointed blocks are a
 * box whose faces and corners are cut by near-axial fault planes; boulders are cut from every side; a shrub's masses
 * are boxes with every corner cut away. Every rock is a closed solid of flat, outward-wound convex polygons,
 * normalized to a unit horizontal extent with its base at z = 0.
 */
final class BoardRocks {
    static final int BLOCKS = 8;
    static final int BOULDERS = 8;
    /** Rounded masses for shrubs, at about two thirds of a boulder's triangles. */
    static final int BUSHES = 8;

    /** One flat face, counter-clockwise seen from outside. */
    record Polygon(Vector3[] points, Vector3 normal) { }

    /** A unit rock: horizontal extent about one, base at zero, {@code height} on top. */
    record Rock(List<Polygon> polygons, float height) { }

    private static final List<Rock> LIBRARY = library();

    private BoardRocks() { }

    /** A jointed block (sandstone, granite, concrete) or a rounded boulder; the variant wraps. */
    static Rock rock(boolean block, int variant) {
        return LIBRARY.get((block ? 0 : BLOCKS) + Math.floorMod(variant, block ? BLOCKS : BOULDERS));
    }

    /** One mass of a shrub; the variant wraps. */
    static Rock bush(int variant) {
        return LIBRARY.get(BLOCKS + BOULDERS + Math.floorMod(variant, BUSHES));
    }

    private static List<Rock> library() {
        List<Rock> result = new ArrayList<>();
        for (int i = 0; i < BLOCKS; i++) { result.add(build(0x5eed00L + i, true, false)); }
        for (int i = 0; i < BOULDERS; i++) { result.add(build(0xb01d00L + i, false, false)); }
        for (int i = 0; i < BUSHES; i++) { result.add(build(0xb05400L + i, false, true)); }
        return List.copyOf(result);
    }

    /** A jointed block, a boulder cut from every side, or a shrub's mass: a box with every corner cut away. */
    private static Rock build(long seed, boolean block, boolean bush) {
        Random random = new Random(seed);
        float ex = 1, ey = .62f + .3f * random.nextFloat(), ez = block ? .45f + .4f * random.nextFloat() : .6f + .3f * random.nextFloat();
        List<List<Vector3>> faces = box(ex, ey, ez);
        int cuts = block ? 5 + random.nextInt(4) : bush ? 8 + random.nextInt(3) : 12 + random.nextInt(6);
        for (int i = 0; i < cuts; i++) {
            Vector3 n;
            if (bush && i < 8) {
                // A rounded mass at few triangles: every corner of the box cut away, a little askew.
                n = new Vector3((i & 1) == 0 ? 1 : -1, (i & 2) == 0 ? 1 : -1, (i & 4) == 0 ? 1 : -1)
                      .add(gaussian(random, .2f), gaussian(random, .2f), gaussian(random, .2f)).nor();
            } else if (block && i < 4) {
                // Fault planes near the box axes: tilted sides and a sloping top, never a perfect box.
                int axis = random.nextInt(3);
                n = new Vector3(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0).scl(random.nextBoolean() ? 1 : -1);
                n.add(gaussian(random, .22f), gaussian(random, .22f), gaussian(random, .22f)).nor();
            } else if (block) {
                // Chipped corners and edges.
                n = new Vector3(random.nextBoolean() ? 1 : -1, random.nextBoolean() ? 1 : -1,
                      random.nextBoolean() ? 1 : -1).add(gaussian(random, .35f), gaussian(random, .35f),
                      gaussian(random, .35f)).nor();
            } else {
                n = new Vector3(gaussian(random, 1), gaussian(random, 1), gaussian(random, 1)).nor();
            }
            float support = (float) Math.sqrt(square(ex * n.x) + square(ey * n.y) + square(ez * n.z));
            float depth = support * (block ? (i < 4 ? .9f : .78f) + .1f * random.nextFloat()
                  : bush && i < 8 ? .7f + .1f * random.nextFloat() : .8f + .14f * random.nextFloat());
            faces = clip(faces, n, depth);
        }
        // Normalize: largest horizontal extent one, centred, base at zero.
        Vector3 min = new Vector3(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE);
        Vector3 max = new Vector3(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (List<Vector3> face : faces) {
            for (Vector3 p : face) {
                min.set(Math.min(min.x, p.x), Math.min(min.y, p.y), Math.min(min.z, p.z));
                max.set(Math.max(max.x, p.x), Math.max(max.y, p.y), Math.max(max.z, p.z));
            }
        }
        float scale = 1 / Math.max(max.x - min.x, max.y - min.y);
        Vector3 center = new Vector3((min.x + max.x) / 2, (min.y + max.y) / 2, min.z);
        List<Polygon> polygons = new ArrayList<>();
        List<Vector3> placed = new ArrayList<>();
        for (List<Vector3> face : faces) {
            Vector3[] points = new Vector3[face.size()];
            for (int i = 0; i < points.length; i++) {
                // Coincident corners of neighbouring faces stay one shared value, bit for bit.
                Vector3 p = new Vector3(face.get(i)).sub(center).scl(scale);
                Vector3 shared = null;
                for (Vector3 q : placed) {
                    if (q.dst2(p) < 1e-10f) { shared = q; break; }
                }
                if (shared == null) { placed.add(p); shared = p; }
                points[i] = shared;
            }
            Vector3 normal = newell(points);
            if (normal != null) { polygons.add(new Polygon(points, normal)); }
        }
        return new Rock(List.copyOf(polygons), (max.z - min.z) * scale);
    }

    private static List<List<Vector3>> box(float ex, float ey, float ez) {
        List<List<Vector3>> faces = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                Vector3 n = new Vector3(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0);
                List<Vector3> corners = new ArrayList<>();
                for (int a = -1; a <= 1; a += 2) {
                    for (int b = -1; b <= 1; b += 2) {
                        float[] c = new float[3];
                        c[axis] = sign;
                        c[(axis + 1) % 3] = a;
                        c[(axis + 2) % 3] = b;
                        corners.add(new Vector3(c[0] * ex, c[1] * ey, c[2] * ez));
                    }
                }
                faces.add(ordered(corners, n));
            }
        }
        return faces;
    }

    /** Keeps the half-space n.p <= d and closes the cut with a new face. */
    private static List<List<Vector3>> clip(List<List<Vector3>> faces, Vector3 n, float d) {
        List<List<Vector3>> result = new ArrayList<>();
        List<Vector3> section = new ArrayList<>();
        for (List<Vector3> face : faces) {
            List<Vector3> kept = new ArrayList<>();
            for (int i = 0; i < face.size(); i++) {
                Vector3 p = face.get(i), q = face.get((i + 1) % face.size());
                float dp = n.dot(p) - d, dq = n.dot(q) - d;
                if (dp <= 0) { kept.add(p); }
                if (dp == 0) { section.add(p); }
                if (dp < 0 && dq > 0 || dp > 0 && dq < 0) {
                    Vector3 x = new Vector3(p).lerp(q, dp / (dp - dq));
                    kept.add(x);
                    section.add(x);
                }
            }
            if (kept.size() >= 3) { result.add(kept); }
        }
        List<Vector3> cap = new ArrayList<>();
        for (Vector3 p : section) {
            if (cap.stream().noneMatch(q -> q.dst2(p) < 1e-10f)) { cap.add(p); }
        }
        if (cap.size() >= 3) {
            // Neighbouring faces computed the same crossing independently; reuse one value for each.
            List<Vector3> ordered = ordered(cap, n);
            for (List<Vector3> face : result) {
                for (int i = 0; i < face.size(); i++) {
                    for (Vector3 q : ordered) {
                        if (face.get(i).dst2(q) < 1e-10f) { face.set(i, q); }
                    }
                }
            }
            result.add(ordered);
        }
        return result;
    }

    /** Counter-clockwise around n, seen from outside. */
    private static List<Vector3> ordered(List<Vector3> points, Vector3 n) {
        Vector3 centroid = new Vector3();
        points.forEach(centroid::add);
        centroid.scl(1f / points.size());
        Vector3 u = Math.abs(n.x) < .9f ? new Vector3(1, 0, 0).crs(n).nor() : new Vector3(0, 1, 0).crs(n).nor();
        Vector3 v = new Vector3(n).crs(u);
        List<Vector3> result = new ArrayList<>(points);
        result.sort(Comparator.comparingDouble(p -> Math.atan2(new Vector3(p).sub(centroid).dot(v),
              new Vector3(p).sub(centroid).dot(u))));
        return result;
    }

    /** Area-weighted face normal, or null for a sliver left by nearly coincident cuts. */
    private static Vector3 newell(Vector3[] points) {
        Vector3 normal = new Vector3();
        for (int i = 0; i < points.length; i++) {
            Vector3 p = points[i], q = points[(i + 1) % points.length];
            normal.add((p.y - q.y) * (p.z + q.z), (p.z - q.z) * (p.x + q.x), (p.x - q.x) * (p.y + q.y));
        }
        return normal.len2() < 1e-10f ? null : normal.nor();
    }

    private static float gaussian(Random random, float sigma) { return (float) random.nextGaussian() * sigma; }

    private static float square(float value) { return value * value; }
}
