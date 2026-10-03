/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.ResolvedAttack;

/** Bounded procedural effects using the posed assembly's actual muzzle/launcher emitters. No rules or hit tests. */
final class GpuAttackEffects implements Disposable {
    private static final int MAX_MUZZLES = 512;
    private static final int FLAME_PUFFS = 32;
    private static final float MUZZLE_BLAST_SECONDS = .11f;
    private static final float MUZZLE_SMOKE_SECONDS = .34f;
    private static final float PPC_BEAM_WIDTH = 4.3f;
    // Beam shader kinds; particle kinds belong to the separate particle shader.
    private static final int LASER = 0, PPC = 1, PPC_GLOW = 2;
    // projectiles.frag kinds. Fractional parts carry animation phase from the existing attack clock.
    static final int TRACER = 0, ENERGY_TRAIL = 1, ENERGY_GLOW = 2, FLARE_GLOW = 3, SPRAY = 4, SCREEN = 5, CONTACT_GLOW = 6;
    static final int IMPACT_SPARK = 7;
    private final Vector3[] origins = new Vector3[MAX_MUZZLES];
    private final Vector3[] muzzles = new Vector3[MAX_MUZZLES];
    private final Vector3[] forwards = new Vector3[MAX_MUZZLES];
    private final Vector3[] endpoints = new Vector3[MAX_MUZZLES];
    private final String[] effects = new String[MAX_MUZZLES];
    private final boolean[] arcs = new boolean[MAX_MUZZLES];
    private final boolean[] impacts = new boolean[MAX_MUZZLES];
    private final float[] flameSizes = new float[MAX_MUZZLES];
    private final float[] roundDelays = new float[MAX_MUZZLES];
    private final Trace[] emitted = new Trace[MAX_MUZZLES];
    private final ResolvedAttack.Shot[] profiles = new ResolvedAttack.Shot[MAX_MUZZLES];
    private final Vector3 target = new Vector3();
    private final Vector3 direction = new Vector3();
    private final Vector3 point = new Vector3();
    private final Vector3 end = new Vector3();
    private UnitAttack attack;
    private List<UnitAttack> volley = List.of();
    private GpuUnitModels library;
    private Map<String, ModelInstance> instances = Map.of();
    private final GpuMissileEffects missiles = new GpuMissileEffects();
    private final GpuExplosionEffects explosions = new GpuExplosionEffects();
    private final GpuEffectBatch flames = new GpuEffectBatch(2048);
    private final GpuEffectBatch beams = new GpuEffectBatch(MAX_MUZZLES * 4, "beams");
    private final GpuEffectBatch projectiles = new GpuEffectBatch(512, "projectiles");
    private final Vector3 electricAxis = new Vector3(), electricSide = new Vector3(), electricUp = new Vector3();
    private final Vector3 electricStart = new Vector3(), electricEnd = new Vector3();
    private final Vector3 flameWidth = new Vector3(), flameLength = new Vector3();
    private final Vector3 flameSide = new Vector3(), flameUp = new Vector3();
    private final Vector3 sparkAxis = new Vector3(), sparkSide = new Vector3(), sparkUp = new Vector3(), sparkVelocity = new Vector3();
    private Camera camera;
    record Trace(Vector3 origin, Vector3 forward, Vector3 target, boolean impact) { }
    private final Map<UnitAttack, Map<String, Trace>> traces = new HashMap<>();
    private final Map<UnitAttack, Map<String, GpuMissileEffects.Launch>> launches = new HashMap<>();
    private int muzzleCount;
    private final java.util.List<Trace> interceptionBeams = new java.util.ArrayList<>();
    private int airbursts;

    List<Trace> interceptionBeams() { return List.copyOf(interceptionBeams); }
    int airburstCount() { return airbursts; }

    void update(UnitAttack next, GpuUnitModels library, Map<String, ModelInstance> instances) {
        update(next == null ? List.of() : List.of(next), library, instances);
    }

    void update(List<UnitAttack> next, GpuUnitModels library, Map<String, ModelInstance> instances) {
        this.volley = next;
        this.library = library;
        this.instances = instances;
        launches.keySet().retainAll(next);
        traces.keySet().retainAll(next);
        missiles.begin();
        explosions.begin();
    }

    private void prepare(UnitAttack next) {
        attack = next;
        muzzleCount = 0;
        if (attack.event.attacker().sensorContact()) { return; }
        var event = attack.event;
        var unit = event.attacker();
        var instance = instances.get(unit.id() + ":" + unit.part());
        var assembly = library == null ? null : library.loaded(unit.model(), unit.id());
        var receiving = event.target();
        var victim = receiving == null ? null : instances.get(receiving.id() + ":" + receiving.part());
        UnitAttack.center(instance, unit.location(), point);
        attack.endpoint(victim, point, target);
        var captured = launches.computeIfAbsent(attack, ignored -> new HashMap<>());
        var paths = traces.computeIfAbsent(attack, ignored -> new HashMap<>());
        captured.values().forEach(missiles::add);
        var represented = new java.util.HashSet<String>();
        if (assembly != null && instance != null) {
            for (var binding : assembly.equipment()) {
                int owner = binding.memberId() < 0 ? event.entityId() : binding.memberId();
                represented.add(owner + ":" + binding.index());
                if (captured.containsKey(binding.node())) { continue; }
                var attachment = instance.getNode(binding.node());
                if (!attack.fires(binding) || attachment == null || !UnitBounds.subtree(attachment).isValid()) { continue; }
                var emitters = binding.emitters().stream().filter(GpuAttackEffects::firingEmitter)
                      .filter(emitter -> instance.getNode(emitter.node()) != null).toList();
                if (emitters.isEmpty()) { continue; }
                var profile = attack.profile(binding);
                int count = missileCount(profile, emitters.getFirst().effect());
                if (count > 0) {
                    if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) { continue; }
                    var ports = new Vector3[emitters.size()];
                    for (int index = 0; index < ports.length; index++) {
                        ports[index] = new Vector3();
                        UnitModelAttachment.emitter(instance, emitters.get(index), ports[index], direction);
                    }
                    launch(captured, binding.node(), owner, binding.index(), ports, victim, count, profile);
                    continue;
                }
                int rounds = UnitAttack.roundCount(profile);
                boolean bullet = "bullet".equals(emitters.getFirst().effect());
                // A physical round belongs to one barrel; its ordinal is never restarted for each barrel.
                for (int index = 0; index < (bullet ? rounds : emitters.size()) && muzzleCount < MAX_MUZZLES; index++) {
                    int barrel = bullet ? index % emitters.size() : index;
                    var emitter = emitters.get(barrel);
                    liveMuzzle();
                    UnitModelAttachment.emitter(instance, emitter, muzzles[muzzleCount], forwards[muzzleCount]);
                    float size = MathUtils.clamp(UnitBounds.subtree(attachment).getDimensions(point).len()
                          * instance.transform.getScaleX() * .5f, 1.5f, 14);
                    String key = binding.node() + ":" + (barrel + 1) + (bullet && index > 0 ? ":round-" + index : "");
                    emission(paths, key, emitter.effect(), profile, victim, size, bullet ? attack.roundDelay(index, profile) : 0);
                }
            }
        }
        // No mesh is required for firing. Preserve each observed physical gun, even in a mixed weapon bay.
        for (var mount : event.result().mounts()) {
            int owner = mount.entityId(), index = mount.equipmentIndex();
            String key = owner + ":" + index;
            if (represented.contains(key) || !attack.fires(owner, index) || captured.containsKey(key)) { continue; }
            var profile = attack.profile(owner, index);
            String effect = attack.effect(owner, index);
            if ("none".equals(effect)) { continue; }
            int count = missileCount(profile, effect);
            if (count > 0) {
                if (attack.seconds >= UnitAttack.ANTICIPATION_SECONDS) {
                    launch(captured, key, owner, index,
                          new Vector3[] { UnitAttack.center(instance, unit.location(), new Vector3()) }, victim, count, profile);
                }
                continue;
            }
            int rounds = "bullet".equals(effect) ? UnitAttack.roundCount(profile) : 1;
            for (int round = 0; round < rounds && muzzleCount < MAX_MUZZLES; round++) {
                liveMuzzle();
                UnitAttack.center(instance, unit.location(), muzzles[muzzleCount]);
                forwards[muzzleCount].set(target).sub(muzzles[muzzleCount]).nor();
                emission(paths, key + ":round-" + round, effect, profile, victim, 2, attack.roundDelay(round, profile));
            }
        }
    }

    private static int missileCount(ResolvedAttack.Shot profile, String effect) {
        return profile != null && profile.missiles() > 0 ? profile.missiles()
              : "missile".equals(effect) || "cluster".equals(effect) ? 1 : 0;
    }

    private void launch(Map<String, GpuMissileEffects.Launch> captured, String key, int owner, int index,
          Vector3[] ports, ModelInstance victim, int count, ResolvedAttack.Shot profile) {
        var launch = GpuMissileEffects.capture(attack, ports, victim, count, attack.missileHits(owner, index, count),
              profile != null && (profile.indirect() || profile.artillery()), key.hashCode() ^ attack.event.result().id().hashCode(), profile,
              attack.interceptedMissiles(owner, index, count));
        captured.put(key, launch);
        missiles.add(launch);
    }

    private void liveMuzzle() {
        if (muzzles[muzzleCount] == null) {
            muzzles[muzzleCount] = new Vector3();
            forwards[muzzleCount] = new Vector3();
        }
    }

    /** Capture each emitted round once. Beams follow the live nozzle; projectiles and released smoke do not. */
    private void emission(Map<String, Trace> paths, String key, String effect, ResolvedAttack.Shot profile,
          ModelInstance victim, float size, float delay) {
        Trace trace = paths.get(key);
        if (trace == null && attack.seconds >= UnitAttack.ANTICIPATION_SECONDS + delay) {
            var start = muzzles[muzzleCount].cpy();
            var finish = new Vector3();
            boolean impact;
            if ("laser".equals(effect) || "ppc".equals(effect)) { impact = attack.beamEndpoint(victim, start, key.hashCode(), finish); }
            else { attack.endpoint(victim, start, !attack.event.result().hit(), key.hashCode(), finish); impact = true; }
            trace = new Trace(start, forwards[muzzleCount].cpy(), finish, impact);
            paths.put(key, trace);
        }
        origin(muzzleCount).set(trace == null ? muzzles[muzzleCount] : trace.origin());
        endpoint(muzzleCount).set(trace == null ? target : trace.target());
        emitted[muzzleCount] = trace;
        impacts[muzzleCount] = trace != null && trace.impact();
        flameSizes[muzzleCount] = size;
        arcs[muzzleCount] = profile != null && (profile.indirect() || profile.artillery());
        roundDelays[muzzleCount] = delay;
        profiles[muzzleCount] = profile;
        effects[muzzleCount++] = effect;
    }

    private static boolean firingEmitter(UnitModelDescriptor.Emitter emitter) {
        return "muzzle".equals(emitter.role()) || "beam".equals(emitter.role()) || "launcher".equals(emitter.role())
              || "contact".equals(emitter.role());
    }

    private Vector3 endpoint(int index) {
        if (endpoints[index] == null) { endpoints[index] = new Vector3(); }
        return endpoints[index];
    }

    int missileCount() { return missiles.missileCount(); }
    int smokeCount() { return missiles.smokeCount(); }
    List<Trace> emissions(UnitAttack shot) { return List.copyOf(traces.getOrDefault(shot, Map.of()).values()); }
    List<GpuMissileEffects.Launch> missileLaunches(UnitAttack shot) { return List.copyOf(launches.getOrDefault(shot, Map.of()).values()); }
    int flameParticleCount() { return flames.size(); }
    int explosionCount() { return explosions.size(); }

    void setSmokeLight(Color light) {
        flames.setSmokeLight(light);
        missiles.setSmokeLight(light);
        explosions.setSmokeLight(light);
    }

    void setLightDirection(Vector3 direction) { explosions.setLightDirection(direction); }
    void setWind(BoardAtmosphere.Effects weather) { explosions.setWind(weather); }

    float flameSize() {
        float size = 0;
        for (int index = 0; index < muzzleCount; index++) {
            if ("flame".equals(effects[index])) { size = Math.max(size, flameSizes[index]); }
        }
        return size;
    }

    private Vector3 origin(int index) {
        if (origins[index] == null) {
            origins[index] = new Vector3();
        }
        return origins[index];
    }

    void render(Camera camera) {
        render(camera, null);
    }

    void render(Camera camera, GpuEffectDepth depth) {
        interceptionBeams.clear();
        airbursts = 0;
        explosions.begin();
        missiles.begin();
        flames.begin();
        beams.begin();
        projectiles.begin();
        if (volley.isEmpty()) { return; }
        this.camera = camera;
        for (var shot : volley) {
            if (shot.defensive() && shot.interception() != null) { continue; }
            if (shot.seconds < UnitAttack.ANTICIPATION_SECONDS && !shot.chargingPpc() || shot.seconds >= shot.duration) { continue; }
            prepare(shot);
            renderShot();
        }
        renderInterceptions();
        missiles.render(camera);
        projectiles.render(camera, 0);
        if (depth == null) { explosions.render(camera); }
        else { explosions.render(camera, depth); }
        // Alpha blending retains orange/red detail where flame puffs overlap instead of bleaching into a beam.
        flames.render(camera, flames.size());
        beams.render(camera, 0);
    }

    private record CounterMuzzle(Vector3 origin, String effect) { }

    /** Counter-fire samples the very same path and clock as the incoming missile and its disappearing trail. */
    private void renderInterceptions() {
        for (var salvo : volley) {
            if (salvo.defensive() || salvo.interception() == null) { continue; }
            var captured = launches.get(salvo);
            if (captured == null) { continue; }
            var counters = new java.util.ArrayList<CounterMuzzle>();
            for (var counter : volley) {
                if (!counter.defensive() || counter.interception() == null
                      || !counter.interception().id().equals(salvo.interception().id())) { continue; }
                var unit = counter.event.attacker();
                var instance = instances.get(unit.id() + ":" + unit.part());
                var assembly = library == null ? null : library.loaded(unit.model(), unit.id());
                int before = counters.size();
                if (assembly != null && instance != null) {
                    for (var binding : assembly.equipment()) {
                        if (!counter.fires(binding)) { continue; }
                        for (var emitter : binding.emitters()) {
                            if (!firingEmitter(emitter) || instance.getNode(emitter.node()) == null) { continue; }
                            var muzzle = new Vector3();
                            UnitModelAttachment.emitter(instance, emitter, muzzle, direction);
                            counters.add(new CounterMuzzle(muzzle, emitter.effect()));
                        }
                    }
                }
                if (counters.size() == before) {
                    counters.add(new CounterMuzzle(UnitAttack.center(instance, unit.location(), new Vector3()),
                          counter.effect(unit.id(), counter.event.result().equipmentIndex())));
                }
            }
            int ordinal = 0;
            for (var launch : captured.values()) {
                for (int missile = 0; missile < launch.missiles(); missile++) {
                    boolean destroyed = launch.intercepted(missile);
                    // A defense which fired but stopped nothing still shoots at an incoming round.
                    if (!destroyed && (launch.intercepted() > 0 || missile != 0)) { continue; }
                    float stop = destroyed ? launch.endProgress(missile)
                          : UnitAttack.interceptProgress(launch.origins()[0], launch.targets()[missile]);
                    float start = UnitAttack.ANTICIPATION_SECONDS;
                    float contact = destroyed ? launch.endSeconds(missile)
                          : MathUtils.lerp(start, salvo.roundContactSeconds(launch.profile(), 0), stop);
                    float age = salvo.seconds - contact;
                    var intercept = GpuMissileEffects.position(launch, missile, stop, new Vector3());
                    if (destroyed && age >= 0 && age < UnitAttack.RECOVERY_SECONDS) {
                        float fade = 1 - age / UnitAttack.RECOVERY_SECONDS;
                        explosions.burst(intercept, 7, 1 - fade, launch.seed() + missile);
                        airbursts++;
                    }
                    if (counters.isEmpty()) { continue; }
                    var counter = counters.get(ordinal++ % counters.size());
                    if ("laser".equals(counter.effect())) {
                        if (age < -.04f || age > .06f) { continue; }
                        GpuMissileEffects.position(launch, missile,
                              Math.min(stop, GpuMissileEffects.progress(launch, missile, salvo.seconds)), end);
                        beam(counter.origin(), end, .5f, LASER, 1 - Math.max(0, age) / .06f);
                        interceptionBeams.add(new Trace(counter.origin().cpy(), new Vector3(), end.cpy(), destroyed));
                    } else {
                        if (destroyed && age >= 0 && age < UnitAttack.RECOVERY_SECONDS) {
                            impactSparks(counter.origin(), intercept, age / UnitAttack.RECOVERY_SECONDS, .7f, 8, launch.seed() + missile, true);
                        }
                        for (int round = 0; round < 3; round++) {
                            float t = (age + .12f - round * .02f) / (.12f - round * .02f);
                            if (t < 0 || t >= 1) { continue; }
                            point.set(counter.origin()).lerp(intercept, t);
                            end.set(counter.origin()).lerp(intercept, Math.max(0, t - .16f));
                            projectiles.ribbon(camera, end, point, .225f, TRACER, 1);
                        }
                    }
                }
            }
        }
    }

    private void renderShot() {
        if (attack.death()) {
            explosions.burst(target, 18, attack.deathProgress(), attack.event.result().id().hashCode());
            return;
        }
        if (attack.shot()) {
            for (int index = 0; index < muzzleCount; index++) {
                String effect = effects[index];
                Vector3 start = origins[index];
                Vector3 target = endpoints[index];
                if ("ppc".equals(effect)) {
                    ppc(muzzles[index], target, index);
                    float flash = attack.contactFlash(attack.roundContactSeconds(profiles[index], 0));
                    if (impacts[index] && flash > 0) { electricGlow(target, 3 + (1 - flash) * 5, flash); }
                    continue;
                }
                if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) { continue; }
                if ("flame".equals(effect)) {
                    flame(start, target, flameSizes[index]);
                    continue;
                }
                float cannon = UnitAttack.cannonScale(profiles[index]);
                float age = attack.seconds - UnitAttack.ANTICIPATION_SECONDS - roundDelays[index];
                if (age < 0 || emitted[index] == null) { continue; }
                if (cannon > 0 && "bullet".equals(effect)) {
                    cannonMuzzle(muzzles[index], forwards[index], emitted[index], cannon, age, index * 31);
                }
                float contact = attack.roundContactSeconds(profiles[index], roundDelays[index]);
                float flash = attack.contactFlash(contact);
                if (impacts[index] && flash > 0) {
                    if ("bullet".equals(effect)) {
                        boolean targetHit = attack.event.result().hit() && attack.event.target() != null
                              && (profiles[index] == null || profiles[index].impact() == null);
                        ballisticImpact(start, target, flash, profiles[index], attack.event.result().id().hashCode() ^ index * 31, targetHit);
                    } else if ("energy".equals(effect)) { glow(target, 1.5f + (1 - flash) * 3, ENERGY_GLOW, 1 - flash, flash); }
                    else { impact(target, flash, profiles[index]); }
                }
                if (attack.seconds >= contact) { continue; }
                float t = attack.flight();
                switch (effect) {
                    case "laser" -> beam(muzzles[index], target, .65f, LASER, Math.min(1, t * 8));
                    case "energy" -> {
                        point.set(start).lerp(target, t);
                        glow(point, 2.5f, ENERGY_GLOW, t, 1);
                        end.set(start).lerp(target, Math.max(0, t - .13f));
                        projectiles.ribbon(camera, end, point, .7f, ENERGY_TRAIL, .8f);
                        float muzzleFlash = Math.max(0, 1 - (attack.seconds - UnitAttack.ANTICIPATION_SECONDS) / .12f);
                        if (muzzleFlash > 0) { glow(muzzles[index], 2 * muzzleFlash, ENERGY_GLOW, t, muzzleFlash); }
                    }
                    case "spray", "screen" -> {
                        int kind = "screen".equals(effect) ? SCREEN : SPRAY;
                        for (int puff = 0; puff < 6; puff++) {
                            float progress = MathUtils.clamp(t * 1.3f - puff * .06f, 0, 1);
                            point.set(start).lerp(target, progress).add(0, 0, puff * .5f);
                            glow(point, .75f + progress * 1.5f, kind, progress, .65f);
                        }
                    }
                    default -> {
                        float travel = age / (contact - UnitAttack.ANTICIPATION_SECONDS - roundDelays[index]);
                        UnitAttack.projectile(start, target, travel, arcs[index], point);
                        UnitAttack.projectile(start, target, Math.max(0, travel - .07f), arcs[index], end);
                        projectiles.ribbon(camera, end, point, .275f * Math.max(1, cannon), TRACER, 1);
                    }
                }
            }
        }
        if (attack.shot()) {
            var launched = launches.get(attack);
            if (launched != null) {
                for (var launch : launched.values()) {
                    float impact = attack.contactFlash(attack.roundContactSeconds(launch.profile(), 0));
                    if (impact <= 0) { continue; }
                    // Nearby bursts merge visually; keep their cost bounded independently of rack size.
                    int step = Math.max(1, launch.missiles() / 12);
                    for (int index = 0; index < launch.missiles(); index += step) {
                        if (launch.intercepted(index)) { continue; }
                        var profile = launch.profile();
                        if (profile != null && profile.ballistic()) {
                            boolean targetHit = launch.hit(index) && attack.event.target() != null && profile.impact() == null;
                            ballisticImpact(launch.origins()[index % launch.origins().length], launch.targets()[index], impact,
                                  profile, launch.seed() ^ index * 31, targetHit);
                        } else { impact(launch.targets()[index], impact, profile); }
                    }
                }
            }
        } else if (attack.event.result().hit() && attack.contactFlash() > 0) {
            float impact = attack.contactFlash();
            glow(target, 1 + (1 - impact) * 2, CONTACT_GLOW, 1 - impact, impact);
        }
    }

    /** A short expanding muzzle explosion and lingering exhaust, sharing the existing bounded plume batch. */
    private void cannonMuzzle(Vector3 muzzle, Vector3 forward, Trace emission, float size, float age, int seed) {
        if (age < 0 || age >= MUZZLE_SMOKE_SECONDS) { return; }
        flameSide.set(emission.forward()).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        float time = age / MUZZLE_SMOKE_SECONDS;
        for (int puff = 0; puff < 5; puff++) {
            float angle = seed + puff * 2.399963f;
            point.set(emission.origin()).mulAdd(emission.forward(), size * (1 + time * (5 + puff)))
                  .mulAdd(flameSide, MathUtils.sin(angle) * size * time * 2)
                  .add(0, 0, size * time * time * 5);
            muzzlePuff(size * (1 + time * 2), 0, .24f * (1 - time));
        }
        if (age >= MUZZLE_BLAST_SECONDS) { return; }
        flameSide.set(forward).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        flameUp.set(flameSide).crs(forward).nor();
        time = age / MUZZLE_BLAST_SECONDS;
        for (int puff = 0; puff < 7; puff++) {
            float angle = seed + puff * 2.399963f;
            point.set(muzzle).mulAdd(forward, size * (.5f + time * (3 + puff * .6f)))
                  .mulAdd(flameSide, MathUtils.sin(angle) * size * time * 1.5f)
                  .mulAdd(flameUp, MathUtils.cos(angle) * size * time * 1.5f);
            muzzlePuff(size * (1 + time), 2 + puff + time, .9f * (1 - time * time));
        }
    }

    private void muzzlePuff(float radius, float kind, float alpha) {
        flameWidth.set(camera.direction).crs(camera.up).nor().scl(radius);
        flameLength.set(camera.up).scl(radius);
        flames.quad(point, flameWidth, flameLength, -1, kind, alpha);
    }

    /** Muzzle charge, then a continuous hot beam with independently flickering electrical branches. */
    private void ppc(Vector3 muzzle, Vector3 finish, int index) {
        float contact = attack.roundContactSeconds(profiles[index], 0);
        int seed = attack.event.result().id().hashCode() ^ index * 7919 ^ (int) (attack.seconds * 36) * 109;
        if (attack.seconds < UnitAttack.ANTICIPATION_SECONDS) {
            float charge = MathUtils.clamp(attack.seconds / UnitAttack.ANTICIPATION_SECONDS, 0, 1);
            float radius = .4f + charge * 3.5f;
            electricGlow(muzzle, radius, charge);
            for (int arc = 0; arc < 4; arc++) {
                float angle = arc * 2.399963f + charge * 5;
                point.set(muzzle).add(MathUtils.sin(angle) * radius, MathUtils.cos(angle) * radius,
                      (UnitAttack.noise(seed + arc) - .5f) * radius * 2);
                electricArc(muzzle, point, radius * .3f, 3, seed + arc * 31, charge);
            }
        } else if (attack.seconds < contact) {
            float fade = MathUtils.clamp((contact - attack.seconds) / .05f, 0, 1);
            electricGlow(muzzle, 5, fade);
            beam(muzzle, finish, PPC_BEAM_WIDTH, PPC, fade);
            electricArc(muzzle, finish, 5, 8, seed, fade);
            electricArc(muzzle, finish, 9, 8, seed + 137, fade * .8f);
        }
    }

    private void electricGlow(Vector3 center, float radius, float alpha) {
        beams.billboard(camera, center, radius, PPC_GLOW, alpha);
    }

    /** Fixed segment count and the shared attack clock keep electrical flicker bounded and deterministic. */
    private void electricArc(Vector3 start, Vector3 finish, float spread, int segments, int seed, float alpha) {
        electricAxis.set(finish).sub(start);
        electricSide.set(electricAxis).crs(Vector3.Z).nor();
        if (electricSide.isZero()) { electricSide.set(Vector3.X); }
        electricUp.set(electricSide).crs(electricAxis).nor();
        electricStart.set(start);
        for (int segment = 1; segment <= segments; segment++) {
            float t = segment / (float) segments;
            float width = segment == segments ? 0 : spread;
            electricEnd.set(start).mulAdd(electricAxis, t)
                  .mulAdd(electricSide, (UnitAttack.noise(seed + segment * 31) * 2 - 1) * width)
                  .mulAdd(electricUp, (UnitAttack.noise(seed + segment * 53) * 2 - 1) * width * .5f);
            beam(electricStart, electricEnd, .4f, PPC, alpha);
            electricStart.set(electricEnd);
        }
    }

    /** Constant width even when a missed beam continues far off-board. */
    private void beam(Vector3 start, Vector3 finish, float width, float kind, float alpha) {
        beams.ribbon(camera, start, finish, width, kind, alpha);
    }

    /** A short stream of growing, rolling flame packets; all positions come from the shared attack clock. */
    private void flame(Vector3 start, Vector3 finish, float size) {
        direction.set(finish).sub(start);
        float time = (attack.seconds - UnitAttack.ANTICIPATION_SECONDS)
              / Math.max(.18f, attack.flightSeconds);
        float fade = MathUtils.clamp((UnitAttack.ANTICIPATION_SECONDS + attack.flightSeconds
              + UnitAttack.RECOVERY_SECONDS - attack.seconds) / .16f, 0, 1);
        float seed = (attack.event.result().id().hashCode() & 255) * .17f;
        flameSide.set(direction).crs(Vector3.Z).nor();
        if (flameSide.isZero()) { flameSide.set(Vector3.X); }
        flameUp.set(flameSide).crs(direction).nor();
        // These wisps are transient exhaust; the game alone decides whether terrain actually catches fire.
        for (int puff = 0; puff < 6; puff++) {
            float age = time - .7f - puff * .12f;
            if (age <= 0 || age >= 1.2f) { continue; }
            point.set(start).mulAdd(direction, .8f + puff * .035f)
                  .mulAdd(flameSide, MathUtils.sin(seed + puff * 2.4f) * age * size)
                  .add(0, 0, age * size * 5);
            float radius = size * (1 + age * 3);
            flameWidth.set(camera.direction).crs(camera.up).nor().scl(radius);
            flameLength.set(camera.up).scl(radius);
            flames.quad(point, flameWidth, flameLength, -1, 0,
                  .16f * MathUtils.sin(age / 1.2f * MathUtils.PI) * fade);
        }
        for (int puff = 0; puff < FLAME_PUFFS; puff++) {
            float age = time - puff * (.85f / (FLAME_PUFFS - 1));
            if (age <= 0 || age >= 1.12f) { continue; }
            float travel = Math.min(1, age);
            float swirl = seed + puff * 2.399963f + age * 8;
            float spread = size * travel * 1.25f;
            point.set(start).mulAdd(direction, travel)
                  .mulAdd(flameSide, MathUtils.sin(swirl) * spread)
                  .mulAdd(flameUp, MathUtils.cos(swirl * 1.3f) * spread * .65f)
                  .add(0, 0, travel * travel * size * 1.5f);
            float radius = size * (.55f + travel * 3.4f) * (.85f + .15f * MathUtils.sin(swirl));
            flameWidth.set(direction).crs(camera.direction).nor();
            if (flameWidth.isZero()) { flameWidth.set(camera.up); }
            flameLength.set(camera.direction).crs(flameWidth).nor()
                  .scl(Math.max(radius * 1.35f, direction.len() / FLAME_PUFFS));
            flameWidth.scl(radius);
            float opacity = .8f * MathUtils.clamp(age * 16, 0, 1)
                  * (1 - MathUtils.clamp((age - 1) / .12f, 0, 1)) * fade;
            // Integer part identifies a packet; the fractional part is its normalized age for the flame shader.
            flames.quad(point, flameWidth, flameLength, -1, 2 + puff + age / 1.12f, opacity);
        }
    }

    /** Transient presentation of the observed munition. Persistent fire, smoke and minefields belong to the board. */
    private void impact(Vector3 center, float fade, ResolvedAttack.Shot profile) {
        var ammo = profile == null ? java.util.Set.<String>of() : profile.munitions();
        boolean smoke = ammo.contains("M_SMOKE") || ammo.contains("M_SMOKE_WARHEAD");
        boolean fire = ammo.contains("M_INFERNO") || ammo.contains("M_INFERNO_IV") || ammo.contains("M_INCENDIARY")
              || ammo.contains("M_INCENDIARY_LRM") || ammo.contains("M_THUNDER_INFERNO");
        int seed = attack.event.result().id().hashCode() ^ Float.floatToIntBits(center.x);
        if (ammo.contains("M_FLARE")) { glow(center, 1 + (1 - fade) * 2, FLARE_GLOW, 1 - fade, fade); }
        else if (!smoke) { explosions.burst(center, 5.5f, 1 - fade, seed); }
        if (smoke || fire) {
            for (int puff = 0; puff < 4; puff++) {
                float angle = puff * 2.399963f, spread = (1 - fade) * 8;
                point.set(center).add(MathUtils.cos(angle) * spread, MathUtils.sin(angle) * spread, spread * (1 + puff * .2f));
                explosions.add(point, 1.5f + spread * .6f, 1 - fade, fire ? .4f : 0, fade * .8f, seed + puff * 31);
            }
        }
    }

    /** Solid rounds spark only. Calibre sets their extent; each MG round has a short, separate contact pulse. */
    private void ballisticImpact(Vector3 origin, Vector3 center, float fade, ResolvedAttack.Shot profile, int seed, boolean targetHit) {
        if (targetHit ? attack.event.conventionalInfantryTarget() : !attack.hardImpact(center)) { return; }
        float age = 1 - fade;
        if (profile != null && profile.machineGun()) { age *= UnitAttack.RECOVERY_SECONDS / .13f; }
        if (age >= 1) { return; }
        // Square-root scaling keeps small rounds readable and larger racks visibly larger without a linear-size blast.
        int rack = Math.max(1, profile == null ? 5 : profile.rackSize());
        float size = 1.5f * (float) Math.sqrt(rack / 5f);
        impactSparks(origin, center, age, size, 4 + Math.min(rack, 28), seed, targetHit);
    }

    /** Short outward streaks with drag and gravity, sampled from each round's contact clock. No particle state. */
    private void impactSparks(Vector3 origin, Vector3 center, float age, float size, int count, int seed, boolean targetHit) {
        sparkAxis.set(origin).sub(center).nor();
        if (!targetHit || sparkAxis.isZero()) { sparkAxis.set(Vector3.Z); }
        sparkSide.set(sparkAxis).crs(Vector3.Z).nor();
        if (sparkSide.isZero()) { sparkSide.set(Vector3.X); }
        sparkUp.set(sparkSide).crs(sparkAxis).nor();
        float rotation = Math.floorMod(seed, 4096) * .17f;
        float tail = Math.max(0, age - .18f);
        for (int spark = 0; spark < count; spark++) {
            float angle = rotation + spark * 2.399963f;
            float spread = 7 + spark % 3 * 2;
            sparkVelocity.set(sparkAxis).scl(3 + spark % 4)
                  .mulAdd(sparkSide, MathUtils.cos(angle) * spread).mulAdd(sparkUp, MathUtils.sin(angle) * spread).scl(size);
            point.set(center).mulAdd(sparkVelocity, age * (1.5f - age * .5f)).add(0, 0, -size * age * age * 2.5f);
            end.set(center).mulAdd(sparkVelocity, tail * (1.5f - tail * .5f)).add(0, 0, -size * tail * tail * 2.5f);
            projectiles.ribbon(camera, end, point, size * .38f, IMPACT_SPARK + Math.min(age, .99f), 1 - age);
        }
        glow(center, size * 1.4f, CONTACT_GLOW, age, Math.max(0, 1 - age * 6));
    }

    private void glow(Vector3 center, float radius, int kind, float phase, float alpha) {
        projectiles.billboard(camera, center, radius, kind + MathUtils.clamp(phase, 0, .99f), alpha);
    }

    @Override
    public void dispose() {
        attack = null;
        volley = List.of();
        instances = Map.of();
        library = null;
        launches.clear();
        traces.clear();
        missiles.dispose();
        explosions.dispose();
        flames.dispose();
        beams.dispose();
        projectiles.dispose();
    }
}
