/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;

/** Instanced meshes must disable their instance attributes before unbinding the VAO in a core GL context. */
final class GpuInstancedMesh extends Mesh {
    GpuInstancedMesh(int vertices, int indices, VertexAttributes attributes) {
        super(true, vertices, indices, attributes);
    }

    GpuInstancedMesh(Mesh source) {
        this(source.getNumVertices(), source.getNumIndices(), source.getVertexAttributes());
        float[] vertexData = new float[source.getNumVertices() * source.getVertexSize() / Float.BYTES];
        source.getVertices(vertexData);
        setVertices(vertexData);
        short[] indexData = new short[source.getNumIndices()];
        source.getIndices(indexData);
        setIndices(indexData);
    }

    @Override
    public void unbind(ShaderProgram shader, int[] locations, int[] instanceLocations) {
        // libGDX 1.14.2 unbinds the VAO first, then disables instance attributes with no VAO bound.
        if (instances != null && instances.getNumInstances() > 0) { instances.unbind(shader, instanceLocations); }
        vertices.unbind(shader, locations);
        if (indices.getNumIndices() > 0) { indices.unbind(); }
    }
}
