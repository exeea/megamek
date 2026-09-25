/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** The actual topography of one hex. Rendering, road continuity, banks and picking share it. */
final class BoardSurface {
    /** GL-view-owned geometry. Artwork snapshots retain surfaces; shape changes are checked once per generation. */
    static final class Cache {
        private static final int CAPACITY = 256;
        private static final class Entry {
            final Key key;
            final BoardSurface surface;
            long generation;

            Entry(Key key, BoardSurface surface, long generation) {
                this.key = key;
                this.surface = surface;
                this.generation = generation;
            }
        }

        private List<BoardScene.Tile> tiles;
        private int revision = -1;
        private int boardId = -1;
        private int width;
        private int height;
        private long generation;
        private final Map<Coords, Entry> surfaces = new LinkedHashMap<>(32, .75f, true);

        BoardSurface get(BoardScene scene, BoardScene.Tile tile) {
            if (boardId != scene.boardId() || width != scene.width() || height != scene.height()
                  || revision != BoardGeometry.revision()) {
                clear();
                boardId = scene.boardId();
                width = scene.width();
                height = scene.height();
                revision = BoardGeometry.revision();
            }
            if (tiles != scene.tiles()) {
                tiles = scene.tiles();
                generation++;
            }
            Entry entry = surfaces.get(tile.coords());
            if (entry == null || entry.generation != generation) {
                Key key = geometryKey(scene, tile);
                if (entry == null || !entry.key.equals(key)) {
                    entry = new Entry(key, new BoardSurface(scene, tile), generation);
                    surfaces.put(tile.coords(), entry);
                } else {
                    entry.generation = generation;
                }
            }
            if (surfaces.size() > CAPACITY) { surfaces.remove(surfaces.keySet().iterator().next()); }
            return entry.surface;
        }

        void clear() { surfaces.clear(); tiles = null; }
    }

    /** Only inputs that alter topology; the ramp mask also captures second-ring road/bridge approaches. */
    record Geometry(int elevation, int waterDepth, boolean frozen, int roadExits, BoardScene.Surface surface,
          BoardLiquid liquid, boolean detailedGround, List<BoardScene.Feature> features, int ramps) { }

    /** What of a hex further out can reach a hex's shape: through the water's shore, its level, liquid and ground. */
    record Shape(int elevation, int waterDepth, BoardLiquid liquid, boolean detailedGround, BoardScene.Surface surface,
          int roadExits) { }

    /** A hex's own geometry and its six neighbours', and the shapes of the hexes out to {@link #SHORE_RINGS}. */
    record Key(List<Geometry> near, List<Shape> far) { }

    /**
     * How many hexes away a hex's shape can move another's water shore, its banks or the corners the shore moves (see
     * {@link BoardRelief#shore}): the field reaches about two hexes, each hex's pull depends on its neighbours, and the
     * land round a corner allows its move by the moves of all its own corners.
     */
    static final int SHORE_RINGS = 6;

    /**
     * Own shape followed by the six neighboring shapes, a missing board neighbor as a null entry; then the shapes of the
     * hexes out to {@link #SHORE_RINGS}, beyond the board's edge the nearest board hex's, as the shore takes them.
     */
    static Key geometryKey(BoardScene scene, BoardScene.Tile tile) {
        List<Geometry> near = new ArrayList<>(7);
        near.add(geometry(scene, tile));
        for (int direction = 0; direction < 6; direction++) {
            near.add(geometry(scene, scene.tile(tile.coords().translated(direction))));
        }
        List<Shape> far = new ArrayList<>();
        Coords at = tile.coords();
        for (int x = at.getX() - SHORE_RINGS; x <= at.getX() + SHORE_RINGS; x++) {
            for (int y = at.getY() - SHORE_RINGS - 1; y <= at.getY() + SHORE_RINGS + 1; y++) {
                int distance = at.distance(x, y);
                if (distance < 2 || distance > SHORE_RINGS) { continue; }
                BoardScene.Tile other = scene.tile(new Coords(Math.clamp(x, 0, scene.width() - 1),
                      Math.clamp(y, 0, scene.height() - 1)));
                far.add(new Shape(other.elevation(), other.waterDepth(), other.liquid(), other.detailedGround(),
                      other.surface(), other.roadExits()));
            }
        }
        return new Key(Collections.unmodifiableList(near), List.copyOf(far));
    }

    private static Geometry geometry(BoardScene scene, BoardScene.Tile tile) {
        return tile == null ? null : new Geometry(tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
              tile.surface(), tile.liquid(), tile.detailedGround(), tile.features(), ramps(scene, tile));
    }

    static int ramps(BoardScene scene, BoardScene.Tile tile) {
        int result = 0;
        for (int direction = 0; direction < 6; direction++) {
            if (roadEdgeElevation(tile, scene.tile(tile.coords().translated(direction)), direction) != tile.elevation()) {
                result |= 1 << direction;
            }
        }
        return result;
    }

    /**
     * Whether a river that runs out at the board's edge pours off it as a fall. Otherwise it runs on into the edge and
     * is cut off there with the board, like a slice of cake, as all other water at the board's edge is.
     */
    static final boolean FALLS_OFF_THE_BOARD = false;
    /** Levels a fall off the board's edge drops through as it fades out: nothing lies below the board to catch it. */
    private static final float BOTTOMLESS_LEVELS = 3;
    /** Waterline points per edge; crests, the walls beneath them and the sheets poured over them share them. */
    static final int SHORE_SEGMENTS = 12;
    /** How many times finer than its points a bank's waterline is traced before they are spaced along it. */
    private static final int BANK_TRACE = 4;
    /**
     * The wet margin, in hex-scale units, the water keeps beyond any land that reaches into its hex: the foot of a
     * slope, the steps and fillet through a corner, and a mouth's ends. The river hugs the slopes it runs between.
     */
    private static final float HUG = 1.5f;
    /** How far, in hex-scale units, a bank keeps the water in from its edge where no slope runs up from the water. */
    private static final float BEACH = 8;
    /** How far, in hex-scale units, a pool swells out round the foot of a fall that pours into it. */
    private static final float PLUNGE_POOL = 6;
    /** The bank, in hex-scale units, the water keeps inside its outline beside land at its own level. */
    private static final float SHORE_BANK = 4.5f;
    /** Rounding, in hex-scale units, at bank corners and across a bank beside higher natural ground. */
    private static final float SHORE_ROUND = 6;
    /** The shore's search along a ray: even steps to the first dry one, then halvings of that step. */
    private static final int SHORE_STEPS = 8;
    private static final int SHORE_HALVINGS = 12;
    /**
     * How much of a beach, in hex-scale units, an open mouth gives up beside a bank where no shore moves the corner,
     * and below a fall, where the pool reaches nearly to the corners so the whole sheet lands in water.
     */
    private static final float MOUTH_OPENING = 4;
    private static final float PLUNGE_OPENING = 6.5f;
    /** How far a fall's lip juts out unevenly over the pool below, at most, in hex-scale units. */
    private static final float LIP_JUT = 3.5f;
    /** A fall's lip: the water stops this far short of its crest so the sheet can curve down over it. */
    private static final float FALL_LIP_WIDTH = .045f;
    private static final float FALL_LIP_DROP = .5f;
    /** Share of each ray from the waterline to the hex centre over which a bed descends; the rest is its plateau. */
    private static final float PLATEAU = .75f;
    /**
     * Water depth over a fall's lip, in hex-scale units: the bed rises to a ledge the water pours over, so the wall
     * beneath the fall closes up to just under the sheet. No pool is shallower, so both sides of a corner agree.
     */
    private static final float LIP_DEPTH = 1;
    /**
     * How far out over the pool below the crest bows where the falls of two neighbouring pools meet around it, along
     * the edge the pools share, in hex-scale units: the crest rounds the corner instead of folding into a V. Far enough
     * that the crests beside it never pass inside their hexes; no crest reaches further out.
     */
    static final float VALLEY = 7;

    /** Visual water and bank controls; applied on the GL thread before rebuilding terrain and support surfaces. */
    record Tuning(boolean fallsOffBoard, float bottomlessLevels, float hug, float beach, float plungePool,
          float shoreBank, float shoreRound, float mouthOpening, float plungeOpening, float lipJut,
          float fallLipWidth, float fallLipDrop, float plateau, float lipDepth, float valley) {
        Tuning {
            for (float value : new float[] { bottomlessLevels, hug, beach, plungePool, shoreBank, shoreRound,
                  mouthOpening, plungeOpening, lipJut, fallLipWidth, fallLipDrop, plateau, lipDepth, valley }) {
                if (!Float.isFinite(value) || value < 0) { throw new IllegalArgumentException("Invalid water tuning"); }
            }
            // Zero shore width denotes an open mouth, so closed banks must retain a positive margin.
            if (bottomlessLevels == 0 || hug == 0 || beach == 0 || shoreRound == 0 || fallLipWidth > .5f || fallLipDrop > 1
                  || plateau <= 0 || plateau >= 1) {
                throw new IllegalArgumentException("Invalid water tuning");
            }
            mouthOpening = Math.min(mouthOpening, beach);
            plungeOpening = Math.min(plungeOpening, beach);
        }
    }

    static final Tuning DEFAULTS = new Tuning(FALLS_OFF_THE_BOARD, BOTTOMLESS_LEVELS, HUG, BEACH, PLUNGE_POOL,
          SHORE_BANK, SHORE_ROUND, MOUTH_OPENING, PLUNGE_OPENING, LIP_JUT, FALL_LIP_WIDTH, FALL_LIP_DROP,
          PLATEAU, LIP_DEPTH, VALLEY);
    private static Tuning tuning = DEFAULTS;

    static Tuning tuning() { return tuning; }

    static void tune(Tuning next) {
        if (next.equals(tuning)) { return; }
        tuning = next;
        BoardGeometry.terrainChanged();
    }
    /** DRESSING is thin detail on the ground, such as tree pits: drawn and picked, never raising what stands there. */
    enum Finish { TOP, RIM, CAP, WALL, OUTCROP, SHORE, BED, BANK, ICE, DRESSING }
    /** landEdge is the edge a bank or wall face stands on (bank artwork takes the hex across it); else -1. */
    record Face(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge) {
        Face(Vector3 a, Vector3 b, Vector3 c, Finish finish) {
            this(a, b, c, finish, -1);
        }

        /** Height of the face above a point, or negative infinity when the point lies outside it. */
        float height(float x, float y) {
            float tolerance = .001f * BoardGeometry.HEX_SCALE;
            if (x < Math.min(a.x, Math.min(b.x, c.x)) - tolerance
                  || x > Math.max(a.x, Math.max(b.x, c.x)) + tolerance
                  || y < Math.min(a.y, Math.min(b.y, c.y)) - tolerance
                  || y > Math.max(a.y, Math.max(b.y, c.y)) + tolerance) { return Float.NEGATIVE_INFINITY; }
            float denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
            if (Math.abs(denominator) < 0.00001f) {
                return Float.NEGATIVE_INFINITY;
            }
            float u = ((b.y - c.y) * (x - c.x) + (c.x - b.x) * (y - c.y)) / denominator;
            float v = ((c.y - a.y) * (x - c.x) + (a.x - c.x) * (y - c.y)) / denominator;
            float w = 1 - u - v;
            // World-space tolerance keeps rounded edge samples on thin bank triangles even far from the origin.
            // A fixed barycentric tolerance can miss the bank and drop the exposed wall to the riverbed.
            float squared = tolerance * tolerance / (denominator * denominator);
            if ((u >= 0 || u * u <= squared * (square(b.x - c.x) + square(b.y - c.y)))
                  && (v >= 0 || v * v <= squared * (square(c.x - a.x) + square(c.y - a.y)))
                  && (w >= 0 || w * w <= squared * (square(a.x - b.x) + square(a.y - b.y)))) {
                u = Math.max(0, u);
                v = Math.max(0, v);
                w = Math.max(0, w);
                return (u * a.z + v * b.z + w * c.z) / (u + v + w);
            }
            return Float.NEGATIVE_INFINITY;
        }
    }
    record Side(Vector3 a, Vector3 b, float lowA, float lowB, int edge) { }

    /**
     * The crest a fall pours over, at the pool's surface: a smooth curve across its mouth from {@code a} to {@code b}.
     * A free end meets its bank on the edge and leaves along it. Where the next fall carries on, both crests pass one
     * point with one tangent (see {@link #join}): the hex's own corner at a prow, and {@link #VALLEY} out over the pool
     * below where two pools' falls meet around it. A fall that runs on over several edges so curves round them without
     * a corner, and the curve never passes inside its hex: it only ever bows out over the pool it falls into, whose hex
     * it stays in. {@code corner} and {@code outward} are the edge's start and outward normal; {@code jut} how far a
     * free-standing fall's lip may jut out unevenly over the pool below, in world units.
     */
    record Crest(Vector3 a, Vector3 b, Vector3 startTangent, Vector3 endTangent, Vector3 corner, Vector3 outward,
          float jut, float level) {
        /** The point at {@code s}, from 0 at {@code a} to 1 at {@code b}. */
        Vector3 point(float s) {
            if (s <= 0) { return new Vector3(a); }
            if (s >= 1) { return new Vector3(b); }
            float chord = chord(), s2 = s * s, s3 = s2 * s;
            Vector3 p = new Vector3(a).scl(2 * s3 - 3 * s2 + 1).mulAdd(startTangent, chord * (s3 - 2 * s2 + s))
                  .mulAdd(b, 3 * s2 - 2 * s3).mulAdd(endTangent, chord * (s3 - s2));
            p.z = mouthDepth(p, a, a.z, b, b.z, level);
            if (jut > 0) {
                // The lip juts out unevenly over the pool below, as ledges break away from a real one; its ends, on
                // the banks, stay put.
                float m = BoardRelief.metres(1);
                float broken = .6f * BoardRelief.gradient(p.x / (3 * m) + 7.1f, p.y / (3 * m) - 2.3f)
                      + .4f * BoardRelief.gradient(p.x / (1.2f * m) - 4.4f, p.y / (1.2f * m) + 1.9f);
                p.mulAdd(normal(s), jut * Math.clamp(.5f + .6f * broken, 0, 1) * 16 * s2 * (1 - s) * (1 - s));
            }
            float inside = new Vector3(corner.x - p.x, corner.y - p.y, 0).dot(outward);
            return inside > 0 ? p.mulAdd(outward, inside) : p;
        }

        /** The unit direction of the crest at {@code s}, from {@code a} toward {@code b}. */
        Vector3 tangent(float s) {
            if (s <= 0) { return new Vector3(startTangent); }
            if (s >= 1) { return new Vector3(endTangent); }
            float chord = chord(), s2 = s * s;
            Vector3 t = new Vector3(a).scl(6 * s2 - 6 * s).mulAdd(startTangent, chord * (3 * s2 - 4 * s + 1))
                  .mulAdd(b, 6 * s - 6 * s2).mulAdd(endTangent, chord * (3 * s2 - 2 * s));
            t.z = 0;
            return t.nor();
        }

        /** The unit normal of the crest at {@code s}, level and away from the pool: the way the water falls. */
        Vector3 normal(float s) {
            Vector3 t = tangent(s);
            return new Vector3(t.y, -t.x, 0);
        }

        private float chord() {
            return (float) Math.hypot(b.x - a.x, b.y - a.y);
        }
    }

    final BoardScene.Tile tile;
    private final BoardScene scene;
    private final BoardSurface[] receivingWater = new BoardSurface[6];
    final List<Face> faces = new ArrayList<>();
    final List<Vector3> water = new ArrayList<>();
    /**
     * The water's full outline within its hex, before a falling mouth pulls its surface back to the crest. Segments on
     * open mouths continue into the next pool; every other segment is a bank.
     */
    final List<Vector3> outline = new ArrayList<>();
    final List<Face> waterFaces = new ArrayList<>();
    /** The water's cut face where the board's edge cuts it off, facing out; drawn like its surface. */
    final List<Face> cutFaces = new ArrayList<>();
    /** Edges a fall pours over off the board's edge, as a bit mask; see {@link #bottomless}. */
    private int offBoard;
    final List<Side> waterfalls = new ArrayList<>();
    final int ramps;
    final BoardRelief relief;
    private final List<Face> edgeTopography = new ArrayList<>();
    private List<Face> wallFaces;
    private float wallFloor = Float.NaN;
    /**
     * Open mouths by edge: connected liquid continues there, so no bank rises across that edge and a wall exposed
     * on it starts at the bed rather than at the hex's own top.
     */
    private int openMouths;
    private boolean gradedWater;
    /** The faces of a sculpted bed that stay clear of the hex outline, as a range of {@link #faces}. */
    private int interiorFrom, interiorTo;
    /**
     * Per falling edge, the crest's normal at its first and last corner where another fall of the same drop carries
     * on, from this pool or from the neighbouring one; null where the end is free. See {@link #join}.
     */
    private final Vector3[] joinA = new Vector3[6];
    private final Vector3[] joinB = new Vector3[6];
    /** Per falling edge, the crest its water pours over; null elsewhere. */
    private final Crest[] crests = new Crest[6];
    /**
     * The bed's outer ring, along the waterline and out along the crests: the top of the walls that stand under falls.
     */
    private Vector3[] bedOutline;
    /** A water hex's waterline, frozen or not, which its sculpted banks run down to; null on land. */
    private Vector3[] waterline;
    /** The waterline with each falling mouth moved out to its crest; null on land. */
    private Vector3[] crestLine;
    private final Vector3 center;
    private final Vector3[] corners = new Vector3[6];
    /** A water hex's outline corners at its level: its corners as the shore moves them; null on land. */
    private Vector3[] shoreCorners;
    /** The bank and corner clearances that keep a water hex's shore away from the surrounding land. */
    private List<Bank> banks;

    /** A bank's inward clearance. The shared stream field supplies the curve across hex boundaries. */
    private record Bank(float x, float y, float nx, float ny, float width) {
        float clearance(float px, float py) {
            float dx = px - x, dy = py - y;
            return dx * nx + dy * ny - width;
        }
    }

    BoardSurface(BoardScene scene, BoardScene.Tile tile) {
        this(scene, tile, true);
    }

    private BoardSurface(BoardScene scene, BoardScene.Tile tile, boolean detailed) {
        this.scene = scene;
        this.tile = tile;
        center = BoardGeometry.center(tile.coords(), tile.elevation());
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
        }
        ramps = ramps(scene, tile);
        // The relief first: a water hex lays out its waterline round the steps the relief puts beside it.
        relief = new BoardRelief(scene, tile, ramps);
        if (tile.liquid().present()) {
            river(scene);
        } else if (ramps != 0) {
            road(scene);
        } else {
            // As the shore moves its corners, which a flat top stands in for when its neighbours seek their walls.
            Vector3[] outline = new Vector3[6];
            for (int k = 0; k < 6; k++) { outline[k] = moved(k); }
            fan(outline, center.z, Finish.TOP);
        }
        for (int i = 0; i < faces.size(); i++) {
            // Only faces reaching the hex outline can meet a neighbour's: a bed's inner rings never do.
            if (i < interiorFrom || i >= interiorTo) { edgeTopography.add(faces.get(i)); }
        }
        if (detailed) {
            int falls = 0;
            for (int edge = 0; edge < 6; edge++) { falls |= crests[edge] == null ? 0 : 1 << edge; }
            // Banks meet the actual ground rim, including emerged shallow bars, while the water keeps its level.
            relief.top(faces, bedOutline, openMouths, crestLine, falls);
        }
    }

    /** A bridge approach reaches the deck at the edge; ordinary roads share their height change across both hexes. */
    static float roadEdgeElevation(BoardScene.Tile tile, BoardScene.Tile neighbor, int direction) {
        if (neighbor == null || tile.liquid().present()) {
            return tile.elevation();
        }
        var bridge = connectingBridge(tile, neighbor, direction);
        if (bridge != null) {
            return neighbor.elevation() + bridge.elevation();
        }
        return hasRoadApproach(tile, neighbor, direction)
              ? (tile.elevation() + neighbor.elevation()) / 2f : tile.elevation();
    }

    /** Presentation only: a road end can meet unpaved ground across at most two levels. */
    static boolean hasRoadApproach(BoardScene.Tile tile, BoardScene.Tile neighbor, int direction) {
        if (neighbor == null || tile.liquid().present() || neighbor.liquid().present()) {
            return false;
        }
        boolean exit = (tile.roadExits() & (1 << direction)) != 0;
        int reverse = (direction + 3) % 6;
        boolean continuation = (neighbor.roadExits() & (1 << reverse)) != 0;
        // A bridge approach changes only the road hex, leaving the ground beneath the deck intact.
        if (connectingBridge(tile, neighbor, direction) != null
              || connectingBridge(neighbor, tile, reverse) != null) {
            return false;
        }
        return (exit && continuation)
              || ((exit || continuation) && Math.abs(tile.elevation() - neighbor.elevation()) <= 2);
    }

    private static BoardScene.Feature connectingBridge(BoardScene.Tile road, BoardScene.Tile bridge, int direction) {
        if (road.liquid().present() || (road.roadExits() & (1 << direction)) == 0) {
            return null;
        }
        int reverse = (direction + 3) % 6;
        boolean continuation = !bridge.liquid().present() && (bridge.roadExits() & (1 << reverse)) != 0;
        if (continuation && road.elevation() == bridge.elevation()) {
            return null;
        }
        // A level deck wins over a sloped ground road; a sloped deck is the last connected-road fallback.
        // Captured bridge arms point north before rotation; hex directions run clockwise.
        return bridge.features().stream().filter(feature -> feature.asset().equals("bridge")
              && Math.abs(bridge.elevation() + feature.elevation() - road.elevation()) <= 1
              && (!continuation || bridge.elevation() + feature.elevation() == road.elevation())
              && Math.floorMod(Math.round(-feature.rotation() / 60), 6) == reverse).findFirst().orElse(null);
    }

    private void river(BoardScene scene) {
        float[] shore = new float[6];
        float[] lip = new float[6];
        // Levels the land beside each bank rises above this hex; none at a mouth or the board's edge.
        float[] rise = new float[6];
        // Mouths a fall pours in over: the pool below opens wide and deep round its foot.
        boolean[] plunge = new boolean[6];
        // Water runs on into the board's edge: a river that runs out there pours off it, ahead of where it comes from;
        // other water is cut off with the board.
        int source = tuning.fallsOffBoard() && !tile.frozen() ? riverEnd(scene) : -1;
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            boolean bank = neighbor != null && !tile.liquid().connects(neighbor.liquid());
            // A bank; where a slope beside it reaches into the hex, only a wet margin where the water meets it. A slope
            // up from the water runs on under it to the bed.
            shore[edge] = !bank ? 0 : least(edge);
            rise[edge] = shore[edge] > 0 ? neighbor.elevation() - tile.elevation() : 0;
            if (shore[edge] == 0) {
                openMouths |= 1 << edge;
                // A fall owns the last stretch of its mouth: the water stops short so the sheet can curve down.
                if (neighbor == null) {
                    if (source >= 0 && Math.abs(Math.floorMod(edge - source, 6) - 3) <= 1) {
                        offBoard |= 1 << edge;
                        lip[edge] = fallLip(BoardGeometry.waterZ(tile),
                              BoardGeometry.waterZ(tile) - tuning.bottomlessLevels() * BoardGeometry.LEVEL);
                    }
                } else if (!tile.frozen() && !neighbor.frozen() && tile.elevation() > neighbor.elevation()
                      && !waterSlope(tile, neighbor)) {
                    lip[edge] = fallLip(BoardGeometry.waterZ(tile), BoardGeometry.waterZ(neighbor));
                }
                plunge[edge] = neighbor != null && !tile.frozen() && !neighbor.frozen()
                      && neighbor.elevation() > tile.elevation() && !waterSlope(tile, neighbor);
            }
        }
        waterline = shoreline(scene, shore, rise, plunge, BoardGeometry.waterZ(tile));
        gradeWaterline(scene, shore);
        for (int edge = 0; edge < 6; edge++) {
            if (lip[edge] > 0) {
                joinA[edge] = join(scene, shore, lip, edge, (edge + 5) % 6);
                joinB[edge] = join(scene, shore, lip, edge, (edge + 1) % 6);
            }
        }
        // The pool reaches out to the crests its falls pour over, which curve round the corners the falls share.
        crestLine = waterline.clone();
        for (int edge = 0; edge < 6; edge++) {
            if (lip[edge] > 0) {
                crests[edge] = crest(waterline, lip, edge);
                for (int i = 0; i <= SHORE_SEGMENTS; i++) {
                    crestLine[(edge * SHORE_SEGMENTS + i) % crestLine.length] =
                          crests[edge].point(i / (float) SHORE_SEGMENTS);
                }
            }
        }
        basin(scene, waterline, crestLine, shore, rise, plunge);
        for (int edge = 0; edge < 6; edge++) {
            if (shore[edge] == 0) {
                continue; // An open river mouth has no bank or wall across it.
            }
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                int index = edge * SHORE_SEGMENTS + segment;
                int next = (index + 1) % waterline.length;
                Vector3 a = new Vector3(shoreCorners[edge]).lerp(shoreCorners[(edge + 1) % 6],
                      segment / (float) SHORE_SEGMENTS);
                Vector3 b = new Vector3(shoreCorners[edge]).lerp(shoreCorners[(edge + 1) % 6],
                      (segment + 1f) / SHORE_SEGMENTS);
                Vector3 lipA = shoreLip(bedOutline[index], a);
                Vector3 lipB = shoreLip(bedOutline[next], b);
                quad(a, b, lipB, lipA, Finish.TOP, edge);
                quad(lipA, lipB, bedOutline[next], bedOutline[index], Finish.SHORE, edge);
            }
        }
        if (tile.frozen()) {
            // The ice covers the hex as the shore moves its corners.
            fan(shoreCorners, center.z, Finish.ICE);
        } else {
            // The water recedes from each crest; the sheet's lip curves back down over it.
            Vector3[] surface = pulledBack(crestLine, lip);
            outline.addAll(List.of(waterline));
            water.addAll(List.of(surface));
            if (gradedWater) { slopingSurface(surface); }
            else { polygon(surface, Finish.TOP, waterFaces); }
            for (int edge = 0; edge < 6; edge++) {
                BoardScene.Tile other = neighbor(scene, edge);
                if (crests[edge] != null) {
                    // Off the board's edge a fall drops into nothing and fades out on its way down.
                    float bottom = other == null ? BoardGeometry.waterZ(tile) - tuning.bottomlessLevels() * BoardGeometry.LEVEL
                          : BoardGeometry.waterZ(other);
                    waterfalls.add(new Side(new Vector3(crests[edge].a()), new Vector3(crests[edge].b()), bottom,
                          bottom, edge));
                } else if (other == null) {
                    cut(edge);
                }
            }
        }
    }

    /** Water steps use surface levels, never the depths of their beds. */
    static boolean waterSlope(BoardScene.Tile a, BoardScene.Tile b) {
        return a != null && b != null && !a.frozen() && !b.frozen() && !a.liquid().molten()
              && a.liquid().present() && a.liquid().connects(b.liquid())
              && Math.abs(a.elevation() - b.elevation()) <= 2;
    }

    /** Both halves of an open mouth share its height and ease back to their own centre's water level. */
    private void gradeWaterline(BoardScene scene, float[] shore) {
        if (tile.frozen() || tile.liquid().molten()) { return; }
        float base = BoardGeometry.waterZ(tile);
        float[] ends = new float[6];
        for (int k = 0; k < 6; k++) {
            int previous = (k + 5) % 6;
            if (shore[previous] == 0 && shore[k] == 0) {
                List<BoardScene.Tile> joined = cornerWater(scene, k);
                float sum = 0;
                for (BoardScene.Tile other : joined) { sum += BoardGeometry.waterZ(other); }
                ends[k] = sum / joined.size();
            } else {
                BoardScene.Tile other = shore[previous] == 0 ? neighbor(scene, previous)
                      : shore[k] == 0 ? neighbor(scene, k) : null;
                ends[k] = waterSlope(tile, other) ? (base + BoardGeometry.waterZ(other)) / 2 : base;
            }
        }
        for (int e = 0; e < 6; e++) {
            BoardScene.Tile other = neighbor(scene, e);
            float middle = shore[e] == 0 && waterSlope(tile, other) ? (base + BoardGeometry.waterZ(other)) / 2 : base;
            Vector3 a = waterline[e * SHORE_SEGMENTS], b = waterline[(e + 1) * SHORE_SEGMENTS % waterline.length];
            for (int i = 0; i < SHORE_SEGMENTS; i++) {
                Vector3 point = waterline[e * SHORE_SEGMENTS + i];
                point.z = mouthDepth(point, a, ends[e], b, ends[(e + 1) % 6], middle);
                gradedWater |= Math.abs(point.z - base) > .0001f;
            }
        }
    }

    /** Water connected by gentle steps at this corner, in a canonical order for both water and bed heights. */
    private List<BoardScene.Tile> cornerWater(BoardScene scene, int index) {
        List<BoardScene.Tile> joined = new ArrayList<>(List.of(tile));
        for (int pass = 0; pass < 2; pass++) {
            for (int e : new int[] { (index + 5) % 6, index }) {
                BoardScene.Tile other = neighbor(scene, e);
                if (other == null || joined.contains(other)) { continue; }
                if (joined.stream().anyMatch(t -> waterSlope(t, other))) { joined.add(other); }
            }
        }
        joined.sort(java.util.Comparator.comparingInt((BoardScene.Tile t) -> t.coords().getX())
              .thenComparingInt(t -> t.coords().getY()));
        return joined;
    }

    /** A sampled slope, with a level centre for units and the same rings used by the riverbed beneath it. */
    private void slopingSurface(Vector3[] outer) {
        Vector3[] ring = outer;
        float base = BoardGeometry.waterZ(tile);
        for (int k = 1; k <= 4; k++) {
            float x = k / 4f, scale = 1 - tuning.plateau() * x;
            Vector3[] inner = new Vector3[outer.length];
            for (int i = 0; i < outer.length; i++) {
                inner[i] = new Vector3(center.x + (outer[i].x - center.x) * scale,
                      center.y + (outer[i].y - center.y) * scale, base + (outer[i].z - base) * ease(x));
            }
            for (int i = 0; i < outer.length; i++) {
                int j = (i + 1) % outer.length;
                waterFaces.add(new Face(ring[i], ring[j], inner[j], Finish.TOP));
                waterFaces.add(new Face(ring[i], inner[j], inner[i], Finish.TOP));
            }
            ring = inner;
        }
        Vector3 anchor = new Vector3(center.x, center.y, base);
        for (int i = 0; i < ring.length; i++) {
            waterFaces.add(new Face(anchor, ring[i], ring[(i + 1) % ring.length], Finish.TOP));
        }
    }

    float waterHeight(float x, float y) {
        float height = BoardGeometry.waterZ(tile);
        if (!gradedWater) { return height; }
        float drawn = sampleHeight(waterFaces, x, y, Float.NaN);
        if (Float.isFinite(drawn)) { return drawn; }
        // Bank vertices lie outside the water mesh. Extend the nearest shore's height for their wetness and bed
        // tint; reverting to the hex's level painted dry banks beside a descending stream as deep water.
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < waterline.length; i++) {
            Vector3 a = waterline[i], b = waterline[(i + 1) % waterline.length];
            float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
            float t = length == 0 ? 0 : Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / length, 0, 1);
            float distance = square(x - a.x - t * dx) + square(y - a.y - t * dy);
            if (distance < nearest) {
                nearest = distance;
                height = BoardRelief.lerp(a.z, b.z, t);
            }
        }
        return height;
    }

    /**
     * Whitewater gathers after a stream starts descending and settles as it levels out. The upper hex owns the
     * entire slope's effect, sampled at the drawn water height, so neither hex spreads foam over its level approach.
     */
    float slopeAgitation(Vector3 point) {
        return slopeEffects(point, null);
    }

    /** The upper surface owns each descent's whitewater and added downhill velocity, in hex widths per second. */
    float slopeEffects(Vector3 point, Vector2 acceleration) {
        if (!gradedWater) { return 0; }
        float top = BoardGeometry.waterZ(tile), agitation = 0;
        if (point.z >= top) { return 0; }
        boolean inside = BoardGeometry.contains(tile.coords(), point.x, point.y);
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (!waterSlope(tile, other) || other.elevation() >= tile.elevation()) { continue; }
            float dx = BoardGeometry.centerX(other.coords()) - center.x;
            float dy = BoardGeometry.centerY(other.coords()) - center.y;
            float length2 = dx * dx + dy * dy;
            float along = ((point.x - center.x) * dx + (point.y - center.y) * dy) / length2;
            float drop = tile.elevation() - other.elevation();
            float descended = (top - point.z) / (drop * BoardGeometry.LEVEL);
            if (acceleration != null && along > 0) {
                // Gain speed from the actual lost surface height, never the riverbed depth. Carry that momentum
                // through the foot, then ease it away inside the receiving hex. The rounded corridor also keeps
                // unrelated nearby streams out of this descent and slows the current toward the banks.
                float across = ((point.x - center.x) * dy - (point.y - center.y) * dx) / length2;
                float distance = (float) Math.hypot(Math.max(0, along - 1), across);
                float wake = 1 - BoardRelief.smooth((distance - .15f) / .25f);
                float fallen = Math.clamp(descended, 0, 1) * drop;
                float gain = ((float) Math.sqrt(.01f + .08f * fallen) - .1f) * wake;
                float length = (float) Math.sqrt(length2);
                acceleration.add(dx / length * gain, dy / length * gain);
            }
            if (!inside && !BoardGeometry.contains(other.coords(), point.x, point.y)) { continue; }
            float churn = BoardRelief.smooth((descended - .15f) / .5f)
                  * (1 - BoardRelief.smooth((descended - .85f) / .15f)) * BoardRelief.smooth(along * 4);
            agitation = Math.max(agitation, (drop == 2 ? .85f : .3f) * churn);
        }
        return agitation;
    }

    /**
     * Where this water hex is where a river runs out at the board's edge, the edge its water comes from: on the board's
     * edge, with water joining it from a single neighbour at its level or above. -1 elsewhere.
     */
    private int riverEnd(BoardScene scene) {
        int joined = 0, source = -1;
        boolean edge = false, above = true;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile other = neighbor(scene, direction);
            edge |= other == null;
            if (other != null && tile.liquid().connects(other.liquid())) {
                joined++;
                source = direction;
                above &= other.elevation() >= tile.elevation() && !other.frozen();
            }
        }
        return edge && joined == 1 && above ? source : -1;
    }

    /** The water's cut face along an edge that the board's edge cuts through: from its bed up to its surface. */
    private void cut(int edge) {
        float surface = BoardGeometry.waterZ(tile);
        for (int i = 0; i < SHORE_SEGMENTS; i++) {
            Vector3 a = bedOutline[edge * SHORE_SEGMENTS + i];
            Vector3 b = bedOutline[(edge * SHORE_SEGMENTS + i + 1) % bedOutline.length];
            if (Math.min(a.z, b.z) >= surface - .001f * BoardGeometry.HEX_SCALE) { continue; }
            Vector3 topA = new Vector3(a.x, a.y, surface), topB = new Vector3(b.x, b.y, surface);
            cutFaces.add(new Face(new Vector3(a), new Vector3(b), topB, Finish.TOP, edge));
            if (a.z < surface - .001f * BoardGeometry.HEX_SCALE) {
                cutFaces.add(new Face(new Vector3(a), topB, topA, Finish.TOP, edge));
            }
        }
    }

    /** The crest of the fall over {@code edge}, from where its mouth's water starts to where it stops. */
    private Crest crest(Vector3[] waterline, float[] lip, int edge) {
        int previous = (edge + 5) % 6, next = (edge + 1) % 6;
        Vector3 along = new Vector3(corners[next]).sub(corners[edge]).nor();
        Vector3 a = new Vector3(waterline[edge * SHORE_SEGMENTS]), b = new Vector3(waterline[next * SHORE_SEGMENTS]);
        Vector3 startTangent = new Vector3(along), endTangent = new Vector3(along);
        if (joinA[edge] != null) { turn(a, startTangent, corners[edge], joinA[edge], lip[previous] > 0); }
        if (joinB[edge] != null) { turn(b, endTangent, corners[next], joinB[edge], lip[next] > 0); }
        a.z = waterline[edge * SHORE_SEGMENTS].z;
        b.z = waterline[next * SHORE_SEGMENTS].z;
        // Only a fall standing free between two banks breaks unevenly; falls that run on round a corner stay smooth.
        float jut = joinA[edge] == null && joinB[edge] == null ? tuning.lipJut() * BoardGeometry.HEX_SCALE : 0;
        return new Crest(a, b, startTangent, endTangent, new Vector3(corners[edge]), edgeOutward(edge), jut,
              BoardGeometry.waterZ(tile));
    }

    /**
     * Where a crest turns a corner into the next fall, given its normal there: at the corner itself on a prow and out
     * over the pool below in a valley, running square to the normal.
     */
    private static void turn(Vector3 point, Vector3 tangent, Vector3 corner, Vector3 normal, boolean prow) {
        point.set(corner).mulAdd(normal, prow ? 0 : tuning.valley() * BoardGeometry.HEX_SCALE);
        tangent.set(-normal.y, normal.x, 0);
    }

    /**
     * The bed as one sculpted basin. It descends from the waterline, or from an open mouth's profile, along rays toward
     * the hex centre, where a plateau keeps the unit anchor at the game depth. Mouth profiles and corners are pure
     * functions of the hexes sharing them, so neighbouring beds meet without a step. Under each bank the bed drops as
     * the land beside it and the water's depth suggest: steeply under higher ground and in deep water, gently off land
     * at the water's own level, where shallows reach out. Between rim and plateau it undulates into bars and pools.
     * {@code outer} is the waterline with each falling mouth moved out to its crest, which the ledge beneath the fall
     * reaches; rise, per edge, the levels the land beside a bank rises above this hex; plunge, the mouths a fall pours
     * in over, where the water scours a deeper pool.
     */
    private void basin(BoardScene scene, Vector3[] waterline, Vector3[] outer, float[] shore, float[] rise,
          boolean[] plunge) {
        int count = waterline.length;
        float surface = BoardGeometry.waterZ(tile), full = depth(tile), ledge = tuning.lipDepth() * BoardGeometry.HEX_SCALE;
        boolean[] spills = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            spills[edge] = crests[edge] != null;
        }
        // Depth at each edge's first point: none where a bank meets it, the ledge beside a fall, else the depth shared
        // by the corner's hexes.
        float[] ends = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6;
            ends[edge] = shore[edge] > 0 || shore[previous] > 0 ? 0
                  : spills[edge] || spills[previous] ? ledge : cornerDepth(scene, edge);
        }
        float[] steep = new float[6], scour = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            scour[edge] = plunge[edge] ? 1 : 0;
            float land = shore[edge] == 0 ? 0 : rise[edge] > 0 ? .4f + .3f * rise[edge] : rise[edge] == 0 ? -.6f : 0;
            // Under a slope that runs on down to the bed the bed takes its full depth near the slope's foot.
            steep[edge] = relief.slope(edge) ? 2
                  : Math.clamp(land + .25f * (tile.waterDepth() - 1), -.7f, 1);
        }
        float[] rim = new float[count];
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS;
            if (i % SHORE_SEGMENTS == 0) {
                rim[i] = ends[edge];
            } else if (shore[edge] == 0) {
                // Beds of one level meet at their mean depth; a fall pours over its ledge into the pool below, whose
                // bed keeps its own depth.
                BoardScene.Tile other = neighbor(scene, edge);
                float middle = spills[edge] ? ledge
                      : other != null && (other.elevation() == tile.elevation() || waterSlope(tile, other))
                            ? (full + depth(other)) / 2 : full;
                int next = (edge + 1) % 6;
                rim[i] = mouthDepth(waterline[i], waterline[edge * SHORE_SEGMENTS], ends[edge],
                      waterline[next * SHORE_SEGMENTS], ends[next], middle);
            }
        }
        Vector3[] ring = new Vector3[count];
        boolean shallow = tile.waterDepth() == 0 && tile.detailedGround() && !tile.frozen() && !tile.liquid().molten();
        float[] bank = new float[count];
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS;
            float t = i % SHORE_SEGMENTS / (float) SHORE_SEGMENTS;
            bank[i] = shore[edge] == 0 ? 0 : Math.min(shore[(edge + 5) % 6] > 0 ? 1 : BoardRelief.smooth(2 * t),
                  shore[(edge + 1) % 6] > 0 ? 1 : BoardRelief.smooth(2 * (1 - t)));
            float bed = shallow ? shallowBed(rim[i], outer[i].x, outer[i].y, bank[i]) : rim[i];
            ring[i] = new Vector3(outer[i].x, outer[i].y, outer[i].z - bed);
        }
        bedOutline = ring;
        int hexes = scene.width() * scene.height();
        int rings = hexes <= BoardRelief.tuning().fullDetailHexes() ? 4 : hexes <= BoardRelief.tuning().mediumDetailHexes() ? 3 : 2;
        for (int k = 1; k <= rings; k++) {
            float x = k / (float) rings, scale = 1 - tuning.plateau() * x;
            // Falling from the rim with some slope, steepest halfway, and easing into the plateau.
            float descent = .25f * x + .75f * x * x * (3 - 2 * x);
            Vector3[] inner = new Vector3[count];
            for (int i = 0; i < count; i++) {
                // Each ray drops as the banks beside it do.
                float s = alongBanks(steep, i);
                float d = s >= 0 ? 1 - (float) Math.pow(1 - descent, 1 + 2 * s)
                      : (float) Math.pow(descent, 1 - 1.5f * s);
                float px = center.x + (outer[i].x - center.x) * scale, py = center.y + (outer[i].y - center.y) * scale;
                // Bars and pools between the rim and the plateau, which stays at the game depth.
                float bed = (rim[i] + (full - rim[i]) * d)
                      * (1 + (.25f * meander(px, py, 5.1f) + .45f * alongBanks(scour, i)) * 4 * x * (1 - x));
                if (shallow) {
                    // Bars can merge into a bank instead of dipping at the old waterline. Open mouths and the
                    // centre plateau keep their shared bed depth; neither acquires a step or a raised unit anchor.
                    bed = shallowBed(bed, px, py, BoardRelief.lerp(4 * x * (1 - x), 1 - x, bank[i]));
                }
                inner[i] = new Vector3(px, py, surface + (outer[i].z - surface) * ease(x) - bed);
            }
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                quad(ring[i], ring[j], inner[j], inner[i], Finish.BED);
            }
            ring = inner;
            if (k == 1) { interiorFrom = faces.size(); }
        }
        Vector3 anchor = new Vector3(center.x, center.y, surface - full);
        for (int i = 0; i < count; i++) {
            triangle(anchor, ring[i], ring[(i + 1) % count], Finish.BED);
        }
        interiorTo = faces.size();
    }

    private static float shallowBed(float bed, float x, float y, float weight) {
        float bar = BoardRelief.smooth((meander(x, y, 9.7f) + .15f) / .5f);
        return Math.max(bed - 2.5f * BoardGeometry.HEX_SCALE * bar * weight, -.6f * BoardGeometry.HEX_SCALE);
    }

    /** A value given per edge at waterline point {@code index}, blended over each corner so no crease shows there. */
    private static float alongBanks(float[] perEdge, int index) {
        int edge = index / SHORE_SEGMENTS;
        float f = index % SHORE_SEGMENTS / (float) SHORE_SEGMENTS, own = perEdge[edge];
        return f < .5f ? BoardRelief.lerp((perEdge[(edge + 5) % 6] + own) / 2, own, 2 * f)
              : BoardRelief.lerp(own, (own + perEdge[(edge + 1) % 6]) / 2, 2 * f - 1);
    }

    /** Smooth world noise, roughly in [-1, 1], for banks and beds: swells of about 12 m with 4 m detail. */
    private static float meander(float x, float y, float seed) {
        float m = BoardRelief.metres(1);
        return .7f * BoardRelief.gradient(x / (12 * m) + seed, y / (12 * m) - seed)
              + .3f * BoardRelief.gradient(x / (4 * m) - seed, y / (4 * m) + seed);
    }

    private BoardScene.Tile neighbor(BoardScene scene, int edge) {
        return scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
    }

    /** Water depth of a liquid tile, from its surface to its bed. */
    private static float depth(BoardScene.Tile tile) {
        return BoardGeometry.waterZ(tile) - BoardGeometry.groundZ(tile);
    }

    /** Mean depth of the connected water at this hex's level around the corner where edge {@code index} starts. */
    private float cornerDepth(BoardScene scene, int index) {
        if (!tile.frozen() && !tile.liquid().molten()) {
            List<BoardScene.Tile> joined = cornerWater(scene, index);
            float sum = 0;
            int low = tile.elevation(), high = low;
            for (BoardScene.Tile other : joined) {
                sum += depth(other);
                low = Math.min(low, other.elevation());
                high = Math.max(high, other.elevation());
            }
            if (high - low >= 3) { return Math.min(sum / joined.size(), tuning.lipDepth() * BoardGeometry.HEX_SCALE); }
            return sum / joined.size();
        }
        List<Float> depths = new ArrayList<>(List.of(depth(tile)));
        for (int edge : new int[] { (index + 5) % 6, index }) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (other != null && tile.liquid().connects(other.liquid()) && other.elevation() == tile.elevation()) {
                depths.add(depth(other));
            }
        }
        // The same order in every hex around the corner, so all of them round the mean alike.
        Collections.sort(depths);
        float sum = 0;
        for (float value : depths) { sum += value; }
        return sum / depths.size();
    }

    /**
     * An open mouth's bed at p: its middle depth, easing over a third of the mouth to the depth at either end. The
     * profile is symmetric in its ends, so both hexes of the mouth evaluate the same points to the same heights.
     */
    private static float mouthDepth(Vector3 p, Vector3 a, float atA, Vector3 b, float atB, float middle) {
        float reach = Math.max((float) Math.hypot(b.x - a.x, b.y - a.y) / 3, .001f);
        return middle + (atA - middle) * ease((float) Math.hypot(p.x - a.x, p.y - a.y) / reach)
              + (atB - middle) * ease((float) Math.hypot(p.x - b.x, p.y - b.y) / reach);
    }

    /** 1 at 0, easing smoothly down to 0 at 1 and beyond. */
    private static float ease(float x) {
        float t = Math.clamp(x, 0, 1);
        return 1 - t * t * (3 - 2 * t);
    }

    /**
     * The crest's normal where falling edge {@code edge} meets edge {@code side}, when a fall of the same drop carries
     * on there; null where the end is free. On a prow, where this pool falls on over {@code side} into connected water
     * at the same level, it bisects the two edges. In a valley, where the neighbouring pool across {@code side}, at
     * this pool's level, falls into the same lower pool, it runs on along the edge the two pools share: both pools see
     * that edge alike, so their crests meet exactly on it and neither passes inside the other's hex.
     */
    private Vector3 join(BoardScene scene, float[] shore, float[] lip, int edge, int side) {
        BoardScene.Tile below = neighbor(scene, edge), beside = neighbor(scene, side);
        // Falls off the board's edge over two edges of this pool run on round the corner between them, as a prow.
        if (below == null || beside == null) {
            return below == null && beside == null && lip[side] > 0 ? edgeOutward(edge).add(edgeOutward(side)).nor()
                  : null;
        }
        if (lip[side] > 0) {
            return beside.elevation() == below.elevation() && beside.liquid().connects(below.liquid())
                  ? edgeOutward(edge).add(edgeOutward(side)).nor() : null;
        }
        if (shore[side] != 0 || beside.elevation() != tile.elevation() || beside.frozen()
              || !beside.liquid().connects(below.liquid())) { return null; }
        boolean before = side == (edge + 5) % 6;
        Vector3 corner = before ? corners[edge] : corners[side];
        return new Vector3(corner).sub(before ? corners[side] : corners[(side + 1) % 6]).nor();
    }

    /** Outward normal of one edge: away from this hex. */
    private Vector3 edgeOutward(int edge) {
        return edgeInward(edge).scl(-1);
    }

    /** The crest a fall of this pool pours over. */
    Crest crest(Side fall) {
        return crests[fall.edge()];
    }

    /** A waterfall can land beside a sloping stream; sample that same receiving surface for its sheet and spray. */
    float receivingHeight(Side fall, float x, float y) {
        BoardScene.Tile other = neighbor(scene, fall.edge());
        if (other == null) { return fall.lowA(); }
        if (receivingWater[fall.edge()] == null) {
            receivingWater[fall.edge()] = new BoardSurface(scene, other, false);
        }
        return receivingWater[fall.edge()].waterHeight(x, y);
    }

    /** Whether a fall pours off the board's edge, where nothing catches it: it fades out and throws up no spray. */
    boolean bottomless(Side fall) {
        return (offBoard & 1 << fall.edge()) != 0;
    }

    /** Whether the end of a fall carries on into another fall's sheet, so that end is not a free side. */
    boolean fallJoins(Side fall, boolean atA) {
        return (atA ? joinA : joinB)[fall.edge()] != null;
    }

    /** Radius of the curve where a fall leaves the upper surface, bounded by half the drop and one hex width. */
    static float fallLip(float surface, float bottom) {
        return Math.min(Math.max(0, surface - bottom) * tuning.fallLipDrop(), tuning.fallLipWidth() * BoardGeometry.WIDTH);
    }

    /** Inward normal of one edge: the direction a falling mouth pulls its water back from the shared edge. */
    private Vector3 edgeInward(int edge) {
        return new Vector3(corners[(edge + 1) % 6]).sub(corners[edge]).crs(Vector3.Z).nor().scl(-1);
    }

    /**
     * Pull the water surface back from each crest along its normal, leaving the lip's room for the sheet to fill. A
     * corner where two falls meet recedes along the normal both crests share there, which is where both pulled-back
     * edges and both sheets meet, whether the other fall is this pool's or the neighbouring one's.
     */
    private Vector3[] pulledBack(Vector3[] crestLine, float[] lip) {
        Vector3[] result = crestLine.clone();
        for (int index = 0; index < crestLine.length; index++) {
            int edge = index / SHORE_SEGMENTS, previous = (edge + 5) % 6;
            // An anchor also ends the previous edge, whose crest ends there too.
            boolean anchor = index % SHORE_SEGMENTS == 0;
            Crest crest = crests[edge];
            int lipEdge = edge;
            float s = index % SHORE_SEGMENTS / (float) SHORE_SEGMENTS;
            if (crest == null && anchor && crests[previous] != null) {
                crest = crests[previous];
                lipEdge = previous;
                s = 1;
            }
            if (crest != null) {
                result[index] = new Vector3(crestLine[index])
                      .mulAdd(crest.normal(s), -lipWidth(lipEdge, s,
                            anchor ? Math.max(lip[edge], lip[previous]) : lip[edge]));
            }
        }
        return result;
    }

    /** At a free bank the lip narrows to its anchor, so pulling it back cannot cross the adjoining shoreline. */
    float lipWidth(int edge, float s, float width) {
        if (joinA[edge] == null && joinB[edge] == null) {
            width = Math.min(width, .15f * crests[edge].chord());
        }
        return width * (joinA[edge] == null ? BoardRelief.smooth(s / .2f) : 1)
              * (joinB[edge] == null ? BoardRelief.smooth((1 - s) / .2f) : 1);
    }

    private Vector3 shoreLip(Vector3 waterline, Vector3 boundary) {
        // The bank's width is horizontal, just as BoardRelief.stub measures it. A descending stream can put its
        // waterline far below the corner; counting that height shortened the rim used to close its side walls.
        float distance = (float) Math.hypot(boundary.x - waterline.x, boundary.y - waterline.y);
        Vector3 lip = new Vector3(waterline).lerp(boundary,
              Math.min(1, BoardRelief.tuning().shoreLip() * BoardGeometry.HEX_SCALE / Math.max(distance, .0001f)));
        lip.z = center.z;
        return lip;
    }

    /**
     * The waterline: where this hex's shore (see {@link #wet}) turns from water to land, inside its outline as the
     * shore moves its corners. Open mouths run straight between their ends, which both hexes of a mouth place alike
     * (see {@link #mouthEnd}); along the banks the line lies where the shore turns on the way out from the hex centre
     * toward the outline, so it stays star-shaped round the centre and the bed's rings never fold.
     */
    private Vector3[] shoreline(BoardScene scene, float[] inset, float[] rise, boolean[] plunge, float z) {
        float scale = BoardGeometry.HEX_SCALE;
        shoreCorners = new Vector3[6];
        for (int k = 0; k < 6; k++) { shoreCorners[k] = moved(k); }
        // Keep a bank beside land at the water's level, a beach beside walls and lower land, and the slope's reach
        // plus a wet margin beside a slope up from the water, which runs on under it to the bed. Keep clear of the
        // corners the land rounds into the hex too.
        banks = new ArrayList<>(12);
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            if (inset[k] > 0) {
                float width = relief.slope(k) || rise[k] != 0 ? inset[k] : tuning.shoreBank() * scale;
                Vector3 inward = new Vector3(b).sub(a).crs(Vector3.Z).nor().scl(-1);
                banks.add(new Bank(a.x, a.y, inward.x, inward.y, width));
            }
            float in = relief.cornerInset(k);
            if (in > 0) {
                Vector3 corner = shoreCorners[k];
                Vector3 inward = new Vector3(center.x - corner.x, center.y - corner.y, 0).nor();
                banks.add(new Bank(corner.x, corner.y, inward.x, inward.y, in + tuning.hug() * scale));
            }
        }
        Vector3[] anchors = new Vector3[6];
        for (int k = 0; k < 6; k++) {
            int before = (k + 5) % 6, next = (k + 1) % 6;
            boolean in = inset[before] == 0, out = inset[k] == 0;
            if (in && out) {
                anchors[k] = new Vector3(shoreCorners[k]);
            } else if (in || out) {
                int other = in ? before : next, mouth = in ? before : k;
                float end = mouthEnd(k, other, plunge[mouth]);
                anchors[k] = new Vector3(shoreCorners[k]).lerp(shoreCorners[other], end);
                // Across the mouth, onto the seam the relief lays there, which bends where steps through the corner
                // move it; along it, where the mouth ends.
                Vector3 across = new Vector3(shoreCorners[other]).sub(shoreCorners[k]).crs(Vector3.Z).nor();
                anchors[k].mulAdd(across, new Vector3(relief.seam(mouth, k, end)).sub(anchors[k]).dot(across));
            } else {
                // Between two banks, where the shore turns on the way out to the corner; the banks' own limits stop it
                // short of the corner where the water would run on past them.
                anchors[k] = along(shoreCorners[k], plunge);
            }
            anchors[k].z = z;
        }
        Vector3[] result = new Vector3[6 * SHORE_SEGMENTS];
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6;
            Vector3[] bank = inset[edge] == 0 ? null : bank(anchors[edge], anchors[next], plunge, edge);
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                Vector3 point = bank == null ? mouthPoint(anchors[edge], anchors[next], segment) : bank[segment];
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        return result;
    }

    /**
     * A bank's waterline from {@code from} to the next bank's or mouth's end {@code to}, as {@link #SHORE_SEGMENTS}
     * points spaced evenly along it, the first at {@code from}: traced finely out along bearings turning evenly round
     * the centre, so it follows the shore however far past the corners its ends lie, then spaced by length, so it keeps
     * its points where the shore runs out nearly along those bearings, as beside a mouth that bends into the hex.
     */
    private Vector3[] bank(Vector3 from, Vector3 to, boolean[] plunge, int edge) {
        int fine = BANK_TRACE * SHORE_SEGMENTS;
        float start = bearing(from), turn = bearing(to) - start;
        if (turn <= 0) { turn += 2 * (float) Math.PI; }
        Vector3[] trace = new Vector3[fine + 1];
        float[] length = new float[fine + 1];
        trace[0] = from;
        trace[fine] = to;
        Vector3 rawFrom = along(outlinePoint(start), plunge), rawTo = along(outlinePoint(start + turn), plunge);
        float fromOffset = radius(from) - radius(rawFrom), toOffset = radius(to) - radius(rawTo);
        float blend = BoardRelief.smooth((BoardRelief.tuning().riverWidth() - .25f) / .5f);
        // A plunge pool must meet its fixed inlet continuously, even when the stream is narrow.
        float fromBlend = plunge[(edge + 5) % 6] ? 1 : blend, toBlend = plunge[(edge + 1) % 6] ? 1 : blend;
        Vector3 fromNormal = bankNormal(from, (edge + 5) % 6), toNormal = bankNormal(to, (edge + 1) % 6);
        for (int i = 1; i <= fine; i++) {
            if (i < fine) {
                float t = BoardRelief.smooth(i / (float) fine);
                Vector3 limit = outlinePoint(start + turn * t);
                trace[i] = along(limit, plunge);
                float rawRadius = radius(trace[i]);
                // Meet a mouth with the same tangent from both hexes. Correcting only its radius misses cases where
                // the raw trace follows the hex edge past the fixed opening, then doubles back into a pointed bank.
                float fromCorrection = fromNormal == null ? fromBlend * fromOffset : bankJoin(from, fromNormal, limit, rawRadius);
                float toCorrection = toNormal == null ? toBlend * toOffset : bankJoin(to, toNormal, limit, rawRadius);
                float correction = fromCorrection * ease(2 * t) + toCorrection * ease(2 * (1 - t));
                float core = Math.min(rawRadius, (tile.waterDepth() > 0 ? 12 : 4) * BoardGeometry.HEX_SCALE);
                float radius = Math.min(radius(limit), Math.max(core, rawRadius + correction));
                trace[i].sub(center).scl(radius / rawRadius).add(center);
            }
            length[i] = length[i - 1] + (float) Math.hypot(trace[i].x - trace[i - 1].x, trace[i].y - trace[i - 1].y);
        }
        Vector3[] result = new Vector3[SHORE_SEGMENTS];
        result[0] = new Vector3(from);
        for (int segment = 1, i = 0; segment < SHORE_SEGMENTS; segment++) {
            float wanted = length[fine] * segment / SHORE_SEGMENTS;
            while (i < fine - 1 && length[i + 1] < wanted) { i++; }
            float span = length[i + 1] - length[i], f = span > 0 ? (wanted - length[i]) / span : 0;
            result[segment] = new Vector3(trace[i]).lerp(trace[i + 1], f);
        }
        return result;
    }

    /** A margin-limited opening shares a bank tangent perpendicular to its seam. Natural shores keep their curve. */
    private Vector3 bankNormal(Vector3 point, int edge) {
        if (!mouth(edge) || shore(point.x, point.y) <= .25f * BoardGeometry.HEX_SCALE) { return null; }
        return new Vector3(shoreCorners[(edge + 1) % 6]).sub(shoreCorners[edge]);
    }

    /** Radial correction onto the tangent through an end, preserving the bed's non-crossing radial rings. */
    private float bankJoin(Vector3 end, Vector3 normal, Vector3 limit, float rawRadius) {
        float denominator = (limit.x - center.x) * normal.x + (limit.y - center.y) * normal.y;
        float numerator = (end.x - center.x) * normal.x + (end.y - center.y) * normal.y;
        if (Math.abs(denominator) < 1e-6f || numerator * denominator <= 0) { return 0; }
        return radius(limit) * numerator / denominator - rawRadius;
    }

    private float radius(Vector3 point) {
        return (float) Math.hypot(point.x - center.x, point.y - center.y);
    }

    /** The bearing of p from the hex centre, in radians. */
    private float bearing(Vector3 p) {
        return (float) Math.atan2(p.y - center.y, p.x - center.x);
    }

    /** Where the way out from the hex centre at {@code bearing} meets the outline as the shore moves its corners. */
    private Vector3 outlinePoint(float bearing) {
        float dx = (float) Math.cos(bearing), dy = (float) Math.sin(bearing), nearest = Float.POSITIVE_INFINITY;
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            float ex = b.x - a.x, ey = b.y - a.y, denominator = dx * ey - dy * ex;
            if (Math.abs(denominator) < 1e-9f) { continue; }
            float qx = a.x - center.x, qy = a.y - center.y;
            float s = (qx * ey - qy * ex) / denominator, u = (qx * dy - qy * dx) / denominator;
            if (s > 0 && u >= -.00001f && u <= 1.00001f) { nearest = Math.min(nearest, s); }
        }
        return new Vector3(center.x + dx * nearest, center.y + dy * nearest, center.z);
    }

    /**
     * Point {@code segment} of an open mouth from {@code a} to {@code b}, laid out from the end both hexes of the mouth
     * order first, so they compute the bit-identical point.
     */
    private static Vector3 mouthPoint(Vector3 a, Vector3 b, int segment) {
        boolean forward = a.x < b.x || a.x == b.x && a.y < b.y;
        return forward ? new Vector3(a).lerp(b, segment / (float) SHORE_SEGMENTS)
              : new Vector3(b).lerp(a, (SHORE_SEGMENTS - segment) / (float) SHORE_SEGMENTS);
    }

    /**
     * Where an open mouth beside a bank ends, as the fraction of the mouth from corner k toward corner {@code other},
     * as the shore moves both: where the shore field turns to land on the way out from the mouth's middle toward k,
     * but no nearer k than {@link #mouthLimit} allows. Both hexes of the mouth compute it from the same corners and the
     * same field, so they end the mouth at the same point.
     */
    private float mouthEnd(int k, int other, boolean plunge) {
        Vector3 c = shoreCorners[k], o = shoreCorners[other];
        float least = mouthLimit(k, plunge) / c.dst(o);
        float mx = (c.x + o.x) / 2, my = (c.y + o.y) / 2;
        float seed = .5f;
        if (shore(mx, my) <= 0 && !tile.liquid().molten()) {
            // A narrow curved stream can cross the side away from its midpoint. Find its wet centre, using the
            // same sampling order from either hex, before tracing toward either bank.
            boolean forward = c.x < o.x || c.x == o.x && c.y < o.y;
            Vector3 a = forward ? c : o, b = forward ? o : c;
            float best = 0;
            for (int i = 1; i < 96; i++) {
                float s = i / 96f, x = a.x + (b.x - a.x) * s, y = a.y + (b.y - a.y) * s;
                float wet = shore(x, y);
                if (wet <= best) { continue; }
                best = wet;
                mx = x;
                my = y;
                seed = forward ? s : 1 - s;
            }
        }
        // Fixed banks can make the field dry even between connected water hexes. Keep their opening instead of
        // tracing from land, which would collapse both mouth ends onto its midpoint.
        if (shore(mx, my) <= 0) { return least; }
        float dry = firstDry(mx, my, c.x + (o.x - c.x) * least, c.y + (o.y - c.y) * least, null);
        return dry < 0 ? least : seed - dry * (seed - least);
    }

    /**
     * How near corner k, as the shore moves it, an open mouth beside a bank may end, in world units along the mouth:
     * a bank and a unit short of a corner the shore moves at the water's level, a wet margin beyond the steps through
     * a corner where the land rises; at a corner that stays put four units less land than a beach (below a fall,
     * nearly at the corner, so the whole sheet lands in water), and beyond whatever steps through it reach into the
     * mouth.
     */
    private float mouthLimit(int k, boolean plunge) {
        float scale = BoardGeometry.HEX_SCALE, reach = relief.cornerReach(k);
        if (shoreCorners[k].equals(corners[k])) {
            return Math.max((tuning.beach() - (plunge ? tuning.plungeOpening() : tuning.mouthOpening())) * scale * 1.1547005f,
                  reach + tuning.hug() * scale);
        }
        return reach > 0 ? reach + tuning.hug() * scale : (tuning.shoreBank() + 1) * scale;
    }

    /**
     * Where the water ends on the way from the hex centre out to {@code target} on the outline: the first point where
     * this hex's shore turns to land; the target itself if it never does.
     */
    private Vector3 along(Vector3 target, boolean[] plunge) {
        float dry = firstDry(center.x, center.y, target.x, target.y, plunge);
        return dry < 0 ? new Vector3(target) : new Vector3(center).lerp(target, dry);
    }

    /**
     * Where the way from (ax, ay) to (bx, by) first turns to land, as a fraction of it, or -1 if it never does: by the
     * shore field alone without {@code plunge}, else by this hex's shore ({@link #wet}). Even steps find the first dry
     * one, and halvings the turn within it.
     */
    private float firstDry(float ax, float ay, float bx, float by, boolean[] plunge) {
        float dx = bx - ax, dy = by - ay, lo = 0, hi = -1;
        for (int i = 1; i <= SHORE_STEPS && hi < 0; i++) {
            float s = i / (float) SHORE_STEPS;
            if (plunge == null ? shore(ax + dx * s, ay + dy * s) <= 0 : wet(ax + dx * s, ay + dy * s, plunge) <= 0) {
                lo = (i - 1) / (float) SHORE_STEPS;
                hi = s;
            }
        }
        if (hi < 0) { return -1; }
        for (int i = 0; i < SHORE_HALVINGS; i++) {
            float s = (lo + hi) / 2;
            if (plunge == null ? shore(ax + dx * s, ay + dy * s) > 0 : wet(ax + dx * s, ay + dy * s, plunge) > 0) {
                lo = s;
            } else {
                hi = s;
            }
        }
        return (lo + hi) / 2;
    }

    /**
     * This hex's shore at (x, y), positive in the water: the shore field, swollen round the foot of each fall that
     * pours into this hex, and inside the banks it keeps, the corners between them rounded over {@link #SHORE_ROUND}.
     */
    private float wet(float x, float y, boolean[] plunge) {
        float scale = BoardGeometry.HEX_SCALE, value = shore(x, y);
        for (int edge = 0; edge < 6; edge++) {
            if (!plunge[edge]) { continue; }
            Vector3 middle = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f);
            Vector3 inward = edgeInward(edge);
            float into = (x - middle.x) * inward.x + (y - middle.y) * inward.y;
            // Scour opens a rounded pool downstream of the inlet. At the shared edge it vanishes smoothly,
            // leaving the canonical mouth intact instead of abruptly narrowing the swollen bank there.
            middle.mulAdd(inward, .15f * BoardGeometry.WIDTH);
            float d = (float) Math.hypot(x - middle.x, y - middle.y) / (.4f * BoardGeometry.WIDTH);
            value += tuning.plungePool() * scale * (1 - BoardRelief.smooth(d))
                  * BoardRelief.smooth(into / (.12f * BoardGeometry.WIDTH));
        }
        for (Bank bank : banks) {
            value = BoardRelief.smoothMin(value, bank.clearance(x, y), tuning.shoreRound() * scale);
        }
        return value;
    }

    /** The board's shore field of this hex's liquid at (x, y): positive in it (see {@link BoardRelief#shore}). */
    private float shore(float x, float y) {
        return relief.shore(x, y, tile.liquid().molten());
    }

    /**
     * How far a bank on {@code edge} keeps the water in from the edge: beside a slope up from the water, which runs on
     * under it to the bed, a wet margin beyond how far the slope's foot reaches into the hex; elsewhere a beach.
     */
    private float least(int edge) {
        float scale = BoardGeometry.HEX_SCALE;
        return relief.slope(edge) ? relief.reach(edge) + tuning.hug() * scale : tuning.beach() * scale;
    }

    /** Curved channels can be concave; a centre fan would fill parts of their banks with water. */
    private static void polygon(Vector3[] contour, Finish finish, List<Face> destination) {
        float[] xy = new float[contour.length * 2];
        for (int i = 0; i < contour.length; i++) {
            xy[i * 2] = contour[i].x;
            xy[i * 2 + 1] = contour[i].y;
        }
        var indices = new EarClippingTriangulator().computeTriangles(xy);
        for (int i = 0; i < indices.size; i += 3) {
            destination.add(new Face(contour[indices.get(i)], contour[indices.get(i + 2)],
                  contour[indices.get(i + 1)], finish));
        }
    }

    private void road(BoardScene scene) {
        Vector3[] hub = new Vector3[6];
        for (int i = 0; i < 6; i++) {
            hub[i] = new Vector3(corners[i]).lerp(center, 0.5f);
        }
        fan(hub, center.z, Finish.TOP);
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6;
            int direction = BoardGeometry.edgeDirection(edge);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            if ((ramps & (1 << direction)) == 0) {
                quad(hub[edge], corners[edge], corners[next], hub[next], Finish.TOP);
                continue;
            }
            float half = 9 * BoardGeometry.HEX_SCALE / corners[edge].dst(corners[next]);
            Vector3 left = new Vector3(corners[edge]).lerp(corners[next], 0.5f - half);
            Vector3 right = new Vector3(corners[edge]).lerp(corners[next], 0.5f + half);
            Vector3 innerLeft = new Vector3(hub[edge]).lerp(hub[next], 0.5f - half * 2);
            Vector3 innerRight = new Vector3(hub[edge]).lerp(hub[next], 0.5f + half * 2);
            quad(hub[edge], corners[edge], left, innerLeft, Finish.TOP);
            quad(innerRight, right, corners[next], hub[next], Finish.TOP);
            Vector3 roadLeft = new Vector3(left);
            Vector3 roadRight = new Vector3(right);
            roadLeft.z = roadRight.z = roadEdgeElevation(tile, neighbor, direction) * BoardGeometry.LEVEL;
            quad(innerLeft, roadLeft, roadRight, innerRight, Finish.TOP);
            triangle(innerLeft, left, roadLeft, Finish.BANK);
            triangle(innerRight, roadRight, right, Finish.BANK);
        }
    }

    private void fan(Vector3[] polygon, float z, Finish finish) {
        Vector3 middle = new Vector3(center.x, center.y, z);
        for (int i = 0; i < polygon.length; i++) {
            triangle(middle, polygon[i], polygon[(i + 1) % polygon.length], finish);
        }
    }

    private void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Finish finish) {
        quad(a, b, c, d, finish, -1);
    }

    private void quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Finish finish, int landEdge) {
        triangle(a, b, c, finish, landEdge);
        triangle(a, c, d, finish, landEdge);
    }

    private void triangle(Vector3 a, Vector3 b, Vector3 c, Finish finish) {
        triangle(a, b, c, finish, -1);
    }

    private void triangle(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge) {
        if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 0.000001f) {
            faces.add(new Face(new Vector3(a), new Vector3(b), new Vector3(c), finish, landEdge));
        }
    }

    float height(float x, float y) {
        return height(faces, x, y);
    }

    private float height(List<Face> geometry, float x, float y) {
        return sampleHeight(geometry, x, y, BoardGeometry.groundZ(tile));
    }

    /** Shared triangle sampler for ground support and embedded rock placement. */
    static float sampleHeight(List<Face> geometry, float x, float y, float fallback) {
        float height = Float.NEGATIVE_INFINITY;
        for (Face face : geometry) {
            if (face.finish() != Finish.ICE && face.finish() != Finish.DRESSING) {
                height = Math.max(height, face.height(x, y));
            }
        }
        return Float.isFinite(height) ? height : fallback;
    }

    private static float square(float value) { return value * value; }

    /** Derived once per immutable terrain snapshot; repeated pointer rays reuse the rendered topology. */
    List<Face> walls(BoardScene scene, float floor) {
        if (wallFaces == null || wallFloor != floor) {
            wallFaces = relief.walls(sides(scene, floor));
            wallFloor = floor;
        }
        return wallFaces;
    }

    private void cuts(Vector3 a, Vector3 b, TreeSet<Float> cuts) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float length2 = dx * dx + dy * dy;
        for (Face face : edgeTopography) {
            for (Vector3 point : List.of(face.a(), face.b(), face.c())) {
                if (Math.abs(dx * (point.y - a.y) - dy * (point.x - a.x)) < 0.02f) {
                    float t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / length2;
                    if (t > 0.001f && t < 0.999f) {
                        cuts.add(Math.round(t * 100000) / 100000f);
                    }
                }
            }
        }
    }

    /** Corner k as the shore moves it (see {@link BoardRelief#shoreShift}). */
    private Vector3 moved(int k) {
        float[] shift = relief.shoreShift(k);
        return new Vector3(corners[k]).add(shift[0], shift[1], 0);
    }

    /** Whether connected liquid continues across this edge, which a bank never crosses and only a fall descends. */
    boolean mouth(int edge) {
        return (openMouths & 1 << edge) != 0;
    }

    /**
     * Only the higher column contributes a shared wall; road gates meet at the same height. Under a fall the wall
     * stands beneath its crest, from the ledge the water pours over down to the pool below.
     */
    List<Side> sides(BoardScene scene, float floor) {
        List<Side> result = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            // The edge as the shore moves its corners, where both hexes' tops meet.
            Vector3 a = moved(edge), b = moved((edge + 1) % 6);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (mouth(edge) && neighbor != null && neighbor.elevation() == tile.elevation()) {
                continue; // Beds of one level share their mouth's profile and their banks' top: nothing stands between.
            }
            BoardSurface adjacent = neighbor == null ? null : new BoardSurface(scene, neighbor, false);
            TreeSet<Float> cuts = new TreeSet<>(List.of(0f, 1f));
            cuts(a, b, cuts);
            if (adjacent != null) {
                adjacent.cuts(a, b, cuts);
            }
            // The stretch of the edge a crest spans, where its own wall replaces the straight one.
            Crest crest = crests[edge];
            float spanA = crest == null ? 2 : joinA[edge] != null ? 0 : along(a, b, crest.a());
            float spanB = crest == null ? 2 : joinB[edge] != null ? 1 : along(a, b, crest.b());
            List<Float> points = new ArrayList<>(cuts);
            for (int i = 1; i < points.size(); i++) {
                float from = points.get(i - 1), to = points.get(i);
                if (to - from < 0.0001f || from > spanA - 0.0001f && to < spanB + 0.0001f) {
                    continue;
                }
                Vector3 start = new Vector3(a).lerp(b, from);
                Vector3 end = new Vector3(a).lerp(b, to);
                Vector3 sampleA = new Vector3(start).lerp(end, 0.001f);
                Vector3 sampleB = new Vector3(end).lerp(start, 0.001f);
                start.z = height(edgeTopography, sampleA.x, sampleA.y);
                end.z = height(edgeTopography, sampleB.x, sampleB.y);
                float lowA = adjacent == null ? floor : adjacent.height(adjacent.edgeTopography, sampleA.x, sampleA.y);
                float lowB = adjacent == null ? floor : adjacent.height(adjacent.edgeTopography, sampleB.x, sampleB.y);
                float dA = start.z - lowA, dB = end.z - lowB;
                if (dA < -0.001f && dB > 0.001f) {
                    float t = -dA / (dB - dA);
                    start.lerp(end, t);
                    lowA = start.z;
                } else if (dB < -0.001f && dA > 0.001f) {
                    float t = dA / (dA - dB);
                    end.set(new Vector3(start).lerp(end, t));
                    lowB = end.z;
                }
                if (Math.max(start.z - lowA, end.z - lowB) > 0.01f) {
                    result.add(new Side(start, end, Math.min(start.z, lowA), Math.min(end.z, lowB), edge));
                }
            }
            if (crest != null) {
                for (int i = 0; i < SHORE_SEGMENTS; i++) {
                    Vector3 start = new Vector3(bedOutline[edge * SHORE_SEGMENTS + i]);
                    Vector3 end = new Vector3(bedOutline[(edge * SHORE_SEGMENTS + i + 1) % bedOutline.length]);
                    float lowA = adjacent == null ? floor : adjacent.height(adjacent.edgeTopography, start.x, start.y);
                    float lowB = adjacent == null ? floor : adjacent.height(adjacent.edgeTopography, end.x, end.y);
                    if (Math.max(start.z - lowA, end.z - lowB) > 0.01f) {
                        result.add(new Side(start, end, Math.min(start.z, lowA), Math.min(end.z, lowB), edge));
                    }
                }
            }
        }
        return result;
    }

    /** Where p lies along the edge from a to b, 0 at a and 1 at b. */
    private static float along(Vector3 a, Vector3 b, Vector3 p) {
        float dx = b.x - a.x, dy = b.y - a.y;
        return ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
    }
}
