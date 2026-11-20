/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;

/** Cosmetic portals at authored road exits blocked by a tall wall. Never adds a game connection. */
record BoardTunnel(Coords road, int direction, Vector3 origin, Vector3 along, BoardRoad.Kind kind, boolean bridge, float widthScale,
      Wings wings) {
    static final String ASSET = "scenery/road/road-tunnel";
    static final String LONG_WINGS_ASSET = "scenery/road/road-tunnel-long-wings";
    static final String BRIDGE_ASSET = "scenery/road/road-tunnel-bridge";
    // Asset contract: mm-data tools/board-models/road-tunnel.md. +Y points into the cliff, Z is up.
    /** The opening's half width; its elliptical arch rises ARCH_RISE above the piers' SPRING. */
    static final float HALF_WIDTH = 17.5f, SPRING = 6.9f, ARCH_RISE = 10.925f;
    /** Authored GLBs have a road's 30px carriageway; only their lateral dimension follows a wider carried route. */
    static final float ASSET_ROAD_HALF = 15;
    /** The portal's shell, its half width and roof. */
    static final float SHELL_HALF = 22.25f, ROOF = 22.1f;
    /** The headwall on the shell's front (mm-data road_tunnel.py): its cap's top, and its back in from the opening. */
    static final float HEADWALL = 26, HEADWALL_BACK = 1.75f;
    static final float DEPTH = 36;
    /** How far the opening stands in front of the hex's edge, in tile pixels. */
    static final float SETBACK = 3;
    static final int ARCH_SEGMENTS = 16;
    /**
     * A ground portal's wing walls (mm-data road_tunnel.py): their outer sides flare from {@code root} at the face to
     * {@code end} {@code length} ahead, their tops fall from {@code high} to {@code low}. LONG lies along the scree beside
     * the approach on the height field; SHORT, as first made, everywhere else (#entrances). A bridge portal has none.
     */
    record Wings(float root, float end, float length, float high, float low) {
        static final Wings LONG = new Wings(21.56f, 30.12f, 21.4f, 25.1f, 4), SHORT = new Wings(21.4f, 27.4f, 6, 9.2f, 4);
        /** Both forms' thickness across: their inner faces stand this far in from their outer ones. */
        static final float THICK = 1.75f;

        /** The outer side's distance from the axis at a distance ahead of the face, clamped to the wall. */
        float outer(float ahead) { return root + (end - root) * Math.clamp(ahead / length, 0, 1); }

        /** The top's height at a distance ahead of the face, clamped to the wall. */
        float top(float ahead) { return high + (low - high) * Math.clamp(ahead / length, 0, 1); }
    }
    /** Over how far the ground eases back to its own shape above the wings, and over how far their ends sink into it. */
    static final float WING_EASE = 6, WING_BURY = 3;

    static List<BoardTunnel> entrances(BoardScene scene, BoardScene.Tile tile) {
        List<BoardTunnel> result = new ArrayList<>();
        if (BoardRoad.rendered(tile)) {
            entrances(scene, tile, tile.roadExits(), BoardRoadGrade.ground(tile), tile.road(), false, result);
        }
        // A deck's road continues into the wall on its own sides.
        var feature = BoardBridge.feature(tile);
        if (feature != null && tile.deckRoad() != BoardRoad.Kind.NONE) {
            entrances(scene, tile, tile.deckRoadExits(), tile.elevation() + feature.elevation(), tile.deckRoad(), true, result);
        }
        return List.copyOf(result);
    }

    /**
     * The ground tunnels whose road hex lies within two hexes of {@code at}, whose scree (FieldStamps#scree) can reach
     * it; in one order, so every hex that reads them applies them alike.
     */
    static List<BoardTunnel> near(BoardScene scene, Coords at) {
        Set<Coords> around = new LinkedHashSet<>();
        around.add(at);
        for (int d = 0; d < 6; d++) {
            around.add(at.translated(d));
            for (int e = 0; e < 6; e++) { around.add(at.translated(d).translated(e)); }
        }
        List<BoardTunnel> result = new ArrayList<>();
        for (Coords c : around) {
            BoardScene.Tile tile = scene.tile(c);
            if (tile == null) { continue; }
            for (BoardTunnel tunnel : entrances(scene, tile)) {
                if (!tunnel.bridge()) { result.add(tunnel); }
            }
        }
        result.sort(Comparator.comparingDouble((BoardTunnel tunnel) -> tunnel.origin.x).thenComparingDouble(tunnel -> tunnel.origin.y));
        return result;
    }

    /**
     * The hexes an edit at {@code at} rebuilds for the scree of the mouths whose scree reaches it, before or after the
     * edit (FieldStamps#scree): those that read a mouth (#near: within two of its road hex) and the ring that shares
     * their samples. Empty where no mouth's scree reaches the edited hex.
     */
    static Set<Coords> screeHexes(BoardScene before, BoardScene after, Coords at) {
        Set<Coords> result = new LinkedHashSet<>();
        for (BoardScene scene : List.of(before, after)) {
            for (BoardTunnel tunnel : near(scene, at)) {
                if (tunnel.frame(scene) != null) { result.addAll(tunnel.road.allAtDistanceOrLess(3)); }
            }
        }
        return result;
    }

    /**
     * The frame (#frame) of a ground tunnel's scree, capped at the top of the hex it enters; null where that hex is not
     * natural ground (FieldBoard#dryNatural), which sheds none.
     */
    float[] frame(BoardScene scene) {
        BoardScene.Tile entered = scene.tile(road.translated(direction));
        return entered == null || !FieldBoard.dryNatural(entered) ? null : frame(entered.groundLevel() * BoardGeometry.level());
    }

    private static void entrances(BoardScene scene, BoardScene.Tile tile, int exits, float elevation,
          BoardRoad.Kind kind, boolean bridge, List<BoardTunnel> result) {
        for (int d = 0; d < 6; d++) {
            if ((exits & 1 << d) == 0) { continue; }
            var next = scene.tile(tile.coords().translated(d));
            if (next == null || next.liquid().present()
                  || (bridge && BoardBridge.connected(tile, next, d))
                  || (next.elevation() - elevation) * BoardGeometry.level() < 20 * BoardGeometry.hexScale()) { continue; }
            // Ground roads keep their existing two-hex grading. A suspended deck instead meets the cliff at deck height.
            if (!bridge && (next.elevation() - elevation <= 2 || BoardSurface.hasRoadApproach(tile, next, d))) { continue; }
            Vector3 center = BoardGeometry.center(tile.coords(), elevation);
            Vector3 neighbor = BoardGeometry.center(next.coords(), elevation);
            Vector3 along = new Vector3(neighbor).sub(center).nor();
            Vector3 origin = center.lerp(neighbor, .5f).mulAdd(along, -SETBACK * BoardGeometry.hexScale());
            origin.z += GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
            int rails = bridge ? tile.features().stream().map(BoardScene.Feature::decoration)
                  .filter(route -> RailRoute.kind(route) == RailRoute.Kind.RAIL && RailRoute.onDeck(route))
                  .mapToInt(route -> route.connections()).reduce(0, (a, b) -> a | b) : tile.railExits();
            float half = (rails & 1 << d) != 0 ? BoardRoad.CORRIDOR_HALF : kind.halfWidth;
            // Long wings hold the field's scree, which only natural ground sheds; they would stand on a road or rail
            // leaving at a side beside the tunnel's. Today's ground keeps the short ones.
            int beside = 1 << (d + 1) % 6 | 1 << (d + 5) % 6;
            Wings wings = bridge ? null : TerrainField.ON && FieldBoard.dryNatural(next)
                  && ((tile.roadExits() | tile.railExits()) & beside) == 0 ? Wings.LONG : Wings.SHORT;
            result.add(new BoardTunnel(tile.coords(), d, origin, along, kind, bridge, half / ASSET_ROAD_HALF, wings));
        }
    }

    /** Half the span the face a portal is set into keeps flat: its shell's, or to just past its wings' ends. */
    private float span() { return bridge ? SHELL_HALF : wings.end + .6f; }

    String asset() { return bridge ? BRIDGE_ASSET : wings == Wings.LONG ? LONG_WINGS_ASSET : ASSET; }

    /**
     * The light in a tunnel at a depth in from its opening, in tile pixels: full at the hex's edge, falling off to black at
     * its end. The GLBs' floor and lining colours follow it.
     */
    static float light(float depth) {
        return (float) Math.pow(Math.clamp(1 - (depth - SETBACK) / (DEPTH - SETBACK), 0, 1), 2.2);
    }

    /** How far a world point lies in from the opening, in tile pixels. */
    float depth(float x, float y) { return ((x - origin.x) * along.x + (y - origin.y) * along.y) / BoardGeometry.hexScale(); }

    Matrix4 transform() {
        float turn = (float) Math.toDegrees(Math.atan2(-along.x, along.y));
        float scale = BoardGeometry.hexScale();
        return new Matrix4().setToTranslation(origin).rotate(Vector3.Z, turn).scale(scale * widthScale, scale, scale);
    }

    private Vector3 local(Vector3 p) {
        float scale = BoardGeometry.hexScale(), x = (p.x - origin.x) / scale, y = (p.y - origin.y) / scale;
        return new Vector3((x * along.y - y * along.x) / widthScale, x * along.x + y * along.y, (p.z - origin.z) / scale);
    }

    /** How far ahead of the face the flat face's span reaches, in tile pixels: past the wing walls' ends. */
    private float reach() { return bridge ? 8 : wings.length + 2; }

    /** Over how many tile pixels the rock round a portal eases back to its natural face, above and beside it. */
    private static final float RELIEF_RISE = 36, RELIEF_SIDE = 16;

    /**
     * The share of a cliff's rock relief that stands at (x, y, z) round the portal: none on the face it is set into,
     * up to just above its roof, all of it {@link #RELIEF_SIDE} beside the flat span and {@link #RELIEF_RISE} above.
     * Today's relief (BoardRelief) and the height field's walls (FieldBoard#portalFace) both ease their push by it.
     */
    float relief(float x, float y, float z) {
        Vector3 p = local(new Vector3(x, y, z));
        return relief(p.x, p.y, p.z, widthScale, span(), reach());
    }

    /** {@link #relief(float, float, float)} from a ground portal's frame (#frame), as the height field holds it. */
    static float relief(float[] f, float x, float y, float z) {
        float dx = (x - f[0]) / f[6], dy = (y - f[1]) / f[6];
        return relief((dx * f[4] - dy * f[3]) / f[5], dx * f[3] + dy * f[4], (z - f[2]) / f[6], f[5], f[14], f[15]);
    }

    /** The relief's share at a point of the portal's frame (#local), from the flat span's half width and reach. */
    private static float relief(float lx, float ly, float lz, float widthScale, float span, float reach) {
        float beside = Math.max((Math.abs(lx) - span) * widthScale, Math.max(-reach - ly, ly - DEPTH - 2));
        return Math.max(BoardRelief.smooth(beside / RELIEF_SIDE), BoardRelief.smooth((lz - ROOF - 3) / RELIEF_RISE));
    }

    /**
     * Moves a point of the ground ahead of a ground portal sideways out of its wing walls' span, up to their tops: the
     * slopes beside the approach stand behind the walls, which retain them, instead of burying them.
     */
    void clearWings(Vector3 point) {
        if (bridge) { return; }
        Vector3 p = local(point);
        if (p.y >= 0 || p.y <= -wings.length) { return; }
        float outer = wings.outer(-p.y), top = wings.top(-p.y);
        float share = (1 - BoardRelief.smooth((p.z - top) / WING_EASE))
              * BoardRelief.smooth((p.y + wings.length) / WING_BURY);
        float move = (outer - Math.abs(p.x)) * widthScale * BoardGeometry.hexScale() * share;
        if (move <= 0) { return; }
        float side = p.x < 0 ? -1 : 1;
        point.x += along.y * side * move;
        point.y -= along.x * side * move;
    }

    /** Whether a point stands over the portal's shell, above its roof slab. */
    boolean overRoof(Vector3 point) {
        Vector3 p = local(point);
        return p.z >= ROOF && p.y >= 0 && p.y <= DEPTH && Math.abs(p.x) <= SHELL_HALF;
    }

    /** Keep whole decorative rocks clear of the shell, wing walls and the road approaching the mouth. */
    boolean obstructs(Vector3 base, float radius, float height) {
        Vector3 p = local(base);
        float scale = BoardGeometry.hexScale(), r = radius / scale;
        // Above the roof slab only the headwall, along the front, still stands in the way.
        if (p.z >= (p.y < HEADWALL_BACK + r ? HEADWALL : ROOF) + 1 || p.z + height / scale <= -1) { return false; }
        // Long wings hold scree on their outer sides: only the shell, the walls and the ground between them stay clear.
        boolean shell = Math.abs(p.x) < (wings == Wings.LONG ? SHELL_HALF : span()) + r / widthScale
              && p.y > (bridge ? -.1f : -7) - r && p.y < DEPTH + r;
        boolean wall = wings == Wings.LONG && p.y < r && p.y > -wings.length - 1 - r
              && Math.abs(p.x) < wings.outer(-p.y) + .6f + r / widthScale;
        boolean approach = Math.abs(p.x) < HALF_WIDTH + 1 + r / widthScale && p.y > -36 - r && p.y < r;
        return shell || wall || approach;
    }

    /** Remove only the portal's arch prism from the existing cliff mesh; its surrounding slope stays native. */
    void cut(List<BoardSurface.Face> faces, BoardRelief relief) {
        BoardSurface.subtract(faces, planes().stream()
              .<BoardSurface.Boundary>map(plane -> point -> distance(local(point), plane)).toList(), relief);
    }

    /** The portal's arch prism in its own frame (#local): per plane its normal and offset, positive outside. */
    private static List<float[]> planes() {
        List<float[]> planes = new ArrayList<>();
        planes.add(new float[] { 1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { -1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { 0, 1, 0, DEPTH });
        planes.add(new float[] { 0, -1, 0, .1f });
        planes.add(new float[] { 0, 0, -1, .1f });
        for (int i = 0; i < ARCH_SEGMENTS; i++) {
            double a = i * Math.PI / ARCH_SEGMENTS, b = (i + 1) * Math.PI / ARCH_SEGMENTS;
            float ax = HALF_WIDTH * (float) Math.cos(a), az = SPRING + ARCH_RISE * (float) Math.sin(a);
            float bx = HALF_WIDTH * (float) Math.cos(b), bz = SPRING + ARCH_RISE * (float) Math.sin(b);
            float nx = bz - az, nz = ax - bx;
            planes.add(new float[] { nx, 0, nz, nx * ax + nz * az });
        }
        return planes;
    }

    /**
     * The arch prism in world space, for the height field (FieldClip): per plane six floats, its normal and a point on it,
     * the distance {@code n . (p - point)} being the frame's (#local) scaled.
     */
    float[] region() { return world(planes()); }

    /**
     * The opening's footprint in world space (as #region): the arch prism's sides, depth and face; with {@code roofed}
     * only up to the arch's crown. The height field lowers a road hex's ground inside it and cuts the hex the tunnel
     * enters along it, below the crown, where the portal's shell covers the cut (FieldStamps).
     */
    float[] footprint(boolean roofed) {
        // The face first: a plane cuts each piece across, so the ground in front of the opening then stays whole.
        List<float[]> prism = planes();
        List<float[]> planes = new ArrayList<>(List.of(prism.get(3), prism.get(2), prism.get(0), prism.get(1)));
        if (roofed) { planes.add(new float[] { 0, 0, 1, SPRING + ARCH_RISE }); }
        return world(planes);
    }

    /**
     * A ground portal's frame (#local) as plain numbers: origin x, y, z, along x, y, the width's scale, the hex scale,
     * {@code top} (the entered hex's top, the scree's cap), its wings' root, end, length, high and low, its road hex
     * (FieldBoard#packed), whose own ground its approach is (FieldStamps#approach), and its flat face's half span and
     * reach (#relief).
     */
    private float[] frame(float top) {
        return new float[] { origin.x, origin.y, origin.z, along.x, along.y, widthScale, BoardGeometry.hexScale(), top,
              wings.root, wings.end, wings.length, wings.high, wings.low, FieldBoard.packed(road), span(), reach() };
    }

    /** Planes of the frame (#local) in world space: per plane six floats, its normal and a point on it. */
    private float[] world(List<float[]> planes) {
        float[] result = new float[planes.size() * 6];
        float scale = BoardGeometry.hexScale(), ax = along.x, ay = along.y;
        for (int i = 0; i < planes.size(); i++) {
            float[] p = planes.get(i);
            float wx = (p[0] * ay / widthScale + p[1] * ax) / scale, wy = (-p[0] * ax / widthScale + p[1] * ay) / scale;
            float wz = p[2] / scale, length2 = wx * wx + wy * wy + wz * wz;
            result[i * 6] = wx; result[i * 6 + 1] = wy; result[i * 6 + 2] = wz;
            result[i * 6 + 3] = origin.x + p[3] * wx / length2;
            result[i * 6 + 4] = origin.y + p[3] * wy / length2;
            result[i * 6 + 5] = origin.z + p[3] * wz / length2;
        }
        return result;
    }

    private static float distance(Vector3 p, float[] plane) {
        return p.x * plane[0] + p.y * plane[1] + p.z * plane[2] - plane[3];
    }
}
