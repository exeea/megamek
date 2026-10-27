/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/**
 * The pilot flame a working flamer keeps burning at its nozzle, ready to fire: a small blue gas core and tongues of
 * fire licking up from it. Borrows the posed flame emitters each frame and draws with the flamer stream's own shader.
 */
final class GpuPilotFlames implements Disposable {
    static final int MAX_PILOTS = 256;
    private static final int TONGUES = 3;
    /** One tongue's life from the nozzle until it fades above it. */
    private static final float TONGUE_SECONDS = .55f;
    /** A flamer nozzle's radius in authored model units, Mek and turret alike: the pilot flame's size. */
    private static final float NOZZLE = .45f;
    private final Vector3[] positions = new Vector3[MAX_PILOTS], directions = new Vector3[MAX_PILOTS];
    private final float[] sizes = new float[MAX_PILOTS];
    private final int[] seeds = new int[MAX_PILOTS];
    private final Vector3 right = new Vector3(), across = new Vector3(), along = new Vector3();
    private final Vector3 side = new Vector3(), point = new Vector3();
    private final GpuEffectBatch batch = new GpuEffectBatch(MAX_PILOTS * (TONGUES + 1));
    private int count;
    private float time;

    /** Starts a frame's pilots; they burn on the frame's real time, not on any unit or attack timeline. */
    void beginFrame(float seconds) {
        count = 0;
        time = (time + seconds) % 3600;
    }

    /** Call after bone animation, placement and hover. No sensor contact, meeple or broken flamer has a pilot. */
    void update(GpuUnitModel model, ModelInstance instance, BoardScene.Unit unit) {
        if (model.meeple() || unit.sensorContact() || unit.model().state() == null) {
            return;
        }
        for (var binding : model.equipment()) {
            if (binding.emitters().stream().noneMatch(emitter -> "flame".equals(emitter.effect()))
                  || !binding.intact(unit.model())) {
                continue;
            }
            for (var emitter : binding.emitters()) {
                var node = instance.getNode(emitter.node());
                if (!"flame".equals(emitter.effect()) || node == null || count == MAX_PILOTS
                      || !UnitModelAttachment.visible(node)) {
                    continue;
                }
                if (positions[count] == null) {
                    positions[count] = new Vector3();
                    directions[count] = new Vector3();
                }
                UnitModelAttachment.emitter(instance, node, emitter, positions[count], directions[count]);
                sizes[count] = NOZZLE * node.globalTransform.getScaleX() * instance.transform.getScaleX();
                seeds[count++] = (unit.id() * 31 + binding.index()) * 31 + emitter.id().hashCode();
            }
        }
    }

    int pilotCount() { return count; }

    void render(Camera camera) {
        batch.begin();
        if (count == 0) {
            return;
        }
        right.set(camera.direction).crs(camera.up).nor();
        for (int index = 0; index < count; index++) {
            var nozzle = positions[index];
            var forward = directions[index];
            float size = sizes[index];
            int seed = seeds[index];
            side.set(forward).crs(Vector3.Z);
            if (side.isZero(.001f)) { side.set(Vector3.X); }
            side.nor();
            // Each tongue rises out of the nozzle, sways and fades on its own staggered cycle.
            for (int tongue = 0; tongue < TONGUES; tongue++) {
                float age = (time / TONGUE_SECONDS + (float) tongue / TONGUES + (seed & 255) / 255f) % 1;
                float sway = MathUtils.sin(time * 9 + (seed & 1023) * .013f + tongue * 2.1f);
                point.set(nozzle).mulAdd(forward, size * (.5f + age))
                      .mulAdd(side, sway * size * .3f * age)
                      .add(0, 0, age * age * size * 2.4f);
                float radius = size * (.5f + .4f * MathUtils.sin(age * MathUtils.PI));
                across.set(right).scl(radius);
                along.set(camera.up).scl(radius * 1.35f);
                // Integer part picks the fire pattern; the fractional part is the tongue's age (particles-fire.glsl).
                batch.quad(point, across, along, -1, 2 + ((seed + tongue) & 1023) + age * .999f,
                      .8f * MathUtils.sin(age * MathUtils.PI));
            }
        }
        int tongues = batch.size();
        for (int index = 0; index < count; index++) {
            var forward = directions[index];
            float size = sizes[index];
            across.set(forward).crs(camera.direction);
            if (across.isZero(.001f)) { across.set(right); }
            across.nor().scl(size * .45f);
            float flicker = .85f + .15f * MathUtils.sin(time * 53 + (seeds[index] & 1023) * .021f);
            along.set(forward).scl(size * 1.5f * flicker);
            batch.quad(positions[index], across, along, 0, 1, .75f * flicker);
        }
        // Tongues blend over the scene like the flame stream; the blue core adds its light.
        batch.render(camera, tongues);
    }

    /** Links its program now (GpuBattleView's warm-up), not at the first flamer in view. */
    void prepare() { batch.prepare(); }

    @Override
    public void dispose() {
        batch.dispose();
    }
}
