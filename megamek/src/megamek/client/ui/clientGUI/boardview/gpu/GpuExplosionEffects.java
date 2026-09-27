/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Arrays;
import java.util.Comparator;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Bounded, depth-clipped fire/smoke volumes. Owns its reusable coverage mesh; the caller owns the attack clock. */
final class GpuExplosionEffects implements Disposable {
    static final int CAPACITY = 128;
    private static final Comparator<Volume> BACK_TO_FRONT = Comparator.comparingDouble((Volume v) -> v.depth).reversed();
    private final Volume[] volumes = new Volume[CAPACITY];
    private final Vector3 burstCenter = new Vector3();
    private final Color light = new Color(Color.WHITE);
    private final Vector3 wind = new Vector3(0, 1, 0);
    private final Vector3 sun = new Vector3(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS).direction()).scl(-1);
    private final GpuEffectDepth ownDepth = new GpuEffectDepth();
    private ShaderProgram shader;
    private Mesh proxy;
    private int count;

    private static final class Volume {
        final Vector3 center = new Vector3();
        float radius, age, fire, alpha, seed, depth, stretch;
    }

    void begin() { count = 0; }
    int size() { return count; }
    void setSmokeLight(Color color) { light.set(color); }
    void setLightDirection(Vector3 direction) { sun.set(direction).scl(-1).nor(); }

    /** The board's travel bearing and normalized visual strength, not a second weather simulation. */
    void setWind(BoardAtmosphere.Effects weather) {
        wind.set(MathUtils.sinDeg(weather.windDirection()), MathUtils.cosDeg(weather.windDirection()), weather.wind());
    }

    /** One expanding, buoyant fire-to-smoke volume, sampled entirely from the caller's normalized attack clock. */
    void burst(Vector3 center, float radius, float age, int seed) {
        if (age < 0 || age >= 1) { return; }
        float expansion = .24f + .76f * (1 - (float) Math.exp(-age * 6));
        float fade = MathUtils.clamp((age - .65f) / .35f, 0, 1);
        burstCenter.set(center).add(0, 0, radius * age * (.3f + age * .2f));
        add(burstCenter, radius * expansion, age, 1, 1 - fade * fade * (3 - 2 * fade), seed);
    }

    void add(Vector3 center, float radius, float age, float fire, float alpha, int seed) {
        if (count == CAPACITY || radius <= 0 || alpha <= 0) { return; }
        Volume volume = volumes[count];
        if (volume == null) { volume = new Volume(); volumes[count] = volume; }
        volume.radius = radius;
        volume.age = MathUtils.clamp(age, 0, 1);
        // The pressure-driven initial blast resists wind; the cooling plume is entrained progressively.
        float drift = radius * wind.z * volume.age * volume.age * 1.4f;
        volume.center.set(center).add(wind.x * drift, wind.y * drift, 0);
        volume.stretch = wind.z * volume.age * .8f;
        volume.fire = MathUtils.clamp(fire, 0, 1);
        volume.alpha = MathUtils.clamp(alpha, 0, 1) / (1 + volume.stretch);
        volume.seed = Math.floorMod(seed, 4096) * .17f;
        count++;
    }

    void render(Camera camera) {
        ownDepth.begin();
        render(camera, ownDepth);
    }

    void render(Camera camera, GpuEffectDepth snapshot) {
        if (count == 0) { return; }
        if (shader == null) {
            String path = "megamek/client/ui/clientGUI/boardview/gpu/explosion";
            shader = new ShaderProgram(Gdx.files.classpath(path + ".vert"), Gdx.files.classpath(path + ".frag"));
            if (!shader.isCompiled()) {
                String error = shader.getLog();
                shader.dispose();
                shader = null;
                throw new IllegalStateException("Explosion shader: " + error);
            }
            // The same unit cube bounds every analytic volume. Allocate/upload once, then move it with uniforms.
            proxy = new Mesh(true, 8, 36, VertexAttribute.Position());
            proxy.setVertices(new float[] {
                  -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1, -1,
                  -1, -1, 1, 1, -1, 1, 1, 1, 1, -1, 1, 1 });
            proxy.setIndices(new short[] {
                  0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
                  0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5,
                  2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 });
        }
        Texture depth = snapshot.capture();
        depth.bind(0);
        for (int i = 0; i < count; i++) { volumes[i].depth = volumes[i].center.dot(camera.direction); }
        Arrays.sort(volumes, 0, count, BACK_TO_FRONT);
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_CULL_FACE);
        // Back faces cover the volume even when the free-flight camera is inside its proxy.
        Gdx.gl.glCullFace(GL20.GL_FRONT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            shader.bind();
            shader.setUniformMatrix("u_projView", camera.combined);
            shader.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
            shader.setUniformf("u_viewport", snapshot.x, snapshot.y, snapshot.width, snapshot.height);
            shader.setUniformf("u_light", light.r, light.g, light.b);
            shader.setUniformf("u_sun", sun);
            shader.setUniformf("u_wind", wind);
            shader.setUniformi("u_depth", 0);
            for (int i = 0; i < count; i++) {
                Volume volume = volumes[i];
                if (!camera.frustum.sphereInFrustum(volume.center, volume.radius * (1 + volume.stretch))) { continue; }
                shader.setUniformf("u_centerRadius", volume.center.x, volume.center.y, volume.center.z, volume.radius);
                shader.setUniformf("u_stretch", volume.stretch);
                shader.setUniformf("u_effect", volume.age, volume.fire, volume.alpha, volume.seed);
                proxy.render(shader, GL20.GL_TRIANGLES);
            }
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glCullFace(GL20.GL_BACK);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    @Override
    public void dispose() {
        ownDepth.dispose();
        if (proxy != null) { proxy.dispose(); proxy = null; }
        if (shader != null) { shader.dispose(); shader = null; }
        begin();
    }
}
