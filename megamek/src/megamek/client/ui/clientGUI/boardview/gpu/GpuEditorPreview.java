/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.enums.BuildingType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * The board editor's Paint and Erase preview at the pointer (b10-scope "Paint placement preview" and "Route drawing
 * direction preview"): what the next pointer event would do, as {@link BoardEditorSession#preview} computes it by running
 * that event unseen. Placed objects, stamps' members, decals and route pieces show as ghosts built by the board's own
 * placement and route builders. Drawn roads, rails, walls and bridges use the pending neighbourhood's committed
 * geometry, including changed connections, with the movement destination's blue ghost material. Other terrain cards
 * use tinted stand-ins; Erase shows what goes in red. Hovering rebuilds no installed terrain.
 */
final class GpuEditorPreview implements Disposable {
    /**
     * The preview as the render thread draws it, made on the EDT ({@link #data}): the hex the event lands on, whether it
     * erases, the hexes it changes (while a route is drawn by hand, the hexes of its path), the objects it shows by hex
     * (Paint: each changed hex's objects afterwards; Erase: those that go), the terrain stand-ins, their tint (rgba8888),
     * the label at {@link #at}, route stubs (marked red), and the pending route neighbourhood captured on the EDT.
     */
    record Data(Coords at, boolean erase, List<Coords> hexes, Map<Coords, List<BoardScene.Feature>> objects,
          List<StandIn> standIns, int tint, String label, List<Coords> stubs, List<DeckOutline> decks,
          Map<Coords, BoardScene.Tile> routes, boolean wallRoute) {
        static final Data NONE = new Data(null, false, List.of(), Map.of(), List.of(), 0, "", List.of(), List.of(), Map.of(), false);
    }
    record DeckOutline(Coords at, int level, boolean removed) { }

    /**
     * A terrain card's result in one hex, in levels: a flat top at {@code high}, sides down to {@code low} where it holds
     * a volume (water to its bed, a structure or canopy from the ground), and a centreline to each of {@code exits}.
     */
    record StandIn(Coords coords, float low, float high, int exits) { }

    /** Erase's red: the editor's issue red. */
    private static final Color ERASE = Color.valueOf("FF554C");
    private static final float FILL_OPACITY = .38f, GHOST_OPACITY = .55f, HIDDEN = .4f;
    /** The stand-ins' tops lie this many levels above their level, over the drawn ground's small relief. */
    private static final float LIFT = .06f;
    /**
     * The most object ghosts a preview builds: a hex stamp's objects over a wide brush show by its footprint and
     * stand-ins only, as building a ghost each (a decal's projection, a route's ground) would stall the frame.
     */
    private static final int GHOSTS = 200;
    /** A centreline's half width, in hex widths. */
    private static final float LINE = .02f;
    /** Height fields that stand as volumes: every blueprint height field but a bridge's deck. */
    private static final Set<Integer> VOLUMES = BoardEditorBlueprint.get().components().stream()
          .flatMap(component -> component.fields().stream()).filter(BoardEditorBlueprint.Field::height)
          .map(field -> Terrains.getType(field.terrain())).filter(type -> type != Terrains.BRIDGE_ELEV)
          .collect(Collectors.toUnmodifiableSet());

    /** Submission order: the stand-ins' hidden pass, then their visible pass, then the ghosts. */
    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Editor ghosts", GpuUnitShader::linearDisplayProvider),
          (camera, renderables) -> { });
    private final Environment light = new Environment();
    private final GpuLinearOverlay blend = new GpuLinearOverlay();
    /** What the built geometry shows, for which renderer, and whether that renderer was busy then. */
    private Data built = Data.NONE;
    private GpuTerrain builtFor;
    private boolean builtBusy;
    private BoardScene builtScene;
    private GpuTerrain.EditorRoutes routes;
    private FrameBuffer ghostLayer;
    private SpriteBatch composite;
    private final IntBuffer viewport = BufferUtils.newIntBuffer(4), framebuffer = BufferUtils.newIntBuffer(1);
    private final List<ModelInstance> ghosts = new ArrayList<>();
    /** The models this preview made (route and decal ghosts, the stand-ins), disposed with the next build. */
    private final List<Model> owned = new ArrayList<>();
    private ModelInstance standIns;
    private final List<BoundingBox> outlines = new ArrayList<>();
    private final Vector3 labelPoint = new Vector3();

    GpuEditorPreview() {
        light.set(new ColorAttribute(ColorAttribute.AmbientLight, .65f, .65f, .65f, 1));
        light.add(new DirectionalLight().set(.5f, .5f, .5f, -.4f, .3f, -1));
    }

    /**
     * {@code preview} as the render thread draws it ({@link Data}); {@code capture} makes a hex the tile it would be at its
     * coords (BoardTiles#preview), whose minimap colour ({@link GpuMinimap#colour}) tints Paint's stand-ins. A route drawn by
     * hand is labelled with what it lays ({@code brush} where no terrain names it), its length and the session's notice.
     * On the EDT.
     */
    static Data data(BoardEditorSession.Preview preview, boolean erase, BoardEditorSession.Brush brush, boolean routePaint,
          BiFunction<Coords, Hex, BoardScene.Tile> capture) {
        if (preview.after().isEmpty() && preview.path().isEmpty() && preview.layers().isEmpty()) { return Data.NONE; }
        Set<String> removed = preview.removed().stream().map(BoardEditorSession.Selection::object).collect(Collectors.toSet());
        Map<Coords, List<BoardScene.Feature>> objects = new LinkedHashMap<>();
        List<StandIn> standIns = new ArrayList<>();
        List<DeckOutline> decks = new ArrayList<>();
        Coords labelled = null;
        for (var entry : preview.after().entrySet()) {
            Coords at = entry.getKey();
            Hex after = entry.getValue(), before = preview.before().get(at);
            if (!before.hasBridge() && after.hasBridge() && HexAppearance.implicit(after.getAppearance())) {
                decks.add(new DeckOutline(at, BridgeSpan.deck(after), false));
            } else if (before.hasBridge() && !after.hasBridge()) {
                decks.add(new DeckOutline(at, BridgeSpan.deck(before), true));
            }
            var shown = BoardFeatures.decorations(after, at);
            var was = BoardFeatures.decorations(before, at);
            if (!erase && !shown.equals(was)) { objects.put(at, shown); }
            if (erase) {
                // What goes: the removed objects, and what a rail owns (its buffer stops) that goes with its sides.
                var goes = was.stream().filter(feature -> !shown.contains(feature) && (removed.contains(feature.decoration().id())
                      || BoardFeatures.terrainOwned(feature.decoration()))).toList();
                if (!goes.isEmpty()) { objects.put(at, goes); }
            }
            if (!terrain(before).equals(terrain(after))) {
                standIns.add(erase ? standIn(at, before, after) : standIn(at, after, before));
                if (labelled == null || at.equals(preview.at())) { labelled = at; }
            }
        }
        String label = labelled == null ? "" : label(preview.before().get(labelled), preview.after().get(labelled), erase);
        if (erase && !removed.isEmpty()) {
            String count = Messages.getString("BoardEditor.previewObjects", removed.size());
            label = label.isEmpty() ? count : label + ", " + count;
        }
        if (erase && !label.isEmpty()) { label = Messages.getString("BoardEditor.previewRemoves", label); }
        List<Coords> hexes = List.copyOf(preview.after().keySet());
        if (!preview.path().isEmpty()) {
            label = Messages.getString("BoardEditor.previewPath", label.isEmpty() ? brush.label() : label, preview.path().size());
            if (!preview.notice().isEmpty()) { label += " \u00B7 " + preview.notice(); }
            hexes = preview.path().stream().distinct().toList();
        }
        if (preview.path().isEmpty() && !preview.notice().isEmpty()) { label = preview.notice(); }
        List<Coords> stubs = new ArrayList<>(preview.stubs());
        preview.layers().forEach((at, layer) -> { if (layer.refused() && !stubs.contains(at)) { stubs.add(at); } });
        int tint = erase ? Color.rgba8888(ERASE) : labelled == null ? 0
              : GpuMinimap.colour(capture.apply(labelled, preview.after().get(labelled)));
        Map<Coords, BoardScene.Tile> routes = new LinkedHashMap<>();
        if (!erase && routePaint) {
            // A join changes its neighbours' curves, ramps and end caps even without changing their saved fields.
            Set<Coords> area = new java.util.LinkedHashSet<>(preview.after().keySet());
            area.addAll(preview.path());
            if (preview.at() != null) { area.add(preview.at()); }
            for (int ring = 0; ring < 2; ring++) {
                for (Coords at : List.copyOf(area)) {
                    for (int side = 0; side < 6; side++) { area.add(at.translated(side)); }
                }
            }
            for (Coords at : area) {
                var tile = capture.apply(at, preview.after().get(at));
                if (tile != null) { routes.put(at, tile); }
            }
        }
        return new Data(labelled == null ? preview.at() : labelled, erase, hexes, objects, List.copyOf(standIns), tint, label,
              List.copyOf(stubs), List.copyOf(decks), Map.copyOf(routes), brush.component().equals("building"));
    }

    /** A hex's file form without its objects: what a terrain card changes. */
    private static Object terrain(Hex hex) { return BoardFile.hexNode(hex).without("decorations"); }

    /**
     * {@code hex}'s stand-in ({@link StandIn}): water to its bed, a deck at its height, volumes to their tops, and the
     * centreline of its bridge, road or wall where the event changes it ({@code other} is the hex on the event's other side).
     */
    static StandIn standIn(Coords coords, Hex hex, Hex other) {
        float level = hex.getLevel(), low = level, high = level;
        int way = hex.terrainLevel(Terrains.BUILDING) == BuildingType.WALL.getTypeValue() ? Terrains.BUILDING
              : hex.containsTerrain(Terrains.BRIDGE) ? Terrains.BRIDGE : Terrains.ROAD;
        Terrain shown = hex.getTerrain(way), was = other.getTerrain(way);
        int exits = shown != null && (was == null || was.getLevel() != shown.getLevel() || was.getExits() != shown.getExits())
              ? shown.getExits() & 63 : 0;
        if (hex.containsTerrain(Terrains.WATER) && hex.terrainLevel(Terrains.WATER) > 0) { low = level - hex.terrainLevel(Terrains.WATER); }
        if (way == Terrains.BRIDGE) {
            low = high = level + Math.max(0, hex.terrainLevel(Terrains.BRIDGE_ELEV));
        } else {
            for (int type : VOLUMES) {
                if (hex.containsTerrain(type)) { high = Math.max(high, level + hex.terrainLevel(type)); }
            }
        }
        return new StandIn(coords, low, high, exits);
    }

    /**
     * What the event changes in {@code before} to make {@code after}: Paint the terrain it brings, Erase the terrain that
     * goes, each by the name players see (the hex card's), zones by their IDs; heights show as the stand-ins' volumes.
     */
    private static String label(Hex before, Hex after, boolean erase) {
        Hex from = erase ? after : before, to = erase ? before : after;
        List<String> names = new ArrayList<>();
        for (int type : to.getTerrainTypes()) {
            Terrain terrain = to.getTerrain(type), old = from.getTerrain(type);
            if (old != null && old.getLevel() == terrain.getLevel() && old.getExits() == terrain.getExits()
                  || VOLUMES.contains(type) || type == Terrains.BRIDGE_ELEV) { continue; }
            if (type == Terrains.DEPLOYMENT_ZONE) {
                int zones = terrain.getExits() & ~(old == null ? 0 : old.getExits());
                if (zones != 0) { names.add(Messages.getString("BoardEditor.deploymentZoneMarker", BoardEditorSession.zoneIds(zones))); }
                continue;
            }
            String name = type == Terrains.BUILDING && terrain.getLevel() == BuildingType.WALL.getTypeValue()
                  ? BuildingType.WALL.toString() : Terrains.getDisplayName(type, terrain.getLevel());
            if (name != null && !names.contains(name)) { names.add(name); }
        }
        return String.join(", ", names);
    }

    /**
     * Prepare before the scene's colour and depth passes so replaced infrastructure is hidden in every pass. Rebuild
     * when the edit, installed scene or renderer changes; no authoritative board state is kept on the render thread.
     */
    void prepare(BoardScene scene, GpuTerrain terrain, Data data) {
        boolean busy = terrain.busy();
        if (data != built || terrain != builtFor || builtScene == null || scene == null
              || scene.tiles() != builtScene.tiles() || builtBusy && !busy) { build(scene, terrain, data); }
        builtBusy = busy;
    }

    /** Draw over the finished frame: other terrain's stand-ins, then model ghosts. */
    void render(Camera camera, BoardScene scene, GpuTerrain terrain, Data data) {
        prepare(scene, terrain, data);
        if (data == Data.NONE) { return; }
        if (standIns == null && ghosts.isEmpty()) { return; }
        if (routes != null && !ghosts.isEmpty()) { renderGhostLayer(camera); }
        blend.begin();
        try {
            if (standIns != null) {
                for (boolean hidden : new boolean[] { true, false }) {
                    for (Material material : standIns.materials) {
                        material.set(new DepthTestAttribute(hidden ? GL20.GL_GREATER : GL20.GL_LEQUAL, false));
                        ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity = opacity(material) * (hidden ? HIDDEN : 1);
                    }
                    batch.begin(camera);
                    batch.render(standIns, light);
                    batch.end();
                }
            }
            if (!ghosts.isEmpty()) {
                if (routes != null) {
                    // Composite opacity once, so overlapping road coats and wall modules do not make bright blobs.
                    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                    composite.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, ghostLayer.getWidth(), ghostLayer.getHeight()));
                    composite.begin();
                    // This overload takes the bottom-left UV first. Framebuffer and screen share that origin.
                    composite.draw(ghostLayer.getColorBufferTexture(), 0, 0, ghostLayer.getWidth(), ghostLayer.getHeight(), 0, 0, 1, 1);
                    composite.end();
                } else {
                    batch.begin(camera);
                    ghosts.forEach(ghost -> batch.render(ghost, light));
                    batch.end();
                }
            }
        } finally { blend.end(); }
    }

    /** Nearest preview surface per pixel, with the gameplay ghost's colour/opacity, over the untouched scene depth. */
    private void renderGhostLayer(Camera camera) {
        viewport.clear(); Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, viewport);
        framebuffer.clear(); Gdx.gl.glGetIntegerv(GL30.GL_DRAW_FRAMEBUFFER_BINDING, framebuffer);
        boolean srgb = Gdx.gl.glIsEnabled(GpuLinearOverlay.FRAMEBUFFER_SRGB);
        boolean scissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        int width = viewport.get(2), height = viewport.get(3);
        if (ghostLayer == null || ghostLayer.getWidth() != width || ghostLayer.getHeight() != height) {
            if (ghostLayer != null) { ghostLayer.dispose(); }
            ghostLayer = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, true);
        }
        if (composite == null) { composite = new SpriteBatch(1); }
        try {
            ghostLayer.begin();
            Gdx.gl.glDisable(GpuLinearOverlay.FRAMEBUFFER_SRGB);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            for (var ghost : ghosts) {
                for (Material material : ghost.materials) {
                    material.get(BlendingAttribute.class, BlendingAttribute.Type).blended = false;
                    material.set(new DepthTestAttribute(GL20.GL_LEQUAL, true));
                }
            }
            batch.begin(camera);
            ghosts.forEach(ghost -> batch.render(ghost, light));
            batch.end();
        } finally {
            for (var ghost : ghosts) {
                for (Material material : ghost.materials) {
                    material.get(BlendingAttribute.class, BlendingAttribute.Type).blended = true;
                    material.set(new DepthTestAttribute(GL20.GL_ALWAYS, false));
                }
            }
            Gdx.gl.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer.get(0));
            Gdx.gl.glViewport(viewport.get(0), viewport.get(1), width, height);
            if (srgb) { Gdx.gl.glEnable(GpuLinearOverlay.FRAMEBUFFER_SRGB); }
            if (scissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); }
            Gdx.gl.glDepthMask(false);
        }
    }

    /** The erased objects' boxes, which the hover rings outline in red. */
    List<BoundingBox> outlines() { return outlines; }

    /** Where the label stands: above the labelled hex's stand-in. */
    Vector3 labelPoint() { return labelPoint; }

    /** A stand-in material's opacity where nothing hides it: the centrelines nearly opaque, the fills faint. */
    private static float opacity(Material material) { return material.id.equals("lines") ? .9f : FILL_OPACITY; }

    private void build(BoardScene scene, GpuTerrain terrain, Data data) {
        clear();
        built = data;
        builtFor = terrain;
        builtScene = scene;
        if (data == Data.NONE || scene == null) { return; }
        Color tint = new Color(data.tint());
        if (!data.routes().isEmpty()) {
            var pending = new ArrayList<>(scene.tiles());
            data.routes().forEach((at, tile) -> pending.set(at.getX() * scene.height() + at.getY(), tile));
            routes = terrain.previewRoutes(scene.withTiles(pending), data.routes().keySet(), data.wallRoute());
            routes.ghosts.forEach((at, instances) -> instances.forEach(ghost -> {
                ghost.materials.forEach(material -> ghostly(material, data.stubs().contains(at)));
                ghosts.add(ghost);
            }));
        }
        // Other object placements keep their existing support rules; drawn infrastructure was collected together above.
        List<BoardScene.Tile> tiles = null;
        for (var entry : data.objects().entrySet()) {
            var tile = scene.tile(entry.getKey());
            if (tile == null) { continue; }
            List<BoardScene.Feature> others = tile.features().stream().filter(feature -> feature.decoration() == null).toList();
            if (!data.erase() && entry.getValue().stream().anyMatch(BoardRail::route)) {
                if (tiles == null) { tiles = new ArrayList<>(scene.tiles()); }
                List<BoardScene.Feature> features = new ArrayList<>(others);
                features.addAll(entry.getValue());
                tiles.set(entry.getKey().getX() * scene.height() + entry.getKey().getY(), tile.withFeatures(features));
            }
        }
        BoardScene shown = tiles == null ? scene : scene.withTiles(tiles);
        long count = data.objects().entrySet().stream().mapToLong(entry -> entry.getValue().stream()
              .filter(feature -> data.erase() || scene.tile(entry.getKey()) == null
                    || !scene.tile(entry.getKey()).features().contains(feature)).count()).sum();
        for (var entry : count > GHOSTS ? Map.<Coords, List<BoardScene.Feature>>of().entrySet() : data.objects().entrySet()) {
            Coords at = entry.getKey();
            var tile = shown.tile(at);
            if (tile == null) { continue; }
            for (var feature : entry.getValue()) {
                if (routes != null && GpuTerrain.routeFeature(tile, feature)) { continue; }
                if (!data.erase() && scene.tile(at).features().contains(feature)) { continue; }
                ModelInstance ghost;
                if (BoardRail.route(feature)) {
                    ghost = terrain.routeGhost(data.erase() ? scene : shown, tile, feature);
                    if (ghost != null) { owned.add(ghost.model); }
                } else if (feature.decoration().kind().equals("decal")) {
                    Model paint = terrain.decalGhost(at, feature, new Material("paint"));
                    if (paint != null) { owned.add(paint); }
                    ghost = paint == null ? null : new ModelInstance(paint);
                } else {
                    ghost = terrain.editorGhost(at, feature);
                }
                if (ghost == null) { continue; }
                // Where a drawn route ends in a new stub (a turn too sharp to lay), its ghost is red, as Erase's.
                for (Material material : ghost.materials) { ghostly(material, data.erase() || data.stubs().contains(at)); }
                ghosts.add(ghost);
                if (data.erase()) { outlines.add(ghost.calculateBoundingBox(new BoundingBox()).mul(ghost.transform)); }
            }
        }
        if (routes == null && !data.standIns().isEmpty()) {
            Model model = standInModel(data.standIns(), tint, data.erase());
            owned.add(model);
            standIns = new ModelInstance(model);
        }
        var labelled = data.standIns().stream().filter(standIn -> standIn.coords().equals(data.at())).findFirst();
        float top = labelled.map(StandIn::high).orElse(scene.tile(data.at()) == null ? 0f : scene.tile(data.at()).elevation());
        labelPoint.set(BoardGeometry.center(data.at(), top + .5f));
    }

    /**
     * Paint uses the gameplay destination ghost; Erase keeps its red tint. Cut-out textures keep their silhouettes.
     */
    static void ghostly(Material material, boolean erase) {
        if (!erase) {
            GpuBoardOverlay.ghostly(material);
            return;
        }
        if (erase) {
            Attribute alphaTest = material.get(FloatAttribute.AlphaTest);
            Attribute cutOut = alphaTest == null ? null : material.get(TextureAttribute.Diffuse);
            Attribute cullFace = material.get(IntAttribute.CullFace);
            Attribute linearColors = material.get(GpuLinearColors.TYPE);
            material.clear();
            if (linearColors != null) { material.set(linearColors); }
            material.set(ColorAttribute.createDiffuse(GpuLinearColors.authored(material, ERASE)),
                  ColorAttribute.createEmissive(GpuLinearColors.authored(material,
                        new Color(ERASE.r * .5f, ERASE.g * .3f, ERASE.b * .3f, 1))));
            if (cutOut != null) { material.set(alphaTest, cutOut); }
            if (cullFace != null) { material.set(cullFace); }
        }
        material.set(new BlendingAttribute(GHOST_OPACITY), new DepthTestAttribute(GL20.GL_LEQUAL, false));
    }

    /** The stand-ins as one mesh: their tinted tops and sides, and their centrelines in white (Erase: pale red). */
    private static Model standInModel(List<StandIn> standIns, Color tint, boolean erase) {
        var builder = new ModelBuilder();
        builder.begin();
        float level = BoardGeometry.level();
        Vector3 a = new Vector3(), b = new Vector3(), c = new Vector3(), d = new Vector3();
        // One part after the other (a part takes the vertices built until the next one starts), each its own mesh: a
        // radius-20 brush's 1,141 volumes need 35,000 vertices, within one mesh's 65,536.
        MeshPartBuilder fill = builder.part("fill", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal, material("fill", tint));
        short[] corners = new short[6];
        for (StandIn standIn : standIns) {
            Coords at = standIn.coords();
            float top = (standIn.high() + LIFT) * level, bottom = standIn.low() * level;
            Vector3 centre = BoardGeometry.center(at, 0).add(0, 0, top);
            short middle = fill.vertex(centre, Vector3.Z, null, null);
            for (int corner = 0; corner < 6; corner++) {
                corners[corner] = fill.vertex(BoardGeometry.corner(a, at, 0, corner).add(0, 0, top), Vector3.Z, null, null);
            }
            for (int corner = 0; corner < 6; corner++) {
                fill.triangle(middle, corners[corner], corners[(corner + 1) % 6]);
                if (top - bottom > .01f) {
                    BoardGeometry.corner(a, at, 0, corner).z = top;
                    BoardGeometry.corner(b, at, 0, corner + 1).z = top;
                    c.set(b.x, b.y, bottom);
                    d.set(a.x, a.y, bottom);
                    fill.rect(a, d, c, b, new Vector3(a).add(b).scl(.5f).sub(centre.x, centre.y, top).nor());
                }
            }
        }
        MeshPartBuilder lines = builder.part("lines", GL20.GL_TRIANGLES, VertexAttributes.Usage.Position,
              material("lines", erase ? new Color(1, .8f, .78f, 1) : Color.WHITE));
        for (StandIn standIn : standIns) {
            Coords at = standIn.coords();
            float top = (standIn.high() + LIFT) * level;
            Vector3 centre = BoardGeometry.center(at, 0).add(0, 0, top);
            for (int direction = 0; direction < 6; direction++) {
                if ((standIn.exits() & (1 << direction)) == 0) { continue; }
                Coords next = at.translated(direction);
                Vector3 edge = new Vector3((centre.x + BoardGeometry.centerX(next)) / 2, (centre.y + BoardGeometry.centerY(next)) / 2, top + .02f * level);
                Vector3 across = new Vector3(edge).sub(centre).crs(Vector3.Z).nor().scl(LINE * BoardGeometry.width());
                Vector3 start = new Vector3(centre).add(0, 0, .02f * level);
                lines.rect(new Vector3(start).sub(across), new Vector3(edge).sub(across), new Vector3(edge).add(across),
                      new Vector3(start).add(across), Vector3.Z);
            }
        }
        return builder.end();
    }

    /**
     * A stand-in material: flat {@code colour} (emitted, so the light leaves it as it is); {@link #render} sets its depth
     * test and opacity for each pass.
     */
    private static Material material(String id, Color colour) {
        return new Material(id, ColorAttribute.createDiffuse(Color.BLACK), ColorAttribute.createEmissive(colour),
              new BlendingAttribute(1), IntAttribute.createCullFace(GL20.GL_NONE), new DepthTestAttribute(GL20.GL_LEQUAL, false));
    }

    private void clear() {
        if (routes != null) { routes.dispose(); routes = null; }
        ghosts.clear();
        outlines.clear();
        standIns = null;
        owned.forEach(Model::dispose);
        owned.clear();
    }

    @Override
    public void dispose() {
        clear();
        batch.dispose();
        blend.dispose();
        if (ghostLayer != null) { ghostLayer.dispose(); }
        if (composite != null) { composite.dispose(); }
    }
}
