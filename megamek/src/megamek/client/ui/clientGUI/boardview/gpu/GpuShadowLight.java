/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.IntArray;
import org.lwjgl.opengl.GL32;

/** One hardware depth map for terrain, models and fog, through libGDX's existing shadow texture binding. */
final class GpuShadowLight extends DirectionalShadowLight {
    /** near and far as SlabCamera reads them: the slab's top and bottom heights, or distances along a lower light. */
    GpuShadowLight(int width, int height, float viewportWidth, float viewportHeight, float near, float far) {
        super(width, height, viewportWidth, viewportHeight, near, far);
        cam = new SlabCamera();
        cam.viewportWidth = viewportWidth;
        cam.viewportHeight = viewportHeight;
        cam.near = near;
        cam.far = far;
        fbo.dispose();
        fbo = depthBuffer(width, height);
        // The binder applies this descriptor too; setting only Texture's filter would be overwritten by libGDX.
        textureDesc.minFilter = textureDesc.magFilter = Texture.TextureFilter.Linear;
    }

    /** Caches copy only depth, with exactly the light's attachment format and normal framebuffer ownership. */
    static FrameBuffer depthBuffer(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addDepthTextureAttachment(GL30.GL_DEPTH_COMPONENT16, GL20.GL_UNSIGNED_SHORT);
        return new DepthBuffer(builder);
    }

    @Override
    public void begin() {
        // Clear the complete map, including its unoccluded border, after screen passes disabled depth writes.
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glClearDepthf(1);
        super.begin();
        depthClamp(true);
    }

    @Override
    public void end() {
        depthClamp(false);
        super.end();
    }

    /**
     * Casters outside the slab (SlabCamera) still cast, at its top or bottom: depth clamping keeps what the slab's planes
     * would clip.
     */
    static void depthClamp(boolean enabled) {
        if (enabled) { Gdx.gl.glEnable(GL32.GL_DEPTH_CLAMP); }
        else { Gdx.gl.glDisable(GL32.GL_DEPTH_CLAMP); }
    }

    /**
     * Whether a light along {@code direction} (normalized) takes the slab (SlabCamera): every light the world-fixed sun
     * and moon cast, down to a little below the sun's lowest. A lower one, only the camera-relative light, keeps depth
     * along the light: over its long slab, the drift between a comparison's texels would need a bias that lifts shadows
     * off their casters.
     */
    static boolean slab(Vector3 direction) {
        return direction.z <= BoardAtmosphere.LOWEST_SUN_Z * .98f;
    }

    /**
     * An orthographic light camera whose depth measures height across the casters' slab instead of distance along the
     * light: its {@code near} and {@code far} are the world heights of the slab's top (depth 0) and bottom (depth 1).
     * Along every ray of light depth still grows away from the sun, so the map compares as before, while its precision
     * spans the slab's height rather than the board's whole extent along the light. Across the map it is the light's own
     * orthographic projection. A light that does not take the slab (slab) is a plain orthographic camera.
     */
    static final class SlabCamera extends OrthographicCamera {
        @Override
        public void update(boolean updateFrustum) {
            if (!slab(direction)) {
                super.update(updateFrustum);
                return;
            }
            super.update(false);
            float scale = 2 / (near - far);
            float[] m = combined.val;
            m[Matrix4.M20] = 0;
            m[Matrix4.M21] = 0;
            m[Matrix4.M22] = -scale;
            m[Matrix4.M23] = scale * near - 1;
            if (updateFrustum) {
                invProjectionView.set(combined).inv();
                frustum.update(invProjectionView);
            }
        }
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
