/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

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
import megamek.common.board.MaglevRoute;

/**
 * Small texture-free guideways, generated only on board edits and shared by identical local connections. A marker's
 * stored sides lead to its hex edges; a side its neighbour also has joins that marker's level, any other side is an
 * open, level end at the edge.
 */
final class BoardMaglev {
    private BoardMaglev() { }

    record End(float x, float y, float z, float slope, float centerSlope) {
        Vector3 point() { return new Vector3(x, y, z); }
    }
    record Layout(List<End> ends) { }

    static boolean route(BoardScene.Feature feature) { return MaglevRoute.isRoute(feature.decoration()); }

    static BoardScene.Feature route(BoardScene.Tile tile) {
        if (tile == null) { return null; }
        return tile.features().stream().filter(BoardMaglev::route).findFirst().orElse(null);
    }

    /** A route marker's level by the placement support rule; a route holds a fixed level or follows the ground. */
    static float level(BoardScene.Tile tile, BoardScene.Feature feature) {
        return (float) feature.decoration().placement().level(tile.elevation(), receiver -> tile.elevation());
    }

    /** The neighbour whose marker joins {@code feature}'s route in {@code direction}, or null. */
    private static BoardScene.Tile joined(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature, int direction) {
        var neighbor = scene.neighbor(tile, direction);
        var other = route(neighbor);
        return other != null && MaglevRoute.joined(feature.decoration(), direction, other.decoration()) ? neighbor : null;
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        var ends = new ArrayList<End>(6);
        float base = level(tile, feature);
        for (int direction = 0; direction < 6; direction++) {
            if ((feature.decoration().connections() & MaglevRoute.side(direction)) == 0) { continue; }
            var next = tile.coords().translated(direction);
            float x = (BoardGeometry.centerX(next) - BoardGeometry.centerX(tile.coords())) / BoardGeometry.hexScale();
            float y = (BoardGeometry.centerY(next) - BoardGeometry.centerY(tile.coords())) / BoardGeometry.hexScale();
            var neighbor = joined(scene, tile, feature, direction);
            if (neighbor == null) { ends.add(new End(x / 2, y / 2, 0, 0, 0)); continue; }
            var other = route(neighbor);
            float length = (float) Math.hypot(x, y);
            float rise = (level(neighbor, other) - base) * BoardGeometry.level() / BoardGeometry.hexScale();
            float start = slope(scene, tile, neighbor), finish = -slope(scene, neighbor, tile);
            // Sample the same centre-to-centre Hermite curve from either owner. An arithmetic midpoint
            // would force a steepening/easing ripple when entering a constant grade from level track.
            ends.add(new End(x / 2, y / 2, rise / 2 + length * (start - finish) / 8,
                  1.5f * rise / length - .25f * (start + finish), start));
        }
        return new Layout(List.copyOf(ends));
    }

    /** Outward grade at a marker; only the immediate neighbours it joins are consulted. */
    private static float slope(BoardScene scene, BoardScene.Tile tile, BoardScene.Tile toward) {
        var feature = route(tile);
        float base = level(tile, feature);
        float outgoing = grade(tile, toward, base);
        BoardScene.Tile behind = null;
        for (int direction = 0; direction < 6; direction++) {
            var candidate = joined(scene, tile, feature, direction);
            if (candidate == null || candidate.coords().equals(toward.coords())) { continue; }
            // Switches have one level centre shared by their different paths.
            if (behind != null) { return 0; }
            behind = candidate;
        }
        return behind == null ? outgoing : monotone(-grade(tile, behind, base), outgoing);
    }

    private static float grade(BoardScene.Tile from, BoardScene.Tile to, float base) {
        float distance = (float) Math.hypot(BoardGeometry.centerX(to.coords()) - BoardGeometry.centerX(from.coords()),
              BoardGeometry.centerY(to.coords()) - BoardGeometry.centerY(from.coords()));
        return (level(to, route(to)) - base) * BoardGeometry.level() / distance;
    }

    private static float monotone(float incoming, float outgoing) {
        return incoming * outgoing <= 0 ? 0 : 2 * incoming * outgoing / (incoming + outgoing);
    }

    /** Branches form a turnout fan with one shared approach, never radial spokes meeting at a sharp hub. */
    static List<List<Vector3>> paths(Layout layout) {
        var ends = layout.ends();
        if (ends.isEmpty()) { return List.of(List.of(new Vector3(0, -12, 0), new Vector3(0, 12, 0))); }
        if (ends.size() == 2) { return List.of(curve(ends.get(0), ends.get(1))); }
        if (ends.size() == 1) { return List.of(curve(new End(0, 0, 0, 0, 0), ends.getFirst())); }
        // Choose the approach facing the branches as directly as possible. Comparing the tightest turn
        // also handles symmetric Y junctions deterministically, without storing switch state on the board.
        int approach = 0;
        float best = Float.POSITIVE_INFINITY;
        for (int i = 0; i < ends.size(); i++) {
            var a = ends.get(i).point(); a.z = 0; a.nor();
            float tightest = -1;
            for (int j = 0; j < ends.size(); j++) {
                if (i == j) { continue; }
                var b = ends.get(j).point(); b.z = 0; b.nor();
                tightest = Math.max(tightest, a.dot(b));
            }
            if (tightest < best - .0001f) { best = tightest; approach = i; }
        }
        var paths = new ArrayList<List<Vector3>>(ends.size() - 1);
        for (int i = 0; i < ends.size(); i++) {
            if (i != approach) { paths.add(curve(ends.get(approach), ends.get(i))); }
        }
        return paths;
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
        // Horizontal approaches meet neighbouring hexes squarely.
        Vector3 ca = new Vector3(a).scl(.35f), cb = new Vector3(b).scl(.35f);
        int steps = terminal ? 6 : 12;
        var result = new ArrayList<Vector3>(steps + 1);
        var distance = new float[steps + 1];
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps, u = 1 - t;
            var point = new Vector3(a).scl(u * u * u).mulAdd(ca, 3 * u * u * t)
                  .mulAdd(cb, 3 * u * t * t).mulAdd(b, t * t * t);
            if (terminal) { point.set(b).scl(t); }
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

    static ModelData model(Layout layout) {
        var builder = new MeshBuilder();
        var attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(), VertexAttribute.ColorPacked() };
        builder.begin(new VertexAttributes(attributes), GL20.GL_TRIANGLES);
        for (var path : paths(layout)) {
            beam(builder, layout, path, 2, 0, 2.4f, new Color(.59f, .58f, .53f, 1));
            beam(builder, layout, path, .7f, 2.4f, 4, new Color(.38f, .43f, .46f, 1));
        }
        var mesh = new ModelMesh();
        mesh.id = "maglev";
        mesh.attributes = attributes;
        mesh.vertices = new float[builder.getNumVertices() * 7];
        builder.getVertices(mesh.vertices, 0);
        var part = new ModelMeshPart();
        part.id = "maglev"; part.primitiveType = GL20.GL_TRIANGLES;
        part.indices = new short[builder.getNumIndices()]; builder.getIndices(part.indices, 0);
        mesh.parts = new ModelMeshPart[] { part };
        var material = new ModelMaterial(); material.id = "maglev"; material.diffuse = new Color(Color.WHITE);
        var nodePart = new ModelNodePart(); nodePart.meshPartId = part.id; nodePart.materialId = material.id;
        var node = new ModelNode(); node.id = "maglev"; node.meshId = mesh.id;
        node.parts = new ModelNodePart[] { nodePart };
        var data = new ModelData(); data.id = "maglev";
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

    private static MeshPartBuilder.VertexInfo vertex(Vector3 point, Vector3 normal) {
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setCol(Color.WHITE);
    }

    private static void quad(MeshBuilder mesh, Vector3 a, Vector3 b, Vector3 c, Vector3 d) {
        Vector3 normal = new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).nor();
        mesh.rect(a, b, c, d, normal);
    }
}
