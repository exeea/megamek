/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
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
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * The board editor's Paint and Erase preview at the pointer (b10-scope "Paint placement preview" and "Route drawing
 * direction preview"): what the next pointer event would do, as {@link BoardEditorSession#preview} computes it by running
 * that event unseen. Placed objects, stamps' members, decals and route pieces show as ghosts built by the board's own
 * placement and route builder ({@link GpuTerrain#editorGhost}, {@link GpuTerrain#routeGhost},
 * {@link GpuTerrain#decalGhost}); a terrain card's result as flat tinted stand-ins of each changed hex at its heights,
 * never its real materials; Erase as the same in red, of what goes. Everything is built when the preview changes and
 * drawn over the finished frame, beside the hover rings: hovering rebuilds no terrain.
 */
final class GpuEditorPreview implements Disposable {
    /**
     * The preview as the render thread draws it, made on the EDT ({@link #data}): the hex the event lands on, whether it
     * erases, the hexes it changes (while a route is drawn by hand, the hexes of its path), the objects it shows by hex
     * (Paint: each changed hex's objects afterwards; Erase: those that go), the terrain stand-ins, their tint (rgba8888),
     * the label at {@link #at} and the hexes where the route ends in a new stub (marked red).
     */
    record Data(Coords at, boolean erase, List<Coords> hexes, Map<Coords, List<BoardScene.Feature>> objects,
          List<StandIn> standIns, int tint, String label, List<Coords> stubs, List<DeckOutline> decks) {
        static final Data NONE = new Data(null, false, List.of(), Map.of(), List.of(), 0, "", List.of(), List.of());
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
    static Data data(BoardEditorSession.Preview preview, boolean erase, String brush,
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
            label = Messages.getString("BoardEditor.previewPath", label.isEmpty() ? brush : label, preview.path().size());
            if (!preview.notice().isEmpty()) { label += " \u00B7 " + preview.notice(); }
            hexes = preview.path().stream().distinct().toList();
        }
        if (preview.path().isEmpty() && !preview.notice().isEmpty()) { label = preview.notice(); }
        List<Coords> stubs = new ArrayList<>(preview.stubs());
        preview.layers().forEach((at, layer) -> { if (layer.refused() && !stubs.contains(at)) { stubs.add(at); } });
        int tint = erase ? Color.rgba8888(ERASE) : labelled == null ? 0
              : GpuMinimap.colour(capture.apply(labelled, preview.after().get(labelled)));
        return new Data(labelled == null ? preview.at() : labelled, erase, hexes, objects, List.copyOf(standIns), tint, label,
              List.copyOf(stubs), List.copyOf(decks));
    }

    /** A hex's file form without its objects: what a terrain card changes. */
    private static Object terrain(Hex hex) { return BoardFile.hexNode(hex).without("decorations"); }

    /**
     * {@code hex}'s stand-in ({@link StandIn}): water to its bed, a deck at its height, volumes to their tops, and the
     * centreline of its bridge or road where the event changes it ({@code other} is the hex on the event's other side).
     */
    static StandIn standIn(Coords coords, Hex hex, Hex other) {
        float level = hex.getLevel(), low = level, high = level;
        int way = hex.containsTerrain(Terrains.BRIDGE) ? Terrains.BRIDGE : Terrains.ROAD;
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
            String name = Terrains.getDisplayName(type, terrain.getLevel());
            if (name != null && !names.contains(name)) { names.add(name); }
        }
        return String.join(", ", names);
    }

    /**
     * Draws {@code data} over the finished frame, as the hover rings are: the stand-ins faintly where scenery hides
     * them, then plainly, then the ghosts. Rebuilt when the preview or the renderer changes, and once more when the
     * renderer finishes a build, whose supports the ghosts stand on.
     */
    void render(Camera camera, BoardScene scene, GpuTerrain terrain, Data data) {
        boolean busy = terrain.busy();
        if (data != built || terrain != builtFor || builtBusy && !busy) { build(scene, terrain, data); }
        builtBusy = busy;
        if (data == Data.NONE) { return; }
        if (standIns == null && ghosts.isEmpty()) { return; }
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
                batch.begin(camera);
                ghosts.forEach(ghost -> batch.render(ghost, light));
                batch.end();
            }
        } finally { blend.end(); }
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
        if (data == Data.NONE || scene == null) { return; }
        Color tint = new Color(data.tint());
        // Objects: Paint's ghosts of what is new in each hex, in their colours; Erase's of what goes, in red.
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
        if (!data.standIns().isEmpty()) {
            Model model = standInModel(data.standIns(), tint, data.erase());
            owned.add(model);
            standIns = new ModelInstance(model);
        }
        var labelled = data.standIns().stream().filter(standIn -> standIn.coords().equals(data.at())).findFirst();
        float top = labelled.map(StandIn::high).orElse(scene.tile(data.at()) == null ? 0f : scene.tile(data.at()).elevation());
        labelPoint.set(BoardGeometry.center(data.at(), top + .5f));
    }

    /**
     * A ghost's material: Paint keeps the model's look, translucent; Erase turns it red and keeps only a cut-out, so a
     * flat sprite keeps its outline. Neither writes depth.
     */
    static void ghostly(Material material, boolean erase) {
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
    }
}
