/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.model.MeshPart;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import org.lwjgl.opengl.GL40;

/**
 * Tessellation of the sculpted relief where the context offers it (OpenGL 4.0 and later: every Mac's 4.1, current
 * Windows and Linux drivers' 4.6). The relief's facets, 1 to 2 m on a bank or cliff, become Phong-tessellated patches
 * (Boubekeur and Alexa 2008) that follow the smooth normals its shading already uses, so its rims and silhouettes round
 * as its shading does. A program's vertex stage becomes three: a vertex stage that passes the mesh's inputs on, a
 * control stage that splits each edge from the camera, and an evaluation stage that runs the original vertex stage on
 * every point of the patch, so every output the fragment stage reads stays the same. The stages travel in one vertex
 * source, apart at {@link #CONTROL} and {@link #EVALUATION} lines (GpuGlsl splits them). The colour pass and its depth
 * pre-pass twin tessellate the relief alike; the shadow map draws its flat triangles, which the curve only ever rises
 * above, so nothing shades itself. The Tuning panel's
 * Tessellation switch (GpuTerrain#setTessellation, off by default) rebuilds the board; built with it on, only the
 * full-detail triangles that curve ({@link #curves}) go in patches. Render thread.
 */
final class GpuTessellation {
    /**
     * Metres an edge's Phong curve must bulge from it at its middle before it is split (the control stage's TESS_BULGE):
     * less is under half a pixel at the closest view.
     */
    static final float BULGE_METRES = .03f;
    /** The id suffix of a material whose triangles curve ({@link #curved(Material)}). */
    private static final String CURVED = "#curved";
    static final String CONTROL = "#pragma stage(tess_control)";
    static final String EVALUATION = "#pragma stage(tess_evaluation)";
    /** A vertex stage's mesh input (GpuGlsl.libGdx spells libGDX's attributes so). */
    private static final Pattern INPUT = Pattern.compile("[\\t ]*in[\\t ]+(float|vec[234])[\\t ]+(\\w+)[\\t ]*;[\\t ]*");
    private static final Pattern DIRECTIVE = Pattern.compile("[\\t ]*#[\\t ]*(if|ifdef|ifndef|elif|else|endif|define|undef)\\b.*");
    private static final Set<ShaderProgram> PROGRAMS = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Matrix4 VIEW = new Matrix4();
    private static final IntBuffer VIEWPORT = BufferUtils.newIntBuffer(16);

    private GpuTessellation() { }

    /** Whether the context offers tessellation stages: OpenGL 4.0 and later. */
    static boolean available() {
        return GpuGlsl.glsl() >= 400;
    }

    /**
     * A copy of {@code material} for the triangles that curve ({@link #curves}): only parts of such a material pass
     * through the stages, so flat ground and far chunks draw as they would without tessellation.
     */
    static Material curvedCopy(Material material) {
        Material copy = new Material(material);
        copy.id = material.id + CURVED;
        return copy;
    }

    static boolean curved(Material material) { return material.id.endsWith(CURVED); }

    /**
     * Whether a triangle with these corners and vertex normals curves: one of its edges bulges outward more than nine
     * tenths of {@link #BULGE_METRES}. Every other edge keeps one segment, straight (tessellation-control.glsl), so the triangles
     * that do not curve meet those that do without a crack; the margin keeps the stage's own test, whose shape only
     * lessens the bulge, from splitting an edge built as straight.
     */
    static boolean curves(Vector3 a, Vector3 na, Vector3 b, Vector3 nb, Vector3 c, Vector3 nc) {
        float least = .9f * BoardRelief.metres(BULGE_METRES);
        return bulge(a, na, b, nb) > least || bulge(b, nb, c, nc) > least || bulge(c, nc, a, na) > least;
    }

    /**
     * How far the Phong curve of the edge from a to b rises from it at its middle (tessellation-control.glsl); 0 where it
     * would sink, which the evaluation stage keeps flat.
     */
    private static float bulge(Vector3 a, Vector3 na, Vector3 b, Vector3 nb) {
        float la = na.len(), lb = nb.len();
        if (la == 0 || lb == 0) { return 0; }
        float hx = (b.x - a.x) / 2, hy = (b.y - a.y) / 2, hz = (b.z - a.z) / 2;
        float da = (hx * na.x + hy * na.y + hz * na.z) / (la * la), db = (hx * nb.x + hy * nb.y + hz * nb.z) / (lb * lb);
        // The bulge is half of minus this, outward where it leans along the two normals.
        float x = da * na.x - db * nb.x, y = da * na.y - db * nb.y, z = da * na.z - db * nb.z;
        float along = x * (na.x / la + nb.x / lb) + y * (na.y / la + nb.y / lb) + z * (na.z / la + nb.z / lb);
        return along < 0 ? .5f * (float) Math.sqrt(x * x + y * y + z * z) : 0;
    }

    /**
     * The three stages for {@code source}, a vertex stage whose mesh inputs are plain {@code in} declarations, which may
     * stand inside preprocessor conditions. Every stage keeps those conditions, so an input exists in each exactly when
     * the original declares it.
     */
    static String vertex(String source) {
        Map<String, String> inputs = new LinkedHashMap<>();
        StringBuilder pass = new StringBuilder(), control = new StringBuilder(), evaluation = new StringBuilder();
        boolean renamed = false;
        for (String line : source.split("\\R", -1)) {
            Matcher input = INPUT.matcher(line);
            if (input.matches()) {
                String type = input.group(1), name = input.group(2);
                String define = "#define TESS_" + name + "\n";
                if (!carried(name)) {
                    inputs.putIfAbsent(name, type);
                    pass.append("in ").append(type).append(' ').append(name).append(";\nout ").append(type)
                          .append(" vs_").append(name).append(";\n").append(define);
                    control.append("in ").append(type).append(" vs_").append(name).append("[];\nout ").append(type)
                          .append(" tc_").append(name).append("[];\n").append(define);
                    evaluation.append("in ").append(type).append(" tc_").append(name).append("[];\n");
                }
                // The original stage reads its inputs as before: as globals the evaluation stage fills in.
                evaluation.append(type).append(' ').append(name).append(";\n").append(define);
            } else if (DIRECTIVE.matcher(line).matches()) {
                pass.append(line).append('\n');
                control.append(line).append('\n');
                evaluation.append(line).append('\n');
            } else {
                if (!renamed && line.contains("void main()")) {
                    line = line.replace("void main()", "void tessVertex()");
                    renamed = true;
                }
                evaluation.append(line).append('\n');
            }
        }
        if (!renamed) { throw new IllegalArgumentException("Tessellation needs the vertex stage's main"); }
        StringBuilder copies = new StringBuilder(), interpolation = new StringBuilder();
        for (var entry : inputs.entrySet()) {
            String name = entry.getKey();
            copies.append("#ifdef TESS_").append(name).append("\n    OUT_").append(name).append(" = IN_").append(name)
                  .append(";\n#endif\n");
            interpolation.append("#ifdef TESS_").append(name).append("\n    ").append(name)
                  .append(" = gl_TessCoord.x * tc_").append(name).append("[0] + gl_TessCoord.y * tc_").append(name)
                  .append("[1] + gl_TessCoord.z * tc_").append(name).append("[2];\n#endif\n");
        }
        String shape = GpuShaderSource.read("terrain-rockiness.glsl") + GpuShaderSource.read("tessellation-shape.glsl");
        String passCopies = copies.toString().replaceAll("OUT_(\\w+)", "vs_$1").replaceAll("IN_(\\w+)", "$1");
        String controlCopies = copies.toString().replaceAll("OUT_(\\w+)", "tc_$1[gl_InvocationID]")
              .replaceAll("IN_(\\w+)", "vs_$1[gl_InvocationID]");
        return """
              in vec3 a_position;
              in vec3 a_normal;
              in vec4 a_color;
              out vec3 vs_position;
              out vec3 vs_normal;
              out vec4 vs_color;
              """ + pass + """
              void main() {
                  vs_position = a_position;
                  // A zero normal stays zero: its edges keep one segment and their chords (tessellation-*.glsl).
                  vs_normal = dot(a_normal, a_normal) > 0.0 ? normalize(a_normal) : vec3(0.0);
                  vs_color = a_color;
              """ + passCopies + "}\n"
              + CONTROL + "\n" + """
              layout(vertices = 3) out;
              in vec3 vs_position[];
              in vec3 vs_normal[];
              in vec4 vs_color[];
              out vec3 tc_position[];
              out vec3 tc_normal[];
              out vec4 tc_color[];
              """ + control + shape + "const float TESS_BULGE = " + BoardRelief.metres(BULGE_METRES) + ";\n"
              + GpuShaderSource.read("tessellation-control.glsl") + """
              void main() {
                  tc_position[gl_InvocationID] = vs_position[gl_InvocationID];
                  tc_normal[gl_InvocationID] = vs_normal[gl_InvocationID];
                  tc_color[gl_InvocationID] = vs_color[gl_InvocationID];
              """ + controlCopies + """
                  if (gl_InvocationID == 0) { tessellationLevels(); }
              }
              """
              + EVALUATION + "\n" + """
              layout(triangles, equal_spacing, ccw) in;
              in vec3 tc_position[];
              in vec3 tc_normal[];
              in vec4 tc_color[];
              """ + evaluation + shape + GpuShaderSource.read("tessellation-evaluation.glsl") + """
              void main() {
                  vec3 surface = phongPoint();
              #ifdef TESS_a_position
                  a_position = surface;
              #endif
              #ifdef TESS_a_normal
                  a_normal = phongNormal();
              #endif
              #ifdef TESS_a_color
                  a_color = gl_TessCoord.x * tc_color[0] + gl_TessCoord.y * tc_color[1] + gl_TessCoord.z * tc_color[2];
              #endif
              """ + interpolation + """
                  tessVertex();
              }
              """;
    }

    /** The inputs every stage carries itself: the patch's shape reads them (tessellation-*.glsl). */
    private static boolean carried(String name) {
        return name.equals("a_position") || name.equals("a_normal") || name.equals("a_color");
    }

    /** The vertex, control and evaluation stages of {@code source}; only the vertex stage when it has no others. */
    static String[] stages(String source) {
        int control = source.indexOf(CONTROL);
        if (control < 0) { return new String[] { source }; }
        int evaluation = source.indexOf(EVALUATION, control);
        if (evaluation < 0) { throw new IllegalArgumentException("A tessellation control stage without its evaluation stage"); }
        return new String[] { source.substring(0, control), source.substring(control + CONTROL.length(), evaluation),
              source.substring(evaluation + EVALUATION.length()) };
    }

    /** Remembers a linked program as tessellated: its draws are patches. */
    static void tessellated(ShaderProgram program) { PROGRAMS.add(program); }

    private static boolean tessellates(ShaderProgram program) { return PROGRAMS.contains(program); }

    /** Draws a part with a program, as patches when it tessellates. */
    static void render(MeshPart part, ShaderProgram program) {
        if (tessellates(program)) { part.mesh.render(program, GL40.GL_PATCHES, part.offset, part.size); }
        else { part.render(program); }
    }

    /** Draws a renderable through {@code draw} (its shader's render) as patches. */
    static void render(Renderable renderable, Runnable draw) {
        int primitive = renderable.meshPart.primitiveType;
        renderable.meshPart.primitiveType = GL40.GL_PATCHES;
        try { draw.run(); }
        finally { renderable.meshPart.primitiveType = primitive; }
    }

    /**
     * The control stage's inputs: the pass's camera with the part's placement, and its target's size in pixels, so the
     * colour pass and its depth twin split by what the view resolves.
     */
    static void register(BaseShader shader) {
        shader.register("u_tessView", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                target.set(id, VIEW.set(target.camera.combined).mul(renderable.worldTransform));
            }
        });
        shader.register("u_tessViewport", new BaseShader.GlobalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                VIEWPORT.clear();
                Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, VIEWPORT);
                target.set(id, (float) VIEWPORT.get(2), (float) VIEWPORT.get(3));
            }
        });
    }
}
