/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;

/**
 * Depth cues without fog, in either projection: the distance the view centres on, measured from the scene depth and
 * eased over time, behind which the composite hazes surfaces with their depth there. The focus and all in front of it
 * keep their colour. Render state, never game state. Borrows the screen quad; owns its program and two one-texel
 * targets.
 */
final class GpuDepthCues implements Disposable {
    /** The Tuning panel's Depth cues: the haze's strength, from Crystalline Canyon reviews (GpuDepthCuesSmokeTest). */
    static final float STRENGTH = .08f;
    /** The least depth the view spans, in view heights: from straight above the only depth is the relief's. */
    private static final float MIN_VIEW_DEPTH = 1;
    /** The depth behind the focus over which the haze approaches its strength, as a share of the depth the view spans. */
    private static final float SPAN = 1;
    /** Seconds over which the focus moves about 63% of the way to a new distance. */
    private static final float FOCUS_EASING = .3f;
    private final Mesh quad;
    private ShaderProgram focus;
    /** The eased focus distance in R, written alternately: the other holds the previous frame's. */
    private FrameBuffer focusRead;
    private FrameBuffer focusWrite;
    /** Whether focusRead holds a focus to ease from; a view without the cues forgets it. */
    private boolean focused;
    private float rangeOrigin;
    private float viewDepth;

    GpuDepthCues(Mesh quad) {
        this.quad = quad;
        focus = GpuShaderManager.program(() -> GpuAtmosphere.shader("depth-cues-focus.frag"), next -> focus = next);
    }

    /**
     * This frame's focus from the scene {@code depth} and the nearest {@code water} (null without water), eased by
     * {@code delta} seconds.
     */
    void render(Texture depth, Texture water, BoardProjectionCamera camera, float delta) {
        if (focusRead == null) {
            try {
                focusRead = target();
                focusWrite = target();
            } catch (RuntimeException failure) {
                disposeTargets();
                throw failure;
            }
        }
        // Distances from the orbit pivot: the view's centre stays near zero as the camera zooms or orbits, so the eased
        // focus does not drift with the camera's own distance.
        rangeOrigin = camera.pivotDepth;
        // The depth that the view's height spans over level ground, tan(tilt) of that height, and at least MIN_VIEW_DEPTH that
        // height: from straight above the only depth is the relief's, and a drop of a few levels hazes little.
        float down = MathUtils.clamp(-camera.direction.z, .05f, 1);
        viewDepth = camera.viewportHeight * camera.zoom * Math.max(MIN_VIEW_DEPTH, (float) Math.sqrt(1 - down * down) / down);
        GpuAtmosphere.screenState();
        focusWrite.begin();
        focus.bind();
        depth.bind(0);
        (water == null ? depth : water).bind(1);
        focusRead.getColorBufferTexture().bind(2);
        focus.setUniformi("u_depth", 0);
        focus.setUniformi("u_waterDepth", 1);
        focus.setUniformi("u_previous", 2);
        float[] projection = camera.projection.val;
        focus.setUniformf("u_projectionDepth", projection[Matrix4.M22], projection[Matrix4.M23], projection[Matrix4.M32],
              projection[Matrix4.M33]);
        focus.setUniformf("u_rangeOrigin", rangeOrigin);
        focus.setUniformf("u_blend", focused ? 1 - (float) Math.exp(-Math.max(0, delta) / FOCUS_EASING) : 1);
        quad.render(focus, GL20.GL_TRIANGLES);
        focusWrite.end();
        FrameBuffer previous = focusRead;
        focusRead = focusWrite;
        focusWrite = previous;
        focused = true;
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    }

    /** A frame without the cues: the next one starts from its own focus instead of easing from a stale one. */
    void forget() { focused = false; }

    /** The composite's inputs: the haze's {@code strength} and linear radiance, the focus on texture unit {@code unit}. */
    void bind(ShaderProgram composite, float strength, Color haze, int unit) {
        focusRead.getColorBufferTexture().bind(unit);
        composite.setUniformi("u_depthFocus", unit);
        composite.setUniformf("u_depthCues", strength, 1 / (SPAN * viewDepth), rangeOrigin);
        composite.setUniformf("u_depthHaze", haze.r, haze.g, haze.b);
    }

    /** No cues in this composite; its sampler rests on {@code unit}, which holds an ordinary 2D texture. */
    static void unbind(ShaderProgram composite, int unit) {
        composite.setUniformi("u_depthFocus", unit);
        composite.setUniformf("u_depthCues", 0, 0, 0);
    }

    /** A single float: in an orthographic view distances lie thousands of units from the camera. */
    private static FrameBuffer target() {
        var builder = new GLFrameBuffer.FrameBufferBuilder(1, 1);
        builder.addFloatAttachment(GL30.GL_RG32F, GL30.GL_RG, GL20.GL_FLOAT, true);
        FrameBuffer result = builder.build();
        result.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return result;
    }

    private void disposeTargets() {
        if (focusRead != null) { focusRead.dispose(); focusRead = null; }
        if (focusWrite != null) { focusWrite.dispose(); focusWrite = null; }
        focused = false;
    }

    @Override
    public void dispose() {
        disposeTargets();
        GpuShaderManager.dispose(focus);
    }
}
