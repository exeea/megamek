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

    /** Only inputs that alter topology; the ramp mask also captures second-ring road/bridge approaches. */
    record Geometry(int elevation, int waterDepth, boolean frozen, int roadExits, BoardScene.Surface surface,
          BoardLiquid liquid, boolean detailedGround, List<BoardScene.Feature> features, int ramps) { }

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

    private static final int SHORE_SEGMENTS = 6;
    /** A fall's lip: the water stops this far short of the mouth so the sheet can curve down to the shared edge. */
    private static final float FALL_LIP_WIDTH = .045f;
    private static final float FALL_LIP_DROP = .5f;
    /** Share of each ray from the waterline to the hex centre over which a bed descends; the rest is its plateau. */
    private static final float PLATEAU = .75f;
    /**
     * Water depth over a fall's lip, in hex-scale units: the bed rises to a ledge the water pours over, so the wall
     * beneath the fall closes up to just under the sheet. No pool is shallower, so both sides of a corner agree.
     */
    private static final float LIP_DEPTH = 1;
    /** DRESSING is thin detail on the ground, such as tree pits: drawn and picked, never raising what stands there. */
    enum Finish { TOP, RIM, CAP, WALL, OUTCROP, SHORE, BED, BANK, ICE, DRESSING }
    /** landEdge identifies the adjoining dry hex for bank artwork; other faces use -1. */
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

    final BoardScene.Tile tile;
    final List<Face> faces = new ArrayList<>();
    final List<Vector3> water = new ArrayList<>();
    /**
     * The water's full outline, before a falling mouth pulls its surface back from the edge. Segments on open mouths
     * continue into the next pool; every other segment is a bank.
     */
    final List<Vector3> outline = new ArrayList<>();
    final List<Face> waterFaces = new ArrayList<>();
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
     * Per falling edge, the miter its sheet turns on at its first and last corner where another fall of the same drop
     * carries on, from this pool or from the neighbouring one; null where the end is free.
     */
    private final Vector3[] joinA = new Vector3[6];
    private final Vector3[] joinB = new Vector3[6];
    private final Vector3 center;
    private final Vector3[] corners = new Vector3[6];

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
        relief = new BoardRelief(scene, tile, ramps);
        if (detailed) { relief.top(faces); }
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
        List<Integer> mouths = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            shore[edge] = neighbor == null || !tile.liquid().connects(neighbor.liquid()) ? 8 * BoardGeometry.HEX_SCALE : 0;
            if (shore[edge] == 0) {
                openMouths |= 1 << edge;
                mouths.add(edge);
                // A fall owns the last stretch of its mouth: the water stops short so the sheet can curve down.
                if (!tile.frozen() && !neighbor.frozen() && tile.elevation() > neighbor.elevation()) {
                    lip[edge] = fallLip(BoardGeometry.waterZ(tile), BoardGeometry.waterZ(neighbor));
                }
            }
        }
        boolean channel = mouths.size() == 2 && mouths.getLast() - mouths.getFirst() >= 2
              && mouths.getLast() - mouths.getFirst() <= 4;
        List<Integer> channelMouths = channel ? mouths : List.of();
        Vector3[] waterline = curvedContour(shore, BoardGeometry.waterZ(tile), channelMouths);
        basin(scene, waterline, shore);
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
            fan(corners, center.z, Finish.ICE);
        } else {
            for (int edge = 0; edge < 6; edge++) {
                if (lip[edge] > 0) {
                    joinA[edge] = join(scene, shore, lip, edge, (edge + 5) % 6);
                    joinB[edge] = join(scene, shore, lip, edge, (edge + 1) % 6);
                }
            }
            // The water recedes from a falling mouth; the sheet's lip curves back down to the shared edge.
            Vector3[] surface = pulledBack(waterline, lip);
            outline.addAll(List.of(waterline));
            water.addAll(List.of(surface));
            polygon(surface, Finish.TOP, waterFaces);
            for (int edge = 0; edge < 6; edge++) {
                if (lip[edge] <= 0) {
                    continue;
                }
                float bottom = BoardGeometry.waterZ(scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge))));
                waterfalls.add(new Side(waterline[edge * SHORE_SEGMENTS],
                      waterline[((edge + 1) % 6) * SHORE_SEGMENTS], bottom, bottom, edge));
            }
        }
    }

    /**
     * The bed as one sculpted basin. It descends from the waterline, or from an open mouth's profile, along rays toward
     * the hex centre, where a plateau keeps the unit anchor at the game depth. Mouth profiles and corners are pure
     * functions of the hexes sharing them, so neighbouring beds meet without a step; a basin whose rim already lies at
     * its own depth all round, as inside a lake, stays a flat fan.
     */
    private void basin(BoardScene scene, Vector3[] waterline, float[] shore) {
        int count = waterline.length;
        float surface = BoardGeometry.waterZ(tile), full = depth(tile), ledge = LIP_DEPTH * BoardGeometry.HEX_SCALE;
        boolean[] spills = new boolean[6];
        for (int edge = 0; edge < 6; edge++) {
            spills[edge] = shore[edge] == 0 && neighbor(scene, edge).elevation() < tile.elevation();
        }
        // Depth at each edge's first point: none where a bank meets it, the ledge beside a fall, else the depth shared
        // by the corner's hexes.
        float[] ends = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6;
            ends[edge] = shore[edge] > 0 || shore[previous] > 0 ? 0
                  : spills[edge] || spills[previous] ? ledge : cornerDepth(scene, edge);
        }
        float[] rim = new float[count];
        boolean level = true;
        for (int i = 0; i < count; i++) {
            int edge = i / SHORE_SEGMENTS;
            if (i % SHORE_SEGMENTS == 0) {
                rim[i] = ends[edge];
            } else if (shore[edge] == 0) {
                // Beds of one level meet at their mean depth; a fall pours over its ledge into the pool below, whose
                // bed keeps its own depth.
                BoardScene.Tile other = neighbor(scene, edge);
                float middle = spills[edge] ? ledge
                      : other.elevation() == tile.elevation() ? (full + depth(other)) / 2 : full;
                int next = (edge + 1) % 6;
                rim[i] = mouthDepth(waterline[i], waterline[edge * SHORE_SEGMENTS], ends[edge],
                      waterline[next * SHORE_SEGMENTS], ends[next], middle);
            }
            level &= Math.abs(rim[i] - full) < .001f * BoardGeometry.HEX_SCALE;
        }
        Vector3[] ring = new Vector3[count];
        for (int i = 0; i < count; i++) {
            ring[i] = new Vector3(waterline[i].x, waterline[i].y, surface - rim[i]);
        }
        int hexes = scene.width() * scene.height();
        int rings = level ? 0 : hexes <= BoardRelief.FULL_DETAIL_HEXES ? 4
              : hexes <= BoardRelief.MEDIUM_DETAIL_HEXES ? 3 : 2;
        for (int k = 1; k <= rings; k++) {
            float x = k / (float) rings, scale = 1 - PLATEAU * x;
            // Falling from the rim with some slope, steepest halfway, and easing into the plateau.
            float descent = .25f * x + .75f * x * x * (3 - 2 * x);
            Vector3[] inner = new Vector3[count];
            for (int i = 0; i < count; i++) {
                inner[i] = new Vector3(center.x + (waterline[i].x - center.x) * scale,
                      center.y + (waterline[i].y - center.y) * scale, surface - rim[i] - (full - rim[i]) * descent);
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
        if (rings > 0) { interiorTo = faces.size(); }
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
     * The miter at the corner between falling edge {@code edge} and edge {@code side} when a fall of the same drop
     * continues there: this pool's own fall over {@code side}, or the fall of the neighbouring pool across
     * {@code side}, at this pool's level, into the same lower pool. Both sheets and both pools then turn that corner on
     * one vector, whose projection on each fall's outward is one, so nothing opens between them. Null where the end is
     * free.
     */
    private Vector3 join(BoardScene scene, float[] shore, float[] lip, int edge, int side) {
        Vector3 other = null;
        if (lip[side] > 0) {
            other = edgeOutward(side);
        } else if (shore[side] == 0) {
            BoardScene.Tile beside = neighbor(scene, side), below = neighbor(scene, edge);
            Coords at = beside.coords();
            if (beside.elevation() == tile.elevation() && !beside.frozen() && beside.liquid().connects(below.liquid())) {
                for (int k = 0; k < 6; k++) {
                    if (at.translated(BoardGeometry.edgeDirection(k)).equals(below.coords())) {
                        other = BoardGeometry.corner(at, 0, k + 1).sub(BoardGeometry.corner(at, 0, k)).crs(Vector3.Z).nor();
                    }
                }
            }
        }
        if (other == null) { return null; }
        Vector3 own = edgeOutward(edge);
        return own.add(other).scl(1 / (1 + edgeOutward(edge).dot(other)));
    }

    /** Outward normal of one edge: away from this hex. */
    private Vector3 edgeOutward(int edge) {
        return edgeInward(edge).scl(-1);
    }

    /** The direction the end of a fall moves out along: its own outward, or the miter it shares with the next fall. */
    Vector3 fallDirection(Side fall, boolean atA) {
        Vector3 join = (atA ? joinA : joinB)[fall.edge()];
        return join != null ? new Vector3(join) : edgeOutward(fall.edge());
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

    /** Pull the water surface back from each falling mouth, leaving the lip's room for the sheet to fill. */
    private Vector3[] pulledBack(Vector3[] waterline, float[] lip) {
        Vector3[] result = waterline.clone();
        for (int index = 0; index < waterline.length; index++) {
            int edge = index / SHORE_SEGMENTS;
            // An anchor also ends the previous edge, whose fall pulls the shared corner by its own inward.
            int previous = (edge + 5) % 6;
            boolean anchor = index % SHORE_SEGMENTS == 0;
            if (lip[edge] <= 0 && !(anchor && lip[previous] > 0)) {
                continue;
            }
            // A corner where two falls meet recedes along their shared miter, which is where both pulled-back edges and
            // both sheets meet, whether the other fall is this pool's or the neighbouring one's.
            Vector3 join = !anchor ? null : lip[edge] > 0 ? joinA[edge] : joinB[previous];
            Vector3 inward = join != null ? new Vector3(join).scl(-Math.max(lip[edge], lip[previous]))
                  : edgeInward(lip[edge] > 0 ? edge : previous).scl(lip[edge] > 0 ? lip[edge] : lip[previous]);
            result[index] = new Vector3(waterline[index]).add(inward);
        }
        return result;
    }

    private Vector3 shoreLip(Vector3 waterline, Vector3 boundary) {
        Vector3 lip = new Vector3(waterline).lerp(boundary,
              Math.min(1, 5 * BoardGeometry.HEX_SCALE / waterline.dst(boundary)));
        lip.z = center.z;
        return lip;
    }

    /** Rounded bays and irregular banks, with fixed mouths that meet the next hex exactly. */
    private Vector3[] curvedContour(float[] inset, float z, List<Integer> channelMouths) {
        Vector3[] anchors = contour(inset, z);
        Vector3[] result = new Vector3[6 * SHORE_SEGMENTS];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6, next = (edge + 1) % 6;
            Vector3 a = anchors[edge], b = anchors[next];
            Vector3 c1 = new Vector3(a).mulAdd(new Vector3(b).sub(anchors[previous]), 1f / 6);
            Vector3 c2 = new Vector3(b).mulAdd(new Vector3(anchors[(edge + 2) % 6]).sub(a), -1f / 6);
            if (inset[previous] == 0) {
                Vector3 inward = new Vector3(corners[edge]).sub(corners[previous]).crs(Vector3.Z).nor().scl(-1);
                c1.set(a).mulAdd(inward, a.dst(b) / 3);
            }
            if (inset[next] == 0) {
                Vector3 outward = new Vector3(corners[(edge + 2) % 6]).sub(corners[next]).crs(Vector3.Z).nor();
                c2.set(b).mulAdd(outward, -a.dst(b) / 3);
            }
            float phase = tile.coords().getX() * 1.73f + tile.coords().getY() * 2.41f + edge;
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                float t = segment / (float) SHORE_SEGMENTS, s = 1 - t;
                Vector3 point;
                if (inset[edge] == 0) {
                    point = new Vector3(a).lerp(b, t);
                } else {
                    point = new Vector3(a).scl(s * s * s).mulAdd(c1, 3 * s * s * t)
                          .mulAdd(c2, 3 * s * t * t).mulAdd(b, t * t * t);
                    Vector3 inward = new Vector3(center.x - point.x, center.y - point.y, 0).nor();
                    float ripple = (float) Math.sin(phase + t * Math.PI * 3) * 16 * t * t * s * s;
                    point.mulAdd(inward, ripple * 1.4f * BoardGeometry.HEX_SCALE);
                }
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        if (!channelMouths.isEmpty()) {
            channelBank(result, anchors, channelMouths.getFirst(), channelMouths.getLast());
            channelBank(result, anchors, channelMouths.getLast(), channelMouths.getFirst());
        }
        return result;
    }

    /** One continuous bank between river mouths, without a separate bay at each hex centre. */
    private void channelBank(Vector3[] contour, Vector3[] anchors, int from, int to) {
        Vector3 a = new Vector3(corners[from]).lerp(corners[(from + 1) % 6], 0.5f);
        Vector3 b = new Vector3(corners[to]).lerp(corners[(to + 1) % 6], 0.5f);
        a.z = b.z = anchors[from].z;
        Vector3 inA = edgeInward(from);
        Vector3 inB = edgeInward(to);
        // A gentle bend keeps the inner bank from folding back across itself.
        float handle = a.dst(b) * 0.4f;
        Vector3 c1 = new Vector3(a).mulAdd(inA, handle);
        Vector3 c2 = new Vector3(b).mulAdd(inB, handle);
        float widthA = a.dst(anchors[(from + 1) % 6]), widthB = b.dst(anchors[to]);
        int segments = Math.floorMod(to - from - 1, 6) * SHORE_SEGMENTS;
        for (int index = 0; index < segments; index++) {
            float t = index / (float) segments, s = 1 - t;
            Vector3 tangent = new Vector3(c1).sub(a).scl(3 * s * s)
                  .mulAdd(new Vector3(c2).sub(c1), 6 * s * t).mulAdd(new Vector3(b).sub(c2), 3 * t * t);
            Vector3 point = new Vector3(a).scl(s * s * s).mulAdd(c1, 3 * s * s * t)
                  .mulAdd(c2, 3 * s * t * t).mulAdd(b, t * t * t);
            contour[((from + 1) * SHORE_SEGMENTS + index) % contour.length] = point
                  .mulAdd(tangent.crs(Vector3.Z).nor(), widthA + (widthB - widthA) * t);
        }
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

    /** Intersections of independently offset edge planes preserve matching river mouths between neighbors. */
    private Vector3[] contour(float[] inset, float z) {
        Vector3[] result = new Vector3[6];
        for (int vertex = 0; vertex < 6; vertex++) {
            int before = (vertex + 5) % 6;
            Vector3 a = corners[before];
            Vector3 b = corners[vertex];
            Vector3 c = corners[(vertex + 1) % 6];
            Vector3 n1 = new Vector3(b).sub(a).crs(Vector3.Z).nor();
            Vector3 n2 = new Vector3(c).sub(b).crs(Vector3.Z).nor();
            float d1 = n1.dot(a) - inset[before];
            float d2 = n2.dot(b) - inset[vertex];
            float determinant = n1.x * n2.y - n1.y * n2.x;
            result[vertex] = new Vector3((d1 * n2.y - n1.y * d2) / determinant,
                  (n1.x * d2 - d1 * n2.x) / determinant, z);
        }
        // The 84x72 artwork is not a mathematically regular hex. Use the same
        // distance along both sides of a shared mouth instead of intersecting
        // two slightly different corner angles independently.
        for (int edge = 0; edge < 6; edge++) {
            if (inset[edge] == 0) {
                int next = (edge + 1) % 6;
                float length = corners[edge].dst(corners[next]);
                // Open mouths use four units less land on each side than a rounded basin bank.
                // The wider channel occupies the old water-and-shore width; its sand fade reaches the corners.
                float from = Math.max(0, inset[(edge + 5) % 6] - 4 * BoardGeometry.HEX_SCALE) * 1.1547005f / length;
                float to = Math.max(0, inset[next] - 4 * BoardGeometry.HEX_SCALE) * 1.1547005f / length;
                result[edge].set(corners[edge]).lerp(corners[next], from).z = z;
                result[next].set(corners[next]).lerp(corners[edge], to).z = z;
            }
        }
        return result;
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

    /** Only the higher column contributes a shared wall; road gates meet at the same height. */
    List<Side> sides(BoardScene scene, float floor) {
        List<Side> result = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = corners[edge], b = corners[(edge + 1) % 6];
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
            if (mouth(edge) && neighbor.elevation() == tile.elevation()) {
                continue; // Beds of one level share their mouth's profile and their banks' top: nothing stands between.
            }
            BoardSurface adjacent = neighbor == null ? null : new BoardSurface(scene, neighbor, false);
            TreeSet<Float> cuts = new TreeSet<>(List.of(0f, 1f));
            cuts(a, b, cuts);
            if (adjacent != null) {
                adjacent.cuts(a, b, cuts);
            }
            List<Float> points = new ArrayList<>(cuts);
            for (int i = 1; i < points.size(); i++) {
                float from = points.get(i - 1), to = points.get(i);
                if (to - from < 0.0001f) {
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
        }
        return result;
    }
}
