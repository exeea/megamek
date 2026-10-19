/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/** Linear-light model overlays over an encoded display, without changing the HUD or scene exposure. */
final class GpuLinearOverlay implements Disposable {
    // Desktop GL state is absent from libGDX's GLES interface; the board requires desktop GL 3.3.
    static final int FRAMEBUFFER_SRGB = 0x8DB9;
    private static final int BACK_LEFT = 0x0402;
    private final IntBuffer values = BufferUtils.newIntBuffer(4);
    private FrameBuffer fallback;
    private Boolean defaultSrgb;
    private boolean active, copied, previousSrgb, previousScissor;
    private int previousDraw, previousRead, x, y, width, height;
    private int scissorX, scissorY, scissorWidth, scissorHeight;

    /** Only call for nonempty draws. The caller supplies unscaled linear display RGB and preserves depth. */
    void begin() {
        if (active) { throw new IllegalStateException("Nested linear overlay"); }
        previousDraw = integer(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        previousSrgb = Gdx.gl.glIsEnabled(FRAMEBUFFER_SRGB);
        copied = false;
        boolean srgb;
        if (previousDraw == 0 && defaultSrgb != null) { srgb = defaultSrgb; }
        else {
            values.clear();
            Gdx.gl.glGetFramebufferAttachmentParameteriv(GL30.GL_DRAW_FRAMEBUFFER,
                  previousDraw == 0 ? BACK_LEFT : GL20.GL_COLOR_ATTACHMENT0,
                  GL30.GL_FRAMEBUFFER_ATTACHMENT_COLOR_ENCODING, values);
            srgb = values.get(0) == GL30.GL_SRGB;
            if (previousDraw == 0) { defaultSrgb = srgb; }
        }
        active = true;
        if (srgb) {
            Gdx.gl.glEnable(FRAMEBUFFER_SRGB);
            return;
        }
        // Ordinary offscreen display targets, or a window without sRGB storage, need a scoped fallback.
        // Both endpoint targets carry encoded RGB. Disabled sRGB conversion makes the two blits byte copies.
        previousRead = integer(GL30.GL_READ_FRAMEBUFFER_BINDING);
        values.clear(); Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, values);
        x = values.get(0); y = values.get(1); width = values.get(2); height = values.get(3);
        previousScissor = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
        values.clear(); Gdx.gl.glGetIntegerv(GL20.GL_SCISSOR_BOX, values);
        scissorX = values.get(0); scissorY = values.get(1);
        scissorWidth = values.get(2); scissorHeight = values.get(3);
        copied = true;
        try {
            if (fallback == null || fallback.getWidth() != width || fallback.getHeight() != height) {
                if (fallback != null) { fallback.dispose(); fallback = null; }
                var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
                builder.addColorTextureAttachment(GL30.GL_SRGB8_ALPHA8, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE);
                // The board window and existing display/export targets use the same 24-bit depth format.
                builder.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
                fallback = builder.build();
            }
            Gdx.gl.glDisable(FRAMEBUFFER_SRGB);
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousDraw);
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fallback.getFramebufferHandle());
            Gdx.gl30.glBlitFramebuffer(x, y, x + width, y + height, 0, 0, width, height,
                  GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT, GL20.GL_NEAREST);
            Gdx.gl.glViewport(0, 0, width, height);
            Gdx.gl.glScissor(scissorX - x, scissorY - y, scissorWidth, scissorHeight);
            if (previousScissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); }
            Gdx.gl.glEnable(FRAMEBUFFER_SRGB);
        } catch (RuntimeException | Error failure) {
            restore();
            throw failure;
        }
    }

    /** Return only color; the overlay must not replace the original scene/picking depth. Call in finally. */
    void end() {
        if (!active) { throw new IllegalStateException("Linear overlay is not active"); }
        try {
            if (copied) {
                Gdx.gl.glDisable(FRAMEBUFFER_SRGB);
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
                Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fallback.getFramebufferHandle());
                Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
                Gdx.gl30.glBlitFramebuffer(0, 0, width, height, x, y, x + width, y + height,
                      GL20.GL_COLOR_BUFFER_BIT, GL20.GL_NEAREST);
            }
        } finally { restore(); }
    }

    private void restore() {
        if (copied) {
            Gdx.gl.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            Gdx.gl.glViewport(x, y, width, height);
            Gdx.gl.glScissor(scissorX, scissorY, scissorWidth, scissorHeight);
            if (previousScissor) { Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST); }
            else { Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST); }
        }
        if (previousSrgb) { Gdx.gl.glEnable(FRAMEBUFFER_SRGB); }
        else { Gdx.gl.glDisable(FRAMEBUFFER_SRGB); }
        active = false;
    }

    private int integer(int parameter) {
        values.clear(); Gdx.gl.glGetIntegerv(parameter, values); return values.get(0);
    }

    @Override
    public void dispose() {
        if (fallback != null) { fallback.dispose(); fallback = null; }
    }
}
