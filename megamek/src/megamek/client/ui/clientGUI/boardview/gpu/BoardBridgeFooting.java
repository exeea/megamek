/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;

/** Short continuations of the authored deck and rails, ending on the actual bank rather than a nominal hex edge. */
record BoardBridgeFooting(BoardBridge.Shape shape, List<Float> lengths, int bareExits) {
    static final float APRON_METRES = 7;

    private record Block(BoardShape shape, Vector3 size) { }

    /** Publish the complete immutable kit together; terrain workers retain one snapshot while building. */
    private static final class Kit {
        static volatile List<Block> blocks = load();

        private static List<Block> load() {
            var shapes = BoardShape.loadKit("bridge-terminal");
            for (String name : shapes.keySet()) {
                if (!name.matches("bridge-terminal-lod[01]")) {
                    throw new IllegalArgumentException("Unexpected bridge terminal mesh: " + name);
                }
            }
            var blocks = MeshLod.load("bridge-terminal", 2, name -> {
                var shape = shapes.get(name);
                if (shape == null) { return null; }
                var bounds = new BoundingBox().inf();
                shape.polygons().forEach(face -> { for (var p : face.points()) { bounds.ext(p); } });
                var size = bounds.getDimensions(new Vector3());
                if (!bounds.min.isZero(.001f) || size.x <= 0 || size.y <= 0) {
                    throw new IllegalArgumentException("Bridge terminal must start at the inner rail/deck origin: " + name);
                }
                return new Block(shape, size);
            });
            if (!blocks.getFirst().size().epsilonEquals(blocks.getLast().size(), .001f)) {
                throw new IllegalArgumentException("Bridge terminal LODs must retain the same bank footprint and height");
            }
            return blocks;
        }
    }

    static void reload() { Kit.blocks = Kit.load(); }

    static float terminalLength() { return Kit.blocks.getFirst().size().y; }

    /** How far a banked span's terminal block rises above the deck surface. */
    static float terminalHeight() { return Kit.blocks.getFirst().size().z; }

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
        var block = Kit.blocks.get(lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 0 : 1);
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
                          .mulAdd(across, (lane + block.size().x * scale) * i / 4);
                    supported &= bank.relief.clearance(p.x, p.y) >= BoardRelief.metres(.5f)
                          && Float.isFinite(BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN));
                }
                if (supported) { break; }
            }
            float supportedAt = reach;
            boolean bare = !BoardBridge.road(tile, next, d);
            if (bare) { bareExits |= 1 << d; }
            reach += block.size().y * scale;
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
            for (int side : new int[] { -1, 1 }) {
                terminal(faces, block.shape(), start, end, supportedAt, reach, side, ground);
            }
        }
        return new BoardBridgeFooting(BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces), List.copyOf(lengths), bareExits);
    }

    private static Vector3[] row(Vector3[] start, Vector3[] end, float t) {
        var row = new Vector3[start.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = point(start[i], end[i], t);
        }
        return row;
    }

    private static Vector3 point(Vector3 start, Vector3 end, float t) {
        var point = new Vector3(start).lerp(end, t);
        point.z = start.z + (end.z - start.z) * t * t * (3 - 2 * t);
        return point;
    }

    /** Place the authored block outside the lane, grade it with the slab, and seat only its bottom on the bank. */
    private static void terminal(List<BoardBridge.Facet> faces, BoardShape block, Vector3[] start, Vector3[] end,
          float supportedAt, float reach, int side, List<BoardSurface.Face> ground) {
        float scale = BoardGeometry.hexScale();
        int inner = side < 0 ? 1 : 2;
        var outward = new Vector3(end[side < 0 ? 0 : 3]).sub(end[inner]);
        outward.z = 0;
        outward.nor();
        var placed = new IdentityHashMap<Vector3, Vector3>();
        for (var face : block.polygons()) {
            var points = new Vector3[3];
            for (int i = 0; i < 3; i++) {
                points[i] = placed.computeIfAbsent(face.points()[i], source -> {
                    var p = point(start[inner], end[inner], (supportedAt + source.y * scale) / reach)
                          .mulAdd(outward, source.x * scale);
                    p.z = Math.abs(source.z) < .001f
                          ? Math.min(p.z, BoardSurface.sampleHeight(ground, p.x, p.y, p.z)) - BoardRelief.metres(.025f)
                          : p.z + source.z * scale;
                    return p;
                });
            }
            // Mirroring to the opposite rail must preserve outward-facing triangles.
            BoardBridge.triangle(faces, points[0], points[side < 0 ? 1 : 2], points[side < 0 ? 2 : 1], BoardBridge.Part.STRUCTURE);
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
