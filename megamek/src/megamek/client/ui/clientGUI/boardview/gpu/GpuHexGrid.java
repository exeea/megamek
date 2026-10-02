/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/** Top view repeats the shared terrain grid over the completed scene, including roofs and units. */
final class GpuHexGrid implements Disposable {
    private final GpuInstancedMesh hex;
    private ShaderProgram shader;
    private List<BoardScene.Tile> tiles;
    private int capacity;

    GpuHexGrid() {
        shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU top-view grid",
              GpuShaderSource.read("hex-grid.vert"), GpuShaderSource.read("hex-grid.frag")
                    .replace("// TERRAIN_PATTERNS", GpuShaderSource.read("terrain-patterns.glsl"))), next -> shader = next);
        hex = new GpuInstancedMesh(6, 12, new VertexAttributes(VertexAttribute.Position()));
        hex.setVertices(new float[] { .5f, 0, 0, .25f, .5f, 0, -.25f, .5f, 0,
              -.5f, 0, 0, -.25f, -.5f, 0, .25f, -.5f, 0 });
        hex.setIndices(new short[] { 0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 5 });
    }

    void render(BoardCamera camera, BoardScene scene) {
        if (!camera.isTopDown() || BoardGeometry.tuning().gridShade() >= 1 || scene.tiles().isEmpty()) { return; }
        if (tiles != scene.tiles()) {
            // One render-owned snapshot per board edit; the camera and scale only change uniforms.
            if (scene.tiles().size() > capacity) {
                if (capacity > 0) { hex.disableInstancedRendering(); }
                capacity = Math.max(scene.tiles().size(), capacity * 2);
                hex.enableInstancedRendering(false, capacity, new VertexAttribute(VertexAttributes.Usage.Generic, 3, "a_hex"));
            }
            float[] data = new float[scene.tiles().size() * 3];
            int at = 0;
            for (var tile : scene.tiles()) {
                data[at++] = tile.coords().getX();
                data[at++] = tile.coords().getY();
                data[at++] = tile.elevation();
            }
            hex.setInstanceData(data);
            tiles = scene.tiles();
        }
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            shader.bind();
            shader.setUniformMatrix("u_projTrans", camera.camera.combined);
            shader.setUniformf("u_hexSize", BoardGeometry.width(), BoardGeometry.height(), BoardGeometry.level());
            shader.setUniformf("u_gridShade", BoardGeometry.tuning().gridShade());
            hex.render(shader, GL20.GL_TRIANGLES);
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    @Override
    public void dispose() {
        hex.dispose();
        tiles = null;
        GpuShaderManager.dispose(shader);
    }
}
