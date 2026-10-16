/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * Ground-truth ambient occlusion of the opaque scene (ambient-occlusion-horizon.frag), which the lit surfaces apply to
 * their ambient term (ambient-occlusion.glsl). It runs between the depth pre-pass and the opaque colour draws, in the
 * colour stage of {@link GpuTerrain#render(Camera, Runnable, Runnable)}: half-resolution horizons from the pre-pass
 * depth, then two depth-aware blur passes. While this attribute is in the terrain's environment the pre-pass also draws
 * the relief, so the occlusion sees the ground and the trees; buildings, props and units neither cast nor receive it.
 * Its presence compiles ambientOcclusionFlag into the lit programs (GpuCloudShadow.prefix), so a switch is a lighting
 * change behind the terrain's loading stage, as specular anti-aliasing is. The occlusion is current from {@link #render}
 * to {@link #end}; other draws read visibility 1. Its targets follow the scene depth's size, so the window and the
 * render resolution. Owns its targets, programs and screen quad; one instance per view, which its copies share.
 */
final class GpuAmbientOcclusion extends Attribute implements Disposable {
    static final long TYPE = register("boardAmbientOcclusion");
    /** The world radius in metres: about the foot of a one-level cliff, a building's base, the ground around a trunk. */
    static final float RADIUS_METRES = 4;
    /** The largest screen radius in scene pixels: a close view searches a smaller world radius instead of more pixels. */
    static final float MAX_RADIUS_PIXELS = 160;
    private final IntBuffer query = BufferUtils.newIntBuffer(16);
    private Mesh quad;
    private ShaderProgram horizon;
    private ShaderProgram blur;
    /** Visibility 1 at camera distance 0, which no fragment has: what the colour programs read without an occlusion. */
    private Texture fallback;
    /** The half-resolution horizons, where the second blur pass leaves the final occlusion, and the first's output. */
    private FrameBuffer horizons;
    private FrameBuffer blurred;
    private float strength = 1;
    private boolean current;
    /** What the colour programs read with the occlusion: the projection's depth terms and a scene pixel's world size. */
    private final float[] projectionDepth = new float[4];
    private float pixelBase;
    private float pixelSlope;
    private int revision;

    GpuAmbientOcclusion() { super(TYPE); }

    /**
     * Puts this attribute in the environment while {@code enabled}, else removes it and its targets; called before the
     * frame's terrain is submitted, as material readiness follows the environment. The strength, an exponent on the
     * visibility, applies from the next occlusion.
     */
    void configure(Environment environment, boolean enabled, float strength) {
        this.strength = strength;
        if (!enabled) {
            environment.remove(TYPE);
            disposeTargets();
            return;
        }
        if (horizon == null) { createPrograms(); }
        if (environment.get(TYPE) != this) { environment.set(this); }
    }

    /**
     * This frame's occlusion from the scene depth {@code depth}, which the depth pre-pass has just drawn. It runs inside
     * the terrain's opaque batch, through the render context that tracks the bound textures and the state it changes,
     * and restores the scene target and viewport. Nothing without this attribute in the terrain's environment or without
     * a pre-pass (the Tactical View, the Classic columns).
     */
    void render(Camera camera, GpuTerrain terrain, Texture depth) {
        current = false;
        if (terrain.environment().get(TYPE) != this || !terrain.prepassed()) { return; }
        RenderContext context = terrain.renderContext();
        int width = (depth.getWidth() + 1) / 2, height = (depth.getHeight() + 1) / 2;
        if (horizons == null || horizons.getWidth() != width || horizons.getHeight() != height) {
            disposeTargets();
            horizons = createTarget(width, height);
            blurred = createTarget(width, height);
            // Creating a texture binds it on the active unit, behind the binder's back: it forgets what it had bound.
            context.textureBinder.begin();
        }
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query);
        int previous = query.get(0);
        query.clear();
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query);
        int viewportX = query.get(0), viewportY = query.get(1), viewportWidth = query.get(2), viewportHeight = query.get(3);
        context.setDepthTest(0);
        context.setDepthMask(false);
        context.setBlending(false, GL20.GL_ONE, GL20.GL_ZERO);
        context.setCullFace(0);
        float[] projection = camera.projection.val;
        projectionDepth[0] = projection[Matrix4.M22];
        projectionDepth[1] = projection[Matrix4.M23];
        projectionDepth[2] = projection[Matrix4.M32];
        projectionDepth[3] = projection[Matrix4.M33];
        // A scene pixel's world size at camera distance d: clip w (d in perspective, 1 orthographic) over the pixel scale.
        float pixelScale = projection[Matrix4.M11] * depth.getHeight() / 2;
        pixelBase = projection[Matrix4.M33] / pixelScale;
        pixelSlope = -projection[Matrix4.M32] / pixelScale;
        horizons.bind();
        Gdx.gl.glViewport(0, 0, width, height);
        horizon.bind();
        horizon.setUniformi("u_depth", context.textureBinder.bind(depth));
        horizon.setUniformf("u_projectionDepth", projectionDepth[0], projectionDepth[1], projectionDepth[2],
              projectionDepth[3]);
        horizon.setUniformf("u_projectionScale", projection[Matrix4.M00], projection[Matrix4.M11]);
        horizon.setUniformf("u_radius", BoardRelief.metres(RADIUS_METRES), MAX_RADIUS_PIXELS);
        quad.render(horizon, GL20.GL_TRIANGLES);
        blur(blurred, horizons, 1, 0, 1, context);
        blur(horizons, blurred, 0, 1, strength, context);
        Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, previous);
        Gdx.gl.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
        revision++;
        current = true;
    }

    private void blur(FrameBuffer target, FrameBuffer source, int x, int y, float exponent, RenderContext context) {
        target.bind();
        blur.bind();
        blur.setUniformi("u_occlusion", context.textureBinder.bind(source.getColorBufferTexture()));
        blur.setUniformi("u_direction", x, y);
        blur.setUniformf("u_blur", pixelBase, pixelSlope, exponent);
        quad.render(blur, GL20.GL_TRIANGLES);
    }

    /** The draws that read this frame's occlusion are done: the opaque scene and the units. Later draws read 1. */
    void end() { current = false; }

    /** Native checks: the target holding the last occlusion (visibility, camera distance), or null. */
    FrameBuffer target() { return horizons; }

    private Texture texture() { return current ? horizons.getColorBufferTexture() : fallback; }

    /** The colour programs' inputs (ambient-occlusion.glsl); a program without ambientOcclusionFlag has none of them. */
    static void register(DefaultShader shader) {
        shader.register("u_ambientOcclusion", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = attributes.get(GpuAmbientOcclusion.class, TYPE);
                // Touched every draw: material textures can evict it from the binder's slots.
                if (occlusion != null) { target.set(id, occlusion.texture()); }
            }
        });
        shader.register("u_projectionDepth", new BaseShader.LocalSetter() {
            private GpuAmbientOcclusion uploaded;
            private int uploadedRevision = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = attributes.get(GpuAmbientOcclusion.class, TYPE);
                if (occlusion == null || occlusion == uploaded && occlusion.revision == uploadedRevision) { return; }
                uploaded = occlusion;
                uploadedRevision = occlusion.revision;
                float[] depth = occlusion.projectionDepth;
                target.set(id, depth[0], depth[1], depth[2], depth[3]);
            }
        });
        shader.register("u_ambientOcclusionPixel", new BaseShader.LocalSetter() {
            private GpuAmbientOcclusion uploaded;
            private int uploadedRevision = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var occlusion = attributes.get(GpuAmbientOcclusion.class, TYPE);
                if (occlusion == null || occlusion == uploaded && occlusion.revision == uploadedRevision) { return; }
                uploaded = occlusion;
                uploadedRevision = occlusion.revision;
                target.set(id, occlusion.pixelBase, occlusion.pixelSlope);
            }
        });
    }

    /** Built with the setting's first switch on, which shows the terrain's loading stage. */
    private void createPrograms() {
        try {
            quad = GpuAtmosphere.screenQuad();
            fallback = new Texture(new GLOnlyTextureData(1, 1, 0, GL30.GL_RG32F, GL30.GL_RG, GL20.GL_FLOAT));
            fallback.bind();
            var pixel = BufferUtils.newFloatBuffer(2);
            pixel.put(1).put(0).flip();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL30.GL_RG, GL20.GL_FLOAT, pixel);
            horizon = GpuShaderManager.program(() -> GpuAtmosphere.shader("ambient-occlusion-horizon.frag"),
                  next -> horizon = next);
            blur = GpuShaderManager.program(() -> GpuAtmosphere.shader("ambient-occlusion-blur.frag"), next -> blur = next);
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    private static FrameBuffer createTarget(int width, int height) {
        var builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
        builder.addFloatAttachment(GL30.GL_RG32F, GL30.GL_RG, GL20.GL_FLOAT, true);
        FrameBuffer result = builder.build();
        result.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        result.getColorBufferTexture().setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        return result;
    }

    private void disposeTargets() {
        current = false;
        if (horizons != null) { horizons.dispose(); horizons = null; }
        if (blurred != null) { blurred.dispose(); blurred = null; }
    }

    @Override
    public void dispose() {
        disposeTargets();
        if (fallback != null) { fallback.dispose(); fallback = null; }
        if (quad != null) { quad.dispose(); quad = null; }
        GpuShaderManager.dispose(horizon);
        GpuShaderManager.dispose(blur);
        horizon = null;
        blur = null;
    }

    /** One instance per view: a copy is the same occlusion. */
    @Override
    public Attribute copy() { return this; }

    @Override
    public int compareTo(Attribute other) { return Long.compare(type, other.type); }
}
