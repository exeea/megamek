/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * A height-field water hex ({@link FieldShore}) as today's water code reads it: its {@link BoardSurface} carries the
 * water layer's plane ({@link WaterLayer}) as its water faces, the hex's outline as its waterline, and its ground
 * under the water as bed; the water shader's texels on it and on the banks beside it are the field's values at each
 * texel's place, so they are the same at every detail and in every chunk.
 */
final class FieldWater {
    private FieldWater() { }

    /**
     * Gives the surface of field water hex {@code surface.tile} its water: the plane, the outline at the water's
     * height, the edges water runs on through, and the water's cut faces along the board's edge down to the block's
     * ground.
     */
    static void fill(BoardScene scene, BoardSurface surface, FieldBoard board, ChunkField.Block block) {
        BoardScene.Tile tile = surface.tile;
        Coords h = tile.coords();
        int n = BoardSurface.SHORE_SEGMENTS, mouths = 0;
        Vector3[] line = new Vector3[6 * n];
        float[] p = new float[2];
        for (int e = 0; e < 6; e++) {
            for (int j = 0; j < n; j++) {
                WaterLayer.along(board.cornerX(h, e), board.cornerY(h, e), board.cornerX(h, e + 1), board.cornerY(h, e + 1), j, n, p);
                line[e * n + j] = new Vector3(p[0], p[1], board.shore.water(h, p[0], p[1]));
            }
            BoardScene.Tile next = scene.tile(h.translated(BoardGeometry.edgeDirection(e)));
            if (next == null || tile.joins(next, BoardGeometry.edgeDirection(e))) { mouths |= 1 << e; }
            if (next == null) { cut(surface, block, e, board.shore, h); }
        }
        // The hex's falls are today's (BoardSurface#fieldFalls): along each, the line is today's crest and the water
        // today's pool, pulled back from the crest for the sheet's lip: pool, lip and sheet meet as they do today.
        BoardSurface today = falls(scene, tile) ? surface.fieldFalls() : null;
        Vector3[] pool = line.clone();
        float[][] lips = new float[6][];
        for (BoardSurface.Side fall : today == null ? List.<BoardSurface.Side>of() : today.waterfalls) {
            int e = fall.edge();
            boolean pulled = today.water.size() == line.length;
            lips[e] = new float[2 * (n + 1)];
            for (int i = 0; i <= n; i++) {
                int index = (e * n + i) % line.length;
                line[index] = today.crest(fall).point(i / (float) n);
                pool[index] = pulled ? new Vector3(today.water.get(index)) : line[index];
                lips[e][2 * i] = pool[index].x;
                lips[e][2 * i + 1] = pool[index].y;
            }
        }
        surface.fieldWater(line, mouths);
        surface.outline.addAll(List.of(line));
        surface.water.addAll(List.of(pool));
        float[] plane = surface(board, h, lips);
        for (int i = 0; i < plane.length; i += 9) {
            surface.waterFaces.add(new BoardSurface.Face(new Vector3(plane[i], plane[i + 1], plane[i + 2]),
                  new Vector3(plane[i + 3], plane[i + 4], plane[i + 5]), new Vector3(plane[i + 6], plane[i + 7], plane[i + 8]),
                  BoardSurface.Finish.TOP));
        }
        if (board.shore.graded(h)) { surface.fieldGrade(); }
    }

    /** How often a descending stream's plane is split in four, so it follows today's graded water (its rings). */
    private static final int GRADED_SPLITS = 2;

    /**
     * Field water hex h's surface as triangles, x, y and z a corner: the water layer's plane ({@link WaterLayer#plane}),
     * level, or on a descending stream split GRADED_SPLITS times and laid on today's graded water (FieldShore#water),
     * one function, so it is the same at every detail and its points on an edge alike from either hex.
     */
    static float[] surface(FieldBoard board, Coords h) { return surface(board, h, new float[6][]); }

    /** That surface, its plane ending on the pulled-back pool edge {@code lips[e]} (x, y a point) of each fall edge e. */
    private static float[] surface(FieldBoard board, Coords h, float[][] lips) {
        float[] plane = lipped(board, h, WaterLayer.plane(board, h), lips);
        boolean graded = board.shore.graded(h);
        for (int s = 0; graded && s < GRADED_SPLITS; s++) { plane = split(plane); }
        float[] out = new float[plane.length / 2 * 3];
        for (int i = 0, o = 0; i < plane.length; i += 2, o += 3) {
            out[o] = plane[i];
            out[o + 1] = plane[i + 1];
            out[o + 2] = board.shore.water(h, plane[i], plane[i + 1]);
        }
        return out;
    }

    /**
     * The plane (x, y a corner) with its fan to each fall edge's rim replaced by a fan to that fall's pulled-back pool
     * edge, closed to the rim's two ends: the pool ends exactly where the sheet's lip starts, as today's does.
     */
    private static float[] lipped(FieldBoard board, Coords h, float[] plane, float[][] lips) {
        float cx = board.centerX(h), cy = board.centerY(h), sx = 0, sy = 0;
        float[] out = new float[plane.length + 6 * 6 * (BoardSurface.SHORE_SEGMENTS + 2)];
        int o = 0, spokes = 0;
        for (int i = 0; i < plane.length; i += 6) {
            // The fan's triangles start at the centre, FULL of them an edge, in the edges' order (WaterLayer#plane).
            boolean spoke = plane[i] == cx && plane[i + 1] == cy;
            int e = spoke ? spokes / FieldBoard.FULL : -1, j = spoke ? spokes % FieldBoard.FULL : -1;
            if (spoke) { spokes++; }
            if (e < 0 || e > 5 || lips[e] == null) {
                System.arraycopy(plane, i, out, o, 6);
                o += 6;
                continue;
            }
            if (j == 0) {
                sx = plane[i + 2];
                sy = plane[i + 3];
            }
            if (j < FieldBoard.FULL - 1) { continue; }
            float[] lip = lips[e];
            float px = sx, py = sy;
            for (int k = 0; k <= lip.length; k += 2) {
                float qx = k < lip.length ? lip[k] : plane[i + 4], qy = k < lip.length ? lip[k + 1] : plane[i + 5];
                // (a lip's end may lie on the rim's, or just past it: only triangles that turn as the plane's do)
                if ((px - cx) * (qy - cy) - (py - cy) * (qx - cx) > 0) {
                    for (float v : new float[] { cx, cy, px, py, qx, qy }) { out[o++] = v; }
                }
                px = qx;
                py = qy;
            }
        }
        return Arrays.copyOf(out, o);
    }

    /** Triangles (x, y a corner) each split in four at its edges' middles, turning as they did. */
    private static float[] split(float[] t) {
        float[] out = new float[t.length * 4];
        int o = 0;
        for (int i = 0; i < t.length; i += 6) {
            float ax = t[i], ay = t[i + 1], bx = t[i + 2], by = t[i + 3], cx = t[i + 4], cy = t[i + 5];
            float abx = (ax + bx) / 2, aby = (ay + by) / 2, bcx = (bx + cx) / 2, bcy = (by + cy) / 2;
            float cax = (cx + ax) / 2, cay = (cy + ay) / 2;
            for (float[] q : new float[][] { { ax, ay, abx, aby, cax, cay }, { abx, aby, bx, by, bcx, bcy },
                  { cax, cay, bcx, bcy, cx, cy }, { abx, aby, bcx, bcy, cax, cay } }) {
                System.arraycopy(q, 0, out, o, 6);
                o += 6;
            }
        }
        return out;
    }

    /**
     * The field water hex beside land hex {@code h} whose plane reaches over it (WaterLayer#bank) and whose surface stands
     * highest, or null: what its low ground lies under.
     */
    static BoardScene.Tile beside(BoardScene scene, FieldBoard board, Coords h) {
        BoardScene.Tile best = null;
        for (int d = 0; d < 6; d++) {
            Coords next = h.translated(d);
            if (!WaterLayer.water(board, next) || !WaterLayer.bank(board, next, h)) { continue; }
            BoardScene.Tile tile = scene.tile(next);
            if (best == null || BoardGeometry.waterZ(tile) > BoardGeometry.waterZ(best)) { best = tile; }
        }
        return best;
    }

    /** Whether field water hex {@code tile} pours over a fall on any of its edges (#fallsInto). */
    private static boolean falls(BoardScene scene, BoardScene.Tile tile) {
        for (int e = 0; e < 6; e++) {
            int direction = BoardGeometry.edgeDirection(e);
            if (fallsInto(tile, scene.tile(tile.coords().translated(direction)), direction)) { return true; }
        }
        return false;
    }

    /**
     * Whether water hex {@code tile} pours into its neighbour {@code next} (in {@code direction}) over a fall, as river's
     * do: joined water more than two levels lower (BoardSurface#waterSlope), or lower frozen water.
     */
    static boolean fallsInto(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return next != null && tile.joins(next, direction) && tile.elevation() > next.elevation()
              && !BoardSurface.waterSlope(tile, next);
    }

    /** The water's cut face along edge e, which the board's edge cuts through: from the block's ground up to the water. */
    private static void cut(BoardSurface surface, ChunkField.Block block, int e, FieldShore shore, Coords h) {
        FieldSupport ground = new FieldSupport(block);
        float[][] row = block.row(e);
        float tolerance = .001f * BoardGeometry.hexScale();
        for (int j = 0; j + 1 < row.length; j++) {
            Vector3 a = new Vector3(row[j][0], row[j][1], ground.height(row[j][0], row[j][1]));
            Vector3 b = new Vector3(row[j + 1][0], row[j + 1][1], ground.height(row[j + 1][0], row[j + 1][1]));
            Vector3 topA = new Vector3(a.x, a.y, shore.water(h, a.x, a.y)), topB = new Vector3(b.x, b.y, shore.water(h, b.x, b.y));
            if (b.z < topB.z - tolerance) { surface.cutFaces.add(new BoardSurface.Face(a, b, topB, BoardSurface.Finish.TOP, e)); }
            if (a.z < topA.z - tolerance) { surface.cutFaces.add(new BoardSurface.Face(a, topB, topA, BoardSurface.Finish.TOP, e)); }
        }
    }

    /** A field water hex's top faces as its ground under water: bed where wholly under z, shore where z crosses them. */
    static void bed(List<BoardSurface.Face> faces, float z) {
        for (int i = 0; i < faces.size(); i++) {
            BoardSurface.Face face = faces.get(i);
            float low = Math.min(face.a().z, Math.min(face.b().z, face.c().z));
            if (face.finish() != BoardSurface.Finish.TOP || low >= z) { continue; }
            boolean under = Math.max(face.a().z, Math.max(face.b().z, face.c().z)) <= z;
            faces.set(i, new BoardSurface.Face(face.a(), face.b(), face.c(),
                  under ? BoardSurface.Finish.BED : BoardSurface.Finish.SHORE, face.landEdge()));
        }
    }

    /**
     * The water shader's values on field water: for each texel in a field water hex, or in a field land hex beside
     * one, the signed distance to the waterline in hex widths (positive in the water), returned, and the water's depth
     * in levels, written over {@code depth}; NaN and untouched elsewhere. Texels lie row by row, {@code span} to a row,
     * the first one's centre at (firstX + .5, firstY + .5) * spacing. Each value is the field's at the texel's centre
     * ({@link FieldBoard#height}, {@link FieldShore#field}), whatever detail its hex is drawn at.
     */
    static float[] texels(FieldBoard board, BoardScene scene, float spacing, int firstX, int firstY, int span, float[] depth) {
        float[] shore = new float[depth.length];
        Arrays.fill(shore, Float.NaN);
        int rows = depth.length / span;
        FieldBoard.Shares shares = board.new Shares(firstX * spacing, firstY * spacing, (firstX + span) * spacing,
              (firstY + rows) * spacing);
        Map<Coords, List<FieldBoard.Segment>> segments = new HashMap<>();
        float least = .001f;
        float[] out = new float[8];
        for (int i = 0; i < depth.length; i++) {
            float x = (firstX + i % span + .5f) * spacing, y = (firstY + i / span + .5f) * spacing;
            Coords h = GpuStarShells.hexAt(x, y);
            if (!board.taken(h)) { continue; }
            h = WaterLayer.owner(board, h, x, y);
            float water = water(board, scene, h, x, y);
            if (Float.isNaN(water)) { continue; }
            float below = -1;
            if (WaterLayer.water(board, h) || board.shore.near(x, y)) {
                board.height(h, segments.computeIfAbsent(h, board::segments), shares, x, y, out);
                below = (water - out[0]) / BoardGeometry.level();
            }
            float distance = board.shore.distance(x, y) * BoardGeometry.hexScale() / BoardGeometry.width();
            depth[i] = below;
            shore[i] = below > 0 ? Math.max(least, distance) : Math.min(-least, distance);
        }
        return shore;
    }

    /** The water's height at (x, y) in field hex h: its own, else its field water neighbour's nearest the point, else NaN. */
    private static float water(FieldBoard board, BoardScene scene, Coords h, float x, float y) {
        if (WaterLayer.water(board, h)) { return board.shore.water(h, x, y); }
        float water = Float.NaN, nearest = Float.POSITIVE_INFINITY;
        for (int d = 0; d < 6; d++) {
            Coords next = h.translated(d);
            if (!WaterLayer.water(board, next) || !WaterLayer.bank(board, next, h)) { continue; }
            float dx = x - board.centerX(next), dy = y - board.centerY(next);
            if (dx * dx + dy * dy < nearest) {
                nearest = dx * dx + dy * dy;
                water = board.shore.water(next, x, y);
            }
        }
        return water;
    }
}
