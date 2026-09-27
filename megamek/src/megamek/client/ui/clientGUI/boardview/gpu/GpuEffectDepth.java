/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/** One lazy opaque-depth snapshot per effect frame, safe to sample while compositing into the scene target. */
final class GpuEffectDepth implements Disposable {
    private final IntBuffer integers = BufferUtils.newIntBuffer(4);
    private Texture texture;
    private boolean captured;
    private int framebuffer;
    int x, y, width, height;

    void begin() { captured = false; }

    Texture capture() {
        if (captured) { return texture; }
        integers.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, integers);
        x = integers.get(0); y = integers.get(1); width = integers.get(2); height = integers.get(3);
        integers.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, integers);
        framebuffer = integers.get(0);
        if (texture == null || texture.getWidth() != width || texture.getHeight() != height) {
            if (texture != null) { texture.dispose(); }
            texture = new Texture(new GLOnlyTextureData(width, height, 0,
                  GL30.GL_DEPTH_COMPONENT24, GL20.GL_DEPTH_COMPONENT, GL20.GL_UNSIGNED_INT));
            texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        texture.bind(0);
        Gdx.gl.glCopyTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, x, y, width, height);
        captured = true;
        return texture;
    }

    void restoreTarget() {
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, framebuffer);
        Gdx.gl.glViewport(x, y, width, height);
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        captured = false;
    }
}
