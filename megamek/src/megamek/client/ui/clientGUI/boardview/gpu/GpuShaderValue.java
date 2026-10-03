/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.stream.Collectors;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.utils.BufferUtils;

/** A validated, immutable value for one uniform (array elements have their own rows). */
final class GpuShaderValue {
    enum Type {
        FLOAT(GL20.GL_FLOAT, 1), VEC2(GL20.GL_FLOAT_VEC2, 2), VEC3(GL20.GL_FLOAT_VEC3, 3), VEC4(GL20.GL_FLOAT_VEC4, 4),
        INT(GL20.GL_INT, 1), IVEC2(GL20.GL_INT_VEC2, 2), IVEC3(GL20.GL_INT_VEC3, 3), IVEC4(GL20.GL_INT_VEC4, 4),
        BOOL(GL20.GL_BOOL, 1), BVEC2(GL20.GL_BOOL_VEC2, 2), BVEC3(GL20.GL_BOOL_VEC3, 3), BVEC4(GL20.GL_BOOL_VEC4, 4),
        UINT(GL20.GL_UNSIGNED_INT, 1), UVEC2(GL30.GL_UNSIGNED_INT_VEC2, 2), UVEC3(GL30.GL_UNSIGNED_INT_VEC3, 3), UVEC4(GL30.GL_UNSIGNED_INT_VEC4, 4),
        MAT2(GL20.GL_FLOAT_MAT2, 4), MAT3(GL20.GL_FLOAT_MAT3, 9), MAT4(GL20.GL_FLOAT_MAT4, 16),
        MAT2X3(GL30.GL_FLOAT_MAT2x3, 6), MAT3X2(GL30.GL_FLOAT_MAT3x2, 6), MAT2X4(GL30.GL_FLOAT_MAT2x4, 8),
        MAT4X2(GL30.GL_FLOAT_MAT4x2, 8), MAT3X4(GL30.GL_FLOAT_MAT3x4, 12), MAT4X3(GL30.GL_FLOAT_MAT4x3, 12),
        SAMPLER_2D(GL20.GL_SAMPLER_2D, 1), SAMPLER_CUBE(GL20.GL_SAMPLER_CUBE, 1),
        SAMPLER_2D_SHADOW(GL30.GL_SAMPLER_2D_SHADOW, 1), SAMPLER_2D_ARRAY(GL30.GL_SAMPLER_2D_ARRAY, 1);

        final int gl, components;
        Type(int gl, int components) { this.gl = gl; this.components = components; }
        boolean bool() { return this == BOOL || name().startsWith("BVEC"); }
        boolean unsigned() { return this == UINT || name().startsWith("UVEC"); }
        boolean sampler() { return name().startsWith("SAMPLER"); }
        boolean integer() { return this == INT || name().startsWith("IVEC") || bool() || unsigned() || sampler(); }
        static Type of(int gl) { return Arrays.stream(values()).filter(type -> type.gl == gl).findFirst().orElse(null); }
        @Override
        public String toString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    final Type type;
    private final double[] values;
    private GpuShaderValue(Type type, double[] values) { this.type = type; this.values = values; }
    int integer() { return (int) values[0]; }
    double scalar() { return values[0]; }

    static GpuShaderValue parse(Type type, String text) {
        if (type == null) { throw new IllegalArgumentException("This input type is read-only."); }
        String[] words = text.trim().replaceAll("[\\[\\](),;]", " ").trim().split("\\s+");
        if (words.length != type.components) { throw new IllegalArgumentException("Expected " + type.components + " value(s) for " + type + "."); }
        double[] values = new double[words.length];
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (type.bool()) {
                if (word.equalsIgnoreCase("true") || word.equals("1")) { values[i] = 1; }
                else if (word.equalsIgnoreCase("false") || word.equals("0")) { values[i] = 0; }
                else { throw new IllegalArgumentException("Use true/false or 1/0."); }
            } else if (type.integer()) {
                long value = Long.parseLong(word);
                long minimum = type.unsigned() || type.sampler() ? 0 : Integer.MIN_VALUE;
                long maximum = type.unsigned() ? 0xffff_ffffL : Integer.MAX_VALUE;
                if (value < minimum || value > maximum) { throw new IllegalArgumentException("Integer is outside the input's range."); }
                values[i] = value;
            } else {
                float value = Float.parseFloat(word);
                if (!Float.isFinite(value)) { throw new IllegalArgumentException("Use finite numbers."); }
                values[i] = value;
            }
        }
        return new GpuShaderValue(type, values);
    }

    static GpuShaderValue read(int program, int location, Type type, FloatBuffer floats, IntBuffer integers) {
        double[] values = new double[type.components];
        if (type.integer()) {
            integers.clear();
            if (type.unsigned()) { Gdx.gl30.glGetUniformuiv(program, location, integers); }
            else { Gdx.gl.glGetUniformiv(program, location, integers); }
            for (int i = 0; i < values.length; i++) { values[i] = type.unsigned() ? Integer.toUnsignedLong(integers.get(i)) : integers.get(i); }
        } else {
            floats.clear(); Gdx.gl.glGetUniformfv(program, location, floats);
            for (int i = 0; i < values.length; i++) { values[i] = floats.get(i); }
        }
        return new GpuShaderValue(type, values);
    }

    void upload(int location) {
        if (type.integer()) {
            int[] data = Arrays.stream(values).mapToInt(value -> (int) (long) value).toArray();
            if (type.unsigned()) {
                // GL30's libGDX interface omits glUniform2uiv; use the same LWJGL backend for that one entry point.
                switch (type.components) {
                    case 1 -> org.lwjgl.opengl.GL30.glUniform1uiv(location, data);
                    case 2 -> org.lwjgl.opengl.GL30.glUniform2uiv(location, data);
                    case 3 -> org.lwjgl.opengl.GL30.glUniform3uiv(location, data);
                    case 4 -> org.lwjgl.opengl.GL30.glUniform4uiv(location, data);
                    default -> { }
                }
            } else {
                switch (type.components) {
                    case 1 -> Gdx.gl.glUniform1iv(location, 1, data, 0);
                    case 2 -> Gdx.gl.glUniform2iv(location, 1, data, 0);
                    case 3 -> Gdx.gl.glUniform3iv(location, 1, data, 0);
                    case 4 -> Gdx.gl.glUniform4iv(location, 1, data, 0);
                    default -> throw new IllegalStateException("Unsupported integer input");
                }
            }
            return;
        }
        float[] data = new float[values.length];
        for (int i = 0; i < data.length; i++) { data[i] = (float) values[i]; }
        switch (type) {
            case FLOAT -> Gdx.gl.glUniform1fv(location, 1, data, 0);
            case VEC2 -> Gdx.gl.glUniform2fv(location, 1, data, 0);
            case VEC3 -> Gdx.gl.glUniform3fv(location, 1, data, 0);
            case VEC4 -> Gdx.gl.glUniform4fv(location, 1, data, 0);
            case MAT2 -> Gdx.gl.glUniformMatrix2fv(location, 1, false, data, 0);
            case MAT3 -> Gdx.gl.glUniformMatrix3fv(location, 1, false, data, 0);
            case MAT4 -> Gdx.gl.glUniformMatrix4fv(location, 1, false, data, 0);
            default -> {
                FloatBuffer buffer = BufferUtils.newFloatBuffer(data.length); buffer.put(data).flip();
                switch (type) {
                    case MAT2X3 -> Gdx.gl30.glUniformMatrix2x3fv(location, 1, false, buffer);
                    case MAT3X2 -> Gdx.gl30.glUniformMatrix3x2fv(location, 1, false, buffer);
                    case MAT2X4 -> Gdx.gl30.glUniformMatrix2x4fv(location, 1, false, buffer);
                    case MAT4X2 -> Gdx.gl30.glUniformMatrix4x2fv(location, 1, false, buffer);
                    case MAT3X4 -> Gdx.gl30.glUniformMatrix3x4fv(location, 1, false, buffer);
                    case MAT4X3 -> Gdx.gl30.glUniformMatrix4x3fv(location, 1, false, buffer);
                    default -> throw new IllegalStateException("Unsupported float input");
                }
            }
        }
    }

    @Override
    public String toString() {
        return Arrays.stream(values).mapToObj(value -> type.bool() ? Boolean.toString(value != 0)
              : type.integer() ? Long.toString((long) value) : Float.toString((float) value)).collect(Collectors.joining(", "));
    }
}
