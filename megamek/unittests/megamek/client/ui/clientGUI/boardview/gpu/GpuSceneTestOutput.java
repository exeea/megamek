/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;

/** Pixel fixtures retain their explicit lighting while using the production HDR and display boundary. */
final class GpuSceneTestOutput implements AutoCloseable {
    private final FrameBuffer scene;
    private FrameBuffer pixels;
    private GpuSceneDisplay display;

    GpuSceneTestOutput() {
        this(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    GpuSceneTestOutput(int width, int height) {
        scene = GpuHeatGlow.buffer(width, height, true);
        try {
            pixels = GpuAtmosphere.buffer(width, height, true);
            display = new GpuSceneDisplay();
        } catch (RuntimeException error) {
            scene.dispose();
            if (pixels != null) { pixels.dispose(); }
            throw error;
        }
    }

    FrameBuffer scene() { return scene; }

    /** Borrowed display target for repeated pixel readback without running another conversion pass. */
    FrameBuffer pixels() { return pixels; }

    /** Start a scene with an authored display-color background, preserving the fixture's lighting and draw order. */
    void begin(float red, float green, float blue, float alpha) {
        scene.begin();
        Gdx.gl.glDepthMask(true);
        ScreenUtils.clear(GpuColor.linear(red) * GpuSceneRadiance.SCALE,
              GpuColor.linear(green) * GpuSceneRadiance.SCALE,
              GpuColor.linear(blue) * GpuSceneRadiance.SCALE, alpha, true);
    }

    /** Restore canonical radiance and encode through the production display boundary. */
    void display() { display.render(scene, pixels); }

    /** Explicit actual-atmosphere fixtures apply the complete-source adaptation once. */
    void display(float adaptation) { display.render(scene, pixels, adaptation); }

    /** Leaves display pixels and the matching depth bound for readback. */
    void draw(GpuTerrain terrain, Camera camera, boolean transparent) {
        // setAtmosphere installs a shadow map; populate it before any lit material samples its visibility.
        terrain.renderShadows(camera, List.of());
        begin(.02f, .025f, .035f, 1);
        terrain.render(camera, false);
        if (transparent) { terrain.renderTransparent(camera); }
        display();
    }

    /** Read the actual RGBA16F target in canonical radiance units; alpha retains its original meaning. */
    float[] linearPixels() {
        scene.begin();
        try {
            var buffer = BufferUtils.newFloatBuffer(scene.getWidth() * scene.getHeight() * 4);
            Gdx.gl.glReadPixels(0, 0, scene.getWidth(), scene.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
            float[] values = new float[buffer.capacity()];
            buffer.get(values);
            for (int i = 0; i < values.length; i++) {
                if (i % 4 != 3) { values[i] /= GpuSceneRadiance.SCALE; }
            }
            return values;
        } finally { scene.end(); }
    }

    @Override
    public void close() {
        pixels.end();
        display.dispose();
        scene.dispose();
        pixels.dispose();
    }
}
