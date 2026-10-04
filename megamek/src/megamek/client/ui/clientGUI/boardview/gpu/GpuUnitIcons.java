/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntMap;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Tactical View's unit icons: the classic sprite in a team-tinted frame with a facing tick, placed on the existing
 * animated poses. Owns no game state or animation clock.
 */
final class GpuUnitIcons implements Disposable {
    /**
     * Side of the square in hex heights, at every zoom: hud-v3's 1.25 hex radii, rounded down so that the square
     * turned to any facing, and its tick, stay within the hex. Only a bold frame's corners reach past it, by 1% of a
     * hex.
     */
    static final float SIZE_IN_HEXES = .7f;
    /** hud-v3's lift of a unit's head over its icon's centre, in icon sides (view3d.js project). */
    static final float HEAD_LIFT = .62f;
    // hud-v3 flat.js proportions of an icon of 38 pixels: the sprite fills the square less 3 pixels a side, the frame
    // is 1.3 pixels (2.5 when selected or targeted), a contact's dashes are 4 on, 3 off, the tick is 7 by 10, and a
    // destroyed unit's cross is 2 pixels wide, 4 pixels in from the corners.
    private static final float SPRITE_SIZE = 1 - 6 / 38f;
    private static final float LINE = 1.3f / 38;
    private static final float BOLD_LINE = 2.5f / 38;
    private static final float DASHED_LINE = 1.5f / 38;
    private static final float DASH = 4 / 38f;
    private static final float GAP = 3 / 38f;
    private static final float TICK_LENGTH = 7 / 38f;
    private static final float TICK_HALF_WIDTH = 5 / 38f;
    private static final float CROSS_INSET = 4 / 38f;
    private static final float CROSS_LINE = 2 / 38f;
    /** hud-v3's opacity of a destroyed or doomed unit's whole icon. */
    private static final float DESTROYED_OPACITY = .45f;
    private static final Color CROSS_COLOR = new Color(0xff8a80ff);
    /** hud-v3's veil over a friendly unit that has already moved in the movement phase. */
    private static final Color VEIL_COLOR = new Color(20 / 255f, 30 / 255f, 28 / 255f, .55f);
    // Parts of the one node, drawn in this order by the unsorted batch.
    private static final int FILL = 0;
    private static final int SPRITE = 1;
    private static final int FRAME = 2;
    private static final int BOLD = 3;
    private static final int DASHED = 4;
    private static final int TICK = 5;
    private static final int CROSS = 6;
    private static final int VEIL = 7;

    /** hud-v3 icon colors: a dark tint of the side's color under the sprite, and the side's color for the lines. */
    private enum Palette {
        FRIEND(new Color(22 / 255f, 44 / 255f, 39 / 255f, .88f), UiTheme.MINT),
        ENEMY(new Color(66 / 255f, 40 / 255f, 36 / 255f, .88f), UiTheme.CORAL),
        CONTACT(new Color(80 / 255f, 50 / 255f, 30 / 255f, .8f), UiTheme.BLIP),
        /** A unit the battle status does not list, such as a wreck. */
        NEUTRAL(new Color(28 / 255f, 36 / 255f, 38 / 255f, .88f), UiTheme.ACCENT);

        final Color fill;
        final Color line;

        Palette(Color fill, Color line) {
            this.fill = fill;
            this.line = line;
        }
    }

    private final GpuTextures<BoardScene.Pixels> textures = new GpuTextures<>(true);
    private final Map<BoardScene.Pixels, GpuUnitModel> models = new HashMap<>();
    private final Map<String, ModelInstance> instances = new HashMap<>();
    private final IntMap<GpuBattleStatus.UnitStatus> listed = new IntMap<>();
    private GpuBattleStatus.Snapshot status;
    private boolean active;
    /** Created on first use, on the GL thread. */
    private ModelBatch batch;

    boolean active() { return active; }

    ModelInstance instance(BoardScene.Unit unit) { return instances.get(unit.id() + ":" + unit.part()); }

    Collection<ModelInstance> instances() { return instances.values(); }

    /**
     * Places one icon per unit while the Tactical View is on. {@code status} supplies each unit's side and state, as
     * the client decided them; {@code marked} units (selected or targeted) get a bold white frame, {@code hovered}
     * ones a white one. Icons are sized in board units by {@link #place}, at every zoom, and each unit's
     * {@link #head} goes into {@code anchors}.
     *
     * @return true when cached picking meshes must be released along with an obsolete atlas layout
     */
    boolean update(boolean tacticalView, Camera camera, BoardScene scene,
          GpuBattleStatus.Snapshot status, Predicate<BoardScene.Unit> marked, Predicate<BoardScene.Unit> hovered,
          Map<BoardScene.Unit, UnitFootprint.Pose> poses, Map<BoardScene.Unit, Vector3> anchors) {
        active = tacticalView;
        if (!active) { return false; }
        if (status != this.status) {
            this.status = status;
            listed.clear();
            if (status != null) { status.units().forEach(unit -> listed.put(unit.id(), unit)); }
        }
        boolean movement = status != null && status.phase().isMovement();
        var images = scene.units().stream().map(BoardScene.Unit::image).distinct()
              .collect(Collectors.toMap(image -> image, image -> image));
        boolean changed = textures.update(images);
        if (changed) {
            models.values().forEach(GpuUnitModel::dispose);
            models.clear();
            instances.clear();
        }
        instances.keySet().retainAll(scene.units().stream().map(unit -> unit.id() + ":" + unit.part())
              .collect(Collectors.toSet()));
        for (var unit : scene.units()) {
            var pose = poses.get(unit);
            if (pose == null) { continue; }
            var model = models.computeIfAbsent(unit.image(), image -> icon(image, textures.region(image)));
            String key = unit.id() + ":" + unit.part();
            var instance = instances.get(key);
            if (instance == null || instance.model != model.instance.model) {
                instance = new ModelInstance(model.instance.model);
                instances.put(key, instance);
            }
            var position = pose.position();
            // The Tactical View's terrain: the tileset column under the icon, else the unit's own hex.
            var tile = BoardGeometry.tile(scene, position.x, position.y);
            if (tile == null) { tile = scene.tile(unit.location().coords()); }
            float ground = tile == null ? 0 : BoardGeometry.surfaceZ(tile);
            place(instance.transform, position, ground, pose.facing());
            var listing = unit.sensorContact() ? null : listed.get(unit.id());
            boolean friendly = listing != null && listing.side() != GpuBattleStatus.Side.ENEMY;
            show(instance, palette(unit, listing), unit.sensorContact(), marked.test(unit), hovered.test(unit),
                  destroyed(unit, listing), friendly && movement && listing.done());
            anchors.put(unit, head(instance.transform, camera));
        }
        return changed;
    }

    /**
     * hud-v3's head of a unit in the Tactical View (view3d.js project): the icon's centre lifted up the screen by
     * {@link #HEAD_LIFT} of its side, so nameplates, badges and leaders sit above the icon at every zoom.
     */
    static Vector3 head(Matrix4 icon, Camera camera) {
        return icon.getTranslation(new Vector3()).mulAdd(camera.up, HEAD_LIFT * SIZE_IN_HEXES * BoardGeometry.height());
    }

    /**
     * Destroyed or doomed as the scene shows the unit now: by its pose, which follows the animation timeline and marks
     * a wreck the battle status no longer lists, or else by the battle status.
     */
    private static boolean destroyed(BoardScene.Unit unit, GpuBattleStatus.UnitStatus listing) {
        var state = unit.model() == null ? null : unit.model().state();
        return state == null ? listing != null && listing.destroyed() : state.pose().dead();
    }

    /**
     * Lays the square on the ground at {@code position}, {@link #SIZE_IN_HEXES} hex heights wide in world units, so
     * the square, sprite, frame and tick zoom with the board like the classic 2D board's units. The whole square
     * turns with the animated facing, so the tick and the sprite always agree.
     */
    static Matrix4 place(Matrix4 transform, Vector3 position, float ground, float facing) {
        float side = SIZE_IN_HEXES * BoardGeometry.height();
        return transform.setToTranslation(position.x, position.y, ground + .25f * BoardGeometry.hexScale())
              .rotate(Vector3.Z, -facing).scale(side, side, 1);
    }

    /**
     * Draws the icons over the board and its labels, full-bright and without writing depth. The batch does not sort,
     * so each icon's parts, from the fill to the veil, stack in the order they were built.
     */
    void render(Camera camera) {
        if (!active || instances.isEmpty()) { return; }
        if (batch == null) { batch = new ModelBatch((sortCamera, renderables) -> { }); }
        batch.begin(camera);
        for (var icon : instances.values()) {
            if (camera.frustum.boundsInFrustum(UnitBounds.world(icon))) { batch.render(icon); }
        }
        batch.end();
    }

    private static Palette palette(BoardScene.Unit unit, GpuBattleStatus.UnitStatus listing) {
        if (unit.sensorContact()) { return Palette.CONTACT; }
        if (listing == null) { return Palette.NEUTRAL; }
        return listing.side() == GpuBattleStatus.Side.ENEMY ? Palette.ENEMY : Palette.FRIEND;
    }

    /**
     * Per-instance colors, opacity and part choice; the instance owns copies of the model's materials. A destroyed
     * unit is crossed out and faded; a veiled one has already moved.
     */
    private static void show(ModelInstance icon, Palette palette, boolean contact, boolean marked, boolean hovered,
          boolean destroyed, boolean veiled) {
        var parts = icon.nodes.first().parts;
        parts.get(FRAME).enabled = !marked && !contact;
        parts.get(BOLD).enabled = marked;
        parts.get(DASHED).enabled = !marked && contact;
        parts.get(TICK).enabled = !contact;
        parts.get(CROSS).enabled = destroyed;
        parts.get(VEIL).enabled = veiled;
        float opacity = destroyed ? DESTROYED_OPACITY : 1;
        color(parts.get(FILL)).set(palette.fill).a *= opacity;
        color(parts.get(SPRITE)).set(1, 1, 1, opacity);
        color(parts.get(FRAME)).set(marked || hovered ? Color.WHITE : palette.line).a *= opacity;
        color(parts.get(TICK)).set(palette.line).a *= opacity;
        color(parts.get(CROSS)).set(CROSS_COLOR).a *= opacity;
        color(parts.get(VEIL)).set(VEIL_COLOR).a *= opacity;
    }

    private static Color color(NodePart part) {
        return ((ColorAttribute) part.material.get(ColorAttribute.Diffuse)).color;
    }

    /**
     * One unit-square icon per sprite: fill, sprite, thin, bold and dashed frames sharing one color, the tick, the
     * destroyed cross and the already-moved veil.
     */
    private static GpuUnitModel icon(BoardScene.Pixels pixels, TextureRegion region) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        long solid = VertexAttributes.Usage.Position;
        bar(builder.part("fill", GL20.GL_TRIANGLES, solid, material("icon-fill")), -.5f, -.5f, .5f, .5f);
        MeshPartBuilder sprite = builder.part("sprite", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates,
              material("icon-sprite", TextureAttribute.createDiffuse(region.getTexture()),
                    FloatAttribute.createAlphaTest(.1f)));
        sprite.setUVRange(region);
        float fit = SPRITE_SIZE / Math.max(pixels.width(), pixels.height());
        float x = pixels.width() * fit / 2, y = pixels.height() * fit / 2;
        sprite.rect(-x, -y, 0, x, -y, 0, x, y, 0, -x, y, 0, 0, 0, 1);
        Material line = material("icon-line");
        frame(builder.part("frame", GL20.GL_TRIANGLES, solid, line), LINE, 1, 0);
        frame(builder.part("bold", GL20.GL_TRIANGLES, solid, line), BOLD_LINE, 1, 0);
        frame(builder.part("dashed", GL20.GL_TRIANGLES, solid, line), DASHED_LINE, DASH, GAP);
        // The tick points along the unit's facing, which is local +Y (north) before the pose's rotation.
        builder.part("tick", GL20.GL_TRIANGLES, solid, material("icon-tick"))
              .triangle(new Vector3(-TICK_HALF_WIDTH, .5f, 0), new Vector3(TICK_HALF_WIDTH, .5f, 0),
                    new Vector3(0, .5f + TICK_LENGTH, 0));
        // Two diagonal bars; (h, -h) is half the bar's width across the diagonal from corner to corner.
        MeshPartBuilder cross = builder.part("cross", GL20.GL_TRIANGLES, solid, material("icon-cross"));
        float end = .5f - CROSS_INSET, h = CROSS_LINE / 2 / (float) Math.sqrt(2);
        cross.rect(-end + h, -end - h, 0, end + h, end - h, 0, end - h, end + h, 0, -end - h, -end + h, 0, 0, 0, 1);
        cross.rect(end - h, -end - h, 0, -end - h, end - h, 0, -end + h, end + h, 0, end + h, -end + h, 0, 0, 0, 1);
        bar(builder.part("veil", GL20.GL_TRIANGLES, solid, material("icon-veil")), -.5f, -.5f, .5f, .5f);
        return new GpuUnitModel(builder.end());
    }

    /**
     * Symbols stay readable over sprites and labels without writing scene depth, and blend so a destroyed unit's icon
     * can fade; the id keeps materials apart.
     */
    private static Material material(String id, Attribute... attributes) {
        Material material = new Material(id, ColorAttribute.createDiffuse(Color.WHITE), new BlendingAttribute(1f),
              new DepthTestAttribute(GL20.GL_ALWAYS, false), IntAttribute.createCullFace(GL20.GL_NONE));
        material.set(attributes);
        return material;
    }

    /** A square outline centred on the unit square's edge; {@code dash} of 1 draws it solid. */
    private static void frame(MeshPartBuilder mesh, float width, float dash, float gap) {
        float half = width / 2;
        for (float from = -.5f; from < .5f; from += dash + gap) {
            float to = Math.min(from + dash, .5f);
            bar(mesh, from - half, .5f - half, to + half, .5f + half);
            bar(mesh, from - half, -.5f - half, to + half, -.5f + half);
            bar(mesh, -.5f - half, from - half, -.5f + half, to + half);
            bar(mesh, .5f - half, from - half, .5f + half, to + half);
        }
    }

    private static void bar(MeshPartBuilder mesh, float x0, float y0, float x1, float y1) {
        mesh.rect(x0, y0, 0, x1, y0, 0, x1, y1, 0, x0, y1, 0, 0, 0, 1);
    }

    @Override public void dispose() {
        models.values().forEach(GpuUnitModel::dispose);
        models.clear();
        instances.clear();
        textures.dispose();
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
    }
}
