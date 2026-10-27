/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/**
 * A Solaris elevator's platform ({@link BoardFeatures#shaft}): its diamond-plate top inside a hazard-striped band, its
 * level painted on as a decal; raised, its lift, open beneath it: the deck's hazard-striped rim and girders on a massive
 * scissor table that two hydraulic cylinders push open, standing in a machinery pit sunk below the ground it rose from.
 * CPU geometry in world units; the terrain draws it in place of the hex's top and walls, which stay solid for picking.
 */
final class BoardElevator {
    /** The finish each face is drawn in. */
    enum Part { STEEL, HAZARD, CHROME, PIT, PAINT, IRON, PLATE }

    /** Metres the hazard-striped band along the platform's rim is wide. */
    private static final float BAND = 1.2f;

    /**
     * An elevator's platform and lift: its faces by finish, and where wear gathers on it, round its pins and joints and
     * low in its pit, not spread evenly over its paint.
     */
    record Lift(Map<Part, List<BoardSurface.Face>> parts, List<Vector3> joints, float pit, float metre) {
        /** How worn the finish at {@code p} is, 0 (clean paint) to 1: grime and rust round the joints and toward the pit floor. */
        float wear(Vector3 p) {
            if (joints.isEmpty()) { return 0; }
            float near = Float.MAX_VALUE;
            for (var joint : joints) { near = Math.min(near, joint.dst(p)); }
            float round = 1 - Math.clamp((near - .8f * metre) / (3.2f * metre), 0, 1);
            float low = 1 - Math.clamp((p.z - pit) / (5 * metre), 0, 1);
            return Math.max(round * round * .9f, low * .6f);
        }
    }

    private final Coords coords;
    private final Map<Part, List<BoardSurface.Face>> parts = new EnumMap<>(Part.class);
    private final List<Vector3> joints = new ArrayList<>();
    private float pit = Float.NaN;

    private BoardElevator(Coords coords) {
        this.coords = coords;
        for (Part part : Part.values()) { parts.put(part, new ArrayList<>()); }
    }

    /**
     * The platform at {@code top} and its lift over the ground beside it, down to which the hex's straight walls reach
     * ({@code walls}, each on its edge; those of a higher platform down to ground above {@code top} stand for none);
     * the platform's top alone when it is not raised.
     */
    static Lift build(BoardScene scene, Coords coords, float top, List<BoardSurface.Face> walls) {
        BoardElevator lift = new BoardElevator(coords);
        lift.top(top);
        float[] ground = new float[6];
        java.util.Arrays.fill(ground, Float.NaN);
        float bottom = top;
        for (var face : walls) {
            float low = lowest(face);
            if (low >= top) { continue; }
            bottom = Math.min(bottom, low);
            int edge = face.landEdge();
            if (edge >= 0 && edge < 6) { ground[edge] = Float.isNaN(ground[edge]) ? low : Math.min(ground[edge], low); }
        }
        // Even a platform barely risen stands on its lift: the deck and the pit's lining close its sides as it moves.
        if (top > bottom) { lift.build(scene, top, bottom, ground); }
        return new Lift(lift.parts, lift.joints, lift.pit, BoardRelief.metres(1));
    }

    private static float lowest(BoardSurface.Face face) { return Math.min(face.a().z, Math.min(face.b().z, face.c().z)); }

    /** The platform's top: diamond plate inside the hazard-striped band, each piece of which stands on its rim edge. */
    private void top(float top) {
        Vector3 centre = at(BoardGeometry.center(coords, 0), top);
        Vector3[] rim = new Vector3[6], inner = new Vector3[6];
        for (int k = 0; k < 6; k++) { rim[k] = at(BoardGeometry.corner(coords, 0, k), top); }
        float apothem = centre.dst(new Vector3(rim[0]).add(rim[1]).scl(.5f));
        for (int k = 0; k < 6; k++) { inner[k] = new Vector3(rim[k]).lerp(centre, BoardRelief.metres(BAND) / apothem); }
        for (int k = 0; k < 6; k++) {
            int next = (k + 1) % 6;
            upward(Part.PLATE, -1, centre, inner[k], inner[next]);
            upward(Part.HAZARD, k, rim[k], rim[next], inner[next]);
            upward(Part.HAZARD, k, rim[k], inner[next], inner[k]);
        }
    }

    /** A face of the platform's top, facing up; one of the band's carries its rim edge, along which its stripes run. */
    private void upward(Part part, int edge, Vector3 a, Vector3 b, Vector3 c) {
        boolean up = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).z >= 0;
        parts.get(part).add(new BoardSurface.Face(a, up ? b : c, up ? c : b, BoardSurface.Finish.TOP, edge));
    }

    /** {@code ground}: each edge's ground beside it, NaN where no wall comes down to lower ground there. */
    private void build(BoardScene scene, float top, float bottom, float[] ground) {
        float m = BoardRelief.metres(1);
        float deck = 1.1f * m, band = .45f * m, girder = .7f * m;
        Vector3 centre = BoardGeometry.center(coords, 0);
        Vector3[] rim = new Vector3[6];
        for (int k = 0; k < 6; k++) { rim[k] = BoardGeometry.corner(coords, 0, k); }
        // The deck: its rim striped below the platform's edge, steel below that, and its underside.
        for (int k = 0; k < 6; k++) {
            Vector3 a = rim[k], b = rim[(k + 1) % 6];
            quad(Part.HAZARD, at(centre, top - band / 2), at(a, top), at(b, top), at(b, top - band), at(a, top - band));
            quad(Part.STEEL, at(centre, top - deck), at(a, top - band), at(b, top - band), at(b, top - deck), at(a, top - deck));
            triangle(Part.STEEL, at(centre, top), at(centre, top - deck), at(b, top - deck), at(a, top - deck));
        }
        // Girders across the underside, through the middle.
        float apothem = centre.dst(at(rim[0], centre.z).add(at(rim[1], centre.z)).scl(.5f));
        for (int k = 0; k < 3; k++) {
            double angle = Math.toRadians(30 + 60 * k);
            Vector3 along = new Vector3((float) Math.cos(angle), (float) Math.sin(angle), 0);
            box(Part.STEEL, at(centre, top - deck - girder / 2), along, apothem * .94f, .25f * m, girder / 2);
        }
        float pit = bottom - 1.2f * m, curb = .3f * m, ceiling = top - deck - girder;
        this.pit = pit;
        // The machinery pit, sunk below the lowest ground beside it: a diamond-plate floor, its walls lined with diamond
        // plate up to each side's ground behind a hazard-striped curb. A side by another raised lift stops at the pit's
        // rim; a side with no lower ground beside it is closed up to the deck. The walls face into the pit.
        for (int k = 0; k < 6; k++) {
            Vector3 a = rim[k], b = rim[(k + 1) % 6], beyond = new Vector3(a).add(b).sub(centre);
            triangle(Part.PIT, at(centre, pit - m), at(centre, pit), at(a, pit), at(b, pit));
            var beside = scene.tile(coords.translated(BoardGeometry.edgeDirection(k)));
            boolean open = !Float.isNaN(ground[k]) || beside != null && BoardFeatures.shaft(beside);
            float edge = !Float.isNaN(ground[k]) ? ground[k] : open ? bottom : top - deck;
            float lining = open ? edge - curb : edge;
            quad(Part.PIT, at(beyond, (pit + lining) / 2), at(a, lining), at(b, lining), at(b, pit), at(a, pit));
            if (open) {
                quad(Part.HAZARD, at(beyond, edge - curb / 2), at(a, edge), at(b, edge), at(b, lining), at(a, lining));
            }
        }
        scissors(centre, rim, pit, ceiling, m);
    }

    /**
     * The scissor table: two scissor planes either side of the middle, along the hex's long axis, of safety-yellow
     * box-girder arms of one length (an outer and an inner arm in each plane, so they cross), on axles with hubs where they cross and where
     * the stages join, tied across by torsion tubes. So many stages that none opens past a steep angle; their height then
     * sets their width, as a scissor lift's does. On the pit floor and under the deck, one end of each plane is pinned
     * and the other rolls along a rail. In every stage a hydraulic cylinder outside each plane pushes from the stage's
     * bottom tube at the pinned end up to a tube across the arm that rises from the rolling end, steeper than that arm,
     * and so opens the table; the tubes they hang on reach out past the planes.
     */
    private void scissors(Vector3 centre, Vector3[] rim, float pit, float ceiling, float m) {
        float arm = 18 * m, span = ceiling - pit, plane = 7 * m;
        int stages = Math.max(1, (int) Math.ceil(span / (.75f * arm)));
        float rise = span / stages, reach = (float) Math.sqrt(Math.max(arm * arm - rise * rise, 0)) / 2;
        Vector3 along = new Vector3(rim[0]).sub(centre);
        along.z = 0;
        along.nor();
        Vector3 across = new Vector3(-along.y, along.x, 0);
        Vector3 left = new Vector3(centre).mulAdd(along, -reach), right = new Vector3(centre).mulAdd(along, reach);
        float[] sides = { -plane, plane };
        for (int stage = 0; stage < stages; stage++) {
            float z0 = pit + stage * rise, z1 = z0 + rise;
            for (float side : sides) {
                Vector3 outer = new Vector3(across).scl(side + Math.signum(side) * .7f * m);
                Vector3 inner = new Vector3(across).scl(side - Math.signum(side) * .7f * m);
                arm(at(left, z0).add(outer), at(right, z1).add(outer), across, m);
                arm(at(right, z0).add(inner), at(left, z1).add(inner), across, m);
                axle(at(centre, (z0 + z1) / 2), across, side, m);
            }
            tube(at(centre, (z0 + z1) / 2), across, plane, .45f * m);
        }
        for (int joint = 0; joint <= stages; joint++) {
            float z = pit + joint * rise;
            for (Vector3 end : new Vector3[] { left, right }) {
                for (float side : sides) { axle(at(end, z), across, side, m); }
                tube(at(end, z), across, plane, .5f * m);
            }
        }
        // Rails on the pit floor and under the deck; the rolling ends run along them.
        for (float z : new float[] { pit + .3f * m, ceiling - .3f * m }) {
            for (float side : sides) {
                box(Part.STEEL, at(centre, z).mulAdd(across, side), along, reach + 1.8f * m, 1.1f * m, .3f * m);
            }
        }
        // The cylinders: in every stage, from its bottom tube at the pinned end to a tube 60 % up the arm rising from the
        // rolling end, outside each plane; both tubes reach out to carry them.
        float lift = .6f, outboard = plane + 2.6f * m;
        Vector3 rod = new Vector3(right).lerp(left, lift);
        for (int stage = 0; stage < stages; stage++) {
            float z0 = pit + stage * rise, zRod = z0 + rise * lift;
            tube(at(left, z0), across, outboard + .9f * m, .45f * m);
            tube(at(rod, zRod), across, outboard + .9f * m, .45f * m);
            for (float side : sides) {
                Vector3 offset = new Vector3(across).scl(Math.signum(side) * outboard);
                Vector3 base = at(left, z0).add(offset), end = at(rod, zRod).add(offset);
                cylinder(base, end, across, m);
            }
        }
    }

    /** A scissor arm: a painted box girder, in four lengths so that wear can gather at its ends and where it crosses. */
    private void arm(Vector3 a, Vector3 b, Vector3 across, float m) {
        for (int k = 0; k < 4; k++) {
            beam(Part.PAINT, new Vector3(a).lerp(b, k / 4f), new Vector3(a).lerp(b, (k + 1) / 4f), across, 1.0f * m, 1.6f * m);
        }
    }

    /**
     * A hydraulic cylinder from {@code base} to {@code end}: a painted barrel with a steel gland at its mouth, a chrome
     * rod, a cast clevis at either end on its tube, and a hose along the barrel.
     */
    private void cylinder(Vector3 base, Vector3 end, Vector3 across, float m) {
        Vector3 axis = new Vector3(end).sub(base).nor();
        Vector3 barrel = new Vector3(base).lerp(end, .58f);
        joints.add(base);
        joints.add(end);
        bar(Part.PAINT, new Vector3(base).mulAdd(axis, .6f * m), barrel, .8f * m, 16);
        bar(Part.STEEL, new Vector3(barrel).mulAdd(axis, -.5f * m), barrel, .95f * m, 16);
        bar(Part.CHROME, barrel, new Vector3(end).mulAdd(axis, -.6f * m), .42f * m, 14);
        for (Vector3 at : new Vector3[] { base, end }) {
            Vector3 inward = new Vector3(axis).scl(at == base ? 1 : -1);
            beam(Part.IRON, new Vector3(at).mulAdd(inward, -.4f * m), new Vector3(at).mulAdd(inward, .9f * m), across,
                  1.2f * m, .9f * m);
        }
        Vector3 out = new Vector3(axis).crs(across).nor();
        if (out.z < 0) { out.scl(-1); }
        bar(Part.STEEL, new Vector3(base).mulAdd(axis, .9f * m).mulAdd(out, .95f * m),
              new Vector3(barrel).mulAdd(axis, -.7f * m).mulAdd(out, .95f * m), .14f * m, 6);
    }

    /** An axle through a scissor plane's two arms at {@code side} across the middle, with a cast hub either side. */
    private void axle(Vector3 point, Vector3 across, float side, float m) {
        Vector3 middle = new Vector3(point).mulAdd(across, side);
        joints.add(middle);
        bar(Part.CHROME, new Vector3(middle).mulAdd(across, -1.6f * m), new Vector3(middle).mulAdd(across, 1.6f * m), .35f * m, 12);
        for (float face : new float[] { -1.3f, 1.3f }) {
            bar(Part.IRON, new Vector3(middle).mulAdd(across, face * m), new Vector3(middle).mulAdd(across, face * m * 1.15f),
                  .9f * m, 16);
        }
    }

    /** A torsion tube tying the two scissor planes together. */
    private void tube(Vector3 point, Vector3 across, float plane, float radius) {
        bar(Part.STEEL, new Vector3(point).mulAdd(across, -plane), new Vector3(point).mulAdd(across, plane), radius, 12);
    }

    /** A straight beam from {@code a} to {@code b}: {@code width} across along {@code side}, {@code depth} the other way. */
    private void beam(Part part, Vector3 a, Vector3 b, Vector3 side, float width, float depth) {
        Vector3 along = new Vector3(b).sub(a).nor();
        Vector3 s = new Vector3(side).nor().scl(width / 2), t = new Vector3(along).crs(side).nor().scl(depth / 2);
        Vector3 middle = new Vector3(a).add(b).scl(.5f);
        Vector3[] p = new Vector3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = new Vector3((i & 4) == 0 ? a : b).mulAdd(s, (i & 1) == 0 ? -1 : 1).mulAdd(t, (i & 2) == 0 ? -1 : 1);
        }
        int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
        for (int[] f : faces) { quad(part, middle, p[f[0]], p[f[1]], p[f[2]], p[f[3]]); }
    }

    /** A round bar from {@code a} to {@code b}, capped. */
    private void bar(Part part, Vector3 a, Vector3 b, float radius, int segments) {
        Vector3 axis = new Vector3(b).sub(a).nor();
        Vector3 u = new Vector3(axis).crs(Math.abs(axis.z) < .9f ? new Vector3(0, 0, 1) : new Vector3(1, 0, 0)).nor();
        Vector3 v = new Vector3(axis).crs(u).nor();
        Vector3 middle = new Vector3(a).add(b).scl(.5f);
        for (int s = 0; s < segments; s++) {
            double a0 = 2 * Math.PI * s / segments, a1 = 2 * Math.PI * (s + 1) / segments;
            Vector3 r0 = new Vector3(u).scl((float) Math.cos(a0) * radius).mulAdd(v, (float) Math.sin(a0) * radius);
            Vector3 r1 = new Vector3(u).scl((float) Math.cos(a1) * radius).mulAdd(v, (float) Math.sin(a1) * radius);
            quad(part, middle, new Vector3(a).add(r0), new Vector3(a).add(r1), new Vector3(b).add(r1), new Vector3(b).add(r0));
            triangle(part, middle, new Vector3(a), new Vector3(a).add(r0), new Vector3(a).add(r1));
            triangle(part, middle, new Vector3(b), new Vector3(b).add(r0), new Vector3(b).add(r1));
        }
    }

    private static Vector3 at(Vector3 point, float z) { return new Vector3(point.x, point.y, z); }

    /** A face turned away from {@code inside}, a point within the solid it bounds. */
    private void triangle(Part part, Vector3 inside, Vector3 a, Vector3 b, Vector3 c) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a));
        boolean outward = normal.dot(new Vector3(a).add(b).add(c).scl(1 / 3f).sub(inside)) >= 0;
        parts.get(part).add(new BoardSurface.Face(a, outward ? b : c, outward ? c : b, BoardSurface.Finish.WALL));
    }

    /** a, b, c, d around a planar quad, turned away from {@code inside}. */
    private void quad(Part part, Vector3 inside, Vector3 a, Vector3 b, Vector3 c, Vector3 d) {
        triangle(part, inside, a, b, c);
        triangle(part, inside, a, c, d);
    }

    /** A box about {@code centre}: half-extents along {@code along}, across it, and up. */
    private void box(Part part, Vector3 centre, Vector3 along, float halfAlong, float halfAcross, float halfUp) {
        Vector3 u = new Vector3(along).nor().scl(halfAlong);
        Vector3 v = new Vector3(-along.y, along.x, 0).nor().scl(halfAcross);
        Vector3 w = new Vector3(0, 0, halfUp);
        Vector3[] p = new Vector3[8];
        for (int i = 0; i < 8; i++) {
            p[i] = new Vector3(centre).mulAdd(u, (i & 1) == 0 ? -1 : 1).mulAdd(v, (i & 2) == 0 ? -1 : 1)
                  .mulAdd(w, (i & 4) == 0 ? -1 : 1);
        }
        int[][] faces = { { 0, 2, 3, 1 }, { 4, 5, 7, 6 }, { 0, 1, 5, 4 }, { 2, 6, 7, 3 }, { 0, 4, 6, 2 }, { 1, 3, 7, 5 } };
        for (int[] f : faces) { quad(part, centre, p[f[0]], p[f[1]], p[f[2]], p[f[3]]); }
    }
}
