/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix3;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;

/** Intercepts the one uniform-upload API used by both our renderers and libGDX's material shaders. */
final class GpuShaderUniforms extends ShaderProgram {
    final String name;
    private final boolean editable = GpuShaderManager.current() != null;
    private final Map<Integer, Uniform> uniforms = new LinkedHashMap<>();
    private final Map<Integer, GpuShaderValue> active = new HashMap<>();
    private Map<String, GpuShaderValue> requested = Map.of();
    private Map<String, GpuShaderValue> applied = requested;
    private final FloatBuffer floats = BufferUtils.newFloatBuffer(16);
    private final IntBuffer integers = BufferUtils.newIntBuffer(4);
    private boolean inspected;

    private static final class Uniform {
        final String name, array;
        final int location, index;
        final GpuShaderValue.Type type;
        GpuShaderValue provided;
        Uniform(String name, String array, int location, int index, GpuShaderValue.Type type) {
            this.name = name; this.array = array; this.location = location; this.index = index; this.type = type;
        }
    }

    GpuShaderUniforms(String name, String vertex, String fragment) { super(vertex, fragment); this.name = name; }

    @Override
    public int fetchUniformLocation(String uniform, boolean pedantic) {
        return super.fetchUniformLocation(uniform, pedantic && !editable);
    }

    void overrides(Map<String, GpuShaderValue> values) { requested = values; }

    private void inspect() {
        if (inspected) { return; }
        inspected = true;
        for (String array : getUniforms()) {
            int size = getUniformSize(array);
            var type = GpuShaderValue.Type.of(getUniformType(array));
            for (int index = 0; index < size; index++) {
                String uniform = size == 1 ? array : array.replaceFirst("\\[0\\]", "[" + index + "]");
                int location = super.fetchUniformLocation(uniform, false);
                if (location >= 0) { uniforms.put(location, new Uniform(uniform, array, location, index, type)); }
            }
        }
    }

    List<GpuShaderInputs.Row> rows() {
        inspect();
        List<GpuShaderInputs.Row> rows = new ArrayList<>();
        for (var uniform : uniforms.values()) {
            GpuShaderValue effective = uniform.type == null ? null : read(uniform);
            GpuShaderValue provided = active.containsKey(uniform.location) ? uniform.provided : effective;
            var override = requested.get(uniform.name);
            rows.add(new GpuShaderInputs.Row(uniform.name, uniform.type, provided == null ? "—" : provided.toString(),
                  effective == null ? "—" : effective.toString(), override == null ? "" : override.toString(),
                  null, uniform.type != null && uniform.type.sampler() ? (double) maxTextureUnits() - 1 : null,
                  uniform.type == null ? "This uniform type is read-only." : uniform.type.sampler()
                        ? "Texture unit index; the renderer supplies the texture."
                        : uniform.type.name().startsWith("MAT") ? "Matrix components in column-major order." : ""));
        }
        return rows;
    }

    private int maxTextureUnits() {
        integers.clear();
        com.badlogic.gdx.Gdx.gl.glGetIntegerv(com.badlogic.gdx.graphics.GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS, integers);
        return integers.get(0);
    }

    private GpuShaderValue read(Uniform uniform) {
        return GpuShaderValue.read(getHandle(), uniform.location, uniform.type, floats, integers);
    }

    @Override
    public void bind() {
        super.bind();
        if (requested != applied) {
            inspect();
            for (int location : active.keySet()) {
                var uniform = uniforms.get(location);
                var next = requested.get(uniform.name);
                if ((next == null || next.type != uniform.type) && uniform.provided != null) { uniform.provided.upload(location); }
            }
            Map<Integer, GpuShaderValue> nextActive = new HashMap<>();
            for (var uniform : uniforms.values()) {
                var value = requested.get(uniform.name);
                if (value != null && value.type == uniform.type) {
                    if (!active.containsKey(uniform.location)) { uniform.provided = read(uniform); }
                    nextActive.put(uniform.location, value);
                }
            }
            active.clear(); active.putAll(nextActive);
            applied = requested;
        }
        active.forEach((location, value) -> value.upload(location));
    }

    /** Remember the renderer's latest value before replacing it. Clearing an override restores that value. */
    private void uploaded(int location, int count) {
        if (active.isEmpty() || location < 0) { return; }
        var first = uniforms.get(location);
        if (first == null) { return; }
        for (var override : active.entrySet()) {
            var uniform = uniforms.get(override.getKey());
            if (uniform.array.equals(first.array) && uniform.index >= first.index && uniform.index < first.index + count) {
                uniform.provided = read(uniform);
                override.getValue().upload(uniform.location);
            }
        }
    }

    @Override
    public void setUniformf(String name, float x) { setUniformf(fetchUniformLocation(name, pedantic), x); }
    @Override
    public void setUniformf(int location, float x) { super.setUniformf(location, x); uploaded(location, 1); }

    @Override
    public void setUniformf(String name, float x, float y) { setUniformf(fetchUniformLocation(name, pedantic), x, y); }
    @Override
    public void setUniformf(int location, float x, float y) { super.setUniformf(location, x, y); uploaded(location, 1); }

    @Override
    public void setUniformf(String name, float x, float y, float z) { setUniformf(fetchUniformLocation(name, pedantic), x, y, z); }
    @Override
    public void setUniformf(int location, float x, float y, float z) { super.setUniformf(location, x, y, z); uploaded(location, 1); }

    @Override
    public void setUniformf(String name, float x, float y, float z, float w) { setUniformf(fetchUniformLocation(name, pedantic), x, y, z, w); }
    @Override
    public void setUniformf(int location, float x, float y, float z, float w) { super.setUniformf(location, x, y, z, w); uploaded(location, 1); }

    @Override
    public void setUniform1fv(String name, float[] values, int offset, int length) {
        setUniform1fv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform1fv(int location, float[] values, int offset, int length) {
        super.setUniform1fv(location, values, offset, length); uploaded(location, length / 1);
    }

    @Override
    public void setUniform2fv(String name, float[] values, int offset, int length) {
        setUniform2fv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform2fv(int location, float[] values, int offset, int length) {
        super.setUniform2fv(location, values, offset, length); uploaded(location, length / 2);
    }

    @Override
    public void setUniform3fv(String name, float[] values, int offset, int length) {
        setUniform3fv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform3fv(int location, float[] values, int offset, int length) {
        super.setUniform3fv(location, values, offset, length); uploaded(location, length / 3);
    }

    @Override
    public void setUniform4fv(String name, float[] values, int offset, int length) {
        setUniform4fv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform4fv(int location, float[] values, int offset, int length) {
        super.setUniform4fv(location, values, offset, length); uploaded(location, length / 4);
    }

    @Override
    public void setUniformi(String name, int x) { setUniformi(fetchUniformLocation(name, pedantic), x); }
    @Override
    public void setUniformi(int location, int x) { super.setUniformi(location, x); uploaded(location, 1); }

    @Override
    public void setUniformi(String name, int x, int y) { setUniformi(fetchUniformLocation(name, pedantic), x, y); }
    @Override
    public void setUniformi(int location, int x, int y) { super.setUniformi(location, x, y); uploaded(location, 1); }

    @Override
    public void setUniformi(String name, int x, int y, int z) { setUniformi(fetchUniformLocation(name, pedantic), x, y, z); }
    @Override
    public void setUniformi(int location, int x, int y, int z) { super.setUniformi(location, x, y, z); uploaded(location, 1); }

    @Override
    public void setUniformi(String name, int x, int y, int z, int w) { setUniformi(fetchUniformLocation(name, pedantic), x, y, z, w); }
    @Override
    public void setUniformi(int location, int x, int y, int z, int w) { super.setUniformi(location, x, y, z, w); uploaded(location, 1); }

    @Override
    public void setUniform1iv(String name, int[] values, int offset, int length) {
        setUniform1iv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform1iv(int location, int[] values, int offset, int length) {
        super.setUniform1iv(location, values, offset, length); uploaded(location, length / 1);
    }

    @Override
    public void setUniform2iv(String name, int[] values, int offset, int length) {
        setUniform2iv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform2iv(int location, int[] values, int offset, int length) {
        super.setUniform2iv(location, values, offset, length); uploaded(location, length / 2);
    }

    @Override
    public void setUniform3iv(String name, int[] values, int offset, int length) {
        setUniform3iv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform3iv(int location, int[] values, int offset, int length) {
        super.setUniform3iv(location, values, offset, length); uploaded(location, length / 3);
    }

    @Override
    public void setUniform4iv(String name, int[] values, int offset, int length) {
        setUniform4iv(fetchUniformLocation(name, pedantic), values, offset, length);
    }
    @Override
    public void setUniform4iv(int location, int[] values, int offset, int length) {
        super.setUniform4iv(location, values, offset, length); uploaded(location, length / 4);
    }

    @Override
    public void setUniformMatrix(String name, Matrix3 matrix, boolean transpose) {
        setUniformMatrix(fetchUniformLocation(name, pedantic), matrix, transpose);
    }
    @Override
    public void setUniformMatrix(int location, Matrix3 matrix, boolean transpose) {
        super.setUniformMatrix(location, matrix, transpose); uploaded(location, 1);
    }
    @Override
    public void setUniformMatrix3fv(String name, FloatBuffer buffer, int count, boolean transpose) {
        super.setUniformMatrix3fv(name, buffer, count, transpose); uploaded(fetchUniformLocation(name, pedantic), count);
    }

    @Override
    public void setUniformMatrix(String name, Matrix4 matrix, boolean transpose) {
        setUniformMatrix(fetchUniformLocation(name, pedantic), matrix, transpose);
    }
    @Override
    public void setUniformMatrix(int location, Matrix4 matrix, boolean transpose) {
        super.setUniformMatrix(location, matrix, transpose); uploaded(location, 1);
    }
    @Override
    public void setUniformMatrix4fv(String name, FloatBuffer buffer, int count, boolean transpose) {
        super.setUniformMatrix4fv(name, buffer, count, transpose); uploaded(fetchUniformLocation(name, pedantic), count);
    }

    @Override
    public void setUniformMatrix4fv(int location, float[] values, int offset, int length) {
        super.setUniformMatrix4fv(location, values, offset, length); uploaded(location, length / 16);
    }
}
