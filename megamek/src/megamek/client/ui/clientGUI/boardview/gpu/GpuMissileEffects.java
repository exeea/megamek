/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;

/** Four triangles per missile; all bodies and smoke are batched. Trails sample the same immutable flight curve. */
final class GpuMissileEffects implements Disposable {
    static final float LAUNCH_JITTER_SECONDS = .06f;
    static final int SMOKE_BUDGET = 7680;
    static final int SMOKE_PER_MISSILE = 20;
    static final float SMOKE_LIFE_SECONDS = .3f;
    private static final int MISSILES_PER_BATCH = 512;
    private static final int STRIDE = 7;
    record Launch(UnitAttack attack, Vector3[] origins, Vector3[] targets, int missiles, int hits, boolean indirect, int seed,
          ResolvedAttack.Shot profile, int intercepted, Vector3[] aims, UnitAttack.Contact[] contacts) {
        boolean hit(int missile) { return Math.floorMod(seed - missile, missiles) < hits; }
        boolean intercepted(int missile) {
            int rank = Math.floorMod(seed - missile, missiles);
            return rank >= hits && rank < hits + intercepted;
        }

        float endProgress(int missile) {
            return intercepted(missile) ? UnitAttack.interceptProgress(origins[missile % origins.length], targets[missile])
                  : contacts[missile] == null ? 1 : contacts[missile].progress();
        }

        float endSeconds(int missile) {
            float start = UnitAttack.ANTICIPATION_SECONDS + launchDelay(this, missile);
            return MathUtils.lerp(start, attack.roundContactSeconds(profile, 0), endProgress(missile));
        }
    }

    static Launch capture(UnitAttack attack, Vector3[] origins, ModelInstance target, int missiles, int hits,
          boolean indirect, int seed) {
        return capture(attack, origins, target, missiles, hits, indirect, seed, attack.event.result().shot());
    }

    static Launch capture(UnitAttack attack, Vector3[] origins, ModelInstance target, int missiles, int hits,
          boolean indirect, int seed, ResolvedAttack.Shot profile) {
        return capture(attack, origins, target, missiles, hits, indirect, seed, profile,
              attack.interception() == null ? 0 : attack.interception().missiles());
    }

    static Launch capture(UnitAttack attack, Vector3[] origins, ModelInstance target, int missiles, int hits,
          boolean indirect, int seed, ResolvedAttack.Shot profile, int intercepted) {
        hits = MathUtils.clamp(hits, 0, missiles);
        var launch = new Launch(attack, origins, new Vector3[missiles], missiles, hits, indirect, seed, profile,
              MathUtils.clamp(intercepted, 0, missiles - hits), new Vector3[missiles], new UnitAttack.Contact[missiles]);
        int hitOrdinal = 0;
        for (int missile = 0; missile < missiles; missile++) {
            Vector3 origin = origins[missile % origins.length];
            // Observed artillery landings and counterfire retain their own targets rather than a body surface.
            launch.targets[missile] = launch.hit(missile) && !attack.defensive() && (profile == null || profile.impact() == null)
                  ? attack.hitEndpoint(target, origin, missile + seed, hitOrdinal++, launch.hits, new Vector3())
                  : attack.endpoint(target, origin, !launch.hit(missile) && !launch.intercepted(missile), missile + seed, new Vector3());
            launch.aims[missile] = launch.targets[missile].cpy();
            if (!launch.hit(missile) && !launch.intercepted(missile) && !attack.defensive()) {
                int index = missile;
                var contact = attack.landscapeContact(t -> position(launch, index, (float) t, new Vector3()), 32);
                launch.contacts[missile] = contact;
                if (contact != null) { launch.targets[missile] = contact.point(); }
            }
        }
        return launch;
    }
    private static final class Puff {
        final Vector3 center = new Vector3();
        float radius, alpha, depth;
    }

    private final List<Launch> launches = new ArrayList<>();
    private final GpuEffectBatch exhaust = new GpuEffectBatch(SMOKE_BUDGET + MISSILES_PER_BATCH);
    private final Puff[] smoke = new Puff[SMOKE_BUDGET];
    private final Vector3 point = new Vector3(), direction = new Vector3(), side = new Vector3(), up = new Vector3();
    private final Vector3 right = new Vector3(), width = new Vector3(), length = new Vector3();
    private float[] vertices;
    private float[] body;
    private Mesh mesh;
    private ShaderProgram shader;
    private int offset, smokeCount, missileCount;

    void begin() { launches.clear(); smokeCount = 0; missileCount = 0; }
    void add(Launch launch) { launches.add(launch); }
    void setSmokeLight(Color light) { exhaust.setSmokeLight(light); }
    int missileCount() { return missileCount; }
    int smokeCount() { return smokeCount; }

    private static float launchDelay(Launch launch, int missile) {
        return Math.min(LAUNCH_JITTER_SECONDS, launch.attack.flightSeconds * .25f)
              * noise(launch.seed + missile * 7919);
    }

    static float progress(Launch launch, int missile, float time) {
        float start = UnitAttack.ANTICIPATION_SECONDS + launchDelay(launch, missile);
        return (time - start) / (launch.attack.roundContactSeconds(launch.profile, 0) - start);
    }

    /** Includes a small fan-out, then converges on the authorized hit or a shared safe miss area. */
    static Vector3 position(Launch launch, int missile, float progress, Vector3 result) {
        float t = MathUtils.clamp(progress, 0, 1);
        if (launch.contacts[missile] != null && t >= launch.endProgress(missile)) { return result.set(launch.targets[missile]); }
        Vector3 origin = launch.origins[missile % launch.origins.length];
        Vector3 target = launch.aims[missile];
        float dx = target.x - origin.x, dy = target.y - origin.y;
        float horizontal = Math.max(.001f, (float) Math.hypot(dx, dy));
        float fan = (noise(launch.seed + missile * 31) - .5f) * 9 * MathUtils.sin(t * MathUtils.PI) * (1 - t);
        UnitAttack.projectile(origin, target, t, launch.indirect, result);
        result.x += dy / horizontal * fan;
        result.y -= dx / horizontal * fan;
        result.z += MathUtils.sin(t * MathUtils.PI) * ((launch.indirect ? 0 : 2) + noise(missile + launch.seed) * 3);
        return result;
    }

    void render(Camera camera) {
        if (launches.isEmpty()) { return; }
        int total = launches.stream().mapToInt(Launch::missiles).sum();
        int perMissile = Math.max(1, Math.min(SMOKE_PER_MISSILE, SMOKE_BUDGET / Math.max(1, total)));
        int smokeStride = Math.max(1, (total + SMOKE_BUDGET - 1) / SMOKE_BUDGET);
        right.set(camera.direction).crs(camera.up).nor();
        offset = 0;
        smokeCount = 0;
        missileCount = 0;
        int ordinal = 0;
        for (var launch : launches) {
            float time = launch.attack.seconds;
            for (int missile = 0; missile < launch.missiles; missile++, ordinal++) {
                float t = progress(launch, missile, time);
                if (t >= 0 && t < launch.endProgress(missile)) {
                    position(launch, missile, t, point);
                    position(launch, missile, Math.min(1, t + .002f), direction).sub(point).nor();
                    missile(camera, point, direction);
                    missileCount++;
                }
                if (ordinal % smokeStride != 0) { continue; }
                float interval = SMOKE_LIFE_SECONDS / perMissile;
                float newest = (float) Math.floor(time / interval) * interval;
                for (int puff = 0; puff < perMissile && smokeCount < SMOKE_BUDGET; puff++) {
                    float birth = newest - puff * interval;
                    float at = progress(launch, missile, birth);
                    if (at < 0 || at >= launch.endProgress(missile)) { continue; }
                    float age = time - birth, remaining = Math.max(0, 1 - age / SMOKE_LIFE_SECONDS);
                    Puff sample = smoke[smokeCount];
                    if (sample == null) { sample = new Puff(); smoke[smokeCount] = sample; }
                    position(launch, missile, at, sample.center).add(0, 0, age * 3);
                    sample.radius = .65f + age * 4;
                    sample.alpha = .28f * remaining * remaining;
                    sample.depth = sample.center.dot(camera.direction);
                    smokeCount++;
                }
            }
        }
        flush(camera);
        Arrays.sort(smoke, 0, smokeCount, Comparator.comparingDouble((Puff puff) -> puff.depth).reversed());
        exhaust.begin();
        for (int index = 0; index < smokeCount; index++) {
            var puff = smoke[index];
            width.set(right).scl(puff.radius);
            length.set(camera.up).scl(puff.radius);
            exhaust.quad(puff.center, width, length, -1, 0, puff.alpha);
        }
        int drawnSmoke = exhaust.size();
        int flames = 0;
        for (var launch : launches) {
            for (int missile = 0; missile < launch.missiles && flames < MISSILES_PER_BATCH; missile++) {
                float t = progress(launch, missile, launch.attack.seconds);
                if (t < 0 || t >= launch.endProgress(missile)) { continue; }
                position(launch, missile, t, point);
                position(launch, missile, Math.max(0, t - .003f), direction).sub(point).nor();
                width.set(right).scl(.5f);
                length.set(direction).scl(2.5f);
                exhaust.quad(point, width, length, 0, 1, .8f);
                flames++;
            }
        }
        exhaust.render(camera, drawnSmoke);
    }

    private void missile(Camera camera, Vector3 center, Vector3 forward) {
        if (mesh == null) { create(); }
        if (offset == vertices.length) { flush(camera); }
        side.set(forward).crs(Vector3.Z);
        if (side.isZero(.001f)) { side.set(Vector3.X); }
        side.nor();
        up.set(side).crs(forward).nor();
        for (int i = 0; i < body.length; i += STRIDE) {
            float x = body[i], y = body[i + 1], z = body[i + 2];
            vertices[offset++] = center.x + side.x * x + forward.x * y + up.x * z;
            vertices[offset++] = center.y + side.y * x + forward.y * y + up.y * z;
            vertices[offset++] = center.z + side.z * x + forward.z * y + up.z * z;
            System.arraycopy(body, i + 3, vertices, offset, 4);
            offset += 4;
        }
    }

    private void flush(Camera camera) {
        if (offset == 0) { return; }
        mesh.setVertices(vertices, 0, offset);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDisable(GL20.GL_BLEND);
        shader.bind();
        shader.setUniformMatrix("u_projView", camera.combined);
        mesh.render(shader, GL20.GL_TRIANGLES, 0, offset / STRIDE);
        offset = 0;
    }

    private void create() {
        var file = new FileHandle(new File(Configuration.dataDir(), "models/effects/missile-body.glb"));
        var data = RigidGlb.loadLods(file).getFirst();
        var points = new com.badlogic.gdx.utils.FloatArray();
        for (var node : data.nodes) { body(data, node, new Matrix4(), points); }
        body = points.toArray();
        if (body.length == 0) { throw new IllegalArgumentException("Empty missile body"); }
        shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU missile",
              GpuShaderSource.read("missile.vert"), GpuShaderSource.read("missile.frag")), next -> shader = next);
        vertices = new float[MISSILES_PER_BATCH * body.length];
        mesh = new Mesh(false, vertices.length / STRIDE, 0, VertexAttribute.Position(), VertexAttribute.ColorUnpacked());
    }

    /** Bake the authored rigid transforms once; the dynamic batch only rotates/translates this template. */
    private static void body(ModelData data, ModelNode node, Matrix4 parent, com.badlogic.gdx.utils.FloatArray points) {
        Matrix4 transform = new Matrix4(parent).mul(new Matrix4(node.translation, node.rotation, node.scale));
        Vector3 point = new Vector3();
        for (var binding : node.parts) {
            var material = data.materials.select(value -> value.id.equals(binding.materialId)).iterator().next();
            Color color = material.diffuse == null ? Color.WHITE : material.diffuse;
            for (var mesh : data.meshes) {
                for (var part : mesh.parts) {
                    if (!part.id.equals(binding.meshPartId)) { continue; }
                    for (short index : part.indices) {
                        int at = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                        point.set(mesh.vertices[at], mesh.vertices[at + 1], mesh.vertices[at + 2]).mul(transform);
                        points.addAll(point.x, point.y, point.z);
                        points.addAll(mesh.vertices[at + 6] * color.r, mesh.vertices[at + 7] * color.g,
                              mesh.vertices[at + 8] * color.b, mesh.vertices[at + 9]);
                    }
                }
            }
        }
        for (var child : node.children) { body(data, child, transform, points); }
    }

    private static float noise(int value) {
        value ^= value >>> 16; value *= 0x45d9f3b; value ^= value >>> 16;
        return (value & 0xFFFFFF) / 16777216f;
    }

    @Override
    public void dispose() {
        begin();
        exhaust.dispose();
        if (mesh != null) { mesh.dispose(); mesh = null; }
        if (shader != null) { GpuShaderManager.dispose(shader); shader = null; }
        vertices = null;
        body = null;
        Arrays.fill(smoke, null);
    }
}
