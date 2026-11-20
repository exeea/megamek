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
        falls(scene, surface, block);
        surface.fieldWater(line, mouths);
        surface.outline.addAll(List.of(line));
        surface.water.addAll(List.of(line));
        float[] plane = surface(board, h);
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
    static float[] surface(FieldBoard board, Coords h) {
        float[] plane = WaterLayer.plane(board, h);
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

    /**
     * The falls of field water hex {@code surface.tile}: over each open mouth into water more than two levels lower, as
     * river's are. Two that meet at their shared corner run on into each other there, as today's prow does: one point,
     * one tangent, the corner's bisector.
     */
    private static void falls(BoardScene scene, BoardSurface surface, ChunkField.Block block) {
        BoardScene.Tile tile = surface.tile;
        float[][] paths = new float[6][];
        BoardScene.Tile[] below = new BoardScene.Tile[6];
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile next = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(e)));
            if (fallsInto(tile, next, BoardGeometry.edgeDirection(e))) {
                paths[e] = path(surface, block, e);
                below[e] = next;
            }
        }
        boolean[] joined = new boolean[6];
        for (int e = 0; e < 6; e++) {
            float[] a = paths[e], b = paths[(e + 1) % 6];
            joined[e] = a != null && b != null && a[a.length - 3] == b[0] && a[a.length - 2] == b[1];
        }
        for (int e = 0; e < 6; e++) {
            float[] path = paths[e];
            if (path == null) { continue; }
            int o = path.length, previous = (e + 5) % 6;
            float water = path[2];
            Vector3 a = new Vector3(path[0], path[1], water), b = new Vector3(path[o - 3], path[o - 2], water);
            Vector3 start = joined[previous] ? corner(paths[previous], path) : new Vector3(path[3] - path[0], path[4] - path[1], 0).nor();
            Vector3 end = joined[e] ? corner(path, paths[(e + 1) % 6])
                  : new Vector3(path[o - 3] - path[o - 6], path[o - 2] - path[o - 5], 0).nor();
            surface.fieldFall(e, new BoardSurface.Crest(a, b, start, end, new Vector3(a), new Vector3(start.y, -start.x, 0), 0,
                  water, path), BoardGeometry.surfaceZ(below[e]), joined[previous], joined[e]);
        }
    }

    /**
     * Whether water hex {@code tile} pours into its neighbour {@code next} (in {@code direction}) over a fall, as river's
     * do: joined water more than two levels lower (BoardSurface#waterSlope), or lower frozen water.
     */
    static boolean fallsInto(BoardScene.Tile tile, BoardScene.Tile next, int direction) {
        return next != null && tile.joins(next, direction) && tile.elevation() > next.elevation()
              && !BoardSurface.waterSlope(tile, next);
    }

    /** The tangent two joined crests share at their corner: the mean of the arriving and the leaving piece. */
    private static Vector3 corner(float[] arriving, float[] leaving) {
        int o = arriving.length;
        return new Vector3(arriving[o - 3] - arriving[o - 6], arriving[o - 2] - arriving[o - 5], 0).nor()
              .add(new Vector3(leaving[3] - leaving[0], leaving[4] - leaving[1], 0).nor()).nor();
    }

    /**
     * The crest's path over edge e: the block's row along e (the points the wall's rows hang from) at the pool's water,
     * where the rim lies under it, cut where the rim rises out of it toward a bank; null where none of it does.
     */
    private static float[] path(BoardSurface surface, ChunkField.Block block, int e) {
        float[][] row = block.row(e);
        FieldSupport ground = new FieldSupport(block);
        float water = BoardGeometry.waterZ(surface.tile);
        int n = row.length;
        float[] over = new float[n];
        for (int j = 0; j < n; j++) { over[j] = water - ground.height(row[j][0], row[j][1]); }
        int first = 0, last = n - 1;
        while (first < n && over[first] <= 0) { first++; }
        while (last >= 0 && over[last] <= 0) { last--; }
        if (last <= first) { return null; }
        float[] path = new float[3 * (last - first + 3)];
        int o = 0;
        if (first > 0) { o = crossing(path, o, row[first - 1], row[first], over[first - 1], over[first], water); }
        for (int j = first; j <= last; j++) {
            path[o++] = row[j][0];
            path[o++] = row[j][1];
            path[o++] = water;
        }
        if (last < n - 1) { o = crossing(path, o, row[last + 1], row[last], over[last + 1], over[last], water); }
        return Arrays.copyOf(path, o);
    }

    /** Where the rim crosses the water between a row's sample out of it and one under it, written at o; the next o. */
    private static int crossing(float[] path, int o, float[] dry, float[] wet, float overDry, float overWet, float water) {
        float t = overWet / (overWet - overDry);
        path[o] = wet[0] + (dry[0] - wet[0]) * t;
        path[o + 1] = wet[1] + (dry[1] - wet[1]) * t;
        path[o + 2] = water;
        return o + 3;
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
