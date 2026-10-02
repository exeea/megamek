/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Cosmetic portals at authored road exits blocked by a tall wall. Never adds a game connection. */
record BoardTunnel(Coords road, Vector3 origin, Vector3 along, BoardRoad.Kind kind, boolean bridge) {
    static final String ASSET = "road-tunnel";
    static final String BRIDGE_ASSET = "road-tunnel-bridge";
    // Asset contract: tools/build_tunnel_asset.py. +Y points into the cliff, Z is up.
    static final float HALF_WIDTH = 9.5f;
    static final float SPRING = 6;
    static final float DEPTH = 18;
    static final int ARCH_SEGMENTS = 16;

    static List<BoardTunnel> entrances(BoardScene scene, BoardScene.Tile tile) {
        List<BoardTunnel> result = new ArrayList<>();
        if (BoardRoad.rendered(tile)) {
            entrances(scene, tile, tile.roadExits(), tile.elevation(), tile.road(), false, result);
        }
        var feature = BoardBridge.feature(tile);
        var kind = feature == null ? BoardRoad.Kind.NONE : BoardBridge.kind(scene, tile);
        if (kind != BoardRoad.Kind.NONE) {
            entrances(scene, tile, feature.bridgeExits(), tile.elevation() + feature.elevation(), kind, true, result);
        }
        return List.copyOf(result);
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
            Vector3 origin = center.lerp(neighbor, .5f).mulAdd(along, -3 * BoardGeometry.hexScale());
            origin.z += GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
            result.add(new BoardTunnel(tile.coords(), origin, along, kind, bridge));
        }
    }

    String asset() { return bridge ? BRIDGE_ASSET : ASSET; }

    Matrix4 transform() {
        float turn = (float) Math.toDegrees(Math.atan2(-along.x, along.y));
        float scale = BoardGeometry.hexScale();
        return new Matrix4().setToTranslation(origin).rotate(Vector3.Z, turn).scale(scale, scale, scale);
    }

    private Vector3 local(Vector3 p) {
        float scale = BoardGeometry.hexScale(), x = (p.x - origin.x) / scale, y = (p.y - origin.y) / scale;
        return new Vector3(x * along.y - y * along.x, x * along.x + y * along.y, (p.z - origin.z) / scale);
    }

    /** How far (x, y) lies to either side of the wing walls, in tile pixels; negative within them. */
    float beside(float x, float y) {
        Vector3 p = local(new Vector3(x, y, origin.z));
        return p.y < -8 || p.y > DEPTH + 2 ? Float.POSITIVE_INFINITY : Math.abs(p.x) - (bridge ? 13.3f : 18);
    }

    /** Keep whole decorative rocks clear of the shell, wing walls and the road approaching the mouth. */
    boolean obstructs(Vector3 base, float radius, float height) {
        Vector3 p = local(base);
        float scale = BoardGeometry.hexScale(), r = radius / scale;
        if (p.z >= 20 || p.z + height / scale <= -1) { return false; }
        boolean shell = Math.abs(p.x) < (bridge ? 13.3f : 18) + r
              && p.y > (bridge ? -.1f : -7) - r && p.y < DEPTH + r;
        boolean approach = Math.abs(p.x) < HALF_WIDTH + 1 + r && p.y > -36 - r && p.y < r;
        return shell || approach;
    }

    /** Remove only the portal's arch prism from the existing cliff mesh; its surrounding slope stays native. */
    void cut(List<BoardSurface.Face> faces, BoardRelief relief) {
        List<float[]> planes = new ArrayList<>();
        planes.add(new float[] { 1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { -1, 0, 0, HALF_WIDTH });
        planes.add(new float[] { 0, 1, 0, DEPTH });
        planes.add(new float[] { 0, -1, 0, .1f });
        planes.add(new float[] { 0, 0, -1, .1f });
        for (int i = 0; i < ARCH_SEGMENTS; i++) {
            double a = i * Math.PI / ARCH_SEGMENTS, b = (i + 1) * Math.PI / ARCH_SEGMENTS;
            float ax = HALF_WIDTH * (float) Math.cos(a), az = SPRING + HALF_WIDTH * (float) Math.sin(a);
            float bx = HALF_WIDTH * (float) Math.cos(b), bz = SPRING + HALF_WIDTH * (float) Math.sin(b);
            float nx = bz - az, nz = ax - bx;
            planes.add(new float[] { nx, 0, nz, nx * ax + nz * az });
        }
        List<BoardSurface.Face> result = new ArrayList<>(faces.size());
        for (var face : faces) {
            List<Vector3> inside = List.of(face.a(), face.b(), face.c());
            // A separating plane rejects almost every face without allocating clipped polygons.
            boolean outside = false;
            for (float[] plane : planes) {
                if (inside.stream().allMatch(p -> distance(local(p), plane) > 0)) { outside = true; break; }
            }
            if (outside) { result.add(face); continue; }
            for (float[] plane : planes) {
                if (inside.size() < 3) { break; }
                List<Vector3> kept = new ArrayList<>(), removed = new ArrayList<>();
                for (int i = 0; i < inside.size(); i++) {
                    Vector3 a = inside.get(i), b = inside.get((i + 1) % inside.size());
                    float da = distance(local(a), plane), db = distance(local(b), plane);
                    (da <= 0 ? removed : kept).add(a);
                    if ((da < 0 && db > 0) || (da > 0 && db < 0)) {
                        float t = da / (da - db);
                        Vector3 p = new Vector3(a).lerp(b, t);
                        relief.interpolateShade(a, b, t, p);
                        kept.add(p);
                        removed.add(p);
                    } else if (da == 0) {
                        kept.add(a);
                    }
                }
                for (int i = 1; i + 1 < kept.size(); i++) {
                    Vector3 a = kept.getFirst(), b = kept.get(i), c = kept.get(i + 1);
                    if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-10f) {
                        result.add(new BoardSurface.Face(a, b, c, face.finish(), face.landEdge()));
                    }
                }
                inside = removed;
            }
        }
        faces.clear();
        faces.addAll(result);
    }

    private static float distance(Vector3 p, float[] plane) {
        return p.x * plane[0] + p.y * plane[1] + p.z * plane[2] - plane[3];
    }
}
