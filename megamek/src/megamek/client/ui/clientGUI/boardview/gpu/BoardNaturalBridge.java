/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.triangle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Facet;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Part;
import megamek.client.ui.clientGUI.boardview.gpu.BoardBridge.Shape;
import megamek.common.board.Coords;

/** A separate, hollow rock span. Its shell never replaces the ground, roads or water underneath. */
final class BoardNaturalBridge {
    private BoardNaturalBridge() { }

    private record Rim(Vector3 point, int exit) { }

    static Shape build(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, TerrainLod lod,
          Map<Coords, BoardSurface> surfaces) {
        var bridge = BoardBridge.feature(tile);
        float level = tile.elevation() + bridge.elevation();
        var center = BoardGeometry.center(tile.coords(), level);
        int exits = bridge.bridgeExits();
        float space = clearance(tile, center.z);
        var outline = new ArrayList<Rim>();
        for (int d = 0; d < 6; d++) {
            var gate = BoardGeometry.center(tile.coords().translated(d), level).sub(center).scl(.5f);
            float length = gate.len();
            var along = new Vector3(gate).nor();
            if ((exits & (1 << d)) == 0) {
                float waist = .38f + .16f * noise((center.x + gate.x) * .45f, (center.y + gate.y) * .45f);
                outline.add(new Rim(new Vector3(center).mulAdd(along, length * waist), -1));
                continue;
            }
            var next = scene.tile(tile.coords().translated(d));
            boolean bank = BoardBridge.abutment(tile, next, d);
            float width = BoardRelief.metres(BoardBridge.NATURAL_HALF_WIDTH) * (.96f + .08f * noise(center.x + gate.x, center.y + gate.y));
            if (bank) { width *= 1.3f; }
            var across = new Vector3(-along.y, along.x, 0);
            var ground = bank ? surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod)) : null;
            if (bank) {
                // Fit the entire rock body into the rendered wall, whose recess changes with height and width.
                float reach = bankReach(scene, ground, surfaces, center, along, width, space, d);
                gate.set(along).scl(Math.max(length + BoardRelief.metres(1), reach));
            }
            var contact = bank ? ground.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP
                  && f.finish() != BoardSurface.Finish.DRESSING && f.finish() != BoardSurface.Finish.ICE).toList() : List.<BoardSurface.Face>of();
            // A straight two-corner mouth can miss a scalloped cliff between the corners. Fit the entire opening;
            // keep these contact samples at every LOD and never use a decorative boulder as the bank height.
            int sections = bank ? 4 : 1;
            for (int i = 0; i <= sections; i++) {
                var point = new Vector3(center).add(gate).mulAdd(across, width * (1 - 2f * i / sections));
                if (bank && BoardBridge.bank(tile, next, d)) {
                    float[] settled = ground.relief.settle(point.x, point.y, BoardRelief.metres(.7f));
                    point.set(settled[0], settled[1], BoardSurface.sampleHeight(contact, settled[0], settled[1],
                          BoardGeometry.groundZ(next)) - BoardRelief.metres(.025f));
                }
                outline.add(new Rim(point, d));
            }
        }
        // Directions run clockwise; the solid's top needs counterclockwise winding.
        Collections.reverse(outline);
        int edgeSteps = lod == TerrainLod.FULL ? 5 : lod == TerrainLod.MEDIUM ? 3 : lod == TerrainLod.COARSE ? 2 : 1;
        var rim = new ArrayList<Rim>();
        for (int i = 0; i < outline.size(); i++) {
            var a = outline.get(i);
            var b = outline.get((i + 1) % outline.size());
            boolean mouth = a.exit() >= 0 && a.exit() == b.exit();
            int steps = mouth ? 1 : edgeSteps;
            for (int j = 0; j < steps; j++) {
                float t = j / (float) steps;
                var point = mouth ? new Vector3(a.point()).lerp(b.point(), t) : curve(outline, i, t);
                if (j > 0) {
                    var outward = new Vector3(point).sub(center); outward.z = 0; outward.nor();
                    point.mulAdd(outward, BoardRelief.metres(2) * (noise(point.x * .6f, point.y * .6f) - .5f));
                }
                rim.add(new Rim(point, mouth || j == 0 ? a.exit() : -1));
            }
        }
        int rings = lod == TerrainLod.FULL ? 5 : lod == TerrainLod.MEDIUM ? 4 : lod == TerrainLod.COARSE ? 3 : 2;
        var facets = new ArrayList<Facet>();
        Vector3[][] top = new Vector3[rings + 1][rim.size()], bottom = new Vector3[rings + 1][rim.size()];
        for (int r = 0; r <= rings; r++) {
            // The outer fifth is exposed caprock at every LOD; only interior sampling changes with distance.
            float t = r == rings ? 1 : .8f * r / (rings - 1);
            for (int i = 0; i < rim.size(); i++) {
                var edge = rim.get(i);
                var point = new Vector3(center).lerp(edge.point(), t);
                // Keep the centre and the joined mouths at deck height, with shallow erosion across the cap.
                point.z -= Math.min(BoardRelief.metres(1.4f), space * .16f) * t * (1 - t)
                      * (.3f + .7f * noise(point.x * .35f, point.y * .35f));
                if (edge.exit() < 0) {
                    point.z -= Math.min(BoardRelief.metres(1.3f), space * .16f) * t * t * (.6f + .4f * noise(point.x, point.y));
                }
                top[r][i] = point;
                float anchored = anchoring(scene, tile, point), room = space;
                for (int d = 0; d < 6; d++) {
                    var next = scene.tile(tile.coords().translated(d));
                    if (!BoardBridge.connected(tile, next, d)) { continue; }
                    var gate = BoardGeometry.center(next.coords(), level).sub(center).scl(.5f);
                    float u = Math.clamp(new Vector3(point).sub(center).dot(gate) / gate.len2(), 0, 1);
                    float join = Math.clamp(2 * u - 1, 0, 1);
                    anchored = Math.max(anchored, anchoring(scene, next, point) * join);
                    room = Math.min(room, space + (clearance(next, center.z) - space) * join);
                }
                float crown = Math.min(BoardRelief.metres(2.7f) * (.82f + .3f * noise(point.x * .35f, point.y * .35f)), room * .43f);
                float spring = Math.min(BoardRelief.metres(5.3f), room * .79f);
                float thickness = (crown + (spring - crown) * anchored) * (.93f + .1f * noise(point.x, point.y));
                bottom[r][i] = new Vector3(point).add(0, 0, -thickness);
                if (edge.exit() < 0) {
                    var inward = new Vector3(center).sub(point); inward.z = 0; inward.nor();
                    bottom[r][i].mulAdd(inward, BoardRelief.metres(.8f) * t * t * t * t);
                }
            }
        }
        for (int i = 0; i < rim.size(); i++) {
            int n = (i + 1) % rim.size();
            for (int r = 1; r <= rings; r++) {
                boolean mouth = rim.get(i).exit() >= 0 && rim.get(i).exit() == rim.get(n).exit();
                var cover = r == rings && !mouth ? Part.RIM : Part.TOP;
                triangle(facets, top[r - 1][i], top[r][i], top[r][n], cover);
                triangle(facets, bottom[r - 1][i], bottom[r][n], bottom[r][i], Part.SOFFIT);
                if (r > 1) {
                    triangle(facets, top[r - 1][i], top[r][n], top[r - 1][n], cover);
                    triangle(facets, bottom[r - 1][i], bottom[r - 1][n], bottom[r][n], Part.SOFFIT);
                }
            }
            int exit = rim.get(i).exit();
            if (exit >= 0 && exit == rim.get(n).exit()
                  && BoardBridge.connected(tile, scene.tile(tile.coords().translated(exit)), exit)) { continue; }
            // Broad broken strata define the close silhouette; the shared normal maps supply small fissures.
            int beds = lod == TerrainLod.FULL ? 3 : lod == TerrainLod.MEDIUM ? 2 : 1;
            var a = top[rings][i];
            var b = top[rings][n];
            for (int bed = 1; bed <= beds; bed++) {
                float t = bed / (float) beds;
                var c = new Vector3(top[rings][i]).lerp(bottom[rings][i], t);
                var e = new Vector3(top[rings][n]).lerp(bottom[rings][n], t);
                if (bed < beds) {
                    for (int side = 0; side < 2; side++) {
                        if (rim.get(side == 0 ? i : n).exit() >= 0) { continue; }
                        var p = side == 0 ? c : e;
                        var outward = new Vector3(p).sub(center); outward.z = 0; outward.nor();
                        p.mulAdd(outward, BoardRelief.metres(1.4f) * (float) Math.sin(Math.PI * t)
                              * (.3f + .7f * noise(p.x + bed, p.y)));
                    }
                }
                triangle(facets, a, c, e, Part.SIDE);
                triangle(facets, a, e, b, Part.SIDE);
                a = c; b = e;
            }
        }
        return BoardBridge.shape(deck.surface(), level, facets);
    }

    /** Round the exposed outline through its control points; mouths keep their exact shared contacts. */
    private static Vector3 curve(List<Rim> outline, int index, float t) {
        var a = outline.get(index).point();
        var b = outline.get((index + 1) % outline.size()).point();
        var before = outline.get(Math.floorMod(index - 1, outline.size())).point();
        var after = outline.get((index + 2) % outline.size()).point();
        float limit = a.dst(b) * .35f;
        var first = new Vector3(b).sub(before).scl(1f / 6).limit(limit).add(a);
        var second = new Vector3(a).sub(after).scl(1f / 6).limit(limit).add(b);
        float u = 1 - t;
        return new Vector3(a).scl(u * u * u).mulAdd(first, 3 * u * u * t)
              .mulAdd(second, 3 * u * t * t).mulAdd(b, t * t * t);
    }

    private static float anchoring(BoardScene scene, BoardScene.Tile tile, Vector3 point) {
        var center = BoardGeometry.center(tile.coords(), tile.elevation() + BoardBridge.feature(tile).elevation());
        float anchored = 0;
        for (int d = 0; d < 6; d++) {
            if (!BoardBridge.abutment(tile, scene.tile(tile.coords().translated(d)), d)) { continue; }
            var gate = BoardGeometry.center(tile.coords().translated(d), 0).sub(BoardGeometry.center(tile.coords(), 0)).scl(.5f);
            float u = Math.clamp(new Vector3(point).sub(center).dot(gate) / gate.len2(), 0, 1);
            anchored = Math.max(anchored, u * u);
        }
        return anchored;
    }

    /** A conservative overlap of the whole mouth and underside with the finished cliff triangles. */
    private static float bankReach(BoardScene scene, BoardSurface ground, Map<Coords, BoardSurface> surfaces,
          Vector3 center, Vector3 along, float width, float space, int direction) {
        List<BoardSurface.Face> walls;
        // Within the chunk walls are already complete; an out-of-chunk bank can be borrowed by several spans.
        synchronized (ground) { walls = ground.walls(scene, BoardGeometry.floor(scene), surfaces); }
        float reach = 0;
        for (var face : walls) {
            // Around a scalloped corner the contact can belong to the bank's adjoining edge as well.
            int facing = Math.floorMod(BoardGeometry.edgeDirection(face.landEdge()) - direction - 3, 6);
            if (face.landEdge() < 0 || (facing != 0 && facing != 1 && facing != 5)) { continue; }
            var normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
            if (normal.dot(along) >= 0) { continue; }
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            float bottom = Float.POSITIVE_INFINITY, top = Float.NEGATIVE_INFINITY, depth = 0;
            for (var p : List.of(face.a(), face.b(), face.c())) {
                float x = p.x - center.x, y = p.y - center.y;
                float lateral = y * along.x - x * along.y;
                low = Math.min(low, lateral); high = Math.max(high, lateral);
                bottom = Math.min(bottom, p.z); top = Math.max(top, p.z);
                depth = Math.max(depth, x * along.x + y * along.y);
            }
            if (low <= width && high >= -width && bottom <= center.z && top >= center.z - space) {
                reach = Math.max(reach, depth);
            }
        }
        return reach + BoardRelief.metres(1.25f);
    }

    /** Preserve the last whole level below the deck; never root a support in an occupied lower hex. */
    private static float clearance(BoardScene.Tile tile, float deck) {
        float below = BoardGeometry.groundZ(tile);
        for (var feature : tile.features()) {
            if (feature.asset().equals("bridge")) { continue; }
            float top = (tile.elevation() + feature.elevation() + feature.height()) * BoardGeometry.level();
            if (top < deck) { below = Math.max(below, top); }
        }
        return Math.max(BoardRelief.metres(.05f), Math.min(BoardGeometry.level(), deck - below));
    }

    private static float noise(float x, float y) {
        float metre = BoardRelief.metres(1);
        return BoardRelief.noise(x / (metre * 2.3f), y / (metre * 2.3f));
    }

}
