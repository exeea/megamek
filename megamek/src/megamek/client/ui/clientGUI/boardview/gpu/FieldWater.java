/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
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
     * A fall edge's dry rim, in metres: where its crest does not pour (the hex's depth lets less than the whole crest
     * flow, BoardSurface#fieldStretch), the water ends this far inside today's crest line; the strip between is the
     * ground's, rock standing above the water.
     */
    private static final float DRY_RIM = .8f;

    /** Crest point {@code crest} of hex h moved DRY_RIM toward the hex's centre in plan. */
    private static Vector3 rimmed(Vector3 crest, Coords h) {
        float dx = BoardGeometry.centerX(h) - crest.x, dy = BoardGeometry.centerY(h) - crest.y;
        float lip = BoardRelief.metres(DRY_RIM) / Math.max((float) Math.hypot(dx, dy), 1e-6f);
        return new Vector3(crest.x + dx * lip, crest.y + dy * lip, crest.z);
    }

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
            // The stretch of today's crest the hex's depth lets flow (BoardSurface#fieldFalls): the pool is pulled back
            // from the crest only there; beside it the water reaches today's crest line and nothing pours.
            float[] stretch = surface.fieldStretch(e);
            boolean whole = stretch != null && stretch[0] == 0 && stretch[1] == 1;
            List<Vector3> edge = new ArrayList<>();
            boolean poured = false;
            for (int i = 0; i <= n; i++) {
                int index = (e * n + i) % line.length;
                float s = i / (float) n;
                line[index] = today.crest(fall).point(s);
                boolean flows = stretch != null && s >= stretch[0] && s <= stretch[1];
                // Where the crest does not pour the water ends inside the crest line, against the dry rim's rock.
                Vector3 dry = whole ? line[index] : rimmed(line[index], h);
                pool[index] = pulled && flows ? new Vector3(today.water.get(index)) : dry;
                if (whole) {
                    edge.add(pool[index]);
                } else if (stretch == null || s < stretch[0] || s > stretch[1]) {
                    // (the shorter sheet's own first row comes once, between the dry stretches' points)
                    if (stretch != null && s > stretch[1] && !poured) { poured = sheetEdge(surface, e, edge); }
                    edge.add(dry);
                }
            }
            if (!whole && stretch != null && !poured) { sheetEdge(surface, e, edge); }
            lips[e] = new float[2 * edge.size()];
            for (int i = 0; i < edge.size(); i++) {
                lips[e][2 * i] = edge.get(i).x;
                lips[e][2 * i + 1] = edge.get(i).y;
            }
        }
        surface.fieldWater(line, mouths);
        surface.outline.addAll(List.of(line));
        surface.water.addAll(List.of(pool));
        // A pool of molten rock that stands alone (no mouth, no fall) takes today's sheet, which ends on today's lava
        // line: an opaque plane ends where it meets the lattice's bank, short of that line and angular. Joined pools
        // keep the plane, whose points along a mouth both hexes share.
        List<BoardSurface.Face> melt = today == null && mouths == 0 ? board.shore.melt(h) : null;
        float[] plane = melt == null ? surface(board, h, lips) : new float[0];
        for (int i = 0; i < plane.length; i += 9) {
            surface.waterFaces.add(new BoardSurface.Face(new Vector3(plane[i], plane[i + 1], plane[i + 2]),
                  new Vector3(plane[i + 3], plane[i + 4], plane[i + 5]), new Vector3(plane[i + 6], plane[i + 7], plane[i + 8]),
                  BoardSurface.Finish.TOP));
        }
        for (BoardSurface.Face face : melt == null ? List.<BoardSurface.Face>of() : melt) {
            surface.waterFaces.add(new BoardSurface.Face(laid(surface, face.a()), laid(surface, face.b()),
                  laid(surface, face.c()), BoardSurface.Finish.TOP));
        }
        if (board.shore.graded(h)) { surface.fieldGrade(); }
    }

    /** How far over the block's ground a molten sheet's point lies where that ground stands above the sheet, metres. */
    private static final float MELT_LIFT = .05f;

    /**
     * A point of today's molten sheet, on the field hex's ground where that stands above it: the lattice's bank is
     * coarser than today's lava line, and the sheet reaches that line over it.
     */
    private static Vector3 laid(BoardSurface surface, Vector3 p) {
        float ground = surface.height(p.x, p.y);
        float lift = BoardRelief.metres(MELT_LIFT);
        return new Vector3(p.x, p.y, Float.isFinite(ground) ? Math.max(p.z, ground + lift) : p.z);
    }

    /** The rings a descending stream's plane has between its outline and its centre, so it can follow its surface. */
    private static final int GRADED_RINGS = 8;

    /**
     * Field water hex h's surface as triangles, x, y and z a corner: the water layer's plane ({@link WaterLayer#plane}),
     * level, or on a descending stream in rings (#ringed), refined where it bends (#refined), on its surface (FieldShore#water),
     * one function, so it is the same at every detail and its points on an edge alike from either hex.
     */
    static float[] surface(FieldBoard board, Coords h) { return surface(board, h, new float[6][]); }

    /** That surface, its plane ending on the pulled-back pool edge {@code lips[e]} (x, y a point) of each fall edge e. */
    private static float[] surface(FieldBoard board, Coords h, float[][] lips) {
        float[] plane = lipped(board, h, WaterLayer.plane(board, h), lips);
        boolean graded = board.shore.graded(h);
        if (graded) { plane = ringed(board, h, plane); }
        // A descending stream's surface bends along its flow: the plane is refined where it does (#refined).
        for (int s = 0; graded && s < BEND_PASSES; s++) { plane = refined(board, h, plane); }
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
        // (a fall edge's fan may gain its crest's dry points and its sheet's first row)
        float[] out = new float[plane.length + 6 * 6 * (2 * BoardSurface.SHORE_SEGMENTS + 4)];
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

    /** Adds the first row of the sheet over edge e of a field hex's surface (its fall's crest pulled back, as GpuWaterfall#grid starts it); true. */
    private static boolean sheetEdge(BoardSurface surface, int e, List<Vector3> edge) {
        for (BoardSurface.Side fall : surface.waterfalls) {
            if (fall.edge() != e) { continue; }
            for (int k = 0; k <= BoardSurface.SHORE_SEGMENTS; k++) { edge.add(surface.fieldLip(fall, k / (float) BoardSurface.SHORE_SEGMENTS)); }
        }
        return true;
    }

    /** How often a descending stream's plane is refined where its surface bends, and how far (metres) an edge's middle may lie off the surface. */
    private static final int BEND_PASSES = 3;
    private static final float BEND = .025f;

    /**
     * Triangles (x, y a corner) of water hex h, each edge whose middle lies more than BEND off the water's surface
     * (FieldShore#water) halved there, turning as they did. An edge is judged by its two ends alone, so both triangles
     * at it, of this hex's plane or the next's, halve it alike and the mesh stays closed.
     */
    private static float[] refined(FieldBoard board, Coords h, float[] t) {
        float limit = BEND * BoardRelief.metres(1);
        float[] out = new float[t.length * 4];
        int o = 0;
        float[] q = new float[12];
        for (int i = 0; i < t.length; i += 6) {
            System.arraycopy(t, i, q, 0, 6);
            int bent = 0;
            for (int k = 0; k < 3; k++) {
                float ax = q[2 * k], ay = q[2 * k + 1], bx = q[2 * ((k + 1) % 3)], by = q[2 * ((k + 1) % 3) + 1];
                float mx = (ax + bx) / 2, my = (ay + by) / 2;
                q[6 + 2 * k] = mx;
                q[7 + 2 * k] = my;
                float off = board.shore.water(h, mx, my) - (board.shore.water(h, ax, ay) + board.shore.water(h, bx, by)) / 2;
                if (Math.abs(off) > limit) { bent |= 1 << k; }
            }
            // Corners 0, 1, 2 and the middles 3 (of 0-1), 4 (of 1-2), 5 (of 2-0).
            int[] pieces = switch (bent) {
                case 0 -> new int[] { 0, 1, 2 };
                case 1 -> new int[] { 0, 3, 2, 3, 1, 2 };
                case 2 -> new int[] { 0, 1, 4, 0, 4, 2 };
                case 4 -> new int[] { 0, 1, 5, 5, 1, 2 };
                case 3 -> new int[] { 3, 1, 4, 0, 3, 4, 0, 4, 2 };
                case 6 -> new int[] { 5, 4, 2, 0, 1, 4, 0, 4, 5 };
                case 5 -> new int[] { 0, 3, 5, 3, 1, 2, 3, 2, 5 };
                default -> new int[] { 0, 3, 5, 3, 1, 4, 5, 4, 2, 3, 4, 5 };
            };
            for (int corner : pieces) {
                out[o++] = q[2 * corner];
                out[o++] = q[2 * corner + 1];
            }
        }
        return Arrays.copyOf(out, o);
    }

    /**
     * The plane (x, y a corner) of water hex h with each triangle of its fan (from the centre to two points of the
     * outline) cut into GRADED_RINGS rings toward the centre: quads of about the outline's spacing, turning as they did.
     */
    private static float[] ringed(FieldBoard board, Coords h, float[] plane) {
        float cx = board.centerX(h), cy = board.centerY(h);
        float[] out = new float[plane.length * 2 * GRADED_RINGS];
        int o = 0;
        for (int i = 0; i < plane.length; i += 6) {
            if (plane[i] != cx || plane[i + 1] != cy) {
                System.arraycopy(plane, i, out, o, 6);
                o += 6;
                continue;
            }
            float px = plane[i + 2], py = plane[i + 3], qx = plane[i + 4], qy = plane[i + 5];
            for (int k = 0; k < GRADED_RINGS; k++) {
                float f = k / (float) GRADED_RINGS, g = (k + 1f) / GRADED_RINGS;
                float ax = px + (cx - px) * f, ay = py + (cy - py) * f, bx = qx + (cx - qx) * f, by = qy + (cy - qy) * f;
                if (k == GRADED_RINGS - 1) {
                    for (float v : new float[] { ax, ay, bx, by, cx, cy }) { out[o++] = v; }
                    continue;
                }
                float ex = px + (cx - px) * g, ey = py + (cy - py) * g, fx = qx + (cx - qx) * g, fy = qy + (cy - qy) * g;
                for (float v : new float[] { ax, ay, bx, by, fx, fy, ax, ay, fx, fy, ex, ey }) { out[o++] = v; }
            }
        }
        return Arrays.copyOf(out, o);
    }

    /**
     * The field water hex beside land hex {@code h} whose plane reaches over it (WaterLayer#bank) and whose surface stands
     * highest, or null: what its low ground lies under. Molten rock is none: it wets no ground.
     */
    static BoardScene.Tile beside(BoardScene scene, FieldBoard board, Coords h) {
        BoardScene.Tile best = null;
        for (int d = 0; d < 6; d++) {
            Coords next = h.translated(d);
            if (!WaterLayer.water(board, next) || !WaterLayer.bank(board, next, h)) { continue; }
            BoardScene.Tile tile = scene.tile(next);
            if (tile.liquid().molten()) { continue; }
            if (best == null || BoardGeometry.waterZ(tile) > BoardGeometry.waterZ(best)) { best = tile; }
        }
        return best;
    }

    /**
     * Per edge e of field hex h the open water across it that the field does not take (today's code draws it: a canal
     * beside a quay's plate), or null; null for none at all. A field wall under it takes its optics (ChunkMesh#of), as
     * today's walls under water do (GpuWaterCover#coveredFace).
     */
    static BoardScene.Tile[] untakenWater(BoardScene scene, FieldBoard board, Coords h) {
        BoardScene.Tile[] water = null;
        for (int e = 0; e < 6; e++) {
            Coords next = h.translated(BoardGeometry.edgeDirection(e));
            BoardScene.Tile open = board.inside(next) && !board.taken(next) ? GpuTerrain.openWater(scene.tile(next)) : null;
            if (open == null) { continue; }
            if (water == null) { water = new BoardScene.Tile[6]; }
            water[e] = open;
        }
        return water;
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
     * {@code film}: how much of a descending stream's formed stretch, its film and thread, lies at each texel
     * (FieldShore#formed), filled here.
     */
    static float[] texels(FieldBoard board, BoardScene scene, float spacing, int firstX, int firstY, int span, float[] depth,
          float[] film) {
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
            film[i] = below > 0 ? board.shore.formed(x, y) : 0;
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
