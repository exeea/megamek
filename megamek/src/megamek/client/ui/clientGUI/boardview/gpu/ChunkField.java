/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.board.Coords;

/**
 * One terrain chunk's height field at one detail ({@link TerrainField}): a block of samples per hex the field takes, in
 * plain arrays. Built on the chunk's preparation worker from the scene revision's {@link FieldBoard}; its blocks feed
 * the chunk's {@link ChunkMesh} and the hexes' {@link BoardSurface} adapters (support, picking, the old passes).
 */
final class ChunkField {
    /**
     * A wall's rows a level of its step, at fixed heights above the lower level, so the walls of one cliff share
     * their rows whatever their feet; medium detail takes every second, coarse one a level.
     */
    private static final int ROWS_A_LEVEL = 6;

    final FieldBoard board;
    /** The scene whose covers the blocks' samples carry ({@link FieldCover}); null without the compact ground. */
    final BoardScene scene;
    final TerrainLod lod;
    final float floor;
    private final Map<Coords, Block> blocks = new ConcurrentHashMap<>();
    /** The banks' level-set grids, one per chunk of {@link TerrainLod#CHUNK_SIZE} hexes. */
    private final Map<Coords, FieldBoard.Shares> shares = new ConcurrentHashMap<>();
    /** The detail this one morphs to, whose blocks' portal cuts its own follow (FieldStamps); null for the coarsest. */
    private final ChunkField coarser;

    ChunkField(FieldBoard board, TerrainLod lod, float floor) { this(board, null, lod, floor); }

    ChunkField(FieldBoard board, BoardScene scene, TerrainLod lod, float floor) {
        this.board = board;
        this.scene = scene;
        this.lod = lod;
        this.floor = floor;
        coarser = switch (lod) {
            case FULL -> new ChunkField(board, TerrainLod.MEDIUM, floor);
            case MEDIUM -> new ChunkField(board, TerrainLod.COARSE, floor);
            case COARSE -> new ChunkField(board, TerrainLod.DISTANT, floor);
            case DISTANT -> null;
        };
    }

    /**
     * A hex edge's samples at a detail. DISTANT is HEXFAN: a hex's centre and its six corners, with one wall row a
     * step. Water hexes, hexes beside ones the field does not take and steps a plate or a road's ramp takes off the
     * edge keep COARSE's samples (Builder#wide, #keeps).
     */
    static int segments(TerrainLod lod) {
        return switch (lod) { case FULL -> 8; case MEDIUM -> 4; case COARSE -> 2; case DISTANT -> 1; };
    }

    /** The level-set grid over the chunk holding h, with a hex's margin. */
    FieldBoard.Shares shares(Coords h) {
        int size = TerrainLod.CHUNK_SIZE;
        Coords chunk = new Coords(h.getX() / size * size, h.getY() / size * size);
        return shares.computeIfAbsent(chunk, at -> {
            Coords far = new Coords(at.getX() + size - 1, at.getY() + size - 1);
            float w = board.width, ht = board.height;
            return board.new Shares(board.centerX(at) - w, board.centerY(far) - 1.5f * ht, board.centerX(far) + w,
                  board.centerY(at) + 1.5f * ht);
        });
    }

    /** The hex's block, evaluated once on the first ask; null where the field does not take the hex. */
    Block block(Coords h) { return board.taken(h) ? blocks.computeIfAbsent(h, this::build) : null; }

    private Block build(Coords h) {
        Block built = new Builder(this, h).run();
        Block stamped = FieldStamps.apply(board, built, coarser == null ? null : () -> coarser.block(h));
        // A clip moves and adds samples: their covers are asked again.
        return stamped == built || scene == null ? stamped : stamped.covered(FieldCover.of(scene, stamped, segments(lod)));
    }

    /**
     * One hex's samples (structure of arrays), lattice samples first, then its walls' samples, and its triangles by
     * group as index triples into them. Lattice samples carry the field's values; wall samples their own.
     * {@code faceRim} and {@code faceFoot}: a step or wall sample's height above its foot and depth below its rim, in
     * metres; {@code rim}, {@code foot}: a top sample's distances to the nearest drop and rise, in metres.
     * {@code role}: 0 a top sample, 1 a step's face, 2 a wall's sample, whose {@code foot} is its occlusion instead.
     * {@code parents}: per sample six floats, its coarse parent (field-morph.glsl), the position the next coarser detail
     * has at its place minus its own, and that detail's normal there; NaN where the sample is its own parent.
     * {@code sides}: the neighbouring chunk a sample borders (ChunkMesh), 0 none.
     * {@code cover}: the samples' cover weights; null without the compact ground and where the hex has no cover border.
     */
    record Block(Coords coords, int family, int level, int lattice, float[] x, float[] y, float[] z, float[] nx,
          float[] ny, float[] nz, float[] role, float[] rock, float[] rim, float[] foot, float[] steps, float[] faceRim,
          float[] faceFoot, float[] projection, int[] levelTops, int[] ground, int[] faces, int[] walls,
          float[] bounds, float[] parents, int[] sides, int[] stepTop, FieldCover.Hex cover) {
        /** A block without step tops (FieldClip's clipped blocks, until it carries them through its moves). */
        Block(Coords coords, int family, int level, int lattice, float[] x, float[] y, float[] z, float[] nx, float[] ny,
              float[] nz, float[] role, float[] rock, float[] rim, float[] foot, float[] steps, float[] faceRim, float[] faceFoot,
              float[] projection, int[] levelTops, int[] ground, int[] faces, int[] walls, float[] bounds, float[] parents,
              int[] sides, FieldCover.Hex cover) {
            this(coords, family, level, lattice, x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim, faceFoot,
                  projection, levelTops, ground, faces, walls, bounds, parents, sides, null, cover);
        }

        int size() { return x.length; }

        Block covered(FieldCover.Hex hex) {
            return new Block(coords, family, level, lattice, x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim,
                  faceFoot, projection, levelTops, ground, faces, walls, bounds, parents, sides, stepTop, hex);
        }

        /** Edge e's row of lattice samples, x and y, from corner e to corner e + 1 (where its wall and rocks stand). */
        float[][] row(int e) {
            int n = (int) Math.round((Math.sqrt(12.0 * (lattice - 1) + 9) - 3) / 6);
            float[][] row = new float[n + 1][];
            for (int j = 0; j <= n; j++) {
                int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                row[j] = new float[] { x[v], y[v] };
            }
            return row;
        }

        /** A top sample's distance to the nearest drop, in metres, as today's ground carries it. */
        float rimMetres(int v) { return Math.clamp(rim[v] / BoardRelief.metres(1), 0, 99); }

        /** A top sample's distance to the nearest rise, in metres. */
        float footMetres(int v) { return Math.clamp(foot[v] / BoardRelief.metres(1), 0, 99); }

        /** A top sample's occlusion: darker toward the foot of a rise (today's groundOcclusion, approximately). */
        float occlusion(int v) { return 1 - .3f * (float) Math.exp(-footMetres(v) / 1.6f); }

        /** A top sample's tint: the height of the nearest step, in levels up to seven. */
        float coverTint(int v) { return .3f + .1f * Math.min(steps[v], 7); }

        /**
         * A face sample's occlusion: a bank darkens toward its middle, as today's banks do; a wall's rock carries
         * today's (Builder#occlude); a step's rock keeps its light.
         */
        float faceOcclusion(int v) {
            if (rock[v] >= .5f) { return role[v] > 1.5f ? foot[v] : 1; }
            float span = faceRim[v] + faceFoot[v];
            return span <= 0 ? 1 : 1 - .15f * (float) Math.sin(Math.PI * faceFoot[v] / span);
        }

        /** A face sample's rock bed hardness (BoardRelief#bed). */
        float bed(int v) { return BoardRelief.bed(x[v], y[v], z[v], family); }

        /** The edge (by edge index) nearest the triangle at index {@code i} of {@code list}: the edge it stands on. */
        int edge(int[] list, int i) {
            float px = (x[list[i]] + x[list[i + 1]] + x[list[i + 2]]) / 3, py = (y[list[i]] + y[list[i + 1]] + y[list[i + 2]]) / 3;
            float cx = megamek.common.board.HexTile.centerX(coords, BoardGeometry.width());
            float cy = megamek.common.board.HexTile.centerY(coords, BoardGeometry.height());
            float angle = (float) Math.atan2(py - cy, px - cx);
            int best = 0;
            float bestDot = Float.NEGATIVE_INFINITY;
            for (int e = 0; e < 6; e++) {
                float mx = (megamek.common.board.HexTile.cornerX(coords, e, BoardGeometry.width())
                      + megamek.common.board.HexTile.cornerX(coords, (e + 1) % 6, BoardGeometry.width())) / 2 - cx;
                float my = (megamek.common.board.HexTile.cornerY(coords, e, BoardGeometry.height())
                      + megamek.common.board.HexTile.cornerY(coords, (e + 1) % 6, BoardGeometry.height())) / 2 - cy;
                float dot = (float) Math.cos(angle - Math.atan2(my, mx));
                if (dot > bestDot) { bestDot = dot; best = e; }
            }
            return best;
        }
    }

    static int vertex(int k, int i, int j) {
        if (i == 0) { return 0; }
        if (j == i) { k = (k + 1) % 6; j = 0; }
        return 1 + 3 * i * (i - 1) + k * i + j;
    }

    /** One hex's evaluation into growable arrays. */
    private static final class Builder {
        final ChunkField chunk;
        final FieldBoard b;
        final Coords h;
        final int n, own, lattice;
        final List<FieldBoard.Segment> segments;
        final FieldBoard.Shares shares;
        /** The hex's covers; null without the compact ground and where it has no cover border. */
        final FieldCover cover;
        int size;
        float[] x, y, z, nx, ny, nz, role, rock, rim, foot, steps, faceRim, faceFoot, projection;
        /** The wall samples' parents as they are made ({@link #wallParents}): sample, dx, dy, dz, nx, ny, nz. */
        float[] wallParent = new float[64];
        int wallParentCount;
        /** Each edge's wall samples, from {@code wallStart[e]} to before {@code wallEnd[e]}. */
        final int[] wallStart = new int[6], wallEnd = new int[6];
        final boolean[] flat;
        /** Each lattice sample's nearest step's lowest level (FieldBoard#height). */
        final float[] stepLow;
        /** Each sample's nearest step's upper hex (FieldBoard#height out[7], FieldBoard#packed; -1 none): Block#stepTop. */
        int[] stepTop;
        /** A ground portal's road hex: per lattice sample, the hex whose height it takes (#sharer); else null. */
        Coords[] sharer;
        int[] levelTops = new int[48], ground = new int[48], faces = new int[48], walls = new int[48];
        int levelTopCount, groundCount, faceCount, wallCount;
        /** Whether the board cuts one of this hex's sides (a neighbour outside it): its level ground is not merged. */
        boolean cut;

        Builder(ChunkField chunk, Coords h) {
            this.chunk = chunk;
            b = chunk.board;
            this.h = h;
            n = chunk.lod == TerrainLod.DISTANT && wide(b, h) ? 2 : segments(chunk.lod);
            own = b.level(h);
            segments = b.segments(h);
            shares = chunk.shares(h);
            cover = chunk.scene == null ? null : FieldCover.of(chunk.scene, h);
            lattice = 1 + 3 * n * (n + 1);
            int capacity = lattice * 2;
            x = new float[capacity]; y = new float[capacity]; z = new float[capacity]; nx = new float[capacity];
            ny = new float[capacity]; nz = new float[capacity]; role = new float[capacity]; rock = new float[capacity];
            rim = new float[capacity]; foot = new float[capacity]; steps = new float[capacity]; faceRim = new float[capacity];
            faceFoot = new float[capacity]; projection = new float[capacity];
            flat = new boolean[lattice];
            stepLow = new float[lattice];
            stepTop = new int[capacity];
            java.util.Arrays.fill(stepTop, -1);
            size = lattice;
        }

        /** FieldBoard#height as hex {@code at} evaluates it, with its steps and level-set grid. */
        private void height(Coords at, float px, float py, float[] out) {
            boolean self = at.equals(h);
            b.height(at, self ? segments : b.segments(at), self ? shares : chunk.shares(at), px, py, out);
        }

        /**
         * The hex whose height a sample of edge k's row (j along it, 0 at corner k) takes, one for every hex that shares
         * it (the neighbours the field takes at this hex's level: #walls closes every other edge): one of them with no
         * ground portal of its own, where every such hex gives the sample one height; else the lowest by
         * FieldBoard#packed of this hex and those portals' road hexes.
         */
        private Coords sharer(int k, int j) {
            Coords owner = h;
            for (int e : j == 0 ? new int[] { k, (k + 5) % 6 } : new int[] { k }) {
                Coords g = h.translated(BoardGeometry.edgeDirection(e));
                if (!b.taken(g) || b.level(g) != own) { continue; }
                if (!b.portal(g)) { return g; }
                if (FieldBoard.packed(g) < FieldBoard.packed(owner)) { owner = g; }
            }
            return owner;
        }

        Block run() {
            float cx = b.centerX(h), cy = b.centerY(h);
            float[] cornerX = new float[6], cornerY = new float[6];
            float[][] boundary = new float[6 * FieldBoard.FULL][];
            for (int k = 0; k < 6; k++) {
                cornerX[k] = b.cornerX(h, k);
                cornerY[k] = b.cornerY(h, k);
                float[][] e = b.edgeMove(h, k);
                for (int j = 0; j < FieldBoard.FULL; j++) { boundary[k * FieldBoard.FULL + j] = e[j]; }
            }
            float ownZ = own * b.levelZ, flatReach = BoardRelief.metres(8);
            float[] out = new float[8];
            // A ground portal's road hex shares its edge samples with the neighbours at its level: they take the height
            // those give them (#sharer), which no approach of the road hex's own tunnels lowers (FieldStamps#approach).
            sharer = b.portal(h) ? new Coords[lattice] : null;
            for (int k = 0; k < 6; k++) {
                for (int i = 0; i <= n; i++) {
                    for (int j = 0; j < Math.max(1, i) && (i > 0 || k == 0); j++) {
                        int v = vertex(k, i, j);
                        float px = cx + (cornerX[k] - cx) * i / n + (cornerX[(k + 1) % 6] - cornerX[k]) * j / n;
                        float py = cy + (cornerY[k] - cy) * i / n + (cornerY[(k + 1) % 6] - cornerY[k]) * j / n;
                        // The boundary's move at this point's spoke, falling off linearly to the centre.
                        float dx = 0, dy = 0;
                        if (i > 0) {
                            float u = j * (float) FieldBoard.FULL / i, f = u - (int) u, s = i / (float) n;
                            float[] p0 = boundary[k * FieldBoard.FULL + (int) u];
                            float[] p1 = boundary[(k * FieldBoard.FULL + (int) u + 1) % (6 * FieldBoard.FULL)];
                            dx = (p0[0] + (p1[0] - p0[0]) * f) * s;
                            dy = (p0[1] + (p1[1] - p0[1]) * f) * s;
                        }
                        x[v] = px + dx;
                        y[v] = py + dy;
                        if (sharer != null) { sharer[v] = i == n ? sharer(k, j) : h; }
                        height(sharer == null ? h : sharer[v], x[v], y[v], out);
                        z[v] = out[0]; role[v] = out[1]; rock[v] = out[2]; rim[v] = out[3]; foot[v] = out[4]; steps[v] = out[5];
                        stepLow[v] = out[6];
                        stepTop[v] = (int) out[7];
                        // Flat and unmoved: merged triangles' straight edges then pass through every merged sample.
                        flat[v] = Math.abs(out[0] - ownZ) < 1e-3f && Math.min(out[3], out[4]) > flatReach
                              && Math.abs(dx) + Math.abs(dy) < 1e-4f;
                    }
                }
            }
            normals();
            // After the normals: ring 1 keeps COARSE's (its parents there), a straight middle its corners' mean.
            if (chunk.lod == TerrainLod.DISTANT && n == 2) {
                for (int k = 0; k < 6; k++) {
                    if (keeps(k)) { continue; }
                    int v = vertex(k, 2, 1), a = vertex(k, 2, 0), c = vertex(k, 2, 2);
                    x[v] = (x[a] + x[c]) / 2; y[v] = (y[a] + y[c]) / 2; z[v] = (z[a] + z[c]) / 2;
                    flat[v] = false;
                    float mx = nx[a] + nx[c], my = ny[a] + ny[c], mz = nz[a] + nz[c];
                    float len = (float) Math.sqrt(mx * mx + my * my + mz * mz);
                    nx[v] = mx / len; ny[v] = my / len; nz[v] = mz / len;
                }
            }
            for (int v = 0; v < lattice; v++) { shade(v); }
            if (cover != null) { cover.lattice(x, y, z, role, rock, foot, stepTop, lattice); }
            for (int k = 0; k < 6; k++) { cut |= !b.inside(h.translated(BoardGeometry.edgeDirection(k))); }
            for (int k = 0; k < 6; k++) { emit(k, 0, 0, n, 0, n, n); }
            for (int e = 0; e < 6; e++) {
                wallStart[e] = size;
                walls(e);
                wallEnd[e] = size;
                // A wall's samples: the step's upper hex is the higher of the two.
                Coords g = h.translated(BoardGeometry.edgeDirection(e));
                java.util.Arrays.fill(stepTop, wallStart[e], wallEnd[e],
                      FieldBoard.packed(b.inside(g) && b.level(g) > own ? g : h));
            }
            float[] bounds = { Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                  Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY };
            for (int v = 0; v < size; v++) {
                bounds[0] = Math.min(bounds[0], x[v]); bounds[1] = Math.min(bounds[1], y[v]); bounds[2] = Math.min(bounds[2], z[v]);
                bounds[3] = Math.max(bounds[3], x[v]); bounds[4] = Math.max(bounds[4], y[v]); bounds[5] = Math.max(bounds[5], z[v]);
            }
            float[] parents = parents();
            groundCount = solid(ground, groundCount, parents);
            faceCount = solid(faces, faceCount, parents);
            wallCount = solid(walls, wallCount, parents);
            FieldCover.Hex covers = cover == null ? null : cover.finish(x, y, z, role, faceRim, lattice, size, n, parents);
            return new Block(h, b.family(h), own, lattice, trim(x), trim(y), trim(z), trim(nx), trim(ny), trim(nz),
                  trim(role), trim(rock), trim(rim), trim(foot), trim(steps), trim(faceRim), trim(faceFoot),
                  trim(projection), java.util.Arrays.copyOf(levelTops, levelTopCount),
                  java.util.Arrays.copyOf(ground, groundCount), java.util.Arrays.copyOf(faces, faceCount),
                  java.util.Arrays.copyOf(walls, wallCount), bounds, parents, sides(), java.util.Arrays.copyOf(stepTop, size),
                  covers);
        }

        /**
         * A list's triangles that have an area, moved to its front; their count of indices. A triangle is flat where
         * two of its samples stand at one place (a wall's lip whose top row stands on its rim): it draws nothing, and
         * a ray test counts it as hit at the ray's start (a pick then names the wrong hex). Only those flat all the
         * way to their samples' parents go: none may open in the glide to the coarser detail.
         */
        private int solid(int[] list, int count, float[] parents) {
            int kept = 0;
            for (int i = 0; i < count; i += 3) {
                int p = list[i], q = list[i + 1], r = list[i + 2];
                // A triangle's area is quadratic along the glide: flat at its start, middle and end is flat throughout.
                if (flat(p, q, r, parents, 0) && flat(p, q, r, parents, .5f) && flat(p, q, r, parents, 1)) { continue; }
                list[kept++] = p;
                list[kept++] = q;
                list[kept++] = r;
            }
            return kept;
        }

        /** Whether a triangle is under a square millimetre with its samples the share {@code t} of the way to their parents. */
        private boolean flat(int p, int q, int r, float[] parents, float t) {
            float ux = glide(x, q, parents, 0, t) - glide(x, p, parents, 0, t);
            float uy = glide(y, q, parents, 1, t) - glide(y, p, parents, 1, t);
            float uz = glide(z, q, parents, 2, t) - glide(z, p, parents, 2, t);
            float vx = glide(x, r, parents, 0, t) - glide(x, p, parents, 0, t);
            float vy = glide(y, r, parents, 1, t) - glide(y, p, parents, 1, t);
            float vz = glide(z, r, parents, 2, t) - glide(z, p, parents, 2, t);
            float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
            float tiny = 1e-6f * b.metre * b.metre;
            return cx * cx + cy * cy + cz * cz <= tiny * tiny;
        }

        /** A sample's coordinate the share {@code t} of the way to its parent (a sample that is its own stays). */
        private static float glide(float[] own, int v, float[] parents, int axis, float t) {
            float offset = parents[v * 6 + axis];
            return Float.isNaN(offset) ? own[v] : own[v] + t * offset;
        }

        /**
         * Every sample's parent: an odd lattice sample halves a coarse edge and takes its midpoint (along its ring,
         * outward, or across) and the mean of its ends' coarse normals; an even inner one stays and takes the coarse
         * normal, its triangles' as normals() makes them at the coarser detail (the outline's and flat samples' are the
         * same at every detail); a wall sample takes the coarser wall's point at its place (#wallParents).
         */
        private float[] parents() {
            float[] result = new float[size * 6];
            java.util.Arrays.fill(result, Float.NaN);
            if (chunk.lod == TerrainLod.DISTANT) { return result; }
            float[] cx = new float[lattice], cy = new float[lattice], cz = new float[lattice];
            for (int k = 0; n >= 2 && k < 6; k++) {
                for (int i = 1; i <= n / 2; i++) {
                    for (int j = 0; j < i; j++) {
                        accumulate(vertex(k, 2 * i - 2, 2 * j), vertex(k, 2 * i, 2 * j), vertex(k, 2 * i, 2 * j + 2), cx, cy, cz);
                        if (j < i - 1) {
                            accumulate(vertex(k, 2 * i - 2, 2 * j), vertex(k, 2 * i, 2 * j + 2), vertex(k, 2 * i - 2, 2 * j + 2),
                                  cx, cy, cz);
                        }
                    }
                }
            }
            for (int v = 0; v < lattice; v++) {
                float len = (float) Math.sqrt(cx[v] * cx[v] + cy[v] * cy[v] + cz[v] * cz[v]);
                boolean coarse = n >= 2 && len > 0 && !flat[v] && v < lattice - 6 * n;
                cx[v] = coarse ? cx[v] / len : nx[v];
                cy[v] = coarse ? cy[v] / len : ny[v];
                cz[v] = coarse ? cz[v] / len : nz[v];
                if (coarse) {
                    turnUp(v, cx, cy, cz);
                    int o = v * 6;
                    result[o] = result[o + 1] = result[o + 2] = 0;
                    result[o + 3] = cx[v];
                    result[o + 4] = cy[v];
                    result[o + 5] = cz[v];
                }
            }
            for (int k = 0; n >= 2 && k < 6; k++) {
                for (int i = 1; i <= n; i++) {
                    for (int j = 0; j < i; j++) {
                        if (i % 2 == 0 && j % 2 == 0) { continue; }
                        int a, c;
                        if (i % 2 == 1 && j % 2 == 1) { a = vertex(k, i - 1, j - 1); c = vertex(k, i + 1, j + 1); }
                        else if (i % 2 == 1) { a = vertex(k, i - 1, j); c = vertex(k, i + 1, j); }
                        else { a = vertex(k, i, j - 1); c = vertex(k, i, j + 1); }
                        int v = vertex(k, i, j), o = v * 6;
                        if (chunk.lod == TerrainLod.COARSE && wide(b, h) && (i == 1 || i == n && keeps(k))) {
                            // HEXFAN keeps this sample (#keeps).
                            result[o] = result[o + 1] = result[o + 2] = 0;
                            result[o + 3] = nx[v]; result[o + 4] = ny[v]; result[o + 5] = nz[v];
                            continue;
                        }
                        float px = cx[a] + cx[c], py = cy[a] + cy[c], pz = cz[a] + cz[c];
                        float len = (float) Math.sqrt(px * px + py * py + pz * pz);
                        result[o] = (x[a] + x[c]) / 2 - x[v];
                        result[o + 1] = (y[a] + y[c]) / 2 - y[v];
                        result[o + 2] = (z[a] + z[c]) / 2 - z[v];
                        result[o + 3] = px / len;
                        result[o + 4] = py / len;
                        result[o + 5] = pz / len;
                    }
                }
            }
            if (chunk.lod == TerrainLod.COARSE && wide(b, h)) {
                // HEXFAN keeps the centre too, with this detail's normal.
                result[3] = nx[0]; result[4] = ny[0]; result[5] = nz[0];
            }
            for (int i = 0; i < wallParentCount; i += 7) {
                System.arraycopy(wallParent, i + 1, result, (int) wallParent[i] * 6, 6);
            }
            return result;
        }

        private boolean keeps(int k) { return keeps(b, h, k); }

        /**
         * Whether hex h keeps its edge k's middle at HEXFAN: toward a hex inside the board the field does not take
         * (today's hex there is not straight); across a step a plate's fitted outline (FieldPlates) or a road's
         * ramp across a cliff takes off the edge; or from water to field water at its level with no cliff.
         */
        static boolean keeps(FieldBoard b, Coords h, int k) {
            Coords g = h.translated(BoardGeometry.edgeDirection(k));
            if (!b.taken(g)) { return b.inside(g); }
            if (b.level(g) != b.level(h) && (b.plate(h) || b.plate(g) || b.cliff(h, g) && b.ramp(h, g))) {
                return true;
            }
            return WaterLayer.water(b, h) && WaterLayer.water(b, g) && b.level(g) == b.level(h) && !b.cliff(h, g);
        }

        /** Whether hex h keeps COARSE's samples at HEXFAN: water, or an edge's middle kept (#keeps). */
        static boolean wide(FieldBoard b, Coords h) {
            for (int k = 0; k < 6; k++) {
                if (keeps(b, h, k)) { return true; }
            }
            return WaterLayer.water(b, h);
        }

        /**
         * Which neighbouring chunk a sample on the hex's outline borders, its edge's walls' samples included (1-8, row
         * by row from the chunk to the lower left, the chunk itself skipped; 0 none): its morph is capped by that
         * chunk's, so no crack opens between chunks at different detail.
         */
        private int[] sides() {
            int[] result = new int[size];
            int chunkSize = TerrainLod.CHUNK_SIZE;
            for (int e = 0; e < 6; e++) {
                Coords g = h.translated(BoardGeometry.edgeDirection(e));
                int dx = Math.floorDiv(g.getX(), chunkSize) - Math.floorDiv(h.getX(), chunkSize);
                int dy = Math.floorDiv(g.getY(), chunkSize) - Math.floorDiv(h.getY(), chunkSize);
                if (dx == 0 && dy == 0) { continue; }
                int index = (dy + 1) * 3 + dx + 1;
                int side = index < 4 ? index + 1 : index;
                for (int j = 0; j <= n; j++) {
                    int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                    if (result[v] == 0) { result[v] = side; }
                }
                java.util.Arrays.fill(result, wallStart[e], wallEnd[e], side);
            }
            return result;
        }

        /**
         * A wall's samples' parents: the next coarser detail builds the same wall on every {@code step}th lattice sample,
         * with a {@code ratio}th of the columns and rows every {@code coarseStride}; each sample's parent is that wall's
         * surface at its place (the triangles strip() cuts there), so at weight 0 this wall lies on the coarser one, rims
         * and foot rows on the coarser rows the ground morphs to.
         */
        private void wallParents(Wall w, int[][] columns, int[] rims, int ratio, int step, int stride, int coarseStride,
              boolean flip, boolean plane) {
            int fine = columns.length - 1, coarse = fine / ratio;
            int rows = (w.rows - 1) / coarseStride + 1, every = coarseStride / stride;
            // Per coarser column: its rows' points and normals (x, y, z, nx, ny, nz), then its rim.
            float[][] grid = new float[coarse + 1][];
            boolean[] tall = new boolean[coarse + 1];
            float[] out = new float[4];
            for (int c = 0; c <= coarse; c++) {
                w.column(c, coarse, step);
                // A cast wall closes its edge both ways (walls()).
                tall[c] = (w.outside ? Math.abs(w.top - w.bottom) : w.top - w.bottom) > .01f * b.metre;
                float[] column = grid[c] = new float[(rows + 1) * 6];
                for (int k = 0; k < rows; k++) {
                    if (tall[c]) { w.point(k * coarseStride, out); }
                    else { out[0] = w.px; out[1] = w.py; out[2] = w.top; }
                    set(column, k, out[0], out[1], out[2], w.onx, w.ony, 0);
                }
                // The lip is level rock; a column with no height is one sample, lit as the wall (walls()).
                set(column, rows, w.px, w.py, w.top, tall[c] ? 0 : w.onx, tall[c] ? 0 : w.ony, tall[c] ? 1 : 0);
            }
            // Its normals as wallNormals lights the coarser wall.
            for (int c = 0; c <= coarse; c++) {
                // A cast wall's plane keeps its outward normal (walls()).
                if (!tall[c] || plane) { continue; }
                float[] before = grid[Math.max(0, c - 1)], after = grid[Math.min(coarse, c + 1)], column = grid[c];
                for (int k = 0; k < rows; k++) {
                    int down = Math.max(0, k - 1) * 6, up = Math.min(rows - 1, k + 1) * 6, at = k * 6;
                    float tx = after[at] - before[at], ty = after[at + 1] - before[at + 1], tz = after[at + 2] - before[at + 2];
                    float ux = column[up] - column[down], uy = column[up + 1] - column[down + 1];
                    float uz = column[up + 2] - column[down + 2];
                    float cx = ty * uz - tz * uy, cy = tz * ux - tx * uz, cz = tx * uy - ty * ux;
                    if (flip) { cx = -cx; cy = -cy; cz = -cz; }
                    float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (len < 1e-6f) { continue; }
                    column[at + 3] = cx / len;
                    column[at + 4] = cy / len;
                    column[at + 5] = cz / len;
                }
            }
            // Rows under the foot or over the rim are its one sample there (walls()): its normal, which wallNormals
            // leaves from the run's last row at the foot and its first at the rim.
            for (int c = 0; c <= coarse; c++) {
                float[] column = grid[c];
                int foot = 0, rim = rows - 1;
                while (foot < rows - 1 && coincide(column, foot, foot + 1)) { foot++; }
                while (rim > foot && coincide(column, rim, rim - 1)) { rim--; }
                for (int k = 0; k < rows; k++) {
                    int from = k < foot ? foot : k > rim ? rim : k;
                    if (from != k) { System.arraycopy(column, from * 6 + 3, column, k * 6 + 3, 3); }
                }
            }
            float[] p = new float[6];
            for (int c = 0; c <= fine; c++) {
                int c0 = Math.min(c / ratio, coarse), c1 = Math.min(c0 + 1, coarse);
                float fc = c0 == c1 ? 0 : (c - c0 * ratio) / (float) ratio;
                int[] column = columns[c];
                if (column.length == 1) {
                    // A column with no height stands at the coarser wall's top.
                    surface(grid[c0], grid[c1], rows - 1, rows - 1, fc, 0, p);
                    parent(column[0], p, false);
                    continue;
                }
                for (int i = 0; i < column.length; i++) {
                    // Rows under the foot or over the rim stand on its sample (walls()): one parent, at the foot's
                    // row or the rim's.
                    if (i < column.length - 1 && (column[i] == column[column.length - 1] || i > 0 && column[i] == column[i - 1])) {
                        continue;
                    }
                    int k0 = Math.min(i / every, rows - 1), k1 = Math.min(k0 + 1, rows - 1);
                    float z0 = z[column[k0 * every]], z1 = z[column[k1 * every]];
                    float fr = z1 - z0 < 1e-6f ? 0 : Math.clamp((z[column[i]] - z0) / (z1 - z0), 0, 1);
                    surface(grid[c0], grid[c1], k0, k1, fc, fr, p);
                    parent(column[i], p, true);
                }
                surface(grid[c0], grid[c1], rows, rows, fc, 0, p);
                lipNormal(grid[c0], coincide(grid[c0], rows - 1, rows) ? rows - 1 : rows, grid[c1], rows, fc, p);
                parent(rims[c], p, true);
            }
        }

        /** Whether rows k and q of a coarser column stand at one point (a lip with no width there). */
        private boolean coincide(float[] column, int k, int q) {
            float dx = column[k * 6] - column[q * 6], dy = column[k * 6 + 1] - column[q * 6 + 1], dz = column[k * 6 + 2] - column[q * 6 + 2];
            return dx * dx + dy * dy + dz * dz < 1e-6f * b.metre * b.metre;
        }

        /**
         * A lip's normal on the coarser wall at fc from column a to column c, each lit by its row na, nc: where the lip
         * has no width at a pair's first column, strip() covers the lip beside it with the triangle holding the wall's
         * point there, so the coarser wall shades the lip toward that point's normal.
         */
        private static void lipNormal(float[] a, int na, float[] c, int nc, float fc, float[] p) {
            float x = a[na * 6 + 3] + (c[nc * 6 + 3] - a[na * 6 + 3]) * fc, y = a[na * 6 + 4] + (c[nc * 6 + 4] - a[na * 6 + 4]) * fc;
            float z = a[na * 6 + 5] + (c[nc * 6 + 5] - a[na * 6 + 5]) * fc;
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            if (len < 1e-6f) { return; }
            p[3] = x / len;
            p[4] = y / len;
            p[5] = z / len;
        }

        /** Row {@code k}'s point and normal of a coarser column. */
        private static void set(float[] column, int k, float px, float py, float pz, float pnx, float pny, float pnz) {
            int o = k * 6;
            column[o] = px; column[o + 1] = py; column[o + 2] = pz;
            column[o + 3] = pnx; column[o + 4] = pny; column[o + 5] = pnz;
        }

        /**
         * The coarser wall at {@code fc} across from column a to column c and {@code fr} up from row k0 to k1: on
         * strip()'s triangles (a k0, c k0, a k1) and (a k1, c k0, c k1); the normal blended the same way.
         */
        private static void surface(float[] a, float[] c, int k0, int k1, float fc, float fr, float[] result) {
            int lo = k0 * 6, hi = k1 * 6;
            for (int q = 0; q < 6; q++) {
                float pa = a[lo + q], pb = c[lo + q], pd = a[hi + q], pg = c[hi + q];
                result[q] = fc + fr <= 1 ? pa + fc * (pb - pa) + fr * (pd - pa)
                      : pg + (1 - fc) * (pd - pg) + (1 - fr) * (pb - pg);
            }
            float len = (float) Math.sqrt(result[3] * result[3] + result[4] * result[4] + result[5] * result[5]);
            for (int q = 3; q < 6; q++) { result[q] = len < 1e-6f ? 0 : result[q] / len; }
            if (len < 1e-6f) { result[5] = 1; }
        }

        /** Sample v's parent: the point {@code p} (and its normal, or v's own where {@code normal} is false). */
        private void parent(int v, float[] p, boolean normal) {
            if (wallParentCount + 7 > wallParent.length) {
                wallParent = java.util.Arrays.copyOf(wallParent, wallParent.length * 2);
            }
            wallParent[wallParentCount++] = v;
            wallParent[wallParentCount++] = p[0] - x[v];
            wallParent[wallParentCount++] = p[1] - y[v];
            wallParent[wallParentCount++] = p[2] - z[v];
            wallParent[wallParentCount++] = normal ? p[3] : nx[v];
            wallParent[wallParentCount++] = normal ? p[4] : ny[v];
            wallParent[wallParentCount++] = normal ? p[5] : nz[v];
        }

        /**
         * One edge's wall: the lattice row it stands on, the heights on both sides, and how today's relief pushes it.
         * {@link #column} places a column along the edge, {@link #point} a row's point on it; walls() builds its own
         * detail with them and wallParents() the next coarser one, so both agree where they should.
         */
        private final class Wall {
            final boolean outside, wall;
            /** The step's height for its landforms, and the lower hex's level (the board's floor at its cut). */
            final float landform, base, onx, ony;
            final int family;
            /** The finest detail's rows: the first at the foot, the last at the rim, the others at their heights. */
            final int rows;
            final int[] samples;
            final float[] tops, feet;
            /** Today's relief at each lattice sample's rim, which the rim row carries there (FieldBoard#rimMove). */
            final float[] rimRelief;
            /** The columns of the edge's two corners (#cornerColumn); null where the relief fades out instead. */
            final float[][] corners = new float[2][];
            /** The current column's place on the row, its top and foot, its rim's relief, its corners' shares. */
            float px, py, top, bottom, rimPush, first, second;
            /** The current column's relief family (a pool's step: BoardRelief#poolStepFamily). */
            int columnFamily;
            /** The lower hex is shaped water (FieldShore): the wall's foot is the bed and the relief counts from it,
             * which leaves the scree it lays on dry ground under the water. */
            boolean wet;
            /** This hex pours over a fall here: the rows stand smooth behind the crest. */
            boolean fall;

            Wall(int e, boolean outside, boolean wall, float landform, float base, float onx, float ony, int[] samples,
                  float[] tops, float[] feet, int rows) {
                this.outside = outside; this.wall = wall; this.landform = landform; this.base = base;
                this.onx = onx; this.ony = ony; this.samples = samples; this.tops = tops; this.feet = feet;
                this.rows = rows;
                this.family = b.family(h);
                rimRelief = new float[samples.length];
                if (!wall) { return; }
                for (int j = 0; j < samples.length; j++) {
                    rimRelief[j] = BoardRelief.wallRelief(x[samples[j]], y[samples[j]], tops[j], base, tops[j],
                          Math.max(landform, tops[j] - base), family);
                }
                Coords g = h.translated(BoardGeometry.edgeDirection(e));
                if (own > b.level(g)) {
                    corners[0] = cornerColumn(e, g, h.translated(BoardGeometry.edgeDirection((e + 5) % 6)), tops[0]);
                    corners[1] = cornerColumn(e + 1, g, h.translated(BoardGeometry.edgeDirection((e + 1) % 6)),
                          tops[tops.length - 1]);
                }
            }

            /**
             * The column of corner k, where this wall above {@code g} meets the wall beside it round the third hex
             * {@code o}: today's relief at the corner relative to its rim, level by level above the lower one (x, y),
             * pushed along the corner's own direction, out of the one hex above or into the one below. Every value
             * comes from that one hex, so both walls through the corner, from either upper hex, compute one column.
             * Null where a third level meets them or a hex is not the field's.
             */
            private float[] cornerColumn(int k, Coords g, Coords o, float rimZ) {
                if (!b.taken(g) || !b.taken(o)) { return null; }
                boolean above = b.level(o) == b.level(g);
                if (above ? !b.cliff(h, o) : b.level(o) != own || !b.cliff(o, g)) { return null; }
                Coords one = above ? h : g;
                float hx = b.cornerX(h, k), hy = b.cornerY(h, k), cx = 0, cy = 0, nearest = Float.MAX_VALUE;
                for (int q = 0; q < 6; q++) {
                    float dx = b.cornerX(one, q) - hx, dy = b.cornerY(one, q) - hy;
                    if (dx * dx + dy * dy < nearest) { nearest = dx * dx + dy * dy; cx = b.cornerX(one, q); cy = b.cornerY(one, q); }
                }
                float dx = cx - b.centerX(one), dy = cy - b.centerY(one), len = (above ? 1 : -1) * (float) Math.hypot(dx, dy);
                boolean ours = above || h.getX() < o.getX() || h.getX() == o.getX() && h.getY() < o.getY();
                int rock = b.family(ours ? h : o);
                float rim = own * b.levelZ;
                float atRim = BoardRelief.wallRelief(cx, cy, rim, base, rim, landform, rock);
                int levels = (rows - 1) / ROWS_A_LEVEL;
                float[] column = new float[(levels + 1) * 2];
                // Where a wall through the corner is a fall and the rim
                // there still lies under the falling hex's water, the curtain still hangs: the corner stands back with
                // the fall (#point), not out by the relief; the relief comes back only where the rim rises out.
                boolean fallCorner = above && (b.falls(h, g) || b.falls(h, o))
                      && rimZ < b.shore.water(h, hx, hy);
                for (int q = 0; q < levels; q++) {
                    float d = fallCorner
                          ? -FALL_RECESS * b.metre * BoardRelief.smooth(
                                (Math.min(rim, rimZ) - FALL_FACE * b.metre - base - q * b.levelZ) / (1.5f * b.metre))
                          : BoardRelief.wallRelief(cx, cy, base + q * b.levelZ, base, rim, landform, rock) - atRim;
                    column[q * 2] = dx / len * d;
                    column[q * 2 + 1] = dy / len * d;
                }
                return column;
            }

            /**
             * Adds a corner's column at row r, height zr, of the current column to out (x, y), by {@code share}:
             * straight between its rows a level apart, the coarsest detail's, so the line two walls share at the
             * corner is one line at every detail and morph weight. Eased to nothing at the foot.
             */
            private void addCorner(float[] corner, int r, float zr, float share, float[] out) {
                if (corner == null || share <= 0) { return; }
                int q0 = r / ROWS_A_LEVEL, q1 = Math.min(q0 + 1, (rows - 1) / ROWS_A_LEVEL);
                float z0 = height(q0 * ROWS_A_LEVEL), z1 = height(q1 * ROWS_A_LEVEL);
                float e0 = share * BoardRelief.smooth((z0 - bottom) / (1.5f * b.metre));
                float e1 = share * BoardRelief.smooth((z1 - bottom) / (1.5f * b.metre));
                float t = z1 - z0 < 1e-6f ? 0 : Math.clamp((zr - z0) / (z1 - z0), 0, 1);
                out[0] += lerp(corner[q0 * 2] * e0, corner[q1 * 2] * e1, t);
                out[1] += lerp(corner[q0 * 2 + 1] * e0, corner[q1 * 2 + 1] * e1, t);
            }

            /**
             * Column c of {@code columns} along the edge, on the lattice samples every {@code step}: its tops and feet
             * run straight between the samples', as both hexes' meshes do there.
             */
            void column(int c, int columns, int step) {
                int steps = n / step, split = columns / steps;
                int j = Math.min(c / split, steps - 1);
                float f = c / (float) split - j;
                int a = j * step, d = (j + 1) * step, v0 = samples[a], v1 = samples[d];
                px = x[v0] + (x[v1] - x[v0]) * f;
                py = y[v0] + (y[v1] - y[v0]) * f;
                top = tops[a] + (tops[d] - tops[a]) * f;
                bottom = feet[a] + (feet[d] - feet[a]) * f;
                // The rim row carries the relief at its lattice samples and runs straight between them: rows below
                // move by the relief relative to that line.
                rimPush = rimRelief[a] + (rimRelief[d] - rimRelief[a]) * f;
                // Toward a corner the wall takes the corner's column, as today's (over .3 of the edge); toward a
                // corner without one its relief fades out.
                float along = c / (float) columns;
                columnFamily = family;
                if (wall && chunk.scene != null && chunk.scene.tile(h).liquid().present()) {
                    BoardScene.Tile tile = chunk.scene.tile(h);
                    int pool = BoardRelief.poolStepFamily(chunk.scene, h, px, py);
                    if (pool != BoardSurfaceBlend.family(tile)) { columnFamily = pool; }
                }
                first = 1 - BoardRelief.smooth(along / (corners[0] == null ? .15f : .3f));
                second = 1 - BoardRelief.smooth((1 - along) / (corners[1] == null ? .15f : .3f));
            }

            /** Row r's height on the current column (r among the finest detail's rows). */
            float height(int r) {
                return r == 0 ? bottom : r == rows - 1 ? top : Math.clamp(base + r * b.levelZ / ROWS_A_LEVEL, bottom, top);
            }

            /** Row r's point on the current column (r among the finest detail's rows): x, y, z into out. */
            void point(int r, float[] out) {
                float zr = height(r);
                // A row over the rim is the rim's.
                if (zr >= top) { r = rows - 1; }
                // The relief eases to nothing at the foot row, which stands on the lower ground's own samples.
                float ease = outside ? 0 : BoardRelief.smooth((zr - bottom) / (1.5f * b.metre));
                float rest = 1 - first - second;
                out[0] = px; out[1] = py; out[2] = zr;
                if (ease > 0 && rest > 0) {
                    // Today's rock relief above the lower level, caprock included.
                    float foot = wet ? Math.min(base, bottom) : base;
                    float d = BoardRelief.wallRelief(px, py, zr, foot, top, Math.max(landform, top - base), columnFamily) - rimPush;
                    // The rim never stands behind its row, where the ground ends.
                    if (r == rows - 1) { d = Math.max(0, d); }
                    // Under a fall's crest the water wore the face smooth and back: behind the crest's row from FALL_FACE
                    // below the rim, eased in over 1.5 m; its top stands on the hex edge today's curtain hangs from.
                    if (fall) {
                        d = -FALL_RECESS * b.metre * BoardRelief.smooth((top - FALL_FACE * b.metre - zr) / (1.5f * b.metre));
                    }
                    out[0] += onx * d * rest * ease;
                    out[1] += ony * d * rest * ease;
                }
                addCorner(corners[0], r, zr, first, out);
                addCorner(corners[1], r, zr, second, out);
                b.portalFace(h, px, py, zr, out);
            }
        }

        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

        /** How far a fall's face stands behind its crest, metres. */
        private static final float FALL_RECESS = .8f;

        /** How far below the rim a fall's face starts to stand back, metres: its top stands on today's hex edge. */
        private static final float FALL_FACE = 2;

        private float[] trim(float[] values) { return java.util.Arrays.copyOf(values, size); }

        /**
         * The lattice samples' normals: area-weighted over the finest triangles round each inside the hex; on the hex's
         * edge, where the neighbour's triangles are not here, the field's own by differences, which both hexes compute
         * alike. Level ground straight up.
         */
        private void normals() {
            for (int k = 0; k < 6; k++) {
                for (int i = 1; i <= n; i++) {
                    for (int j = 0; j < i; j++) {
                        accumulate(vertex(k, i - 1, j), vertex(k, i, j), vertex(k, i, j + 1));
                        if (j < i - 1) { accumulate(vertex(k, i - 1, j), vertex(k, i, j + 1), vertex(k, i - 1, j + 1)); }
                    }
                }
            }
            float d = BoardRelief.metres(.5f);
            float[] o = new float[7];
            for (int v = 0; v < lattice; v++) {
                if (flat[v]) { nx[v] = 0; ny[v] = 0; nz[v] = 1; continue; }
                if (v >= lattice - 6 * n) {
                    // Forward differences half a metre out: two evaluations, the same in both hexes along a bank.
                    Coords at = sharer == null ? h : sharer[v];
                    height(at, x[v] + d, y[v], o);
                    float zx = o[0];
                    height(at, x[v], y[v] + d, o);
                    float zy = o[0];
                    nx[v] = -(zx - z[v]) / d;
                    ny[v] = -(zy - z[v]) / d;
                    nz[v] = 1;
                }
                float len = (float) Math.sqrt(nx[v] * nx[v] + ny[v] * ny[v] + nz[v] * nz[v]);
                nx[v] /= len; ny[v] /= len; nz[v] /= len;
            }
        }

        private void accumulate(int a, int c, int e) { accumulate(a, c, e, nx, ny, nz); }

        /** Adds triangle (a, c, e)'s upward normal, its area's weight, to its corners' in {@code tx, ty, tz}. */
        private void accumulate(int a, int c, int e, float[] tx, float[] ty, float[] tz) {
            float ux = x[c] - x[a], uy = y[c] - y[a], uz = z[c] - z[a], vx = x[e] - x[a], vy = y[e] - y[a], vz = z[e] - z[a];
            float cx = uy * vz - uz * vy, cy = uz * vx - ux * vz, cz = ux * vy - uy * vx;
            if (cz < 0) { cx = -cx; cy = -cy; cz = -cz; }
            for (int p : new int[] { a, c, e }) { tx[p] += cx; ty[p] += cy; tz[p] += cz; }
        }

        /**
         * A lattice sample's normal (the field's own, by central differences half a metre apart; level ground straight
         * up) and, as a step face would draw it, its height above the step's foot and depth below its rim; the shader
         * projects its texture, as on today's banks and talus.
         */
        private void shade(int v) {
            float m = b.metre;
            // Level ground never draws as a step face: no frame to find.
            if (flat[v]) { return; }
            // A sample on a level is the foot of the rise beside it, or the rim of the drop.
            float bottom = (float) Math.floor(z[v] / b.levelZ + (foot[v] < rim[v] ? 1e-3f : -1e-3f)) * b.levelZ;
            faceRim[v] = (z[v] - bottom) / m;
            faceFoot[v] = (bottom + b.levelZ - z[v]) / m;
            projection[v] = Float.NaN;
            turnUp(v, nx, ny, nz);
        }

        /**
         * An earth bank's normals turn to the ground's at its foot and its rim, as today's (BoardRelief#canonicalWall):
         * the cover they pick stays turf there; the middle of a bank of two levels or more stays earth. Road ground keeps
         * its own. {@code tx, ty, tz}: sample v's normal, its own or the coarser detail's at its place (parents()).
         */
        private void turnUp(int v, float[] tx, float[] ty, float[] tz) {
            // The nearest step's own foot and rim; a sample off that step's height (near another step) keeps its normal.
            float low = stepLow[v] * b.levelZ, high = low + steps[v] * b.levelZ;
            float aboveFoot = z[v] - low, belowRim = high - z[v], touch = 1e-3f * b.metre;
            if (flat[v] || steps[v] <= 0 || Math.min(aboveFoot, belowRim) < -touch
                  || role[v] <= 0 && Math.min(aboveFoot, belowRim) >= touch) { return; }
            float contact = BoardRelief.bankContact(high - low);
            float turn = (1 - rock[v]) * Math.max(BoardRelief.bankFeather(aboveFoot, contact),
                  BoardRelief.bankFeather(belowRim, contact));
            if (turn <= 0) { return; }
            tx[v] *= 1 - turn; ty[v] *= 1 - turn; tz[v] += (1 - tz[v]) * turn;
            float len = (float) Math.sqrt(tx[v] * tx[v] + ty[v] * ty[v] + tz[v] * tz[v]);
            tx[v] /= len; ty[v] /= len; tz[v] /= len;
        }

        private int addSample(float px, float py, float pz, float pnx, float pny, float aboveFoot, float belowRim, float frame) {
            if (size == x.length) { grow(); }
            int v = size++;
            x[v] = px; y[v] = py; z[v] = pz; nx[v] = pnx; ny[v] = pny; nz[v] = 0;
            role[v] = 2; rock[v] = 1; foot[v] = 1; faceRim[v] = aboveFoot; faceFoot[v] = belowRim; projection[v] = frame;
            return v;
        }

        private void grow() {
            int capacity = x.length * 2;
            x = java.util.Arrays.copyOf(x, capacity); y = java.util.Arrays.copyOf(y, capacity);
            z = java.util.Arrays.copyOf(z, capacity); nx = java.util.Arrays.copyOf(nx, capacity);
            ny = java.util.Arrays.copyOf(ny, capacity); nz = java.util.Arrays.copyOf(nz, capacity);
            int was = stepTop.length;
            stepTop = java.util.Arrays.copyOf(stepTop, capacity);
            java.util.Arrays.fill(stepTop, was, capacity, -1);
            role = java.util.Arrays.copyOf(role, capacity); rock = java.util.Arrays.copyOf(rock, capacity);
            rim = java.util.Arrays.copyOf(rim, capacity); foot = java.util.Arrays.copyOf(foot, capacity);
            steps = java.util.Arrays.copyOf(steps, capacity); faceRim = java.util.Arrays.copyOf(faceRim, capacity);
            faceFoot = java.util.Arrays.copyOf(faceFoot, capacity);
            projection = java.util.Arrays.copyOf(projection, capacity);
        }

        /**
         * Where this hex stands above its neighbour along edge e's row, the wall between them with today's rock relief;
         * where the board ends, its cut down to the floor.
         */
        private void walls(int e) {
            Coords g = h.translated(BoardGeometry.edgeDirection(e));
            boolean outside = !b.inside(g);
            // Where the two hexes stand at one level their shared samples are equal: nothing to close.
            if (!outside && b.level(g) == own) { return; }
            // A plate above this hex closes their edge with its cast wall, both ways (FieldPlates).
            if (!outside && !b.plate(h) && b.plate(g) && b.level(g) > own) { return; }
            List<FieldBoard.Segment> other = !outside && b.taken(g) ? b.segments(g) : null;
            boolean wall = !outside && b.cliff(h, g);
            // A plate over its neighbour casts a wall in the board cut's form: two rows, straight (FieldPlates).
            boolean cast = !outside && b.plate(h);
            wall &= !cast;
            // The step's height for its landforms (BoardRelief#drop): its levels, at least three for a wall, not the face
            // left above the talus; the rock's prominence and jointing follow it.
            float landform = outside ? own * b.levelZ - chunk.floor
                  : Math.max(own - b.level(g), wall ? 3 : 0) * b.levelZ;
            float otherZ = outside ? chunk.floor : b.level(g) * b.levelZ, m = b.metre;
            float c0x = b.cornerX(h, e), c0y = b.cornerY(h, e), c1x = b.cornerX(h, e + 1), c1y = b.cornerY(h, e + 1);
            float ex = c1x - c0x, ey = c1y - c0y, len = (float) Math.hypot(ex, ey);
            float onx = ey / len, ony = -ex / len;
            if ((b.centerX(h) - c0x) * onx + (b.centerY(h) - c0y) * ony > 0) { onx = -onx; ony = -ony; }
            // A cliff's rows, like today's, let the shader project the rock; a bank's strip and the board's cut keep a frame.
            float frame = wall ? Float.NaN : (float) Math.atan2(ony, onx);
            boolean full = chunk.lod == TerrainLod.FULL, medium = chunk.lod == TerrainLod.MEDIUM;
            // The board's cut has two rows; a step has its levels' rows, every one, every second or one a level.
            int finest = outside || cast ? 2 : ROWS_A_LEVEL * Math.max(1, own - b.level(g)) + 1;
            int stride = outside || cast || full ? 1 : medium ? 2 : ROWS_A_LEVEL;
            // Columns between the lattice samples too (today's walls are finer than the ground).
            int split = outside || cast ? 1 : full || medium ? 3 : 1;
            float[] out = new float[7], tops = new float[n + 1], feet = new float[n + 1];
            int[] samples = new int[n + 1];
            for (int j = 0; j <= n; j++) {
                int v = j == n ? vertex((e + 1) % 6, n, 0) : vertex(e, n, j);
                samples[j] = v;
                tops[j] = z[v];
                feet[j] = otherZ;
                if (other != null) {
                    b.height(g, other, chunk.shares(g), x[v], y[v], out);
                    feet[j] = out[0];
                }
            }
            if (chunk.lod == TerrainLod.DISTANT && n == 2 && !keeps(e)) { feet[1] = (feet[0] + feet[2]) / 2; }
            Wall w = new Wall(e, outside || cast, wall, landform, otherZ, onx, ony, samples, tops, feet, finest);
            w.wet = other != null && b.shore.family(g) >= 0;
            w.fall = !outside && b.falls(h, g);
            int[][] columns = new int[n * split + 1][];
            // Where the relief moves a wall's rim off the row, a level strip joins it to the row's own sample: the
            // caprock's lip.
            int[] rims = new int[n * split + 1];
            for (int c = 0; c <= n * split; c++) {
                w.column(c, n * split, 1);
                int j = Math.min(c / split, n - 1);
                float f = c / (float) split - j;
                float sampleRock = rock[samples[j]] + (rock[samples[j + 1]] - rock[samples[j]]) * f;
                float px = w.px, py = w.py, top = w.top, bottom = w.bottom;
                boolean tall = top - bottom > .01f * m;
                if (cast && own > b.level(g)) {
                    // A plate's road can take its edge below the lower hex's ground: the cast wall closes that way
                    // too. A column with no height keeps its two samples where the coarser wall has height there:
                    // its foot morphs with the lower hex's ground, its top with the plate's.
                    tall = Math.abs(top - bottom) > .01f * m || (full || medium) && c % 2 == 1
                          && Math.abs(tops[c - 1] + tops[c + 1] - feet[c - 1] - feet[c + 1]) > .02f * m;
                }
                if (tall) {
                    int[] rows = columns[c] = new int[(finest - 1) / stride + 1];
                    for (int i = 0; i < rows.length; i++) {
                        // Rows under the foot or over the rim stand on its sample.
                        float zr = i == 0 || i == rows.length - 1 ? Float.NaN : w.height(i * stride);
                        if (zr <= bottom || zr >= top) { rows[i] = zr <= bottom ? rows[0] : -1; continue; }
                        w.point(i * stride, out);
                        zr = out[2];
                        int p = addSample(out[0], out[1], zr, onx, ony, (zr - otherZ) / m, (top - zr) / m, frame);
                        rock[p] = wall ? 1 : sampleRock;
                        rows[i] = p;
                    }
                    for (int i = 1; i < rows.length - 1; i++) { if (rows[i] < 0) { rows[i] = rows[rows.length - 1]; } }
                    rims[c] = addSample(px, py, top, 0, 0, (top - otherZ) / m, 0, Float.NaN);
                    rock[rims[c]] = wall ? 1 : sampleRock;
                    // The lip is level rock: lit from above, its texture projected by the shader, not the wall's frame.
                    nz[rims[c]] = 1;
                } else {
                    columns[c] = new int[] { addSample(px, py, top, onx, ony, 0, 0, frame) };
                    rock[columns[c][0]] = sampleRock;
                    rims[c] = columns[c][0];
                }
                if (c > 0 && (columns[c - 1].length > 1 || columns[c].length > 1)) {
                    strip(columns[c - 1], columns[c]);
                    int[] a = columns[c - 1], d = columns[c];
                    lip(rims[c - 1], a[a.length - 1], rims[c], d[d.length - 1]);
                }
            }
            // The side the strips' triangles face: along the row x up, or against it.
            boolean flip = ey * onx - ex * ony < 0;
            // A cast wall is a vertical plane: its samples keep their outward normal, also where it closes the edge
            // the other way.
            if (!cast) { wallNormals(columns, flip); }
            if (wall) { occlude(columns, c0x, c0y, onx, ony, landform, otherZ, len, w.wet); }
            // Full detail's walls morph toward medium's, medium's toward coarse's (every 2nd or 6th column there).
            // The board's cut keeps its two rows and one column a lattice step at every detail.
            if (full || medium) {
                wallParents(w, columns, rims, outside || cast || full ? 2 : 6, 2, stride,
                      outside || cast ? stride : full ? 2 : ROWS_A_LEVEL, flip, cast);
            } else if (chunk.lod == TerrainLod.COARSE) {
                // COARSE's walls morph to HEXFAN's: every second column (the corners), the same rows; on an edge HEXFAN
                // keeps (#keeps), every column.
                int step = keeps(e) ? 1 : 2;
                wallParents(w, columns, rims, step, step, stride, outside || cast ? stride : ROWS_A_LEVEL, flip, cast);
            }
        }

        /**
         * A cliff's samples' occlusion by today's rule (BoardRelief#wallOcclusion): contact above the lower level
         * {@code base}, shelter under the rim, and how far a sample stands behind the wall round it, two rows up and
         * down and two of today's columns (1.25 m) to each side; (ax, ay) a point of the edge, {@code length} long.
         */
        private void occlude(int[][] columns, float ax, float ay, float onx, float ony, float drop, float base,
              float length, boolean wet) {
            int reach = Math.max(1, Math.round(BoardRelief.metres(1.25f) * (columns.length - 1) / length));
            for (int c = 0; c < columns.length; c++) {
                for (int r = 0; r < columns[c].length; r++) {
                    float around = 0;
                    for (int dr = -2; dr <= 2; dr++) {
                        for (int dc = -2; dc <= 2; dc++) {
                            int[] near = columns[Math.clamp(c + dc * reach, 0, columns.length - 1)];
                            int q = near[Math.clamp(r + dr, 0, near.length - 1)];
                            around += (x[q] - ax) * onx + (y[q] - ay) * ony;
                        }
                    }
                    int p = columns[c][r];
                    // A foot under water (FieldShore) meets the ground at the bed, below the lower level.
                    float ground = wet ? Math.min(base, z[columns[c][0]]) : base;
                    foot[p] = BoardRelief.wallOcclusion(true, drop, (z[p] - ground) / b.metre, faceFoot[p],
                          around / 25 - (x[p] - ax) * onx - (y[p] - ay) * ony);
                }
            }
        }

        /**
         * A wall's normals from its displaced rows and columns, on the side its triangles face ({@code flip}: against
         * along x up), also where the relief turns a face back along the edge: the rock relief lights.
         */
        private void wallNormals(int[][] columns, boolean flip) {
            for (int c = 0; c < columns.length; c++) {
                int[] column = columns[c];
                if (column.length < 2) { continue; }
                int[] before = columns[Math.max(0, c - 1)], after = columns[Math.min(columns.length - 1, c + 1)];
                for (int r = 0; r < column.length; r++) {
                    int a = before[Math.min(r, before.length - 1)], d = after[Math.min(r, after.length - 1)];
                    int down = column[Math.max(0, r - 1)], up = column[Math.min(column.length - 1, r + 1)];
                    float tx = x[d] - x[a], ty = y[d] - y[a], tz = z[d] - z[a];
                    float ux = x[up] - x[down], uy = y[up] - y[down], uz = z[up] - z[down];
                    float cx = ty * uz - tz * uy, cy = tz * ux - tx * uz, cz = tx * uy - ty * ux;
                    if (flip) { cx = -cx; cy = -cy; cz = -cz; }
                    float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (len < 1e-6f) { continue; }
                    int p = column[r];
                    nx[p] = cx / len;
                    ny[p] = cy / len;
                    nz[p] = cz / len;
                }
            }
        }

        /** Two columns joined: rows matched by index, the shorter one's top repeated. */
        private void strip(int[] a, int[] c) {
            int rows = Math.max(a.length, c.length);
            for (int r = 0; r + 1 < rows; r++) {
                int a0 = a[Math.min(r, a.length - 1)], a1 = a[Math.min(r + 1, a.length - 1)];
                int c0 = c[Math.min(r, c.length - 1)], c1 = c[Math.min(r + 1, c.length - 1)];
                if (a0 != a1) { walls = add(walls, wallCount, a0, c0, a1); wallCount += 3; }
                if (c0 != c1) { walls = add(walls, wallCount, a1, c0, c1); wallCount += 3; }
            }
        }

        /**
         * The lip between two columns' rims and top rows, facing up: a wall's top row stands on its rim or outside it
         * (strip() would face it down).
         */
        private void lip(int rimA, int topA, int rimC, int topC) {
            if (rimA != topA) { walls = add(walls, wallCount, rimA, topA, rimC); wallCount += 3; }
            if (rimC != topC) { walls = add(walls, wallCount, topA, topC, rimC); wallCount += 3; }
        }

        private static int[] add(int[] list, int count, int a, int b, int c) {
            if (count + 3 > list.length) { list = java.util.Arrays.copyOf(list, list.length * 2); }
            list[count] = a;
            list[count + 1] = b;
            list[count + 2] = c;
            return list;
        }

        /** A sector triangle by its corners' (ring, along) indices: emitted whole when flat or finest, else as four. */
        private void emit(int k, int i0, int j0, int i1, int j1, int i2, int j2) {
            int extent = Math.max(Math.abs(i1 - i0), Math.max(Math.abs(i2 - i0), Math.abs(i2 - i1)));
            boolean level = allFlat(k, i0, j0, i1, j1, i2, j2);
            // A hex the board cuts merges nothing: the cut's wall has a column at every outer sample, and a merged
            // triangle's edge there, or beside a split one, would meet them in T-junctions (single-pixel holes).
            if (extent > 1 && (!level || cut)) {
                int ia = (i0 + i1) / 2, ja = (j0 + j1) / 2, ib = (i1 + i2) / 2, jb = (j1 + j2) / 2;
                int ic = (i2 + i0) / 2, jc = (j2 + j0) / 2;
                emit(k, i0, j0, ia, ja, ic, jc);
                emit(k, ia, ja, i1, j1, ib, jb);
                emit(k, ic, jc, ib, jb, i2, j2);
                emit(k, ia, ja, ib, jb, ic, jc);
                return;
            }
            int a = vertex(k, i0, j0), c1 = vertex(k, i1, j1), c2 = vertex(k, i2, j2);
            // Counter-clockwise from above, as today's faces.
            float cross = (x[c1] - x[a]) * (y[c2] - y[a]) - (y[c1] - y[a]) * (x[c2] - x[a]);
            int second = cross >= 0 ? c1 : c2, third = cross >= 0 ? c2 : c1;
            // A level triangle under another cover than the hex's own, or in lava's reach, is drawn with its weights.
            if (level && (cover == null || cover.plain(a))) {
                levelTops = add(levelTops, levelTopCount, a, second, third);
                levelTopCount += 3;
            } else if (!level && role[a] + role[c1] + role[c2] >= 2) {
                faces = add(faces, faceCount, a, second, third);
                faceCount += 3;
            } else {
                ground = add(ground, groundCount, a, second, third);
                groundCount += 3;
            }
        }

        private boolean allFlat(int k, int i0, int j0, int i1, int j1, int i2, int j2) {
            int iMin = Math.min(i0, Math.min(i1, i2)), iMax = Math.max(i0, Math.max(i1, i2));
            float d = (float) ((j1 - j2) * (i0 - i2) + (i2 - i1) * (j0 - j2));
            for (int i = iMin; i <= iMax; i++) {
                for (int j = 0; j <= i; j++) {
                    float u = ((j1 - j2) * (i - i2) + (i2 - i1) * (j - j2)) / d;
                    float w = ((j2 - j0) * (i - i2) + (i0 - i2) * (j - j2)) / d;
                    if (u < -1e-4f || w < -1e-4f || 1 - u - w < -1e-4f) { continue; }
                    int v = vertex(k, i, j);
                    // A merged triangle's samples carry one cover alone: its corners' weights then say all of it.
                    if (!flat[v] || cover != null && !cover.alike(vertex(k, i0, j0), v)) { return false; }
                }
            }
            return true;
        }
    }
}
