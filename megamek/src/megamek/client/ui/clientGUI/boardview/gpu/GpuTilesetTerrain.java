/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.EarClippingTriangulator;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.common.board.Coords;

/**
 * The Classic graphics' terrain, as the libGDX board drew it: every hex a plain column at its
 * level, topped by its Saxarba tileset art with the cliff-top rim along each drop and walled in its material down to
 * its lower neighbours or the board's floor. A road meeting a hex of another level ramps through the edge between them,
 * and a bridge's art lies on its deck. Open liquid moves in its animated frames above a recessed bed and pours into the
 * liquid below. Natural shores round into land-textured banks and sandy shallows; concrete quays keep their fitted
 * vertical edges. This class holds the geometry only: {@link GpuTerrain} builds a Classic chunk from these columns
 * ({@link #write}) on its terrain workers, and draws, shadows, picks and drapes them as it does its 3D relief.
 * Concrete outlines and adjacent water share the fitted corners of {@link BoardConcrete} with the sculpted view.
 */
final class GpuTilesetTerrain {
    /** The old board's material walls: their art repeats every 96 units at hex scale 1. */
    private static final float WALL_REPEAT = 96;
    /** A deck at its hex's own level lies this far above the ground, in hex-scale units, as the libGDX board's did. */
    private static final float DECK_LIFT = .16f;
    /** Liquid moves this far above the surface, in hex-scale units. */
    private static final float LIQUID_LIFT = .05f;
    /** Rounded natural banks from the libGDX board; open mouths and fitted quays retain their full width. */
    private static final int SHORE_SEGMENTS = 6;
    private static final float SHORE_WIDTH = 8, SHORE_FADE = 5, SUBMERGED_BANK = 6;
    /**
     * As the libGDX board's falls: hanging this far clear of the wall, in hex-scale units, their art repeating every 48
     * units down and 24 along at hex scale 1, the lip and the foot turning in four steps each.
     */
    private static final float FALL_CLEARANCE = .06f, FALL_REPEAT = 48, FALL_SPAN = 24;
    private static final int FALL_SEGMENTS = 4;

    /** One animated material: a liquid's frames, on its surface moving with its current, or running down its falls. */
    private record Liquid(BoardLiquid liquid, BoardLiquid.Textures source, BoardFlow.Current current, boolean falling) { }

    private GpuTilesetTerrain() { }

    /**
     * Writes the hex's column {@code surface} into a chunk: its art tops, banks, shallows, walls and bridge deck into
     * {@code opaque}, which casts and receives shadows, and its open liquid into {@code liquid}, molten liquid into
     * {@code opaque} as the 3D relief does, each liquid material with its frames in {@code animations} by its id.
     * {@code art} gives a hex's column art (null without any), {@code deck} the bridge art, or null. Grows
     * {@code bounds} by everything written. CPU only: the shapes are emitted on a terrain worker.
     */
    static void write(BoardScene.Tile tile, BoardTacticalGeometry.Surface surface, BoardConcrete concrete,
          BoardFlow.Current current, Function<Coords, TextureRegion> art, TextureRegion deck, GpuAssets assets,
          BiConsumer<Material, Consumer<MeshPartBuilder>> opaque, BiConsumer<Material, Consumer<MeshPartBuilder>> liquid,
          Map<String, GpuTerrain.LiquidSurface> animations, BoundingBox bounds) {
        Coords coords = tile.coords();
        String rock = tile.liquid().molten() ? "terrain/rock" : null;
        for (BoardSurface.Face face : surface.faces()) {
            bounds.ext(face.a()).ext(face.b()).ext(face.c());
            if (face.finish() == BoardSurface.Finish.BANK || face.finish() == BoardSurface.Finish.BED) {
                opaque.accept(material(assets.material(rock != null ? rock : "terrain/water_bed")),
                      mesh -> bank(mesh, tile, face, false));
                continue;
            }
            Coords source = face.landEdge() < 0 ? coords : coords.translated(BoardGeometry.edgeDirection(face.landEdge()));
            TextureRegion region = art.apply(source);
            opaque.accept(material(region == null ? null : region.getTexture()),
                  mesh -> artTriangle(mesh, coords, face, region, concrete));
            if (face.finish() == BoardSurface.Finish.SHORE) {
                Material sand = material(assets.material(rock != null ? rock : "terrain/sand"));
                sand.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA),
                      new DepthTestAttribute(GL20.GL_LEQUAL, false));
                opaque.accept(sand, mesh -> bank(mesh, tile, face, true));
            }
        }
        for (BoardSurface.Face face : surface.top()) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
        for (BoardSurface.Face face : surface.walls()) {
            bounds.ext(face.a()).ext(face.b()).ext(face.c());
            opaque.accept(material(assets.material(side(tile.surface(), face.finish()))), mesh -> wall(mesh, face));
        }
        if (deck != null) {
            Material material = material(deck.getTexture());
            // The art is transparent beside the deck: cut out there, so the deck writes depth only where it lies.
            // The default shader tests alpha only for blended materials.
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA), FloatAttribute.createAlphaTest(.5f));
            for (BoardSurface.Face face : deck(tile)) {
                bounds.ext(face.a()).ext(face.b()).ext(face.c());
                opaque.accept(material, mesh -> artTriangle(mesh, coords, face, deck, null));
            }
        }
        if (tile.liquid().present() && !tile.frozen()) {
            BoardLiquid.Textures source = tile.liquid().textures(tile.waterDepth(), tile.elevation());
            var layer = tile.liquid().molten() ? opaque : liquid;
            TextureRegion frame = new TextureRegion(assets.liquid(source, 0));
            Material pool = liquid(new Liquid(tile.liquid(), source, current, false), assets, animations);
            layer.accept(pool, mesh -> pool(mesh, tile, surface.water(), frame, concrete));
            if (!surface.waterfalls().isEmpty()) {
                Material fall = liquid(new Liquid(tile.liquid(), source, BoardFlow.Current.STILL, true), assets, animations);
                for (BoardSurface.Side side : surface.waterfalls()) { layer.accept(fall, mesh -> fall(mesh, tile, side)); }
            }
        }
    }

    /** Zero-gravity tiles and fixtures without tileset art show their ground. */
    static BoardScene.Pixels art(BoardScene.Tile tile) {
        return tile.tileset() != null ? tile.tileset() : tile.ground();
    }

    /** Tactical columns retain the authored pit artwork at its game level, without the sculpted view's recess. */
    private static float surfaceZ(BoardScene.Tile tile) {
        return tile.ultraSublevel() ? tile.elevation() * BoardGeometry.level() : BoardGeometry.surfaceZ(tile);
    }

    private static float solidZ(BoardScene.Tile tile) {
        return tile.liquid().present() && !tile.frozen() ? BoardGeometry.groundZ(tile) : surfaceZ(tile);
    }

    private static boolean naturalBank(BoardScene.Tile tile, BoardScene.Tile neighbor) {
        return tile.liquid().present() && !tile.frozen() && neighbor != null && !neighbor.liquid().present()
              && neighbor.surface() != BoardScene.Surface.CONCRETE;
    }

    /**
     * A plain column at the hex's surface. Where a road leaves it toward a hex of another level, as {@link BoardSurface}
     * decides, it keeps a hub at its own level and ramps a strip of the road to the edge, to the height both hexes meet
     * at there, between banks; the walls leave that mouth open. Open liquid falls over each edge above the open liquid
     * it joins.
     */
    static BoardTacticalGeometry.Surface column(BoardScene scene, BoardScene.Tile tile, float floor) {
        float top = surfaceZ(tile), solidTop = solidZ(tile);
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        center.z = top;
        int ramps = BoardSurface.ramps(scene, tile);
        BoardConcrete concrete = BoardConcrete.of(scene);
        Vector3[] corners = new Vector3[6], hub = new Vector3[6];
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = concrete.corner(tile.coords(), edge);
            corners[edge].z = top;
            hub[edge] = ramps == 0 ? corners[edge] : new Vector3(corners[edge]).lerp(center, .5f);
        }
        List<BoardSurface.Face> tops = new ArrayList<>(), walls = new ArrayList<>();
        List<BoardSurface.Side> falls = new ArrayList<>();
        for (int edge = 0; edge < 6; edge++) { triangle(tops, center, hub[edge], hub[(edge + 1) % 6], BoardSurface.Finish.TOP); }
        for (int edge = 0; edge < 6; edge++) {
            int next = (edge + 1) % 6, direction = BoardGeometry.edgeDirection(edge), reverse = (direction + 3) % 6;
            BoardScene.Tile neighbor = scene.tile(tile.coords().translated(direction));
            Vector3 a = corners[edge], b = corners[next];
            float mouth = BoardSurface.ROAD_MOUTH * BoardGeometry.hexScale() / a.dst(b);
            Vector3 left = new Vector3(a).lerp(b, .5f - mouth), right = new Vector3(a).lerp(b, .5f + mouth);
            float road = top;
            if ((ramps & 1 << direction) != 0) {
                road = BoardSurface.roadEdgeElevation(tile, neighbor, direction) * BoardGeometry.level();
                Vector3 innerLeft = new Vector3(hub[edge]).lerp(hub[next], .5f - 2 * mouth);
                Vector3 innerRight = new Vector3(hub[edge]).lerp(hub[next], .5f + 2 * mouth);
                Vector3 roadLeft = new Vector3(left.x, left.y, road), roadRight = new Vector3(right.x, right.y, road);
                quad(tops, hub[edge], a, left, innerLeft, BoardSurface.Finish.TOP);
                quad(tops, innerRight, right, b, hub[next], BoardSurface.Finish.TOP);
                quad(tops, innerLeft, roadLeft, roadRight, innerRight, BoardSurface.Finish.TOP);
                triangle(walls, innerLeft, left, roadLeft, BoardSurface.Finish.BANK);
                triangle(walls, innerRight, roadRight, right, BoardSurface.Finish.BANK);
            } else if (ramps != 0) {
                quad(tops, hub[edge], a, b, hub[next], BoardSurface.Finish.TOP);
            }
            float across = neighbor == null ? floor : surfaceZ(neighbor);
            float solidAcross = neighbor == null ? floor : solidZ(neighbor);
            float roadTop = road == top ? solidTop : road;
            float roadAcross = neighbor != null && (BoardSurface.ramps(scene, neighbor) & 1 << reverse) != 0
                  ? BoardSurface.roadEdgeElevation(neighbor, tile, reverse) * BoardGeometry.level() : solidAcross;
            // Natural shores supply their own raised rim and wall, including the taper at river mouths.
            if (!naturalBank(tile, neighbor)) {
                if (roadTop != solidTop || roadAcross != solidAcross) {
                    wall(walls, tile, direction, a, left, solidTop, solidAcross);
                    wall(walls, tile, direction, left, right, roadTop, roadAcross);
                    wall(walls, tile, direction, right, b, solidTop, solidAcross);
                } else {
                    wall(walls, tile, direction, a, b, solidTop, solidAcross);
                }
            }
            if (open(tile, neighbor) && neighbor != null && !tile.frozen() && neighbor.elevation() < tile.elevation()) {
                falls.add(new BoardSurface.Side(new Vector3(a), new Vector3(b), across, across, edge));
            }
        }
        boolean openWater = tile.liquid().present() && !tile.frozen();
        List<BoardSurface.Face> water = openWater ? new ArrayList<>(tops) : List.of();
        List<BoardSurface.Face> solid = tops;
        if (openWater) {
            solid = new ArrayList<>();
            for (var face : tops) {
                solid.add(new BoardSurface.Face(new Vector3(face.a().x, face.a().y, solidTop),
                      new Vector3(face.b().x, face.b().y, solidTop), new Vector3(face.c().x, face.c().y, solidTop),
                      BoardSurface.Finish.BED));
            }
            shore(scene, tile, corners, tops, solid, water, walls);
        }
        return new BoardTacticalGeometry.Surface(List.copyOf(tops), List.of(), List.copyOf(solid),
              List.copyOf(water),
              List.copyOf(walls), List.copyOf(falls));
    }

    /** One finished shoreline supplies the drawn liquid, dry banks, submerged slopes, picking and overlay support. */
    private static void shore(BoardScene scene, BoardScene.Tile tile, Vector3[] corners, List<BoardSurface.Face> tops,
          List<BoardSurface.Face> solid, List<BoardSurface.Face> water, List<BoardSurface.Face> walls) {
        float scale = BoardGeometry.hexScale(), level = tile.elevation() * BoardGeometry.level();
        float[] inset = new float[6];
        boolean banks = false;
        for (int edge = 0; edge < 6; edge++) {
            if (naturalBank(tile, scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge))))) {
                inset[edge] = SHORE_WIDTH * scale;
                banks = true;
            }
        }
        if (!banks) { return; }
        Vector3[] line = shoreline(tile, corners, inset, BoardGeometry.waterZ(tile));
        for (int edge = 0; edge < 6; edge++) { if (inset[edge] > 0) { inset[edge] += SUBMERGED_BANK * scale; } }
        Vector3[] bed = shoreline(tile, corners, inset, BoardGeometry.groundZ(tile));
        tops.clear();
        water.clear();
        float[] xy = new float[line.length * 2];
        for (int i = 0; i < line.length; i++) { xy[2 * i] = line[i].x; xy[2 * i + 1] = line[i].y; }
        var indices = new EarClippingTriangulator().computeTriangles(xy);
        for (int i = 0; i < indices.size; i += 3) {
            triangle(water, line[indices.get(i)], line[indices.get(i + 2)], line[indices.get(i + 1)], BoardSurface.Finish.TOP);
        }
        for (int edge = 0; edge < 6; edge++) {
            if (inset[edge] == 0) { continue; }
            int direction = BoardGeometry.edgeDirection(edge);
            BoardScene.Tile land = scene.tile(tile.coords().translated(direction));
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                int i = edge * SHORE_SEGMENTS + segment, j = (i + 1) % line.length;
                Vector3 a = bankEdge(corners, inset, edge, segment / (float) SHORE_SEGMENTS, level);
                Vector3 b = bankEdge(corners, inset, edge, (segment + 1f) / SHORE_SEGMENTS, level);
                Vector3 lipA = shoreLip(line[i], a), lipB = shoreLip(line[j], b);
                List<BoardSurface.Face> bank = new ArrayList<>();
                quad(bank, a, b, lipB, lipA, BoardSurface.Finish.TOP);
                quad(bank, lipA, lipB, line[j], line[i], BoardSurface.Finish.SHORE);
                for (var face : bank) {
                    var finished = new BoardSurface.Face(face.a(), face.b(), face.c(), face.finish(), edge);
                    tops.add(finished);
                    solid.add(finished);
                }
                quad(solid, line[i], line[j], bed[j], bed[i], BoardSurface.Finish.BANK);
                wall(walls, tile, direction, a, b, solidZ(land));
            }
        }
        tops.addAll(water);
    }

    /** The rim tapers to the unchanged water level at an open mouth, leaving no raised seam across the river. */
    private static Vector3 bankEdge(Vector3[] corners, float[] inset, int edge, float t, float level) {
        float start = inset[(edge + 5) % 6] > 0 ? 1 : Math.min(1, 3 * t);
        float end = inset[(edge + 1) % 6] > 0 ? 1 : Math.min(1, 3 * (1 - t));
        Vector3 point = new Vector3(corners[edge]).lerp(corners[(edge + 1) % 6], t);
        point.z = level - BoardGeometry.hexScale() * (1 - start * end);
        return point;
    }

    private static Vector3 shoreLip(Vector3 water, Vector3 boundary) {
        float distance = Vector3.dst(water.x, water.y, 0, boundary.x, boundary.y, 0);
        Vector3 point = new Vector3(water).lerp(boundary,
              distance == 0 ? 1 : Math.min(1, SHORE_FADE * BoardGeometry.hexScale() / distance));
        point.z = boundary.z;
        return point;
    }

    /** Offset closed corners, then round the bank with cubic curves. Shared open edges keep their exact endpoints. */
    private static Vector3[] shoreline(BoardScene.Tile tile, Vector3[] corners, float[] inset, float z) {
        Vector3[] anchors = new Vector3[6];
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6;
            Vector3 corner = corners[edge];
            Vector3 n1 = new Vector3(corner).sub(corners[previous]).crs(Vector3.Z).nor();
            Vector3 n2 = new Vector3(corners[(edge + 1) % 6]).sub(corner).crs(Vector3.Z).nor();
            float determinant = n1.x * n2.y - n1.y * n2.x;
            anchors[edge] = new Vector3(corner);
            if (inset[previous] > 0 && inset[edge] > 0 && Math.abs(determinant) > .0001f) {
                // Solve relative to the corner so the same shape is stable on large boards too.
                anchors[edge].add((n1.y * inset[edge] - inset[previous] * n2.y) / determinant,
                      (inset[previous] * n2.x - n1.x * inset[edge]) / determinant, 0);
            }
            anchors[edge].z = z;
        }
        Vector3[] result = new Vector3[6 * SHORE_SEGMENTS];
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        for (int edge = 0; edge < 6; edge++) {
            int previous = (edge + 5) % 6, next = (edge + 1) % 6;
            Vector3 a = anchors[edge], b = anchors[next];
            Vector3 c1 = new Vector3(a).mulAdd(new Vector3(b).sub(anchors[previous]), 1f / 6);
            Vector3 c2 = new Vector3(b).mulAdd(new Vector3(anchors[(edge + 2) % 6]).sub(a), -1f / 6);
            // Mouth endpoints stay fixed, but the submerged curve must still recede below a lone bank edge.
            float handle = a.dst(b) * inset[edge] / (3 * SHORE_WIDTH * BoardGeometry.hexScale());
            if (inset[previous] == 0) {
                c1.set(a).mulAdd(new Vector3(corners[edge]).sub(corners[previous]).crs(Vector3.Z).nor(), -handle);
            }
            if (inset[next] == 0) {
                c2.set(b).mulAdd(new Vector3(corners[(edge + 2) % 6]).sub(corners[next]).crs(Vector3.Z).nor(), -handle);
            }
            float phase = tile.coords().getX() * 1.73f + tile.coords().getY() * 2.41f + edge;
            for (int segment = 0; segment < SHORE_SEGMENTS; segment++) {
                float t = segment / (float) SHORE_SEGMENTS, s = 1 - t;
                Vector3 point = new Vector3(a).lerp(b, t);
                if (inset[edge] > 0) {
                    point.set(a).scl(s * s * s).mulAdd(c1, 3 * s * s * t)
                          .mulAdd(c2, 3 * s * t * t).mulAdd(b, t * t * t);
                    float ripple = (float) Math.sin(phase + t * Math.PI * 3) * 16 * t * t * s * s;
                    point.mulAdd(new Vector3(center.x - point.x, center.y - point.y, 0).nor(), ripple * 1.4f * BoardGeometry.hexScale());
                }
                point.z = z;
                result[edge * SHORE_SEGMENTS + segment] = point;
            }
        }
        return result;
    }

    /** Whether a liquid runs on across an edge: into the open liquid it joins, or off the board. */
    private static boolean open(BoardScene.Tile tile, BoardScene.Tile neighbor) {
        return neighbor == null || !neighbor.frozen() && tile.liquid().connects(neighbor.liquid());
    }

    /**
     * One stretch of an edge, from the column's height there down to what lies across it: an earth wall up to an
     * incline, a rock outcrop beyond, split as the rim splits them.
     */
    private static void wall(List<BoardSurface.Face> walls, BoardScene.Tile tile, int direction, Vector3 from, Vector3 to,
          float top, float bottom) {
        Vector3 a = new Vector3(from.x, from.y, top), b = new Vector3(to.x, to.y, top);
        wall(walls, tile, direction, a, b, bottom);
    }

    private static void wall(List<BoardSurface.Face> walls, BoardScene.Tile tile, int direction, Vector3 a, Vector3 b,
          float bottom) {
        float top = Math.max(a.z, b.z);
        if (bottom >= top) { return; }
        boolean rock = BoardRim.high(tile, direction, Math.round((top - bottom) / BoardGeometry.level()));
        quad(walls, a, new Vector3(a.x, a.y, bottom), new Vector3(b.x, b.y, bottom), b,
              rock ? BoardSurface.Finish.OUTCROP : BoardSurface.Finish.WALL);
    }

    /** Grassland shows its dirt on an incline's side and its rock on a cliff's; other ground keeps its own material. */
    private static String side(BoardScene.Surface surface, BoardSurface.Finish finish) {
        return surface == BoardScene.Surface.GRASS && finish != BoardSurface.Finish.OUTCROP
              ? BoardScene.Surface.DIRT.wall : surface.wall;
    }

    private static void quad(List<BoardSurface.Face> faces, Vector3 a, Vector3 b, Vector3 c, Vector3 d,
          BoardSurface.Finish finish) {
        triangle(faces, a, b, c, finish);
        triangle(faces, a, c, d, finish);
    }

    private static void triangle(List<BoardSurface.Face> faces, Vector3 a, Vector3 b, Vector3 c, BoardSurface.Finish finish) {
        if (new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len2() > 1e-6f) {
            faces.add(new BoardSurface.Face(a, b, c, finish));
        }
    }

    /**
     * As the libGDX board's: the liquid's animated frames, water translucent, magma opaque and glowing. Equal liquids
     * share one material, by its id, and its frames are registered in {@code animations} under that id.
     */
    private static Material liquid(Liquid key, GpuAssets assets, Map<String, GpuTerrain.LiquidSurface> animations) {
        Texture frame = assets.liquid(key.source(), 0);
        Material material = material(frame);
        material.id = "column-liquid:" + key;
        material.set(new GpuLiquidShader.Frame(frame), new FloatAttribute(GpuLiquidShader.Frame.BLEND, 0));
        if (!key.liquid().molten()) {
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  key.falling() ? .8f : GpuWaterShader.SURFACE_OPACITY));
            material.set(new DepthTestAttribute(GL20.GL_LEQUAL, false));
        }
        GpuTerrain.liquidColour(material, key.liquid(), frame);
        animations.putIfAbsent(material.id, new GpuTerrain.LiquidSurface(material, key.source(), key.falling(), key.current()));
        return material;
    }

    /** Draw precisely the water footprint used by picking; only its small depth clearance is visual. */
    private static void pool(MeshPartBuilder mesh, BoardScene.Tile tile, List<BoardSurface.Face> faces,
          TextureRegion frame, BoardConcrete concrete) {
        float lift = LIQUID_LIFT * BoardGeometry.hexScale();
        for (var face : faces) {
            mesh.triangle(topVertex(new Vector3(face.a()).add(0, 0, lift), tile.coords(), frame, concrete).setCol(Color.WHITE),
                  topVertex(new Vector3(face.b()).add(0, 0, lift), tile.coords(), frame, concrete).setCol(Color.WHITE),
                  topVertex(new Vector3(face.c()).add(0, 0, lift), tile.coords(), frame, concrete).setCol(Color.WHITE));
        }
    }

    /** The old branch's sandy fade over neighboring land art, continuing into the submerged bank and lakebed. */
    private static void bank(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface.Face face, boolean fade) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        float scale = BoardGeometry.hexScale(), repeat = WALL_REPEAT * scale;
        MeshPartBuilder.VertexInfo[] vertices = new MeshPartBuilder.VertexInfo[3];
        Vector3[] points = { face.a(), face.b(), face.c() };
        for (int i = 0; i < points.length; i++) {
            Vector3 point = points[i];
            float wet = fade ? Math.clamp((tile.elevation() * BoardGeometry.level() - point.z) / scale, 0, 1) : 1;
            float shade = fade ? 1 - wet * .12f : 1;
            vertices[i] = new MeshPartBuilder.VertexInfo().setPos(new Vector3(point).add(0, 0, fade ? .035f * scale : 0))
                  .setNor(normal).setUV(point.x / repeat, point.y / repeat).setCol(shade, shade, shade, wet);
        }
        mesh.triangle(vertices[0], vertices[1], vertices[2]);
    }

    /**
     * As the libGDX board's fall, from the column's edge: the liquid curves over the lip, hangs just clear of the wall
     * and spreads into the liquid it lands in. Its art scrolls down with the clock.
     */
    private static void fall(MeshPartBuilder mesh, BoardScene.Tile tile, BoardSurface.Side side) {
        float scale = BoardGeometry.hexScale(), lift = LIQUID_LIFT * scale, clearance = FALL_CLEARANCE * scale;
        float top = side.a().z + lift, low = side.lowA() + lift;
        float foot = Math.min(.5f * BoardSurface.fallLip(top, low), top - clearance - low);
        Vector3 outward = new Vector3(side.a()).add(side.b()).scl(.5f).sub(BoardGeometry.center(tile.coords(), 0));
        outward.z = 0;
        outward.nor();
        // Each step of the profile, from the edge: over the lip a quarter turn down, then a quarter turn out at the foot.
        List<Vector3> steps = new ArrayList<>(), normals = new ArrayList<>();
        for (int i = 0; i <= FALL_SEGMENTS; i++) {
            Vector3 normal = arc(outward, MathUtils.HALF_PI * i / FALL_SEGMENTS);
            steps.add(new Vector3(normal).scl(clearance).add(0, 0, top - clearance));
            normals.add(normal);
        }
        for (int i = FALL_SEGMENTS; i >= 0; i--) {
            Vector3 normal = arc(outward, MathUtils.HALF_PI * i / FALL_SEGMENTS);
            steps.add(new Vector3(outward).scl(clearance + foot).mulAdd(normal, -foot).add(0, 0, low + foot));
            normals.add(normal);
        }
        float along = side.a().dst(side.b()) / (FALL_SPAN * scale), repeat = FALL_REPEAT * scale;
        for (int i = 0; i + 1 < steps.size(); i++) {
            mesh.rect(fallVertex(side.a(), steps.get(i), normals.get(i), 0, repeat),
                  fallVertex(side.a(), steps.get(i + 1), normals.get(i + 1), 0, repeat),
                  fallVertex(side.b(), steps.get(i + 1), normals.get(i + 1), along, repeat),
                  fallVertex(side.b(), steps.get(i), normals.get(i), along, repeat));
        }
    }

    /** A fall's normal a quarter turn from up (zero) to outward. */
    private static Vector3 arc(Vector3 outward, float angle) {
        return new Vector3(outward).scl(MathUtils.sin(angle)).add(0, 0, MathUtils.cos(angle));
    }

    /** A step of the profile beside an end of the edge; the art's height follows the world's, so it scrolls evenly. */
    private static MeshPartBuilder.VertexInfo fallVertex(Vector3 end, Vector3 step, Vector3 normal, float u, float repeat) {
        Vector3 point = new Vector3(end.x + step.x, end.y + step.y, step.z);
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setUV(u, point.z / repeat).setCol(Color.WHITE);
    }

    /** The bridge's art laid flat over its hex, at its deck's height. */
    static float deckZ(BoardScene.Tile tile) {
        BoardScene.Feature bridge = BoardBridge.feature(tile);
        return (tile.elevation() + bridge.elevation()) * BoardGeometry.level() + DECK_LIFT * BoardGeometry.hexScale();
    }

    /**
     * The Classic bridge's deck as a bridge shape: the plane of its art over the hex, which placed objects, decals and
     * paint on the bridge take there. Never drawn or picked.
     */
    static BoardBridge.Shape deckShape(BoardScene.Tile tile) {
        List<BoardBridge.Facet> facets = new ArrayList<>(6);
        for (BoardSurface.Face face : deck(tile)) {
            facets.add(new BoardBridge.Facet(face.a(), face.b(), face.c(), new Vector3(Vector3.Z), BoardBridge.Part.TOP));
        }
        return BoardBridge.shape(tile.surface(), tile.elevation(), facets);
    }

    private static List<BoardSurface.Face> deck(BoardScene.Tile tile) {
        float z = deckZ(tile);
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        center.z = z;
        List<BoardSurface.Face> faces = new ArrayList<>(6);
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = BoardGeometry.corner(tile.coords(), 0, edge), b = BoardGeometry.corner(tile.coords(), 0, edge + 1);
            a.z = z;
            b.z = z;
            faces.add(new BoardSurface.Face(center, a, b, BoardSurface.Finish.TOP));
        }
        return faces;
    }

    /** A face of the hex's art; a bank borrows its land neighbour's, mirrored across its fitted edge. */
    private static void artTriangle(MeshPartBuilder mesh, Coords coords, BoardSurface.Face face, TextureRegion region,
          BoardConcrete concrete) {
        Coords source = face.landEdge() < 0 ? coords : coords.translated(BoardGeometry.edgeDirection(face.landEdge()));
        mesh.triangle(artVertex(face.a(), coords, face, source, region, concrete),
              artVertex(face.b(), coords, face, source, region, concrete),
              artVertex(face.c(), coords, face, source, region, concrete));
    }

    /** Mirror a bank's artwork across its fitted edge, so quays and natural land keep the same atlas mapping. */
    private static MeshPartBuilder.VertexInfo artVertex(Vector3 point, Coords coords, BoardSurface.Face face, Coords source,
          TextureRegion region, BoardConcrete concrete) {
        if (face.landEdge() < 0) { return topVertex(point, source, region, concrete); }
        Vector3 a = concrete.corner(coords, face.landEdge());
        Vector3 normal = concrete.corner(coords, face.landEdge() + 1).sub(a).crs(Vector3.Z).nor();
        Vector3 sample = new Vector3(point).mulAdd(normal, -2 * new Vector3(point).sub(a).dot(normal));
        Vector3 up = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        return topVertex(sample, source, region, concrete).setPos(point).setNor(up);
    }

    /** One fixed id: equal materials share one draw in their chunk layer. */
    private static Material material(Texture texture) {
        Material material = new Material("column", IntAttribute.createCullFace(GL20.GL_NONE));
        material.set(texture == null ? ColorAttribute.createDiffuse(Color.GRAY) : TextureAttribute.createDiffuse(texture));
        return material;
    }

    /** Tops face straight up, ramps included. */
    private static MeshPartBuilder.VertexInfo topVertex(Vector3 point, Coords coords, TextureRegion region, BoardConcrete concrete) {
        return region == null ? new MeshPartBuilder.VertexInfo().setPos(point).setNor(Vector3.Z).setCol(Color.WHITE)
              : GpuTerrain.topVertex(artPoint(point, coords, concrete), coords, region, Color.WHITE).setPos(point);
    }

    /** Carry the whole hex image onto its fitted fan, including water corners extending beyond the original hex. */
    static Vector3 artPoint(Vector3 point, Coords coords, BoardConcrete concrete) {
        if (concrete == null) { return point; }
        Vector3 center = BoardGeometry.center(coords, 0);
        float x = point.x - center.x, y = point.y - center.y;
        for (int edge = 0; edge < 6; edge++) {
            Vector3 a = concrete.corner(coords, edge).sub(center), b = concrete.corner(coords, edge + 1).sub(center);
            float area = a.x * b.y - a.y * b.x;
            if (Math.abs(area) < .0001f) { continue; }
            float u = (x * b.y - y * b.x) / area, v = (a.x * y - a.y * x) / area;
            if (u < -.0001f || v < -.0001f || u + v > 1.0001f) { continue; }
            Vector3 originalA = BoardGeometry.corner(coords, 0, edge).sub(center);
            Vector3 originalB = BoardGeometry.corner(coords, 0, edge + 1).sub(center);
            return new Vector3(center).mulAdd(originalA, u).mulAdd(originalB, v);
        }
        return point;
    }

    /**
     * Faces outward by its winding: the corners run counter-clockwise, so a wall faces its lower neighbour and a bank
     * whichever of the road and the top beside it lies lower.
     */
    private static void wall(MeshPartBuilder mesh, BoardSurface.Face face) {
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
        normal.z = 0;
        normal.nor();
        float repeat = WALL_REPEAT * BoardGeometry.hexScale();
        mesh.triangle(wallVertex(face.a(), normal, repeat), wallVertex(face.b(), normal, repeat),
              wallVertex(face.c(), normal, repeat));
    }

    /** World-aligned along the wall and up it, so neighbouring walls continue their material's art. */
    private static MeshPartBuilder.VertexInfo wallVertex(Vector3 point, Vector3 normal, float repeat) {
        float along = point.x * -normal.y + point.y * normal.x;
        return new MeshPartBuilder.VertexInfo().setPos(point).setNor(normal).setUV(along / repeat, -point.z / repeat);
    }
}
