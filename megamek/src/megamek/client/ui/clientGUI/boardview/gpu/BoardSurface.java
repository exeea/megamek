/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.function.BiPredicate;

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
          BoardLiquid liquid, boolean detailedGround, List<BoardScene.Feature> features, int ramps,
          List<BoardConcrete.Shift> coast, BoardRoad.Kind road, BoardScene.Biome biome, int cliffTopExits, boolean bare,
          BoardSurfaceBlend.Cover groundCover) { }

    /** What of a hex further out can reach a hex's shape: through the water's shore, its level, liquid and ground. */
    record Shape(int elevation, int waterDepth, BoardLiquid liquid, boolean detailedGround, BoardScene.Surface surface,
          int roadExits, BoardRoad.Kind road, int cliffTopExits) { }

    /** A hex's own geometry and its six neighbours', and the shapes of the hexes out to {@link #SHORE_RINGS}. */
    record Key(List<Geometry> near, List<Shape> far) { }

    /**
     * How many hexes away a hex's shape can move another's water shore, its banks or the corners the shore moves (see
     * {@link BoardRelief#shore}): the field reaches about two hexes, each hex's pull depends on its neighbours, and the
     * land round a corner allows its move by the moves of all its own corners.
     */
    static final int SHORE_RINGS = 6;
    /** Half a road's width where it ramps through a hex edge to the next hex, in hex-scale units. */
    static final float ROAD_MOUTH = 9;

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
                far.add(shape(other, other.waterDepth()));
            }
        }
        return new Key(Collections.unmodifiableList(near), List.copyOf(far));
    }

    /** The hex's shape as a hex further out reads it, with the given water depth. */
    static Shape shape(BoardScene.Tile tile, int waterDepth) {
        return new Shape(tile.elevation(), waterDepth, tile.liquid(), tile.detailedGround(), tile.surface(),
              tile.roadExits(), tile.road(), tile.cliffTopExits());
    }

    private static Geometry geometry(BoardScene scene, BoardScene.Tile tile) {
        return tile == null ? null : new Geometry(tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
              tile.surface(), tile.liquid(), tile.detailedGround(), tile.features(), ramps(scene, tile),
              BoardConcrete.of(scene).corners(tile.coords()), tile.road(), tile.biome(), tile.cliffTopExits(), tile.bare(),
              tile.groundCover());
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
    private static final int BANK_TRACE = 3;
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
    private static final int SHORE_HALVINGS = 8;
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

    static Tuning tuning() {
        TerrainSettings settings = TerrainSettings.current();
        return settings == null ? tuning : settings.water();
    }

    static void tune(Tuning next) {
        if (next.equals(tuning)) { return; }
        tuning = next;
        BoardGeometry.terrainChanged();
    }
    /** DRESSING is thin detail on the ground, such as tree pits: drawn and picked, never raising what stands there. */
    enum Finish { TOP, RIM, CAP, WALL, OUTCROP, SHORE, BED, BANK, ICE, DRESSING, ROUGH }
    /** landEdge is the edge a bank or wall face stands on (bank artwork takes the hex across it); else -1. */
    record Face(Vector3 a, Vector3 b, Vector3 c, Finish finish, int landEdge) {
        Face(Vector3 a, Vector3 b, Vector3 c, Finish finish) {
            this(a, b, c, finish, -1);
        }

        /** Height of the face above a point, or negative infinity when the point lies outside it. */
        float height(float x, float y) {
            return height(x, y, .001f * BoardGeometry.hexScale());
        }

        private float height(float x, float y, float tolerance) {
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
    /** Rough cover is part of the drawn mesh; ordinary units may clip it, infantry can seek footing on it. */
    final List<Face> rough = new ArrayList<>();
    /**
     * A frozen lake as drawn: its slab; the broken pieces round it (cut edges, the ragged margin over open water,
     * floes and shore rubble); and the open leads along ice-free shores. Support and picking use the ICE faces.
     */
    final List<Face> iceSlab = new ArrayList<>(), iceBroken = new ArrayList<>(), iceLeads = new ArrayList<>();
    private List<BoardRough.Placement> roughModels = List.of();

    List<BoardRough.Placement> roughModels() { return roughModels; }
    final List<Vector3> water = new ArrayList<>();
    /** The surface overlap inside cliff recesses; these same faces also belong to waterFaces. */
    final List<Face> cliffWater = new ArrayList<>();
    /**
     * The water's full outline within its hex, before a falling mouth pulls its surface back to the crest. Segments on
     * open mouths continue into the next pool; every other segment is a bank.
     */
    final List<Vector3> outline = new ArrayList<>();
    final List<Face> waterFaces = new ArrayList<>();
    /** Extra contacts where the solid cliff's triangle edges bend the constructed water outline. */
    private final Map<Integer, List<Vector3>> waterContacts = new LinkedHashMap<>();
    /** The water's cut face where the board's edge cuts it off, facing out; drawn like its surface. */
    final List<Face> cutFaces = new ArrayList<>();
    /** Edges a fall pours over off the board's edge, as a bit mask; see {@link #bottomless}. */
    private int offBoard;
    final List<Side> waterfalls = new ArrayList<>();
    final int ramps;
    /** Curved road cuts/fills, shared by the terrain triangles and their shading. Support samples the triangles. */
    private final List<RoadRamp> roadRamps = new ArrayList<>();
    /** Engineered walls inside a concrete hex, between its level slab and a cut or raised road. */
    final List<Face> retainingWalls = new ArrayList<>();
    /** Precast walls retaining a slope's toe that the road beside it cuts back (BoardRelief#retainingWalls). */
    final List<Face> retainingPanels = new ArrayList<>();
    final BoardRelief relief;
    private final TerrainLod lod;
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
    /** Optional render-only bed. Optical depth, support, rocks and neighbouring walls always use {@link #faces}. */
    private List<Face> renderBed;
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

    /** Finished water geometry only: no scene, procedural relief cache or neighbouring surface is retained. */
    record WaterGeometry(Coords coords, TerrainLod lod, int ramps, List<Face> faces, List<Vector3> water,
          List<Face> cliffWater, List<Vector3> outline, List<Face> waterFaces, Map<Integer, List<Vector3>> contacts,
          List<Face> cutFaces, List<Side> waterfalls, List<Face> edgeTopography, int offBoard, int openMouths,
          List<Face> rough, List<BoardRough.Placement> roughModels, List<Face> iceSlab, List<Face> iceBroken,
          List<Face> iceLeads,
          boolean graded, Vector3[] joinsA, Vector3[] joinsB, Crest[] crests, Vector3[] bedOutline,
          Vector3[] waterline, Vector3[] crestLine, Vector3[] shoreCorners) {
        BoardSurface surface(BoardScene scene) { return new BoardSurface(scene, this); }
    }

    WaterGeometry waterGeometry() {
        return new WaterGeometry(tile.coords(), lod, ramps, List.copyOf(faces), List.copyOf(water),
              List.copyOf(cliffWater), List.copyOf(outline), List.copyOf(waterFaces), Map.copyOf(waterContacts),
              List.copyOf(cutFaces), List.copyOf(waterfalls), List.copyOf(edgeTopography), offBoard, openMouths,
              List.copyOf(rough), roughModels, List.copyOf(iceSlab), List.copyOf(iceBroken), List.copyOf(iceLeads),
              gradedWater, joinA.clone(), joinB.clone(), crests.clone(), bedOutline, waterline, crestLine, shoreCorners);
    }

    /** Fresh query context around unchanged finished water; rebuilding its many bed/cliff triangles is unnecessary. */
    private BoardSurface(BoardScene scene, WaterGeometry geometry) {
        this.scene = scene;
        tile = scene.tile(geometry.coords());
        lod = geometry.lod();
        ramps = geometry.ramps();
        center = BoardGeometry.center(tile.coords(), tile.elevation());
        for (int edge = 0; edge < 6; edge++) { corners[edge] = BoardGeometry.corner(tile.coords(), tile.elevation(), edge); }
        relief = new BoardRelief(scene, tile, ramps, lod);
        faces.addAll(geometry.faces());
        rough.addAll(geometry.rough());
        roughModels = geometry.roughModels();
        iceSlab.addAll(geometry.iceSlab());
        iceBroken.addAll(geometry.iceBroken());
        iceLeads.addAll(geometry.iceLeads());
        water.addAll(geometry.water());
        cliffWater.addAll(geometry.cliffWater());
        outline.addAll(geometry.outline());
        waterFaces.addAll(geometry.waterFaces());
        waterContacts.putAll(geometry.contacts());
        cutFaces.addAll(geometry.cutFaces());
        waterfalls.addAll(geometry.waterfalls());
        edgeTopography.addAll(geometry.edgeTopography());
        offBoard = geometry.offBoard();
        openMouths = geometry.openMouths();
        gradedWater = geometry.graded();
        System.arraycopy(geometry.joinsA(), 0, joinA, 0, 6);
        System.arraycopy(geometry.joinsB(), 0, joinB, 0, 6);
        System.arraycopy(geometry.crests(), 0, crests, 0, 6);
        bedOutline = geometry.bedOutline();
        waterline = geometry.waterline();
        crestLine = geometry.crestLine();
        shoreCorners = geometry.shoreCorners();
    }

    BoardSurface(BoardScene scene, BoardScene.Tile tile) {
        this(scene, tile, TerrainLod.FULL);
    }

    BoardSurface(BoardScene scene, BoardScene.Tile tile, TerrainLod lod) {
        this(scene, tile, true, lod);
    }

    private BoardSurface(BoardScene scene, BoardScene.Tile tile, boolean detailed, TerrainLod lod) {
        this.lod = lod;
        this.scene = scene;
        this.tile = tile;
        center = BoardGeometry.center(tile.coords(), tile.elevation());
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
        }
        ramps = ramps(scene, tile);
        // The relief first: a water hex lays out its waterline round the steps the relief puts beside it.
        relief = new BoardRelief(scene, tile, ramps, lod);
        if (tile.liquid().present()) {
            river(scene);
        } else if (ramps != 0 || BoardRoad.rendered(tile)) {
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
            if (ramps != 0 && relief.graded() && tile.surface() != BoardScene.Surface.CONCRETE) {
                roadRelief();
                simplifyRoad();
                roadRimNormals();
            }
            int falls = 0;
            for (int edge = 0; edge < 6; edge++) { falls |= crests[edge] == null ? 0 : 1 << edge; }
            // Banks meet the actual ground rim, including emerged shallow bars, while the water keeps its level.
            relief.top(faces, bedOutline, openMouths, crestLine, falls);
            meetWetCliffs();
            relief.retainingWalls(retainingPanels);
            int roughStart = faces.size();
            relief.boulders(faces);
            roughModels = BoardRough.place(this);
            rough.addAll(faces.subList(roughStart, faces.size()));
        }
    }

    /**
     * A cliff's submerged foot may lean toward the inset bed. Water meets that face at the surface height, not
     * vertically above the bed's edge. Move only the outer ring: the bed and the stream's rounded interior stay put.
     */
    private void meetWetCliffs() {
        if (waterFaces.isEmpty()) { return; }
        float base = tile.elevation() * BoardGeometry.level();
        boolean adjusted = false;
        for (int edge = 0; edge < 6; edge++) {
            if (!relief.wetCliff(edge)) { continue; }
            adjusted = true;
            final int cliffEdge = edge;
            List<Face> wall = faces.stream().filter(f -> f.finish() == Finish.WALL && f.landEdge() == cliffEdge).toList();
            Map<Float, List<ContactSegment>> sections = new LinkedHashMap<>();
            Vector3 toward = BoardGeometry.center(neighbor(scene, edge).coords(), 0)
                  .sub(BoardGeometry.center(tile.coords(), 0)).nor();
            // Keep the shared mouth endpoints and the bank joins exactly where the neighbouring hex puts them.
            for (int i = 1; i < SHORE_SEGMENTS; i++) {
                Vector3 point = waterline[edge * SHORE_SEGMENTS + i];
                float height = point.z;
                if (point.z >= base) {
                    point.set(relief.wetCliffContact(edge, i / (float) SHORE_SEGMENTS, point.z));
                } else {
                    point.set(contactPoint(point, toward, sections.computeIfAbsent(height, z -> contactSection(wall, z))));
                }
                point.z = height;
            }
            refineCliffContact(edge, wall, base, sections);
        }
        if (adjusted && !gradedWater) {
            // A flat pool's old ears can cross its widened contour at a cliff/bank corner.
            List<Vector3> contour = new ArrayList<>();
            for (int edge = 0; edge < 6; edge++) {
                List<Vector3> points = waterBoundary(edge);
                // A falling lip keeps its pulled-back water vertices.
                for (int i = 0; i < points.size() - 1; i++) {
                    contour.add(waterContacts.containsKey(edge) ? points.get(i) : water.get(edge * SHORE_SEGMENTS + i));
                }
            }
            waterFaces.clear();
            polygon(contour.toArray(Vector3[]::new), Finish.TOP, waterFaces);
        }
        if (adjusted && gradedWater) {
            // Carry the contact sheet a short distance inside the rock. The cliff hides this overlap; its
            // undercuts then contain water too, instead of exposing an air gap behind the surface's outline.
            for (int edge = 0; edge < 6; edge++) {
                if (!relief.wetCliff(edge)) { continue; }
                Vector3 into = BoardGeometry.center(neighbor(scene, edge).coords(), 0)
                      .sub(BoardGeometry.center(tile.coords(), 0)).nor().scl(BoardRelief.metres(2));
                List<Vector3> boundary = waterBoundary(edge);
                for (int i = 0; i + 1 < boundary.size(); i++) {
                    Vector3 a = boundary.get(i), b = boundary.get(i + 1);
                    if (Math.max(a.z, b.z) <= base) { continue; }
                    if ((b.x - a.x) * into.y - (b.y - a.y) * into.x >= 0) { continue; }
                    Vector3 outerA = new Vector3(a).add(into), outerB = new Vector3(b).add(into);
                    cliffWater.add(new Face(b, a, outerA, Finish.TOP));
                    cliffWater.add(new Face(b, outerA, outerB, Finish.TOP));
                }
            }
            waterFaces.addAll(cliffWater);
        }
    }

    /** The drawn outline of one edge, including both ends; mouths retain their shared construction samples. */
    List<Vector3> waterBoundary(int edge) {
        List<Vector3> contact = waterContacts.get(edge);
        if (contact != null) { return contact; }
        List<Vector3> points = new ArrayList<>(SHORE_SEGMENTS + 1);
        for (int i = 0; i <= SHORE_SEGMENTS; i++) { points.add(waterline[(edge * SHORE_SEGMENTS + i) % waterline.length]); }
        return points;
    }

    /** Preserve the bends across a wall quad's diagonal, especially where its bed rises into a dry bank. */
    private void refineCliffContact(int edge, List<Face> wall, float base, Map<Float, List<ContactSegment>> sections) {
        List<Vector3> points = waterBoundary(edge), refined = new ArrayList<>();
        for (int i = 0; i < points.size() - 1; i++) {
            Vector3 a = points.get(i), b = points.get(i + 1);
            List<Vector3> path = Math.abs(a.z - b.z) < .0001f && a.z < base
                  ? contactPath(a, b, sections.computeIfAbsent(a.z, z -> contactSection(wall, z))) : List.of(a, b);
            refined.addAll(path.subList(0, path.size() - 1));
            if (gradedWater && path.size() > 2) {
                // Replace just the outer triangle; the remaining rings retain the stream's bulge.
                for (int f = 0, count = waterFaces.size(); f < count; f++) {
                    Face face = waterFaces.get(f);
                    if (face.a() != a || face.b() != b) { continue; }
                    waterFaces.remove(f);
                    for (int k = 0; k < path.size() - 1; k++) {
                        waterFaces.add(new Face(path.get(k), path.get(k + 1), face.c(), Finish.TOP));
                    }
                    break;
                }
            }
        }
        refined.add(points.getLast());
        waterContacts.put(edge, refined);
    }

    private record ContactSegment(Vector3 a, Vector3 b) { }

    /** Nearest wall contact along a horizontal line; all scratch data belongs to this surface's build. */
    private static Vector3 contactPoint(Vector3 point, Vector3 toward, List<ContactSegment> section) {
        Vector3 contact = new Vector3(point);
        float nearest = Float.POSITIVE_INFINITY;
        for (ContactSegment segment : section) {
            Vector3 a = segment.a(), b = segment.b();
            float cross = toward.x * (b.y - a.y) - toward.y * (b.x - a.x);
            if (Math.abs(cross) < .000001f) { continue; }
            float t = ((a.x - point.x) * toward.y - (a.y - point.y) * toward.x) / cross;
            if (t < 0 || t > 1) { continue; }
            Vector3 hit = new Vector3(a).lerp(b, t);
            if (hit.dst2(point) < nearest) {
                nearest = hit.dst2(point);
                contact.set(hit);
            }
        }
        return contact;
    }

    /** Horizontal sections through the actual submerged wall triangles, without assuming planar quads. */
    private static List<ContactSegment> contactSection(List<Face> wall, float z) {
        List<ContactSegment> result = new ArrayList<>();
        for (Face face : wall) {
            Vector3[] corners = { face.a(), face.b(), face.c() };
            Vector3 first = null;
            for (int i = 0; i < 3; i++) {
                Vector3 a = corners[i], b = corners[(i + 1) % 3];
                if ((a.z <= z) == (b.z <= z)) { continue; }
                Vector3 p = new Vector3(a).lerp(b, (z - a.z) / (b.z - a.z));
                p.z = z;
                if (first == null) { first = p; }
                else if (first.dst2(p) > .000001f) { result.add(new ContactSegment(first, p)); }
            }
        }
        return result;
    }

    /** Walk either way along a connected section, starting and ending inside a segment when necessary. */
    private static List<Vector3> contactPath(Vector3 a, Vector3 b, List<ContactSegment> section) {
        for (int first = 0; first < section.size(); first++) {
            ContactSegment start = section.get(first);
            if (!onContact(a, start.a, start.b)) { continue; }
            for (Vector3 end : List.of(start.a, start.b)) {
                List<Vector3> path = new ArrayList<>(List.of(a));
                Vector3 from = a, to = end;
                int previous = first;
                for (int step = 0; step <= section.size(); step++) {
                    if (onContact(b, from, to)) {
                        if (path.size() > 1 && path.getLast().epsilonEquals(b, .002f)) { path.set(path.size() - 1, b); }
                        else { path.add(b); }
                        return path;
                    }
                    if (!path.getLast().epsilonEquals(to, .002f)) { path.add(to); }
                    int next = -1;
                    for (int k = 0; k < section.size(); k++) {
                        if (k == previous) { continue; }
                        ContactSegment candidate = section.get(k);
                        if (to.epsilonEquals(candidate.a, .002f) || to.epsilonEquals(candidate.b, .002f)) {
                            next = k;
                            break;
                        }
                    }
                    if (next < 0) { break; }
                    from = to;
                    ContactSegment candidate = section.get(next);
                    to = from.epsilonEquals(candidate.a, .002f) ? candidate.b : candidate.a;
                    previous = next;
                }
            }
        }
        return List.of(a, b);
    }

    private static boolean onContact(Vector3 p, Vector3 a, Vector3 b) {
        float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
        float t = length == 0 ? 0 : Math.clamp(((p.x - a.x) * dx + (p.y - a.y) * dy) / length, 0, 1);
        return square(p.x - a.x - t * dx) + square(p.y - a.y - t * dy) < .000004f;
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

    static BoardScene.Feature connectingBridge(BoardScene.Tile road, BoardScene.Tile bridge, int direction) {
        if (road.liquid().present() || (road.roadExits() & (1 << direction)) == 0) {
            return null;
        }
        int reverse = (direction + 3) % 6;
        boolean continuation = !bridge.liquid().present() && (bridge.roadExits() & (1 << reverse)) != 0;
        if (continuation && road.elevation() == bridge.elevation()) {
            return null;
        }
        // A level deck wins over a sloped ground road; a sloped deck is the last connected-road fallback.
        return bridge.features().stream().filter(feature -> feature.asset().equals("bridge")
              && Math.abs(bridge.elevation() + feature.elevation() - road.elevation()) <= 1
              && (!continuation || bridge.elevation() + feature.elevation() == road.elevation())
              && (feature.bridgeExits() & (1 << reverse)) != 0).findFirst().orElse(null);
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
        int source = tuning().fallsOffBoard() && !tile.frozen() ? riverEnd(scene) : -1;
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
                              BoardGeometry.waterZ(tile) - tuning().bottomlessLevels() * BoardGeometry.level());
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
        Vector3[] surface = pulledBack(crestLine, lip);
        basin(scene, waterline, crestLine, surface, shore, rise, plunge);
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
            outline.addAll(List.of(waterline));
            freeze(scene, shore);
        } else {
            // The water recedes from each crest; the sheet's lip curves back down over it.
            outline.addAll(List.of(waterline));
            water.addAll(List.of(surface));
            if (!tile.liquid().molten()) {
                if (gradedWater) { slopingSurface(surface); }
                else { polygon(surface, Finish.TOP, waterFaces); }
            }
            for (int edge = 0; edge < 6; edge++) {
                BoardScene.Tile other = neighbor(scene, edge);
                if (crests[edge] != null) {
                    // Off the board's edge a fall drops into nothing and fades out on its way down.
                    float bottom = other == null ? BoardGeometry.waterZ(tile) - tuning().bottomlessLevels() * BoardGeometry.level()
                          : BoardGeometry.waterZ(other);
                    waterfalls.add(new Side(new Vector3(crests[edge].a()), new Vector3(crests[edge].b()), bottom,
                          bottom, edge));
                } else if (other == null) {
                    cut(edge);
                }
            }
        }
    }

    /** Tile pixels a lake slab reaches below open water; its freeboard is the pixel between the water and its level. */
    private static final float ICE_DRAFT = 1.5f;
    /** Tile pixels a frozen edge breaks out over open water at most. */
    private static final float ICE_MARGIN = 6;
    /** Tile pixels a slab breaks off short of an ice-free shore at most, opening a lead along it. */
    private static final float ICE_LEAD = 4;

    /**
     * A frozen lake's slab. Its top is the level units stand on, one pixel of freeboard above open water. It runs on
     * through mouths into frozen neighbours at its level and up banks into frozen land. Toward open water it breaks
     * out raggedly over the neighbour's water; along an ice-free shore it breaks off short of the waterline, leaving
     * a lead with rubble. It shows its thickness wherever it ends. All of that is drawn only: support and picking
     * keep the waterline.
     */
    private void freeze(BoardScene scene, float[] shore) {
        float scale = BoardGeometry.hexScale(), top = BoardGeometry.surfaceZ(tile), water = BoardGeometry.waterZ(tile);
        float bottom = water - ICE_DRAFT * scale;
        int n = waterline.length;
        Vector3[] slab = new Vector3[n];
        float area = 0;
        for (int i = 0; i < n; i++) {
            slab[i] = new Vector3(waterline[i].x, waterline[i].y, top);
            Vector3 next = waterline[(i + 1) % n];
            area += waterline[i].x * next.y - next.x * waterline[i].y;
        }
        // Outward is to the right of the contour's direction when it runs counter-clockwise.
        float outward = Math.signum(area);
        boolean[] ragged = new boolean[6], continuous = new boolean[6], iced = new boolean[6], broken = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            boolean level = shore[edge] == 0 && other != null && other.elevation() == tile.elevation();
            continuous[edge] = level && other.frozen();
            ragged[edge] = level && !other.frozen();
            // Frozen land's ice runs down the bank to the slab; a cut edge there would split the sheet.
            iced[edge] = shore[edge] > 0 && other != null && other.frozen() && !other.liquid().present();
            // The board's edge cuts the slab straight, as it cuts all water.
            broken[edge] = other != null && !continuous[edge] && !ragged[edge] && !iced[edge];
        }
        // Support: the waterline, straight edges by their corners alone.
        List<Vector3> support = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (turns(slab[(i + n - 1) % n], slab[i], slab[(i + 1) % n])) { support.add(slab[i]); }
        }
        polygon(support.toArray(Vector3[]::new), Finish.ICE, faces);
        // Drawn: the waterline's samples, which the ragged margins share, and twice as many along broken shores.
        List<Vector3> base = new ArrayList<>();
        List<Integer> edges = new ArrayList<>();
        List<Boolean> onShore = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int edge = i / SHORE_SEGMENTS;
            base.add(slab[i]);
            edges.add(edge);
            onShore.add(broken[edge] && (i % SHORE_SEGMENTS != 0 || broken[(edge + 5) % 6]));
            if (broken[edge]) {
                base.add(new Vector3(slab[i]).lerp(slab[(i + 1) % n], .5f));
                edges.add(edge);
                onShore.add(true);
            }
        }
        int m = base.size();
        float[] recess = shoreBreak(onShore, scale);
        Vector3[] drawn = new Vector3[m];
        for (int k = 0; k < m; k++) {
            Vector3 before = base.get((k + m - 1) % m), after = base.get((k + 1) % m);
            Vector3 inward = new Vector3(before.y - after.y, after.x - before.x, 0).nor().scl(outward);
            drawn[k] = recess[k] == 0 ? base.get(k) : new Vector3(base.get(k)).mulAdd(inward, recess[k]);
        }
        List<Vector3> outline = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            if (ragged[edges.get(k)] || recess[k] > 0 || turns(drawn[(k + m - 1) % m], drawn[k], drawn[(k + 1) % m])) {
                outline.add(drawn[k]);
            }
        }
        polygon(outline.toArray(Vector3[]::new), Finish.ICE, iceSlab);
        Random random = new Random(tile.coords().getX() * 0x9E3779B97F4A7C15L ^ tile.coords().getY() * 0xC2B2AE3D27D4EB4FL);
        for (int k = 0; k < m; k++) {
            int edge = edges.get(k), next = (k + 1) % m;
            if (continuous[edge] || iced[edge] || ragged[edge]) { continue; }
            iceSkirt(iceBroken, drawn[k], drawn[next], bottom, outward);
            if (recess[k] > 0 || recess[next] > 0) {
                Vector3 a = new Vector3(drawn[k].x, drawn[k].y, water), b = new Vector3(drawn[next].x, drawn[next].y, water);
                Vector3 c = new Vector3(base.get(next).x, base.get(next).y, water);
                Vector3 d = new Vector3(base.get(k).x, base.get(k).y, water);
                iceFace(iceLeads, a, b, c, Vector3.Z);
                iceFace(iceLeads, a, c, d, Vector3.Z);
            }
            // Rubble of the broken edge floats in the wider stretches of the lead.
            if (recess[k] > 2.2f * scale && random.nextFloat() < .4f) {
                Vector3 centre = new Vector3(drawn[k]).lerp(base.get(k), .5f);
                piece(centre, Math.min(.3f * recess[k], 1.4f * scale) * (.6f + .4f * random.nextFloat()),
                      water + .35f * scale, water - .8f * scale, random);
            }
        }
        for (int edge = 0; edge < 6; edge++) {
            if (!ragged[edge]) { continue; }
            int first = edge * SHORE_SEGMENTS;
            Vector3 start = slab[first], end = slab[(first + SHORE_SEGMENTS) % n];
            Vector3 out = new Vector3(end.y - start.y, start.x - end.x, 0).nor().scl(outward);
            Vector3 opening = cornerBreak(scene, edge, edge, ragged, continuous);
            Vector3 closing = cornerBreak(scene, edge, edge + 1, ragged, continuous);
            // A fracture line: straight runs between irregular breaks.
            Random front = new Random(Float.floatToIntBits(start.x + end.x) * 0x9E3779B97F4A7C15L
                  ^ Float.floatToIntBits(start.y + end.y));
            int breaks = 7;
            float[] at = new float[breaks + 1], reach = new float[breaks + 1];
            for (int k = 0; k <= breaks; k++) {
                at[k] = k == 0 || k == breaks ? k / (float) breaks : (k + (front.nextFloat() - .5f) * .7f) / breaks;
                reach[k] = ICE_MARGIN * scale * (.2f + .8f * front.nextFloat());
            }
            // Two margin samples per waterline sample carry the breaks. Near each corner the line eases into the
            // point it shares with the front beyond that corner, or into the corner itself where the front ends.
            Vector3[] margin = new Vector3[2 * SHORE_SEGMENTS + 1];
            for (int j = 0, k = 0; j < margin.length; j++) {
                float t = j / (float) (margin.length - 1);
                Vector3 point = j % 2 == 0 ? slab[(first + j / 2) % n]
                      : new Vector3(slab[(first + j / 2) % n]).lerp(slab[(first + j / 2 + 1) % n], .5f);
                while (k < breaks - 1 && at[k + 1] < t) { k++; }
                float along = Math.clamp((t - at[k]) / Math.max(at[k + 1] - at[k], 1e-4f), 0, 1);
                float from = 1 - smooth(t / .3f), to = 1 - smooth((1 - t) / .3f);
                margin[j] = new Vector3(point).mulAdd(out, BoardRelief.lerp(reach[k], reach[k + 1], along) * (1 - from - to))
                      .mulAdd(opening, from).mulAdd(closing, to);
            }
            for (int i = 0; i < SHORE_SEGMENTS; i++) {
                Vector3 a = slab[(first + i) % n], b = slab[(first + i + 1) % n];
                iceFace(iceBroken, a, margin[2 * i], margin[2 * i + 1], Vector3.Z);
                iceFace(iceBroken, a, margin[2 * i + 1], b, Vector3.Z);
                iceFace(iceBroken, b, margin[2 * i + 1], margin[2 * i + 2], Vector3.Z);
            }
            for (int j = 0; j + 1 < margin.length; j++) { iceSkirt(iceBroken, margin[j], margin[j + 1], bottom, outward); }
            floes(margin, out, front);
        }
    }

    private static boolean turns(Vector3 a, Vector3 b, Vector3 c) {
        float turn = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        return Math.abs(turn) > .00001f * BoardGeometry.hexScale() * BoardGeometry.hexScale();
    }

    /**
     * How far the slab breaks off short of each drawn point on an ice-free shore: straight runs between irregular
     * breaks, easing onto the waterline over three points wherever the shore meets another kind of edge.
     */
    private float[] shoreBreak(List<Boolean> onShore, float scale) {
        int m = onShore.size();
        Random random = new Random(tile.coords().getX() * 0xC2B2AE3D27D4EB4FL ^ tile.coords().getY() * 0x165667B19E3779F9L);
        float[] knots = new float[m], recess = new float[m];
        for (int k = 0; k < m; k++) { knots[k] = ICE_LEAD * scale * (.1f + .9f * random.nextFloat()); }
        // Points along the ring to the nearest point off the shore, both ways round.
        int[] away = new int[m];
        Arrays.fill(away, m);
        for (int pass = 0; pass < 2; pass++) {
            for (int step = 0; step < 2 * m; step++) {
                int k = pass == 0 ? step % m : (2 * m - 1 - step) % m;
                int previous = pass == 0 ? (k + m - 1) % m : (k + 1) % m;
                away[k] = onShore.get(k) ? Math.min(away[k], away[previous] + 1) : 0;
            }
        }
        for (int k = 0; k < m; k++) {
            if (!onShore.get(k)) { continue; }
            int from = k - k % 3, span = Math.min(3, m - from);
            recess[k] = BoardRelief.lerp(knots[from], knots[(from + span) % m], (k - from) / (float) span)
                  * Math.min(1, away[k] / 3f);
        }
        return recess;
    }

    /**
     * A few broken floes drifting just off a ragged front, riding lower than the slab. They keep to the middle of the
     * edge, clear of the corners where the fronts of other slabs break out.
     */
    private void floes(Vector3[] margin, Vector3 out, Random random) {
        float scale = BoardGeometry.hexScale(), water = BoardGeometry.waterZ(tile);
        List<Vector3> centres = new ArrayList<>();
        List<Float> radii = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int f = 0; f < count; f++) {
            float along = (.2f + .6f * random.nextFloat()) * (margin.length - 1);
            float size = random.nextFloat();
            float radius = (1.2f + 4.5f * size * size) * scale;
            float gap = (1 + 7 * random.nextFloat()) * scale;
            int j = (int) along;
            Vector3 centre = new Vector3(margin[j]).lerp(margin[j + 1], along - j).mulAdd(out, gap + radius);
            boolean clear = true;
            for (int k = 0; clear && k < centres.size(); k++) {
                clear = Math.hypot(centres.get(k).x - centre.x, centres.get(k).y - centre.y) > radii.get(k) + radius + scale;
            }
            if (!clear) { continue; }
            centres.add(centre);
            radii.add(radius);
            piece(centre, radius, water + .5f * scale, water - scale, random);
        }
    }

    /** One broken piece of ice, an irregular polygon round centre with its own cut edge. */
    private void piece(Vector3 centre, float radius, float top, float bottom, Random random) {
        int sides = 5 + random.nextInt(3);
        double turn = random.nextDouble() * 2 * Math.PI;
        Vector3[] ring = new Vector3[sides];
        for (int s = 0; s < sides; s++) {
            double angle = turn + (s + (random.nextDouble() - .5) * .5) * 2 * Math.PI / sides;
            float r = radius * (.7f + .3f * random.nextFloat());
            ring[s] = new Vector3(centre.x + (float) Math.cos(angle) * r, centre.y + (float) Math.sin(angle) * r, top);
        }
        Vector3 middle = new Vector3(centre.x, centre.y, top);
        for (int s = 0; s < sides; s++) {
            iceFace(iceBroken, middle, ring[s], ring[(s + 1) % sides], Vector3.Z);
            // The ring runs counter-clockwise, so each side faces out to the right of its direction.
            iceSkirt(iceBroken, ring[s], ring[(s + 1) % sides], bottom, 1);
        }
    }

    /**
     * Where a ragged front reaches a corner, the break every slab at that corner shares: out round this hex's own
     * corner where the front turns it, or on into the open water where the front continues along a frozen neighbour.
     * A front that ends at the corner stays on it.
     */
    private Vector3 cornerBreak(BoardScene scene, int edge, int corner, boolean[] ragged, boolean[] continuous) {
        int beside = corner == edge ? (edge + 5) % 6 : (edge + 1) % 6;
        Vector3 point = corners[corner % 6];
        Vector3 direction;
        if (ragged[beside]) {
            direction = new Vector3(point).sub(center);
        } else if (continuous[beside]) {
            direction = BoardGeometry.center(neighbor(scene, edge).coords(), 0).sub(point);
        } else {
            return new Vector3();
        }
        direction.z = 0;
        // Corners come from the shared lattice, so both slabs derive the same break from identical coordinates.
        long bits = Float.floatToIntBits(point.x) * 0x9E3779B97F4A7C15L ^ Float.floatToIntBits(point.y) * 0xC2B2AE3D27D4EB4FL;
        float shared = ((bits ^ bits >>> 31) & 0xFFFFFF) / (float) 0x1000000;
        return direction.nor().scl(ICE_MARGIN * BoardGeometry.hexScale() * (.3f + .7f * shared));
    }

    private static float smooth(float x) {
        float t = Math.clamp(x, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** The ice's exposed edge below a to b, facing out of it. */
    private static void iceSkirt(List<Face> into, Vector3 a, Vector3 b, float bottom, float outward) {
        Vector3 facing = new Vector3(b.y - a.y, a.x - b.x, 0).scl(outward);
        Vector3 lowA = new Vector3(a.x, a.y, bottom), lowB = new Vector3(b.x, b.y, bottom);
        iceFace(into, a, b, lowB, facing);
        iceFace(into, a, lowB, lowA, facing);
    }

    private static void iceFace(List<Face> into, Vector3 a, Vector3 b, Vector3 c, Vector3 facing) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        if (normal.len2() <= 0.000001f) { return; }
        into.add(normal.dot(facing) >= 0 ? new Face(a, b, c, Finish.ICE) : new Face(a, c, b, Finish.ICE));
    }

    /** Connected liquid steps, including lava, use surface levels, never the depths of their beds. */
    static boolean waterSlope(BoardScene.Tile a, BoardScene.Tile b) {
        return a != null && b != null && !a.frozen() && !b.frozen()
              && a.liquid().present() && a.liquid().connects(b.liquid())
              && Math.abs(a.elevation() - b.elevation()) <= 2;
    }

    /** Both halves of an open mouth share its height and ease back to their own centre's water level. */
    private void gradeWaterline(BoardScene scene, float[] shore) {
        if (tile.frozen()) { return; }
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
                if (shore[e] == 0) {
                    point.z = mouthDepth(point, a, ends[e], b, ends[(e + 1) % 6], middle);
                } else {
                    // Keep the canonical mouth endpoints where neighbouring banks meet. Ease away from them over the
                    // same share of the bank as the surface eases inward from its rim, so the water lies about level
                    // across a descending channel; a mouth's third of an edge tilted it sideways by up to 60 degrees.
                    float t = i / (float) SHORE_SEGMENTS, reach = tuning().plateau();
                    point.z = waterGrade(point.x, point.y) + (ends[e] - waterGrade(a.x, a.y)) * ease(t / reach)
                          + (ends[(e + 1) % 6] - waterGrade(b.x, b.y)) * ease((1 - t) / reach);
                }
                gradedWater |= Math.abs(point.z - base) > .0001f;
            }
        }
    }

    /** Open water connected by gentle steps at this corner, in a canonical order for water heights. */
    private List<BoardScene.Tile> cornerWater(BoardScene scene, int index) {
        return corner(scene, index, BoardSurface::waterSlope);
    }

    /**
     * This hex and those round the corner where edge {@code index} starts that {@code joins} links to it, directly or
     * through each other, in the same order from every hex of the corner.
     */
    private List<BoardScene.Tile> corner(BoardScene scene, int index,
          BiPredicate<BoardScene.Tile, BoardScene.Tile> joins) {
        List<BoardScene.Tile> joined = new ArrayList<>(List.of(tile));
        for (int pass = 0; pass < 2; pass++) {
            for (int e : new int[] { (index + 5) % 6, index }) {
                BoardScene.Tile other = neighbor(scene, e);
                if (other == null || joined.contains(other)) { continue; }
                if (joined.stream().anyMatch(t -> joins.test(t, other))) { joined.add(other); }
            }
        }
        joined.sort(java.util.Comparator.comparingInt((BoardScene.Tile t) -> t.coords().getX())
              .thenComparingInt(t -> t.coords().getY()));
        return joined;
    }

    /**
     * A gentle correction toward connected water levels. Retain most of the radial profile's flowing bulge;
     * fully blending toward a cross-channel level makes the descent look like a flat ramp. Beds and water share it.
     */
    private float waterGrade(float x, float y) {
        float base = BoardGeometry.waterZ(tile);
        float radius2 = square((1 + tuning().plateau()) * .5f * BoardGeometry.height());
        float own = Math.max(0, 1 - (square(x - center.x) + square(y - center.y)) / radius2);
        float total = own * own, change = 0;
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile other = neighbor(scene, edge);
            if (!waterSlope(tile, other)) { continue; }
            float dx = x - BoardGeometry.centerX(other.coords()), dy = y - BoardGeometry.centerY(other.coords());
            float weight = Math.max(0, 1 - (dx * dx + dy * dy) / radius2);
            weight *= weight;
            total += weight;
            change += weight * (BoardGeometry.waterZ(other) - base);
        }
        return total > 0 ? base + .2f * change / total : base;
    }

    /** The shared mouth/crest profile is exact at the rim; its correction eases away inside the channel. */
    private float waterGrade(float x, float y, Vector3 rim, float inward) {
        return waterGrade(x, y) + (rim.z - waterGrade(rim.x, rim.y)) * ease(inward);
    }

    /** Rings between a descending transparent stream's rim and its level centre. */
    private static final int SLOPE_RINGS = 4;

    /** Sample a transparent stream's rounded descent, with a level centre. */
    private void slopingSurface(Vector3[] outer) {
        Vector3[] ring = outer;
        float base = BoardGeometry.waterZ(tile);
        for (int k = 1; k <= SLOPE_RINGS; k++) {
            float x = k / (float) SLOPE_RINGS, scale = 1 - tuning().plateau() * x;
            Vector3[] inner = new Vector3[outer.length];
            for (int i = 0; i < outer.length; i++) {
                float px = center.x + (outer[i].x - center.x) * scale;
                float py = center.y + (outer[i].y - center.y) * scale;
                inner[i] = new Vector3(px, py, waterGrade(px, py, outer[i], x));
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
        for (int edge = 0; edge < 6; edge++) {
            List<Vector3> contact = waterContacts.get(edge);
            int count = contact == null ? SHORE_SEGMENTS : contact.size() - 1;
            for (int i = 0; i < count; i++) {
                Vector3 a = contact == null ? waterline[edge * SHORE_SEGMENTS + i] : contact.get(i);
                Vector3 b = contact == null ? waterline[(edge * SHORE_SEGMENTS + i + 1) % waterline.length] : contact.get(i + 1);
                float dx = b.x - a.x, dy = b.y - a.y, length = dx * dx + dy * dy;
                float t = length == 0 ? 0 : Math.clamp(((x - a.x) * dx + (y - a.y) * dy) / length, 0, 1);
                float distance = square(x - a.x - t * dx) + square(y - a.y - t * dy);
                if (distance < nearest) {
                    nearest = distance;
                    height = BoardRelief.lerp(a.z, b.z, t);
                }
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
            float descended = (top - point.z) / (drop * BoardGeometry.level());
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
        float tolerance = .001f * BoardGeometry.hexScale();
        for (int i = 0; i < SHORE_SEGMENTS; i++) {
            int at = edge * SHORE_SEGMENTS + i, next = (at + 1) % bedOutline.length;
            Vector3 a = bedOutline[at], b = bedOutline[next];
            // A descending pool's border shares the drawn rim, including a neighbouring fall's pulled-back end.
            Vector3 topA = new Vector3(water.get(at)), topB = new Vector3(water.get(next));
            if (b.z < topB.z - tolerance) {
                cutFaces.add(new Face(new Vector3(a), new Vector3(b), topB, Finish.TOP, edge));
            }
            if (a.z < topA.z - tolerance) {
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
        float jut = joinA[edge] == null && joinB[edge] == null ? tuning().lipJut() * BoardGeometry.hexScale() : 0;
        return new Crest(a, b, startTangent, endTangent, new Vector3(corners[edge]), edgeOutward(edge), jut,
              BoardGeometry.waterZ(tile));
    }

    /**
     * Where a crest turns a corner into the next fall, given its normal there: at the corner itself on a prow and out
     * over the pool below in a valley, running square to the normal.
     */
    private static void turn(Vector3 point, Vector3 tangent, Vector3 corner, Vector3 normal, boolean prow) {
        point.set(corner).mulAdd(normal, prow ? 0 : tuning().valley() * BoardGeometry.hexScale());
        tangent.set(-normal.y, normal.x, 0);
    }

    /**
     * The bed as one sculpted basin. It descends from the waterline, or from an open mouth's profile, along rays toward
     * the hex centre, where a plateau keeps the unit anchor at the game depth. Mouth profiles and corners are pure
     * functions of the hexes sharing them, so neighbouring beds meet without a step. Under each bank the bed drops as
     * the land beside it and the water's depth suggest: steeply under higher ground and in deep water, gently off land
     * at the water's own level, where shallows reach out. Ordinary pools use one bank and a flat floor; shallow bars
     * and descending streams retain the rounded profile between rim and plateau.
     * {@code outer} is the waterline with each falling mouth moved out to its crest, which the ledge beneath the fall
     * reaches; rise, per edge, the levels the land beside a bank rises above this hex; plunge, the mouths a fall pours
     * in over, where the water scours a deeper pool.
     */
    private void basin(BoardScene scene, Vector3[] waterline, Vector3[] outer, Vector3[] liquid, float[] shore, float[] rise,
          boolean[] plunge) {
        int count = waterline.length;
        float surface = BoardGeometry.waterZ(tile), full = depth(tile), ledge = tuning().lipDepth() * BoardGeometry.hexScale();
        boolean[] spills = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            spills[edge] = crests[edge] != null;
        }
        // Depth at each edge's first point: none where a bank meets it, the ledge beside a fall, else the depth shared
        // by the corner's hexes.
        float[] ends = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6;
            ends[edge] = (shore[edge] > 0 || shore[previous] > 0) && !relief.wetCliffCorner(edge) ? 0
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
            } else if (shore[edge] == 0 || relief.wetCliff(edge)) {
                // Beds of one level meet at their mean depth; a fall pours over its ledge into the pool below, whose
                // bed keeps its own depth.
                BoardScene.Tile other = neighbor(scene, edge);
                float middle = spills[edge] ? ledge
                      : shore[edge] == 0 && other != null && (other.elevation() == tile.elevation() || waterSlope(tile, other))
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
            bank[i] = shore[edge] == 0 || relief.wetCliff(edge) ? 0 : Math.min(shore[(edge + 5) % 6] > 0 ? 1 : BoardRelief.smooth(2 * t),
                  shore[(edge + 1) % 6] > 0 ? 1 : BoardRelief.smooth(2 * (1 - t)));
            float bed = shallow ? shallowBed(rim[i], outer[i].x, outer[i].y, bank[i]) : rim[i];
            ring[i] = new Vector3(outer[i].x, outer[i].y, outer[i].z - bed);
        }
        bedOutline = ring;
        Vector3 anchor = new Vector3(center.x, center.y, surface - full);
        if (tile.liquid().molten() && !tile.frozen()) {
            lavaBasin(ring, liquid, full);
            return;
        }
        boolean levelBed = !shallow && !gradedWater;
        for (int i = 0; levelBed && i < count; i++) {
            levelBed = shore[i / SHORE_SEGMENTS] == 0 && Math.abs(ring[i].z - anchor.z) < .0001f;
        }
        // Ordinary pools need a level floor and a bank joining it to the shared contour. Keep detail in that
        // contour, rather than repeating all its samples in concentric rings across an almost flat seabed.
        if (!shallow && !gradedWater && !tile.liquid().molten() && sparseBed(ring, anchor, levelBed)) { return; }
        if (levelBed) {
            for (int i = 0; i < count; i++) {
                triangle(anchor, ring[i], ring[(i + 1) % count], Finish.BED, i / SHORE_SEGMENTS);
            }
            return;
        }
        // The optical-depth field samples this bed across chunk boundaries, independently of their render LoDs.
        int rings = 2;
        // Preserve exposed bars and the varying water-height attribute beneath descending streams.
        boolean simplify = !shallow && !gradedWater && !tile.frozen() && !tile.liquid().molten() && lod != TerrainLod.FULL;
        Vector3[][] bedRings = new Vector3[rings + 1][];
        int[] bandEnds = new int[rings + 1];
        bedRings[0] = ring;
        bandEnds[0] = faces.size();
        for (int k = 1; k <= rings; k++) {
            float x = k / (float) rings, scale = 1 - tuning().plateau() * x;
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
                float level = gradedWater ? waterGrade(px, py, outer[i], x) : surface;
                inner[i] = new Vector3(px, py, level - bed);
            }
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                quad(ring[i], ring[j], inner[j], inner[i], Finish.BED, i / SHORE_SEGMENTS);
            }
            ring = inner;
            bedRings[k] = ring;
            bandEnds[k] = faces.size();
            if (k == 1) { interiorFrom = faces.size(); }
        }
        for (int i = 0; i < count; i++) {
            triangle(anchor, ring[i], ring[(i + 1) % count], Finish.BED, i / SHORE_SEGMENTS);
        }
        interiorTo = faces.size();
        if (simplify) { simplifyBed(bedRings, bandEnds, anchor); }
    }

    /** A flat floor and, where needed, one sloping bank; curved shore and depth contacts remain exact. */
    private boolean sparseBed(Vector3[] boundary, Vector3 anchor, boolean level) {
        List<Face> sparse = basinFaces(boundary, anchor, level, boundarySamples(boundary, boundary), 6, 1);
        if (sparse == null) { return false; }
        if (!level) { interiorFrom = faces.size() + sparse.size() - 6; }
        faces.addAll(sparse);
        if (!level) { interiorTo = faces.size(); }
        return true;
    }

    /** Opaque lava and its shallow bed share every interior vertex and diagonal, so the bed cannot pierce the melt. */
    private void lavaBasin(Vector3[] boundary, Vector3[] liquid, float depth) {
        List<Integer> perimeter = boundarySamples(boundary, liquid);
        Vector3 anchor = new Vector3(center.x, center.y, BoardGeometry.waterZ(tile));
        int innerSamples = 12;
        float inward = 2 / 3f;
        List<Face> surface = basinFaces(liquid, anchor, !gradedWater, perimeter, innerSamples, inward);
        boolean plateau = gradedWater && surface != null;
        if (plateau) {
            // One shoulder carries the grade into the centre, instead of long fans ending at a flat plateau.
            for (int i = 0; i < innerSamples; i++) {
                Vector3 point = surface.get(surface.size() - innerSamples + i).b();
                point.z = waterGrade(point.x, point.y, liquid[i * liquid.length / innerSamples], inward);
            }
        }
        if (surface == null) {
            // A narrow concave mouth may reach inside the plateau. Its centre fan needs no inner ring.
            surface = new ArrayList<>();
            for (int i = 0; i < perimeter.size(); i++) {
                surface.add(new Face(anchor, liquid[perimeter.get(i)],
                      liquid[perimeter.get((i + 1) % perimeter.size())], Finish.TOP));
            }
        }
        Map<Vector3, Vector3> bed = new HashMap<>();
        for (int index : perimeter) {
            Vector3 p = liquid[index];
            bed.put(p, new Vector3(p.x, p.y, boundary[index].z));
        }
        // Only a falling lip pulls the drawn surface inward. Its exposed collar keeps the original crest contact.
        for (int i = 0; i < perimeter.size(); i++) {
            int at = perimeter.get(i), next = perimeter.get((i + 1) % perimeter.size());
            // At a lip's end the two contours meet along the same line; those collapsed pieces have no bed area.
            for (Face collar : List.of(
                  new Face(boundary[at], boundary[next], bed.get(liquid[next]), Finish.BED, at / SHORE_SEGMENTS),
                  new Face(boundary[at], bed.get(liquid[next]), bed.get(liquid[at]), Finish.BED, at / SHORE_SEGMENTS))) {
                if (upward(collar) > 0) { faces.add(collar); }
            }
        }
        if (plateau) { interiorFrom = faces.size() + surface.size() - innerSamples; }
        for (Face face : surface) {
            waterFaces.add(new Face(face.a(), face.b(), face.c(), Finish.TOP));
            triangle(bed.computeIfAbsent(face.a(), p -> new Vector3(p.x, p.y, p.z - depth)),
                  bed.computeIfAbsent(face.b(), p -> new Vector3(p.x, p.y, p.z - depth)),
                  bed.computeIfAbsent(face.c(), p -> new Vector3(p.x, p.y, p.z - depth)), Finish.BED, face.landEdge());
        }
        if (plateau) { interiorTo = faces.size(); }
    }

    /** Retain bends in either shared contour; omit only samples lying on the same straight 3D segment. */
    private List<Integer> boundarySamples(Vector3[] boundary, Vector3[] paired) {
        List<Integer> perimeter = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            int first = edge * SHORE_SEGMENTS;
            // The liquid rim is refined against the final cliff later; that pass needs its individual segments.
            boolean straight = (paired == boundary || !relief.wetCliff(edge)) && straightEdge(boundary, first)
                  && (paired == boundary || straightEdge(paired, first));
            for (int i = 0; i < SHORE_SEGMENTS; i += straight ? SHORE_SEGMENTS : 1) { perimeter.add(first + i); }
        }
        return perimeter;
    }

    private static boolean straightEdge(Vector3[] boundary, int first) {
        Vector3 a = boundary[first], b = boundary[(first + SHORE_SEGMENTS) % boundary.length];
        Vector3 direction = new Vector3(b).sub(a);
        float length2 = direction.len2();
        if (length2 == 0) { return false; }
        for (int i = 1; i < SHORE_SEGMENTS; i++) {
            Vector3 p = boundary[first + i];
            float t = new Vector3(p).sub(a).dot(direction) / length2;
            float tolerance = Math.max(.0001f * BoardGeometry.hexScale(),
                  2 * Math.max(Math.ulp(p.x), Math.max(Math.ulp(p.y), Math.ulp(p.z))));
            if (t < 0 || t > 1 || p.dst2(new Vector3(a).mulAdd(direction, t)) > tolerance * tolerance) { return false; }
        }
        return true;
    }

    /** A perimeter joined to one inner contour, or just a centre fan when the surface is already level. */
    private static List<Face> basinFaces(Vector3[] boundary, Vector3 anchor, boolean level, List<Integer> perimeter,
          int innerSamples, float inward) {
        List<Face> sparse = new ArrayList<>();
        if (level) {
            for (int i = 0; i < perimeter.size(); i++) {
                int at = perimeter.get(i), next = perimeter.get((i + 1) % perimeter.size());
                sparse.add(new Face(anchor, boundary[at], boundary[next], Finish.BED, at / SHORE_SEGMENTS));
            }
        } else {
            Vector3[] floor = new Vector3[innerSamples];
            float scale = 1 - tuning().plateau() * inward;
            int stride = boundary.length / innerSamples;
            for (int i = 0; i < innerSamples; i++) {
                Vector3 rim = boundary[i * stride];
                floor[i] = new Vector3(anchor.x + (rim.x - anchor.x) * scale,
                      anchor.y + (rim.y - anchor.y) * scale, anchor.z);
            }
            int outer = 0, inner = 0;
            while (outer < perimeter.size() || inner < floor.length) {
                int at = perimeter.get(outer % perimeter.size());
                int next = outer + 1 < perimeter.size() ? perimeter.get(outer + 1) : boundary.length;
                // A flowing shoulder joins the nearer inner point; a one-sided fan can form a steep angular lip.
                float through = (inner + (inward < 1 ? .5f : 1)) * stride;
                if (outer < perimeter.size() && (inner == floor.length || next <= through)) {
                    sparse.add(new Face(boundary[at], boundary[next % boundary.length], floor[inner % innerSamples],
                          Finish.BED, at / SHORE_SEGMENTS));
                    outer++;
                } else {
                    sparse.add(new Face(boundary[at], floor[(inner + 1) % innerSamples], floor[inner % innerSamples],
                          Finish.BED, inner * 6 / innerSamples));
                    inner++;
                }
            }
            for (int i = 0; i < innerSamples; i++) {
                sparse.add(new Face(anchor, floor[i], floor[(i + 1) % innerSamples], Finish.BED,
                      i * 6 / innerSamples));
            }
        }
        // A deeply concave mouth can reach inside the proposed floor. Keep its radial construction in that case.
        return sparse.stream().anyMatch(face -> upward(face) <= 0) ? null : sparse;
    }

    /** Keep only rings needed to describe the bed's profile, with every boundary sample and radial seam intact. */
    private void simplifyBed(Vector3[][] rings, int[] bandEnds, Vector3 anchor) {
        // Less than a fifth of a pixel at the default refinement thresholds; independent of board dimensions.
        float tolerance = BoardGeometry.width() / switch (lod) {
            case MEDIUM -> 512;
            case COARSE -> 128;
            default -> 32;
        };
        List<Face> simplified = new ArrayList<>();
        for (int outer = 0; outer < rings.length;) {
            int inner = rings.length;
            while (inner > outer + 1 && !bedBandFits(rings, outer, inner, anchor, tolerance)) { inner--; }
            if (inner == outer + 1 && inner < rings.length) {
                simplified.addAll(faces.subList(bandEnds[outer], bandEnds[inner]));
            } else {
                for (int i = 0; i < rings[outer].length; i++) {
                    int j = (i + 1) % rings[outer].length;
                    Vector3 a = rings[outer][i], b = rings[outer][j];
                    Vector3 c = inner == rings.length ? anchor : rings[inner][j];
                    simplified.add(new Face(a, b, c, Finish.BED, i / SHORE_SEGMENTS));
                    if (inner < rings.length) {
                        simplified.add(new Face(a, c, rings[inner][i], Finish.BED, i / SHORE_SEGMENTS));
                    }
                }
            }
            outer = inner;
        }
        if (simplified.size() < interiorTo - bandEnds[0]) { renderBed = simplified; }
    }

    /**
     * Each old radial strip lies inside the proposed quad (or centre fan). Bound both proposed triangle planes
     * over every old vertex, even outside the triangle: linear interpolation then also bounds the error where
     * their diagonals cross. This conservative check avoids accepting a saddle just because its corners match.
     */
    private static boolean bedBandFits(Vector3[][] rings, int outer, int inner, Vector3 anchor, float tolerance) {
        Vector3 first = new Vector3(), second = new Vector3();
        for (int i = 0; i < rings[outer].length; i++) {
            int j = (i + 1) % rings[outer].length;
            Vector3 a = rings[outer][i], b = rings[outer][j];
            Vector3 c = inner == rings.length ? anchor : rings[inner][j];
            first.set(b).sub(a).crs(second.set(c).sub(a));
            if (first.z <= 0) { return false; }
            first.scl(1 / first.z);
            if (inner < rings.length) {
                Vector3 d = rings[inner][i];
                second.set(c).sub(a).crs(d.x - a.x, d.y - a.y, d.z - a.z);
                if (second.z <= 0) { return false; }
                second.scl(1 / second.z);
            } else { second.set(first); }
            for (int k = outer; k <= Math.min(inner, rings.length - 1); k++) {
                for (int side = 0; side < 2; side++) {
                    Vector3 p = rings[k][side == 0 ? i : j];
                    float dx = p.x - a.x, dy = p.y - a.y, dz = p.z - a.z;
                    if (Math.abs(first.x * dx + first.y * dy + dz) > tolerance
                          || Math.abs(second.x * dx + second.y * dy + dz) > tolerance) { return false; }
                }
            }
        }
        return true;
    }

    /** The caller owns the supplied bed list; canonical faces remain available for normal and depth sampling. */
    List<Face> renderBed(List<Face> canonical) {
        if (renderBed != null) { return new ArrayList<>(renderBed); }
        if (tile.liquid().molten() && !tile.frozen()) {
            // Lava is opaque. Remove only triangles wholly covered by one drawn lava triangle, including shared
            // shore vertices at equal height; keep crest geometry outside it. Picking retains the sparse bed.
            List<Face> visible = new ArrayList<>(canonical);
            visible.removeIf(face -> {
                for (Face top : waterFaces) {
                    if (top.height(face.a().x, face.a().y, 0) >= face.a().z
                          && top.height(face.b().x, face.b().y, 0) >= face.b().z
                          && top.height(face.c().x, face.c().y, 0) >= face.c().z) { return true; }
                }
                return false;
            });
            return visible;
        }
        return canonical;
    }

    private static float shallowBed(float bed, float x, float y, float weight) {
        float bar = BoardRelief.smooth((meander(x, y, 9.7f) + .15f) / .5f);
        return Math.max(bed - 2.5f * BoardGeometry.hexScale() * bar * weight, -.6f * BoardGeometry.hexScale());
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

    /**
     * Mean depth of the connected water around the corner where edge {@code index} starts. Ice does not change a bed:
     * frozen and open water at one level join alike, so every bed round the corner meets at the same depth. Molten
     * beds join only at their own level.
     */
    private float cornerDepth(BoardScene scene, int index) {
        boolean molten = tile.liquid().molten();
        List<BoardScene.Tile> joined = corner(scene, index, (a, b) -> a.liquid().connects(b.liquid())
              && (a.elevation() == b.elevation() || !molten && waterSlope(a, b)));
        float sum = 0;
        int low = tile.elevation(), high = low;
        for (BoardScene.Tile other : joined) {
            sum += depth(other);
            low = Math.min(low, other.elevation());
            high = Math.max(high, other.elevation());
        }
        if (high - low >= 3) { return Math.min(sum / joined.size(), tuning().lipDepth() * BoardGeometry.hexScale()); }
        return sum / joined.size();
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
            receivingWater[fall.edge()] = new BoardSurface(scene, other, false, lod);
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
        return Math.min(Math.max(0, surface - bottom) * tuning().fallLipDrop(), tuning().fallLipWidth() * BoardGeometry.width());
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
              Math.min(1, BoardRelief.tuning().shoreLip() * BoardGeometry.hexScale() / Math.max(distance, .0001f)));
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
        float scale = BoardGeometry.hexScale();
        shoreCorners = new Vector3[6];
        for (int k = 0; k < 6; k++) { shoreCorners[k] = moved(k); }
        // Keep a bank beside land at the water's level, a beach beside walls and lower land, and the slope's reach
        // plus a wet margin beside a slope up from the water, which runs on under it to the bed. Keep clear of the
        // corners the land rounds into the hex too.
        banks = new ArrayList<>(12);
        for (int k = 0; k < 6; k++) {
            Vector3 a = shoreCorners[k], b = shoreCorners[(k + 1) % 6];
            if (inset[k] > 0) {
                float width = relief.slope(k) || rise[k] != 0 ? inset[k] : tuning().shoreBank() * scale;
                Vector3 inward = new Vector3(b).sub(a).crs(Vector3.Z).nor().scl(-1);
                banks.add(new Bank(a.x, a.y, inward.x, inward.y, width));
            }
            float in = relief.cornerInset(k);
            if (in > 0) {
                Vector3 corner = shoreCorners[k];
                Vector3 inward = new Vector3(center.x - corner.x, center.y - corner.y, 0).nor();
                banks.add(new Bank(corner.x, corner.y, inward.x, inward.y, in + tuning().hug() * scale));
            }
        }
        Vector3[] anchors = new Vector3[6];
        for (int k = 0; k < 6; k++) {
            int before = (k + 5) % 6, next = (k + 1) % 6;
            boolean in = inset[before] == 0, out = inset[k] == 0;
            if (relief.wetCliffCorner(k) || !in && !out && (relief.wetCliff(before) || relief.wetCliff(k))) {
                // A beach ending against a cliff tapers to the shared rock seam, not to a freestanding sand shelf.
                anchors[k] = relief.seam(k, k, 0);
            } else if (BoardConcrete.concreteBank(scene, tile, before) || BoardConcrete.concreteBank(scene, tile, k)) {
                // A poured edge meets the water directly. No beach setback, rounded corner or natural shore field.
                anchors[k] = new Vector3(shoreCorners[k]);
            } else if (in && out) {
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
            Vector3[] bank = inset[edge] == 0 || relief.wetCliff(edge) || BoardConcrete.concreteBank(scene, tile, edge)
                  ? null : bank(anchors[edge], anchors[next], plunge, edge);
            Vector3 cliffA = relief.wetCliff(edge) ? relief.seam(edge, edge, 0) : null;
            Vector3 cliffB = cliffA == null ? null : relief.seam(edge, next, 0);
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                Vector3 point = bank == null ? mouthPoint(anchors[edge], anchors[next], segment) : bank[segment];
                if (cliffA != null) {
                    // Follow the rock's curve, retaining the common mouth ends where a cliff changes into a bank.
                    point.add(relief.seam(edge, edge, segment / (float) SHORE_SEGMENTS)
                          .sub(mouthPoint(cliffA, cliffB, segment)));
                }
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        BoardConcrete.straighten(scene, tile, result);
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
        // Fixed inlets and cliff joins must stay continuous even when the stream is narrow.
        float fromBlend = plunge[(edge + 5) % 6] || relief.wetCliff(edge + 5) ? 1 : blend;
        float toBlend = plunge[(edge + 1) % 6] || relief.wetCliff(edge + 1) ? 1 : blend;
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
                float core = Math.min(rawRadius, (tile.waterDepth() > 0 ? 12 : 4) * BoardGeometry.hexScale());
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
        if (!mouth(edge) || shore(point.x, point.y) <= .25f * BoardGeometry.hexScale()) { return null; }
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
        float scale = BoardGeometry.hexScale(), reach = relief.cornerReach(k);
        if (shoreCorners[k].equals(corners[k])) {
            return Math.max((tuning().beach() - (plunge ? tuning().plungeOpening() : tuning().mouthOpening())) * scale * 1.1547005f,
                  reach + tuning().hug() * scale);
        }
        return reach > 0 ? reach + tuning().hug() * scale : (tuning().shoreBank() + 1) * scale;
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
        float scale = BoardGeometry.hexScale(), value = shore(x, y);
        for (int edge = 0; edge < 6; edge++) {
            if (!plunge[edge]) { continue; }
            Vector3 middle = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], .5f);
            Vector3 inward = edgeInward(edge);
            float into = (x - middle.x) * inward.x + (y - middle.y) * inward.y;
            // Scour opens a rounded pool downstream of the inlet. At the shared edge it vanishes smoothly,
            // leaving the canonical mouth intact instead of abruptly narrowing the swollen bank there.
            middle.mulAdd(inward, .15f * BoardGeometry.width());
            float d = (float) Math.hypot(x - middle.x, y - middle.y) / (.4f * BoardGeometry.width());
            value += tuning().plungePool() * scale * (1 - BoardRelief.smooth(d))
                  * BoardRelief.smooth(into / (.12f * BoardGeometry.width()));
        }
        for (Bank bank : banks) {
            value = BoardRelief.smoothMin(value, bank.clearance(x, y), tuning().shoreRound() * scale);
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
        float scale = BoardGeometry.hexScale();
        return relief.slope(edge) ? relief.reach(edge) + tuning().hug() * scale : tuning().beach() * scale;
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

    /** A level road with level, dry neighbours needs only the six original terrain faces. */
    static boolean flatRoadTop(BoardScene scene, BoardScene.Tile tile, int ramps) {
        if (ramps != 0) { return false; }
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            if (neighbor != null && (neighbor.elevation() != tile.elevation() || neighbor.liquid().present())) { return false; }
        }
        return true;
    }

    private void road(BoardScene scene) {
        if (flatRoadTop(scene, tile, ramps)) {
            Vector3[] outline = new Vector3[6];
            for (int i = 0; i < 6; i++) { outline[i] = moved(i); }
            fan(outline, center.z, Finish.TOP);
            return;
        }
        float largestRise = 0;
        boolean bridge = false;
        for (int direction = 0; direction < 6; direction++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            bridge |= (ramps & 1 << direction) != 0 && connectingBridge(tile, neighbor, direction) != null;
            largestRise = Math.max(largestRise, Math.abs(roadEdgeElevation(tile,
                  neighbor, direction) * BoardGeometry.level() - center.z));
        }
        // The parabolic grade's chord error is rise / (3 * sections^2). Derive resolution from that error,
        // not a fixed dense grid. Shared counts keep the banks and hub joined, including six-way crossings.
        int sections = largestRise == 0 ? 1 : 2 * (int) Math.ceil(Math.sqrt(largestRise / (3 * .06f * BoardGeometry.hexScale())) / 2);
        var stations = new TreeSet<Float>();
        for (int i = 0; i <= sections; i++) { stations.add(i / (float) sections); }
        // Bridge approaches fit both vertical curves into one half-hex. Detail only their two flat joins.
        if (bridge) { stations.add(.04f); stations.add(.96f); }
        List<Float> runs = new ArrayList<>(stations);
        Vector3[] hub = new Vector3[6];
        for (int i = 0; i < 6; i++) {
            // Both sides of a gate meet at one height along the whole mouth (see BoardRelief#gateCorner).
            float gate = relief.gateCorner(i);
            if (!Float.isNaN(gate)) { corners[i].z = gate; }
            hub[i] = new Vector3(corners[i]).lerp(center, 0.5f);
            hub[i].z = center.z;
        }
        List<Vector3> hubRim = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6;
            int direction = BoardGeometry.edgeDirection(edge);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            if ((ramps & (1 << direction)) == 0) {
                int count = relief.roadSamples(edge);
                for (int i = 0; i < count; i++) {
                    float a = i / (float) count, b = (i + 1) / (float) count;
                    Vector3 innerA = new Vector3(hub[edge]).lerp(hub[next], a);
                    Vector3 innerB = new Vector3(hub[edge]).lerp(hub[next], b);
                    Vector3 outerA = new Vector3(corners[edge]).lerp(corners[next], a);
                    Vector3 outerB = new Vector3(corners[edge]).lerp(corners[next], b);
                    hubRim.add(innerA);
                    for (int section = 1; section < runs.size(); section++) {
                        quad(new Vector3(innerA).lerp(outerA, runs.get(section - 1)),
                              new Vector3(innerA).lerp(outerA, runs.get(section)),
                              new Vector3(innerB).lerp(outerB, runs.get(section)),
                              new Vector3(innerB).lerp(outerB, runs.get(section - 1)), Finish.TOP);
                    }
                }
                continue;
            }
            float half = ROAD_MOUTH * BoardGeometry.hexScale()
                  / (float) Math.hypot(corners[edge].x - corners[next].x, corners[edge].y - corners[next].y);
            float rise = roadEdgeElevation(tile, neighbor, direction) * BoardGeometry.level() - center.z;
            // Both sides derive the bank samples from this approach, independent of any other exits in their hex.
            int bankSections = Math.clamp((int) Math.ceil(Math.sqrt(.75f * Math.abs(rise)
                  / (.2f * BoardGeometry.hexScale()))), 3, 8);
            Vector3 left = new Vector3(corners[edge]).lerp(corners[next], 0.5f - half);
            Vector3 right = new Vector3(corners[edge]).lerp(corners[next], 0.5f + half);
            Vector3 innerLeft = new Vector3(hub[edge]).lerp(hub[next], 0.5f - half * 2);
            Vector3 innerRight = new Vector3(hub[edge]).lerp(hub[next], 0.5f + half * 2);
            for (int i = 0; i < bankSections; i++) { hubRim.add(new Vector3(hub[edge]).lerp(innerLeft, i / (float) bankSections)); }
            hubRim.add(innerLeft);
            for (int i = 0; i < bankSections; i++) { hubRim.add(new Vector3(innerRight).lerp(hub[next], i / (float) bankSections)); }
            RoadRamp ramp = new RoadRamp(innerLeft, new Vector3(left).sub(innerLeft),
                  new Vector3(innerRight).sub(innerLeft), rise, connectingBridge(tile, neighbor, direction) != null);
            roadRamps.add(ramp);
            Vector3 a = innerLeft, b = innerRight;
            // Enough sections to round the actual silhouette, including when normal maps are disabled.
            for (int section = 1; section < runs.size(); section++) {
                float t = runs.get(section);
                Vector3 c = new Vector3(innerLeft).lerp(left, t);
                Vector3 d = new Vector3(innerRight).lerp(right, t);
                c.z = d.z = center.z + rise * ramp.progress(t);
                quad(a, c, d, b, Finish.TOP);
                a = c;
                b = d;
            }
            roadBank(ramp, innerLeft, left, hub[edge], corners[edge], true, runs, bankSections);
            roadBank(ramp, innerRight, right, hub[next], corners[next], false, runs, bankSections);
        }
        // Match the bank vertices at the hub, including six-way junctions. Each point has only one height.
        int rings = sections;
        for (int ring = 0; ring < rings; ring++) {
            for (int i = 0; i < hubRim.size(); i++) {
                Vector3 a = hubRim.get(i), b = hubRim.get((i + 1) % hubRim.size());
                quad(new Vector3(center).lerp(a, ring / (float) rings),
                      new Vector3(center).lerp(a, (ring + 1f) / rings),
                      new Vector3(center).lerp(b, (ring + 1f) / rings),
                      new Vector3(center).lerp(b, ring / (float) rings), Finish.TOP);
            }
        }
        Map<Vector3, Vector3> graded = new HashMap<>();
        for (int i = 0; i < faces.size(); i++) {
            Face face = faces.get(i);
            faces.set(i, new Face(graded.computeIfAbsent(face.a(), this::roadGrade),
                  graded.computeIfAbsent(face.b(), this::roadGrade), graded.computeIfAbsent(face.c(), this::roadGrade),
                  face.finish(), face.landEdge()));
        }
        if (tile.surface() == BoardScene.Surface.CONCRETE) {
            simplifyRoad();
            if (ramps != 0) { concreteRoad(); }
        }
    }

    private void simplifyRoad() {
        BoardRoad road = BoardRoad.clearance(tile.coords(), tile.roadExits() | ramps);
        float scale = BoardGeometry.hexScale();
        BoardRampMesh.simplify(faces, center, p -> road.distance((p.x - center.x) / scale, (p.y - center.y) / scale)
              <= BoardRoad.SHOULDER, p -> roadRamps.stream().anyMatch(ramp -> ramp.curved(p)));
    }

    /** Concrete stays a flat slab outside the road corridor; retaining walls carry the cut/fill inside it. */
    private void concreteRoad() {
        List<Face> graded = List.copyOf(faces);
        BoardRoad road = BoardRoad.clearance(tile.coords(), tile.roadExits() | ramps);
        Area corridor = road.footprint(BoardRoad.SHOULDER);
        float scale = BoardGeometry.hexScale();
        corridor.transform(new AffineTransform(scale, 0, 0, scale, center.x, center.y));
        // Flatten once, before subtraction: two independently flattened curves leave slivers between slab and cut.
        Path2D.Float contour = new Path2D.Float();
        contour.append(corridor.getPathIterator(null, .02f * scale), false);
        corridor = new Area(contour);
        Path2D.Float boundary = new Path2D.Float();
        for (int i = 0; i < 6; i++) {
            Vector3 p = moved(i);
            if (i == 0) { boundary.moveTo(p.x, p.y); } else { boundary.lineTo(p.x, p.y); }
        }
        boundary.closePath();
        Area slab = new Area(boundary);
        slab.subtract(corridor);
        faces.clear();
        for (var t : BoardTacticalGeometry.flat(slab, -1)) {
            t.a().z = t.b().z = t.c().z = center.z;
            triangle(t.a(), t.b(), t.c(), Finish.TOP);
        }
        // Clip each graded face once against the corridor; its pieces lie in the face's own plane. Clipping every
        // corridor triangle against every face multiplied the two tessellations into thousands of slivers per slab.
        Rectangle2D bounds = new Rectangle2D.Float();
        for (var face : graded) {
            float nx = (face.b().y - face.a().y) * (face.c().z - face.a().z) - (face.b().z - face.a().z) * (face.c().y - face.a().y);
            float ny = (face.b().z - face.a().z) * (face.c().x - face.a().x) - (face.b().x - face.a().x) * (face.c().z - face.a().z);
            float nz = (face.b().x - face.a().x) * (face.c().y - face.a().y) - (face.b().y - face.a().y) * (face.c().x - face.a().x);
            if (Math.abs(nz) < 1e-6f) { continue; }
            float minX = Math.min(face.a().x, Math.min(face.b().x, face.c().x)), maxX = Math.max(face.a().x, Math.max(face.b().x, face.c().x));
            float minY = Math.min(face.a().y, Math.min(face.b().y, face.c().y)), maxY = Math.max(face.a().y, Math.max(face.b().y, face.c().y));
            bounds.setRect(minX, minY, maxX - minX, maxY - minY);
            if (!corridor.intersects(bounds)) { continue; }
            if (corridor.contains(bounds)) {
                faces.add(nz > 0 ? new Face(face.a(), face.b(), face.c(), Finish.TOP)
                      : new Face(face.a(), face.c(), face.b(), Finish.TOP));
                continue;
            }
            Path2D.Float outline = new Path2D.Float();
            outline.moveTo(face.a().x, face.a().y);
            outline.lineTo(face.b().x, face.b().y);
            outline.lineTo(face.c().x, face.c().y);
            outline.closePath();
            Area piece = new Area(outline);
            piece.intersect(corridor);
            if (piece.isEmpty()) { continue; }
            for (var t : BoardTacticalGeometry.flat(piece, -1)) {
                for (Vector3 p : new Vector3[] { t.a(), t.b(), t.c() }) {
                    p.z = face.a().z - (nx * (p.x - face.a().x) + ny * (p.y - face.a().y)) / nz;
                }
                Face cut = new Face(t.a(), t.b(), t.c(), Finish.TOP);
                if (upward(cut) > 0) { faces.add(cut); }
                else if (upward(cut) < 0) { faces.add(new Face(cut.a(), cut.c(), cut.b(), Finish.TOP)); }
            }
        }
        // Split each retaining edge at the supporting mesh's breaks, so the wall meets the actual incline exactly.
        PathIterator path = corridor.getPathIterator(null, .25);
        float[] xy = new float[6];
        Vector3 start = null, previous = null;
        while (!path.isDone()) {
            int kind = path.currentSegment(xy);
            if (kind == PathIterator.SEG_MOVETO) {
                start = previous = new Vector3(xy[0], xy[1], 0);
            } else {
                Vector3 next = kind == PathIterator.SEG_CLOSE ? start : new Vector3(xy[0], xy[1], 0);
                retainingEdge(previous, next, corridor, graded);
                previous = next;
            }
            path.next();
        }
    }

    private void retainingEdge(Vector3 a, Vector3 b, Area corridor, List<Face> graded) {
        float dx = b.x - a.x, dy = b.y - a.y, length = (float) Math.hypot(dx, dy);
        if (length < .0001f) { return; }
        TreeSet<Float> cuts = new TreeSet<>(List.of(0f, 1f));
        for (var face : graded) {
            Vector3[] vertices = { face.a(), face.b(), face.c() };
            for (int i = 0; i < 3; i++) {
                Vector3 c = vertices[i], d = vertices[(i + 1) % 3];
                float ex = d.x - c.x, ey = d.y - c.y, denominator = dx * ey - dy * ex;
                if (Math.abs(denominator) < 1e-8f) { continue; }
                float t = ((c.x - a.x) * ey - (c.y - a.y) * ex) / denominator;
                float u = ((c.x - a.x) * dy - (c.y - a.y) * dx) / denominator;
                if (t > 0 && t < 1 && u >= -.00001f && u <= 1.00001f) { cuts.add(Math.round(t * 1000000) / 1000000f); }
            }
        }
        List<Float> points = new ArrayList<>(cuts);
        float epsilon = .01f * BoardGeometry.hexScale();
        boolean right = corridor.contains((a.x + b.x) / 2 - dy / length * epsilon,
              (a.y + b.y) / 2 + dx / length * epsilon);
        for (int i = 1; i < points.size(); i++) {
            float from = points.get(i - 1), to = points.get(i), middle = (from + to) / 2;
            float z = sampleHeight(graded, a.x + dx * middle, a.y + dy * middle, Float.NaN);
            if (!Float.isFinite(z) || Math.abs(z - center.z) < .0001f * BoardGeometry.hexScale()) { continue; }
            Vector3 p = new Vector3(a).lerp(b, from), q = new Vector3(a).lerp(b, to);
            p.z = sampleHeight(graded, p.x, p.y, z);
            q.z = sampleHeight(graded, q.x, q.y, z);
            if (right != (z > center.z)) { Vector3 swap = p; p = q; q = swap; }
            relief.retainingWall(p, q, center.z, retainingWalls);
        }
    }

    /** Extend a two-level climb towards the centre, blending its cut/fill with the other arms of a junction. */
    private Vector3 roadGrade(Vector3 p) {
        float change = 0, weights = 0;
        for (RoadRamp ramp : roadRamps) {
            float dx = p.x - ramp.origin().x, dy = p.y - ramp.origin().y;
            float area = ramp.along().x * ramp.across().y - ramp.along().y * ramp.across().x;
            float t = (dx * ramp.across().y - dy * ramp.across().x) / area;
            if (t < -ramp.extension() || t > 1.0001f) { continue; }
            float side = (ramp.along().x * dy - ramp.along().y * dx) / area;
            float lateral = Math.abs(side - .5f) * ramp.across().len();
            float weight = 1 - BoardRelief.smooth((lateral / BoardGeometry.hexScale() - 9) / 12);
            change += weight * ramp.rise() * (ramp.extended(t) - ramp.progress(Math.clamp(t, 0, 1)));
            weights += weight;
        }
        return new Vector3(p).add(0, 0, change / Math.max(1, weights));
    }

    /** Keep the carriageway, but give its surrounding ground the same rim as neighbouring natural terrain. */
    private void roadRelief() {
        // This pass samples only the six fixed edge lines. Keep the original triangle order and sampler,
        // but avoid scanning the whole graded interior for every projected road vertex.
        List<List<Face>> edgeFaces = new ArrayList<>(6);
        for (int edge = 0; edge < 6; edge++) {
            edgeFaces.add(edgeCandidates(edgeTopography, corners[edge], corners[(edge + 1) % 6]));
        }
        Map<Vector3, Vector3> moved = new HashMap<>();
        for (Face face : faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                moved.computeIfAbsent(p, point -> roadPoint(point, edgeFaces));
            }
        }
        untangle(moved);
        // Walls can add columns from the neighbouring top. Share this road's actual piecewise-linear outline
        // so those columns interpolate the emitted top rather than sample a different curve between its vertices.
        for (int edge = 0; edge < 6; edge++) {
            var rim = new java.util.TreeMap<Float, Vector3>();
            Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
            for (var entry : moved.entrySet()) {
                Vector3 source = entry.getKey();
                if (edgeDistance(source, edge) < outlineTolerance(source)) {
                    rim.put(along(a, b, source), entry.getValue());
                }
            }
            relief.roadRim(edge, rim.entrySet().stream()
                  .map(entry -> new BoardRelief.RoadRimPoint(entry.getKey(), new Vector3(entry.getValue()), null))
                  .toArray(BoardRelief.RoadRimPoint[]::new));
        }
        for (int i = 0; i < faces.size(); i++) {
            Face face = faces.get(i);
            faces.set(i, new Face(moved.get(face.a()), moved.get(face.b()), moved.get(face.c()), face.finish(),
                  face.landEdge()));
        }
        // A rounded rim can make a quad concave. Use its interior diagonal, as the natural top's rim band does.
        for (int i = 0; i + 1 < faces.size(); i++) {
            Face a = faces.get(i), b = faces.get(i + 1);
            if (!a.a().equals(b.a()) || !a.c().equals(b.b())) { continue; }
            Face c = new Face(a.a(), a.b(), b.c(), Finish.TOP), d = new Face(a.b(), a.c(), b.c(), Finish.TOP);
            if (Math.min(upward(c), upward(d)) > Math.min(upward(a), upward(b))) {
                faces.set(i, c);
                faces.set(i + 1, d);
            }
            i++;
        }
    }

    /** The cliff must shade its shared road rim with the same normals as the final graded carrier. */
    private void roadRimNormals() {
        Map<Vector3, Vector3> normals = vertexNormals(faces);
        normals.replaceAll((p, normal) -> roadNormal(p, normal));
        relief.roadRimNormals(normals);
    }

    /** Area-weighted shared normals for an emitted surface, used by the mesh renderer and its road contacts. */
    static Map<Vector3, Vector3> vertexNormals(List<Face> faces) {
        Map<Vector3, Vector3> normals = new HashMap<>();
        for (Face face : faces) {
            Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                normals.computeIfAbsent(p, key -> new Vector3()).add(normal);
            }
        }
        normals.values().forEach(Vector3::nor);
        return normals;
    }

    /**
     * A rim that bends faster than the lattice is fine can still flip a few small triangles, mostly at corners. Each
     * flipped triangle's free corners move to the middle of their neighbours, a few times over; the outline and the
     * graded ramps stay where the warp put them.
     */
    private void untangle(Map<Vector3, Vector3> moved) {
        Map<Vector3, List<Vector3>> ring = new HashMap<>();
        for (Face face : faces) {
            List<Vector3> corner = List.of(face.a(), face.b(), face.c());
            for (int k = 0; k < 3; k++) {
                List<Vector3> around = ring.computeIfAbsent(corner.get(k), key -> new ArrayList<>());
                for (Vector3 q : List.of(corner.get((k + 1) % 3), corner.get((k + 2) % 3))) {
                    if (!around.contains(q)) { around.add(q); }
                }
            }
        }
        for (int pass = 0; pass < UNTANGLE; pass++) {
            List<Vector3> free = new ArrayList<>();
            for (Face face : faces) {
                if (face.finish() != Finish.TOP || upward(moved.get(face.a()), moved.get(face.b()), moved.get(face.c())) >= 0) {
                    continue;
                }
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    if (!free.contains(p) && !onOutline(p) && rampKeep(p) > 0) { free.add(p); }
                }
            }
            if (free.isEmpty()) { return; }
            for (Vector3 p : free) {
                Vector3 middle = new Vector3();
                for (Vector3 q : ring.get(p)) { middle.add(moved.get(q)); }
                middle.scl(1f / ring.get(p).size());
                moved.put(p, new Vector3(middle.x, middle.y, p.z));
            }
        }
    }

    /** Passes of {@link #untangle}. */
    private static final int UNTANGLE = 16;

    private boolean onOutline(Vector3 p) {
        for (int edge = 0; edge < 6; edge++) {
            if (edgeDistance(p, edge) < outlineTolerance(p)) { return true; }
        }
        return false;
    }

    private static float upward(Face face) { return upward(face.a(), face.b(), face.c()); }

    private static float upward(Vector3 a, Vector3 b, Vector3 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }

    /**
     * The outline takes the relief's rim exactly. Inside, each edge's move reaches in along the edge's normal, fading
     * out at {@link #RIM_REACH} of the way to the centre, and the edges blend by inverse square distance. A rim that
     * recedes into the hex then compresses the ground without shearing it, as radial spokes would. Only the graded
     * ramps stay put: flat ground, including a level carriageway, may follow the rim without changing its height.
     */
    private Vector3 roadPoint(Vector3 p, List<List<Face>> edgeFaces) {
        float mx = 0, my = 0, weights = 0, keep = -1;
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
            float ex = b.x - a.x, ey = b.y - a.y, length = (float) Math.hypot(ex, ey);
            float t = Math.clamp(((p.x - a.x) * ex + (p.y - a.y) * ey) / (length * length), 0, 1);
            float distance = edgeDistance(p, edge);
            float fade = 1 - distance / (RIM_REACH * edgeDistance(center, edge));
            boolean outline = distance < outlineTolerance(p);
            if (!outline) {
                float weight = 1 / (distance * distance);
                weights += weight;
                if (fade <= 0) { continue; }
                if (keep < 0) { keep = rampKeep(p); }
                if (keep <= 0) { return new Vector3(p); }
                fade *= weight;
            }
            Vector3 nominal = new Vector3(a).lerp(b, t);
            Vector3 rim = relief.roadPoint(edge, t, height(edgeFaces.get(edge), nominal.x, nominal.y));
            if (outline) { return new Vector3(rim.x, rim.y, p.z); }
            mx += fade * (rim.x - nominal.x);
            my += fade * (rim.y - nominal.y);
        }
        return keep <= 0 ? new Vector3(p) : new Vector3(p).add(mx / weights * keep, my / weights * keep, 0);
    }

    /** How far towards the hex centre an edge's rim move reaches, as a share of the edge's distance from it. */
    private static final float RIM_REACH = .8f;
    /** Lattice points this close to an edge's line are on the outline, where they take the rim exactly. */
    private static final float OUTLINE = .0001f;

    /** Large boards round edge vertices by more than the local tolerance; keep those shared contacts pinned. */
    private static float outlineTolerance(Vector3 p) {
        return Math.max(OUTLINE * BoardGeometry.hexScale(), 2 * Math.max(Math.ulp(p.x), Math.ulp(p.y)));
    }

    /** A local broad phase only: the ordinary height sampler still decides which triangles contain each point. */
    static List<Face> edgeCandidates(List<Face> geometry, Vector3 a, Vector3 b) {
        // Include both the sampler's border tolerance and rounding when lerp projects onto this edge.
        double padding = .001f * BoardGeometry.hexScale() + Math.max(outlineTolerance(a), outlineTolerance(b));
        Rectangle2D.Double bounds = new Rectangle2D.Double();
        List<Face> result = new ArrayList<>();
        for (Face face : geometry) {
            double x = Math.min(face.a().x, Math.min(face.b().x, face.c().x)) - padding;
            double y = Math.min(face.a().y, Math.min(face.b().y, face.c().y)) - padding;
            double right = Math.max(face.a().x, Math.max(face.b().x, face.c().x)) + padding;
            double top = Math.max(face.a().y, Math.max(face.b().y, face.c().y)) + padding;
            bounds.setRect(x, y, right - x, top - y);
            if (bounds.intersectsLine(a.x, a.y, b.x, b.y)) { result.add(face); }
        }
        return result;
    }

    private float edgeDistance(Vector3 p, int edge) {
        Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
        float ex = b.x - a.x, ey = b.y - a.y;
        return Math.abs((p.x - a.x) * ey - (p.y - a.y) * ex) / (float) Math.hypot(ex, ey);
    }

    /** Zero on a ramp, including its extension towards the centre, easing to one within {@link #RAMP_FADE}. */
    private float rampKeep(Vector3 p) {
        float keep = 1;
        for (RoadRamp ramp : roadRamps) {
            float dx = p.x - ramp.origin().x, dy = p.y - ramp.origin().y;
            float area = ramp.along().x * ramp.across().y - ramp.along().y * ramp.across().x;
            float t = (dx * ramp.across().y - dy * ramp.across().x) / area;
            float side = (ramp.along().x * dy - ramp.along().y * dx) / area;
            float lateral = Math.max(0, Math.max(-side, side - 1)) * ramp.across().len();
            float behind = Math.max(0, -ramp.extension() - t) * ramp.along().len();
            keep = Math.min(keep, BoardRelief.smooth((float) Math.hypot(lateral, behind) / (RAMP_FADE * BoardGeometry.hexScale())));
        }
        return keep;
    }

    /** Hex-scale distance over which ground beside a ramp returns to following the relief's rim. */
    private static final float RAMP_FADE = 6;

    /** Cut/fill grades occupy the ground beside the road, rather than closing the carriageway with vertical walls. */
    private void roadBank(RoadRamp ramp, Vector3 near, Vector3 far, Vector3 inner, Vector3 outer, boolean left,
          List<Float> runs, int across) {
        Vector3[] previous = null;
        for (float t : runs) {
            Vector3 shoulder = new Vector3(near).lerp(far, t);
            shoulder.z = center.z + ramp.rise() * ramp.progress(t);
            Vector3 ground = new Vector3(inner).lerp(outer, t);
            Vector3[] row = new Vector3[across + 1];
            for (int j = 0; j <= across; j++) {
                float s = j / (float) across;
                Vector3 p = new Vector3(shoulder).lerp(ground, s);
                p.z = shoulder.z + (ground.z - shoulder.z) * BoardRelief.smooth(s);
                // Small weathered folds belong to the earthwork, fading completely at pavement and untouched ground.
                float weathering = BoardRelief.noise(p.x / (5 * BoardGeometry.hexScale()), p.y / (5 * BoardGeometry.hexScale())) - .5f;
                float envelope = 16 * s * s * (1 - s) * (1 - s);
                p.z += weathering * envelope * Math.min(Math.abs(shoulder.z - ground.z) * .12f, .8f * BoardGeometry.hexScale());
                row[j] = p;
                if (previous != null && j > 0) {
                    if (left) { quad(previous[j], row[j], row[j - 1], previous[j - 1], Finish.TOP); }
                    else { quad(previous[j - 1], row[j - 1], row[j], previous[j], Finish.TOP); }
                }
            }
            previous = row;
        }
    }

    /** A parabolic vertical curve leaves the flat hub, then holds its grade through the shared gate. */
    private record RoadRamp(Vector3 origin, Vector3 along, Vector3 across, float rise, boolean deck) {

        private static float eased(float t) {
            return (t < .5f ? t * t : t - .25f) / .75f;
        }

        float progress(float t) {
            // A bridge owns no matching half-ramp: finish the whole curve before reaching its level deck.
            return deck ? t < .5f ? .5f * eased(2 * t) : 1 - .5f * eased(2 * (1 - t)) : eased(t);
        }

        float extension() {
            // The old run starts halfway to the edge. Two levels use nearly the full centre-to-edge distance.
            return deck ? 0 : Math.abs(rise) >= .75f * BoardGeometry.level() ? .8f : .2f;
        }

        float extended(float t) {
            return progress(Math.clamp((t + extension()) / (1 + extension()), 0, 1));
        }

        boolean curved(Vector3 p) {
            if (normal(p) == null) { return false; }
            float area = along.x * across.y - along.y * across.x;
            float t = ((p.x - origin.x) * across.y - (p.y - origin.y) * across.x) / area;
            t = (t + extension()) / (1 + extension());
            return deck ? t <= .2501f || t >= .7499f : t <= .5001f;
        }

        Vector3 normal(Vector3 p) {
            float dx = p.x - origin.x, dy = p.y - origin.y;
            float area = along.x * across.y - along.y * across.x;
            float t = (dx * across.y - dy * across.x) / area;
            float side = (along.x * dy - along.y * dx) / area;
            if (t < -extension() - .0001f || t > 1.0001f || side < -.0001f || side > 1.0001f) { return null; }
            // The flat verge can have the same XY at a cut/fill's foot. Allow only the mesh chord error and decals.
            if (Math.abs(p.z - origin.z - rise * extended(t))
                  > .2f * BoardGeometry.hexScale()) { return null; }
            t = Math.clamp((t + extension()) / (1 + extension()), 0, 1);
            float slope = Math.min(2 * (deck ? 2 * Math.min(t, 1 - t) : t), 1) / (.75f * (1 + extension()));
            return new Vector3(along.x, along.y, rise * slope).crs(across).nor();
        }
    }

    /** Road layers use the curve's tangent; the surrounding earthworks use their own shared mesh normals. */
    Vector3 roadNormal(Vector3 p) {
        return roadNormal(p, Vector3.Z);
    }

    Vector3 roadNormal(Vector3 p, Vector3 groundNormal) {
        for (RoadRamp ramp : roadRamps) {
            Vector3 normal = ramp.normal(p);
            if (normal != null) { return normal; }
        }
        return new Vector3(groundNormal == null ? Vector3.Z : groundNormal);
    }

    /** The existing terrain materials expose soil on low earthworks and rock in deeper cuts. */
    float roadLevels() {
        float levels = 0;
        for (RoadRamp ramp : roadRamps) {
            levels = Math.max(levels, Math.abs(ramp.rise()) * (ramp.deck() ? 1 : 2) / BoardGeometry.level());
        }
        return levels;
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

    /** Banks and exposed bars continue the land across their own edge, even on a hex with several shore materials. */
    BoardScene.Surface family(Face face) {
        BoardScene.Tile land = tile.liquid().present() && face.landEdge() >= 0 ? neighbor(scene, face.landEdge()) : null;
        return land != null && !land.liquid().present() ? land.surface() : BoardScene.Surface.values()[relief.family()];
    }

    float height(float x, float y) {
        return height(faces, x, y);
    }

    /** The finished terrain before Rough cover, sharing the rendered vertices rather than a second height field. */
    List<Face> groundFaces() {
        return faces.subList(0, faces.size() - rough.size());
    }

    private float height(List<Face> geometry, float x, float y) {
        return sampleHeight(geometry, x, y, BoardGeometry.groundZ(tile));
    }

    /** Shared triangle sampler for ground support and embedded rock placement. */
    static float sampleHeight(List<Face> geometry, float x, float y, float fallback) {
        float height = Float.NEGATIVE_INFINITY, nearby = Float.NEGATIVE_INFINITY;
        // One query shares one settings snapshot; avoid a thread-local lookup for every rejected triangle.
        float tolerance = .001f * BoardGeometry.hexScale();
        for (Face face : geometry) {
            if (face.finish() != Finish.ICE && face.finish() != Finish.DRESSING) {
                float exact = face.height(x, y, 0);
                if (Float.isFinite(exact)) { height = Math.max(height, exact); }
                else { nearby = Math.max(nearby, face.height(x, y, tolerance)); }
            }
        }
        // Border tolerance recovers rounding gaps, but must not choose the higher of two neighbouring slopes
        // when one triangle actually contains the contact. Road overlays use that same triangle plane.
        return Float.isFinite(height) ? height : Float.isFinite(nearby) ? nearby : fallback;
    }

    private static float square(float value) { return value * value; }

    /** Derived once per immutable terrain snapshot; repeated pointer rays reuse the rendered topology. */
    List<Face> walls(BoardScene scene, float floor) {
        return walls(scene, floor, Map.of());
    }

    /** Neighbours must have completed construction; only their immutable edge topography is borrowed. */
    List<Face> walls(BoardScene scene, float floor, Map<Coords, BoardSurface> neighbors) {
        if (wallFaces == null || wallFloor != floor) {
            wallFaces = relief.walls(sides(scene, floor, neighbors));
            wallFaces.addAll(retainingWalls);
            for (int d = 0; d < 6; d++) {
                var lower = scene.tile(tile.coords().translated(d));
                if (lower == null || lower.elevation() >= tile.elevation()) { continue; }
                for (var tunnel : BoardTunnel.entrances(scene, lower)) { tunnel.cut(wallFaces, relief); }
            }
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
        return sides(scene, floor, Map.of());
    }

    /** A cut has two heights at its shoulder. Select the interval's roof inside it, then use that roof's endpoint. */
    private float edgeHeight(Vector3 point, Vector3 interval) {
        if (interval == null) { return height(edgeTopography, point.x, point.y); }
        float x = interval.x + (center.x - interval.x) * .0001f;
        float y = interval.y + (center.y - interval.y) * .0001f;
        float highest = Float.NEGATIVE_INFINITY, result = Float.NEGATIVE_INFINITY;
        for (Face face : edgeTopography) {
            float sample = face.height(x, y, 0);
            if (sample > highest) {
                float endpoint = face.height(point.x, point.y);
                if (Float.isFinite(endpoint)) { highest = sample; result = endpoint; }
            }
        }
        return Float.isFinite(result) ? result : height(edgeTopography, point.x, point.y);
    }

    /** The build's completed surfaces save reconstructing neighbouring tops; the lookup is not retained. */
    List<Side> sides(BoardScene scene, float floor, Map<Coords, BoardSurface> neighbors) {
        List<Side> result = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            // The edge as the shore moves its corners, where both hexes' tops meet.
            Vector3 a = moved(edge), b = moved((edge + 1) % 6);
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (mouth(edge) && neighbor != null && neighbor.elevation() == tile.elevation()) {
                continue; // Beds of one level share their mouth's profile and their banks' top: nothing stands between.
            }
            BoardSurface adjacent = neighbor == null ? null : neighbors.get(neighbor.coords());
            if (adjacent == null && neighbor != null) { adjacent = new BoardSurface(scene, neighbor, false, lod); }
            boolean basin = crests[edge] != null && adjacent != null && adjacent.relief.waterfallFoot((edge + 3) % 6);
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
            int previous = 0;
            for (int i = 1; i < points.size(); i++) {
                float from = points.get(previous), to = points.get(i);
                // Merge nearly coincident cuts into the next interval instead of leaving a slit in the wall.
                if (to - from < 0.0001f) { continue; }
                previous = i;
                if (from > spanA - 0.0001f && to < spanB + 0.0001f) { continue; }
                Vector3 start = new Vector3(a).lerp(b, from);
                Vector3 end = new Vector3(a).lerp(b, to);
                boolean graded = ramps != 0 || adjacent != null && adjacent.ramps != 0;
                // Gentle liquid mouths share continuous bank/bed endpoints. Sampling inside the interval instead
                // leaves their wall a fraction short of both banks, visible as pinholes at close camera distances.
                boolean inset = !waterSlope(tile, neighbor) && (!graded || crest == null
                      && (tile.liquid().present() || neighbor != null && neighbor.liquid().present()));
                Vector3 sampleA = inset ? new Vector3(start).lerp(end, 0.001f) : start;
                Vector3 sampleB = inset ? new Vector3(end).lerp(start, 0.001f) : end;
                // A road cut is discontinuous; both endpoints must come from this interval's own roof.
                Vector3 interval = graded ? new Vector3(start).lerp(end, .5f) : null;
                start.z = edgeHeight(sampleA, interval);
                end.z = edgeHeight(sampleB, interval);
                float lowA = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                      : adjacent.edgeHeight(sampleA, interval);
                float lowB = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                      : adjacent.edgeHeight(sampleB, interval);
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
                    float lowA = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                          : adjacent.height(adjacent.edgeTopography, start.x, start.y);
                    float lowB = adjacent == null ? floor : basin ? neighbor.elevation() * BoardGeometry.level()
                          : adjacent.height(adjacent.edgeTopography, end.x, end.y);
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
