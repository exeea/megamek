/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.math.Vector3;

/** Grade the generated slab and rails together; the installed facets serve drawing, road paint and picking. */
final class BoardBridgeSlope {
    /** Rows across each half of an eased approach, so the mesh follows its curve. */
    static final int EASED_ROWS = 8;

    private BoardBridgeSlope() { }

    /**
     * Share of its rise an eased approach has made at the share u of its run from a hex's level inset to its edge. The
     * deck's half and the bank's mirror each other about their edge: the whole approach is one ease, level at the deck
     * and on the bank, with one slope at the edge.
     */
    static float ease(float u) { return u * u * (3 - u) / 2; }

    /**
     * How far, in tile pixels, a vertical face is clipped behind its facing (see {@link #build}): a few times the grade
     * patches' own containment tolerance, so the face lies clearly inside one of them.
     */
    private static final float NUDGE = .004f;

    /** The rise the {@code eased} exits' solid ramps drop by to their edges, the same for all of them; 0 for none. */
    static float easedRise(BoardBridge.Deck deck, int eased) {
        return eased == 0 ? 0 : deck.rises().get(Integer.numberOfTrailingZeros(eased)) * BoardGeometry.level();
    }

    /** A height of the planar grade above the deck, eased where its approaches drop by {@code rise} ({@link #ease}). */
    static float eased(float height, float rise) {
        float u = height / rise;
        return u > 0 && u < 1 ? rise * ease(u) : height;
    }

    /** Install one generated deck, including its actual end or joint trusses, onto the shared grade profile. */
    static BoardBridge.Shape build(BoardScene.Tile tile, BoardBridge.Deck deck, BoardBridgeFooting footing) {
        var profile = deck.sloped() || footing.solidExits() != 0 ? profile(tile, deck) : List.<BoardSurface.Face>of();
        float rise = easedRise(deck, footing.easedExits());
        var faces = new ArrayList<>(footing.shape().facets());
        var clipper = new BoardTacticalGeometry.Clipper();
        float level = tile.elevation() + BoardBridge.feature(tile).elevation();
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardBridge.deckZ(level);
        for (var face : BoardBridgeMesh.build(tile, deck)) {
            var a = new Vector3(face.a()).scl(scale).add(center);
            var b = new Vector3(face.b()).scl(scale).add(center);
            var c = new Vector3(face.c()).scl(scale).add(center);
            if (profile.isEmpty()) {
                BoardBridge.triangle(faces, a, b, c, face.part(), face.colour());
                continue;
            }
            boolean slab = Math.max(face.a().z, Math.max(face.b().z, face.c().z)) <= .001f;
            boolean vertical = Math.abs(face.normal().z) < .0001f;
            // A vertical face along a grade patch's edge lies in neither patch by plan, and clipped in place both cut it
            // to slivers. It is clipped a hair behind its facing, inside one patch, and set back; both give one height.
            float nudge = vertical ? NUDGE * scale / (float) Math.hypot(face.normal().x, face.normal().y) : 0;
            float backX = face.normal().x * nudge, backY = face.normal().y * nudge;
            var polygon = List.of(new Vector3(a).sub(backX, backY, 0), new Vector3(b).sub(backX, backY, 0),
                  new Vector3(c).sub(backX, backY, 0));
            for (var patch : profile) {
                int direction = patch.landEdge() < 0 ? -1 : BoardGeometry.edgeDirection(patch.landEdge());
                boolean solid = direction >= 0 && (footing.solidExits() & (1 << direction)) != 0;
                // The solid ramp owns its top and sides. Retain only the raised rails above that block.
                if (slab && solid) { continue; }
                var exposed = polygon;
                if (face.part() == BoardBridge.Part.STEEL && direction >= 0
                      && (solid || footing.lengths().get(direction) > 0 && deck.rises().get(direction) > 0)) {
                    // Fascia triangles cross the support's top: dropping only whole slab triangles leaves
                    // coplanar steel over concrete. Cut before grading so both meet on the same slope.
                    exposed = above(polygon, center.z - (solid ? 0 : BoardBridge.SLAB * scale));
                }
                for (int i = 1; i + 1 < exposed.size(); i++) {
                    clipper.prepare(new BoardTacticalGeometry.Triangle(exposed.getFirst(), exposed.get(i), exposed.get(i + 1), -1));
                    clipper.displace(patch, triangle -> {
                        // A vertical face has no XY area, so both patches beside a shared edge can contain it.
                        // The solid support owns that boundary; its neighbour must not restore buried fascia.
                        if (vertical && !ownsBoundary(profile, patch, footing.solidExits(),
                              triangle)) { return; }
                        ease(triangle, patch, rise, eased -> BoardBridge.triangle(faces,
                              new Vector3(eased.a()).add(backX, backY, 0), new Vector3(eased.b()).add(backX, backY, 0),
                              new Vector3(eased.c()).add(backX, backY, 0), face.part(), face.colour()));
                    });
                }
            }
        }
        return BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces);
    }

    private static boolean ownsBoundary(List<BoardSurface.Face> profile, BoardSurface.Face patch, int solidExits,
          BoardTacticalGeometry.Triangle triangle) {
        if (patch.landEdge() >= 0 && (solidExits & 1 << BoardGeometry.edgeDirection(patch.landEdge())) != 0) {
            return true;
        }
        for (var candidate : profile) {
            if (candidate.landEdge() < 0 || (solidExits & 1 << BoardGeometry.edgeDirection(candidate.landEdge())) == 0) {
                continue;
            }
            // Only a solid support takes ownership of a boundary face. Choosing between its own coplanar
            // triangles can discard the sole emitted fascia when clipping rounds a diagonal edge differently.
            if (List.of(triangle.a(), triangle.b(), triangle.c()).stream()
                  .allMatch(point -> Float.isFinite(candidate.height(point.x, point.y)))) { return false; }
        }
        return true;
    }

    /** Clip a facet at the concrete support's top, retaining winding and the exposed steel above it. */
    private static List<Vector3> above(List<Vector3> polygon, float height) {
        if (polygon.stream().allMatch(p -> p.z >= height)) { return polygon; }
        var result = new ArrayList<Vector3>(4);
        var previous = polygon.getLast();
        for (var point : polygon) {
            if ((previous.z >= height) != (point.z >= height)) {
                var cut = new Vector3(previous).lerp(point, (height - previous.z) / (point.z - previous.z));
                cut.z = height;
                result.add(cut);
            }
            if (point.z >= height) { result.add(point); }
            previous = point;
        }
        return result;
    }

    /**
     * A level central hub and broad planar approaches. Adjacent mouths blend through their shared corner height;
     * inactive sides retain the full deck overhang. The slab and pier caps use this same grade (world XY), eased where
     * a deck's solid ramps ease (see {@link #ease}).
     */
    static List<BoardSurface.Face> profile(BoardScene.Tile tile, BoardBridge.Deck deck) {
        var result = new ArrayList<BoardSurface.Face>();
        var center = BoardGeometry.center(tile.coords(), 0);
        var corners = new Vector3[6];
        var original = new Vector3[6];
        var inner = new Vector3[6];
        for (int edge = 0; edge < 6; edge++) {
            original[edge] = BoardGeometry.corner(tile.coords(), 0, edge);
            corners[edge] = new Vector3(original[edge]);
            inner[edge] = new Vector3(center).lerp(corners[edge], .5f);
        }
        float shoulder = BoardBridge.SHOULDER * BoardGeometry.hexScale();
        for (int edge = 0; edge < 6; edge++) {
            int direction = BoardGeometry.edgeDirection(edge), next = (edge + 1) % 6;
            if ((deck.exits() & 1 << direction) == 0) { continue; }
            float wide = mouthWidth(deck, direction), half = original[edge].dst(original[next]) / 2;
            var across = new Vector3(original[next]).sub(original[edge]).nor();
            var midpoint = new Vector3(original[edge]).lerp(original[next], .5f);
            for (int end = 0; end < 2; end++) {
                int corner = end == 0 ? edge : next, neighbor = end == 0 ? (edge + 5) % 6 : next;
                float sign = end == 0 ? -1 : 1;
                if (!sharedCorner(deck, corner, BoardGeometry.edgeDirection(neighbor), direction)) {
                    inner[corner] = new Vector3(midpoint).lerp(center, .5f).mulAdd(across, sign * wide);
                    if (wide > half) { corners[corner] = new Vector3(midpoint).mulAdd(across, sign * wide); }
                }
            }
        }
        for (int corner = 0; corner < 6; corner++) {
            corners[corner].z = deck.cornerRises().isEmpty() ? 0 : deck.cornerRises().get(corner) * BoardGeometry.level();
        }
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6, direction = BoardGeometry.edgeDirection(edge);
            var a = corners[edge];
            var b = corners[next];
            var innerA = inner[edge];
            var innerB = inner[next];
            Vector3 mouthA = a, mouthB = b;
            if ((deck.exits() & 1 << direction) != 0) {
                float length = original[edge].dst(original[next]), inset = .5f - mouthWidth(deck, direction) / length;
                boolean sharedA = sharedCorner(deck, edge, BoardGeometry.edgeDirection((edge + 5) % 6), direction);
                boolean sharedB = sharedCorner(deck, next, BoardGeometry.edgeDirection(next), direction);
                // Adjacent graded mouths meet one shared corner; their outer shoulders blend into its common height.
                mouthA = new Vector3(original[edge]).lerp(original[next], sharedA ? Math.max(inset, shoulder / length) : inset);
                mouthB = new Vector3(original[edge]).lerp(original[next], 1 - (sharedB ? Math.max(inset, shoulder / length) : inset));
                mouthA.z = mouthB.z = deck.rises().get(direction) * BoardGeometry.level();
                if (!sharedA && inset <= 0) { a.z = mouthA.z; }
                if (!sharedB && inset <= 0) { b.z = mouthB.z; }
            }
            result.add(face(center, innerA, innerB, -1));
            if ((deck.exits() & 1 << direction) == 0) {
                int first = result.size();
                // A widened neighbouring mouth can make this side's skirt concave. Choose its interior diagonal;
                // fanning from innerA would otherwise fold the last triangle back over the level centre.
                if (cross(innerA, a, b) * cross(innerA, b, innerB) < 0) {
                    result.add(face(innerA, a, innerB, edge));
                    result.add(face(a, b, innerB, edge));
                } else {
                    result.add(face(innerA, a, b, edge));
                    result.add(face(innerA, b, innerB, edge));
                }
                extendApproach(result, first, deck, edge, original, center);
                continue;
            }
            result.add(face(innerA, a, mouthA, edge));
            result.add(face(innerA, mouthA, mouthB, edge));
            result.add(face(innerA, mouthB, innerB, edge));
            result.add(face(innerB, mouthB, b, edge));
        }
        return result;
    }

    /**
     * Passes on a triangle displaced onto {@code patch} of the planar grade, eased where its approaches drop by
     * {@code rise} (none at 0): cut along the patch's rows of equal height, each point raised by the difference between
     * the eased and the planar grade there, so whatever lies on the grade keeps its thickness.
     */
    static void ease(BoardTacticalGeometry.Triangle triangle, BoardSurface.Face patch, float rise,
          Consumer<BoardTacticalGeometry.Triangle> destination) {
        var a = patch.a();
        var b = patch.b();
        var c = patch.c();
        if (rise == 0 || Math.min(a.z, Math.min(b.z, c.z)) >= Math.max(0, rise)
              || Math.max(a.z, Math.max(b.z, c.z)) <= Math.min(0, rise)) {
            destination.accept(triangle);
            return;
        }
        float bx = b.x - a.x, by = b.y - a.y, cx = c.x - a.x, cy = c.y - a.y, area = bx * cy - by * cx;
        // The patch's height gradient in XY.
        float gx = ((b.z - a.z) * cy - (c.z - a.z) * by) / area, gy = ((c.z - a.z) * bx - (b.z - a.z) * cx) / area;
        float slope = (float) Math.hypot(gx, gy);
        List<Vector3> polygon = List.of(triangle.a(), triangle.b(), triangle.c());
        var pieces = List.of(polygon);
        if (slope > 1e-6f) {
            var axis = new Vector3(gx / slope, gy / slope, 0);
            // Lines of equal height, a row apart from the level deck.
            pieces = BoardBridgeFooting.slice(polygon, new Vector3(a).mulAdd(axis, -a.z / slope), axis,
                  Math.abs(rise) / EASED_ROWS / slope);
        }
        for (var piece : pieces) {
            var points = piece.stream().map(p -> {
                float height = a.z + gx * (p.x - a.x) + gy * (p.y - a.y);
                return new Vector3(p.x, p.y, p.z + eased(height, rise) - height);
            }).toList();
            for (int i = 2; i < points.size(); i++) {
                var u = new Vector3(points.get(i - 1)).sub(points.getFirst());
                var v = new Vector3(points.get(i)).sub(points.getFirst());
                // Cutting can leave nearly collinear slivers; their unstable plane can give a distant picking hit.
                if (u.crs(v).len2() > 1e-10f * u.len2() * v.len2()) {
                    destination.accept(new BoardTacticalGeometry.Triangle(points.getFirst(), points.get(i - 1),
                          points.get(i), triangle.argb()));
                }
            }
        }
    }

    /**
     * The edge pq of the planar grade (heights above the deck), eased where its approaches drop by {@code rise} (none
     * at 0): cut where it crosses the rows, in order from p.
     */
    static List<Vector3> easeEdge(Vector3 p, Vector3 q, float rise) {
        if (rise == 0) { return List.of(p, q); }
        List<Vector3> result = new ArrayList<>(List.of(new Vector3(p.x, p.y, eased(p.z, rise))));
        float step = Math.abs(rise) / EASED_ROWS;
        for (int k = 1; k < EASED_ROWS; k++) {
            float height = Math.min(0, rise) + (q.z > p.z ? k : EASED_ROWS - k) * step;
            if ((p.z - height) * (q.z - height) < 0) {
                var cut = new Vector3(p).lerp(q, (height - p.z) / (q.z - p.z));
                cut.z = eased(height, rise);
                result.add(cut);
            }
        }
        result.add(new Vector3(q.x, q.y, eased(q.z, rise)));
        return result;
    }

    /** Continue one approach's grade around its curved parapet, then meet the level junction without a twist. */
    private static void extendApproach(List<BoardSurface.Face> result, int first, BoardBridge.Deck deck, int edge,
          Vector3[] original, Vector3 center) {
        int approach = -1;
        for (int nearby : new int[] { (edge + 5) % 6, (edge + 1) % 6 }) {
            int direction = BoardGeometry.edgeDirection(nearby);
            if ((deck.exits() & 1 << direction) == 0 || deck.rises().get(direction) == 0) { continue; }
            if (approach >= 0) { return; }
            approach = nearby;
        }
        if (approach < 0) { return; }
        var a = new Vector3(center).lerp(original[approach], .5f);
        var b = new Vector3(center).lerp(original[(approach + 1) % 6], .5f);
        var normal = new Vector3(-(b.y - a.y), b.x - a.x, 0);
        float span = new Vector3(center).sub(a).dot(normal);
        float rise = deck.rises().get(BoardGeometry.edgeDirection(approach)) * BoardGeometry.level();
        var source = new ArrayList<>(result.subList(first, result.size()));
        // Keep the existing blend where another mouth or shared corner requires a different height.
        for (var face : source) {
            for (var point : List.of(face.a(), face.b(), face.c())) {
                float height = rise * Math.max(0, -new Vector3(point).sub(a).dot(normal) / span);
                if (Math.abs(point.z - height) > .001f) { return; }
            }
        }
        result.subList(first, result.size()).clear();
        for (var face : source) {
            var triangle = List.of(face.a(), face.b(), face.c());
            for (boolean sloped : new boolean[] { false, true }) {
                var polygon = BoardTacticalGeometry.clip(triangle, sloped ? b : a, sloped ? a : b);
                var graded = new ArrayList<Vector3>();
                for (var point : polygon) {
                    var p = new Vector3(point);
                    p.z = sloped ? rise * Math.max(0, -new Vector3(p).sub(a).dot(normal) / span) : 0;
                    graded.add(p);
                }
                for (int i = 1; i + 1 < graded.size(); i++) {
                    result.add(face(graded.getFirst(), graded.get(i), graded.get(i + 1), edge));
                }
            }
        }
    }

    private static float mouthWidth(BoardBridge.Deck deck, int direction) {
        float width = ((deck.corridor().sides() & 1 << direction) == 0 ? BoardRoad.Kind.PAVED.halfWidth : BoardRoad.CORRIDOR_HALF)
              + BoardBridge.SHOULDER;
        int edge = Math.floorMod(1 - direction, 6);
        var across = BoardGeometry.modelCorner(edge + 1).sub(BoardGeometry.modelCorner(edge)).nor();
        var axis = BoardGeometry.modelCorner(edge).add(BoardGeometry.modelCorner(edge + 1)).nor();
        // The lattice edge is slightly oblique to its center line. Match the actual full-width slab and footing,
        // including the thin outer strip which otherwise falls into the neighboring grade patch.
        return width * BoardGeometry.hexScale() / Math.abs(across.x * axis.y - across.y * axis.x);
    }

    private static boolean sharedCorner(BoardBridge.Deck deck, int corner, int otherDirection, int direction) {
        return (deck.exits() & 1 << otherDirection) != 0 || !deck.cornerRises().isEmpty()
              && Math.abs(deck.cornerRises().get(corner) - deck.rises().get(direction)) >= .0001f;
    }

    private static BoardSurface.Face face(Vector3 a, Vector3 b, Vector3 c, int edge) {
        return new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP, edge);
    }

    private static float cross(Vector3 a, Vector3 b, Vector3 c) {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
    }
}
