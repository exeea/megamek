/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;

/** One hardware depth map for terrain, models and fog, through libGDX's existing shadow texture binding. */
final class GpuShadowLight extends DirectionalShadowLight {
    GpuShadowLight(int width, int height, float viewportWidth, float viewportHeight, float near, float far) {
        super(width, height, viewportWidth, viewportHeight, near, far);
        fbo.dispose();
        fbo = depthBuffer(width, height);
        // The binder applies this descriptor too; setting only Texture's filter would be overwritten by libGDX.
        textureDesc.minFilter = textureDesc.magFilter = Texture.TextureFilter.Linear;
    }

    /** Caches copy only depth, with exactly the light's attachment format and normal framebuffer ownership. */
    static FrameBuffer depthBuffer(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addDepthTextureAttachment(GL30.GL_DEPTH_COMPONENT24, GL20.GL_UNSIGNED_INT);
        return new DepthBuffer(builder);
    }

    @Override
    public void begin() {
        // Clear the complete map, including its unoccluded border, after screen passes disabled depth writes.
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(1);
        super.begin();
    }

    /**
     * Copies depth inside each rectangle of runs (x0, y0, x1, y1 texels, four values each) between two maps of one
     * size, as GLFrameBuffer#transfer copies the whole map.
     */
    static void copyDepth(FrameBuffer from, FrameBuffer to, IntArray runs) {
        DepthBuffer.copy(from, to, runs);
    }

    /** libGDX 1.14.2 otherwise attaches a lone texture as COLOR_ATTACHMENT0, even for a depth specification. */
    private static final class DepthBuffer extends FrameBuffer {
        DepthBuffer(GLFrameBuffer.FrameBufferBuilder builder) { super(builder); }

        static void copy(FrameBuffer from, FrameBuffer to, IntArray runs) {
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.getFramebufferHandle());
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.getFramebufferHandle());
            for (int i = 0; i < runs.size; i += 4) {
                int x0 = runs.get(i), y0 = runs.get(i + 1), x1 = runs.get(i + 2), y1 = runs.get(i + 3);
                Gdx.gl30.glBlitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, GL20.GL_DEPTH_BUFFER_BIT, GL20.GL_NEAREST);
            }
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, defaultFramebufferHandle);
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, defaultFramebufferHandle);
        }

        @Override
        protected Texture createTexture(FrameBufferTextureAttachmentSpec specification) {
            Texture texture = super.createTexture(specification);
            texture.bind();
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
            Gdx.gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_COMPARE_FUNC, GL20.GL_LEQUAL);
            return texture;
        }

        @Override
        protected void attachFrameBufferColorTexture(Texture texture) {
            Gdx.gl.glFramebufferTexture2D(GL20.GL_FRAMEBUFFER, GL20.GL_DEPTH_ATTACHMENT, GL20.GL_TEXTURE_2D,
                  texture.getTextureObjectHandle(), 0);
            var none = BufferUtils.newIntBuffer(1);
            none.put(GL20.GL_NONE).flip();
            Gdx.gl30.glDrawBuffers(1, none);
            Gdx.gl30.glReadBuffer(GL20.GL_NONE);
        }
    }
}
