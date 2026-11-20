/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/**
 * A height-field hex's ground at a point ({@link TerrainField}): the height of the drawn triangle of its block that holds
 * the point, its walls left out. The triangles are bucketed once on a small grid over the hex, so a lookup tests a few
 * of them. Plain arrays, built per hex by its adapter and owned by it; what stands on the hex (field stones, rocks,
 * boulders, rough cover) is placed with it.
 */
final class FieldSupport {
    /** The bucket grid's cells along each side of the hex's bounds. */
    private static final int CELLS = 8;
    private final float[] x, y, z;
    /** The drawn ground's triangles, three sample indices each: level tops, top cover and step faces. */
    private final int[] triangles;
    private final float minX, minY, cellX, cellY, tolerance;
    /** Per bucket, the first index into {@link #triangles} of each triangle whose bounds reach it. */
    private final int[][] buckets = new int[CELLS * CELLS][];

    FieldSupport(ChunkField.Block block) {
        x = block.x();
        y = block.y();
        z = block.z();
        int[] tops = block.levelTops(), ground = block.ground(), faces = block.faces();
        triangles = new int[tops.length + ground.length + faces.length];
        System.arraycopy(tops, 0, triangles, 0, tops.length);
        System.arraycopy(ground, 0, triangles, tops.length, ground.length);
        System.arraycopy(faces, 0, triangles, tops.length + ground.length, faces.length);
        float[] b = block.bounds();
        minX = b[0];
        minY = b[1];
        cellX = Math.max(1e-3f, (b[3] - b[0]) / CELLS);
        cellY = Math.max(1e-3f, (b[4] - b[1]) / CELLS);
        // As BoardSurface#sampleHeight's second pass: rounding gaps along shared edges are recovered.
        tolerance = .001f * BoardGeometry.hexScale();
        int[] counts = new int[CELLS * CELLS];
        for (int pass = 0; pass < 2; pass++) {
            for (int t = 0; t < triangles.length; t += 3) {
                int a = triangles[t], c = triangles[t + 1], d = triangles[t + 2];
                int i0 = cell(Math.min(x[a], Math.min(x[c], x[d])) - tolerance, minX, cellX);
                int i1 = cell(Math.max(x[a], Math.max(x[c], x[d])) + tolerance, minX, cellX);
                int j0 = cell(Math.min(y[a], Math.min(y[c], y[d])) - tolerance, minY, cellY);
                int j1 = cell(Math.max(y[a], Math.max(y[c], y[d])) + tolerance, minY, cellY);
                for (int j = j0; j <= j1; j++) {
                    for (int i = i0; i <= i1; i++) {
                        int k = j * CELLS + i;
                        if (pass == 0) { counts[k]++; } else { buckets[k][counts[k]++] = t; }
                    }
                }
            }
            if (pass == 0) {
                for (int k = 0; k < counts.length; k++) { buckets[k] = new int[counts[k]]; }
                java.util.Arrays.fill(counts, 0);
            }
        }
    }

    private static int cell(float v, float min, float size) {
        return Math.clamp((int) Math.floor((v - min) / size), 0, CELLS - 1);
    }

    /** The ground's height at (px, py), as BoardSurface#sampleHeight finds it on the drawn triangles; NaN off the hex. */
    float height(float px, float py) {
        int[] bucket = buckets[cell(py, minY, cellY) * CELLS + cell(px, minX, cellX)];
        float result = Float.NEGATIVE_INFINITY;
        for (int pass = 0; pass < 2 && result == Float.NEGATIVE_INFINITY; pass++) {
            for (int t : bucket) {
                int a = triangles[t], c = triangles[t + 1], d = triangles[t + 2];
                result = Math.max(result, height(x[a], y[a], z[a], x[c], y[c], z[c], x[d], y[d], z[d], px, py,
                      pass == 0 ? 0 : tolerance));
            }
        }
        return result == Float.NEGATIVE_INFINITY ? Float.NaN : result;
    }

    /**
     * The height at (x, y) of the triangle a, b, c by its barycentric weights, or negative infinity outside it; within
     * {@code tolerance} (world units) of an edge the point counts as on it.
     */
    static float height(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
          float x, float y, float tolerance) {
        if (x < Math.min(ax, Math.min(bx, cx)) - tolerance
              || x > Math.max(ax, Math.max(bx, cx)) + tolerance
              || y < Math.min(ay, Math.min(by, cy)) - tolerance
              || y > Math.max(ay, Math.max(by, cy)) + tolerance) { return Float.NEGATIVE_INFINITY; }
        float denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
        if (Math.abs(denominator) < 0.00001f) {
            return Float.NEGATIVE_INFINITY;
        }
        float u = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator;
        float v = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator;
        float w = 1 - u - v;
        // World-space tolerance keeps rounded edge samples on thin bank triangles even far from the origin.
        // A fixed barycentric tolerance can miss the bank and drop the exposed wall to the riverbed.
        float squared = tolerance * tolerance / (denominator * denominator);
        if ((u >= 0 || u * u <= squared * square(bx - cx, by - cy))
              && (v >= 0 || v * v <= squared * square(cx - ax, cy - ay))
              && (w >= 0 || w * w <= squared * square(ax - bx, ay - by))) {
            u = Math.max(0, u);
            v = Math.max(0, v);
            w = Math.max(0, w);
            return (u * az + v * bz + w * cz) / (u + v + w);
        }
        return Float.NEGATIVE_INFINITY;
    }

    private static float square(float dx, float dy) { return dx * dx + dy * dy; }
}
