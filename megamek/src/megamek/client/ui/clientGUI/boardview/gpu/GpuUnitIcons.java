/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.IntMap;
import megamek.client.ui.gdx.UiTheme;

/**
 * The Tactical View's unit icons, from the top view: the classic sprite flat on the board,
 * turned with the unit's facing, with a facing tick where the facing matters, placed on the existing animated poses.
 * Owns no game state or animation clock.
 */
final class GpuUnitIcons implements Disposable {
    /** top view: within this many degrees of straight down, the Tactical View's units are icons. */
    static final float FLAT_TILT_DEGREES = 15;
    /** sprite size: the classic sprite spans this share of its hex, keeping its aspect. */
    static final float SIZE_IN_HEXES = .9f;
    /** hud-v3's lift of a unit's head over its icon's centre, in icon sizes (view3d.js project). */
    static final float HEAD_LIFT = .62f;
    // In hex heights from the icon's centre: the tick lies just inside the hexside it faces, as the classic board's
    // facing arrow, at every facing; hud-v3's cross over a destroyed unit is 2 of its 38 pixels wide.
    private static final float TICK_BASE = .35f;
    private static final float TICK_TIP = .48f;
    private static final float TICK_HALF_WIDTH = .09f;
    private static final float CROSS_END = .28f;
    private static final float CROSS_LINE = .037f;
    /** hud-v3's opacity of a destroyed or doomed unit's whole icon. */
    private static final float DESTROYED_OPACITY = .45f;
    private static final Color CROSS_COLOR = new Color(0xff8a80ff);
    // Parts of the one node, drawn in this order by the unsorted batch.
    private static final int SPRITE = 0;
    private static final int TICK = 1;
    private static final int CROSS = 2;

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

    /** Whether the units are icons: in the Tactical View, from its top view. */
    static boolean shown(boolean tacticalView, Camera camera) {
        return tacticalView && -camera.direction.z >= MathUtils.cosDeg(FLAT_TILT_DEGREES);
    }

    /**
     * Places one icon per unit while they are {@link #shown}. {@code status} supplies each unit's side, state and
     * facing, as the client decided them. Icons are sized in board units by {@link #place}, at every zoom, and each
     * unit's {@link #head} goes into {@code anchors}.
     *
     * @return true when cached picking meshes must be released along with an obsolete atlas layout
     */
    boolean update(boolean tacticalView, Camera camera, BoardScene scene, GpuBattleStatus.Snapshot status,
          Map<BoardScene.Unit, UnitFootprint.Pose> poses, Map<BoardScene.Unit, Vector3> anchors) {
        active = shown(tacticalView, camera);
        if (!active) { return false; }
        if (status != this.status) {
            this.status = status;
            listed.clear();
            if (status != null) { status.units().forEach(unit -> listed.put(unit.id(), unit)); }
        }
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
            show(instance, listing, destroyed(unit, listing));
            anchors.put(unit, head(instance.transform, camera));
        }
        return changed;
    }

    /**
     * hud-v3's head of a unit in the Tactical View (view3d.js project): the icon's centre lifted up the screen by
     * {@link #HEAD_LIFT} of its size, so nameplates, badges and leaders sit above the icon at every zoom.
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
     * Lays the icon on the ground at {@code position}, one hex height to its local unit, so the sprite, tick and cross
     * zoom with the board like the classic 2D board's units. The whole icon turns with the animated facing, so the
     * tick and the sprite always agree.
     */
    static Matrix4 place(Matrix4 transform, Vector3 position, float ground, float facing) {
        float hex = BoardGeometry.height();
        return transform.setToTranslation(position.x, position.y, ground + .25f * BoardGeometry.hexScale())
              .rotate(Vector3.Z, -facing).scale(hex, hex, 1);
    }

    /**
     * Draws the icons over the board and its labels, full-bright and without writing depth. The batch does not sort,
     * so each icon's parts, from the sprite to the cross, stack in the order they were built.
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

    /**
     * Per-instance colors, opacity and part choice; the instance owns copies of the model's materials. A unit the
     * battle status lists with a facing (GpuBattleStatus.facing) has the tick in its side's color; sensor contacts,
     * wrecks, battle armor and infantry that fires all around have none. A destroyed unit is crossed out and faded.
     */
    private static void show(ModelInstance icon, GpuBattleStatus.UnitStatus listing, boolean destroyed) {
        var parts = icon.nodes.first().parts;
        parts.get(TICK).enabled = listing != null && listing.facing() >= 0;
        parts.get(CROSS).enabled = destroyed;
        float opacity = destroyed ? DESTROYED_OPACITY : 1;
        color(parts.get(SPRITE)).set(1, 1, 1, opacity);
        boolean enemy = listing != null && listing.side() == GpuBattleStatus.Side.ENEMY;
        color(parts.get(TICK)).set(enemy ? UiTheme.CORAL : UiTheme.MINT).a *= opacity;
        color(parts.get(CROSS)).set(CROSS_COLOR).a *= opacity;
    }

    private static Color color(NodePart part) {
        return ((ColorAttribute) part.material.get(ColorAttribute.Diffuse)).color;
    }

    /** One icon per sprite: the sprite, the facing tick and the destroyed cross, in hex heights. */
    private static GpuUnitModel icon(BoardScene.Pixels pixels, TextureRegion region) {
        ModelBuilder builder = new ModelBuilder();
        builder.begin();
        MeshPartBuilder sprite = builder.part("sprite", GL20.GL_TRIANGLES,
              VertexAttributes.Usage.Position | VertexAttributes.Usage.TextureCoordinates,
              material("icon-sprite", TextureAttribute.createDiffuse(region.getTexture()),
                    FloatAttribute.createAlphaTest(.1f)));
        sprite.setUVRange(region);
        float fit = spriteScale(pixels.width(), pixels.height());
        float x = pixels.width() * fit / 2, y = pixels.height() * fit / 2;
        sprite.rect(-x, -y, 0, x, -y, 0, x, y, 0, -x, y, 0, 0, 0, 1);
        long solid = VertexAttributes.Usage.Position;
        // The tick points along the unit's facing, which is local +Y (north) before the pose's rotation.
        builder.part("tick", GL20.GL_TRIANGLES, solid, material("icon-tick"))
              .triangle(new Vector3(-TICK_HALF_WIDTH, TICK_BASE, 0), new Vector3(TICK_HALF_WIDTH, TICK_BASE, 0),
                    new Vector3(0, TICK_TIP, 0));
        // Two diagonal bars; (h, -h) is half the bar's width across the diagonal from corner to corner.
        MeshPartBuilder cross = builder.part("cross", GL20.GL_TRIANGLES, solid, material("icon-cross"));
        float end = CROSS_END, h = CROSS_LINE / 2 / (float) Math.sqrt(2);
        cross.rect(-end + h, -end - h, 0, end + h, end - h, 0, end - h, end + h, 0, -end - h, -end + h, 0, 0, 0, 1);
        cross.rect(end - h, -end - h, 0, -end - h, end - h, 0, -end + h, end + h, 0, end + h, -end + h, 0, 0, 0, 1);
        return new GpuUnitModel(builder.end());
    }

    /**
     * Hex heights per pixel of a sprite {@code width} by {@code height} pixels
     * of the hex in the sprite's limiting direction.
     */
    static float spriteScale(int width, int height) {
        return SIZE_IN_HEXES * Math.min(BoardGeometry.TILE_WIDTH / BoardGeometry.TILE_HEIGHT / width, 1f / height);
    }

    /**
     * Icons stay readable over the board and its labels without writing scene depth, and blend so a destroyed unit's
     * icon can fade; the id keeps materials apart.
     */
    private static Material material(String id, Attribute... attributes) {
        Material material = new Material(id, ColorAttribute.createDiffuse(Color.WHITE), new BlendingAttribute(1f),
              new DepthTestAttribute(GL20.GL_ALWAYS, false), IntAttribute.createCullFace(GL20.GL_NONE));
        material.set(attributes);
        return material;
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
