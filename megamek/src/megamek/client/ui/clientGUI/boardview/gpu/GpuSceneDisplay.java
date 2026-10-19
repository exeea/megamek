/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/** Final display conversion for isolated scene previews, sharing the board's highlight shoulder. */
final class GpuSceneDisplay implements Disposable {
    private final Mesh quad = GpuAtmosphere.screenQuad();
    private ShaderProgram shader;

    GpuSceneDisplay() {
        try {
            shader = GpuShaderManager.program(() -> GpuAtmosphere.shader("scene-display.frag"), next -> shader = next);
        } catch (RuntimeException failure) {
            quad.dispose();
            throw failure;
        }
    }

    /** Equal-size targets with 24-bit depth. Leaves the display target bound for post-composite overlays. */
    void render(FrameBuffer source, FrameBuffer target) {
        render(source, target, 1);
    }

    /** Adapt the complete canonical scene once; coverage and copied hardware depth are not light. */
    void render(FrameBuffer source, FrameBuffer target, float exposure) {
        target.bind();
        Gdx.gl.glViewport(0, 0, target.getWidth(), target.getHeight());
        GpuAtmosphere.screenState();
        source.getColorBufferTexture().bind(0);
        shader.bind();
        shader.setUniformi("u_scene", 0);
        shader.setUniformf("u_exposure", exposure);
        quad.render(shader, GL20.GL_TRIANGLES);
        Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source.getFramebufferHandle());
        Gdx.gl30.glBlitFramebuffer(0, 0, source.getWidth(), source.getHeight(), 0, 0, target.getWidth(), target.getHeight(),
              GL20.GL_DEPTH_BUFFER_BIT, GL20.GL_NEAREST);
        target.bind();
    }

    @Override
    public void dispose() {
        quad.dispose();
        GpuShaderManager.dispose(shader);
    }
}
