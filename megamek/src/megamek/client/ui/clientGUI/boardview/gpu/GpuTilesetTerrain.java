/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/**
 * The Tactical View's terrain, as the libGDX board drew it: every hex a plain column at its level, topped by its Saxarba
 * tileset art with the cliff-top rim along each drop and walled in its material down to its lower neighbours or the
 * board's floor. A road meeting a hex of another level ramps through the edge between them, and a bridge's art lies on
 * its deck. Open liquid moves over its art in its animated frames and pours over the drops into the liquid below.
 * {@link GpuTerrain} lights and shadows them with its own batch and light, without the 3D board's sculpting, blending,
 * water or features. Overlays drape on these columns and picks hit them.
 */
final class GpuTilesetTerrain implements Disposable {
    private static final long ATTRIBUTES = Usage.Position | Usage.Normal | Usage.TextureCoordinates;
    private static final long LIQUID_ATTRIBUTES = ATTRIBUTES | Usage.ColorUnpacked;
    /** The old board's material walls: their art repeats every 96 units at hex scale 1. */
    private static final float WALL_REPEAT = 96;
    /** A deck at its hex's own level lies this far above the ground, in hex-scale units, as the libGDX board's did. */
    private static final float DECK_LIFT = .16f;
    /** Liquid moves this far above its art, in hex-scale units; it is whole this far out and fades out to a bank. */
    private static final float LIQUID_LIFT = .05f, LIQUID_WHOLE = .7f;
    /**
     * As the libGDX board's falls: hanging this far clear of the wall, in hex-scale units, their art repeating every 48
     * units down and 24 along at hex scale 1, the lip and the foot turning in four steps each.
     */
    private static final float FALL_CLEARANCE = .06f, FALL_REPEAT = 48, FALL_SPAN = 24;
    private static final int FALL_SEGMENTS = 4;

    /** A section: its columns, its animated liquid apart (it casts no shadow) and that liquid's materials. */
    private record Chunk(ModelInstance instance, ModelInstance liquid, BoundingBox bounds,
          List<GpuTerrain.LiquidSurface> liquids) { }
    private record Top(Coords coords, BoardSurface.Face face) { }
    /** One animated material: a liquid's frames, on its surface moving with its current, or running down its falls. */
    private record Liquid(BoardLiquid liquid, BoardLiquid.Textures source, BoardFlow.Current current, boolean falling) { }

    private final GpuTextures<Coords> art = new GpuTextures<>(true);
    private final GpuTextures<Coords> decks = new GpuTextures<>(true);
    private final BoardRim rims = new BoardRim();
    /** Each hex's faces, its art with the rim shading and its bridge's art, kept until the hex or a neighbour changes. */
    private final Map<Coords, BoardTacticalGeometry.Surface> columns = new HashMap<>();
    private final Map<Coords, BoardScene.Pixels> tops = new HashMap<>();
    private final Map<Coords, BoardScene.Pixels> bridges = new HashMap<>();
    /** Sections of {@link GpuTerrain#CHUNK_SIZE} hexes square, by index; dirty ones are rebuilt before the next draw. */
    private final Map<Integer, Chunk> chunks = new HashMap<>();
    private final Set<Integer> dirty = new HashSet<>();
    private final Set<Coords> stale = new HashSet<>();
    private Map<Coords, BoardFlow.Current> currents = Map.of();
    private List<BoardScene.Tile> tiles;
    private int width;
    private int height;
    private int revision;
    private float floor;

    /** The column's faces: what this view draws, what overlays drape on and what pointer picks hit. */
    BoardTacticalGeometry.Surface surface(BoardScene scene, Coords coords, float floor) {
        sync(scene, floor);
        return columns.computeIfAbsent(coords, key -> column(scene, scene.tile(key), floor));
    }

    /** Rebuilds the sections the scene changed; true when one was. Call outside a batch: it uploads art. */
    boolean update(BoardScene scene, float floor, GpuAssets assets) {
        sync(scene, floor);
        if (dirty.isEmpty()) { return false; }
        rebuild(scene, floor, assets);
        return true;
    }

    /**
     * Submits the sections within the begun batch's camera, lit by {@code environment}. Depth passes have none and
     * leave the liquids out: they cast no shadow.
     */
    void render(ModelBatch batch, Environment environment) {
        for (Chunk chunk : chunks.values()) {
            if (!batch.getCamera().frustum.boundsInFrustum(chunk.bounds())) { continue; }
            batch.render(chunk.instance(), environment);
            if (environment != null && chunk.liquid() != null) { batch.render(chunk.liquid(), environment); }
        }
    }

    /** The sections' animated liquid materials, for {@link GpuTerrain} to move on with its own. */
    List<GpuTerrain.LiquidSurface> liquids() {
        List<GpuTerrain.LiquidSurface> result = new ArrayList<>();
        chunks.values().forEach(chunk -> result.addAll(chunk.liquids()));
        return result;
    }

    /** What the sections span, decks included, for fitting the shadow. */
    BoundingBox bounds() {
        BoundingBox result = new BoundingBox().inf();
        chunks.values().forEach(chunk -> result.ext(chunk.bounds()));
        return result;
    }

    /**
     * Marks what a new snapshot changes: a hex whose column changed and, when what its neighbours read of it changed,
     * those neighbours too: their walls reach down to it, their rims follow its level, their roads ramp toward it and
     * their liquid pours into it. A changed level or liquid can turn a river's current far downstream, which marks
     * that hex's section. Markings, units and 3D detail change no column. A new board, tuning or floor marks every
     * section.
     */
    private void sync(BoardScene scene, float floor) {
        List<BoardScene.Tile> next = scene.tiles();
        if (next == tiles && revision == BoardGeometry.revision() && this.floor == floor) { return; }
        boolean all = tiles == null || width != scene.width() || height != scene.height()
              || revision != BoardGeometry.revision() || this.floor != floor;
        List<BoardScene.Tile> before = tiles;
        tiles = next;
        width = scene.width();
        height = scene.height();
        revision = BoardGeometry.revision();
        this.floor = floor;
        if (all) {
            columns.clear();
            tops.clear();
            bridges.clear();
            disposeChunks();
            for (BoardScene.Tile tile : next) { invalidate(tile.coords()); }
            currents = BoardFlow.calculate(scene);
            return;
        }
        boolean flows = false;
        for (int i = 0; i < next.size(); i++) {
            BoardScene.Tile old = before.get(i), tile = next.get(i);
            if (sameColumn(old, tile)) { continue; }
            flows |= old.elevation() != tile.elevation() || old.frozen() != tile.frozen()
                  || !old.liquid().equals(tile.liquid());
            Coords coords = tile.coords();
            invalidate(coords);
            if (sameEdges(old, tile)) { continue; }
            for (int direction = 0; direction < 6; direction++) {
                Coords neighbor = coords.translated(direction);
                if (scene.tile(neighbor) != null) { invalidate(neighbor); }
            }
        }
        if (flows) {
            Map<Coords, BoardFlow.Current> turned = BoardFlow.calculate(scene);
            Set<Coords> rivers = new HashSet<>(currents.keySet());
            rivers.addAll(turned.keySet());
            for (Coords coords : rivers) {
                if (!Objects.equals(currents.get(coords), turned.get(coords))) { dirty.add(index(coords)); }
            }
            currents = turned;
        }
    }

    private static boolean sameColumn(BoardScene.Tile a, BoardScene.Tile b) {
        return a == b || sameEdges(a, b) && a.surface() == b.surface() && a.cliffTopExits() == b.cliffTopExits()
              && a.waterDepth() == b.waterDepth() && Objects.equals(art(a), art(b)) && Objects.equals(a.bridge(), b.bridge());
    }

    /** What a neighbouring column reads of a hex: its levels, its roads and liquid, and its bridge's deck. */
    private static boolean sameEdges(BoardScene.Tile a, BoardScene.Tile b) {
        return a.elevation() == b.elevation() && BoardGeometry.surfaceZ(a) == BoardGeometry.surfaceZ(b)
              && a.roadExits() == b.roadExits() && a.liquid().equals(b.liquid()) && a.frozen() == b.frozen()
              && Objects.equals(BoardBridge.feature(a), BoardBridge.feature(b));
    }

    private void invalidate(Coords coords) {
        columns.remove(coords);
        stale.add(coords);
        dirty.add(index(coords));
    }

    private int index(Coords coords) {
        int size = GpuTerrain.CHUNK_SIZE;
        return coords.getX() / size * ((height + size - 1) / size) + coords.getY() / size;
    }

    /** Zero-gravity tiles and fixtures without tileset art show their ground. */
    private static BoardScene.Pixels art(BoardScene.Tile tile) {
        return tile.tileset() != null ? tile.tileset() : tile.ground();
    }

    /**
     * A plain column at the hex's surface. Where a road leaves it toward a hex of another level, as {@link BoardSurface}
     * decides, it keeps a hub at its own level and ramps a strip of the road to the edge, to the height both hexes meet
     * at there, between banks; the walls leave that mouth open. Open liquid falls over each edge above the open liquid
     * it joins.
     */
    static BoardTacticalGeometry.Surface column(BoardScene scene, BoardScene.Tile tile, float floor) {
        float top = BoardGeometry.surfaceZ(tile);
        Vector3 center = BoardGeometry.center(tile.coords(), 0);
        center.z = top;
        int ramps = BoardSurface.ramps(scene, tile);
        Vector3[] corners = new Vector3[6], hub = new Vector3[6];
        for (int edge = 0; edge < 6; edge++) {
            corners[edge] = BoardGeometry.corner(tile.coords(), 0, edge);
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
            float across = neighbor == null ? floor : BoardGeometry.surfaceZ(neighbor);
            float roadAcross = neighbor != null && (BoardSurface.ramps(scene, neighbor) & 1 << reverse) != 0
                  ? BoardSurface.roadEdgeElevation(neighbor, tile, reverse) * BoardGeometry.level() : across;
            if (road != top || roadAcross != across) {
                wall(walls, tile, direction, a, left, top, across);
                wall(walls, tile, direction, left, right, road, roadAcross);
                wall(walls, tile, direction, right, b, top, across);
            } else {
                wall(walls, tile, direction, a, b, top, across);
            }
            if (open(tile, neighbor) && neighbor != null && !tile.frozen() && neighbor.elevation() < tile.elevation()) {
                falls.add(new BoardSurface.Side(new Vector3(a), new Vector3(b), across, across, edge));
            }
        }
        return new BoardTacticalGeometry.Surface(List.copyOf(tops), List.of(), List.copyOf(tops), List.of(),
              List.copyOf(walls), List.copyOf(falls));
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
        if (bottom >= top) { return; }
        boolean rock = BoardRim.high(tile, direction, Math.round((top - bottom) / BoardGeometry.level()));
        Vector3 a = new Vector3(from.x, from.y, top), b = new Vector3(to.x, to.y, top);
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

    /** Shades the invalidated tops, updates their atlas slots and rebuilds the dirty sections. */
    private void rebuild(BoardScene scene, float floor, GpuAssets assets) {
        for (Coords coords : stale) {
            BoardScene.Tile tile = scene.tile(coords);
            tops.remove(coords);
            bridges.remove(coords);
            if (art(tile) != null) {
                tops.put(coords, rims.column(scene, tile, art(tile), assets.inclineMask(), assets.highInclineMask()));
            }
            if (tile.bridge() != null && BoardBridge.feature(tile) != null) { bridges.put(coords, tile.bridge()); }
        }
        stale.clear();
        // The rim cache shares identical tops within one update; the kept tops hold their own images.
        rims.clear();
        // A slot that moved, such as after a repack, also changes the UVs of the sections drawing it.
        for (Coords coords : art.updateRegions(tops, Map.of())) { dirty.add(index(coords)); }
        for (Coords coords : decks.updateRegions(bridges, Map.of())) { dirty.add(index(coords)); }
        int size = GpuTerrain.CHUNK_SIZE, rows = (height + size - 1) / size;
        for (int index : dirty) {
            Chunk old = chunks.put(index, chunk(scene, index / rows * size, index % rows * size, floor, assets));
            if (old != null) { dispose(old); }
        }
        dirty.clear();
    }

    private Chunk chunk(BoardScene scene, int startX, int startY, float floor, GpuAssets assets) {
        // ModelBuilder has one open part at a time: collect each material's faces first.
        Map<Texture, List<Top>> topFaces = new LinkedHashMap<>(), deckFaces = new LinkedHashMap<>();
        Map<String, List<BoardSurface.Face>> wallFaces = new LinkedHashMap<>();
        Map<Liquid, List<Consumer<MeshPartBuilder>>> liquidShapes = new LinkedHashMap<>();
        BoundingBox bounds = new BoundingBox().inf();
        int size = GpuTerrain.CHUNK_SIZE;
        for (int x = startX; x < Math.min(scene.width(), startX + size); x++) {
            for (int y = startY; y < Math.min(scene.height(), startY + size); y++) {
                Coords coords = new Coords(x, y);
                BoardScene.Tile tile = scene.tile(coords);
                BoardTacticalGeometry.Surface surface = surface(scene, coords, floor);
                Texture page = tops.containsKey(coords) ? art.region(coords).getTexture() : null;
                for (BoardSurface.Face face : surface.top()) {
                    topFaces.computeIfAbsent(page, key -> new ArrayList<>()).add(new Top(coords, face));
                }
                for (BoardSurface.Face face : surface.walls()) {
                    wallFaces.computeIfAbsent(side(tile.surface(), face.finish()), key -> new ArrayList<>()).add(face);
                }
                if (bridges.containsKey(coords)) {
                    for (BoardSurface.Face face : deck(tile)) {
                        deckFaces.computeIfAbsent(decks.region(coords).getTexture(), key -> new ArrayList<>())
                              .add(new Top(coords, face));
                        bounds.ext(face.a()).ext(face.b()).ext(face.c());
                    }
                }
                if (tile.liquid().present() && !tile.frozen()) {
                    BoardLiquid.Textures source = tile.liquid().textures(tile.waterDepth(), tile.elevation());
                    BoardFlow.Current current = currents.getOrDefault(coords, BoardFlow.Current.STILL);
                    TextureRegion frame = new TextureRegion(assets.liquid(source, 0));
                    liquidShapes.computeIfAbsent(new Liquid(tile.liquid(), source, current, false), key -> new ArrayList<>())
                          .add(mesh -> pool(mesh, scene, tile, frame));
                    for (BoardSurface.Side fall : surface.waterfalls()) {
                        liquidShapes.computeIfAbsent(new Liquid(tile.liquid(), source, BoardFlow.Current.STILL, true),
                              key -> new ArrayList<>()).add(mesh -> fall(mesh, tile, fall));
                    }
                }
                for (BoardSurface.Face face : surface.faces()) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
                for (BoardSurface.Face face : surface.walls()) { bounds.ext(face.a()).ext(face.b()).ext(face.c()); }
            }
        }
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        topFaces.forEach((page, faces) -> addArt(builder, material(page), faces, art));
        wallFaces.forEach((name, faces) -> {
            MeshPartBuilder mesh = builder.part("wall", GL20.GL_TRIANGLES, ATTRIBUTES, material(assets.material(name)));
            faces.forEach(face -> wall(mesh, face));
        });
        deckFaces.forEach((page, faces) -> {
            Material deck = material(page);
            // The art is transparent beside the deck: cut out there, so the deck writes depth only where it lies.
            // The default shader tests alpha only for blended materials.
            deck.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA), FloatAttribute.createAlphaTest(.5f));
            addArt(builder, deck, faces, decks);
        });
        ModelInstance liquid = null;
        List<GpuTerrain.LiquidSurface> liquids = new ArrayList<>();
        if (!liquidShapes.isEmpty()) {
            ModelBuilder pools = new ModelBuilder();
            pools.begin();
            Map<String, Liquid> ids = new HashMap<>();
            liquidShapes.forEach((key, shapes) -> {
                Material material = liquid(key, assets);
                material.id = "liquid" + ids.size();
                ids.put(material.id, key);
                MeshPartBuilder mesh = pools.part("liquid", GL20.GL_TRIANGLES, LIQUID_ATTRIBUTES, material);
                shapes.forEach(shape -> shape.accept(mesh));
            });
            liquid = new ModelInstance(pools.end());
            liquid.extendBoundingBox(bounds);
            // The instance draws copies of the model's materials: those are the ones to animate.
            for (Material material : liquid.materials) {
                Liquid key = ids.get(material.id);
                liquids.add(new GpuTerrain.LiquidSurface(material, key.source(), key.falling(), key.current()));
            }
        }
        return new Chunk(new ModelInstance(builder.end()), liquid, bounds, liquids);
    }

    /** As the libGDX board's: the liquid's animated frames, water translucent, magma opaque and glowing. */
    private static Material liquid(Liquid key, GpuAssets assets) {
        Texture frame = assets.liquid(key.source(), 0);
        Material material = material(frame);
        material.set(new GpuLiquidShader.Frame(frame), new FloatAttribute(GpuLiquidShader.Frame.BLEND, 0));
        if (!key.liquid().molten()) {
            material.set(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA,
                  key.falling() ? .8f : GpuWaterShader.SURFACE_OPACITY));
        }
        GpuTerrain.liquidColour(material, key.liquid(), frame);
        return material;
    }

    /**
     * The liquid's frames over its art, a hair above it: whole over the middle and fading out toward each bank, so the
     * art's own shore shows; it runs on to an edge where the liquid continues.
     */
    private static void pool(MeshPartBuilder mesh, BoardScene scene, BoardScene.Tile tile, TextureRegion frame) {
        Coords coords = tile.coords();
        Vector3 center = BoardGeometry.center(coords, 0);
        center.z = BoardGeometry.surfaceZ(tile) + LIQUID_LIFT * BoardGeometry.hexScale();
        float[] open = new float[6];
        for (int edge = 0; edge < 6; edge++) {
            open[edge] = open(tile, scene.tile(coords.translated(BoardGeometry.edgeDirection(edge)))) ? 1 : 0;
        }
        // Round the outline, corner then edge middle: a corner is open only where both its edges are.
        short middle = mesh.vertex(GpuTerrain.topVertex(center, coords, frame, Color.WHITE));
        short[] whole = new short[12], outline = new short[12];
        for (int i = 0; i < 12; i++) {
            int edge = i / 2;
            Vector3 point = BoardGeometry.corner(coords, 0, edge);
            if (i % 2 == 1) { point.lerp(BoardGeometry.corner(coords, 0, edge + 1), .5f); }
            point.z = center.z;
            float alpha = i % 2 == 1 ? open[edge] : Math.min(open[edge], open[(edge + 5) % 6]);
            outline[i] = mesh.vertex(GpuTerrain.topVertex(point, coords, frame, new Color(1, 1, 1, alpha)));
            whole[i] = mesh.vertex(GpuTerrain.topVertex(new Vector3(center).lerp(point, LIQUID_WHOLE), coords, frame,
                  Color.WHITE));
        }
        for (int i = 0; i < 12; i++) {
            int next = (i + 1) % 12;
            mesh.triangle(middle, whole[i], whole[next]);
            mesh.rect(whole[i], outline[i], outline[next], whole[next]);
        }
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
    private static List<BoardSurface.Face> deck(BoardScene.Tile tile) {
        BoardScene.Feature bridge = BoardBridge.feature(tile);
        float z = (tile.elevation() + bridge.elevation()) * BoardGeometry.level() + DECK_LIFT * BoardGeometry.hexScale();
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

    private static void addArt(ModelBuilder builder, Material material, List<Top> faces, GpuTextures<Coords> atlas) {
        boolean textured = material.has(TextureAttribute.Diffuse);
        MeshPartBuilder mesh = builder.part("art", GL20.GL_TRIANGLES, ATTRIBUTES, material);
        for (Top top : faces) {
            TextureRegion region = textured ? atlas.region(top.coords()) : null;
            BoardSurface.Face face = top.face();
            mesh.triangle(topVertex(face.a(), top.coords(), region), topVertex(face.b(), top.coords(), region),
                  topVertex(face.c(), top.coords(), region));
        }
    }

    private static Material material(Texture texture) {
        Material material = new Material(IntAttribute.createCullFace(GL20.GL_NONE));
        material.set(texture == null ? ColorAttribute.createDiffuse(Color.GRAY) : TextureAttribute.createDiffuse(texture));
        return material;
    }

    /** Tops face straight up, ramps included. */
    private static MeshPartBuilder.VertexInfo topVertex(Vector3 point, Coords coords, TextureRegion region) {
        return region == null ? new MeshPartBuilder.VertexInfo().setPos(point).setNor(Vector3.Z)
              : GpuTerrain.topVertex(point, coords, region, Color.WHITE);
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

    private void disposeChunks() {
        chunks.values().forEach(GpuTilesetTerrain::dispose);
        chunks.clear();
    }

    private static void dispose(Chunk chunk) {
        chunk.instance().model.dispose();
        if (chunk.liquid() != null) { chunk.liquid().model.dispose(); }
    }

    @Override
    public void dispose() {
        disposeChunks();
        art.dispose();
        decks.dispose();
        rims.clear();
    }
}
