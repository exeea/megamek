/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;

/**
 * Concrete ramp blocks and bank landings, with authored entrance wedges seated on the road or actual bank, and the
 * concrete piers under the joints between a deck's hexes.
 */
record BoardBridgeFooting(BoardBridge.Shape shape, List<Float> lengths, int bareExits, int solidExits) {
    static final float APRON_METRES = 7;

    private record Block(BoardShape shape, Vector3 size) { }

    /** Publish the complete immutable kit together; terrain workers retain one snapshot while building. */
    private static final BoardKit<List<Block>> KIT = new BoardKit<>(BoardBridgeFooting::load);

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

    /**
     * The pier kit ({@code bridges/bridge-pier.glb}) at two LODs: a cap that follows the deck's underside, a shaft
     * stretched in Z only (kit Z 0 to 1) and a footing buried in the floor. Kit frame: the origin on the joint, X along
     * the shared edge (across the deck), Y toward the neighbour; Z per part, as each joint places it. Each part is kept
     * as its half on the near side of the joint (Y at most 0), cut once here and closed by its section on the joint
     * ({@link #section}): each hex of a joint builds its own half.
     * The soffit (the cap's bottom), the footing's lip (its top) and its half extents are the parts' own bounds.
     */
    private record Pier(List<List<Vector3[]>> cap, List<List<Vector3[]>> shaft, List<List<Vector3[]>> footing,
          float soffit, float lip, float footX, float footY) { }

    private static final BoardKit<Pier> PIER = new BoardKit<>(BoardBridgeFooting::loadPier);
    /** A pier footing's corners in units of its half extents. */
    private static final float[][] CORNERS = { { -1, -1 }, { 1, -1 }, { 1, 1 }, { -1, 1 } };

    private static Pier loadPier() {
        var shapes = BoardShape.loadKit("bridges/bridge-pier");
        for (String name : shapes.keySet()) {
            if (!name.matches("bridge-pier-(cap|shaft|footing)-lod[01]")) {
                throw new IllegalArgumentException("Unexpected bridge pier mesh: " + name);
            }
        }
        Map<String, BoundingBox> bounds = new HashMap<>();
        var cap = halves(shapes, "bridge-pier-cap", bounds);
        var shaft = halves(shapes, "bridge-pier-shaft", bounds);
        var footing = halves(shapes, "bridge-pier-footing", bounds);
        var length = bounds.get("bridge-pier-shaft");
        if (Math.abs(length.min.z) > .001f || Math.abs(length.max.z - 1) > .001f) {
            throw new IllegalArgumentException("The bridge pier shaft must span Z 0 to 1");
        }
        var foot = bounds.get("bridge-pier-footing");
        return new Pier(cap, shaft, footing, bounds.get("bridge-pier-cap").min.z, foot.max.z, foot.max.x, foot.max.y);
    }

    /** One part's near halves by LOD; records its LOD0 bounds. */
    private static List<List<Vector3[]>> halves(Map<String, BoardShape> shapes, String part, Map<String, BoundingBox> bounds) {
        return MeshLod.load(part, 2, name -> {
            var shape = shapes.get(name);
            if (shape == null) { return null; }
            var box = new BoundingBox().inf();
            shape.polygons().forEach(face -> { for (var p : face.points()) { box.ext(p); } });
            // Both hexes of a joint place this same half: the part must be centred on the joint.
            if (Math.abs(box.min.x + box.max.x) > .001f || Math.abs(box.min.y + box.max.y) > .001f) {
                throw new IllegalArgumentException("Bridge pier parts must be centred on the joint: " + name);
            }
            bounds.putIfAbsent(part, box);
            var near = new BoardSurface.Face(new Vector3(-100, 0, 0), new Vector3(0, -100, 0), new Vector3(100, 0, 0),
                  BoardSurface.Finish.TOP, -1);
            var clipper = new BoardTacticalGeometry.Clipper();
            List<Vector3[]> result = new ArrayList<>();
            for (var face : shape.polygons()) {
                var p = face.points();
                clipper.prepare(new BoardTacticalGeometry.Triangle(p[0], p[1], p[2], -1));
                // A flat face of zero height keeps each vertex's own Z; the cut lands exactly on the joint.
                clipper.displace(near, t -> result.add(new Vector3[] { seam(t.a()), seam(t.b()), seam(t.c()) }));
            }
            section(result);
            return List.copyOf(result);
        });
    }

    /**
     * Closes a half with its section on the joint, facing the neighbour, so it reads as solid from every side, as in
     * the editor's side view, which draws one hex. The parts are convex: the cut's points ring its centroid. In a
     * whole pier the two sections lie back to back inside it.
     */
    private static void section(List<Vector3[]> half) {
        List<Vector3> ring = new ArrayList<>();
        for (var triangle : half) {
            for (var p : triangle) {
                if (p.y == 0 && ring.stream().noneMatch(q -> q.dst2(p) < 1e-8f)) { ring.add(p); }
            }
        }
        if (ring.size() < 3) { return; }
        var centre = new Vector3();
        ring.forEach(centre::add);
        centre.scl(1f / ring.size());
        ring.sort(java.util.Comparator.comparingDouble(p -> Math.atan2(p.z - centre.z, p.x - centre.x)));
        for (int i = 0; i < ring.size(); i++) {
            var a = ring.get((i + 1) % ring.size());
            var b = ring.get(i);
            // Clockwise in X-Z, which faces +Y; a part that is not convex would fold its section over.
            if ((a.z - centre.z) * (b.x - centre.x) - (a.x - centre.x) * (b.z - centre.z) <= 0) {
                throw new IllegalArgumentException("Bridge pier parts must be convex");
            }
            half.add(new Vector3[] { new Vector3(centre), new Vector3(a), new Vector3(b) });
        }
    }

    private static Vector3 seam(Vector3 point) {
        if (Math.abs(point.y) < 1e-4f) { point.y = 0; }
        return point;
    }

    static void reload() { KIT.reload(); PIER.reload(); }

    static float terminalLength() { return KIT.get().getFirst().size().y; }

    /** How far a banked span's terminal block rises above the deck surface. */
    static float terminalHeight() { return KIT.get().getFirst().size().z; }

    /**
     * Whether a pier stands under the joint of the tile's deck toward {@code d}, the edge it shares with the next hex of
     * its deck: the decks are {@link BoardBridge#connected}, the Pillars toggle ({@link HexAppearance#PILLARS}) is on in
     * either hex, no drawn ground road crosses that edge, and the deck is at least one whole level above both floors. The
     * toggle is a built type, so its span is built ({@link BoardBridge#kind}): a rock arch has none. Never at a hex
     * centre, a bank, ramp or road landing, an open or off-board end: a one-hex bridge rests on its landings. Scene data
     * only, and the same from both hexes.
     */
    static boolean pier(BoardScene scene, BoardScene.Tile tile, int d) {
        var next = scene.tile(tile.coords().translated(d));
        if (!BoardBridge.connected(tile, next, d)
              || !HexAppearance.pillars(tile.appearance()) && !HexAppearance.pillars(next.appearance())) { return false; }
        // A road passing beneath the deck keeps its carriageway.
        if (BoardRoad.rendered(tile) && (tile.roadExits() & 1 << d) != 0
              || BoardRoad.rendered(next) && (next.roadExits() & 1 << (d + 3) % 6) != 0) { return false; }
        float room = BoardBridge.edgeElevation(tile, next, d) * BoardGeometry.level()
              - Math.max(BoardGeometry.groundZ(tile), BoardGeometry.groundZ(next));
        return room >= BoardGeometry.level() - .001f * BoardGeometry.hexScale();
    }

    /**
     * {@code shape}, the tile's deck, with the halves of its joints' piers ({@link #pier}) that stand in this hex, cut at
     * the shared edge; the neighbour adds the other half from the same inputs, so the two meet exactly. {@code shape}
     * itself when the tile has no pier.
     */
    static BoardBridge.Shape withPiers(BoardScene scene, BoardScene.Tile tile, BoardBridge.Deck deck, BoardBridge.Shape shape,
          TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        List<BoardBridge.Facet> faces = null;
        List<BoardSurface.Face> under = List.of();
        for (int d = 0; d < 6; d++) {
            if (!pier(scene, tile, d)) { continue; }
            if (faces == null) {
                faces = new ArrayList<>(shape.facets());
                // What the cap follows: the deck's grade above its deck plane.
                under = deck.sloped() ? BoardBridgeSlope.profile(tile, deck) : List.of();
            }
            pier(faces, scene, tile, under, d, lod, surfaces);
        }
        return faces == null ? shape : BoardBridge.shape(shape.surface(), shape.level(), faces);
    }

    /** The outline of the footing of the pier under the tile's joint {@code d} ({@link #pier}), world XY: two triangles. */
    static List<Vector3> pierFootprint(BoardScene.Tile tile, int d) {
        var kit = PIER.get();
        var frame = joint(tile, d);
        var corners = new Vector3[CORNERS.length];
        for (int i = 0; i < corners.length; i++) {
            corners[i] = local(frame, CORNERS[i][0] * kit.footX(), CORNERS[i][1] * kit.footY());
        }
        return List.of(corners[0], corners[1], corners[2], new Vector3(corners[0]), new Vector3(corners[2]), corners[3]);
    }

    /**
     * Appends the half of the pier under joint {@code d} that lies in this tile, in its kit's three frames: the cap's
     * Z 0 is the drawn deck underside above each of its points ({@link #underside}), so the cap follows the slab's
     * grade; the footing's Z 0 is the lowest drawn floor under the footing (a 5 x 5 grid over it, the joint and
     * its corners among them), in either hex (no loose rock; under ice the bed), lowered where needed to keep a 2 px
     * shaft; under a liquid it sinks out of sight, since an uneven bed would break its lip into loose slivers; the
     * shaft spans between them, overlapping each by half a px. The kit scales with the hex, never with the level height.
     */
    private static void pier(List<BoardBridge.Facet> faces, BoardScene scene, BoardScene.Tile tile,
          List<BoardSurface.Face> under, int d, TerrainLod lod, Map<Coords, BoardSurface> surfaces) {
        var kit = PIER.get();
        float s = BoardGeometry.hexScale();
        var next = scene.tile(tile.coords().translated(d));
        var frame = joint(tile, d);
        // The underside at the joint, the same from both hexes.
        float atJoint = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d)) - BoardBridge.SLAB * s;
        var near = surfaces.computeIfAbsent(tile.coords(), c -> new BoardSurface(scene, tile, lod)).foundation();
        var far = surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod)).foundation();
        float floor = atJoint - (kit.lip() - kit.soffit() + 2) * s;
        for (int i = 0; i < 25; i++) {
            var p = local(frame, (i % 5 / 2f - 1) * kit.footX(), (i / 5 / 2f - 1) * kit.footY());
            var owner = BoardGeometry.tile(scene, p.x, p.y);
            if (owner == null) { continue; }
            floor = Math.min(floor, BoardSurface.sampleHeight(owner.coords().equals(next.coords()) ? far : near, p.x, p.y,
                  BoardGeometry.groundZ(owner)));
        }
        // Its top half a px under the lowest floor: never coplanar with a flat bed.
        if (tile.liquid().present() || next.liquid().present()) { floor -= (kit.lip() + .5f) * s; }
        int level = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 0 : 1;
        float bottom = floor + (kit.lip() - .5f) * s, top = (kit.soffit() + .5f) * s;
        var parts = List.of(kit.cap().get(level), kit.shaft().get(level), kit.footing().get(level));
        for (int part = 0; part < parts.size(); part++) {
            for (var triangle : parts.get(part)) {
                var points = new Vector3[3];
                for (int i = 0; i < 3; i++) {
                    var k = triangle[i];
                    var p = local(frame, k.x, k.y);
                    if (part == 2) {
                        p.z = floor + k.z * s;
                    } else {
                        // On the joint the deck's grade is the same from both hexes.
                        float deckUnder = underside(tile, under, p);
                        p.z = part == 0 ? deckUnder + k.z * s : MathUtils.lerp(bottom, deckUnder + top, k.z);
                    }
                    points[i] = p;
                }
                BoardBridge.triangle(faces, points[0], points[1], points[2], BoardBridge.Part.PIER);
            }
        }
    }

    /** The drawn underside of the tile's own deck above {@code p}: its slab on its grade ({@code under}, relative to the deck plane). */
    private static float underside(BoardScene.Tile tile, List<BoardSurface.Face> under, Vector3 p) {
        return BoardBridge.deckZ(tile) + BoardSurface.sampleHeight(under, p.x, p.y, 0) - BoardBridge.SLAB * BoardGeometry.hexScale();
    }

    /**
     * The tile's joint toward {@code d}: the shared edge's midpoint, the unit vector along the edge (kit X) and the one
     * toward the neighbour (kit Y). The edge itself, not the centres' line, orients the pier on the slightly non-regular
     * lattice; both hexes compute the same midpoint and opposite axes.
     */
    private static Vector3[] joint(BoardScene.Tile tile, int d) {
        int edge = Math.floorMod(1 - d, 6);
        var a = BoardGeometry.corner(tile.coords(), 0, edge);
        var b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
        var across = new Vector3(a).sub(b).nor();
        return new Vector3[] { a.add(b).scl(.5f), across, new Vector3(-across.y, across.x, 0) };
    }

    /** A kit point (model px) of a joint, in world XY; its Z is the caller's. */
    private static Vector3 local(Vector3[] frame, float x, float y) {
        float s = BoardGeometry.hexScale();
        return new Vector3(frame[0]).mulAdd(frame[1], x * s).mulAdd(frame[2], y * s);
    }

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
        var block = KIT.get().get(lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 0 : 1);
        var center = BoardGeometry.center(tile.coords(), 0);
        center.z = BoardBridge.deckZ(level);
        var faces = new ArrayList<BoardBridge.Facet>();
        var lengths = new ArrayList<Float>();
        int bareExits = 0, solidExits = 0;
        float lane = BoardRoad.Kind.PAVED.halfWidth * scale, width = lane + BoardRoad.SHOULDER * scale;
        for (int d = 0; d < 6; d++) {
            var next = scene.tile(tile.coords().translated(d));
            if (!BoardBridge.bank(tile, next, d)) { lengths.add(0f); continue; }
            var bank = surfaces.computeIfAbsent(next.coords(), c -> new BoardSurface(scene, next, lod));
            var ground = bank.foundation();
            var along = BoardGeometry.center(next.coords(), level).sub(BoardGeometry.center(tile.coords(), level));
            float half = along.len() / 2;
            along.nor();
            var across = new Vector3(-along.y, along.x, 0);
            var gate = new Vector3(center).mulAdd(along, half);
            gate.z = BoardBridge.deckZ(BoardBridge.edgeElevation(tile, next, d));
            if (BoardBridge.road(tile, next, d) && next.elevation() < level) {
                solidExits |= 1 << d;
                lengths.add((half * .5f + block.size().y * scale) / scale);
                var below = surfaces.computeIfAbsent(tile.coords(), c -> new BoardSurface(scene, tile, lod));
                ramp(faces, tile, next, d, center, gate, along, across, half, block, ground, below.groundFaces());
                continue;
            }
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
            boolean ramp = !bare && next.elevation() != level;
            // A bare bank gets a loose apron (GpuRoads.deckPatches), never over pavement.
            if (bare && next.surface() != BoardScene.Surface.CONCRETE) { bareExits |= 1 << d; }
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
            if (ramp) {
                var below = surfaces.computeIfAbsent(tile.coords(), c -> new BoardSurface(scene, tile, lod));
                float bottom = (float) below.groundFaces().stream()
                      .mapToDouble(f -> Math.min(f.a().z, Math.min(f.b().z, f.c().z))).min().orElse(BoardGeometry.groundZ(tile))
                      - BoardRelief.metres(.05f);
                // The support ends at the bridge's inset, leaving the level centre of the span open below.
                var inner = new Vector3(center).lerp(corner, .5f);
                var left = new Vector3(start[0]);
                var right = new Vector3(start[3]);
                left.mulAdd(along, new Vector3(inner).sub(left).dot(normal) / along.dot(normal));
                right.mulAdd(along, new Vector3(inner).sub(right).dot(normal) / along.dot(normal));
                left.z = right.z = center.z;
                support(faces, left, start[0], start[3], right, bottom, -BoardBridge.SLAB * scale);
            }
            int steps = lod == TerrainLod.FULL || lod == TerrainLod.MEDIUM ? 4 : 2;
            var runs = new TreeSet<Float>();
            for (int step = 1; step <= steps; step++) { runs.add(step / (float) steps); }
            // Keep the full-height rail across the void, then replace its end with a separate concrete block.
            float terminal = supportedAt / reach;
            runs.add(terminal);
            var previous = start;
            for (float t : runs) {
                var row = row(start, end, t, !bare);
                // A graded road already reaches this mouth on the same plane. A second slab and paint coat
                // over that carrier z-fight; only the rails and their terminals need to continue onto the road.
                if (!ramp) {
                    prism(faces, previous[0], row[0], row[3], previous[3], -BoardBridge.SLAB * scale, 0, BoardBridge.Part.TOP);
                }
                if (t <= terminal) {
                    float rail = 2.5f * scale;
                    prism(faces, previous[0], row[0], row[1], previous[1], 0, rail, BoardBridge.Part.STRUCTURE);
                    prism(faces, previous[2], row[2], row[3], previous[3], 0, rail, BoardBridge.Part.STRUCTURE);
                }
                previous = row;
            }
            for (int side : new int[] { -1, 1 }) {
                terminal(faces, block.shape(), start, end, supportedAt, reach, side, ground, !bare);
            }
        }
        return new BoardBridgeFooting(BoardBridge.shape(BoardScene.Surface.CONCRETE, level, faces),
              List.copyOf(lengths), bareExits, solidExits);
    }

    /** One closed concrete block spans both insets. The authored entrance blocks stand on the flat road beyond it. */
    private static void ramp(List<BoardBridge.Facet> faces, BoardScene.Tile tile, BoardScene.Tile road, int direction,
          Vector3 center, Vector3 gate, Vector3 along, Vector3 across, float half, Block block,
          List<BoardSurface.Face> ground, List<BoardSurface.Face> below) {
        float scale = BoardGeometry.hexScale();
        int edge = Math.floorMod(1 - direction, 6);
        var corner = BoardGeometry.corner(tile.coords(), 0, edge);
        var edgeVector = BoardGeometry.corner(tile.coords(), 0, edge + 1).sub(corner);
        var normal = new Vector3(-edgeVector.y, edgeVector.x, 0).nor();
        float lane = BoardRoad.Kind.PAVED.halfWidth * scale, width = lane + BoardRoad.SHOULDER * scale;
        float[] offsets = { -width, -lane, lane, width };
        Vector3[] top = new Vector3[4], base = new Vector3[4], end = new Vector3[4];
        var inner = new Vector3(center).lerp(corner, .5f);
        var foot = new Vector3(inner).mulAdd(along, half);
        float length = block.size().y * scale;
        for (int i = 0; i < offsets.length; i++) {
            top[i] = new Vector3(gate).mulAdd(across, offsets[i]);
            top[i].mulAdd(along, new Vector3(inner).sub(top[i]).dot(normal) / along.dot(normal));
            top[i].z = center.z;
            base[i] = new Vector3(gate).mulAdd(across, offsets[i]);
            base[i].mulAdd(along, new Vector3(foot).sub(base[i]).dot(normal) / along.dot(normal));
            base[i].z = BoardGeometry.groundZ(road) + GpuRoads.SURFACE_LIFT * scale;
            end[i] = new Vector3(base[i]).mulAdd(along, length);
        }
        float bottom = Math.min(BoardGeometry.groundZ(tile), BoardGeometry.groundZ(road));
        for (var surface : List.of(ground, below)) {
            for (var face : surface) { bottom = Math.min(bottom, Math.min(face.a().z, Math.min(face.b().z, face.c().z))); }
        }
        bottom -= BoardRelief.metres(.05f);
        // The slab and retaining sides share the same four corners, with no terrain faces forming the support.
        for (int i = 0; i < 3; i++) {
            quad(faces, top[i], base[i], base[i + 1], top[i + 1], i == 1 ? BoardBridge.Part.TOP : BoardBridge.Part.RIM);
        }
        support(faces, top[0], base[0], base[3], top[3], bottom, 0);
        quad(faces, new Vector3(top[3].x, top[3].y, bottom), new Vector3(base[3].x, base[3].y, bottom),
              new Vector3(base[0].x, base[0].y, bottom), new Vector3(top[0].x, top[0].y, bottom), BoardBridge.Part.STRUCTURE);
        // Only the road half needs new rails; the bridge half retains its authored rails on the same plane.
        var mouth = row(top, base, .5f, true);
        prism(faces, mouth[0], base[0], base[1], mouth[1], 0, 2.5f * scale, BoardBridge.Part.STRUCTURE);
        prism(faces, mouth[2], base[2], base[3], mouth[3], 0, 2.5f * scale, BoardBridge.Part.STRUCTURE);
        prism(faces, base[0], end[0], end[3], base[3], -BoardBridge.SLAB * scale, 0, BoardBridge.Part.TOP);
        for (int side : new int[] { -1, 1 }) {
            terminal(faces, block.shape(), base, end, 0, length, side, ground, true);
        }
    }

    private static Vector3[] row(Vector3[] start, Vector3[] end, float t, boolean road) {
        var row = new Vector3[start.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = point(start[i], end[i], t, road);
        }
        return row;
    }

    private static Vector3 point(Vector3 start, Vector3 end, float t, boolean road) {
        var point = new Vector3(start).lerp(end, t);
        if (!road) { point.z = start.z + (end.z - start.z) * t * t * (3 - 2 * t); }
        return point;
    }

    /** Place the authored block outside the lane, grade it with the slab, and seat only its bottom on the bank. */
    private static void terminal(List<BoardBridge.Facet> faces, BoardShape block, Vector3[] start, Vector3[] end,
          float supportedAt, float reach, int side, List<BoardSurface.Face> ground, boolean road) {
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
                    var p = point(start[inner], end[inner], (supportedAt + source.y * scale) / reach, road)
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

    /** Solid concrete beneath a sloping bridge end, seated below its ground and capped by the existing deck. */
    private static void support(List<BoardBridge.Facet> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          float bottom, float underside) {
        var top = List.of(new Vector3(a).add(0, 0, underside), new Vector3(b).add(0, 0, underside),
              new Vector3(c).add(0, 0, underside), new Vector3(d).add(0, 0, underside));
        for (int i = 0; i < 4; i++) {
            var p = top.get(i);
            var q = top.get((i + 1) % 4);
            quad(faces, p, new Vector3(p.x, p.y, Math.min(bottom, p.z)),
                  new Vector3(q.x, q.y, Math.min(bottom, q.z)), q, BoardBridge.Part.STRUCTURE);
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
