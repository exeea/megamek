/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;

/** Shared bounded quad batching; particle and beam shaders own their distinct appearances. At most two draws. */
final class GpuEffectBatch implements Disposable {
    private static final int STRIDE = 7;
    private final int capacity;
    private final String fragment;
    private final Color smokeLight = new Color(Color.WHITE);
    private final Vector3 quadWidth = new Vector3(), quadLength = new Vector3(), quadOrigin = new Vector3();
    private float[] vertices;
    private Mesh mesh;
    private ShaderProgram shader;
    private int offset;

    GpuEffectBatch(int capacity) { this(capacity, "particles"); }

    GpuEffectBatch(int capacity, String fragment) {
        if (capacity < 1 || capacity > 16383) { throw new IllegalArgumentException("Effect capacity"); }
        this.capacity = capacity;
        this.fragment = fragment;
    }

    void begin() { offset = 0; }

    void billboard(Camera camera, Vector3 center, float radius, float kind, float alpha) {
        quadWidth.set(camera.direction).crs(camera.up).nor().scl(radius);
        quadLength.set(camera.up).scl(radius);
        quad(center, quadWidth, quadLength, -1, kind, alpha);
    }

    /** A camera-facing streak; an end-on view retains a small head instead of collapsing to zero area. */
    void ribbon(Camera camera, Vector3 start, Vector3 finish, float width, float kind, float alpha) {
        quadLength.set(finish).sub(start);
        if (quadLength.isZero(.001f)) { return; }
        quadWidth.set(quadLength).crs(camera.direction);
        if (quadWidth.len2() < quadLength.len2() * .0001f) {
            quadWidth.set(camera.direction).crs(camera.up);
            quadLength.set(camera.up).scl(width * 2);
            quadOrigin.set(finish).mulAdd(quadLength, -.5f);
        } else { quadOrigin.set(start); }
        quadWidth.nor().scl(width);
        quad(quadOrigin, quadWidth, quadLength, 0, kind, alpha);
    }

    void quad(Vector3 origin, Vector3 width, Vector3 length, float from, float kind, float alpha) {
        if (alpha <= 0 || offset == capacity * 4 * STRIDE) { return; }
        if (mesh == null) { create(); }
        for (int corner = 0; corner < 4; corner++) {
            float x = corner == 1 || corner == 2 ? 1 : -1;
            float y = corner < 2 ? from : 1;
            vertices[offset++] = origin.x + width.x * x + length.x * y;
            vertices[offset++] = origin.y + width.y * x + length.y * y;
            vertices[offset++] = origin.z + width.z * x + length.z * y;
            vertices[offset++] = x;
            vertices[offset++] = y;
            vertices[offset++] = kind;
            vertices[offset++] = alpha;
        }
    }

    int size() { return offset / (4 * STRIDE); }

    /** Display-encoded light on smoke; flames and jets stay emissive. White draws smoke as authored. */
    void setSmokeLight(Color light) { smokeLight.set(light); }

    void render(Camera camera, int smokeCount) {
        if (offset == 0) { return; }
        mesh.setVertices(vertices, 0, offset);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        try {
            shader.bind();
            shader.setUniformMatrix("u_projView", camera.combined);
            if (shader.hasUniform("u_light")) {
                shader.setUniformf("u_light", smokeLight.r, smokeLight.g, smokeLight.b);
            }
            int smoke = Math.min(smokeCount, size());
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            if (smoke > 0) { mesh.render(shader, GL20.GL_TRIANGLES, 0, smoke * 6); }
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            if (size() > smoke) { mesh.render(shader, GL20.GL_TRIANGLES, smoke * 6, (size() - smoke) * 6); }
        } finally {
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    private void create() {
        shader = GpuGlsl.compile(fragment, GpuShaderSource.read("effects.vert"), GpuShaderSource.read(fragment + ".frag"));
        vertices = new float[capacity * 4 * STRIDE];
        short[] indices = new short[capacity * 6];
        int[] corners = { 0, 1, 2, 2, 3, 0 };
        for (int quad = 0; quad < capacity; quad++) {
            for (int index = 0; index < corners.length; index++) { indices[quad * 6 + index] = (short) (quad * 4 + corners[index]); }
        }
        mesh = new Mesh(false, capacity * 4, indices.length, VertexAttribute.Position(), VertexAttribute.TexCoords(0), VertexAttribute.TexCoords(1));
        mesh.setIndices(indices);
    }

    @Override
    public void dispose() {
        if (mesh != null) { mesh.dispose(); mesh = null; }
        if (shader != null) { shader.dispose(); shader = null; }
        vertices = null;
        offset = 0;
    }
}
