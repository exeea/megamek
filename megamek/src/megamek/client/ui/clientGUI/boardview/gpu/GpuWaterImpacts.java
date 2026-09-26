/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Bounded water spray and foam rings from observed mek falls, shared by both cameras. */
final class GpuWaterImpacts implements Disposable {
    static final float LIFETIME = 1.1f;
    private final GpuEffectBatch spray = new GpuEffectBatch(512, "water-splash");
    private final Vector3 point = new Vector3(), width = new Vector3(), length = new Vector3(), right = new Vector3();
    private record Burst(Vector3 origin, float start) { }
    private record Contact(long sequence, List<Burst> bursts) { }
    private final Map<Integer, Contact> contacts = new HashMap<>();
    private final UnitPicking surfaces = new UnitPicking();
    private int ripples;

    int particleCount() { return spray.size(); }
    int rippleCount() { return ripples; }
    Vector3 contact(int unit) { return contacts.containsKey(unit) ? contacts.get(unit).bursts().getLast().origin().cpy() : null; }

    void render(Camera camera, BoardScene scene, Map<Integer, UnitMotion> motions, Map<String, ModelInstance> instances, Color light) {
        spray.begin();
        ripples = 0;
        spray.setSmokeLight(light);
        right.set(camera.direction).crs(camera.up).nor();
        contacts.keySet().retainAll(motions.keySet());
        for (var unit : scene.units()) {
            if (unit.sensorContact() || unit.model() == null || unit.model().state() == null
                  || unit.model().state().structure().anatomy() == null) { continue; }
            var motion = motions.get(unit.id());
            var impact = motion == null ? null : motion.waterImpact(scene);
            var instance = instances.get(unit.id() + ":" + unit.part());
            if (impact == null || instance == null) { contacts.remove(unit.id()); continue; }
            var contact = contacts.get(unit.id());
            if (contact == null || contact.sequence() != motion.sample().sequence()) {
                contacts.remove(unit.id());
                contact = new Contact(motion.sample().sequence(), new ArrayList<>());
            }
            float age = impact.age(), level = impact.position().z - .25f;
            // A moving, rotating torso enters over several frames. Leave each emission at its own contact point.
            while (contact.bursts().size() < 4 && (contact.bursts().isEmpty()
                  || age >= contact.bursts().getFirst().start() + contact.bursts().size() * .05f)) {
                var origin = new Vector3();
                var torso = instance.getNode("CT");
                var bounds = torso == null ? UnitBounds.world(instance) : UnitBounds.subtree(torso).mul(instance.transform);
                if (bounds.min.z > level) { break; }
                if (!surfaces.waterline(instance, "CT", level, origin)) {
                    // A large playback step can pass the whole entry. Use the actual submerged torso's footprint.
                    bounds.getCenter(origin);
                }
                origin.z = level + .25f;
                var tile = BoardGeometry.tile(scene, origin.x, origin.y);
                if (tile == null || !tile.liquid().present() || tile.frozen() || tile.liquid().molten()) { break; }
                float start = contact.bursts().isEmpty() ? age : contact.bursts().getFirst().start() + contact.bursts().size() * .05f;
                contact.bursts().add(new Burst(origin, start));
                contacts.put(unit.id(), contact);
            }
            var body = UnitBounds.world(instance);
            boolean crossesSurface = body.min.z < level && body.max.z > level;
            for (int burst = 0; burst < contact.bursts().size(); burst++) {
                var emission = contact.bursts().get(burst);
                float t = age - emission.start(), fade = Math.max(0, 1 - age / LIFETIME);
                if (burst == 3 && fade > 0 && crossesSurface && surfaces.waterline(instance, "*", level, point)) {
                    // Ripples belong to the current surface contact; a fully submerged unit leaves only entry spray.
                    point.z = level + .25f;
                    float radius = BoardGeometry.HEIGHT * .1f + t * 26;
                    width.set(radius, 0, 0);
                    length.set(0, radius, 0);
                    spray.quad(point, width, length, -1, 0, fade * .65f);
                    ripples++;
                }
                for (int drop = 0; drop < 16; drop++) {
                    float angle = (drop + burst * 16) * 2.399963f + unit.id(), speed = 12 + drop % 7 * 2.5f;
                    float height = t * (22 + drop % 5 * 4) - 42 * t * t;
                    if (height < 0) { continue; }
                    float spread = BoardGeometry.HEIGHT * .06f + t * speed;
                    point.set(emission.origin()).add(MathUtils.cos(angle) * spread,
                          MathUtils.sin(angle) * spread, height);
                    width.set(right).scl((1.2f + drop % 3 * .35f) * fade);
                    length.set(camera.up).scl((2 + fade * 3) * fade);
                    spray.quad(point, width, length, -1, 1, fade * (.35f + burst * .15f));
                }
            }
        }
        spray.render(camera, spray.size());
    }

    void clear() { contacts.clear(); surfaces.clear(); }

    @Override
    public void dispose() { clear(); spray.dispose(); }
}
