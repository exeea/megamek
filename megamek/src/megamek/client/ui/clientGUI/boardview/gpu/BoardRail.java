/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;

/**
 * The one route builder of both kinds ({@link RailRoute}): small texture-free meshes, generated only on board edits and
 * shared by identical layouts. A marker's drawn sides ({@link RailRoute#drawnSides}) lead to its hex edges; a side whose
 * neighbour's marker of the same kind leaves back joins it, any other side ends open at its edge. Its pieces
 * ({@link RailRoute#pieces(megamek.common.board.BoardDecoration, int)}) are straights and 60-degree arcs between the
 * midpoints of those edges, each leaving its edge squarely along the line between the hex centres, so any piece
 * continues any piece of the neighbour; a stranded side ends in a short stub, a single side runs to the centre, a marker
 * without sides draws a short N-S stub. MAGLEV is the elevated beam on the markers' fixed levels, eased between them;
 * RAIL is ballast, sleepers and two rails at real scale draped on what carries it: the ground the road engine shapes for
 * it as for a road ({@link BoardScene.Tile#wayExits}), or its bridge's drawn deck ({@link BoardBridge#drawnTop}). On a
 * drawn road only its rails show, set into the road. Rail ends close with buffer stops, which are objects of their own
 * ({@link RailRoute#stops}).
 */
final class BoardRail {
    /** The rail profile, model px (2.8 px = 1 m): ballast bed, sleepers on it, rails on them (top at RAIL_TOP). */
    private static final float BALLAST_TOP = .6f, BALLAST_HALF = 4.4f, BALLAST_FOOT = 5.6f, BALLAST_SINK = -.3f;
    private static final float SLEEPER_HALF = 3.8f, SLEEPER_HALF_LENGTH = .4f, SLEEPER_TOP = .9f, SLEEPER_PITCH = 3;
    private static final float GAUGE_HALF = 2.1f, RAIL_HALF = .25f, RAIL_HEIGHT = RailRoute.RAIL_TOP - SLEEPER_TOP;
    /** The top of a rail set into a road, above the road. */
    static final float STREET_TOP = .15f;
    /** The longest step between a draped rail's height samples, model px. */
    private static final float STEP = 3;
    private static final Color BALLAST = new Color(.47f, .46f, .44f, 1), SLEEPER = new Color(.24f, .2f, .17f, 1);
    private static final Color RAIL_SIDE = new Color(.42f, .29f, .2f, 1), RAIL_HEAD = new Color(.62f, .6f, .58f, 1);

    private BoardRail() { }

    record End(float x, float y, float z, float slope, float centerSlope) {
        Vector3 point() { return new Vector3(x, y, z); }
    }

    /**
     * What a marker's mesh is made of: its kind, its drawn ends (a maglev beam squares its ends to them) and its centre
     * lines (model px, heights from its carrier plane), each point flagged where it is set into a road. Equal layouts
     * share one mesh.
     */
    record Layout(RailRoute.Kind kind, List<End> ends, List<List<Vector3>> paths, List<List<Boolean>> streets) {
        /** A maglev layout of these ends, its pieces laid by the default of their sides. */
        Layout(List<End> ends) { this(RailRoute.Kind.MAGLEV, ends, courses(ends, RailRoute.pieces(sides(ends))), List.of()); }
    }

    /** The height of what carries a rail at a point of its hex (model px from its centre), above its carrier plane. */
    @FunctionalInterface
    interface Carrier { float height(float x, float y); }

    static boolean route(BoardScene.Feature feature) { return RailRoute.isRoute(feature.decoration()); }

    /** The tile's first route marker, of either kind. */
    static BoardScene.Feature route(BoardScene.Tile tile) {
        if (tile == null) { return null; }
        return tile.features().stream().filter(BoardRail::route).findFirst().orElse(null);
    }

    /** A route marker's level by the placement support rule; a route holds a fixed level or follows its support. */
    static float level(BoardScene.Tile tile, BoardScene.Feature feature) {
        return (float) feature.decoration().placement().level(tile.elevation(), receiver -> tile.elevation());
    }

    /** Half the width a route's mesh covers on its hex: the maglev beam, the rail's ballast. */
    static float halfWidth(BoardScene.Feature feature) {
        return RailRoute.kind(feature.decoration()) == RailRoute.Kind.RAIL ? BALLAST_FOOT : 2;
    }

    /** A neighbouring marker joined to a route, and its tile. */
    private record Join(BoardScene.Tile tile, BoardScene.Feature feature) { }

    /** The neighbour's marker that joins {@code feature}'s route in {@code direction}: same kind, leaving back. */
    private static Join joined(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, int direction) {
        var neighbor = scene.neighbor(tile, direction);
        if (neighbor == null) { return null; }
        for (var other : neighbor.features()) {
            if (route(other) && RailRoute.joined(feature.decoration(), direction, other.decoration())) {
                return new Join(neighbor, other);
            }
        }
        return null;
    }

    /** The sides a marker draws: on a bridge deck only its bridge's exits. */
    static int drawnSides(BoardScene.Tile tile, BoardScene.Feature feature) {
        var bridge = BoardBridge.feature(tile);
        return RailRoute.drawnSides(feature.decoration(), bridge == null ? 0 : bridge.bridgeExits());
    }

    /** The neighboring bridges whose approach carries this ground rail; underpasses keep their ground support. */
    static List<BoardScene.Tile> approaches(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        if (RailRoute.kind(feature.decoration()) != RailRoute.Kind.RAIL || RailRoute.onDeck(feature.decoration())) {
            return List.of();
        }
        var result = new ArrayList<BoardScene.Tile>();
        int sides = drawnSides(tile, feature);
        for (int direction = 0; direction < 6; direction++) {
            var next = scene.neighbor(tile, direction);
            if ((sides & RailRoute.side(direction)) != 0 && next != null
                  && BoardSurface.connectingBridge(tile, next, direction) != null) { result.add(next); }
        }
        return List.copyOf(result);
    }

    /**
     * The marker's drawn ends at its edge midpoints, in direction order. A maglev side its neighbour joins eases on one
     * centre-to-centre Hermite curve towards that marker's level; any other end is open and level.
     */
    static List<End> ends(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        var ends = new ArrayList<End>(6);
        boolean maglev = RailRoute.kind(feature.decoration()) == RailRoute.Kind.MAGLEV;
        int sides = drawnSides(tile, feature);
        for (int direction = 0; direction < 6; direction++) {
            if ((sides & RailRoute.side(direction)) == 0) { continue; }
            ends.add(end(scene, tile, feature, direction, maglev ? joined(scene, tile, feature, direction) : null));
        }
        return List.copyOf(ends);
    }

    /** The end toward {@code direction}: eased towards the level of the maglev {@code neighbor} joins it, else open. */
    private static End end(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, int direction, Join neighbor) {
        var next = tile.coords().translated(direction);
        float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale();
        float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale();
        if (neighbor == null) { return new End(x / 2, y / 2, 0, 0, 0); }
        float length = (float) Math.hypot(x, y);
        float rise = (level(neighbor.tile(), neighbor.feature()) - level(tile, feature)) * BoardGeometry.level()
              / BoardGeometry.hexScale();
        float start = slope(scene, tile, feature, neighbor.tile()),
              finish = -slope(scene, neighbor.tile(), neighbor.feature(), tile);
        // Sample the same centre-to-centre Hermite curve from either owner. An arithmetic midpoint
        // would force a steepening/easing ripple when entering a constant grade from level track.
        return new End(x / 2, y / 2, rise / 2 + length * (start - finish) / 8,
              1.5f * rise / length - .25f * (start + finish), start);
    }

    /**
     * A maglev beam's joint with the neighbour's marker that joins it on its side {@code direction}: that tile and marker,
     * the beam's underside on the shared edge (world height, the same from both hexes) and its grade there towards the
     * neighbour (rise per run). Null for a rail, and where that side is not drawn or not joined.
     */
    record Joint(BoardScene.Tile next, BoardScene.Feature marker, float underside, float grade) { }

    static Joint joint(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, int direction) {
        if (RailRoute.kind(feature.decoration()) != RailRoute.Kind.MAGLEV
              || (drawnSides(tile, feature) & RailRoute.side(direction)) == 0) { return null; }
        var neighbor = joined(scene, tile, feature, direction);
        if (neighbor == null) { return null; }
        var end = end(scene, tile, feature, direction, neighbor);
        // The beam's mesh stands at the marker's level, in model px; its underside is the mesh's Z 0.
        return new Joint(neighbor.tile(), neighbor.feature(),
              level(tile, feature) * BoardGeometry.level() + end.z() * BoardGeometry.hexScale(), end.slope());
    }

    /**
     * The layout of a route marker; a rail's heights are draped on what carries it: its bridge's drawn deck
     * or a connected bridge approach (from {@code bridges}, the prepared or installed shapes), else the ground's
     * finished faces ({@code surface}, the road paint's support), where its rails are set into a drawn road.
     * Without a surface a rail lies on its carrier plane.
     */
    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, BoardSurface surface,
          Function<Coords, BoardBridge.Shape> bridges) {
        var ends = ends(scene, tile, feature);
        var kind = RailRoute.kind(feature.decoration());
        int sides = drawnSides(tile, feature);
        var paths = courses(ends, RailRoute.pieces(feature.decoration(), sides));
        if (kind == RailRoute.Kind.MAGLEV) { return new Layout(kind, ends, paths, List.of()); }
        float scale = BoardGeometry.hexScale(), base = level(tile, feature) * BoardGeometry.level();
        float cx = BoardGeometry.centerX(tile.coords()), cy = BoardGeometry.centerY(tile.coords());
        boolean onDeck = RailRoute.onDeck(feature.decoration());
        var deck = bridges.apply(tile.coords());
        var approaches = approaches(scene, tile, feature).stream().map(next -> bridges.apply(next.coords()))
              .filter(java.util.Objects::nonNull).toList();
        Carrier carrier = surface == null ? (x, y) -> 0 : (x, y) -> {
            float wx = cx + x * scale, wy = cy + y * scale;
            float z = onDeck ? BoardBridge.drawnTop(deck, tile, wx, wy) : BoardSurface.sampleHeight(surface.groundFaces(), wx, wy, base);
            for (var approach : approaches) { z = Math.max(z, BoardBridge.top(approach, wx, wy)); }
            return ((Float.isFinite(z) ? z : base) - base) / scale;
        };
        // Rails cross a drawn road set into it; ballast and sleepers resume beyond its carriageway.
        var road = surface == null ? null : street(scene, tile, onDeck);
        List<List<Vector3>> draped = new ArrayList<>(paths.size());
        List<List<Boolean>> streets = new ArrayList<>(paths.size());
        for (var path : paths) {
            var points = drape(path, carrier);
            var street = points.stream().map(p -> road != null && road.distance(p.x, p.y) < 0).toList();
            // Keep the points where the line bends, in plan or in height, and where it enters or leaves a road.
            List<Vector3> kept = new ArrayList<>();
            List<Boolean> keptStreet = new ArrayList<>();
            for (int i = 0; i < points.size(); i++) {
                boolean inner = i > 0 && i < points.size() - 1 && street.get(i) == street.get(i - 1)
                      && street.get(i) == street.get(i + 1);
                if (inner && between(kept.getLast(), points.get(i), points.get(i + 1))) { continue; }
                kept.add(points.get(i));
                keptStreet.add(street.get(i));
            }
            draped.add(List.copyOf(kept));
            streets.add(List.copyOf(keptStreet));
        }
        return new Layout(kind, ends, List.copyOf(draped), List.copyOf(streets));
    }

    /** The drawn road a rail of the tile is set into: none on a deck. */
    private static BoardRoad street(BoardScene scene, BoardScene.Tile tile, boolean onDeck) {
        return !onDeck && BoardRoad.rendered(tile) ? BoardRoad.of(scene, tile) : null;
    }

    /**
     * How far above its support a rail rider ({@link RailRoute#railRider}) stands at (x, y) of {@code tile} (model px
     * from its centre), the drawn rail top there: set into a drawn road, else on the ballast; 0 where no rail of the
     * tile lies on the rider's support (the ground, or a deck), and for any other object. In model px, as the track.
     */
    static float railTop(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature rider, float x, float y) {
        var object = rider.decoration();
        if (tile == null || object == null || !RailRoute.railRider(object.asset()) || object.placement().receiver() == null) {
            return 0;
        }
        boolean deck = object.placement().receiver().terrain().equals("bridge");
        for (var feature : tile.features()) {
            if (RailRoute.kind(feature.decoration()) != RailRoute.Kind.RAIL || RailRoute.onDeck(feature.decoration()) != deck) {
                continue;
            }
            var road = street(scene, tile, deck);
            return road != null && road.distance(x, y) < 0 ? STREET_TOP : RailRoute.RAIL_TOP;
        }
        return 0;
    }

    /** A maglev marker's layout, or a rail's on its carrier plane. */
    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        return layout(scene, tile, feature, null, at -> null);
    }

    /**
     * A route of the kind with these sides on its flat carrier, laying {@code tracks} where they are a layout of the
     * sides, else the default: what its palette card (a N-S straight) and the junction card show.
     */
    static Layout flat(RailRoute.Kind kind, int sides, int tracks) {
        var ends = new ArrayList<End>(6);
        for (int direction = 0; direction < 6; direction++) {
            if ((sides & RailRoute.side(direction)) == 0) { continue; }
            float[] border = RailRoute.border(direction);
            ends.add(new End(border[0], border[1], 0, 0, 0));
        }
        int pieces = tracks != 0 && RailRoute.valid(sides, tracks) ? tracks : RailRoute.pieces(sides);
        var paths = courses(ends, pieces);
        return new Layout(kind, List.copyOf(ends), paths, kind == RailRoute.Kind.MAGLEV ? List.of()
              : paths.stream().map(path -> path.stream().map(point -> false).toList()).toList());
    }

    /** The centre lines of a layout. */
    static List<List<Vector3>> paths(Layout layout) { return layout.paths(); }

    /** The centre lines of a marker on the board plane (a maglev's with its heights): what its mesh covers. */
    static List<List<Vector3>> paths(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        return courses(ends(scene, tile, feature), RailRoute.pieces(feature.decoration(), drawnSides(tile, feature)));
    }

    private static int sides(List<End> ends) {
        int sides = 0;
        for (End end : ends) { sides |= RailRoute.side(direction(end)); }
        return sides;
    }

    /** The side an end lies on, by the angle of its midpoint (N = 0, clockwise). */
    private static int direction(End end) {
        double angle = Math.toDegrees(Math.atan2(end.x(), end.y()));
        return Math.floorMod(Math.round((float) (angle / 60)), 6);
    }

    /**
     * The centre lines through drawn {@code ends}, as {@link RailRoute#lines} composes them, with their heights: each
     * piece of {@code pieces} between its two ends and a single end's run from the centre on Hermite heights; a stranded
     * side's stub and the lone stub of a marker without sides level.
     */
    private static List<List<Vector3>> courses(List<End> ends, int pieces) {
        End[] at = new End[6];
        for (End end : ends) { at[direction(end)] = end; }
        var result = new ArrayList<List<Vector3>>();
        for (var line : RailRoute.lines(sides(ends), pieces)) {
            if (line.from() >= 0 && line.to() >= 0) { result.add(curve(at[line.from()], at[line.to()])); }
            else if (line.to() >= 0) { result.add(curve(new End(0, 0, 0, 0, 0), at[line.to()])); }
            else {
                // A short level stub from the edge, closed by a buffer stop on a rail or the beam's end on a maglev.
                Vector3 edge = line.from() >= 0 ? at[line.from()].point() : new Vector3(line.a()[0], line.a()[1], 0);
                result.add(List.of(edge, new Vector3(line.b()[0], line.b()[1], edge.z)));
            }
        }
        return List.copyOf(result);
    }

    /** Outward grade at a marker; only the immediate neighbours it joins are consulted. */
    private static float slope(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, BoardScene.Tile toward) {
        float base = level(tile, feature);
        Join outgoing = null, behind = null;
        for (int direction = 0; direction < 6; direction++) {
            var candidate = joined(scene, tile, feature, direction);
            if (candidate == null) { continue; }
            if (candidate.tile().coords().equals(toward.coords())) { outgoing = candidate; continue; }
            // Switches have one level centre shared by their different paths.
            if (behind != null) { return 0; }
            behind = candidate;
        }
        float grade = outgoing == null ? 0 : grade(tile, outgoing, base);
        return behind == null ? grade : monotone(-grade(tile, behind, base), grade);
    }

    private static float grade(BoardScene.Tile from, Join to, float base) {
        float distance = (float) Math.hypot(BoardGeometry.centerX(to.tile().coords()) - BoardGeometry.centerX(from.coords()),
              BoardGeometry.centerY(to.tile().coords()) - BoardGeometry.centerY(from.coords()));
        return (level(to.tile(), to.feature()) - base) * BoardGeometry.level() / distance;
    }

    private static float monotone(float incoming, float outgoing) {
        return incoming * outgoing <= 0 ? 0 : 2 * incoming * outgoing / (incoming + outgoing);
    }

    private static List<Vector3> curve(End from, End to) {
        Vector3 a = from.point(), b = to.point();
        boolean terminal = a.isZero();
        // Constant grades need only one segment, including the half-hex at a route's end.
        boolean straight = Math.abs(a.x * b.y - a.y * b.x) < .001f;
        float middle = terminal ? to.centerSlope() : monotone(-from.centerSlope(), to.centerSlope());
        if (straight && Math.abs(to.slope() - middle) < .0001f && Math.abs(to.z() - middle * horizontalLength(b)) < .0001f
              && (terminal || Math.abs(from.slope() + middle) < .0001f
                    && Math.abs(from.z() + middle * horizontalLength(a)) < .0001f)) {
            return List.of(a, b);
        }
        // Horizontal approaches meet neighbouring hexes squarely; the one course in plan (RailRoute.course), which is
        // also what rolling stock rides.
        int steps = terminal ? 6 : 12;
        var result = new ArrayList<Vector3>(steps + 1);
        var distance = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            float[] plan = RailRoute.course(a.x, a.y, b.x, b.y, t);
            var point = terminal ? new Vector3(b).scl(t) : new Vector3(plan[0], plan[1], 0);
            point.z = 0;
            if (i > 0) { distance[i] = distance[i - 1] + point.dst(result.getLast()); }
            result.add(point);
        }
        float first = distance[steps / 2], second = distance[steps] - first;
        for (int i = 0; i <= steps; i++) {
            result.get(i).z = terminal ? height(0, b.z, middle, to.slope(), distance[steps], distance[i])
                  : i <= steps / 2 ? height(a.z, 0, -from.slope(), middle, first, distance[i])
                  : height(0, b.z, middle, to.slope(), second, distance[i] - first);
        }
        return result;
    }

    private static float horizontalLength(Vector3 point) { return (float) Math.hypot(point.x, point.y); }

    /** Cubic Hermite height using the shared edge grades derived by layout. */
    private static float height(float from, float to, float startSlope, float endSlope, float length, float distance) {
        float t = distance / length, t2 = t * t, t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * from + (t3 - 2 * t2 + t) * length * startSlope
              + (-2 * t3 + 3 * t2) * to + (t3 - t2) * length * endSlope;
    }

    /** Whether {@code point} lies on the line from {@code from} to {@code to}, within a hundredth of a pixel. */
    private static boolean between(Vector3 from, Vector3 point, Vector3 to) {
        Vector3 line = new Vector3(to).sub(from), offset = new Vector3(point).sub(from);
        float t = Math.clamp(offset.dot(line) / Math.max(1e-12f, line.len2()), 0, 1);
        return offset.dst2(new Vector3(line).scl(t)) < 1e-4f;
    }

    /** A centre line resampled at most {@link #STEP} apart, each point at its carrier's height. */
    private static List<Vector3> drape(List<Vector3> path, Carrier carrier) {
        var result = new ArrayList<Vector3>();
        for (int i = 0; i < path.size(); i++) {
            Vector3 point = path.get(i);
            if (i > 0) {
                Vector3 previous = path.get(i - 1);
                int steps = (int) Math.ceil(horizontalLength(new Vector3(point).sub(previous)) / STEP);
                for (int step = 1; step < steps; step++) {
                    result.add(new Vector3(previous).lerp(point, (float) step / steps));
                }
            }
            result.add(new Vector3(point));
        }
        for (Vector3 point : result) { point.z = carrier.height(point.x, point.y); }
        return List.copyOf(result);
    }

    static ModelData model(Layout layout) {
        var builder = new MeshBuilder();
        var attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked() };
        builder.begin(new VertexAttributes(attributes), GL20.GL_TRIANGLES);
        for (int index = 0; index < layout.paths().size(); index++) {
            var path = layout.paths().get(index);
            if (layout.kind() == RailRoute.Kind.MAGLEV) {
                beam(builder, layout, path, 2, 0, 2.4f, new Color(.59f, .58f, .53f, 1));
                beam(builder, layout, path, .7f, 2.4f, 4, new Color(.38f, .43f, .46f, 1));
            } else {
                track(builder, path, layout.streets().get(index));
            }
        }
        String id = layout.kind() == RailRoute.Kind.MAGLEV ? "maglev" : "rail";
        var mesh = new ModelMesh();
        mesh.id = id;
        mesh.attributes = attributes;
        mesh.vertices = new float[builder.getNumVertices() * 7];
        builder.getVertices(mesh.vertices, 0);
        var part = new ModelMeshPart();
        part.id = id; part.primitiveType = GL20.GL_TRIANGLES;
        part.indices = new short[builder.getNumIndices()]; builder.getIndices(part.indices, 0);
        mesh.parts = new ModelMeshPart[] { part };
        var material = new ModelMaterial(); material.id = id; material.diffuse = new Color(Color.WHITE);
        var nodePart = new ModelNodePart(); nodePart.meshPartId = part.id; nodePart.materialId = material.id;
        var node = new ModelNode(); node.id = id; node.meshId = mesh.id;
        node.parts = new ModelNodePart[] { nodePart };
        var data = new ModelData(); data.id = id;
        data.meshes.add(mesh); data.materials.add(material); data.nodes.add(node);
        return data;
    }

    private static void beam(MeshBuilder mesh, Layout layout, List<Vector3> path, float halfWidth, float low, float high, Color color) {
        mesh.setColor(color);
        Vector3[] previous = null;
        Vector3[] previousNormals = null;
        for (int i = 0; i < path.size(); i++) {
            Vector3 p = path.get(i);
            Vector3 tangent = new Vector3(path.get(Math.min(i + 1, path.size() - 1)))
                  .sub(path.get(Math.max(0, i - 1)));
            // Endpoint sections stay square to the shared edge; their corners then match exactly on both sides.
            if (i == 0 || i == path.size() - 1) {
                for (var end : layout.ends()) {
                    if (p.x == end.x() && p.y == end.y()) {
                        tangent.set(p.x, p.y, end.slope() * horizontalLength(p));
                        if (i == 0) { tangent.scl(-1); }
                        break;
                    }
                    if (p.isZero() && layout.ends().size() == 1) {
                        tangent.z = end.centerSlope() * horizontalLength(tangent);
                    }
                }
            }
            Vector3 side = new Vector3(-tangent.y, tangent.x, 0).nor();
            Vector3 up = new Vector3(tangent).crs(side).nor();
            Vector3[] normals = { new Vector3(up).scl(-1), new Vector3(side), up, new Vector3(side).scl(-1) };
            side.scl(halfWidth);
            Vector3[] ring = { new Vector3(p).sub(side).add(0, 0, low), new Vector3(p).add(side).add(0, 0, low),
                  new Vector3(p).add(side).add(0, 0, high), new Vector3(p).sub(side).add(0, 0, high) };
            if (previous == null) { quad(mesh, ring[3], ring[2], ring[1], ring[0]); }
            else {
                for (int edge = 0; edge < 4; edge++) {
                    int next = (edge + 1) % 4;
                    // Smooth along the rail while retaining the rectangular profile's sharp edges.
                    mesh.rect(vertex(previous[edge], previousNormals[edge]),
                          vertex(previous[next], previousNormals[edge]),
                          vertex(ring[next], normals[edge]), vertex(ring[edge], normals[edge]));
                }
            }
            if (i == path.size() - 1) { quad(mesh, ring[0], ring[1], ring[2], ring[3]); }
            previous = ring;
            previousNormals = normals;
        }
    }

    /**
     * Ballast, sleepers and two rails along a draped centre line. Where it is set into a road ({@code street}) only the
     * rails show, their heads {@link #STREET_TOP} above the road. Sleepers fit whole pitches between the line's ends, as
     * road dashes fit between borders ({@link BoardRoad#fit}), so they continue evenly across every border.
     */
    private static void track(MeshBuilder mesh, List<Vector3> path, List<Boolean> street) {
        int count = path.size();
        Vector3[] across = new Vector3[count];
        float[] along = new float[count];
        for (int i = 0; i < count; i++) {
            Vector3 tangent = new Vector3(path.get(Math.min(i + 1, count - 1))).sub(path.get(Math.max(0, i - 1)));
            across[i] = new Vector3(-tangent.y, tangent.x, 0).nor();
            if (i > 0) { along[i] = along[i - 1] + horizontalLength(new Vector3(path.get(i)).sub(path.get(i - 1))); }
        }
        mesh.setColor(BALLAST);
        float[][] bed = { { -BALLAST_FOOT, BALLAST_SINK }, { -BALLAST_HALF, BALLAST_TOP }, { BALLAST_HALF, BALLAST_TOP },
              { BALLAST_FOOT, BALLAST_SINK } };
        for (int i = 1; i < count; i++) {
            if (street.get(i - 1) || street.get(i)) { continue; }
            for (int strip = 0; strip < 3; strip++) {
                quad(mesh, at(path, across, i - 1, bed[strip]), at(path, across, i, bed[strip]),
                      at(path, across, i, bed[strip + 1]), at(path, across, i - 1, bed[strip + 1]));
            }
        }
        for (float offset : new float[] { -GAUGE_HALF, GAUGE_HALF }) {
            for (int i = 1; i < count; i++) {
                float topA = street.get(i - 1) ? STREET_TOP : RailRoute.RAIL_TOP, topB = street.get(i) ? STREET_TOP : RailRoute.RAIL_TOP;
                float[][] rail = { { offset - RAIL_HALF, topA - RAIL_HEIGHT, topB - RAIL_HEIGHT },
                      { offset - RAIL_HALF, topA, topB }, { offset + RAIL_HALF, topA, topB },
                      { offset + RAIL_HALF, topA - RAIL_HEIGHT, topB - RAIL_HEIGHT } };
                for (int face = 0; face < 3; face++) {
                    mesh.setColor(face == 1 ? RAIL_HEAD : RAIL_SIDE);
                    float[] a = rail[face], b = rail[face + 1];
                    quad(mesh, at(path, across, i - 1, a[0], a[1]), at(path, across, i, a[0], a[2]),
                          at(path, across, i, b[0], b[2]), at(path, across, i - 1, b[0], b[1]));
                }
            }
        }
        mesh.setColor(SLEEPER);
        float length = along[count - 1];
        float pitch = BoardRoad.fit(length, SLEEPER_PITCH);
        int segment = 1;
        for (float position = pitch / 2; position < length; position += pitch) {
            while (segment < count - 1 && along[segment] < position) { segment++; }
            float t = (position - along[segment - 1]) / Math.max(1e-6f, along[segment] - along[segment - 1]);
            if (street.get(segment - 1) || street.get(segment)) { continue; }
            Vector3 centre = new Vector3(path.get(segment - 1)).lerp(path.get(segment), t);
            Vector3 side = new Vector3(across[segment - 1]).lerp(across[segment], t).nor();
            sleeper(mesh, centre, side);
        }
    }

    /** One sleeper across the track at {@code centre}, lying on the ballast: its top and its two long faces. */
    private static void sleeper(MeshBuilder mesh, Vector3 centre, Vector3 side) {
        Vector3 forward = new Vector3(side.y, -side.x, 0).scl(SLEEPER_HALF_LENGTH), wide = new Vector3(side).scl(SLEEPER_HALF);
        Vector3[] low = new Vector3[4], high = new Vector3[4];
        for (int corner = 0; corner < 4; corner++) {
            Vector3 p = new Vector3(centre).mulAdd(wide, corner < 2 ? -1 : 1).mulAdd(forward, corner == 0 || corner == 3 ? -1 : 1);
            low[corner] = new Vector3(p).add(0, 0, BALLAST_TOP);
            high[corner] = new Vector3(p).add(0, 0, SLEEPER_TOP);
        }
        quad(mesh, high[0], high[1], high[2], high[3]);
        // The short ends, 0.8 by 0.3 px, hide beside the rails' ballast; only the faces along the track show.
        for (int corner = 1; corner < 4; corner += 2) {
            int next = (corner + 1) % 4;
            quad(mesh, low[corner], low[next], high[next], high[corner]);
        }
    }

    /** The point {@code offset} across the line at {@code i}, {@code height} above it. */
    private static Vector3 at(List<Vector3> path, Vector3[] across, int i, float offset, float height) {
        return new Vector3(path.get(i)).mulAdd(across[i], offset).add(0, 0, height);
    }

    private static Vector3 at(List<Vector3> path, Vector3[] across, int i, float[] profile) {
        return at(path, across, i, profile[0], profile[1]);
    }

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(Color.WHITE);
    }

    private static void quad(MeshBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Vector3 d) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        mesh.rect(a, b, c, d, normal);
    }
}
