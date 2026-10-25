/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * Draws the aircraft whose moves are held back ({@link BoardAirspace}). The scene keeps each held unit at its
 * destination, its game position, so its nameplate, selection band, camera framing and picking stay there; this class
 * only changes what is drawn:
 * <ul>
 *     <li>its model, shadow and outline are drawn from a copy placed at the start of its move
 *     ({@link #drawn(ModelInstance)}), and</li>
 *     <li>a faded copy in its owner's colour marks its destination ({@link #renderShadows(Camera)}): a faint pass
 *     through scenery, then a depth-tested one.</li>
 * </ul>
 * The copies share the shown model's meshes and textures and are made again only when the shown instance changes, so
 * a held aircraft costs one more draw per material for its start and two for its shadow.
 */
final class GpuFlightModels implements Disposable {
    /** The faint pass that keeps a shadow visible behind scenery. */
    private static final float THROUGH_OPACITY = .2f;
    /** The depth-tested pass where nothing hides the shadow. */
    private static final float SHADOW_OPACITY = .45f;
    /** The share of the owner's colour that glows whatever the model's own colours. */
    private static final float GLOW_SHARE = .5f;

    private final ModelBatch batch = new ModelBatch(
          GpuShaderManager.provider("Held flight shadows", GpuUnitShader::linearDisplayProvider),
          (camera, renderables) -> { });
    /** Enough ambient light to keep the shadows bright, one light from above to show their shape. */
    private final Environment light = new Environment();
    private final GpuLinearOverlay blend = new GpuLinearOverlay();

    /** For each held unit part: its shown instance, and the copy of it placed at the start of its move. */
    private final Map<String, ModelInstance> startSources = new HashMap<>();
    private final Map<String, ModelInstance> startCopies = new HashMap<>();
    /** For each held unit part: its destination shadow's two passes, in the owner's colour they were made for. */
    private final Map<String, ShadowPasses> shadowPasses = new HashMap<>();
    /** This frame: the shown instance of each held unit part, and the copy drawn in its place. */
    private final Map<ModelInstance, ModelInstance> drawn = new IdentityHashMap<>();
    private final List<Shadow> shadows = new ArrayList<>();

    private record Shadow(ModelInstance shown, ShadowPasses passes) { }

    private record ShadowPasses(int ownerRgb, GpuGhostModel through, GpuGhostModel depthTested) { }

    GpuFlightModels() {
        light.set(new ColorAttribute(ColorAttribute.AmbientLight, .6f, .6f, .6f, 1));
        light.add(new DirectionalLight().set(.5f, .5f, .5f, -.4f, .3f, -1));
    }

    /**
     * Places this frame's start copies. Call after the units are posed for the frame, before anything is drawn.
     *
     * @param scene         the frame's scene
     * @param unitInstances the shown instance of each unit part, by {@code id:part}
     */
    void update(BoardScene scene, Map<String, ModelInstance> unitInstances) {
        drawn.clear();
        shadows.clear();
        List<String> heldKeys = new ArrayList<>();
        for (HeldFlight flight : scene.airspace().flights()) {
            for (BoardScene.Unit unit : scene.units()) {
                if (unit.id() != flight.entityId() || unit.sensorContact()) {
                    continue;
                }
                String key = unit.id() + ":" + unit.part();
                ModelInstance shown = unitInstances.get(key);
                if (shown == null) {
                    continue;
                }
                heldKeys.add(key);
                drawn.put(shown, placedAtStart(key, shown, unit.location(), flight.start()));
                shadows.add(shadow(key, shown, unit.outlineRgb()));
            }
        }
        startSources.keySet().retainAll(heldKeys);
        startCopies.keySet().retainAll(heldKeys);
        shadowPasses.keySet().retainAll(heldKeys);
    }

    /** @return the instance to draw for a shown instance: a held unit's copy at the start of its move, else itself */
    ModelInstance drawn(ModelInstance shown) {
        ModelInstance copy = (shown == null) ? null : drawn.get(shown);
        return (copy == null) ? shown : copy;
    }

    /** @return the instances to draw for these shown instances; see {@link #drawn(ModelInstance)} */
    List<ModelInstance> drawn(List<ModelInstance> shown) {
        if (drawn.isEmpty()) {
            return shown;
        }
        List<ModelInstance> result = new ArrayList<>(shown.size());
        for (ModelInstance instance : shown) {
            result.add(drawn(instance));
        }
        return result;
    }

    /** Draws each held aircraft's faded copy at its destination: a faint pass through scenery, then a depth-tested one. */
    void renderShadows(Camera camera) {
        if (shadows.isEmpty()) {
            return;
        }
        blend.begin();
        try {
            batch.begin(camera);
            for (Shadow shadow : shadows) {
                ModelInstance through = shadow.passes().through().copyOf(shadow.shown(), 0);
                through.transform.set(shadow.shown().transform);
                batch.render(through, light);
            }
            for (Shadow shadow : shadows) {
                ModelInstance depthTested = shadow.passes().depthTested().copyOf(shadow.shown(), 0);
                depthTested.transform.set(shadow.shown().transform);
                batch.render(depthTested, light);
            }
            batch.end();
        } finally {
            blend.end();
        }
    }

    /**
     * A copy of the shown instance moved from where the scene places the unit to the start of its move: facings turn
     * clockwise, the model's own rotations do not.
     */
    private ModelInstance placedAtStart(String key, ModelInstance shown, BoardScene.Waypoint destination,
          BoardScene.Waypoint start) {
        ModelInstance copy = startCopies.get(key);
        if ((copy == null) || (startSources.get(key) != shown)) {
            copy = new ModelInstance(shown);
            startCopies.put(key, copy);
            startSources.put(key, shown);
        }
        Vector3 from = BoardGeometry.center(destination.coords(), destination.elevation());
        Vector3 to = BoardGeometry.center(start.coords(), start.elevation());
        copy.transform.setToTranslation(to).rotate(Vector3.Z, (destination.facing() - start.facing()) * 60)
              .translate(-from.x, -from.y, -from.z).mul(shown.transform);
        return copy;
    }

    private Shadow shadow(String key, ModelInstance shown, int ownerRgb) {
        ShadowPasses passes = shadowPasses.get(key);
        if ((passes == null) || (passes.ownerRgb() != ownerRgb)) {
            Color colour = new Color();
            Color.rgb888ToColor(colour, ownerRgb);
            colour.a = 1;
            Color glow = colour.cpy().mul(GLOW_SHARE, GLOW_SHARE, GLOW_SHARE, 1);
            passes = new ShadowPasses(ownerRgb,
                  new GpuGhostModel(new GpuGhostModel.Look(colour, glow, THROUGH_OPACITY, true)),
                  new GpuGhostModel(new GpuGhostModel.Look(colour, glow, SHADOW_OPACITY, false)));
            shadowPasses.put(key, passes);
        }
        return new Shadow(shown, passes);
    }

    /** The copies own no GL object; only the batch and the blend do. */
    @Override
    public void dispose() {
        drawn.clear();
        shadows.clear();
        startSources.clear();
        startCopies.clear();
        shadowPasses.clear();
        batch.dispose();
        blend.dispose();
    }
}
