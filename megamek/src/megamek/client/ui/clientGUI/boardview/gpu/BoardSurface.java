/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** The actual topography of one hex. Rendering, road continuity, banks and picking share it. */
final class BoardSurface {
    /** GL-view-owned geometry. Artwork snapshots retain surfaces; shape changes are checked once per generation. */
    static final class Cache {
        private static final int CAPACITY = 256;
        private static final class Entry {
            final List<Geometry> key;
            final BoardSurface surface;
            long generation;

            Entry(List<Geometry> key, BoardSurface surface, long generation) {
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
                List<Geometry> key = geometryKey(scene, tile);
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

    /**
     * Only inputs that alter topology; the ramp mask also captures second-ring road/bridge approaches, and the shore
     * mask, the neighbours that are liquid at the hex's level, the second-ring water that makes land a point.
     */
    record Geometry(int elevation, int waterDepth, boolean frozen, int roadExits, BoardScene.Surface surface,
          BoardLiquid liquid, boolean detailedGround, List<BoardScene.Feature> features, int ramps, int shores) { }

    /** Own shape followed by the six neighboring shapes; a missing board neighbor has a null entry. */
    static List<Geometry> geometryKey(BoardScene scene, BoardScene.Tile tile) {
        List<Geometry> key = new ArrayList<>(7);
        key.add(geometry(scene, tile));
        for (int direction = 0; direction < 6; direction++) {
            key.add(geometry(scene, scene.tile(tile.coords().translated(direction))));
        }
        return Collections.unmodifiableList(key);
    }

    private static Geometry geometry(BoardScene scene, BoardScene.Tile tile) {
        return tile == null ? null : new Geometry(tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
              tile.surface(), tile.liquid(), tile.detailedGround(), tile.features(), ramps(scene, tile),
              shores(scene, tile));
    }

    /** The neighbours of a hex that are liquid at its level, as a bit mask by direction. */
    private static int shores(BoardScene scene, BoardScene.Tile tile) {
        int result = 0;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile other = scene.tile(tile.coords().translated(direction));
            if (other != null && other.liquid().present() && other.elevation() == tile.elevation()) {
                result |= 1 << direction;
            }
        }
        return result;
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
    /**
     * The wet margin, in hex-scale units, the water keeps beyond any land that reaches into its hex: the foot of a
     * slope, the steps and fillet through a corner, and a mouth's ends. The river hugs the slopes it runs between.
     */
    private static final float HUG = 1.5f;
    /** How far, in hex-scale units, a bank keeps the water in from its edge where no slope runs up from the water. */
    private static final float BEACH = 8;
    /**
     * Reach, in hex-scale units, of each hex centre's pull on the shore field (see {@link #shore}): seven quarters of
     * an edge, where a straight row of hexes pulls without ripple at the hex period, so a straight bank runs straight.
     */
    private static final float SHORE_RADIUS = 73.5f;
    /** How far, in hex-scale units, the bank stands in from the midline between the water and the land. */
    private static final float SHORE_BIAS = 3;
    /**
     * How far, in hex-scale units, the bank wanders to either side of that, and the noise cell it wanders over: bends
     * one and a half to three hexes long, as on the printed maps, so a long bank curves gently instead of running like
     * a ruler, while the hex's own period stays free of ripple.
     */
    private static final float SHORE_WANDER = 8;
    private static final float WANDER_CELL = 140;
    /** How far, in hex-scale units, a pool swells out round the foot of a fall that pours into it. */
    private static final float PLUNGE_POOL = 6;
    /** The bank, in hex-scale units, the water keeps inside its outline beside land at its own level. */
    private static final float SHORE_BANK = 4.5f;
    /** Width, in hex-scale units, over which the shore rounds the corners between the banks it keeps. */
    private static final float SHORE_ROUND = 6;
    /**
     * How hard land at the water's level whose corners stay put (paving, special artwork, roads) pushes the shore
     * away: back to about a beach short of its corners, where the mouths beside it end.
     */
    private static final float SHORE_HARD = 2.5f;
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
          float jut) {
        /** The point at {@code s}, from 0 at {@code a} to 1 at {@code b}. */
        Vector3 point(float s) {
            if (s <= 0) { return new Vector3(a); }
            if (s >= 1) { return new Vector3(b); }
            float chord = chord(), s2 = s * s, s3 = s2 * s;
            Vector3 p = new Vector3(a).scl(2 * s3 - 3 * s2 + 1).mulAdd(startTangent, chord * (s3 - 2 * s2 + s))
                  .mulAdd(b, 3 * s2 - 2 * s3).mulAdd(endTangent, chord * (s3 - s2));
            p.z = a.z;
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
    /** Centres and weights of this water hex and its neighbours for {@link #shore}, in a fixed order. */
    private float[] shoreHexes;
    /** The lines a water hex's shore keeps inside, as {origin x, origin y, inward x, inward y, width}. */
    private float[][] banks;

    BoardSurface(BoardScene scene, BoardScene.Tile tile) {
        this(scene, tile, true);
    }

    private BoardSurface(BoardScene scene, BoardScene.Tile tile, boolean detailed) {
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
            fan(corners, center.z, Finish.TOP);
        }
        for (int i = 0; i < faces.size(); i++) {
            // Only faces reaching the hex outline can meet a neighbour's: a bed's inner rings never do.
            if (i < interiorFrom || i >= interiorTo) { edgeTopography.add(faces.get(i)); }
        }
        if (detailed) {
            int falls = 0;
            for (int edge = 0; edge < 6; edge++) { falls |= crests[edge] == null ? 0 : 1 << edge; }
            relief.top(faces, waterline, openMouths, crestLine, falls);
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
        int source = FALLS_OFF_THE_BOARD && !tile.frozen() ? riverEnd(scene) : -1;
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
                              BoardGeometry.waterZ(tile) - BOTTOMLESS_LEVELS * BoardGeometry.LEVEL);
                    }
                } else if (!tile.frozen() && !neighbor.frozen() && tile.elevation() > neighbor.elevation()) {
                    lip[edge] = fallLip(BoardGeometry.waterZ(tile), BoardGeometry.waterZ(neighbor));
                }
                plunge[edge] = neighbor != null && !tile.frozen() && !neighbor.frozen()
                      && neighbor.elevation() > tile.elevation();
            }
        }
        waterline = shoreline(scene, shore, rise, plunge, BoardGeometry.waterZ(tile));
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
                Vector3 a = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], segment / (float) SHORE_SEGMENTS);
                Vector3 b = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], (segment + 1f) / SHORE_SEGMENTS);
                Vector3 lipA = shoreLip(waterline[index], a);
                Vector3 lipB = shoreLip(waterline[next], b);
                quad(a, b, lipB, lipA, Finish.TOP, edge);
                quad(lipA, lipB, waterline[next], waterline[index], Finish.SHORE, edge);
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
            polygon(surface, Finish.TOP, waterFaces);
            for (int edge = 0; edge < 6; edge++) {
                BoardScene.Tile other = neighbor(scene, edge);
                if (crests[edge] != null) {
                    // Off the board's edge a fall drops into nothing and fades out on its way down.
                    float bottom = other == null ? BoardGeometry.waterZ(tile) - BOTTOMLESS_LEVELS * BoardGeometry.LEVEL
                          : BoardGeometry.waterZ(other);
                    waterfalls.add(new Side(new Vector3(crests[edge].a()), new Vector3(crests[edge].b()), bottom,
                          bottom, edge));
                } else if (other == null) {
                    cut(edge);
                }
            }
        }
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
        a.z = b.z = waterline[0].z;
        // Only a fall standing free between two banks breaks unevenly; falls that run on round a corner stay smooth.
        float jut = joinA[edge] == null && joinB[edge] == null ? LIP_JUT * BoardGeometry.HEX_SCALE : 0;
        return new Crest(a, b, startTangent, endTangent, new Vector3(corners[edge]), edgeOutward(edge), jut);
    }

    /**
     * Where a crest turns a corner into the next fall, given its normal there: at the corner itself on a prow and out
     * over the pool below in a valley, running square to the normal.
     */
    private static void turn(Vector3 point, Vector3 tangent, Vector3 corner, Vector3 normal, boolean prow) {
        point.set(corner).mulAdd(normal, prow ? 0 : VALLEY * BoardGeometry.HEX_SCALE);
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
        float surface = BoardGeometry.waterZ(tile), full = depth(tile), ledge = LIP_DEPTH * BoardGeometry.HEX_SCALE;
        boolean[] spills = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            spills[edge] = shore[edge] == 0
                  && (other == null ? crests[edge] != null : other.elevation() < tile.elevation());
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
                      : other != null && other.elevation() == tile.elevation() ? (full + depth(other)) / 2 : full;
                int next = (edge + 1) % 6;
                rim[i] = mouthDepth(waterline[i], waterline[edge * SHORE_SEGMENTS], ends[edge],
                      waterline[next * SHORE_SEGMENTS], ends[next], middle);
            }
        }
        Vector3[] ring = new Vector3[count];
        for (int i = 0; i < count; i++) {
            ring[i] = new Vector3(outer[i].x, outer[i].y, surface - rim[i]);
        }
        bedOutline = ring;
        int hexes = scene.width() * scene.height();
        int rings = hexes <= BoardRelief.FULL_DETAIL_HEXES ? 4 : hexes <= BoardRelief.MEDIUM_DETAIL_HEXES ? 3 : 2;
        for (int k = 1; k <= rings; k++) {
            float x = k / (float) rings, scale = 1 - PLATEAU * x;
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
                inner[i] = new Vector3(px, py, surface - bed);
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
        return Math.min(Math.max(0, surface - bottom) * FALL_LIP_DROP, FALL_LIP_WIDTH * BoardGeometry.WIDTH);
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
            float s = index % SHORE_SEGMENTS / (float) SHORE_SEGMENTS;
            if (crest == null && anchor && crests[previous] != null) {
                crest = crests[previous];
                s = 1;
            }
            if (crest != null) {
                result[index] = new Vector3(crestLine[index])
                      .mulAdd(crest.normal(s), anchor ? -Math.max(lip[edge], lip[previous]) : -lip[edge]);
            }
        }
        return result;
    }

    private Vector3 shoreLip(Vector3 waterline, Vector3 boundary) {
        Vector3 lip = new Vector3(waterline).lerp(boundary,
              Math.min(1, 5 * BoardGeometry.HEX_SCALE / waterline.dst(boundary)));
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
        shoreHexes(scene);
        float scale = BoardGeometry.HEX_SCALE;
        shoreCorners = new Vector3[6];
        for (int k = 0; k < 6; k++) {
            float[] shift = relief.shoreShift(k);
            shoreCorners[k] = new Vector3(corners[k]).add(shift[0], shift[1], 0);
        }
        // The banks the water keeps, as lines it stays inside: along each bank edge the shore's bank beside land at the
        // water's level, a beach beside walls and lower land, the slope's reach and a wet margin beside a slope up from
        // the water, which runs on under it to the bed; and clear of the corners the land rounds into the hex.
        List<float[]> keep = new ArrayList<>(12);
        float[] widths = new float[6];
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            if (inset[k] > 0) {
                widths[k] = relief.slope(k) || rise[k] != 0 ? inset[k] : SHORE_BANK * scale;
                Vector3 inward = new Vector3(b).sub(a).crs(Vector3.Z).nor().scl(-1);
                keep.add(new float[] { a.x, a.y, inward.x, inward.y, widths[k] });
            }
            float in = relief.cornerInset(k);
            if (in > 0) {
                Vector3 inward = new Vector3(center.x - corners[k].x, center.y - corners[k].y, 0).nor();
                keep.add(new float[] { corners[k].x, corners[k].y, inward.x, inward.y, in + HUG * scale });
            }
        }
        banks = keep.toArray(new float[0][]);
        // Each bank's line, moved in by its width, from end to end: the bank points are sought on the way out to it,
        // so they leave a mouth's end into the hex, whatever steps through its corner reach into the mouth.
        Vector3[] envelope = contour(widths, plunge, z);
        Vector3[] anchors = new Vector3[6];
        for (int k = 0; k < 6; k++) {
            int before = (k + 5) % 6, next = (k + 1) % 6;
            boolean in = inset[before] == 0, out = inset[k] == 0;
            if (in && out) {
                anchors[k] = new Vector3(corners[k]);
            } else if (in || out) {
                Vector3 other = in ? corners[before] : corners[next];
                int mouth = in ? before : k;
                float end = mouthEnd(k, other, plunge[mouth]);
                anchors[k] = new Vector3(corners[k]).lerp(other, end / corners[k].dst(other));
                // Across the mouth, onto the seam the relief lays there, which bends where steps through the corner
                // move it; along it, where the mouth ends.
                Vector3 across = edgeInward(mouth);
                anchors[k].mulAdd(across, new Vector3(relief.seam(mouth, k, end)).sub(anchors[k]).dot(across));
            } else {
                anchors[k] = along(envelope[k], plunge);
            }
            anchors[k].z = z;
        }
        Vector3[] result = new Vector3[6 * SHORE_SEGMENTS];
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6;
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                float t = segment / (float) SHORE_SEGMENTS;
                Vector3 point = inset[edge] == 0 ? mouthPoint(anchors[edge], anchors[next], segment)
                      : segment == 0 ? new Vector3(anchors[edge])
                      : along(new Vector3(envelope[edge]).lerp(envelope[next], t), plunge);
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        return result;
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
     * How far from corner k toward {@code other} an open mouth beside a bank ends, negative past the corner. Past a
     * land corner the shore moves it ends where the shore field turns, but a bank short of where the land's corner now
     * stands; elsewhere as {@link #mouthLimit} says. Both hexes of the mouth compute it from the same corners and the
     * same field, so they end the mouth at the same point.
     */
    private float mouthEnd(int k, Vector3 other, boolean plunge) {
        float least = mouthLimit(k, plunge), shift = shoreCorners[k].dst(corners[k]);
        if (shift == 0) { return least; }
        Vector3 c = corners[k];
        float length = c.dst(other), dx = (c.x - other.x) / length, dy = (c.y - other.y) / length;
        float mx = (c.x + other.x) / 2, my = (c.y + other.y) / 2, span = length / 2 + shift;
        float dry = firstDry(mx, my, mx + dx * span, my + dy * span, null);
        return dry < 0 ? least : Math.max(least, length / 2 - dry * span);
    }

    /**
     * The nearest corner k an open mouth beside a bank may end: past a land corner the shore moves, a bank and a unit
     * short of where that corner now stands; elsewhere four units less land than a beach (below a fall, nearly at the
     * corner, so the whole sheet lands in water), measured along the mouth, and beyond whatever steps through the
     * corner reach into the mouth.
     */
    private float mouthLimit(int k, boolean plunge) {
        float scale = BoardGeometry.HEX_SCALE, shift = shoreCorners[k].dst(corners[k]);
        return shift > 0 ? (SHORE_BANK + 1) * scale - shift
              : Math.max((BEACH - (plunge ? PLUNGE_OPENING : MOUTH_OPENING)) * scale * 1.1547005f,
                    relief.cornerReach(k) + HUG * scale);
    }

    /**
     * The envelope the bank points are sought toward: each bank edge's line moved in by {@code widths}, from where it
     * meets the next bank's to where it meets the next, or from the end of an open mouth beside it.
     */
    private Vector3[] contour(float[] widths, boolean[] plunge, float z) {
        Vector3[] result = new Vector3[6];
        for (int vertex = 0; vertex < 6; vertex++) {
            int before = (vertex + 5) % 6;
            Vector3 a = shoreCorners[before], b = shoreCorners[vertex], c = shoreCorners[(vertex + 1) % 6];
            Vector3 n1 = new Vector3(b).sub(a).crs(Vector3.Z).nor();
            Vector3 n2 = new Vector3(c).sub(b).crs(Vector3.Z).nor();
            float d1 = n1.dot(a) - widths[before], d2 = n2.dot(b) - widths[vertex];
            float determinant = n1.x * n2.y - n1.y * n2.x;
            result[vertex] = new Vector3((d1 * n2.y - n1.y * d2) / determinant, (n1.x * d2 - d1 * n2.x) / determinant,
                  z);
        }
        for (int edge = 0; edge < 6; edge++) {
            if (widths[edge] > 0) { continue; }
            int next = (edge + 1) % 6;
            float length = corners[edge].dst(corners[next]);
            if (widths[(edge + 5) % 6] > 0) {
                result[edge].set(corners[edge]).lerp(corners[next], mouthLimit(edge, plunge[edge]) / length).z = z;
            }
            if (widths[next] > 0) {
                result[next].set(corners[next]).lerp(corners[edge], mouthLimit(next, plunge[edge]) / length).z = z;
            }
        }
        return result;
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
            float d = (float) Math.hypot(x - middle.x, y - middle.y) / (BoardGeometry.WIDTH / 2);
            value += PLUNGE_POOL * scale * (1 - BoardRelief.smooth(d));
        }
        for (float[] bank : banks) {
            value = BoardRelief.smoothMin(value, (x - bank[0]) * bank[2] + (y - bank[1]) * bank[3] - bank[4],
                  SHORE_ROUND * scale);
        }
        return value;
    }

    /**
     * This hex's and its neighbours' centres and weights for {@link #shore}, in a fixed order: water this hex's water
     * joins pulls the shore toward itself (1), and so does land at another level, whose banks this hex keeps by the
     * relief's rules instead ({@link #wet}); land at the water's level pushes it away, natural ground (-1) to the
     * midline between them, other ground ({@link #SHORE_HARD}) back short of the land's corners, which stay put. Beyond
     * the board's edge a hex counts as the nearest board hex does, so a river runs straight on into the edge.
     */
    private void shoreHexes(BoardScene scene) {
        List<Coords> around = new ArrayList<>(7);
        around.add(tile.coords());
        for (int direction = 0; direction < 6; direction++) { around.add(tile.coords().translated(direction)); }
        around.sort((a, b) -> a.getX() != b.getX() ? Integer.compare(a.getX(), b.getX())
              : Integer.compare(a.getY(), b.getY()));
        shoreHexes = new float[3 * around.size()];
        for (int i = 0; i < around.size(); i++) {
            Coords coords = around.get(i);
            BoardScene.Tile other = scene.tile(coords);
            if (other == null) {
                other = scene.tile(new Coords(Math.clamp(coords.getX(), 0, scene.width() - 1),
                      Math.clamp(coords.getY(), 0, scene.height() - 1)));
            }
            shoreHexes[3 * i] = BoardGeometry.centerX(coords);
            shoreHexes[3 * i + 1] = BoardGeometry.centerY(coords);
            shoreHexes[3 * i + 2] = other == null || tile.liquid().connects(other.liquid())
                  || other.elevation() != tile.elevation() ? 1 : relief.shoreGround(other.coords()) ? -1 : -SHORE_HARD;
        }
    }

    /**
     * The shore field at (x, y), in world units: roughly how far the point lies on the water's side of the midline
     * between this hex's water and the land at its level around it, less the bank's bias and plus its slow wander. Each
     * hex centre within {@link #SHORE_RADIUS} pulls it by its weight times (1 - d^2 / r^2)^2; the field divided by its
     * slope measures distance. Only this hex's neighbours come within that reach of any point this hex evaluates, even
     * a mouth's end past a corner the shore moves, and they are summed in a fixed order, so every hex that evaluates a
     * point gets the same value.
     */
    private float shore(float x, float y) {
        float scale = BoardGeometry.HEX_SCALE, r2 = square(SHORE_RADIUS * scale), f = 0, gx = 0, gy = 0;
        for (int i = 0; i < shoreHexes.length; i += 3) {
            float dx = x - shoreHexes[i], dy = y - shoreHexes[i + 1], t = 1 - (dx * dx + dy * dy) / r2;
            if (t <= 0) { continue; }
            f += shoreHexes[i + 2] * t * t;
            gx += shoreHexes[i + 2] * t * dx;
            gy += shoreHexes[i + 2] * t * dy;
        }
        float slope = 4 / r2 * (float) Math.hypot(gx, gy);
        float wander = Math.clamp(BoardRelief.gradient(x / (WANDER_CELL * scale) + 3.1f,
              y / (WANDER_CELL * scale) - 1.7f), -1, 1);
        return f / Math.max(slope, 1e-4f / scale) - (SHORE_BIAS - SHORE_WANDER * wander) * scale;
    }

    /**
     * How far a bank on {@code edge} keeps the water in from the edge: beside a slope up from the water, which runs on
     * under it to the bed, a wet margin beyond how far the slope's foot reaches into the hex; elsewhere a beach.
     */
    private float least(int edge) {
        float scale = BoardGeometry.HEX_SCALE;
        return relief.slope(edge) ? relief.reach(edge) + HUG * scale : BEACH * scale;
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
            Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
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
