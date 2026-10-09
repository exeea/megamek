/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Cosmetic water and steam from the installed geyser assets. The server alone changes eruption state. */
final class GpuGeysers implements Disposable {
    private static final int CAPACITY = 4096;
    private static final int MAX_PER_SOURCE = 96;
    private final GpuEffectBatch spray = new GpuEffectBatch(CAPACITY, "geyser-spray");
    private final List<Emitter> visible = new ArrayList<>();
    private final Vector3 point = new Vector3(), end = new Vector3(), width = new Vector3(), length = new Vector3();

    /** Chunk-owned positions, replaced atomically with the scenery that owns them. */
    record Emitter(Vector3 origin, float scale, float phase, boolean active, boolean magma) { }

    static Emitter emitter(String asset, Matrix4 transform) {
        if (!asset.equals("scenery/geysers/water-dormant")
              && !asset.equals("scenery/geysers/water-erupting")
              && !asset.equals("scenery/geysers/magma")) { return null; }
        boolean magma = asset.endsWith("magma");
        Vector3 origin = new Vector3(0, 0, magma ? 1.85f : .84f).mul(transform);
        float scale = transform.getScale(new Vector3()).x;
        return new Emitter(origin, scale, noise(origin.x * .17f + origin.y * .31f), asset.endsWith("-erupting"), magma);
    }

    void begin() { visible.clear(); }

    void add(List<Emitter> sources, Camera camera) {
        for (Emitter source : sources) {
            point.set(source.origin()).add(0, 0, source.active() ? 17 * source.scale() : 3 * source.scale());
            if (camera.frustum.sphereInFrustum(point, (source.active() ? 26 : 14) * source.scale())
                  && source.scale() * BoardCamera.pixelsPerUnit(camera, point) > .18f) { visible.add(source); }
        }
    }

    /** A droplet sampled from launch to return. Changing cameras cannot restart or move its trajectory. */
    static Vector3 droplet(Emitter source, int index, float seconds, float gravity, Vector3 wind, Vector3 out) {
        float seed = index * 2.399963f + source.phase() * 31;
        float gravityScale = Math.max(.05f, gravity / BoardAtmosphere.STANDARD_GRAVITY);
        float g = gravityScale * 32 * source.scale();
        float height = (20 + 14 * noise(seed + 3)) * source.scale();
        float vertical = (float) Math.sqrt(2 * g * height), life = 2 * vertical / g;
        float t = phase(seconds / life + noise(seed)) * life;
        float spread = (1.0f + 2.3f * noise(seed + 8)) * source.scale() * (float) Math.sqrt(gravityScale);
        float drift = 6 * (t / life) * (t / life) * source.scale();
        return out.set(source.origin()).add(
              MathUtils.cos(seed) * spread * t + wind.x * wind.z * drift,
              MathUtils.sin(seed) * spread * t + wind.y * wind.z * drift,
              Math.max(0, vertical * t - .5f * g * t * t));
    }

    void render(Camera camera, float seconds, float gravity, Vector3 wind, Color light) {
        spray.begin();
        spray.setSmokeLight(light);
        visible.sort(Comparator.comparingDouble((Emitter e) -> e.origin().dot(camera.direction)).reversed());
        // Keep nearby vents at the fixed budget, then draw those back to front.
        for (int n = Math.max(0, visible.size() - CAPACITY / MAX_PER_SOURCE); n < visible.size(); n++) {
            Emitter source = visible.get(n);
            float scale = source.scale(), seed = source.phase() * 31;
            float detail = MathUtils.clamp(scale * BoardCamera.pixelsPerUnit(camera, source.origin()) / 2, .25f, 1);
            if (!source.magma()) {
                point.set(source.origin()).add(0, 0, .045f * scale);
                width.set(6.7f * scale, 0, 0); length.set(0, 6.7f * scale, 0);
                spray.quad(point, width, length, -1, 400 + phase(seconds / 8 + seed), .46f);
            }
            int wisps = source.active() ? 20 : 8;
            for (int i = 0; i < wisps; i++) {
                float angle = seed + i * 2.399963f;
                float age = phase(seconds / (3.5f + noise(angle) * 2) + noise(angle + 5));
                float top = source.active() && i >= 8 ? 21 : 0;
                float drift = (1 + age * 4) * scale;
                point.set(source.origin()).add(MathUtils.cos(angle) * drift + wind.x * wind.z * age * 8 * scale,
                      MathUtils.sin(angle) * drift + wind.y * wind.z * age * 8 * scale,
                      (top + age * (source.active() ? 10 : 6)) * scale);
                float alpha = MathUtils.sin(age * MathUtils.PI);
                spray.billboard(camera, point, (2 + age * (top > 0 ? 5 : 3)) * scale,
                      200 + i + age, alpha * alpha * (source.active() ? .25f : .11f) * detail);
            }
            if (!source.active() || gravity <= 0) { continue; }
            // Several narrow, overlapping aerated ribbons form the continuous jet.
            // Fine turbulence is shaded within them, without an opaque mesh column.
            for (int i = 0; i < 3; i++) {
                float angle = seed + i * 2.399963f;
                point.set(source.origin()).add(MathUtils.cos(angle) * .45f * scale, MathUtils.sin(angle) * .45f * scale, 0);
                end.set(point).add(MathUtils.sin(seconds * 1.3f + angle) * 1.1f * scale,
                      MathUtils.cos(seconds * 1.1f + angle) * .8f * scale,
                      (25 + 2 * MathUtils.sin(seconds * 1.7f + angle)) * scale);
                spray.ribbon(camera, point, end, (1.15f + i * .32f) * scale,
                      100 + i + phase(seconds / 3 + source.phase()), .73f);
            }
            int drops = Math.max(12, Math.round(40 * detail));
            for (int i = 0; i < drops; i++) {
                droplet(source, i, seconds, gravity, wind, point);
                droplet(source, i, seconds - .035f, gravity, wind, end);
                // Hide the discontinuity where a completed ballistic path starts again at the nozzle.
                if (point.dst2(end) > 9 * scale * scale) { end.set(point).add(0, 0, .4f * scale); }
                float size = (.12f + .15f * noise(i + seed)) * scale;
                spray.ribbon(camera, end, point, size, 300 + i, .58f * detail);
            }
            for (int i = 0; i < 4; i++) {
                float age = phase(seconds * .8f + i * .25f + source.phase());
                float radius = (1 + age * 5.8f) * scale;
                point.set(source.origin()).add(0, 0, .055f * scale);
                width.set(radius, 0, 0); length.set(0, radius, 0);
                spray.quad(point, width, length, -1, age, (1 - age) * .24f);
            }
        }
        spray.render(camera, spray.size());
    }

    int particles() { return spray.size(); }

    private static float phase(float value) { return value - (float) Math.floor(value); }
    private static float noise(float value) { return phase(MathUtils.sin(value * 12.9898f) * 437.585f); }

    @Override
    public void dispose() { spray.dispose(); visible.clear(); }
}
