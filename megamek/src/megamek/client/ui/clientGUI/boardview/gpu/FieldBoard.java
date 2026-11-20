/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;
import megamek.common.board.HexTile;

/**
 * The height field's inputs for one scene revision ({@link TerrainField}), in plain arrays: each hex's level and family,
 * which hexes the field takes, which edges are walls, and every wall edge's and corner's moved samples. Built once per
 * scene revision on the terrain build's worker (GpuTerrain's update plan holds it and replaces it with the next plan);
 * read-only afterwards, so the chunk workers share it. Also the field's evaluator: a hex's height at a point.
 */
final class FieldBoard {
    /** The moved boundary is always sampled at full detail; coarser lattices interpolate it, so their samples nest. */
    static final int FULL = 8;
    /**
     * A wall's rim stands back from the contour by this many metres and by today's rock relief at its rim height
     * (BoardRelief#wallRelief: its retreat, caprock and joints), which breaks the rim as today's is broken.
     */
    private static final float RECESS = 2.5f;
    /** A hex keeps its own level this far round its centre, in metres (today's STEP_KEEP). */
    private static final float KEEP = 6;
    /** The largest move of a rim sample, as a share of the inner radius: the linear falloff never folds below 1. */
    private static final float MAX_MOVE = .6f;
    /** A lattice triangle on a moved rim keeps this share of its unmoved area, or the rim flattens. */
    private static final float FOLD = .1f;
    /** Squared distances closer than this are one distance (steps meeting at a corner sample). */
    private static final float TIE = 1e-6f;
    /** The banks' level-set grid's spacing, in metres. */
    private static final float GRID = 2;
    /**
     * A step further than this many metres from a point plays no part in its height (the cliff weight fades out from
     * half of it); every step this near a sample two hexes share is in both hexes' lists (#segments).
     */
    private static final float FAR = 12;
    /** How far an edit reaches on natural ground, in rings: the rim moves within the level set's kernel round it. */
    static final int REACH = 2;

    /** The two corners (by corner index) whose other hexes both come after a hex's own: the corners it holds. */
    private static final int[] OWNED = java.util.stream.IntStream.range(0, 6)
          .filter(k -> later(BoardGeometry.edgeDirection((k + 5) % 6)) && later(BoardGeometry.edgeDirection(k))).toArray();

    final int cols, rows;
    /**
     * Per board column, by row: each hex's level, family, walls (the edges, by edge index, where it stands a wall above
     * its neighbour by BoardRelief's rule) and whether the field takes it. A revision shares the columns an edit leaves
     * alone with the previous revision, so an edit copies only the columns it reaches.
     */
    private final int[][] level, family, walls;
    private final boolean[][] taken;
    /** Per board column, by row: the ground the hex's scenery digs away (BoardGroundCut#footprint); null for none. */
    private final float[][][] cuts;
    /** Per board column, by row: the arch prisms of the tunnels of the hexes below it (BoardTunnel#region); null for none. */
    private final float[][][][] portals;
    /** Per board column, by row: the footprints of the hex's own ground tunnels, kept clear of its ground. */
    private final float[][][][] openings;
    /** Per board column, by row: the roofed footprints of its neighbours' ground tunnels. */
    private final float[][][][] entries;
    /** Per board column, by row: where a paved hex's slab stops short of its separated sides (#gaps); null for none. */
    private final float[][][][] gaps;
    final float width, height, levelZ, metre, room, edge, taper, keep, recess, bankRadius, bankMix, cliffRadius, maxMove, farReach;
    /** BoardRelief#metres of the evaluator's fixed lengths (in the order they are used), read once. */
    private final float probe, limit, settled, shoulder, talusReach, talusNoise;
    private final BoardRelief.StepOutline outline;
    /**
     * The moved samples per board column x + 1 (with a column of margin either side for edges and corners off the
     * board), by row y + 1: each hex's edges toward its later neighbours (directions 1 to 3) at row * 3 + direction - 1,
     * and the moves of the corners it holds ({@link #OWNED}) at row * 2 + their place there. Shared by column as above.
     */
    private final float[][][][] edgeMoves;
    private final float[][][] cornerMoves;
    /** Per hex, the steps that shape its ground (#segments), listed on the first ask by any chunk worker. */
    private final Map<Coords, List<Segment>> steps = new java.util.concurrent.ConcurrentHashMap<>();
    /** Set once built: moves asked afterwards (edges between two hexes off the board) are computed, never stored. */
    private boolean frozen;
    /** The road corridor, the last layer of {@link #height}. */
    private final FieldRoads roads;
    /** Per board column, by row: the frames of the ground tunnels whose scree can reach the hex (#mouths); null for none. */
    private final float[][][][] mouths;
    /** The pavement's fitted outline, whose corners the plates stand on (FieldPlates): the scene owner's, handed in. */
    private final BoardConcrete concrete;
    /** A hex's bit in {@link #walls} beside its edges' six: the field takes it as a plate (FieldPlates). */
    private static final int PLATE = 1 << 6;
    /** walls' bit LEVEL_SHORE + e: edge e parts natural land from water the shore does not shape at the land's level (#add). */
    private static final int LEVEL_SHORE = 7;
    /** walls' bit JOINED + e: edge e joins two waters the shore holds whose beds meet under them (#hex, #add). */
    private static final int JOINED = 13;
    /** Bits 19-24, edge e pours over a fall into its neighbour (FieldWater#fallsInto). */
    private static final int FALL = 19;
    /** Level water: the hexes its operator shapes and the bed below their waterline. */
    final FieldShore shore;

    /**
     * The inputs for {@code scene}: all of them, or, given the previous revision's and the hexes that changed, those
     * the change reaches (the changed hexes' own values, their neighbours' walls, and the rim moves of the edges and
     * corners within {@code rings} rings) recomputed and the rest kept.
     */
    private FieldBoard(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed,
          int rings, BoardConcrete concrete) {
        this.outline = outline;
        roads = new FieldRoads(scene);
        this.concrete = concrete;
        shore = new FieldShore(scene);
        cols = scene.width();
        rows = scene.height();
        width = BoardGeometry.width();
        height = BoardGeometry.height();
        levelZ = BoardGeometry.level();
        metre = BoardRelief.metres(1);
        room = BoardRelief.metres(BoardRelief.TRANSITION);
        edge = width / 2;
        taper = .3f * edge;
        keep = BoardRelief.metres(KEEP);
        recess = BoardRelief.metres(RECESS);
        bankRadius = (outline == BoardRelief.StepOutline.SMOOTH ? 1.2f : .75f) * width;
        // Hex edges: banks most of the way to Rounded corners' outline, as round as today's Hex edges banks look.
        bankMix = outline == BoardRelief.StepOutline.HEX ? .8f : 1;
        // Walls take Rounded corners' level set in every mode, as today's cliffs look the same in all three.
        cliffRadius = .75f * width;
        maxMove = MAX_MOVE * edge * (float) Math.sqrt(3) / 2;
        farReach = BoardRelief.metres(FAR);
        probe = BoardRelief.metres(.5f);
        limit = BoardRelief.metres(4);
        settled = BoardRelief.metres(.02f);
        shoulder = BoardRelief.metres(35);
        talusReach = BoardRelief.metres(6);
        talusNoise = BoardRelief.metres(9);
        boolean incremental = previous != null && changed != null && previous.cols == cols && previous.rows == rows
              && previous.outline == outline && previous.width == width && previous.levelZ == levelZ;
        if (!incremental) {
            level = new int[cols][rows];
            family = new int[cols][rows];
            walls = new int[cols][rows];
            taken = new boolean[cols][rows];
            cuts = new float[cols][rows][];
            portals = new float[cols][rows][][];
            gaps = new float[cols][rows][][];
            openings = new float[cols][rows][][];
            mouths = new float[cols][rows][][];
            entries = new float[cols][rows][][];
            edgeMoves = new float[cols + 2][(rows + 2) * 3][][];
            cornerMoves = new float[cols + 2][(rows + 2) * 2][];
            for (int c = 0; c < cols; c++) {
                for (int r = 0; r < rows; r++) { hex(scene, new Coords(c, r)); }
            }
        } else {
            level = previous.level.clone();
            family = previous.family.clone();
            walls = previous.walls.clone();
            taken = previous.taken.clone();
            cuts = previous.cuts.clone();
            portals = previous.portals.clone();
            gaps = previous.gaps.clone();
            openings = previous.openings.clone();
            mouths = previous.mouths.clone();
            entries = previous.entries.clone();
            edgeMoves = previous.edgeMoves.clone();
            cornerMoves = previous.cornerMoves.clone();
            java.util.Set<Coords> near = rings(changed, 1), reach = rings(changed, rings);
            // The columns the change reaches are copied before they change; the previous revision keeps its own.
            java.util.Set<Integer> hexColumns = new java.util.HashSet<>(), moveColumns = new java.util.HashSet<>();
            for (Coords at : near) { if (inside(at)) { hexColumns.add(at.getX()); } }
            for (Coords at : reach) {
                for (int x = at.getX() - 1; x <= at.getX() + 1; x++) { if (x >= -1 && x <= cols) { moveColumns.add(x); } }
            }
            for (int x : hexColumns) {
                level[x] = level[x].clone();
                family[x] = family[x].clone();
                walls[x] = walls[x].clone();
                taken[x] = taken[x].clone();
                cuts[x] = cuts[x].clone();
                portals[x] = portals[x].clone();
                gaps[x] = gaps[x].clone();
                openings[x] = openings[x].clone();
                mouths[x] = mouths[x].clone();
                entries[x] = entries[x].clone();
            }
            for (int x : moveColumns) {
                edgeMoves[x + 1] = edgeMoves[x + 1].clone();
                cornerMoves[x + 1] = cornerMoves[x + 1].clone();
            }
            for (Coords at : near) { if (inside(at)) { hex(scene, at); } }
            // Only the reach's edges and corners are moved again; the rest stay as the previous revision has them.
            for (Coords at : reach) {
                for (int e = 0; e < 6; e++) {
                    int d = BoardGeometry.edgeDirection(e);
                    Coords u = later(d) ? at : at.translated(d);
                    int slot = edgeSlot(u, later(d) ? d : (d + 3) % 6);
                    if (slot >= 0) { edgeMoves[u.getX() + 1][slot] = null; }
                    int[] corner = cornerSlot(at, e);
                    if (corner[2] >= 0) { cornerMoves[corner[0] + 1][corner[2]] = null; }
                }
            }
            for (Coords at : reach) {
                for (int e = 0; e < 6 && inside(at); e++) { edgeMove(at, e); }
            }
            return;
        }
        for (int c = 0; c < cols; c++) {
            for (int r = 0; r < rows; r++) {
                for (int e = 0; e < 6; e++) { edgeMove(new Coords(c, r), e); }
            }
        }
    }

    /** One hex's own values: level, family, whether the field takes it, its walls. */
    private void hex(BoardScene scene, Coords at) {
        BoardScene.Tile t = scene.tile(at);
        int x = at.getX(), y = at.getY();
        boolean liquid = t.liquid().present();
        // Water the shore operator shapes stands at its surface's level, on its bed's family (FieldShore).
        int bed = shore.family(at);
        level[x][y] = t.groundLevel() - (liquid && bed < 0 ? Math.max(0, t.waterDepth()) : 0);
        family[x][y] = bed < 0 ? t.surface().ordinal() : bed;
        taken[x][y] = dryNatural(t) || bed >= 0 || FieldPlates.takes(scene, t);
        gaps[x][y] = null;
        if (FieldPlates.parted(scene, t)) {
            com.badlogic.gdx.math.Vector3[] corners = new com.badlogic.gdx.math.Vector3[6];
            for (int k = 0; k < 6; k++) { corners[k] = concrete.corner(at, k); }
            List<float[]> strips = BoardSurface.gapStrips(scene, t, corners);
            gaps[x][y] = strips.isEmpty() ? null : strips.toArray(new float[0][]);
        }
        cuts[x][y] = BoardGroundCut.footprint(scene, t);
        // As BoardSurface#walls: the portals of every tunnel of a lower neighbour are cut from the hex's walls. Every
        // neighbour of a ground tunnel's road hex is cut along its footprint too (#entries), so they share its samples.
        List<float[]> arches = new ArrayList<>(), ground = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            BoardScene.Tile next = scene.tile(at.translated(d));
            if (next == null) { continue; }
            for (var tunnel : BoardTunnel.entrances(scene, next)) {
                if (next.elevation() < t.elevation()) { arches.add(tunnel.region()); }
                if (!tunnel.bridge()) { ground.add(tunnel.footprint(true)); }
            }
        }
        // The join's scree (#height, FieldStamps#mound): the frames of the ground tunnels it can reach the hex from.
        List<float[]> entering = new ArrayList<>();
        for (BoardTunnel tunnel : BoardTunnel.near(scene, at)) {
            float[] frame = tunnel.frame(scene);
            if (frame != null) { entering.add(frame); }
        }
        portals[x][y] = arches.isEmpty() ? null : arches.toArray(new float[0][]);
        entries[x][y] = ground.isEmpty() ? null : ground.toArray(new float[0][]);
        mouths[x][y] = entering.isEmpty() ? null : entering.toArray(new float[0][]);
        openings[x][y] = BoardTunnel.entrances(scene, t).stream().filter(tunnel -> !tunnel.bridge())
              .map(tunnel -> tunnel.footprint(false)).toArray(float[][]::new);
        if (openings[x][y].length == 0) { openings[x][y] = null; }
        walls[x][y] = 0;
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
            // Two waters the shore holds whose beds meet under them: at one surface, or a stream running down between
            // them (BoardSurface#waterSlope: joined, at most two levels apart), whose bed is today's (FieldShore#shallows).
            boolean joined = meet(t, next);
            // A wall, as BoardRelief draws one: a drop of more than two and a half levels for its landforms.
            if (next != null && BoardRelief.levels(t, next) > 2.5f && !joined) { walls[x][y] |= 1 << e; }
            // A pool above lower ground or water ends in a rock step (falls, raised water: b and c).
            if (next != null && bed >= 0 && next.groundLevel() < t.groundLevel() && !joined) { walls[x][y] |= 1 << e; }
            if (next != null && levelShore(t, next)) { walls[x][y] |= 1 << (LEVEL_SHORE + e); }
            if (joined) { walls[x][y] |= 1 << (JOINED + e); }
            if (FieldWater.fallsInto(t, next, BoardGeometry.edgeDirection(e))) { walls[x][y] |= 1 << (FALL + e); }
        }
        // A plate stands on its fitted outline: every change of level at its edges is a wall, and so is its edge to
        // water, a quay (FieldPlates).
        if (taken[x][y] && !dryNatural(t) && bed < 0) {
            walls[x][y] |= PLATE;
            for (int e = 0; e < 6; e++) {
                Coords n = at.translated(BoardGeometry.edgeDirection(e));
                BoardScene.Tile next = scene.tile(n);
                if (next == null) { continue; }
                int nextLevel = next.groundLevel() - (next.liquid().present() && shore.family(n) < 0 ? Math.max(0, next.waterDepth()) : 0);
                if (nextLevel != level[x][y] || next.liquid().present()) { walls[x][y] |= 1 << e; }
            }
        } else {
            // A step between two other hexes that ends at a plate's corner is a wall too: a bank there would have to
            // meet the plate's level and the lower hex's at one point.
            for (int e = 0; e < 6; e++) {
                BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
                if (next == null || (walls[x][y] & 1 << e) != 0) { continue; }
                Coords n = at.translated(BoardGeometry.edgeDirection(e));
                int nextLevel = next.groundLevel() - (next.liquid().present() && shore.family(n) < 0 ? Math.max(0, next.waterDepth()) : 0);
                if (nextLevel == level[x][y]) { continue; }
                for (int side : new int[] { (e + 5) % 6, (e + 1) % 6 }) {
                    BoardScene.Tile third = scene.tile(at.translated(BoardGeometry.edgeDirection(side)));
                    if (third != null && FieldPlates.takes(scene, third)) { walls[x][y] |= 1 << e; }
                }
            }
        }
    }

    private static java.util.Set<Coords> rings(java.util.Set<Coords> hexes, int rings) {
        java.util.Set<Coords> result = new java.util.HashSet<>(hexes);
        for (int r = 0; r < rings; r++) {
            for (Coords at : List.copyOf(result)) {
                for (int d = 0; d < 6; d++) { result.add(at.translated(d)); }
            }
        }
        return result;
    }

    /** The field's inputs for {@code scene} in the given hill outline mode; on a terrain worker. */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline) {
        return of(scene, outline, null, null);
    }

    /** The next revision's inputs on natural ground: {@link #of(BoardScene, BoardRelief.StepOutline, FieldBoard, java.util.Set, int)} at {@link #REACH}. */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed) {
        return of(scene, outline, previous, changed, REACH);
    }

    /**
     * The next revision's inputs: {@code previous}'s with the hexes within {@code rings} of {@code changed} recomputed,
     * {@code rings} being the largest reach of the layers in use (all of them when there is no previous, its board or
     * outline differs, or {@code changed} is null: a full rebuild).
     */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed,
          int rings) {
        // Tools and tests: the fit of the caller's own scene, taken on the caller's thread.
        return of(scene, outline, previous, changed, rings, BoardConcrete.of(scene));
    }

    /** The scene owner's revision (GpuTerrain's update plan): its concrete fit handed in with the scene, at {@link #REACH}. */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed,
          BoardConcrete concrete) {
        return of(scene, outline, previous, changed, REACH, concrete);
    }

    /** As {@link #of(BoardScene, BoardRelief.StepOutline, FieldBoard, java.util.Set, int)}, with the scene's concrete fit. */
    static FieldBoard of(BoardScene scene, BoardRelief.StepOutline outline, FieldBoard previous, java.util.Set<Coords> changed,
          int rings, BoardConcrete concrete) {
        FieldBoard board = new FieldBoard(scene.groundScene(), outline, previous, changed, rings, concrete);
        board.frozen = true;
        return board;
    }

    /**
     * The hexes the field takes: dry natural ground, its roads, rails and their ramps included, without paving; a
     * bridge's hex too, whose ground under the deck is the field's (the deck, its piers and footing stay objects).
     */
    static boolean dryNatural(BoardScene.Tile t) {
        return !t.liquid().present() && t.surface() != BoardScene.Surface.CONCRETE && !t.ultraSublevel()
              && !t.backdrop().present() && !BoardFeatures.shaft(t);
    }

    /** A step's edge: its two levels, whether a cliff, and its upper hex ({@link #packed}; -1 where none). */
    /** A hex as one int: column * 4096 + row, exact in a float (columns and rows under 4096). */
    static int packed(Coords c) { return c.getX() * 4096 + c.getY(); }

    /** The hex {@link #packed} gave, or null for -1. */
    static Coords unpacked(int packed) { return packed < 0 ? null : new Coords(packed / 4096, packed % 4096); }

    record Segment(float ax, float ay, float bx, float by, int low, int high, boolean cliff, int top, boolean joined) {
        float distance(float x, float y) { return (float) Math.sqrt(squared(x, y)); }

        float squared(float x, float y) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            float px = ax + t * dx - x, py = ay + t * dy - y;
            return px * px + py * py;
        }
    }

    private int column(Coords c) { return Math.clamp(c.getX(), 0, cols - 1); }

    private int row(Coords c) { return Math.clamp(c.getY(), 0, rows - 1); }

    boolean inside(Coords c) { return c.getX() >= 0 && c.getY() >= 0 && c.getX() < cols && c.getY() < rows; }

    boolean taken(Coords c) { return inside(c) && taken[c.getX()][c.getY()]; }

    /** Whether the field takes the hex as a plate (FieldPlates). */
    boolean plate(Coords c) { return inside(c) && (walls[c.getX()][c.getY()] & PLATE) != 0; }

    /**
     * Whether, of a and b, one is natural land and the other water the shore does not shape (today's code draws it) whose
     * surface stands at the land's own level: today's water hex brings its bank up to the land there (#add: no step).
     */
    private boolean levelShore(BoardScene.Tile a, BoardScene.Tile b) {
        BoardScene.Tile land = a.liquid().present() ? b : a, water = land == a ? b : a;
        return !land.liquid().present() && dryNatural(land) && water.liquid().present() && !water.liquid().molten()
              && !water.frozen() && shore.family(water.coords()) < 0 && water.groundLevel() == land.groundLevel();
    }

    /** Whether the edge between u and v joins two waters the shore holds whose beds meet under them (#hex). */
    private boolean joinedWater(Coords u, Coords v) {
        return inside(u) && inside(v) && ((walls[u.getX()][u.getY()] & 1 << (JOINED + edgeIndex(u, v))) != 0
              || (walls[v.getX()][v.getY()] & 1 << (JOINED + edgeIndex(v, u))) != 0);
    }

    /** Whether u pours into its neighbour v over a fall (FieldWater#fallsInto): the crest is u's rim row there. */
    boolean falls(Coords u, Coords v) {
        return inside(u) && inside(v) && (walls[u.getX()][u.getY()] & 1 << (FALL + edgeIndex(u, v))) != 0;
    }

    /** Whether the edge between u and v parts natural land from water the shore does not shape at the land's level. */
    private boolean levelShore(Coords u, Coords v) {
        return inside(u) && inside(v) && ((walls[u.getX()][u.getY()] & 1 << (LEVEL_SHORE + edgeIndex(u, v))) != 0
              || (walls[v.getX()][v.getY()] & 1 << (LEVEL_SHORE + edgeIndex(v, u))) != 0);
    }

    /** Two waters the shore holds whose beds meet under them: at one surface, or a stream down between them (waterSlope). */
    private boolean meet(BoardScene.Tile t, BoardScene.Tile next) {
        return t != null && next != null && shore.family(t.coords()) >= 0 && shore.family(next.coords()) >= 0
              && (next.groundLevel() == t.groundLevel() || BoardSurface.waterSlope(t, next));
    }

    /**
     * The distance from (x, y) to the nearest wall's row round the hex the point lies in: each wall edge's samples as
     * moved (#edgeMove), as #add lays its segments; infinite for none. A function of the position alone, so every hex
     * whose block holds the point gives it one value.
     */
    private float wallDistance(float x, float y) {
        Coords at = GpuStarShells.hexAt(x, y);
        float best = Float.POSITIVE_INFINITY;
        for (int d = -1; d < 6; d++) {
            Coords u = d < 0 ? at : at.translated(d);
            if (!inside(u)) { continue; }
            for (int e = 0; e < 6; e++) {
                if (!cliff(u, u.translated(BoardGeometry.edgeDirection(e)))) { continue; }
                float[][] m = edgeMove(u, e);
                float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
                for (int j = 0; j < FULL; j++) {
                    float t0 = j / (float) FULL, t1 = (j + 1) / (float) FULL;
                    Segment s = new Segment(ax + (bx - ax) * t0 + m[j][0], ay + (by - ay) * t0 + m[j][1],
                          ax + (bx - ax) * t1 + m[j + 1][0], ay + (by - ay) * t1 + m[j + 1][1], 0, 0, true, -1, false);
                    best = Math.min(best, s.squared(x, y));
                }
            }
        }
        return (float) Math.sqrt(best);
    }

    /** Whether (x, y) lies on or in a neighbouring plate at h's own level, by its fitted corners. */
    private boolean onPlate(Coords h, float x, float y) {
        for (int e = 0; e < 6; e++) {
            Coords g = h.translated(BoardGeometry.edgeDirection(e));
            if (!plate(g) || level(g) != level(h) || cliff(h, g)) { continue; }
            float[] px = new float[6], py = new float[6];
            for (int k = 0; k < 6; k++) {
                float[] move = cornerMove(g, k);
                px[k] = cornerX(g, k) + move[0];
                py[k] = cornerY(g, k) + move[1];
            }
            if (FieldPlates.covers(px, py, x, y, 1e-3f * width)) { return true; }
        }
        return false;
    }

    int level(Coords c) { return level[column(c)][row(c)]; }

    /** The arch prisms cut from the hex's walls (BoardTunnel#region); null for none. */
    float[][] portals(Coords c) { return inside(c) ? portals[c.getX()][c.getY()] : null; }

    /** The roofed footprints of its neighbours' ground tunnels (BoardTunnel#footprint); null for none. */
    float[][] entries(Coords c) { return inside(c) ? entries[c.getX()][c.getY()] : null; }

    /** The footprints of the hex's own ground tunnels (BoardTunnel#footprint); null for none. */
    float[][] openings(Coords c) { return inside(c) ? openings[c.getX()][c.getY()] : null; }

    /**
     * Where a paved hex's slab stops short of its separated sides, on its fitted outline (BoardSurface#gapStrips): per
     * strip three planes in FieldStamps' form (six floats a plane, normal x, y, 0 and a point; inside where every
     * distance is at most 0), closed by the hex's own edge; null for none.
     */
    float[][] gaps(Coords c) { return inside(c) ? gaps[c.getX()][c.getY()] : null; }

    /** The frames of the ground tunnels whose scree can reach the hex (BoardTunnel#near, #frame); null for none. */
    float[][] mouths(Coords c) { return inside(c) ? mouths[c.getX()][c.getY()] : null; }

    /**
     * Eases a wall point's push off its row at (px, py), height z, in {@code out} (x, y) round the ground portals near
     * hex h, as today's relief does (BoardTunnel#relief): the face a portal is set into stays flat. Untouched where no
     * portal is near.
     */
    void portalFace(Coords h, float px, float py, float z, float[] out) {
        float keep = relief(h, px, py, z);
        if (keep >= 1) { return; }
        out[0] = px + (out[0] - px) * keep;
        out[1] = py + (out[1] - py) * keep;
    }

    /** The share of a cliff's rock relief standing at (x, y, z) round the ground portals near hex h (BoardTunnel#relief). */
    private float relief(Coords h, float x, float y, float z) {
        float[][] frames = mouths(h);
        float keep = 1;
        for (int i = 0; frames != null && i < frames.length; i++) { keep = Math.min(keep, BoardTunnel.relief(frames[i], x, y, z)); }
        return keep;
    }

    /** Whether the hex is a ground portal's road hex (BoardTunnel#frame): the one whose ground its approach lowers. */
    boolean portal(Coords c) {
        float[][] frames = mouths(c);
        for (int i = 0; frames != null && i < frames.length; i++) {
            if (frames[i][13] == packed(c)) { return true; }
        }
        return false;
    }

    /** The carriageway's surface at (x, y) as hex h sees it (FieldRoads#bed); NaN without a road in reach. */
    float bed(Coords h, float x, float y) { return roads.bed(h, x, y); }

    /** The ground the hex's scenery digs away: its cut triangles' x and y (BoardGroundCut#footprint); null for none. */
    float[] cuts(Coords c) { return inside(c) ? cuts[c.getX()][c.getY()] : null; }

    /** The hex's surface family, as a {@link BoardScene.Surface} ordinal. */
    int family(Coords c) { return family[column(c)][row(c)]; }

    float cornerX(Coords h, int k) { return HexTile.cornerX(h, Math.floorMod(k, 6), width); }

    float cornerY(Coords h, int k) { return HexTile.cornerY(h, Math.floorMod(k, 6), height); }

    float centerX(Coords h) { return HexTile.centerX(h, width); }

    float centerY(Coords h) { return HexTile.centerY(h, height); }

    /** Whether the step between u and v is a wall by BoardRelief's rule (BoardRelief#levels). */
    boolean cliff(Coords u, Coords v) {
        return inside(u) && inside(v) && ((walls[u.getX()][u.getY()] & 1 << edgeIndex(u, v)) != 0
              || (walls[v.getX()][v.getY()] & 1 << edgeIndex(v, u)) != 0);
    }

    /** Whether a carriageway ramps across the edge between u and v (FieldRoads#ramps). */
    boolean ramp(Coords u, Coords v) {
        return (roads.ramps(u) & 1 << u.direction(v)) != 0 || (roads.ramps(v) & 1 << v.direction(u)) != 0;
    }

    /** A wall whose row the field moves: a cliff between two hexes it takes. */
    private boolean wall(Coords u, Coords v) { return taken(u) && taken(v) && cliff(u, v); }

    /** The edge index of {@code from}'s edge toward its neighbour {@code to}. */
    static int edgeIndex(Coords from, Coords to) {
        int d = from.direction(to), e = 0;
        while (BoardGeometry.edgeDirection(e) != d) { e++; }
        return e;
    }

    private int middle(Coords u, Coords v) { return Math.floorDiv(level(u) + level(v) + 1, 2); }

    /** The share of the kernel round (x, y) on hexes at level k or above: a smoothed level set (HexTile's centres). */
    float share(int k, float x, float y, float reach) {
        float column = width * .75f, inverse = 1 / (reach * reach);
        int c0 = (int) Math.floor((x - reach) / column) - 1, c1 = (int) Math.ceil((x + reach) / column) + 1;
        int r0 = (int) Math.floor((-y - reach) / height) - 1, r1 = (int) Math.ceil((-y + reach) / height) + 1;
        float sum = 0, weight = 0;
        for (int c = c0; c <= c1; c++) {
            float dx = x - (c * column + width / 2);
            int[] levels = level[Math.clamp(c, 0, cols - 1)];
            float odd = (c & 1) * height / 2 + height / 2;
            for (int r = r0; r <= r1; r++) {
                float dy = y + r * height + odd;
                float q = 1 - (dx * dx + dy * dy) * inverse;
                if (q <= 0) { continue; }
                float kq = q * q * q;
                sum += levels[Math.clamp(r, 0, rows - 1)] >= k ? kq : 0;
                weight += kq;
            }
        }
        return weight == 0 ? 0 : sum / weight;
    }

    /**
     * The banks' smoothed level sets over one chunk, sampled lazily on a grid of {@link #GRID} metres (value and
     * gradient interpolated bilinearly): the field reads them from here instead of summing the kernel per sample. Shared
     * by the chunk's workers; a node computed twice gets the same value. The grid's nodes lie on one board-wide grid, so
     * two chunks give a point on their border the same value.
     */
    final class Shares {
        private final float x0, y0, step;
        private final int nx, ny;
        private final Map<Integer, float[]> grids = new java.util.concurrent.ConcurrentHashMap<>();

        Shares(float minX, float minY, float maxX, float maxY) {
            step = BoardRelief.metres(GRID);
            x0 = (float) Math.floor(minX / step - 2) * step;
            y0 = (float) Math.floor(minY / step - 2) * step;
            nx = (int) Math.ceil((maxX - x0) / step) + 3;
            ny = (int) Math.ceil((maxY - y0) / step) + 3;
        }

        private float node(float[] grid, int k, int i, int j) {
            i = Math.clamp(i, 0, nx - 1);
            j = Math.clamp(j, 0, ny - 1);
            float v = grid[j * nx + i];
            if (Float.isNaN(v)) { grid[j * nx + i] = v = share(k, x0 + i * step, y0 + j * step, bankRadius); }
            return v;
        }

        /** out: level k's share at (x, y) and its gradient. */
        void sample(int k, float x, float y, float[] out) {
            float[] grid = grids.computeIfAbsent(k, key -> {
                float[] values = new float[nx * ny];
                Arrays.fill(values, Float.NaN);
                return values;
            });
            float fx = (x - x0) / step, fy = (y - y0) / step;
            int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
            float u = fx - i, w = fy - j;
            float v = 0, gx = 0, gy = 0;
            for (int b = 0; b < 2; b++) {
                for (int a = 0; a < 2; a++) {
                    float weight = (a == 0 ? 1 - u : u) * (b == 0 ? 1 - w : w);
                    int ii = i + a, jj = j + b;
                    v += weight * node(grid, k, ii, jj);
                    gx += weight * (node(grid, k, ii + 1, jj) - node(grid, k, ii - 1, jj)) / (2 * step);
                    gy += weight * (node(grid, k, ii, jj + 1) - node(grid, k, ii, jj - 1)) / (2 * step);
                }
            }
            out[0] = v;
            out[1] = gx;
            out[2] = gy;
        }
    }

    /** out: signed distance from the half line of level k's set (positive outside it) and the outward normal. */
    private void contour(int k, float x, float y, float reach, float[] out) {
        float d = probe;
        float v = share(k, x, y, reach) - .5f;
        float gx = (share(k, x + d, y, reach) - share(k, x - d, y, reach)) / (2 * d);
        float gy = (share(k, x, y + d, reach) - share(k, x, y - d, reach)) / (2 * d);
        float g = (float) Math.hypot(gx, gy);
        out[0] = g < 1e-6f ? (v > 0 ? -1e6f : 1e6f) : -v / g;
        out[1] = g < 1e-6f ? 0 : -gx / g;
        out[2] = g < 1e-6f ? 0 : -gy / g;
    }

    /**
     * The move of a rim sample at (x, y) onto level k's contour, set back into the upper side by the recess and today's
     * rock relief at the rim (between {@code upper} and {@code lower}),
     * capped, and kept off the centres of the hexes {@code around} (upper ones by the keep, lower ones by the keep and
     * the talus's reach). The sample lies on hex {@code at}'s edge e, at t from its first corner.
     */
    private float[] rimMove(int k, float x, float y, Coords[] around, Coords upper, Coords lower, Coords at, int e, float t) {
        float[] o = new float[3];
        float px = x, py = y;
        for (int it = 0; it < 12; it++) {
            contour(k, px, py, cliffRadius, o);
            if (o[1] == 0 && o[2] == 0) { break; }
            float step = Math.clamp(-o[0], -limit, limit);
            px += o[1] * step;
            py += o[2] * step;
            if (Math.abs(step) < settled) { break; }
        }
        contour(k, px, py, cliffRadius, o);
        float top = level(upper) * levelZ, bottom = level(lower) * levelZ;
        float drop = Math.max(level(upper) - level(lower), 3) * levelZ;
        float r = recess - BoardRelief.wallRelief(px, py, top, bottom, top, drop, family(upper));
        float dx = px - x - r * o[1], dy = py - y - r * o[2];
        // The roads' term: the rim recedes from the carriageways beside it by today's BoardRelief#roadPush.
        float[] push = roads.push(at, e, t, x + dx, y + dy);
        if (push != null) { dx += push[0]; dy += push[1]; }
        float len = (float) Math.hypot(dx, dy);
        if (len > maxMove) { dx *= maxMove / len; dy *= maxMove / len; }
        for (Coords c : around) {
            float clear = level(c) >= k ? keep : keep + room;
            float ox = x + dx - centerX(c), oy = y + dy - centerY(c);
            float r0 = (float) Math.hypot(ox, oy);
            if (r0 < clear && r0 > 1e-3f) {
                dx += ox / r0 * (clear - r0);
                dy += oy / r0 * (clear - r0);
            }
        }
        return new float[] { dx, dy, 0 };
    }

    /** Whether a neighbour in direction d comes after the hex (a larger column, or the same column and a larger row). */
    private static boolean later(int d) { return d >= 1 && d <= 3; }

    /** The edge from u toward its later neighbour in direction d (1 to 3): its place in u's column; -1 past the margin. */
    private int edgeSlot(Coords u, int d) {
        int x = u.getX() + 1, y = u.getY() + 1;
        return x < 0 || x >= cols + 2 || y < 0 || y >= rows + 2 ? -1 : y * 3 + d - 1;
    }

    /**
     * Corner k of hex h as the first of its three hexes holds it: {that hex's x, y, its place in the column (-1 past the
     * margin), the corner's index there}.
     */
    private int[] cornerSlot(Coords h, int k) {
        Coords[] t = { h, h.translated(BoardGeometry.edgeDirection((k + 5) % 6)), h.translated(BoardGeometry.edgeDirection(k)) };
        for (Coords o : t) {
            for (int s = 0; s < OWNED.length; s++) {
                Coords a = o.translated(BoardGeometry.edgeDirection((OWNED[s] + 5) % 6));
                Coords b = o.translated(BoardGeometry.edgeDirection(OWNED[s]));
                if (!List.of(t).containsAll(List.of(a, b))) { continue; }
                int x = o.getX() + 1, y = o.getY() + 1;
                boolean held = x >= 0 && x < cols + 2 && y >= 0 && y < rows + 2;
                return new int[] { o.getX(), o.getY(), held ? y * 2 + s : -1, OWNED[s] };
            }
        }
        throw new IllegalStateException("no first hex at corner " + k + " of " + h);
    }

    /**
     * Corner k of hex h (shared with its neighbours k - 1 and k): {dx, dy, 1 when a wall's corner is held}. Moved in the
     * frame of the corner's first hex, whichever of its hexes asks, so an edit's revision moves it as a whole build does.
     */
    private float[] cornerMove(Coords h, int k) {
        int[] slot = cornerSlot(h, k);
        float[] cached = slot[2] < 0 ? null : cornerMoves[slot[0] + 1][slot[2]];
        if (cached != null) { return cached; }
        float[] result = corner(new Coords(slot[0], slot[1]), slot[3]);
        if (!frozen && slot[2] >= 0) { cornerMoves[slot[0] + 1][slot[2]] = result; }
        return result;
    }

    private float[] corner(Coords h, int k) {
        Coords[] t = { h, h.translated(BoardGeometry.edgeDirection((k + 5) % 6)), h.translated(BoardGeometry.edgeDirection(k)) };
        float[] result = { 0, 0, 0 };
        // A corner a plate touches stands where the pavement's fit puts it; no rim moves it (FieldPlates).
        if (plate(t[0]) || plate(t[1]) || plate(t[2])) { return FieldPlates.corner(concrete, h, k, cornerX(h, k), cornerY(h, k)); }
        int k0 = 0;
        Coords upperHex = null, lowerHex = null;
        boolean[] side = null;
        boolean consistent = true, any = false;
        int[][] pairs = { { 0, 1 }, { 0, 2 }, { 1, 2 } };
        for (int[] p : pairs) {
            if (!cliff(t[p[0]], t[p[1]])) { continue; }
            any = true;
            if (!wall(t[p[0]], t[p[1]])) { consistent = false; continue; }
            int km = middle(t[p[0]], t[p[1]]);
            boolean[] up = { level(t[0]) >= km, level(t[1]) >= km, level(t[2]) >= km };
            if (side == null) {
                k0 = km;
                side = up;
                boolean first = level(t[p[0]]) > level(t[p[1]]);
                upperHex = first ? t[p[0]] : t[p[1]];
                lowerHex = first ? t[p[1]] : t[p[0]];
            }
            else if (!Arrays.equals(up, side)) { consistent = false; }
        }
        boolean all = taken(t[0]) && taken(t[1]) && taken(t[2]);
        if (any) {
            // A cliff between two hexes at one level (deep water beside land: BoardRelief#levels counts the depth) has no
            // rise to move a rim by (#rimMove would divide by zero): the corner is held, as an inconsistent one.
            if (!all || !consistent || side == null || level(upperHex) == level(lowerHex)) {
                result[2] = 1;
            } else {
                result = rimMove(k0, cornerX(h, k), cornerY(h, k), t, upperHex, lowerHex, h, k, 0);
                // Round a ground portal the corner stays put, as the row beside it does (#canonicalEdge).
                float foot = Math.min(level(t[0]), Math.min(level(t[1]), level(t[2]))) * levelZ, share = 1;
                for (Coords c : t) { share = Math.min(share, relief(c, cornerX(h, k), cornerY(h, k), foot)); }
                result[0] *= share;
                result[1] *= share;
            }
        }
        return result;
    }

    /** An edge's key, the same from either hex (the smaller coordinates first). */
    private static long edgeKey(Coords h, Coords g) {
        boolean first = h.getX() < g.getX() || h.getX() == g.getX() && h.getY() < g.getY();
        Coords u = first ? h : g, v = first ? g : h;
        return (long) (u.getX() & 0xffff) << 48 | (long) (u.getY() & 0xffff) << 32 | (long) (v.getX() & 0xffff) << 16
              | v.getY() & 0xffff;
    }

    /** The moves of h's edge e's samples at full detail, from corner e to corner e + 1; identical from either hex. */
    float[][] edgeMove(Coords h, int e) {
        int d = BoardGeometry.edgeDirection(e);
        Coords g = h.translated(d);
        boolean canonical = later(d);
        Coords u = canonical ? h : g, v = canonical ? g : h;
        int slot = edgeSlot(u, canonical ? d : (d + 3) % 6);
        float[][] points = slot < 0 ? null : edgeMoves[u.getX() + 1][slot];
        if (points == null) {
            points = canonicalEdge(u, v);
            if (!frozen && slot >= 0) { edgeMoves[u.getX() + 1][slot] = points; }
        }
        if (canonical) { return points; }
        float[][] reversed = new float[FULL + 1][];
        for (int j = 0; j <= FULL; j++) { reversed[j] = points[FULL - j]; }
        return reversed;
    }

    private float[][] canonicalEdge(Coords u, Coords v) {
        int e = edgeIndex(u, v);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[] ca = cornerMove(u, e), cb = cornerMove(u, (e + 1) % 6);
        // A plate's edge runs straight between its corners.
        // A cliff between two hexes at one level is the shore's to shape: no rise to move a rim by (#corner).
        boolean wall = wall(u, v) && !plate(u) && !plate(v) && level(u) != level(v);
        Coords[] around = { u, v };
        float[][] points = new float[FULL + 1][];
        float fade = .5f * edge;
        for (int j = 0; j <= FULL; j++) {
            float t = j / (float) FULL;
            float mx = ca[0] + (cb[0] - ca[0]) * t, my = ca[1] + (cb[1] - ca[1]) * t;
            if (j == 0 || j == FULL || !wall) { points[j] = new float[] { mx, my }; continue; }
            float[] d = rimMove(middle(u, v), ax + (bx - ax) * t, ay + (by - ay) * t, around,
                  level(u) > level(v) ? u : v, level(u) > level(v) ? v : u, u, e, t);
            // The move fades out toward a held corner.
            float w = 1;
            if (ca[2] > 0) { w = Math.min(w, t * edge / fade); }
            if (cb[2] > 0) { w = Math.min(w, (1 - t) * edge / fade); }
            w = w * w * (3 - 2 * w);
            // Round a ground portal the wall's row stays on the edge, as today's foot does (BoardTunnel#relief).
            float x = ax + (bx - ax) * t, y = ay + (by - ay) * t, foot = Math.min(level(u), level(v)) * levelZ;
            w *= Math.min(relief(u, x, y, foot), relief(v, x, y, foot));
            points[j] = new float[] { mx + (d[0] - mx) * w, my + (d[1] - my) * w };
        }
        // A wall's rim flattens toward its corners' line until neither hex's lattice folds on it.
        for (int left = FULL; wall && left > 0 && !(unfolded(u, e, points, false)
              && unfolded(v, edgeIndex(v, u), points, true)); left--) {
            for (int j = 1; j < FULL; j++) {
                float t = j / (float) FULL;
                float mx = ca[0] + (cb[0] - ca[0]) * t, my = ca[1] + (cb[1] - ca[1]) * t;
                points[j][0] = mx + (points[j][0] - mx) * (left - 1) / left;
                points[j][1] = my + (points[j][1] - my) * (left - 1) / left;
            }
        }
        return points;
    }

    /**
     * Whether the lattice hex h lays on its edge e's moves (ChunkField.Builder#run: the moved outline scaled toward the
     * centre, ring by ring) keeps, at every detail, each triangle turning its unmoved way with {@link #FOLD} of its
     * area; {@code reversed}: the moves run from corner e + 1 to corner e.
     */
    private boolean unfolded(Coords h, int e, float[][] moves, boolean reversed) {
        float cx = centerX(h), cy = centerY(h), ax = cornerX(h, e) - cx, ay = cornerY(h, e) - cy;
        float bx = cornerX(h, e + 1) - cx, by = cornerY(h, e + 1) - cy;
        float whole = ax * by - ay * bx, sign = Math.signum(whole);
        for (int n = FULL; n > 1; n /= 2) {
            // The samples from the centre, ring i's sample j at i * (n + 1) + j.
            float[] px = new float[(n + 1) * (n + 1)], py = new float[px.length];
            for (int i = 1; i <= n; i++) {
                for (int j = 0; j <= i; j++) {
                    float at = j * (float) FULL / i;
                    int j0 = Math.min((int) at, FULL - 1);
                    float[] p0 = moves[reversed ? FULL - j0 : j0], p1 = moves[reversed ? FULL - j0 - 1 : j0 + 1];
                    px[i * (n + 1) + j] = (ax + (bx - ax) * at / FULL + p0[0] + (p1[0] - p0[0]) * (at - j0)) * i / n;
                    py[i * (n + 1) + j] = (ay + (by - ay) * at / FULL + p0[1] + (p1[1] - p0[1]) * (at - j0)) * i / n;
                }
            }
            float least = FOLD * Math.abs(whole) / (n * n);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j <= i; j++) {
                    int a = i * (n + 1) + j, c = a + n + 1;
                    if (sign * turn(px, py, a, c, c + 1) < least
                          || j < i && sign * turn(px, py, a, c + 1, a + 1) < least) { return false; }
                }
            }
        }
        return true;
    }

    private static float turn(float[] x, float[] y, int a, int b, int c) {
        return (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a]);
    }

    /**
     * The steps round h along the moved edges: its six edges and the six spokes at its corners, and any other step at a
     * corner of a neighbour that lies within {@link #FAR} (and a metre) of h's moved outline. Every step within FAR of a
     * sample two hexes share is then in both hexes' lists, so both give it one height.
     */
    List<Segment> segments(Coords h) {
        return steps.computeIfAbsent(h, this::near);
    }

    private List<Segment> near(Coords h) {
        List<Segment> result = new ArrayList<>(12 * FULL);
        java.util.Set<Long> listed = new java.util.HashSet<>();
        for (int e = 0; e < 6; e++) {
            Coords g = h.translated(BoardGeometry.edgeDirection(e));
            add(result, h, e);
            Coords next = h.translated(BoardGeometry.edgeDirection((e + 1) % 6));
            add(result, g, edgeIndex(g, next));
            listed.add(edgeKey(h, g));
            listed.add(edgeKey(g, next));
        }
        // h's moved outline, at full detail.
        float[] ox = new float[6 * FULL + 1], oy = new float[6 * FULL + 1];
        for (int e = 0; e < 6; e++) {
            float[][] d = edgeMove(h, e);
            for (int j = 0; j < FULL; j++) {
                float t = j / (float) FULL;
                ox[e * FULL + j] = cornerX(h, e) + (cornerX(h, e + 1) - cornerX(h, e)) * t + d[j][0];
                oy[e * FULL + j] = cornerY(h, e) + (cornerY(h, e + 1) - cornerY(h, e)) * t + d[j][1];
            }
        }
        ox[6 * FULL] = ox[0];
        oy[6 * FULL] = oy[0];
        float reach = farReach + metre;
        List<Segment> candidates = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            Coords c = h.translated(BoardGeometry.edgeDirection(d));
            if (!inside(c)) { continue; }
            for (int e = 0; e < 6; e++) {
                Coords g = c.translated(BoardGeometry.edgeDirection(e)), next = c.translated(BoardGeometry.edgeDirection((e + 1) % 6));
                if (listed.add(edgeKey(c, g))) { add(candidates, c, e); }
                if ((inside(g) || inside(next)) && listed.add(edgeKey(g, next))) { add(candidates, g, edgeIndex(g, next)); }
            }
        }
        for (Segment s : candidates) {
            if (closer(s, ox, oy, reach)) { result.add(s); }
        }
        return List.copyOf(result);
    }

    /** Whether segment s comes within {@code reach} of the closed polyline (ox, oy). */
    private static boolean closer(Segment s, float[] ox, float[] oy, float reach) {
        float r2 = reach * reach;
        for (int i = 0; i + 1 < ox.length; i++) {
            Segment o = new Segment(ox[i], oy[i], ox[i + 1], oy[i + 1], 0, 0, false, -1, false);
            if (s.squared(ox[i], oy[i]) <= r2 || o.squared(s.ax(), s.ay()) <= r2 || o.squared(s.bx(), s.by()) <= r2) { return true; }
            // Crossing.
            float d1 = cross(s, ox[i], oy[i]), d2 = cross(s, ox[i + 1], oy[i + 1]);
            float d3 = cross(o, s.ax(), s.ay()), d4 = cross(o, s.bx(), s.by());
            if (d1 * d2 < 0 && d3 * d4 < 0) { return true; }
        }
        return false;
    }

    private static float cross(Segment s, float x, float y) {
        return (s.bx() - s.ax()) * (y - s.ay()) - (s.by() - s.ay()) * (x - s.ax());
    }

    private void add(List<Segment> into, Coords u, int e) {
        Coords v = u.translated(BoardGeometry.edgeDirection(e));
        int lu = level(u), lv = level(v);
        // Natural land keeps its level up to water today's code draws at that level: its bank comes up to the land there.
        if (lu == lv || levelShore(u, v)) { return; }
        int top = packed(lu > lv ? u : v);
        boolean cliff = cliff(u, v);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[][] d = edgeMove(u, e);
        if (!wall(u, v)) {
            // Its samples lie on the straight line between its moved corners: one segment.
            into.add(new Segment(ax + d[0][0], ay + d[0][1], bx + d[FULL][0], by + d[FULL][1], Math.min(lu, lv), Math.max(lu, lv),
                  cliff, top, joinedWater(u, v)));
            return;
        }
        for (int j = 0; j < FULL; j++) {
            float t0 = j / (float) FULL, t1 = (j + 1) / (float) FULL;
            into.add(new Segment(ax + (bx - ax) * t0 + d[j][0], ay + (by - ay) * t0 + d[j][1],
                  ax + (bx - ax) * t1 + d[j + 1][0], ay + (by - ay) * t1 + d[j + 1][1],
                  Math.min(lu, lv), Math.max(lu, lv), cliff, top, joinedWater(u, v)));
        }
    }

    /** The bank's profile at signed distance s from its outline (negative inside): today's slope transition. */
    private float bank(float s, float x, float y) {
        float u = Math.clamp(s / room, -1, 1);
        float k = .25f + .35f * BoardRelief.noise(x / shoulder + 11.7f, y / shoulder - 5.2f);
        float t = (1 - u) / 2;
        for (int i = 0; i < 4; i++) {
            float f = 1 - 2 * (t + k * (float) Math.sin(2 * Math.PI * t) / (float) (2 * Math.PI)) - u;
            float d = -2 - 2 * k * (float) Math.cos(2 * Math.PI * t);
            t = Math.clamp(t - f / d, 0, 1);
        }
        return t;
    }

    /**
     * Signed distance to the bank's smoothed outline for level k, kept off the hex centres near (x, y): {@code count}
     * floats of {@code centres}, each centre's distance and level; one at k or above stays inside, one below outside.
     * The point's centres, not only the asking hex's, so two hexes agree on the samples they share.
     */
    private float smoothDistance(Shares shares, int k, float x, float y, float[] centres, int count) {
        float[] o = new float[3];
        shares.sample(k, x, y, o);
        float v = o[0] - .5f, gx = o[1], gy = o[2];
        float g = (float) Math.hypot(gx, gy);
        float s = g < 1e-6f ? (v > 0 ? -1e6f : 1e6f) : -v / g;
        for (int i = 0; i < count; i += 2) {
            float guard = room - Math.max(0, centres[i] - keep);
            s = centres[i + 1] >= k ? Math.min(s, -guard) : Math.max(s, guard);
        }
        return s;
    }

    /** 0 up to {@link #FAR} / 2 from a step, 1 from {@link #FAR} on, smoothly between. */
    private float far(float d) {
        float t = Math.clamp((d - farReach / 2) / (farReach / 2), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * Hex h's own height at (x, y) and what its shading needs. out: z; role (0 top cover, 1 step face); rock (how much
     * of a cliff the nearest step is); distance to the nearest drop and rise; the nearest step's height in levels and
     * its lowest level.
     */
    void height(Coords h, List<Segment> segments, Shares shares, float x, float y, float[] out) {
        int own = level(h), lowest = own, highest = own;
        // A plate is level, and so is natural ground on a plate's fitted outline at its level (FieldPlates).
        if (plate(h) || onPlate(h, x, y)) {
            FieldPlates.level(own * levelZ, own, out);
            // Reserved ground on a plate is its road's surface, ramped where today's ramps.
            float road = roads.surface(h, x, y);
            if (!Float.isNaN(road)) { out[0] = road; }
            return;
        }
        // The centres a bank keeps off: h's and its neighbours' near the point, each with its level.
        float[] centres = new float[14];
        int count = 0;
        for (int e = -1; e < 6; e++) {
            Coords c = e < 0 ? h : h.translated(BoardGeometry.edgeDirection(e));
            if (!inside(c)) { continue; }
            float dx = x - centerX(c), dy = y - centerY(c), reach = keep + 2 * room;
            if (dx * dx + dy * dy >= reach * reach) { continue; }
            centres[count++] = (float) Math.sqrt(dx * dx + dy * dy);
            centres[count++] = level(c);
        }
        float[] d = new float[segments.size()];
        float rim = Float.POSITIVE_INFINITY, foot = Float.POSITIVE_INFINITY, near = Float.POSITIVE_INFINITY;
        int nearLevels = 0, nearLow = own, nearTop = -1;
        for (int i = 0; i < d.length; i++) {
            Segment s = segments.get(i);
            d[i] = s.squared(x, y);
            lowest = Math.min(lowest, s.low());
            highest = Math.max(highest, s.high());
            // A cliff's rim and foot are its row; a bank's are its band's edges (below).
            if (s.cliff() && s.high() == own) { rim = Math.min(rim, d[i]); }
            if (s.cliff() && s.low() == own) { foot = Math.min(foot, d[i]); }
            // Ties (steps meeting at a corner) take the taller step, whichever hex lists them first.
            if (d[i] < near - TIE) { near = d[i]; nearLevels = s.high() - s.low(); nearLow = s.low(); nearTop = s.top(); }
            else if (d[i] <= near + TIE) {
                near = Math.min(near, d[i]);
                if (s.high() - s.low() > nearLevels || s.high() - s.low() == nearLevels && s.low() < nearLow) {
                    nearLevels = s.high() - s.low();
                    nearLow = s.low();
                    nearTop = s.top();
                }
            }
        }
        rim = (float) Math.sqrt(rim);
        foot = (float) Math.sqrt(foot);
        float z = lowest * levelZ, role = 0, rock = 0, band = Float.POSITIVE_INFINITY;
        float bankRim = Float.POSITIVE_INFINITY, bankFoot = Float.POSITIVE_INFINITY;
        // Per level set k above the lowest: its nearest step, bank and cliff (with the cliff's drop), squared, from one
        // pass over the steps, each counting for the levels it spans, in the order they are listed.
        int span = highest - lowest;
        float[] nearestTo = new float[span], bankTo = new float[span], cliffTo = new float[span];
        int[] drops = new int[span];
        Arrays.fill(nearestTo, Float.POSITIVE_INFINITY);
        Arrays.fill(bankTo, Float.POSITIVE_INFINITY);
        float[] joinedTo = new float[span];
        Arrays.fill(joinedTo, Float.POSITIVE_INFINITY);
        Arrays.fill(cliffTo, Float.POSITIVE_INFINITY);
        Arrays.fill(drops, 3);
        for (int i = 0; i < d.length; i++) {
            Segment s = segments.get(i);
            for (int j = s.low() - lowest; j < s.high() - lowest; j++) {
                nearestTo[j] = Math.min(nearestTo[j], d[i]);
                if (!s.cliff()) {
                    bankTo[j] = Math.min(bankTo[j], d[i]);
                    if (s.joined()) { joinedTo[j] = Math.min(joinedTo[j], d[i]); }
                }
                else if (d[i] < cliffTo[j] - TIE) { cliffTo[j] = d[i]; drops[j] = Math.max(3, s.high() - s.low()); }
                else if (d[i] <= cliffTo[j] + TIE) { cliffTo[j] = Math.min(cliffTo[j], d[i]); drops[j] = Math.max(drops[j], s.high() - s.low()); }
            }
        }
        float wall = 0, sill = 0, shelf = 0, run = shore.run();
        for (int k = lowest + 1; k <= highest; k++) {
            float nearest = nearestTo[k - lowest - 1], bankD = bankTo[k - lowest - 1], cliffD = cliffTo[k - lowest - 1];
            // The nearest bank a step between joined waters: its foot keeps no shelf (the bed is today's, FieldShore).
            boolean joinedBank = joinedTo[k - lowest - 1] <= bankD + TIE;
            int drop = drops[k - lowest - 1];
            boolean in = own >= k;
            if (nearest == Float.POSITIVE_INFINITY) { z += in ? levelZ : 0; continue; }
            nearest = (float) Math.sqrt(nearest);
            if (nearest > farReach) {
                // Beyond FAR the level is whole or absent, as both hexes' lists agree there.
                z += in ? levelZ : 0;
                if (in) { bankRim = Math.min(bankRim, nearest - .95f * room); }
                else { bankFoot = Math.min(bankFoot, nearest - .95f * room); }
                continue;
            }
            bankD = (float) Math.sqrt(bankD);
            cliffD = (float) Math.sqrt(cliffD);
            float s = in ? -nearest : nearest;
            // A cliff's weight against a bank of the same level set; steps further than FAR from the point have none,
            // so two hexes' segment lists, which differ only beyond it, give their shared samples one height.
            float base = bankD == Float.POSITIVE_INFINITY ? 1 : Math.min(1, bankD / taper) * bankD / (bankD + cliffD + 1e-6f);
            float weight = cliffD == Float.POSITIVE_INFINITY ? 0 : (base + (1 - base) * far(bankD)) * (1 - far(cliffD));
            float share = 0, footShelf = 0;
            boolean step = false;
            if (weight > 0) {
                float a = Math.min(.3f, talusReach / (drop * levelZ))
                      * (.7f + .6f * BoardRelief.noise(x / talusNoise - 3.3f, y / talusNoise + 6.6f));
                // Today's apron (BoardRelief#transition): twice the room wide, steepest under the face.
                float talus = in || s >= 2 * room ? 0 : a * (1 - (float) Math.sqrt(s / (2 * room)));
                share = weight * (in ? 1 : talus);
                step = !in && s < 2 * room;
            }
            // Shore and beds: where its foot stands in water a cliff carries no talus and its wall runs down to the
            // bed; a pool above a cliff keeps a sill along the rim (FieldShore#bed).
            if (weight > 0 && in) { sill = Math.max(sill, weight * Math.max(0, 1 + s / room)); }
            // At the foot of a higher step the bed keeps no depth and falls from there over a bank's width, as
            // today's basin (a bank keeps no depth): the step's face ends at the water (FieldShore#bed's sill). On
            // the step's upper side it keeps none at the rim either, so the hexes on both sides give its outline one
            // height, and gains its depth over the room inward, as the sill does.
            if (weight > 0) {
                float wet = shore.wet(x, y, k);
                // A cliff's foot is its talus apron's, twice the room out from its row.
                footShelf = weight * (1 - wet) * (in ? Math.max(0, 1 + s / room)
                      : 1 - BoardRelief.smooth(Math.max(0, s - 2 * room) / run));
                if (!in) {
                    share *= 1 - wet;
                    wall = Math.max(wall, wet * weight);
                }
            }
            if (weight < 1) {
                float sb = s + bankMix * (smoothDistance(shares, k, x, y, centres, count) - s);
                share += (1 - weight) * bank(sb, x, y);
                // Faded out as the step's weight is (far), so two hexes' step lists give a shared sample one height.
                footShelf += (joinedBank ? 0 : 1) * (1 - weight) * (1 - far(bankD))
                      * (in ? 1 - BoardRelief.smooth(Math.max(0, -sb - .95f * room) / room)
                            : 1 - BoardRelief.smooth(Math.max(0, sb - .95f * room) / run));
                // A bank's face spans its band, as today's does; the top cover's rim and foot distances run from its edges.
                step |= Math.abs(sb) < .95f * room;
                if (in) { bankRim = Math.min(bankRim, Math.max(0, -sb - .95f * room)); }
                else { bankFoot = Math.min(bankFoot, Math.max(0, sb - .95f * room)); }
            }
            // Below a step whose higher side is water the water pours in: a fall's foot keeps no shelf.
            shelf = Math.max(shelf, footShelf * (footShelf > 0 ? 1 - shore.upper(x, y, k) : 1));
            if (step) {
                role = 1;
                if (Math.abs(s) < band) { band = Math.abs(s); rock = weight; }
            }
            z += share * levelZ;
        }
        // Layer 2, shore and beds (FieldShore), before the roads; ground the shore lowers is bank and bed, no step's face.
        float bed = shore.bed(x, y, wall, Math.max(sill, shelf));
        // Land keeps above the water where no water plane reaches over it (WaterLayer#dip): no dry hollow below the
        // line. The hex at the point decides, so a point every hex shares gets one height.
        Coords at = bed > 0 ? GpuStarShells.hexAt(x, y) : null;
        if (at != null && inside(at) && !WaterLayer.water(this, at)) { bed = Math.min(bed, WaterLayer.dip(this, at, x, y, z)); }
        z -= bed;
        if (bed > 0) { role = 0; }
        // Layer 3, the scree a ground tunnel's mouth is set in (FieldStamps#scree), before the roads: talus, shaded
        // as a cliff's apron is, at its foot (the ground material's debris).
        float scree = FieldStamps.scree(mouths(h), x, y);
        boolean talus = scree > z;
        if (talus) {
            z = scree;
            role = 1;
            rock = 1;
        }
        // Between a mouth's wings and in front of its face the ground stays at the opening's floor.
        float approach = FieldStamps.approach(mouths(h), packed(h), x, y);
        if (approach < z) {
            z = approach;
            role = 0;
            rock = 0;
            talus = false;
        }
        // Inside today's waterline no ground stands above the water, whichever operator raised it (a talus apron, a
        // bank, the shelf at a step's foot, scree): at most the water's surface less the shallow margin's depth there.
        // Today's water is read at the point, as the hex there has it. Where this hex's ground reaches past its outline
        // because a wall's row moved off the edge (#edgeMove), the point lies in front of (or behind) that hex's wall:
        // where the neighbour of that hex nearest the point (by centre: the hex the point would lie in without it)
        // stands across a wall from it, the reads take that neighbour's water at its edge's nearest point, so the step
        // stands at the row; the test and the read are the point's own, so hexes sharing a point read one water.
        float wx = x, wy = y;
        Coords here = GpuStarShells.hexAt(x, y);
        int edge = -1;
        if (!here.equals(h)) {
            float best = Float.POSITIVE_INFINITY;
            for (int e = 0; e < 6; e++) {
                Coords n = here.translated(BoardGeometry.edgeDirection(e));
                float ndx = x - centerX(n), ndy = y - centerY(n);
                if (ndx * ndx + ndy * ndy < best) {
                    best = ndx * ndx + ndy * ndy;
                    edge = e;
                }
            }
            if (!cliff(here, here.translated(BoardGeometry.edgeDirection(edge)))) { edge = -1; }
        }
        if (edge >= 0) {
            Coords n = here.translated(BoardGeometry.edgeDirection(edge));
            float c0x = cornerX(here, edge), c0y = cornerY(here, edge), edx = cornerX(here, edge + 1) - c0x, edy = cornerY(here, edge + 1) - c0y;
            float along = Math.clamp(((x - c0x) * edx + (y - c0y) * edy) / (edx * edx + edy * edy), 0, 1);
            float fx = c0x + along * edx, fy = c0y + along * edy, toX = centerX(n) - fx, toY = centerY(n) - fy;
            float nudge = BoardRelief.metres(.01f) / Math.max((float) Math.hypot(toX, toY), 1e-6f);
            wx = fx + toX * nudge;
            wy = fy + toY * nudge;
        }
        float wet = bed > 0 || shelf > 0 || talus ? shore.distance(wx, wy) : 0;
        if (wet > 0) {
            // A descending stream's surface caps it, but at a cliff's outline its level does (a wall's rows hang from that rim).
            float cliffNear = wallDistance(x, y);
            float surface = BoardRelief.lerp(shore.surface(wx, wy, false), shore.surface(wx, wy), BoardRelief.smooth(cliffNear / room));
            float top = surface - shore.shallow(wx, wy, wet);
            if (z > top) {
                z = top;
                role = 0;
            }
            // Shallow (depth 0) water's bed is today's own, bars and all (FieldShore#shallows); at a cliff's outline the
            // field's own height holds (a wall's rows hang from it), and today's comes in over the step's room.
            z = BoardRelief.lerp(z, shore.shallows(wx, wy, z), BoardRelief.smooth(cliffNear / room));
            // Beside water the operator does not shape at this surface (beside paving) the bed meets today's there.
            float seam = shore.seam(wx, wy, z);
            if (seam != z) {
                z = seam;
                role = 0;
            }
        }
        out[0] = roads.height(h, x, y, z);
        // Reserved ground is its road's surface: a top, as today's is (BoardRoadCut#mesh).
        boolean road = roads.reserved(h, x, y);
        out[1] = road ? 0 : role;
        out[2] = road ? 0 : rock;
        out[3] = Math.min(rim, bankRim);
        out[4] = talus ? 0 : Math.min(foot, bankFoot);
        out[5] = nearLevels;
        out[6] = nearLow;
        // The nearest step's upper hex, where the caller asks (an eighth slot): the hex a talus there fell from.
        if (out.length > 7) { out[7] = nearTop; }
    }
}
