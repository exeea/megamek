/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.GLVersion;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.opengl.GL11;

/**
 * The board without its units as green lines on a dark ground: the camera depth pass's own triangles and levels of
 * detail, first as a hidden-line depth fill, then in line mode. Units show thermal signatures. Water surfaces, roads,
 * decals, ground cover and cut-away buildings are outside that pass and draw no lines.
 */
final class GpuWireframe implements Disposable {
    final GpuThermalUnits units = new GpuThermalUnits();
    private final ModelBatch batch = new ModelBatch(GpuShaderManager.provider("Wireframe", () ->
          GpuTreeInstances.depthProvider(new DepthShader.Config(null, GpuShaderSource.read("wireframe.frag")))),
          new GpuOpaqueSorter());

    /** Polygon line mode is desktop OpenGL only; GLES and WebGL have none. */
    static boolean supported() {
        return Gdx.graphics.getGLVersion().getType() == GLVersion.Type.OpenGL;
    }

    /**
     * In the scene target, in place of the shaded terrain: a dark ground and the board's depth, which hides edges
     * behind surfaces and gives units, effects and overlays their usual occlusion.
     */
    void fill(Camera camera, GpuTerrain terrain) {
        Gdx.gl.glClearColor(0.01f, 0.03f, 0.02f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        // libGDX's render context tracks neither polygon offset nor polygon mode; later passes must find both reset.
        // Pushed back, the fill hides no surface's own edges.
        Gdx.gl.glColorMask(false, false, false, false);
        Gdx.gl.glEnable(GL20.GL_POLYGON_OFFSET_FILL);
        Gdx.gl.glPolygonOffset(1, 1);
        try {
            terrain.renderDepth(camera, List.of(), batch);
        } finally {
            Gdx.gl.glDisable(GL20.GL_POLYGON_OFFSET_FILL);
            Gdx.gl.glPolygonOffset(0, 0);
            Gdx.gl.glColorMask(true, true, true, true);
        }
    }

    /** Over the composited scene, whose depth it tests: after grading and fog, the lines keep their color. */
    void lines(Camera camera, GpuTerrain terrain) {
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        try {
            terrain.renderDepth(camera, List.of(), batch);
        } finally {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            // The composite leaves depth testing on for the passes after it; the batch's render context turns it off.
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        }
    }

    @Override
    public void dispose() {
        units.dispose();
        batch.dispose();
    }
}
