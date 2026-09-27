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
    private UnitAttachmentMotion attachment;
    private final Map<String, Burst> suitEntries = new HashMap<>();

    int particleCount() { return spray.size(); }
    int rippleCount() { return ripples; }
    Vector3 contact(int unit) { return contacts.containsKey(unit) ? contacts.get(unit).bursts().getLast().origin().cpy() : null; }

    void render(Camera camera, BoardScene scene, Map<Integer, UnitMotion> motions, Map<String, ModelInstance> instances, Color light) {
        render(camera, scene, motions, instances, light, null);
    }

    void render(Camera camera, BoardScene scene, Map<Integer, UnitMotion> motions, Map<String, ModelInstance> instances,
          Color light, UnitAttachmentMotion release) {
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
                    float radius = BoardGeometry.height() * .1f + t * 26;
                    width.set(radius, 0, 0);
                    length.set(0, radius, 0);
                    spray.quad(point, width, length, -1, 0, fade * .65f);
                    ripples++;
                }
                droplets(camera, emission.origin(), t, fade, unit.id() + burst * 16 * 2.399963f, 1, .35f + burst * .15f);
            }
        }
        suitSplashes(camera, scene, instances, release);
        spray.render(camera, spray.size());
    }

    private void suitSplashes(Camera camera, BoardScene scene, Map<String, ModelInstance> instances, UnitAttachmentMotion release) {
        if (attachment != release) { suitEntries.clear(); attachment = release; }
        if (release == null || release.boarding()) { return; }
        var unit = release.event.before();
        var instance = instances.get(unit.id() + ":" + unit.part());
        if (instance != null) {
            for (var member : instance.nodes) {
                var bounds = UnitBounds.subtree(member).mul(instance.transform);
                if (!bounds.isValid()) { continue; }
                var center = bounds.getCenter(new Vector3());
                var tile = BoardGeometry.tile(scene, center.x, center.y);
                if (tile == null || tile.waterDepth() <= 0 || tile.frozen() || tile.liquid().molten()) { continue; }
                float level = tile.elevation() * BoardGeometry.level();
                if (bounds.min.z <= level && !suitEntries.containsKey(member.id)) {
                    center.z = level + .25f;
                    suitEntries.put(member.id, new Burst(center, release.seconds));
                }
            }
        }
        for (var entry : suitEntries.entrySet()) {
            var burst = entry.getValue();
            float age = release.seconds - burst.start(), fade = Math.max(0, 1 - age / LIFETIME);
            if (fade <= 0) { continue; }
            float radius = 2 + age * 12;
            point.set(burst.origin());
            width.set(radius, 0, 0);
            length.set(0, radius, 0);
            spray.quad(point, width, length, -1, 0, fade * .6f);
            ripples++;
            droplets(camera, burst.origin(), age, fade, entry.getKey().hashCode(), .35f, .65f);
        }
    }

    private void droplets(Camera camera, Vector3 origin, float age, float fade, float seed, float scale, float opacity) {
        for (int drop = 0; drop < 16; drop++) {
            float angle = drop * 2.399963f + seed, speed = 12 + drop % 7 * 2.5f;
            float height = age * (22 + drop % 5 * 4) - 42 * age * age;
            if (height < 0) { continue; }
            float spread = BoardGeometry.height() * .06f + age * speed;
            point.set(origin).add(MathUtils.cos(angle) * spread * scale, MathUtils.sin(angle) * spread * scale, height * scale);
            width.set(right).scl((1.2f + drop % 3 * .35f) * fade * scale);
            length.set(camera.up).scl((2 + fade * 3) * fade * scale);
            spray.quad(point, width, length, -1, 1, fade * opacity);
        }
    }

    void clear() { contacts.clear(); surfaces.clear(); suitEntries.clear(); attachment = null; }

    @Override
    public void dispose() { clear(); spray.dispose(); }
}
