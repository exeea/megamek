/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;

/** Short continuations of the authored deck and rails, ending on the actual bank rather than a nominal hex edge. */
record BoardBridgeFooting(BoardBridge.Shape shape, List<Float> lengths, int bareExits) {
    static final float RAIL_TAPER_METRES = 1.5f;
    static final float APRON_METRES = 7;
    private static final float BLOCK_OUTSET_METRES = .3f;

    /** Paint extends onto the existing bank after the structural footing has ended. */
    BoardRoad road(BoardBridge.Deck deck, Coords coords) {
        var reaches = new ArrayList<>(lengths);
        for (int d = 0; d < 6; d++) {
            if ((bareExits & (1 << d)) != 0) { reaches.set(d, reaches.get(d) + apronLength()); }
        }
        return deck.road(coords).extended(reaches, bareExits);
    }

    static float apronLength() { return BoardRelief.metres(APRON_METRES) / BoardGeometry.hexScale(); }

    static BoardBridgeFooting build(BoardScene scene, BoardScene.Tile tile, TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        float level = tile.elevation() + BoardBridge.feature(tile).elevation();
        float scale = BoardGeometry.hexScale();
        var center = BoardGeometry.center(tile.coords(), level).add(0, 0, GpuRoads.SURFACE_LIFT * scale);
        var faces = new ArrayList<BoardBridge.Facet>();
        var lengths = new ArrayList<Float>();
        int bareExits = 0;
        float lane = BoardRoad.Kind.PAVED.halfWidth * scale, width = lane + BoardRoad.SHOULDER * scale;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (!BoardBridge.bank(tile, next, d)) { lengths.add(0f); continue; }
            var bank = surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod));
            var ground = bank.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP
                  && f.finish() != BoardSurface.Finish.DRESSING && f.finish() != BoardSurface.Finish.ICE).toList();
            var along = BoardGeometry.center(next.coords(), level).sub(BoardGeometry.center(tile.coords(), level));
            float half = along.len() / 2;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            var gate = new Vector3(center).mulAdd(along, half);
            float reach = BoardRelief.metres(1);
            // The widened terminal blocks also need firm bank underneath. Loose rocks are not foundations.
            for (; reach < half * .9f; reach += BoardRelief.metres(.25f)) {
                boolean supported = true;
                for (int i = -4; i <= 4; i++) {
                    var p = new Vector3(gate).mulAdd(along, reach)
                          .mulAdd(across, (width + BoardRelief.metres(BLOCK_OUTSET_METRES)) * i / 4);
                    supported &= bank.relief.clearance(p.x, p.y) >= BoardRelief.metres(.5f)
                          && Float.isFinite(BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN));
                }
                if (supported) { break; }
            }
            float supportedAt = reach;
            boolean bare = !BoardBridge.road(tile, next, d);
            if (bare) { bareExits |= 1 << d; }
            reach += BoardRelief.metres(RAIL_TAPER_METRES);
            lengths.add(reach / scale);
            int edge = Math.floorMod(1 - d, 6);
            var corner = BoardGeometry.corner(tile.coords(), level, edge);
            var edgeVector = BoardGeometry.corner(tile.coords(), level, edge + 1).sub(corner);
            var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
            float[] offsets = { -width, -lane, lane, width };
            Vector3[] start = new Vector3[4], end = new Vector3[4];
            for (int i = 0; i < offsets.length; i++) {
                start[i] = new Vector3(gate).mulAdd(across, offsets[i]);
                // The board lattice is slightly non-regular: use the GLB's real clipping edge at the join.
                start[i].mulAdd(along, new Vector3(corner).sub(start[i]).dot(normal) / along.dot(normal));
                end[i] = new Vector3(gate).mulAdd(along, reach).mulAdd(across, offsets[i]);
                end[i].z = BoardSurface.sampleHeight(ground, end[i].x, end[i].y, BoardGeometry.groundZ(next))
                      + GpuRoads.SURFACE_LIFT * scale;
            }
            int steps = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 4 : 2;
            var runs = new TreeSet<Float>();
            for (int step = 1; step <= steps; step++) { runs.add(step / (float) steps); }
            // Keep the full-height rail across the void, then replace its end with a separate concrete block.
            float terminal = supportedAt / reach;
            runs.add(terminal);
            var previous = start;
            for (float t : runs) {
                var row = row(start, end, t);
                prism(faces, previous[0], row[0], row[3], previous[3], -1.5f * scale, 0, BoardBridge.Part.TOP);
                if (t <= terminal) {
                    float rail = 2.5f * scale;
                    prism(faces, previous[0], row[0], row[1], previous[1], 0, rail, BoardBridge.Part.STRUCTURE);
                    prism(faces, previous[2], row[2], row[3], previous[3], 0, rail, BoardBridge.Part.STRUCTURE);
                }
                previous = row;
            }
            var stations = new Vector3[][] { row(start, end, terminal),
                  row(start, end, (supportedAt + BoardRelief.metres(.32f)) / reach), end };
            for (int side : new int[] { -1, 1 }) {
                terminal(faces, stations, side, ground, lod);
            }
        }
        return new BoardBridgeFooting(BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces), List.copyOf(lengths), bareExits);
    }

    private static Vector3[] row(Vector3[] start, Vector3[] end, float t) {
        float grade = t * t * (3 - 2 * t);
        var row = new Vector3[start.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = new Vector3(start[i]).lerp(end[i], t);
            row[i].z = start[i].z + (end[i].z - start[i].z) * grade;
        }
        return row;
    }

    /** Raised cap, outward-thickened body and sloping nose; the inner face never enters the carriageway. */
    private static void terminal(List<BoardBridge.Facet> faces, Vector3[][] stations, int side,
          List<BoardSurface.Face> ground, TerrainLod lod) {
        float bevel = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? BoardRelief.metres(.035f) : 0;
        var rings = new ArrayList<Vector3[]>();
        for (int s = 0; s < stations.length; s++) {
            var station = stations[s];
            var inner = station[side < 0 ? 1 : 2];
            var outward = new Vector3(station[side < 0 ? 0 : 3]).sub(inner);
            outward.z = 0;
            float width = outward.len() + BoardRelief.metres(BLOCK_OUTSET_METRES);
            outward.nor();
            float height = s == stations.length - 1 ? BoardRelief.metres(.08f)
                  : 2.5f * BoardGeometry.hexScale() + BoardRelief.metres(.1f);
            float[][] profile = bevel == 0 ? new float[][] { { 0, 0 }, { width, 0 }, { width, height }, { 0, height } }
                  : new float[][] { { 0, 0 }, { width, 0 }, { width, height - bevel },
                        { width - bevel, height }, { bevel, height }, { 0, height - bevel } };
            var ring = new Vector3[profile.length];
            for (int i = 0; i < profile.length; i++) {
                var p = new Vector3(inner).mulAdd(outward, profile[i][0]).add(0, 0, profile[i][1]);
                if (i < 2) {
                    p.z = Math.min(inner.z, BoardSurface.sampleHeight(ground, p.x, p.y, inner.z)) - BoardRelief.metres(.025f);
                }
                ring[side < 0 ? profile.length - 1 - i : i] = p;
            }
            rings.add(ring);
        }
        int n = rings.getFirst().length;
        for (int s = 1; s < rings.size(); s++) {
            var a = rings.get(s - 1);
            var b = rings.get(s);
            for (int i = 0; i < n; i++) {
                int next = (i + 1) % n;
                quad(faces, a[i], a[next], b[next], b[i], BoardBridge.Part.STRUCTURE);
            }
        }
        for (int i = 1; i < n - 1; i++) {
            var first = rings.getFirst();
            var last = rings.getLast();
            BoardBridge.triangle(faces, first[0], first[i + 1], first[i], BoardBridge.Part.STRUCTURE);
            BoardBridge.triangle(faces, last[0], last[i], last[i + 1], BoardBridge.Part.STRUCTURE);
        }
    }

    private static void prism(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          float low, float high, BoardBridge.Part top) {
        var lower = new Vector3[4];
        var upper = new Vector3[4];
        var corners = List.of(a, b, c, d);
        for (int i = 0; i < 4; i++) {
            lower[i] = new Vector3(corners.get(i)).add(0, 0, low);
            upper[i] = new Vector3(corners.get(i)).add(0, 0, high);
        }
        quad(faces, upper[0], upper[1], upper[2], upper[3], top);
        quad(faces, lower[3], lower[2], lower[1], lower[0], BoardBridge.Part.STRUCTURE);
        for (int i = 0; i < 4; i++) {
            int n = (i + 1) % 4;
            quad(faces, upper[i], lower[i], lower[n], upper[n], BoardBridge.Part.STRUCTURE);
        }
    }

    private static void quad(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d, BoardBridge.Part part) {
        BoardBridge.triangle(faces, a, b, c, part);
        BoardBridge.triangle(faces, a, c, d, part);
    }
}
