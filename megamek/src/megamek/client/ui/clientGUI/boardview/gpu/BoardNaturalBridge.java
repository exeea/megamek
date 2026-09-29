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
        var outline = new ArrayList<Rim>();
        for (int d = 0; d < 6; d++) {
            var gate = BoardGeometry.center(tile.coords().translated(d), level).sub(center).scl(.5f);
            float length = gate.len();
            var along = new Vector3(gate).nor();
            if ((exits & (1 << d)) == 0) {
                float waist = .29f * (.85f + .3f * noise(center.x + gate.x, center.y + gate.y));
                outline.add(new Rim(new Vector3(center).mulAdd(along, length * waist), -1));
                continue;
            }
            var next = scene.tile(tile.coords().translated(d));
            boolean bank = BoardBridge.bank(tile, next, d);
            // Bury the ends in the existing bank's actual top, including a recessed cliff rim. No bank is moved.
            if (bank) { gate.mulAdd(along, Math.min(length * .65f, BoardRelief.stepRoom() + BoardRelief.metres(.7f))); }
            float width = BoardRelief.metres(BoardBridge.NATURAL_HALF_WIDTH) * (.96f + .08f * noise(center.x + gate.x, center.y + gate.y));
            var across = new Vector3(-along.y, along.x, 0);
            var ground = bank ? surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod)) : null;
            var contact = bank ? ground.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP
                  && f.finish() != BoardSurface.Finish.DRESSING && f.finish() != BoardSurface.Finish.ICE).toList() : List.<BoardSurface.Face>of();
            // A straight two-corner mouth can miss a scalloped cliff between the corners. Fit the entire opening;
            // keep these contact samples at every LOD and never use a decorative boulder as the bank height.
            int sections = bank ? 4 : 1;
            for (int i = 0; i <= sections; i++) {
                var point = new Vector3(center).add(gate).mulAdd(across, width * (1 - 2f * i / sections));
                if (bank) {
                    float[] settled = ground.relief.settle(point.x, point.y, BoardRelief.metres(.7f));
                    point.set(settled[0], settled[1], BoardSurface.sampleHeight(contact, settled[0], settled[1],
                          BoardGeometry.groundZ(next)) - BoardRelief.metres(.025f));
                }
                outline.add(new Rim(point, d));
            }
        }
        // Directions run clockwise; the solid's top needs counterclockwise winding.
        Collections.reverse(outline);
        int edgeSteps = lod == TerrainLod.FULL ? 3 : lod == TerrainLod.MEDIUM ? 2 : 1;
        var rim = new ArrayList<Rim>();
        for (int i = 0; i < outline.size(); i++) {
            var a = outline.get(i);
            var b = outline.get((i + 1) % outline.size());
            boolean mouth = a.exit() >= 0 && a.exit() == b.exit();
            int steps = mouth ? 1 : edgeSteps;
            for (int j = 0; j < steps; j++) {
                float t = j / (float) steps;
                var point = new Vector3(a.point()).lerp(b.point(), t);
                if (j > 0) {
                    var outward = new Vector3(point).sub(center); outward.z = 0; outward.nor();
                    point.mulAdd(outward, BoardRelief.metres(1.2f) * (noise(point.x, point.y) - .5f));
                }
                rim.add(new Rim(point, mouth || j == 0 ? a.exit() : -1));
            }
        }
        int rings = lod == TerrainLod.FULL ? 5 : lod == TerrainLod.MEDIUM ? 4 : lod == TerrainLod.COARSE ? 3 : 2;
        var facets = new ArrayList<Facet>();
        Vector3[][] top = new Vector3[rings + 1][rim.size()], bottom = new Vector3[rings + 1][rim.size()];
        float space = clearance(tile, center.z);
        float crown = Math.min(BoardRelief.metres(1.2f), space * .3f);
        float spring = Math.min(BoardRelief.metres(4), space * .85f);
        for (int r = 0; r <= rings; r++) {
            // The outer fifth is exposed caprock at every LOD; only interior sampling changes with distance.
            float t = r == rings ? 1 : .8f * r / (rings - 1);
            for (int i = 0; i < rim.size(); i++) {
                var edge = rim.get(i);
                var point = new Vector3(center).lerp(edge.point(), t);
                // Keep the usable centre at the authoritative deck level. Only exposed lips are chipped down.
                if (edge.exit() < 0) {
                    point.z -= BoardRelief.metres(.45f) * t * t * t * (.5f + noise(point.x, point.y));
                }
                top[r][i] = point;
                float anchored = 0;
                for (int d = 0; d < 6; d++) {
                    if (!BoardBridge.bank(tile, scene.tile(tile.coords().translated(d)), d)) { continue; }
                    var gate = BoardGeometry.center(tile.coords().translated(d), level).sub(center).scl(.5f);
                    float u = Math.clamp(new Vector3(point).sub(center).dot(gate) / gate.len2(), 0, 1);
                    anchored = Math.max(anchored, u * u * u * u);
                }
                float thickness = crown + (spring - crown) * anchored;
                bottom[r][i] = new Vector3(point).add(0, 0, -thickness);
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
                        p.mulAdd(outward, BoardRelief.metres(.35f) * (noise(p.x + bed, p.y) - .5f));
                    }
                }
                triangle(facets, a, c, e, Part.SIDE);
                triangle(facets, a, e, b, Part.SIDE);
                a = c; b = e;
            }
        }
        return BoardBridge.shape(deck.surface(), level, facets);
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
