/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.ScreenUtils;

/**
 * SMAA 1x over the display-encoded composite, before overlays and the HUD: luma edge detection, blending weights with
 * the reference area and search tables, then neighbourhood blending into the window's framebuffer, which also receives
 * the scene's hardware depth for overlay occlusion. The smaa-*.frag shaders port SMAA.hlsl (Jimenez et al., MIT; the
 * notice is in each); smaa-area.bin and smaa-search.bin are the bytes of its AreaTex.h and SearchTex.h. Borrows the
 * screen quad; owns its programs, the two tables and three display-sized targets.
 */
final class GpuSmaa implements Disposable {
    private final Mesh quad;
    private ShaderProgram edges;
    private ShaderProgram weights;
    private ShaderProgram blend;
    private Texture area;
    private Texture search;
    /** The composite's display-format output, its edges and the blending weights, made at the first display size. */
    private FrameBuffer input;
    private FrameBuffer edgeTarget;
    private FrameBuffer weightTarget;

    GpuSmaa(Mesh quad) {
        this.quad = quad;
        try {
            edges = GpuShaderManager.program(() -> GpuAtmosphere.shader("smaa-edges.frag"), next -> edges = next);
            weights = GpuShaderManager.program(() -> GpuAtmosphere.shader("smaa-weights.frag"), next -> weights = next);
            blend = GpuShaderManager.program(() -> GpuAtmosphere.shader("smaa-blend.frag"), next -> blend = next);
            area = table("smaa-area.bin", 160, 560, GL30.GL_RG8, GL30.GL_RG, 2);
            search = table("smaa-search.bin", 64, 16, GL30.GL_R8, GL30.GL_RED, 1);
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    /** A reference table as numerical unsigned-normalized data, never sRGB decoded; filtered linearly and clamped. */
    private static Texture table(String name, int width, int height, int internalFormat, int format, int channels) {
        byte[] bytes;
        try (var resource = GpuSmaa.class.getResourceAsStream(name)) {
            if (resource == null) { throw new IllegalStateException("Missing " + name); }
            bytes = resource.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load " + name, failure);
        }
        if (bytes.length != width * height * channels) {
            throw new IllegalStateException(name + " holds " + bytes.length + " bytes, not a " + width + "x" + height + " table");
        }
        var pixels = BufferUtils.newByteBuffer(bytes.length);
        pixels.put(bytes).flip();
        var result = new Texture(new GLOnlyTextureData(width, height, 0, internalFormat, format, GL20.GL_UNSIGNED_BYTE));
        // Construction leaves no texture bound. Both tables' rows are whole multiples of four bytes: any unpack
        // alignment reads them unpadded.
        result.bind();
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, width, height, format, GL20.GL_UNSIGNED_BYTE, pixels);
        result.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        result.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    private static FrameBuffer target(int width, int height, int internalFormat, int format) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addColorTextureAttachment(internalFormat, format, GL20.GL_UNSIGNED_BYTE);
        FrameBuffer result = builder.build();
        result.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        result.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    /** Binds the display-sized target the composite draws into, with a viewport covering it. */
    void begin(int width, int height) {
        if (input == null || input.getWidth() != width || input.getHeight() != height) {
            disposeTargets();
            try {
                input = target(width, height, GL30.GL_RGBA8, GL20.GL_RGBA);
                edgeTarget = target(width, height, GL30.GL_RG8, GL30.GL_RG);
                weightTarget = target(width, height, GL30.GL_RGBA8, GL20.GL_RGBA);
            } catch (RuntimeException failure) {
                disposeTargets();
                throw failure;
            }
        }
        input.begin();
    }

    /**
     * The three passes after the composite. The last writes the window at {@code bottom}, as the composite would have:
     * the anti-aliased color and the scene's hardware depth.
     */
    void end(Texture sceneDepth, Camera camera, int bottom) {
        int width = input.getWidth(), height = input.getHeight();
        GpuAtmosphere.screenState();
        // Edge detection discards edgeless pixels, so its target starts cleared; the weights pass writes every pixel.
        edgeTarget.begin();
        ScreenUtils.clear(0, 0, 0, 0);
        input.getColorBufferTexture().bind(0);
        edges.bind();
        edges.setUniformi("u_color", 0);
        edges.setUniformf("u_metrics", 1f / width, 1f / height, width, height);
        quad.render(edges, GL20.GL_TRIANGLES);
        weightTarget.begin();
        edgeTarget.getColorBufferTexture().bind(1);
        area.bind(2);
        search.bind(3);
        weights.bind();
        weights.setUniformi("u_edges", 1);
        weights.setUniformi("u_area", 2);
        weights.setUniformi("u_search", 3);
        weights.setUniformf("u_metrics", 1f / width, 1f / height, width, height);
        quad.render(weights, GL20.GL_TRIANGLES);
        FrameBuffer.unbind();
        HdpiUtils.glViewport(0, bottom, (int) camera.viewportWidth, (int) camera.viewportHeight);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_ALWAYS);
        Gdx.gl.glDepthMask(true);
        weightTarget.getColorBufferTexture().bind(4);
        sceneDepth.bind(5);
        blend.bind();
        blend.setUniformi("u_color", 0);
        blend.setUniformi("u_weights", 4);
        blend.setUniformi("u_depth", 5);
        blend.setUniformf("u_metrics", 1f / width, 1f / height, width, height);
        quad.render(blend, GL20.GL_TRIANGLES);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** Releases the display-sized targets; the programs and tables stay for the next time SMAA is on. */
    void disposeTargets() {
        if (input != null) { input.dispose(); input = null; }
        if (edgeTarget != null) { edgeTarget.dispose(); edgeTarget = null; }
        if (weightTarget != null) { weightTarget.dispose(); weightTarget = null; }
    }

    @Override
    public void dispose() {
        disposeTargets();
        if (area != null) { area.dispose(); area = null; }
        if (search != null) { search.dispose(); search = null; }
        GpuShaderManager.dispose(edges);
        GpuShaderManager.dispose(weights);
        GpuShaderManager.dispose(blend);
    }
}
