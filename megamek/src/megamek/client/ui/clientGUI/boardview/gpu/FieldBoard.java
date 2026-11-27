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
    /** How far above its foot a wall's rock relief eases in, metres (ChunkField.Wall). */
    static final float FOOT_EASE = 1.5f;
    /**
     * How far a cliff's foot stands out from its rim's row, in rooms: today's cliff takes .6 of the room to each side
     * of its edge (BoardRelief#room), its talus between the two (BoardRelief#transition).
     */
    private static final float TALUS_RUN = 1.2f;
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
    /** Per hex, the sides a standing natural span in it lands across by a ramp (BoardNaturalBridge#rampLanding). */
    private final int[][] landings;
    /** Per water hex that falls, the stretch of each fall edge that pours (BoardSurface#pourEdges); null for others. */
    private final float[][][][] pours;
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
    private final float probe, limit, settled, shoulder;
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
    /** Dry ground that keeps its outline (BoardRelief#keepsOutline): natural land above it banks on its own side. */
    private static final int KEPT = 1 << 31;
    /**
     * walls' bit LEVEL_SHORE + e: edge e parts natural land from water the shore does not shape at the land's level, or
     * water the shore shapes from water it does not shape at the same surface (FieldShore#seamed): no step (#add).
     */
    private static final int LEVEL_SHORE = 7;
    /** walls' bit JOINED + e: edge e joins two waters the shore holds whose beds meet under them (#hex, #add). */
    private static final int JOINED = 13;
    /** Bits 19-24, edge e pours over a fall into its neighbour (FieldWater#fallsInto). */
    private static final int FALL = 19;

    /** Bits 25 to 30: a stream hex's step, whose bank is the lower hex's slope ({@link #stream}). */
    private static final int STREAM = 25;
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
        boolean incremental = previous != null && changed != null && previous.cols == cols && previous.rows == rows
              && previous.outline == outline && previous.width == width && previous.levelZ == levelZ;
        if (!incremental) {
            level = new int[cols][rows];
            family = new int[cols][rows];
            walls = new int[cols][rows];
            landings = new int[cols][rows];
            pours = new float[cols][rows][][];
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
            landings = previous.landings.clone();
            pours = previous.pours.clone();
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
                landings[x] = landings[x].clone();
                pours[x] = pours[x].clone();
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
        // Not natural ground the field shapes itself (a road's hex banks by FieldRoads): plates, paving it leaves.
        if (!liquid && !dryNatural(t) && BoardRelief.keepsOutline(scene, t)) { walls[x][y] |= KEPT; }
        landings[x][y] = 0;
        for (int dir = 0; dir < 6; dir++) {
            if (BoardNaturalBridge.rampLanding(scene, t, dir)) { landings[x][y] |= 1 << dir; }
        }
        // A water hex that falls: the stretch each fall edge pours over, water's rule (#dry).
        pours[x][y] = null;
        for (int e = 0; liquid && pours[x][y] == null && e < 6; e++) {
            BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
            if (next != null && FieldWater.fallsInto(t, next, BoardGeometry.edgeDirection(e))) { pours[x][y] = BoardSurface.pourEdges(scene, t); }
        }
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
            // Two waters the shore holds whose beds meet under them: at one surface, or a stream running down between
            // them (BoardSurface#waterSlope: joined, at most two levels apart), whose bed is today's (FieldShore#shallows).
            boolean joined = meet(t, next);
            // A wall, as BoardRelief draws one: a drop of more than two and a half levels for its landforms.
            if (next != null && BoardRelief.levels(t, next) > 2.5f && !joined) { walls[x][y] |= 1 << e; }
            // A pool above lower ground or water ends in a rock step (falls, raised water: b and c).
            if (next != null && bed >= 0 && next.groundLevel() < t.groundLevel() && !joined) { walls[x][y] |= 1 << e; }
            if (next != null && (levelShore(t, next) || shore.seamed(at, next))) {
                walls[x][y] |= 1 << (LEVEL_SHORE + e);
            }
            if (joined) { walls[x][y] |= 1 << (JOINED + e); }
            if (FieldWater.fallsInto(t, next, BoardGeometry.edgeDirection(e))) { walls[x][y] |= 1 << (FALL + e); }
            // A water hex with no fall, over a lower hex it is not joined to: its bank is the lower hex's slope (#stream).
            if (bed >= 0 && !joined && streamHex(scene, t) && streamStep(t, next, BoardGeometry.edgeDirection(e))) {
                walls[x][y] |= 1 << (STREAM + e);
            }
        }
        // A plate stands on its fitted outline: a change of level at its edges is a wall, and so is its edge to water,
        // a quay (FieldPlates); where today's step is a straight concrete slope it is that slope (#pavedSlope).
        if (taken[x][y] && !dryNatural(t) && bed < 0) {
            walls[x][y] |= PLATE;
            for (int e = 0; e < 6; e++) {
                Coords n = at.translated(BoardGeometry.edgeDirection(e));
                BoardScene.Tile next = scene.tile(n);
                if (next == null) { continue; }
                int nextLevel = next.groundLevel() - (next.liquid().present() && shore.family(n) < 0 ? Math.max(0, next.waterDepth()) : 0);
                // Dry natural ground a bank's drop above the plate banks down onto it on its own side (#banksOnto).
                if ((nextLevel != level[x][y] || next.liquid().present()) && !banksOnto(scene, next, t)
                      && !pavedSlope(scene, t, next)) {
                    walls[x][y] |= 1 << e;
                }
            }
        } else {
            // A step between two other hexes that ends at a plate's corner is a wall too: a bank there would have to
            // meet the plate's level and the lower hex's at one point. Not where the plate slopes down to the lower of
            // them and stands level with the other (or slopes down to both): its slope meets the bank at the corner.
            for (int e = 0; e < 6; e++) {
                BoardScene.Tile next = scene.tile(at.translated(BoardGeometry.edgeDirection(e)));
                if (next == null || (walls[x][y] & 1 << e) != 0) { continue; }
                Coords n = at.translated(BoardGeometry.edgeDirection(e));
                int nextLevel = next.groundLevel() - (next.liquid().present() && shore.family(n) < 0 ? Math.max(0, next.waterDepth()) : 0);
                if (nextLevel == level[x][y]) { continue; }
                // A bank onto ground that keeps its outline lies whole on this side: it meets that ground's level all
                // along the edge, its corners too (#banksOnto).
                if (banksOnto(scene, t, next)) { continue; }
                for (int side : new int[] { (e + 5) % 6, (e + 1) % 6 }) {
                    BoardScene.Tile third = scene.tile(at.translated(BoardGeometry.edgeDirection(side)));
                    if (third != null && FieldPlates.takes(scene, third)
                          && !(open(scene, third, t) && open(scene, third, next))) {
                        walls[x][y] |= 1 << e;
                    }
                }
            }
        }
    }

    /**
     * Whether the step between two hexes, the upper a plate, is today's straight concrete slope down to dry ground the
     * field takes (BoardRelief#pavedSlope: BoardRelief#band's for a paved upper hex, where the step has room).
     */
    private static boolean pavedSlope(BoardScene scene, BoardScene.Tile a, BoardScene.Tile b) {
        BoardScene.Tile upper = a.groundLevel() > b.groundLevel() ? a : b, lower = upper == a ? b : a;
        return FieldPlates.takes(scene, upper) && (dryNatural(lower) || FieldPlates.takes(scene, lower))
              && BoardRelief.pavedSlope(scene, upper, lower);
    }

    /** Whether a plate's edge to its neighbour has no wall: dry ground at its level, or its slope (#pavedSlope). */
    private static boolean open(BoardScene scene, BoardScene.Tile plate, BoardScene.Tile next) {
        return !next.liquid().present() && next.groundLevel() == plate.groundLevel() || pavedSlope(scene, plate, next);
    }

    /** Whether the step between u and v is a plate's slope: no wall (#hex), the upper hex a plate. */
    private boolean slopes(Coords u, Coords v) {
        return inside(u) && inside(v) && level(u) != level(v) && !cliff(u, v) && plate(level(u) > level(v) ? u : v);
    }

    /** Whether a step is a plate's slope (#slopes): a bank whose upper hex is a plate. */
    private boolean paved(Segment s) {
        int x = s.top() / 4096, y = s.top() % 4096;
        return !s.cliff() && s.top() >= 0 && x < walls.length && y < walls[x].length && (walls[x][y] & PLATE) != 0;
    }

    /**
     * The line a plate's slope between u and v runs on: the edge between its corners where the pavement's fit puts
     * them, the upper hex on its left: {x0, y0, x1, y1}. The slope stands a room to each side of it (#height).
     */
    private float[] slopeLine(Coords u, Coords v) {
        int e = edgeIndex(u, v);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[] fa = FieldPlates.corner(concrete, u, e, ax, ay);
        float[] fb = FieldPlates.corner(concrete, u, (e + 1) % 6, bx, by);
        float x0 = ax + fa[0], y0 = ay + fa[1], x1 = bx + fb[0], y1 = by + fb[1];
        Coords upper = level(u) > level(v) ? u : v;
        return (x1 - x0) * (centerY(upper) - y0) - (y1 - y0) * (centerX(upper) - x0) > 0
              ? new float[] { x0, y0, x1, y1 } : new float[] { x1, y1, x0, y0 };
    }

    /**
     * How far the corner of hexes t stands off its fitted place for the plates' slopes that meet at it (#slopes): where
     * two do, on both slopes' feet, a room out from each line (a mitre; straight on where the two lines are one);
     * otherwise where it is (a slope's foot that ends there comes back to it over half the edge, #canonicalEdge).
     */
    private float[] footCorner(Coords[] t) {
        float[][] out = new float[2][];
        int count = 0;
        for (int[] p : new int[][] { { 0, 1 }, { 0, 2 }, { 1, 2 } }) {
            if (!slopes(t[p[0]], t[p[1]])) { continue; }
            if (count == 2) { return new float[] { 0, 0 }; }
            float[] line = slopeLine(t[p[0]], t[p[1]]);
            float length = (float) Math.hypot(line[2] - line[0], line[3] - line[1]);
            // Out of the plate: to the line's right.
            out[count++] = new float[] { (line[3] - line[1]) / length, -(line[2] - line[0]) / length };
        }
        // No mitre past a right angle between the two lines (the pavement's fit can fold an outline back on itself):
        // the corner keeps its place and the feet come back to it.
        float spread = count < 2 ? 0 : 1 + out[0][0] * out[1][0] + out[0][1] * out[1][1];
        return spread < 1 ? new float[] { 0, 0 }
              : new float[] { room * (out[0][0] + out[1][0]) / spread, room * (out[0][1] + out[1][1]) / spread };
    }

    /** Whether a plate's slope stands at (x, y): within the step's room of its line (FieldRoads#walls asks). */
    private boolean slopeAt(float x, float y) {
        Coords here = hexAt(x, y);
        if (!inside(here)) { return false; }
        for (Segment s : segments(here)) {
            if (paved(s) && s.squared(x, y) < room * room) { return true; }
        }
        return false;
    }

    /** Whether (x, y) lies within a plate's slope's reach: twice the step's room (its foot, a mitred corner's too). */
    private boolean pavedNear(List<Segment> segments, float x, float y) {
        for (Segment s : segments) {
            if (paved(s) && s.squared(x, y) < 4 * room * room) { return true; }
        }
        return false;
    }

    /**
     * The lower hex whose ground (x, y) is, for hex h at a plate's level: where the point lies past the line of a slope
     * that comes down from that level, out of the plate (the plate's block reaches to the slope's foot,
     * #canonicalEdge), its foot on the line within the slope or at a mitre of two such slopes. The point's place alone
     * decides, a centimetre clear of the line and of the slope's ends, so the hexes at that level sharing a sample give
     * it one answer; a slope that ends alone leaves the ground beyond its end to the hexes there. Null elsewhere.
     */
    private Coords pavedBelow(Coords h, List<Segment> segments, float x, float y) {
        float clear = .01f * metre, best = 4 * room * room;
        Segment line = null;
        for (Segment s : segments) {
            float d = s.high() == level(h) && paved(s) && s.right(x, y) > clear ? s.squared(x, y) : best;
            if (d < best) {
                best = d;
                line = s;
            }
        }
        if (line == null) { return null; }
        float length = (float) Math.hypot(line.bx() - line.ax(), line.by() - line.ay());
        float along = line.along(x, y) * length;
        boolean within = along > clear && along < length - clear;
        for (Segment s : segments) {
            within |= s != line && s.high() == level(h) && paved(s) && s.squared(x, y) <= 1.01f * best + 1e-2f
                  && s.right(x, y) > clear;
        }
        Coords top = within ? unpacked(line.top()) : null, below = null;
        float near = Float.POSITIVE_INFINITY;
        for (int e = 0; top != null && e < 6; e++) {
            Coords g = top.translated(BoardGeometry.edgeDirection(e));
            float dx = x - centerX(g), dy = y - centerY(g);
            if (dx * dx + dy * dy < near && inside(g) && level(g) < level(top) && slopes(top, g)) {
                near = dx * dx + dy * dy;
                below = g;
            }
        }
        return below;
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

    /**
     * Whether dry natural ground {@code upper} banks down onto {@code lower} on its own side: a step no wall retains
     * by today's rule (BoardRelief#levels, two and a half levels) onto dry ground that keeps its outline, as today's
     * land above paving takes its step (BoardRelief#keepsOutline, #band).
     */
    private static boolean banksOnto(BoardScene scene, BoardScene.Tile upper, BoardScene.Tile lower) {
        return upper != null && lower != null && dryNatural(upper) && !dryNatural(lower) && !lower.liquid().present()
              && upper.groundLevel() > lower.groundLevel() && BoardRelief.levels(upper, lower) <= 2.5f
              && BoardRelief.keepsOutline(scene, lower);
    }

    /** Whether the step between u and v is a bank from natural ground onto ground that keeps its outline. */
    private boolean plated(Coords u, Coords v, boolean cliff) {
        Coords high = level(u) > level(v) ? u : v, low = high == u ? v : u;
        return !cliff && inside(low) && (walls[low.getX()][low.getY()] & KEPT) != 0 && taken(high) && !plate(high)
              && shore.family(high) < 0;
    }

    /** The hex {@link #packed} gave, or null for -1. */
    static Coords unpacked(int packed) { return packed < 0 ? null : new Coords(packed / 4096, packed % 4096); }

    record Segment(float ax, float ay, float bx, float by, int low, int high, boolean cliff, int top, boolean joined,
          boolean plated, Segment rim) {
        /** A step whose rim stands on its own line ({@code rim} null); a wall's can stand off it (#canonicalEdge). */
        Segment(float ax, float ay, float bx, float by, int low, int high, boolean cliff, int top, boolean joined,
              boolean plated) {
            this(ax, ay, bx, by, low, high, cliff, top, joined, plated, null);
        }

        float distance(float x, float y) { return (float) Math.sqrt(squared(x, y)); }

        float squared(float x, float y) {
            float dx = bx - ax, dy = by - ay;
            float t = Math.clamp(((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy), 0, 1);
            float px = ax + t * dx - x, py = ay + t * dy - y;
            return px * px + py * py;
        }

        /** Where along the step's line the foot of (x, y) on it lies: 0 at a, 1 at b, past them too. */
        float along(float x, float y) {
            float dx = bx - ax, dy = by - ay;
            return ((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy);
        }

        /** How far (x, y) lies to the right of the step's line from a to b (negative: to its left). */
        float right(float x, float y) {
            float dx = bx - ax, dy = by - ay;
            return ((x - ax) * dy - (y - ay) * dx) / (float) Math.sqrt(dx * dx + dy * dy);
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

    /**
     * Hex h's ground z at (x, y) under its falls' crests: along each edge h falls over, from hex corner to hex corner
     * (an end whose next edge is no fall eased over its last tenth), the ground within 4 m behind the edge (eased out
     * to 6 m) lies at least 0.3 m under h's water: today's curtain and its level lip hang from the straight hex edge,
     * and no bank, apron or rim tongue stands into them. Where the edge does not pour (#dry) a rim of rock stands
     * instead: 0.5 m over the water out to 1.9 m behind the edge, then falling a metre a metre; {@code out[1]} is
     * set to 1 where (x, y) is that rim's rock over the water (#height draws it as the hex's rock), else left.
     */
    private float crestUnder(Coords h, float x, float y, float z, float[] out) {
        for (int e = 0; e < 6; e++) {
            if (!falls(h, h.translated(BoardGeometry.edgeDirection(e)))) { continue; }
            float ax = cornerX(h, e), ay = cornerY(h, e), ex = cornerX(h, e + 1) - ax, ey = cornerY(h, e + 1) - ay;
            float len = (float) Math.hypot(ex, ey), nx = -ey / len, ny = ex / len;
            if ((centerX(h) - ax) * nx + (centerY(h) - ay) * ny < 0) { nx = -nx; ny = -ny; }
            float behind = (x - ax) * nx + (y - ay) * ny, t = ((x - ax) * ex + (y - ay) * ey) / (len * len);
            if (behind > 6 * metre || t < 0 || t > 1) { continue; }
            boolean before = falls(h, h.translated(BoardGeometry.edgeDirection(Math.floorMod(e - 1, 6))));
            boolean after = falls(h, h.translated(BoardGeometry.edgeDirection((e + 1) % 6)));
            float ends = (before ? 1 : BoardRelief.smooth(t / .1f)) * (after ? 1 : BoardRelief.smooth((1 - t) / .1f));
            float water = shore.water(h, x, y), cap = water - .3f * metre;
            if (z > cap) { z -= (z - cap) * ends * (1 - BoardRelief.smooth((behind - 4 * metre) / (2 * metre))); }
            // Where the edge does not pour, a dry rim of rock: a level top 0.5 m over the water one lattice step
            // wide, its inner face falling a metre a metre (the pool's sheet ends inside it: FieldWater). It eases out
            // over the edge's last tenth toward an end whose next edge is neither a fall nor a pool's wall (#poolRim),
            // so it meets the ground of the hex beside it there, which has no rim.
            float dry = dry(h, e, t)
                  * (before || poolWall(h, Math.floorMod(e - 1, 6)) ? 1 : BoardRelief.smooth(t / .1f))
                  * (after || poolWall(h, (e + 1) % 6) ? 1 : BoardRelief.smooth((1 - t) / .1f));
            if (dry > 0) {
                z += Math.max(0, water + .5f * metre - Math.max(0, behind - 1.9f * metre) - z) * dry;
                if (dry > .5f && z >= water - .1f * metre) { out[1] = 1; }
            }
        }
        return z;
    }

    /**
     * Hex h's ground z at (x, y) behind a pool's wall that is no fall ({@link #poolWall}): #crestUnder's dry rim
     * measured from the wall's moved rim (#edgeMove), a level top 0.5 m over the pool's water out to the pool's own
     * shore line (FieldShore#waterline; at least 1 m and at most 6 m behind the rim), then falling a metre a metre,
     * eased out over the edge's last tenth toward an end whose next edge is neither such
     * a wall nor a fall. It only raises, on the pool's side of the rim, the pool's own ground or ground no wall parts
     * from it, so samples shared across a bank get one height; {@code out[1]} is set to 1 where it raises the pool's
     * own ground (#height draws it as the pool's rock).
     */
    private float poolRim(Coords h, float x, float y, float z, float[] out) {
        Coords here = hexAt(x, y);
        float[] line = { Float.NaN, 0, 0, 0, 0, 0 };
        for (int n = -1; n < 6 && inside(here); n++) {
            Coords p = n < 0 ? here : here.translated(BoardGeometry.edgeDirection(n));
            if (!inside(p) || !WaterLayer.water(this, p) || !p.equals(h) && cliff(h, p)) { continue; }
            for (int e = 0; e < 6; e++) {
                if (!poolWall(p, e)) { continue; }
                float ax = cornerX(p, e), ay = cornerY(p, e), ex = cornerX(p, e + 1) - ax, ey = cornerY(p, e + 1) - ay;
                float len = (float) Math.hypot(ex, ey), nx = -ey / len, ny = ex / len;
                if ((centerX(p) - ax) * nx + (centerY(p) - ay) * ny < 0) { nx = -nx; ny = -ny; }
                float t = ((x - ax) * ex + (y - ay) * ey) / (len * len);
                if (t < 0 || t > 1 || (x - ax) * nx + (y - ay) * ny > 9 * metre) { continue; }
                // The nearest point of the moved rim, and which side of it the point lies.
                float[][] m = edgeMove(p, e);
                float best = Float.POSITIVE_INFINITY, side = 0;
                for (int j = 0; j < FULL; j++) {
                    float px0 = ax + ex * j / FULL + m[j][0], py0 = ay + ey * j / FULL + m[j][1];
                    float sx = ex / FULL + m[j + 1][0] - m[j][0], sy = ey / FULL + m[j + 1][1] - m[j][1], s2 = sx * sx + sy * sy;
                    float u = s2 < 1e-12f ? 0 : Math.clamp(((x - px0) * sx + (y - py0) * sy) / s2, 0, 1);
                    float qx = px0 + sx * u, qy = py0 + sy * u, d2 = (x - qx) * (x - qx) + (y - qy) * (y - qy);
                    if (d2 < best) { best = d2; side = (x - qx) * nx + (y - qy) * ny; }
                }
                float behind = (float) Math.sqrt(best);
                if (side < -.01f * metre || behind > 6.6f * metre) { continue; }
                Coords before = p.translated(BoardGeometry.edgeDirection(Math.floorMod(e - 1, 6)));
                Coords after = p.translated(BoardGeometry.edgeDirection((e + 1) % 6));
                float ends = (poolWall(p, Math.floorMod(e - 1, 6)) || falls(p, before) ? 1 : BoardRelief.smooth(t / .1f))
                      * (poolWall(p, (e + 1) % 6) || falls(p, after) ? 1 : BoardRelief.smooth((1 - t) / .1f));
                // The rim's top runs out to the pool's own shore line (FieldShore#waterline), at least 1 m and at
                // most 6 m behind the moved rim, its inner face falling a metre a metre into the water there.
                if (Float.isNaN(line[0])) { line[0] = shore.waterline(x, y, line) ? line[0] : Float.POSITIVE_INFINITY; }
                float past = Math.max(Math.min(line[0], behind - metre), behind - 6 * metre);
                // Where it meets a fall edge's dry rim (#crestUnder) the two inner faces join in a rounded inner
                // corner, not a sharp one: a smooth minimum of their distances past the tops' ends, over 1.5 m.
                for (int f = -1; f <= 1; f += 2) {
                    int fe = Math.floorMod(e + f, 6);
                    if (!falls(p, p.translated(BoardGeometry.edgeDirection(fe)))) { continue; }
                    float fx = cornerX(p, fe), fy = cornerY(p, fe), gx = cornerX(p, fe + 1) - fx, gy = cornerY(p, fe + 1) - fy;
                    float gl = (float) Math.hypot(gx, gy), mx = -gy / gl, my = gx / gl;
                    if ((centerX(p) - fx) * mx + (centerY(p) - fy) * my < 0) { mx = -mx; my = -my; }
                    float ft = Math.clamp(((x - fx) * gx + (y - fy) * gy) / (gl * gl), 0, 1);
                    float fb = (x - fx) * mx + (y - fy) * my;
                    if (fb < 0 || dry(p, fe, ft) < .999f) { continue; }
                    float pf = fb - 1.9f * metre, k = 1.5f * metre, round = Math.max(k - Math.abs(past - pf), 0) / k;
                    past = Math.min(past, pf) - round * round * k / 4;
                }
                float water = shore.water(p, x, y), target = water + .5f * metre - Math.max(0, past);
                if (z < target) {
                    z += (target - z) * ends;
                    if (p.equals(h) && ends > .5f && z >= water - .1f * metre) { out[1] = 1; }
                }
            }
        }
        return z;
    }

    /** Whether pool p's edge e is a wall that is no fall: over a lower neighbour that is land, a cliff, no stream step. */
    private boolean poolWall(Coords p, int e) {
        Coords n = p.translated(BoardGeometry.edgeDirection(e));
        return inside(n) && level(p) > level(n) && cliff(p, n) && !falls(p, n) && !stream(p, n) && !WaterLayer.water(this, n);
    }

    /**
     * How far fall edge e of hex h is dry at share t of its length, 0 to 1: outside the stretch water's rule lets
     * pour (BoardSurface#pourEdges: {from, to} along the straight edge; null, nothing pours), eased out over half a
     * metre inside it; a stretch reaching a corner (a run turning round it) leaves no rim there.
     */
    private float dry(Coords h, int e, float t) {
        float[][] pour = pours[h.getX()][h.getY()];
        if (pour == null) { return 0; }
        float[] s = pour[e];
        if (s == null) { return 1; }
        if (s[0] <= .001f && s[1] >= .999f) { return 0; }
        float along = t * edge, from = s[0] <= .001f ? Float.NEGATIVE_INFINITY : s[0] * edge;
        float to = s[1] >= .999f ? Float.POSITIVE_INFINITY : s[1] * edge;
        return 1 - BoardRelief.smooth(Math.min(along - from, to - along) / (.5f * metre));
    }

    /** A water hex that pours over no fall: one with a fall keeps the falls' form on every edge. */
    static boolean streamHex(BoardScene scene, BoardScene.Tile t) {
        if (t == null || !t.liquid().present()) { return false; }
        for (int e = 0; e < 6; e++) {
            int direction = BoardGeometry.edgeDirection(e);
            if (FieldWater.fallsInto(t, scene.tile(t.coords().translated(direction)), direction)) { return false; }
        }
        return true;
    }

    /** Whether water hex t stands over its lower neighbour next on a step of its own: no joined slope, no fall. */
    static boolean streamStep(BoardScene.Tile t, BoardScene.Tile next, int direction) {
        return t != null && next != null && t.liquid().present() && next.groundLevel() < t.groundLevel()
              && !(next.liquid().present() && BoardSurface.waterSlope(t, next)) && !FieldWater.fallsInto(t, next, direction);
    }

    /**
     * Whether the step from u down to v is a stream hex's own: a water hex the shore holds, with no fall, over a lower
     * hex it is not joined to. It has no wall: the lower hex's ground rises to the rim as the bank's slope
     * ({@link #bankSlope}), and the field moves no rim sample there.
     */
    boolean stream(Coords u, Coords v) {
        return inside(u) && inside(v) && (walls[u.getX()][u.getY()] & 1 << (STREAM + edgeIndex(u, v))) != 0;
    }

    /**
     * The stream hexes with a step ({@link #stream}) among the hex at (x, y) and its neighbours, which hold every such
     * step within a hex side of the point whichever hex asks: per stream hex the distance of its nearest step in
     * world units and the hex ({@link #packed}), the nearest last; null where there is none.
     */
    private float[] streamsNear(Coords here, float x, float y) {
        float[] found = null;
        int count = 0;
        for (int n = -1; n < 6; n++) {
            Coords c = n < 0 ? here : here.translated(BoardGeometry.edgeDirection(n));
            if (!inside(c)) { continue; }
            int steps = walls[c.getX()][c.getY()] >>> STREAM & 63;
            if (steps == 0) { continue; }
            float best = Float.POSITIVE_INFINITY;
            for (int e = 0; e < 6; e++) {
                if ((steps & 1 << e) == 0) { continue; }
                float ax = cornerX(c, e), ay = cornerY(c, e), ex = cornerX(c, e + 1) - ax, ey = cornerY(c, e + 1) - ay;
                float t = Math.clamp(((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey), 0, 1);
                best = Math.min(best, (float) Math.hypot(x - ax - ex * t, y - ay - ey * t));
            }
            if (found == null) { found = new float[14]; }
            // Kept in falling order of distance: the one #height marks last is the nearest.
            int at = count;
            for (; at > 0 && found[at - 2] < best; at -= 2) {
                found[at] = found[at - 2];
                found[at + 1] = found[at - 1];
            }
            found[at] = best;
            found[at + 1] = packed(c);
            count += 2;
        }
        return found == null ? null : Arrays.copyOf(found, count);
    }

    /**
     * The highest water plane that reaches over (x, y), a point of hex {@code here} (WaterLayer#plane: a water hex's
     * own, and over land the planes of the water beside it whose bank it is); negative infinity where none does.
     */
    private float planeOver(Coords here, float x, float y) {
        if (WaterLayer.water(this, here)) { return shore.water(here, x, y); }
        float plane = Float.NEGATIVE_INFINITY;
        for (int e = 0; inside(here) && e < 6; e++) {
            Coords c = here.translated(BoardGeometry.edgeDirection(e));
            if (WaterLayer.water(this, c) && WaterLayer.bank(this, c, here)) { plane = Math.max(plane, shore.water(c, x, y)); }
        }
        return plane;
    }

    /**
     * A ground sample's step top ({@link #height}'s eighth value) that marks level land beside the waterline of
     * stream hex {@code packed}'s gorge: below -1, so every reader that takes a negative top for none still does.
     */
    static int gorgeGround(int packed) { return -2 - packed; }

    /**
     * A corner where one wall ends against banks: one cliff among the pairs of its three hexes {@code t}, the third
     * hex's level strictly between that wall's two; a stream hex's step under a third hex that stands above both and
     * has a cliff to both does not count ({@link #corner}). The indices in t of the wall's upper hex and of the hex
     * between; null for any other corner.
     */
    private int[] wallEnd(Coords[] t) {
        int cliffs = 0, upper = -1, third = -1;
        for (int a = 0; a < 3; a++) {
            int c = (a + 1) % 3, o = 3 - a - c;
            if (!cliff(t[a], t[c])) { continue; }
            if ((stream(t[a], t[c]) || stream(t[c], t[a])) && inside(t[o]) && level(t[o]) > Math.max(level(t[a]), level(t[c]))
                  && cliff(t[o], t[a]) && cliff(t[o], t[c])) {
                continue;
            }
            cliffs++;
            upper = level(t[a]) > level(t[c]) ? a : c;
            third = o;
        }
        if (cliffs != 1 || !inside(t[third])) { return null; }
        int lower = 3 - upper - third;
        return level(t[lower]) < level(t[third]) && level(t[third]) < level(t[upper]) ? new int[] { upper, third } : null;
    }

    /** A wall's end nose (#wallEndNose), metres: level at the wall's upper level round the corner, and its reach. */
    private static final float NOSE_CORE = 2f, NOSE_REACH = 6.5f;
    /** The share of the nose's size left in front of the wall's face, and the turn from the bank it shrinks over. */
    private static final float NOSE_FRONT = .35f, NOSE_TURN = (float) Math.toRadians(40);
    /**
     * The share of the wall's height the bank beside its end must span for a nose: none at the first, whole from the
     * second on. Where the bank is lower the wall has no nose and runs out into the banks, as today's.
     */
    private static final float NOSE_BANK = .5f, NOSE_BANK_WHOLE = 2 / 3f;

    /**
     * The nose of rock a wall ends in at (x, y), a point of hex {@code here}, beside a corner of it where a wall ends
     * against banks ({@link #wallEnd}; #corner holds it and the wall's row fades out to it): {the wall's upper
     * level's height, the share of the rise to it the ground takes there, the wall's upper hex (#packed)}. The wall
     * keeps its height to its end, as today's, and ends by its foot rising: the ground of all three hexes comes up
     * to the rim round the corner, whole within {@link #NOSE_CORE} and falling away to {@link #NOSE_REACH}. On the
     * bank's side the nose is whole; the lower hex {@code h}'s own ground, in front of the wall's face, takes
     * {@link #NOSE_FRONT} of its size, eased in from the edge it shares with the bank's hex (where both give one
     * height), so the cliff's end stands in the cliff's line. Null elsewhere.
     */
    private float[] wallEndNose(Coords h, Coords here, float x, float y) {
        float reach = NOSE_REACH * metre, core = NOSE_CORE * metre;
        for (int k = 0; inside(here) && k < 6; k++) {
            float dx = x - cornerX(here, k), dy = y - cornerY(here, k), d2 = dx * dx + dy * dy;
            if (d2 >= reach * reach) { continue; }
            Coords[] t = { here, here.translated(BoardGeometry.edgeDirection((k + 5) % 6)),
                  here.translated(BoardGeometry.edgeDirection(k)) };
            int[] end = wallEnd(t);
            if (end == null) { return null; }
            float d = (float) Math.sqrt(d2), size = 1;
            Coords up = t[end[0]], low = t[3 - end[0] - end[1]];
            if (h.equals(low) && d > 1e-4f * metre) {
                // The turn from the edge between the bank's hex and the lower one, which runs from the corner straight
                // away from the upper hex's centre.
                float ex = cornerX(here, k) - centerX(up), ey = cornerY(here, k) - centerY(up);
                float along = (dx * ex + dy * ey) / (d * (float) Math.hypot(ex, ey));
                float turn = (float) Math.acos(Math.clamp(along, -1, 1));
                size = 1 - (1 - NOSE_FRONT) * BoardRelief.smooth(turn / NOSE_TURN);
            }
            float share = 1 - BoardRelief.smooth(Math.max(0, d / size - core) / (reach - core));
            // By how much of the wall the bank beside its end spans: from the third hex's level up to the rim.
            float spans = (level(up) - level(t[end[1]])) / (float) (level(up) - level(low));
            share *= BoardRelief.smooth((spans - NOSE_BANK) / (NOSE_BANK_WHOLE - NOSE_BANK));
            return new float[] { level(t[end[0]]) * levelZ, share, packed(t[end[0]]) };
        }
        return null;
    }

    /** Whether the step's upper hex ({@link #packed}) is a plate (FieldPlates). */
    private boolean plateTop(int packed) {
        return packed >= 0 && (walls[packed / 4096][packed % 4096] & PLATE) != 0;
    }

    /** Whether the hex {@link #packed} gave is a stream hex with such a step. */
    private boolean streamTop(int packed) {
        return packed >= 0 && (walls[packed / 4096][packed % 4096] >>> STREAM & 63) != 0;
    }

    /**
     * The share of a stream hex's step the lower hex's ground keeps at distance s from the shared edge, at (x, y): whole
     * on a shelf at the rim's height ({@link #bankShelf}; both hexes give the edge samples one height), then a straight
     * slope with a rounded shoulder and toe ({@link #straight}); nothing beyond {@link #bankFoot}.
     */
    private float bankSlope(float s, float x, float y) {
        // A shelf at the rim's height (the slope's top line wanders in plan), then the field's bank profile down to a
        // foot that does not wander with it: a rounded shoulder, steepest mid-slope, as the bank beside.
        float shelf = bankShelf(x, y, metre);
        float u = (Math.max(0, s - .02f * metre) - shelf) / (bankFoot(x, y, metre, room) - shelf);
        if (u <= 0 || u >= 1) { return u <= 0 ? 1 : 0; }
        // A straight slope with a rounded shoulder and toe (0.15 of its run each): no steeper band mid-slope, so a
        // bank that faces away from the sun shades evenly.
        float e = .15f, m = 1 / (1 - e);
        float fall = u < e ? m * u * u / (2 * e) : u > 1 - e ? 1 - m * (1 - u) * (1 - u) / (2 * e) : m * (u - e / 2);
        return 1 - fall;
    }

    /** A straight slope's share at u of its run (1 at its top, 0 at its foot), its shoulder and toe rounded over 0.15. */
    private static float straight(float u) {
        if (u <= 0 || u >= 1) { return u <= 0 ? 1 : 0; }
        float e = .15f, m = 1 / (1 - e);
        return 1 - (u < e ? m * u * u / (2 * e) : u > 1 - e ? 1 - m * (1 - u) * (1 - u) / (2 * e) : m * (u - e / 2));
    }

    /** Where a stream hex's bank slope ends, from the shared edge at (x, y): its reach and half its shelf. */
    static float bankFoot(float x, float y) {
        return bankFoot(x, y, BoardRelief.metres(1), BoardRelief.metres(BoardRelief.TRANSITION));
    }

    /** {@link #bankFoot} with the metre and the transition's width its caller holds (no settings read a call). */
    private static float bankFoot(float x, float y, float m, float room) {
        return bankReach(x, y, m, room) + .5f * bankShelf(x, y, m);
    }

    /** How far the top of a stream hex's bank slope stands out from the shared edge at (x, y): 0 to 2 m by noise. */
    private static float bankShelf(float x, float y, float m) {
        return Math.min(2 * m, 4 * m * Math.abs(BoardRelief.noise(x / (10 * m) + 6.3f, y / (10 * m) - 1.8f)
              - BoardRelief.noise(x / (10 * m) - 17.1f, y / (10 * m) + 9.4f)));
    }

    /** The run of a stream hex's bank slope at (x, y): 1.2 to 1.32 of twice the transition (9.6 to 10.6 m). */
    private static float bankReach(float x, float y, float m, float room) {
        return 2 * room * (1.2f + .12f * BoardRelief.noise(x / (10 * m) + 1.9f, y / (10 * m) - 3.6f));
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
        Coords at = hexAt(x, y);
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
                          ax + (bx - ax) * t1 + m[j + 1][0], ay + (by - ay) * t1 + m[j + 1][1], 0, 0, true, -1, false,
                          false);
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

    /**
     * Whether a standing natural span lands on hex h across its edge e by a ramp (BoardNaturalBridge#rampLanding):
     * its top runs just under h's rim there, so h's crest stands straight (ChunkField.Wall).
     */
    boolean landing(Coords h, int e) {
        int across = BoardGeometry.edgeDirection(e);
        Coords span = h.translated(across);
        return inside(span) && (landings[span.getX()][span.getY()] & 1 << (across + 3) % 6) != 0;
    }

    /** Whether the hex is a ground portal's road hex (BoardTunnel#frame): the one whose ground its approach lowers. */
    boolean portal(Coords c) {
        float[][] frames = mouths(c);
        for (int i = 0; frames != null && i < frames.length; i++) {
            if (frames[i][13] == packed(c)) { return true; }
        }
        return false;
    }

    /**
     * The containment walls beside the roads round hex h, over h's own ground (FieldRoads#walls); {@code shares} gives
     * a hex's chunk's grid (ChunkField#shares). None for a hex the field does not take.
     */
    List<float[]> walls(Coords h, java.util.function.Function<Coords, Shares> shares) {
        if (!taken(h)) { return List.of(); }
        return roads.walls(h, at -> {
            if (!taken(at)) { return null; }
            List<Segment> segments = segments(at);
            Shares share = shares.apply(at);
            float[] out = new float[8];
            // Its own ground only, as today's rule asks the hex's own faces: none past its edge.
            return (x, y) -> {
                if (!BoardGeometry.contains(at, x, y)) { return Float.NaN; }
                height(at, segments, share, x, y, out);
                return out[0];
            };
        }, this::slopeAt).places();
    }

    /** The panels hex h's block was cut along (#walls), to draw. */
    List<BoardSurface.Face> wallFaces(Coords h) { return roads.wallFaces(h); }

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

    /** The hex a point lies in: GpuStarShells#hexAt's arithmetic on this board's hex size (no settings read a call). */
    private Coords hexAt(float x, float y) {
        int column = (int) Math.floor(x / (width * .75f));
        int row = (int) Math.floor(-y / height);
        Coords best = null;
        float nearest = Float.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                Coords candidate = new Coords(column + dx, row + dy);
                float offsetX = x - centerX(candidate);
                float offsetY = y - centerY(candidate);
                float distance = offsetX * offsetX + offsetY * offsetY;
                if (distance < nearest) {
                    nearest = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** Whether the step between u and v is a wall by BoardRelief's rule (BoardRelief#levels). */
    boolean cliff(Coords u, Coords v) {
        return inside(u) && inside(v) && ((walls[u.getX()][u.getY()] & 1 << edgeIndex(u, v)) != 0
              || (walls[v.getX()][v.getY()] & 1 << edgeIndex(v, u)) != 0);
    }

    /** The surface of a carriageway on the ground whose reserved ground holds (x, y) (FieldRoads#groundSurface). */
    float roadSurface(Coords h, float x, float y) { return roads.groundSurface(h, x, y); }

    /** Whether a carriageway ramps across the edge between u and v (FieldRoads#ramps). */
    boolean ramp(Coords u, Coords v) {
        return (roads.ramps(u) & 1 << u.direction(v)) != 0 || (roads.ramps(v) & 1 << v.direction(u)) != 0;
    }

    /**
     * Reserved ground at (x, y) beside an edge a carriageway ramps across (#ramp), from {@code ground}: the road's
     * surface within the step's room of the edge, eased back over as much again. The step's level set, centred on
     * the edge, would dip under the ramp's high end (the mean of the two levels where the road stands at the higher).
     * The point alone decides (the hex it lies in and that hex's edges), so hexes sharing a sample give it one height.
     */
    private float rampBand(float ground, float x, float y) {
        Coords here = hexAt(x, y);
        if (!inside(here)) { return ground; }
        float near = Float.POSITIVE_INFINITY;
        for (int e = 0; e < 6; e++) {
            Coords g = here.translated(BoardGeometry.edgeDirection(e));
            if (!inside(g) || level(g) == level(here) || !ramp(here, g)) { continue; }
            near = Math.min(near, BoardRelief.distance(x, y, cornerX(here, e), cornerY(here, e), cornerX(here, e + 1),
                  cornerY(here, e + 1)));
        }
        if (near >= 2 * room) { return ground; }
        float surface = roads.surface(here, x, y);
        return Float.isNaN(surface) ? ground
              : BoardRelief.lerp(surface, ground, BoardRelief.smooth(Math.max(0, near - room) / room));
    }

    /** A wall whose row the field moves: a cliff between two hexes it takes. */
    private boolean wall(Coords u, Coords v) { return taken(u) && taken(v) && cliff(u, v); }

    /** The edge index of {@code from}'s edge toward its neighbour {@code to}. */
    static int edgeIndex(Coords from, Coords to) {
        // A neighbour's direction from the two hexes' columns and rows; Coords#direction takes an arc tangent.
        for (int direction = 0; direction < 6; direction++) {
            if (Coords.xInDir(from.getX(), from.getY(), direction) == to.getX()
                  && Coords.yInDir(from.getX(), from.getY(), direction) == to.getY()) {
                return Math.floorMod(1 - direction, 6);
            }
        }
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
     * the talus's reach). The sample lies on hex {@code at}'s edge e, at t from its first corner. A sample at a fall
     * (one between any two of the hexes round it: the fall edge's samples and both its end corners) is not moved:
     * today's curtain and pool edge hang from the straight hex edge, and its banks stand at the hex corners. Nor is
     * one at a stream hex's step ({@link #stream}): its bank's top stands on the hex edge.
     */
    private float[] rimMove(int k, float x, float y, Coords[] around, Coords upper, Coords lower, Coords at, int e, float t) {
        for (Coords a : around) {
            for (Coords c : around) {
                if (!a.equals(c) && (falls(a, c) || stream(a, c) && level(a) >= k)) { return new float[] { 0, 0, Float.NaN, 1 }; }
            }
        }
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
        // The relief the rim carries: today's at the rim, averaged along the wall over a lattice step either way (the
        // lattice cannot carry its finer changes: the rim would zigzag); the wall's rows below carry them whole.
        float along = edge / FULL, share = 1;
        float relief = .5f * BoardRelief.wallRelief(px, py, top, bottom, top, drop, family(upper))
              + .25f * BoardRelief.wallRelief(px - o[2] * along, py + o[1] * along, top, bottom, top, drop, family(upper))
              + .25f * BoardRelief.wallRelief(px + o[2] * along, py - o[1] * along, top, bottom, top, drop, family(upper));
        float r = recess - relief;
        float dx = px - x - r * o[1], dy = py - y - r * o[2];
        // The roads' term: the rim recedes from the carriageways beside it by today's BoardRelief#roadPush.
        float[] push = roads.push(at, e, t, x + dx, y + dy);
        if (push != null) { dx += push[0]; dy += push[1]; }
        float len = (float) Math.hypot(dx, dy);
        if (len > maxMove) { dx *= maxMove / len; dy *= maxMove / len; share = maxMove / len; }
        for (Coords c : around) {
            float clear = level(c) >= k ? keep : keep + room;
            float ox = x + dx - centerX(c), oy = y + dy - centerY(c);
            float r0 = (float) Math.hypot(ox, oy);
            if (r0 < clear && r0 > 1e-3f) {
                dx += ox / r0 * (clear - r0);
                dy += oy / r0 * (clear - r0);
            }
        }
        // With the relief the move carries and the share of it the cap left: the wall below hangs its rows from that
        // (ChunkField.Wall), so nothing of the relief is left over at a sample.
        return new float[] { dx, dy, relief, share };
    }

    /** The run of a cliff's talus, from its foot back to its face (#footPush; BoardRelief#rockPoint). */
    static float talusRun() { return TALUS_RUN * BoardRelief.metres(BoardRelief.TRANSITION); }

    /**
     * How far a wall's foot stands out from its rim's row at (x, y) on it, as today's does: its talus's run
     * ({@link #TALUS_RUN}) and today's rock relief at the foot, averaged along the wall over (sx, sy) either way as
     * the rim's (#rimMove), beyond {@code relief}, the rim's, by {@code share}, the share of its relief the wall
     * keeps there. The wall's rows come down to the foot along today's talus (ChunkField.Wall).
     */
    private float footPush(float x, float y, float sx, float sy, Coords upper, Coords lower, int family, float relief,
          float share) {
        float top = level(upper) * levelZ, bottom = level(lower) * levelZ;
        float drop = Math.max(level(upper) - level(lower), 3) * levelZ;
        float foot = .5f * BoardRelief.wallRelief(x, y, bottom, bottom, top, drop, family)
              + .25f * BoardRelief.wallRelief(x - sx, y - sy, bottom, bottom, top, drop, family)
              + .25f * BoardRelief.wallRelief(x + sx, y + sy, bottom, bottom, top, drop, family);
        return TALUS_RUN * room + Math.clamp(foot - relief, -.5f * room, 1.5f * room) * share;
    }

    /**
     * The share of a foot's push (ux, uy) from (x, y) that keeps the foot off the flat ground kept round a hex's
     * centre and off the roads' ground: whole, or less by sixths.
     */
    private float footShare(float x, float y, float ux, float uy) {
        for (int i = 6; i > 0; i--) {
            float px = x + ux * i / 6, py = y + uy * i / 6;
            Coords at = hexAt(px, py);
            if (!inside(at) || Math.hypot(px - centerX(at), py - centerY(at)) >= keep && !roads.reserved(at, px, py)) {
                return i / 6f;
            }
        }
        return 0;
    }

    /**
     * How much of a wall takes its corner's column {@code along} its edge from that corner (0 to 1), or, at a corner
     * without one, how much of its relief has faded out there (ChunkField.Wall, #canonicalEdge's foot).
     */
    static float cornerShare(float along, boolean column) {
        return 1 - BoardRelief.smooth(along / (column ? .3f : .15f));
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
        if (plate(t[0]) || plate(t[1]) || plate(t[2])) {
            float[] fitted = FieldPlates.corner(concrete, h, k, cornerX(h, k), cornerY(h, k)), foot = footCorner(t);
            return new float[] { fitted[0] + foot[0], fitted[1] + foot[1], fitted[2] };
        }
        int k0 = 0;
        Coords upperHex = null, lowerHex = null;
        boolean[] side = null;
        boolean consistent = true, any = false;
        int[][] pairs = { { 0, 1 }, { 0, 2 }, { 1, 2 } };
        for (int[] p : pairs) {
            if (!cliff(t[p[0]], t[p[1]])) { continue; }
            // A stream hex's step has no wall (its bank is a slope): under a third hex that stands above both and
            // walls to both, the corner is that hex's wall's, which turns it as one row.
            Coords third = t[3 - p[0] - p[1]];
            if ((stream(t[p[0]], t[p[1]]) || stream(t[p[1]], t[p[0]])) && inside(third)
                  && level(third) > Math.max(level(t[p[0]]), level(t[p[1]])) && cliff(third, t[p[0]]) && cliff(third, t[p[1]])) {
                continue;
            }
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
            // A wall that ends here against banks (#wallEnd): the corner is held too, so the wall's row fades out to the
            // hex corner, as today's wall does at its end.
            if (!all || !consistent || side == null || level(upperHex) == level(lowerHex) || wallEnd(t) != null) {
                result[2] = 1;
            } else {
                float[] move = rimMove(k0, cornerX(h, k), cornerY(h, k), t, upperHex, lowerHex, h, k, 0);
                // Round a ground portal the corner stays put, as the row beside it does (#canonicalEdge).
                float foot = Math.min(level(t[0]), Math.min(level(t[1]), level(t[2]))) * levelZ, share = 1;
                for (Coords c : t) { share = Math.min(share, relief(c, cornerX(h, k), cornerY(h, k), foot)); }
                // Not held (the third value); then the relief the move carries and the share left of it (#rimMove).
                result = new float[] { move[0] * share, move[1] * share, 0, move[2], move[3] * share };
                // Above the portal's head the hexes over the wall stand back with its rim (#latticeMove): the move
                // by the hold at the rim's height, its share, and the hexes that take it, a bit each.
                float top = Math.max(level(t[0]), Math.max(level(t[1]), level(t[2]))) * levelZ, rise = 1;
                for (Coords c : t) { rise = Math.min(rise, relief(c, cornerX(h, k), cornerY(h, k), top)); }
                boolean leaning = true, dry = true;
                for (int[] p : pairs) { leaning &= side[p[0]] == side[p[1]] || leans(t[p[0]], t[p[1]]); }
                for (Coords c : t) { dry &= shore.family(c) < 0 && !portal(c); }
                boolean parts = leaning && rise > share;
                // The foot's push (#footPush), which the hexes below take: along the corner's own direction, as the
                // walls' columns there stand (ChunkField.Wall#cornerColumn: out of the one hex above or into the
                // one below, the rock the first hex above's; they keep one where the two hexes on one side are level).
                int ups = (side[0] ? 1 : 0) + (side[1] ? 1 : 0) + (side[2] ? 1 : 0), one = 0, first = -1, other = -1;
                for (int i = 0; i < 3; i++) {
                    if (side[i] == (ups == 1)) { one = i; } else { other = i; }
                    if (side[i] && (first < 0 || before(t[i], t[first]))) { first = i; }
                }
                boolean column = level(t[3 - one - other]) == level(t[other]);
                float ux = 0, uy = 0;
                if (leaning && dry && !Float.isNaN(move[2])) {
                    float dx = cornerX(h, k) - centerX(t[one]), dy = cornerY(h, k) - centerY(t[one]);
                    float out = footPush(cornerX(h, k), cornerY(h, k), 0, 0, upperHex, lowerHex, family(t[first]),
                          move[2], column ? move[3] : 0) * share / ((ups == 1 ? 1 : -1) * (float) Math.hypot(dx, dy));
                    out *= footShare(cornerX(h, k) + result[0], cornerY(h, k) + result[1], dx * out, dy * out);
                    ux = dx * out;
                    uy = dy * out;
                }
                if (parts || ux != 0 || uy != 0) {
                    float held = parts ? rise : share;
                    result = new float[] { result[0] + ux, result[1] + uy, 0, move[2], result[4], move[0] * held,
                          move[1] * held, move[3] * held, (side[0] ? 1 : 0) | (side[1] ? 2 : 0) | (side[2] ? 4 : 0),
                          ux, uy, column ? 1 : 0 };
                }
            }
        }
        return result;
    }

    /** Whether hex a comes before hex b: a smaller column, or the same column and a smaller row. */
    private static boolean before(Coords a, Coords b) {
        return a.getX() < b.getX() || a.getX() == b.getX() && a.getY() < b.getY();
    }

    /** Whether the step between u and v is a wall whose row the field moves (#canonicalEdge). */
    private boolean rowWall(Coords u, Coords v) {
        // A plate's edge runs straight between its corners.
        // A cliff between two hexes at one level is the shore's to shape: no rise to move a rim by (#corner).
        return wall(u, v) && !plate(u) && !plate(v) && level(u) != level(v);
    }

    /** Whether the wall between u and v can part its rows (#latticeMove): a stream hex's step has none (#stream). */
    private boolean leans(Coords u, Coords v) { return rowWall(u, v) && !stream(u, v) && !stream(v, u); }

    /** Corner k of hex h where h stands over a wall with two rows there: the rim's move, as #corner's; else null. */
    private float[] rimCorner(Coords h, int k) {
        float[] c = cornerMove(h, k);
        if (c.length <= 5) { return null; }
        int[] slot = cornerSlot(h, k);
        Coords first = new Coords(slot[0], slot[1]);
        Coords[] t = { first, first.translated(BoardGeometry.edgeDirection((slot[3] + 5) % 6)),
              first.translated(BoardGeometry.edgeDirection(slot[3])) };
        for (int i = 0; i < 3; i++) {
            if (t[i].equals(h) && ((int) c[8] >> i & 1) != 0) { return new float[] { c[5], c[6], 0, c[3], c[7] }; }
        }
        return null;
    }

    /**
     * The moves hex h's own lattice takes along its edge e: the row's (#edgeMove), but for the hexes over a wall with
     * two rows. Its foot's row is the lower hex's: it stands out from the rim's by the talus's run and the rock's
     * relief at the foot (#footPush) and, round a ground portal, on the edge the portal's head is set into, as
     * today's foot; the hexes above stand back with the rim, as today's rim does (#canonicalEdge, #corner).
     */
    float[][] latticeMove(Coords h, int e) {
        float[][] moves = edgeMove(h, e);
        Coords g = h.translated(BoardGeometry.edgeDirection(e));
        if (moves[0].length <= 4 || rowWall(h, g) && level(h) < level(g)) { return moves; }
        float[][] rim = new float[FULL + 1][];
        for (int j = 0; j <= FULL; j++) { rim[j] = new float[] { moves[j][4], moves[j][5], moves[j][2], moves[j][6] }; }
        return rim;
    }

    /** Whether hex h stands over a wall with two rows on its edge e: its lattice takes the rim's (#latticeMove). */
    boolean leans(Coords h, int e) {
        Coords g = h.translated(BoardGeometry.edgeDirection(e));
        return rowWall(h, g) && level(h) > level(g) && edgeMove(h, e)[0].length > 4;
    }

    /** The share of a wall's move that stands at (x, y, z) of the edge between h and g round their ground portals. */
    float hold(Coords h, Coords g, float x, float y, float z) {
        return Math.min(relief(h, x, y, z), relief(g, x, y, z));
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
        boolean wall = rowWall(u, v);
        Coords[] around = { u, v };
        float[][] points = new float[FULL + 1][];
        float fade = .5f * edge;
        Coords upper = level(u) > level(v) ? u : v;
        boolean pool = wall && poolWall(upper, edgeIndex(upper, upper == u ? v : u));
        // The rim's row, where the hexes over a leaning wall stand (#latticeMove): between their own corners.
        Coords above = wall ? upper : u;
        int ea = above == u ? e : edgeIndex(v, u);
        float[] ra = rimCorner(above, above == u ? ea : (ea + 1) % 6);
        float[] rb = rimCorner(above, above == u ? (ea + 1) % 6 : ea);
        boolean parted = ra != null || rb != null;
        ra = ra == null ? ca : ra;
        rb = rb == null ? cb : rb;
        float[][] rim = parted || leans(u, v) ? new float[FULL + 1][] : null;
        // The foot's push at each sample (#footPush) and at the two corners (#corner), out of the upper hex.
        float[][] push = rim == null ? null : new float[FULL + 1][2];
        float[] pa = { ca.length > 9 ? ca[9] : 0, ca.length > 9 ? ca[10] : 0 };
        float[] pb = { cb.length > 9 ? cb[9] : 0, cb.length > 9 ? cb[10] : 0 };
        // None beside water (the wall runs down to the bed) nor onto a ground portal's road hex (its approach
        // shapes that ground up to the hex edge, where the wall's foot stays, as today's).
        boolean dry = taken(u) && taken(v) && shore.family(u) < 0 && shore.family(v) < 0 && !portal(u) && !portal(v);
        float lowX = centerX(upper == u ? v : u) - centerX(upper), lowY = centerY(upper == u ? v : u) - centerY(upper);
        float lowLength = (float) Math.hypot(lowX, lowY);
        parted |= push != null && (pa[0] != 0 || pa[1] != 0 || pb[0] != 0 || pb[1] != 0);
        // A plate's slope: the lattice's edge stands at the slope's foot, a room out of the plate from its line, so the
        // plate's block holds the whole slope (its slab). Toward a corner that keeps its fitted place (#footCorner) it
        // comes back over half the edge, straight between the samples every detail keeps: a sample moved more than its
        // ring's depth would fold the lattice, and a bend between a coarser detail's samples would part the morph.
        float[] line = slopes(u, v) ? slopeLine(u, v) : null;
        float[] fa = line == null ? null : FieldPlates.corner(concrete, u, e, ax, ay);
        float[] fb = line == null ? null : FieldPlates.corner(concrete, u, (e + 1) % 6, bx, by);
        for (int j = 0; j <= FULL; j++) {
            float t = j / (float) FULL;
            float mx = ca[0] + (cb[0] - ca[0]) * t, my = ca[1] + (cb[1] - ca[1]) * t;
            if (line != null && j > 0 && j < FULL) {
                float length = (float) Math.hypot(line[2] - line[0], line[3] - line[1]);
                float outX = (line[3] - line[1]) / length, outY = -(line[2] - line[0]) / length;
                float atA = Math.clamp(((ca[0] - fa[0]) * outX + (ca[1] - fa[1]) * outY) / room, 0, 1);
                float atB = Math.clamp(((cb[0] - fb[0]) * outX + (cb[1] - fb[1]) * outY) / room, 0, 1);
                float out = room * Math.min(1, Math.min(atA + j / (FULL / 2f), atB + (FULL - j) / (FULL / 2f)));
                // Evenly between the two corners' places along the fitted edge: a mitre shortens the foot at a corner
                // the outline turns in at, and a sample set by the fitted corners would stand past it (a fold).
                float alongX = (bx + fb[0] - ax - fa[0]) / length, alongY = (by + fb[1] - ay - fa[1]) / length;
                float fromA = (ca[0] - fa[0]) * alongX + (ca[1] - fa[1]) * alongY;
                float toB = length + (cb[0] - fb[0]) * alongX + (cb[1] - fb[1]) * alongY;
                float along = fromA + (toB - fromA) * t;
                points[j] = new float[] { fa[0] + alongX * along + out * outX - (bx - ax) * t,
                      fa[1] + alongY * along + out * outY - (by - ay) * t, Float.NaN, 1 };
                continue;
            }
            if (j == 0 || j == FULL || !wall) {
                // A wall's corner the field moved says what its move carries, as the row's samples do (#corner).
                float[] corner = j == 0 ? ca : cb;
                boolean carries = wall && (j == 0 || j == FULL) && corner.length > 3;
                points[j] = new float[] { mx, my, carries ? corner[3] : Float.NaN, carries ? corner[4] : 1 };
                if (rim != null) {
                    rim[j] = new float[] { ra[0] + (rb[0] - ra[0]) * t, ra[1] + (rb[1] - ra[1]) * t,
                          carries ? (j == 0 ? ra : rb)[3] : Float.NaN, carries ? (j == 0 ? ra : rb)[4] : 1 };
                    push[j][0] = pa[0] + (pb[0] - pa[0]) * t;
                    push[j][1] = pa[1] + (pb[1] - pa[1]) * t;
                }
                continue;
            }
            float[] d = rimMove(middle(u, v), ax + (bx - ax) * t, ay + (by - ay) * t, around,
                  level(u) > level(v) ? u : v, level(u) > level(v) ? v : u, u, e, t);
            // The move fades out toward a held corner.
            float w = 1;
            // Behind a pool's wall that is no fall (#poolWall) a corner a fall keeps on the hex corner (#rimMove: its
            // move carries no relief) is faded toward as well: the pool's rim comes back to it, no jog.
            if (ca[2] > 0 || pool && ca.length > 3 && Float.isNaN(ca[3])) { w = Math.min(w, t * edge / fade); }
            if (cb[2] > 0 || pool && cb.length > 3 && Float.isNaN(cb[3])) { w = Math.min(w, (1 - t) * edge / fade); }
            w = w * w * (3 - 2 * w);
            // Round a ground portal the wall's row stays on the edge, as today's foot does (BoardTunnel#relief).
            float x = ax + (bx - ax) * t, y = ay + (by - ay) * t, foot = Math.min(level(u), level(v)) * levelZ;
            float kept = hold(u, v, x, y, foot);
            if (rim != null) {
                // The rim stands back by the hold at its own height: above the head, as today's.
                float rise = hold(u, v, x, y, Math.max(level(u), level(v)) * levelZ);
                float rx = ra[0] + (rb[0] - ra[0]) * t, ry = ra[1] + (rb[1] - ra[1]) * t;
                parted |= rise != kept;
                rim[j] = new float[] { rx + (d[0] * rise - rx) * w, ry + (d[1] * rise - ry) * w, d[2],
                      d[3] * w * rise };
            }
            w *= kept;
            // The corners' line (mx, my) holds their pushes (lx, ly); without them the sample stands at (qx, qy).
            float lx = pa[0] + (pb[0] - pa[0]) * t, ly = pa[1] + (pb[1] - pa[1]) * t;
            float qx = mx + (d[0] - mx) * w - lx * (1 - w), qy = my + (d[1] - my) * w - ly * (1 - w);
            if (push != null) {
                // The foot stands out by its push, blended with the corners' as the wall blends its relief with their
                // columns (ChunkField.Wall#column), and faded as the move.
                float first = cornerShare(t, ca.length > 11 && ca[11] > 0);
                float second = cornerShare(1 - t, cb.length > 11 && cb[11] > 0);
                float own = !dry || Float.isNaN(d[2]) ? 0 : (1 - first - second) / lowLength
                      * footPush(x + d[0], y + d[1], (bx - ax) / FULL, (by - ay) / FULL, upper, upper == u ? v : u,
                            family(upper), d[2], d[3]);
                float px = lx + (own * lowX + first * pa[0] + second * pb[0] - lx) * w;
                float py = ly + (own * lowY + first * pa[1] + second * pb[1] - ly) * w;
                float share = px == 0 && py == 0 ? 0 : footShare(x + qx, y + qy, px, py);
                qx += push[j][0] = px * share;
                qy += push[j][1] = py * share;
                parted |= share != 0;
            }
            // With the relief the rim carries there and the share of the move the fades left (#rimMove).
            points[j] = new float[] { qx, qy, d[2], d[3] * w };
        }
        // A wall's rim flattens toward its corners' line until neither hex's lattice folds on it.
        int whole = FULL;
        for (; wall && whole > 0 && !(unfolded(u, e, points, false) && unfolded(v, edgeIndex(v, u), points, true));
              whole--) {
            flatten(points, ca, cb, whole);
        }
        // The pushes its samples keep flatten with them, toward the corners' own.
        for (int j = 1; push != null && whole < FULL && j < FULL; j++) {
            float t = j / (float) FULL, lx = pa[0] + (pb[0] - pa[0]) * t, ly = pa[1] + (pb[1] - pa[1]) * t;
            push[j][0] = lx + (push[j][0] - lx) * whole / FULL;
            push[j][1] = ly + (push[j][1] - ly) * whole / FULL;
        }
        if (!parted) { return points; }
        // The rim's row likewise, on the lattice of the hex above alone.
        for (int left = FULL; wall && left > 0 && !unfolded(above, ea, rim, above != u); left--) {
            flatten(rim, ra, rb, left);
        }
        for (int j = 0; j <= FULL; j++) {
            float[] p = points[j];
            points[j] = new float[] { p[0], p[1], p[2], p[3], rim[j][0], rim[j][1], rim[j][3], push[j][0], push[j][1] };
        }
        return points;
    }

    /** One step of a row's flattening toward its corners' line (#canonicalEdge): {@code left} steps are left. */
    private static void flatten(float[][] points, float[] ca, float[] cb, int left) {
        for (int j = 1; j < FULL; j++) {
            float t = j / (float) FULL;
            float mx = ca[0] + (cb[0] - ca[0]) * t, my = ca[1] + (cb[1] - ca[1]) * t;
            points[j][0] = mx + (points[j][0] - mx) * (left - 1) / left;
            points[j][1] = my + (points[j][1] - my) * (left - 1) / left;
            points[j][3] *= (left - 1f) / left;
        }
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
            float[][] d = latticeMove(h, e);
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
            if (closer(s, ox, oy, reach) || s.rim() != null && closer(s.rim(), ox, oy, reach)) { result.add(s); }
        }
        return List.copyOf(result);
    }

    /** Whether segment s comes within {@code reach} of the closed polyline (ox, oy). */
    private static boolean closer(Segment s, float[] ox, float[] oy, float reach) {
        float r2 = reach * reach;
        for (int i = 0; i + 1 < ox.length; i++) {
            Segment o = new Segment(ox[i], oy[i], ox[i + 1], oy[i + 1], 0, 0, false, -1, false, false);
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
        // Shaped water keeps its surface up to such water at its surface: its bed meets today's (FieldShore#seam).
        if (lu == lv || levelShore(u, v)) { return; }
        int top = packed(lu > lv ? u : v);
        boolean cliff = cliff(u, v), plated = plated(u, v, cliff);
        float ax = cornerX(u, e), ay = cornerY(u, e), bx = cornerX(u, e + 1), by = cornerY(u, e + 1);
        float[][] d = edgeMove(u, e);
        if (slopes(u, v)) {
            // A plate's slope steps on its fitted line, not on the lattice's edge at its foot (#canonicalEdge).
            float[] line = slopeLine(u, v);
            into.add(new Segment(line[0], line[1], line[2], line[3], Math.min(lu, lv), Math.max(lu, lv), cliff, top,
                  joinedWater(u, v), plated));
            return;
        }
        if (!wall(u, v)) {
            // Its samples lie on the straight line between its moved corners: one segment.
            into.add(new Segment(ax + d[0][0], ay + d[0][1], bx + d[FULL][0], by + d[FULL][1], Math.min(lu, lv), Math.max(lu, lv),
                  cliff, top, joinedWater(u, v), plated));
            return;
        }
        for (int j = 0; j < FULL; j++) {
            float t0 = j / (float) FULL, t1 = (j + 1) / (float) FULL;
            // A wall with two rows (#latticeMove): the hexes at its top measure from the rim's (#height).
            Segment rim = d[j].length <= 4 ? null
                  : new Segment(ax + (bx - ax) * t0 + d[j][4], ay + (by - ay) * t0 + d[j][5],
                        ax + (bx - ax) * t1 + d[j + 1][4], ay + (by - ay) * t1 + d[j + 1][5], 0, 0, true, -1, false,
                        false);
            into.add(new Segment(ax + (bx - ax) * t0 + d[j][0], ay + (by - ay) * t0 + d[j][1],
                  ax + (bx - ax) * t1 + d[j + 1][0], ay + (by - ay) * t1 + d[j + 1][1],
                  Math.min(lu, lv), Math.max(lu, lv), cliff, top, joinedWater(u, v), plated, rim));
        }
    }

    /** The bank's profile at signed distance s from its outline (negative inside): today's slope transition. */
    /**
     * How much deeper the upper half of level set j's bank runs, 1 to 1.75: 1.75 where the level below shares its
     * nearest bank step (within a metre, eased out over a metre) and none above does, so the top of a stack of
     * banks on one outline (a two-level bank) rounds its shoulder over two lattice steps more, as today's does,
     * instead of curving twice as sharply as a one-level bank's within the lattice's step. {@code bankTo}: per level
     * set the squared distance to its nearest bank step (positive infinity for none).
     */
    private float stackTop(float[] bankTo, int j, int span) {
        if (j == 0 || bankTo[j] == Float.POSITIVE_INFINITY || bankTo[j - 1] == Float.POSITIVE_INFINITY) { return 1; }
        float d = (float) Math.sqrt(bankTo[j]);
        float below = 1 - BoardRelief.smooth(Math.abs((float) Math.sqrt(bankTo[j - 1]) - d) / metre);
        float above = j + 1 < span && bankTo[j + 1] != Float.POSITIVE_INFINITY
              ? 1 - BoardRelief.smooth(Math.abs((float) Math.sqrt(bankTo[j + 1]) - d) / metre) : 0;
        return 1 + .75f * below * (1 - above);
    }

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
        // A point with no place (a coordinate not finite) lies in no hex (#hexAt): it has no height.
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            java.util.Arrays.fill(out, 0);
            out[0] = Float.NaN;
            return;
        }
        int own = level(h), lowest = own, highest = own;
        // A plate is level, and so is natural ground on a plate's fitted outline at its level (FieldPlates).
        if (plate(h) || onPlate(h, x, y)) {
            // Reserved ground on a plate is its road's surface, ramped where today's ramps.
            float road = roads.surface(h, x, y);
            // Within a plate's slope's reach the ground is the bank's below, as every hex has it there.
            if (!Float.isNaN(road) || !pavedNear(segments, x, y)) {
                FieldPlates.level(own * levelZ, own, out);
                if (!Float.isNaN(road)) { out[0] = road; }
                return;
            }
            // The plate's block reaches to its slopes' feet (#canonicalEdge): past a slope's line the ground is the
            // lower hex's, as that hex gives it, so the samples the two share have one height.
            Coords below = pavedBelow(h, segments, x, y);
            if (below != null) {
                height(below, segments(below), shares, x, y, out);
                return;
            }
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
            // A wall with two rows stands on its rim's line for the hexes at its top, on its foot's for the others.
            d[i] = s.rim() != null && own >= s.high() ? s.rim().squared(x, y) : s.squared(x, y);
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
        // Per level set k above the lowest: its nearest step, bank and cliff, squared, from one
        // pass over the steps, each counting for the levels it spans, in the order they are listed.
        int span = highest - lowest;
        float[] nearestTo = new float[span], bankTo = new float[span], cliffTo = new float[span];
        Arrays.fill(nearestTo, Float.POSITIVE_INFINITY);
        Arrays.fill(bankTo, Float.POSITIVE_INFINITY);
        float[] joinedTo = new float[span];
        Arrays.fill(joinedTo, Float.POSITIVE_INFINITY);
        Arrays.fill(cliffTo, Float.POSITIVE_INFINITY);
        // Per level set, the nearest step of a stream hex: the lower hex's ground there is its bank slope (#stream).
        float[] streamTo = new float[span];
        Arrays.fill(streamTo, Float.POSITIVE_INFINITY);
        // Per level set, the nearest bank onto ground that keeps its outline (Segment#plated).
        float[] platedTo = new float[span];
        Arrays.fill(platedTo, Float.POSITIVE_INFINITY);
        // Per level set, the nearest plate's slope (#paved): its bank is the plate's straight slope.
        float[] pavedTo = new float[span];
        Arrays.fill(pavedTo, Float.POSITIVE_INFINITY);
        // Per level set, whether its nearest cliff is a plate's cast wall (FieldPlates): poured, it keeps no apron.
        boolean[] plateFoot = new boolean[span];
        for (int i = 0; i < d.length; i++) {
            Segment s = segments.get(i);
            for (int j = s.low() - lowest; j < s.high() - lowest; j++) {
                nearestTo[j] = Math.min(nearestTo[j], d[i]);
                if (s.cliff() && streamTop(s.top())) { streamTo[j] = Math.min(streamTo[j], d[i]); }
                if (!s.cliff()) {
                    bankTo[j] = Math.min(bankTo[j], d[i]);
                    if (s.plated()) { platedTo[j] = Math.min(platedTo[j], d[i]); }
                    if (paved(s)) { pavedTo[j] = Math.min(pavedTo[j], d[i]); }
                    if (s.joined()) { joinedTo[j] = Math.min(joinedTo[j], d[i]); }
                }
                else if (d[i] < cliffTo[j] - TIE) {
                    cliffTo[j] = d[i];
                    plateFoot[j] = plateTop(s.top());
                }
                else if (d[i] <= cliffTo[j] + TIE) {
                    cliffTo[j] = Math.min(cliffTo[j], d[i]);
                    plateFoot[j] &= plateTop(s.top());
                }
            }
        }
        float wall = 0, sill = 0, shelf = 0, run = shore.run(), eased = 0;
        for (int k = lowest + 1; k <= highest; k++) {
            float nearest = nearestTo[k - lowest - 1], bankD = bankTo[k - lowest - 1], cliffD = cliffTo[k - lowest - 1];
            // The nearest bank a step between joined waters: its foot keeps no shelf (the bed is today's, FieldShore).
            boolean joinedBank = joinedTo[k - lowest - 1] <= bankD + TIE;
            // Under a stream hex's step the lower hex's ground is the bank's slope, whole at the shared edge.
            boolean streamStep = streamTo[k - lowest - 1] != Float.POSITIVE_INFINITY && streamTo[k - lowest - 1] <= cliffD + TIE;
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
            float share = 0, footShelf = 0, easedStep = 0;
            boolean step = false;
            if (weight > 0) {
                // A wall's talus is its own rock's, as today's (ChunkField.Wall): the ground at its foot lies level,
                // but under a stream hex's step. A plate's poured wall stands on the lower ground as today's paved
                // wall does: no step's face.
                boolean poured = plateFoot[k - lowest - 1];
                float talus = in || poured || !streamStep ? 0 : bankSlope(s, x, y);
                // Beside a stream hex's step, under a wall that stands nearer: the bank's slope by its own distance to
                // that step runs on under the wall (the higher of it and the wall's apron), so no scarp parts the two.
                float streamD = streamTo[k - lowest - 1] == Float.POSITIVE_INFINITY ? Float.POSITIVE_INFINITY
                      : (float) Math.sqrt(streamTo[k - lowest - 1]);
                if (!in && !streamStep && streamD < bankFoot(x, y, metre, room)) { talus = Math.max(talus, bankSlope(streamD, x, y)); }
                // Round a ground portal the face stands flat (BoardTunnel#relief) and, as today's, keeps no apron.
                if (talus > 0) {
                    float keep = relief(h, x, y, z);
                    easedStep = weight * talus * (1 - keep);
                    talus *= keep;
                }
                share = weight * (in ? 1 : talus);
                step = !in && !poured
                      && (streamStep && s < bankFoot(x, y, metre, room) || streamD < bankFoot(x, y, metre, room));
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
                    easedStep *= 1 - wet;
                    wall = Math.max(wall, wet * weight);
                }
            }
            if (weight < 1) {
                float sb = s + bankMix * (smoothDistance(shares, k, x, y, centres, count) - s);
                // A bank onto ground that keeps its outline lies whole on its own side, a room wide, its foot on the
                // outline at the lower level (#banksOnto): by its exact distance to that step, which the pavement's
                // fit can stand off the hexes' own outline the smoothed distance follows (FieldPlates#corner).
                float platedD = platedTo[k - lowest - 1];
                boolean whole = platedD != Float.POSITIVE_INFINITY && platedD <= bankTo[k - lowest - 1] + TIE;
                if (whole) { sb = (in ? -2 : 2) * (float) Math.sqrt(platedD) + room; }
                // Beside a stream hex's step a bank's band stands a transition's width further out (its top on the
                // outline, as the stream bank's): the two bank lines join in plan. Not a bank between joined waters.
                if (!joinedBank && streamTo[k - lowest - 1] != Float.POSITIVE_INFINITY) {
                    sb -= room * (1 - far((float) Math.sqrt(streamTo[k - lowest - 1])));
                }
                // The top level of banks stacked on one outline rounds its upper shoulder over a deeper band (#stackTop).
                if (sb < 0) { sb /= stackTop(bankTo, k - lowest - 1, span); }
                float banked = bank(sb, x, y);
                // Beside a stream hex's step the bank takes the stream bank's straight profile over a band a quarter wider
                // (whole within 6 m of the step, eased out to 12 m): the two banks turn their corner as one even slope.
                if (!joinedBank && streamTo[k - lowest - 1] != Float.POSITIVE_INFINITY) {
                    float beside = 1 - far((float) Math.sqrt(streamTo[k - lowest - 1]));
                    banked += (straight((sb + room) / (2 * room * (1 + .25f * beside))) - banked) * beside;
                }
                // Under a plate that slopes the bank is the plate's straight slope (BoardRelief#band's concrete step:
                // from the step's room inside its line to the room outside, half the step on the line), by the exact
                // distance to that line; whole where that step is the nearest bank and eased out over a metre beyond.
                // No step's face: the plate's top, as today's slope is its slab, with no rim or foot on it.
                boolean pavedLine = false;
                if (pavedTo[k - lowest - 1] != Float.POSITIVE_INFINITY) {
                    float pavedD = (float) Math.sqrt(pavedTo[k - lowest - 1]);
                    float paved = 1 - BoardRelief.smooth((pavedD - bankD) / metre);
                    banked += ((1 - Math.clamp((in ? -pavedD : pavedD) / room, -1, 1)) / 2 - banked) * paved;
                    pavedLine = paved > .5f;
                    if (pavedLine) { sb = in ? -pavedD : pavedD; }
                }
                share += (1 - weight) * banked;
                // Faded out as the step's weight is (far), so two hexes' step lists give a shared sample one height.
                footShelf += (joinedBank ? 0 : 1) * (1 - weight) * (1 - far(bankD))
                      * (in ? 1 - BoardRelief.smooth(Math.max(0, -sb - .95f * room) / room)
                            : 1 - BoardRelief.smooth(Math.max(0, sb - .95f * room) / run));
                // A bank's face spans its band, as today's does; the top cover's rim and foot distances run from its edges.
                step |= !pavedLine && Math.abs(sb) < .95f * room;
                boolean slab = pavedLine && Math.abs(sb) < room;
                // The lower half of a bank that lies whole on its own side is its foot's side, as the lower hex's.
                if (in && !slab && !(whole && sb > 0)) { bankRim = Math.min(bankRim, Math.max(0, -sb - .95f * room)); }
                else if (!slab) { bankFoot = Math.min(bankFoot, Math.max(0, sb - .95f * room)); }
            }
            // Below a step whose higher side is water the water pours in: a fall's foot keeps no shelf.
            shelf = Math.max(shelf, footShelf * (footShelf > 0 ? 1 - shore.upper(x, y, k) : 1));
            if (step) {
                role = 1;
                if (Math.abs(s) < band) { band = Math.abs(s); rock = weight; }
            }
            eased += easedStep;
            z += share * levelZ;
        }
        // The ground as it stood with the apron the portal eased (zt), carried through the layers below beside z: the
        // roads layer cuts z no deeper than it cut that.
        float zt = z + eased * levelZ;
        // Layer 2, shore and beds (FieldShore), before the roads; ground the shore lowers is bank and bed, no step's face.
        float bed = shore.bed(x, y, wall, Math.max(sill, shelf));
        // Land keeps above the water where no water plane reaches over it (WaterLayer#dip): no dry hollow below the
        // line. The hex at the point decides, so a point every hex shares gets one height. Water across a seam
        // (FieldShore#seamWater) is no land.
        Coords at = bed > 0 ? hexAt(x, y) : null;
        if (at != null && inside(at) && !WaterLayer.water(this, at) && !shore.seamWater(at)) {
            bed = Math.min(bed, WaterLayer.dip(this, at, x, y, z));
        }
        z -= bed;
        zt -= bed;
        if (bed > 0) { role = 0; }
        // Layer 3, the scree a ground tunnel's mouth is set in (FieldStamps#scree), before the roads: talus, shaded
        // as a cliff's apron is, at its foot (the ground material's debris).
        float scree = FieldStamps.scree(mouths(h), x, y);
        boolean talus = scree > z;
        zt = Math.max(zt, scree);
        if (talus) {
            z = scree;
            role = 1;
            rock = 1;
        }
        // Between a mouth's wings and in front of its face the ground stays at the opening's floor.
        float approach = FieldStamps.approach(mouths(h), packed(h), x, y);
        zt = Math.min(zt, approach);
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
        Coords here = hexAt(x, y);
        int edge = -1;
        if (!here.equals(h)) {
            float best = Float.POSITIVE_INFINITY;
            for (int e = 0; e < 6; e++) {
                Coords n = here.translated(BoardGeometry.edgeDirection(e));
                float ndx = x - centerX(n), ndy = y - centerY(n), away = ndx * ndx + ndy * ndy;
                // At here's corner two neighbours lie as near: h, one of them, decides, so a hex level with here reads
                // here's water there as here does (no seam at the corner), and h across a wall keeps its own.
                if (away < best * (1 - 1e-4f) || away <= best * (1 + 1e-4f) && n.equals(h)) {
                    best = away;
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
            // Beside water the eased ground keeps the roads' cut as it is.
            zt = z;
        }
        // A stream hex's gorge ends on today's waterline (FieldShore#waterline): within its bank slopes' reach of a
        // stream step, the ground at or below that stream hex's level stands no higher than the gorge's side, which
        // rises from the water's height on the line (level on the line itself, steepening over 1.5 m to a slope of 42
        // to 54 degrees by noise), keeps above every water plane that reaches over the point, and meets the ground
        // above in a rounded shoulder: the stream hex's own strip beside its chute, and the lower hexes beside the
        // pool. Ground less than half a metre above the water keeps its bank; what stands above the stream hex's level
        // (a higher neighbour's cliff, its talus) keeps its height over that side. Ground the side lowers is a step's
        // face under the stream hex, and level land within 3 m of the line below the slope's foot is marked for its
        // rock (#gorgeGround): the gorge's rock down to the water (FieldCover#gorge). The point alone decides, so
        // hexes sharing a sample give it one height.
        // Beside a corner where a wall ends against banks the ground rises to the wall's rim: its nose of rock, the
        // cliff's own (#wallEndNose).
        float[] nose = wallEndNose(h, here, x, y);
        if (nose != null && z < nose[0]) {
            float up = (nose[0] - z) * nose[1];
            z += up;
            zt += up;
            if (up > .25f * metre) {
                role = 1;
                rock = Math.max(rock, nose[1]);
                nearTop = (int) nose[2];
            }
        }
        float[] streams = streamsNear(here, x, y);
        float[] line = streams == null ? null : new float[6];
        float reach = bankFoot(x, y, metre, room) + 2 * metre, cut = 0, plane = Float.NaN;
        for (int i = 0; streams != null && i < streams.length; i += 2) {
            float within = 1 - BoardRelief.smooth((streams[i] - reach) / (3 * metre));
            if (within <= 0) { continue; }
            if (line[4] == 0 && line[5] == 0 && !shore.waterline(x, y, line)) { break; }
            if (line[0] >= 2 * metre) { break; }
            if (Float.isNaN(plane)) { plane = planeOver(here, x, y); }
            boolean land = line[0] < 0;
            float from = Math.max(0, -line[0]), blend = 1.5f * metre;
            float slope = .9f + .5f * BoardRelief.noise(x / (8 * metre) + 3.7f, y / (8 * metre) - 5.2f);
            // Small ledges on the side, none at the line. Just inside the line (the polygon's, where the shore's
            // grid still reads land) the side is the water's height, as on the line.
            float ledge = (BoardRelief.noise(x / (2.5f * metre) + 4.4f, y / (2.5f * metre) - 9.2f) - .5f) * .8f * metre
                  * BoardRelief.smooth(from / (2 * blend));
            float side = Math.max(line[3] + slope * (from < blend ? from * from / (2 * blend) : from - blend / 2) + ledge,
                  plane + (.05f + .2f * BoardRelief.smooth(from / blend)) * metre);
            // The stream hex's own ground and what lies below it; the shoulder's rounding, none on the line itself
            // (flat ground at the water's height stays).
            int top = (int) streams[i + 1];
            float level = level(unpacked(top)) * levelZ, under = Math.min(z, level), radius = Math.min(metre, .5f * from);
            float round = radius <= 0 ? 0 : Math.max(radius - Math.abs(under - side), 0) / radius;
            float deep = BoardRelief.smooth((under - line[3] - .5f * metre) / metre) * (1 - BoardRelief.smooth(line[0] / metre - 1));
            float lower = within * deep * Math.max(0, under - Math.min(under, side) + round * round * radius / 4);
            cut = Math.max(cut, lower);
            if (within > .5f && lower > .15f * metre) {
                role = 1;
                rock = 1;
                nearTop = top;
            } else if (role < .5f && land && from < 3 * metre && streams[i] < reach && z <= level - metre) {
                nearTop = gorgeGround(top);
            }
        }
        z -= cut;
        zt -= cut;
        // A road's bank cuts the eased ground no deeper than it cut the apron's (BoardRoadBank leaves ground above a road's
        // reach): earth the road did not reach before stays, retained by the portal's wing.
        out[1] = 0;
        z = crestUnder(h, x, y, z, out);
        z = poolRim(h, x, y, z, out);
        // A fall's dry rim is the falling hex's rock (#crestUnder), as is a pool's rim over a wall (#poolRim).
        if (out[1] > 0) {
            role = 1;
            rock = 1;
            nearTop = packed(h);
            // Marked for the mesh (ChunkMesh: a step face's samples use no step count).
            nearLevels = -1;
        }
        // A stream hex's step a containment wall retains by a road is today's earth bank (FieldRoads#retainedBelow).
        Coords upper = rock > 0 ? unpacked(nearTop) : null;
        Coords lower = upper == null ? null : roads.retainedBelow(upper, x, y);
        if (lower != null && stream(upper, lower)) { rock = 0; }
        float ground = roads.height(h, x, y, z);
        if (zt > z && ground < z) { ground = Math.max(ground, Math.min(z, roads.height(h, x, y, zt))); }
        // Reserved ground is its road's surface: a top, as today's is (BoardRoadCut#mesh).
        boolean road = roads.reserved(h, x, y);
        if (road) { ground = rampBand(ground, x, y); }
        out[0] = ground;
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
