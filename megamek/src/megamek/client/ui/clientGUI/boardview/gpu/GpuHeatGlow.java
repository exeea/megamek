/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/** Small camera halo from visible hot HDR surfaces. Borrows the screen quad; owns its programs and targets. */
final class GpuHeatGlow implements Disposable {
    private final Mesh quad;
    private ShaderProgram extract;
    private ShaderProgram blur;
    private FrameBuffer glow;
    private FrameBuffer work;

    GpuHeatGlow(Mesh quad) {
        this.quad = quad;
        extract = GpuShaderManager.program(() -> GpuAtmosphere.shader("heat-extract.frag"), next -> extract = next);
        try {
            blur = GpuShaderManager.program(() -> GpuAtmosphere.shader("heat-blur.frag"), next -> blur = next);
        } catch (RuntimeException failure) {
            GpuShaderManager.dispose(extract);
            throw failure;
        }
    }

    /** Shared floating-point target for the scene and heat halo; preserves values above one in either encoding. */
    static FrameBuffer buffer(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addFloatAttachment(GL30.GL_RGBA16F, GL20.GL_RGBA, GL20.GL_FLOAT, true);
        FrameBuffer result = builder.build();
        Texture texture = result.getColorBufferTexture();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    void render(Texture scene, Texture depth, Texture fog, Camera camera, GpuFieldOfView fieldOfView) {
        int width = Math.max(1, (scene.getWidth() + 3) / 4), height = Math.max(1, (scene.getHeight() + 3) / 4);
        if (glow == null || glow.getWidth() != width || glow.getHeight() != height) {
            disposeBuffers();
            glow = buffer(width, height);
            try {
                work = buffer(width, height);
            } catch (RuntimeException failure) {
                disposeBuffers();
                throw failure;
            }
        }
        GpuAtmosphere.screenState();
        glow.begin();
        extract.bind();
        scene.bind(0);
        // Only the downsample uses bilinear filtering; restore exact scene pixels before its full-size composite.
        scene.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        depth.bind(2);
        if (fog != null) { fog.bind(1); }
        extract.setUniformi("u_scene", 0);
        extract.setUniformi("u_depth", 2);
        extract.setUniformi("u_fog", fog == null ? 0 : 1);
        extract.setUniformf("u_fogEnabled", fog == null ? 0 : 1);
        extract.setUniformf("u_sourcePixel", 1f / scene.getWidth(), 1f / scene.getHeight());
        boolean fovActive = fieldOfView != null && fieldOfView.active();
        extract.setUniformf("u_fovEnabled", fovActive ? 1 : 0);
        if (fovActive) { fieldOfView.bindMask(extract, camera); }
        try {
            quad.render(extract, GL20.GL_TRIANGLES);
        } finally {
            scene.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            glow.end();
        }

        blur(glow, work, 1f / width, 0);
        blur(work, glow, 0, 1f / height);
    }

    private void blur(FrameBuffer source, FrameBuffer target, float x, float y) {
        target.begin();
        blur.bind();
        source.getColorBufferTexture().bind(0);
        blur.setUniformi("u_source", 0);
        blur.setUniformf("u_step", x, y);
        quad.render(blur, GL20.GL_TRIANGLES);
        target.end();
    }

    Texture texture() { return glow.getColorBufferTexture(); }

    void disposeBuffers() {
        if (glow != null) { glow.dispose(); glow = null; }
        if (work != null) { work.dispose(); work = null; }
    }

    @Override
    public void dispose() {
        disposeBuffers();
        GpuShaderManager.dispose(extract);
        GpuShaderManager.dispose(blur);
    }
}
