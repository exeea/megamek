/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Polygon;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;

/** Fits cosmetic member positions against the board's hex edges, leaving mesh size and heading intact. */
final class InfantryFootprint {
    private static final Vector3[] SIDES = sides();
    private static final float EDGE_MARGIN = .5f;
    /** Whether members that overlap as authored spread the layout outwards until they clear, rather than stay put. */
    static final boolean SPREAD_OVERLAPS = false;
    /** The room steps take from each side of a hex that is level ground to its edges. */
    static final float[] NO_STEPS = new float[6];

    record Layout(float scale, Vector3 offset) {
        Vector3 place(Vector3 position) { return new Vector3(position).scl(scale, scale, 1).add(offset); }
    }

    private InfantryFootprint() { }

    private static Vector3[] sides() {
        var result = new Vector3[6];
        var hex = new Coords(0, 0);
        var center = BoardGeometry.center(hex, 0);
        for (int i = 0; i < result.length; i++) {
            var a = BoardGeometry.corner(hex, 0, i).sub(center);
            var edge = BoardGeometry.corner(hex, 0, i + 1).sub(center).sub(a);
            var normal = new Vector3(edge.y, -edge.x, 0).nor();
            result[i] = new Vector3(normal.x, normal.y, normal.dot(a) / BoardGeometry.HEX_SCALE);
        }
        return result;
    }

    /**
     * A member's outline to fit. One that turns in place, as a watching trooper does, is fitted by the octagon around
     * every heading of its footprint, so no turn carries it past an edge; any other keeps its footprint at its heading.
     */
    static Polygon outline(BoundingBox bounds, float heading, boolean turns) {
        if (!turns) { return polygon(bounds, heading); }
        float reach = 0;
        for (float x : new float[] { bounds.min.x, bounds.max.x }) {
            for (float y : new float[] { bounds.min.y, bounds.max.y }) {
                reach = Math.max(reach, (float) Math.hypot(x, y));
            }
        }
        float corner = reach / MathUtils.cosDeg(22.5f);
        float[] vertices = new float[16];
        for (int i = 0; i < 8; i++) {
            vertices[2 * i] = corner * MathUtils.cosDeg(22.5f + 45 * i);
            vertices[2 * i + 1] = corner * MathUtils.sinDeg(22.5f + 45 * i);
        }
        return new Polygon(vertices);
    }

    /**
     * How far towards each hex side a member may stand, in the formation's own units: to the side, less a margin and
     * the room, in world units, that a step takes on this hex's side of that edge.
     */
    private static float[] limits(Polygon outline, float scale, float[] room) {
        float[] vertices = outline.getVertices();
        float cosine = MathUtils.cosDeg(outline.getRotation()), sine = MathUtils.sinDeg(outline.getRotation());
        float[] limits = new float[SIDES.length];
        for (int i = 0; i < SIDES.length; i++) {
            var side = SIDES[i];
            float extent = -Float.MAX_VALUE;
            for (int v = 0; v < vertices.length; v += 2) {
                float x = vertices[v] * cosine - vertices[v + 1] * sine;
                float y = vertices[v] * sine + vertices[v + 1] * cosine;
                extent = Math.max(extent, side.x * x + side.y * y);
            }
            limits[i] = ((side.z - EDGE_MARGIN) * BoardGeometry.HEX_SCALE - room[i]) / scale - extent;
        }
        return limits;
    }

    /**
     * Moves a member to the nearest spot on the hex's level ground, clear of the room its steps take. A member larger
     * than that ground stands centred where it overflows, as far inside as it can.
     *
     * @return whether the member now stands wholly inside
     */
    static boolean fit(Vector3 position, Polygon outline, float scale, float[] room) {
        return fit(position, limits(outline, scale, room));
    }

    private static boolean fit(Vector3 position, float[] limits) {
        boolean fits = true;
        for (int i = 0; i < SIDES.length / 2; i++) {
            // Opposite sides share an axis; where the member is wider than the ground, it centres between them.
            float slack = limits[i] + limits[i + SIDES.length / 2];
            if (slack < 0) {
                limits[i] -= slack / 2;
                limits[i + SIDES.length / 2] -= slack / 2;
                fits = false;
            }
        }
        // Alternating projections find a nearby position inside all six inset edges. Z is untouched.
        for (int pass = 0; pass < 12; pass++) {
            float correction = 0;
            for (int i = 0; i < SIDES.length; i++) {
                var side = SIDES[i];
                float excess = Math.max(0, side.x * position.x + side.y * position.y - limits[i]);
                position.add(-side.x * excess, -side.y * excess, 0);
                correction = Math.max(correction, excess);
            }
            if (correction < .0001f) { return fits; }
        }
        return false;
    }

    /** Draw the layout in, then move it as a whole into the available ground when a bank cuts into just one side. */
    static Layout layout(List<Vector3> positions, List<Polygon> outlines, float scale, float[] room) {
        float compression = compress(positions, outlines, scale, room);
        float[] limits = new float[SIDES.length];
        Arrays.fill(limits, Float.POSITIVE_INFINITY);
        for (int member = 0; member < positions.size(); member++) {
            float[] memberLimits = limits(outlines.get(member), scale, room);
            Vector3 position = positions.get(member);
            for (int side = 0; side < SIDES.length; side++) {
                float reach = SIDES[side].x * position.x + SIDES[side].y * position.y;
                limits[side] = Math.min(limits[side], memberLimits[side] - compression * reach);
            }
        }
        Vector3 offset = new Vector3();
        fit(offset, limits);
        return new Layout(compression, offset);
    }

    /**
     * How far to draw a formation's layout in toward the hex centre, as one shape, so that every member stands on the
     * hex's level ground, clear of the room its steps take, rather than pressing its outer members against the edges.
     * Members keep their size and never come to overlap: a crowd too large for that ground packs as tightly as that
     * allows, and only the rest overflows.
     *
     * @return the scale for the layout's positions: 1 as authored, less to draw it in, and more only to part members
     *       that overlap as authored, with {@link #SPREAD_OVERLAPS}
     */
    static float compress(List<Vector3> positions, List<Polygon> outlines, float scale, float[] room) {
        // Each side's limit is linear in the scale: members out towards a side bound it from above.
        float inside = 1, outside = 0;
        boolean fits = true;
        for (int i = 0; i < positions.size(); i++) {
            float[] limits = limits(outlines.get(i), scale, room);
            for (int side = 0; side < SIDES.length; side++) {
                float reach = SIDES[side].x * positions.get(i).x + SIDES[side].y * positions.get(i).y;
                if (reach > 0) {
                    inside = Math.min(inside, limits[side] / reach);
                } else if (limits[side] < 0) {
                    // Wider than the hex this way: only a member placed out towards the far side can still fit.
                    if (reach < 0) { outside = Math.max(outside, limits[side] / reach); } else { fits = false; }
                }
            }
        }
        float apart = apart(positions, outlines);
        return fits && inside >= outside ? Math.max(inside, apart) : apart;
    }

    /** The tightest scale at which no two members overlap. */
    private static float apart(List<Vector3> positions, List<Polygon> outlines) {
        float apart = 0;
        for (int i = 0; i < outlines.size(); i++) {
            for (int j = i + 1; j < outlines.size(); j++) {
                apart = Math.max(apart, apart(outlines.get(i), positions.get(i), outlines.get(j), positions.get(j)));
            }
        }
        return apart;
    }

    /** The tightest scale at which one pair stands clear. A pair only comes closer as its layout draws in. */
    private static float apart(Polygon a, Vector3 atA, Polygon b, Vector3 atB) {
        float overlapping = 0, clear = 1;
        for (int spread = 0; SPREAD_OVERLAPS && spread < 8 && overlap(a, atA, b, atB, clear); spread++) {
            overlapping = clear;
            clear *= 2;
        }
        // A pair that overlaps as authored stays so: drawing the layout in cannot part it.
        if (overlap(a, atA, b, atB, clear)) { return 0; }
        for (int step = 0; step < 16; step++) {
            float middle = (overlapping + clear) / 2;
            if (overlap(a, atA, b, atB, middle)) { overlapping = middle; } else { clear = middle; }
        }
        return clear;
    }

    private static boolean overlap(Polygon a, Vector3 atA, Polygon b, Vector3 atB, float scale) {
        a.setPosition(atA.x * scale, atA.y * scale);
        b.setPosition(atB.x * scale, atB.y * scale);
        return Intersector.overlapConvexPolygons(a, b);
    }

    /** Steps a member clear of the vehicles, on the hex's level ground while it has room and just beyond it if not. */
    static void avoid(Vector3 position, Polygon outline, List<Polygon> obstacles, float scale, float[] room) {
        for (int pass = 0; pass < 12; pass++) {
            if (!separate(position, outline, obstacles, scale)) { return; }
            fit(position, outline, scale, room);
        }
        // Preserve the requested size: with no room left inside, stand clear right where the ground ran out.
        for (int pass = 0; pass < 12 && separate(position, outline, obstacles, scale); pass++) { }
    }

    /** Seek clear footing nearby, staying inside the plateau. If crowded, retain the original spot on the rocks. */
    static boolean avoidRough(Vector3 position, Polygon outline, List<Polygon> obstacles, float scale, float[] room) {
        Vector3 original = new Vector3(position);
        float[] limits = limits(outline, scale, room);
        for (int attempt = 0; attempt < 49; attempt++) {
            position.set(original);
            if (attempt > 0) {
                float radius = BoardGeometry.WIDTH * .22f / scale * (float) Math.sqrt(attempt / 48f);
                float angle = attempt * 2.399963f;
                position.add(MathUtils.cos(angle) * radius, MathUtils.sin(angle) * radius, 0);
            }
            if (!fit(position, limits.clone())) { continue; }
            // A few projections find a close gap; the spiral also tries the other side of a crowded cluster.
            for (int pass = 0; pass < 6; pass++) {
                if (!separate(position, outline, obstacles, scale)) { return true; }
                if (!fit(position, limits.clone())) { break; }
            }
        }
        position.set(original);
        return false;
    }

    private static boolean separate(Vector3 position, Polygon shape, List<Polygon> obstacles, float scale) {
        boolean moved = false;
        var separation = new Intersector.MinimumTranslationVector();
        for (var obstacle : obstacles) {
            shape.setPosition(position.x, position.y);
            if (Intersector.overlapConvexPolygons(shape, obstacle, separation)) {
                float distance = separation.depth + EDGE_MARGIN * BoardGeometry.HEX_SCALE / scale;
                position.add(separation.normal.x * distance, separation.normal.y * distance, 0);
                moved = true;
            }
        }
        return moved;
    }

    static Polygon polygon(BoundingBox bounds, float heading) {
        var result = new Polygon(new float[] { bounds.min.x, bounds.min.y, bounds.max.x, bounds.min.y,
              bounds.max.x, bounds.max.y, bounds.min.x, bounds.max.y });
        result.setRotation(-heading);
        return result;
    }
}
