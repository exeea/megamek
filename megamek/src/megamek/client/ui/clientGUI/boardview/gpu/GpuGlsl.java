/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The board's shading language. The board asks for the newest core context the driver creates, from 4.6 down to 3.3,
 * the least it needs: some drivers (Intel's on Windows) return exactly the version asked for, so asking for 3.3 would
 * keep them there. Current Windows and Linux drivers give 4.6, every Mac 4.1. Once the context exists,
 * {@link #detect()} reads its version and every shader compiled afterwards is GLSL of it, up to 4.60. Board shaders
 * use GLSL 3.30 core syntax. Only libGDX's built-in shaders need the legacy spelling adapter; custom programs compile
 * and export complete sources without it. All compilation, including the temporary libGDX prefix override, belongs
 * to the application's render thread.
 */
final class GpuGlsl {
    private static final MMLogger LOGGER = MMLogger.create(GpuGlsl.class);
    /** The lowest and highest shading language versions the board compiles for. */
    static final int MINIMUM = 330;
    static final int MAXIMUM = 460;
    /** A system property that caps the version, for troubleshooting a driver: {@code -Dmegamek.gpu.glsl=330}. */
    static final String CAP_PROPERTY = "megamek.gpu.glsl";
    /** Optional directory for complete, editable shader pairs, including material defines and shared functions. */
    static final String EXPORT_PROPERTY = "megamek.gpu.shaderExport";
    private static final Pattern VERSION = Pattern.compile("(?m)^[\\t ]*#[\\t ]*version\\b[^\\r\\n]*(?:\\r?\\n|$)");
    private static final String VERTEX_DEFINES = """
          #define attribute in
          #define varying out
          #define texture2D texture
          #define texture2DLod textureLod
          #define textureCube texture
          """;
    private static final String FRAGMENT_DEFINES = """
          #define varying in
          #define texture2D texture
          #define texture2DLod textureLod
          #define textureCube texture
          out vec4 fragColor;
          #define gl_FragColor fragColor
          """;
    /** Context versions tried in turn, newest first. */
    private static final int[][] CONTEXTS = { { 4, 6 }, { 4, 5 }, { 4, 3 }, { 4, 1 }, { 4, 0 }, { 3, 3 } };
    private static int version = MINIMUM;
    private static String context = "OpenGL 3.3";
    /** The graphics card of the board's context, as its driver names it; empty until the context exists. */
    private static String renderer = "";
    /** The newest context this computer creates, found once per run. */
    private static int[] newest;

    private GpuGlsl() { }

    static ShaderProgram compile(String name, String prefix, String vertex, String fragment) {
        return compilePrepared(name, source(prefix, vertex, version), source(prefix, fragment, version));
    }

    /** Resource stages declare their editor baseline; the runtime supplies the selected version and material flags. */
    static String source(String prefix, String source, int glsl) {
        return "#version " + glsl + " core\n" + prefix + VERSION.matcher(source).replaceFirst("");
    }

    /** Adapt upstream GLSL once, before inserting any board code. libGDX remains the owner of its lighting code. */
    static String libGdx(String source, boolean vertex) {
        source = source.replaceAll("\\battribute\\b", "in")
              .replaceAll("\\bvarying\\b", vertex ? "out" : "in")
              .replaceAll("\\btexture2DLod\\b", "textureLod")
              .replaceAll("\\b(texture2D|textureCube)\\b", "texture")
              .replaceAll("\\bgl_FragColor\\b", "fragColor");
        return vertex ? source : "layout(location = 0) out vec4 fragColor;\n" + source;
    }

    static ShaderProgram compile(String name, String vertex, String fragment) {
        return compile(name, "", vertex, fragment);
    }

    /**
     * Validate editable programs with owned GL handles before handing them to libGDX. Its ShaderProgram loses
     * failed shader/link handles and attempts to delete -1 on dispose, so bad edits otherwise leak on every retry.
     * The successful sources are compiled again by libGDX to retain its normal uniform and context management.
     */
    private static ShaderProgram compilePrepared(String name, String vertex, String fragment) {
        export(name, vertex, fragment);
        int vertexHandle = 0, fragmentHandle = 0, program = 0;
        var gl = Gdx.gl20;
        try {
            vertexHandle = validateStage(name + " vertex", GL20.GL_VERTEX_SHADER, vertex);
            fragmentHandle = validateStage(name + " fragment", GL20.GL_FRAGMENT_SHADER, fragment);
            program = gl.glCreateProgram();
            if (program == 0) { throw new GdxRuntimeException(name + ": cannot create shader program"); }
            gl.glAttachShader(program, vertexHandle);
            gl.glAttachShader(program, fragmentHandle);
            gl.glLinkProgram(program);
            var status = BufferUtils.newIntBuffer(1);
            gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
            if (status.get(0) == 0) { throw new GdxRuntimeException(name + " link: " + gl.glGetProgramInfoLog(program)); }
        } finally {
            if (program != 0) { gl.glDeleteProgram(program); }
            if (vertexHandle != 0) { gl.glDeleteShader(vertexHandle); }
            if (fragmentHandle != 0) { gl.glDeleteShader(fragmentHandle); }
        }
        // SpriteBatch, ShapeRenderer and other unmodified libGDX programs still need the global legacy prefixes.
        // Our sources already contain their version and outputs; restore the globals even when construction fails.
        String vertexPrefix = ShaderProgram.prependVertexCode, fragmentPrefix = ShaderProgram.prependFragmentCode;
        try {
            ShaderProgram.prependVertexCode = "";
            ShaderProgram.prependFragmentCode = "";
            ShaderProgram result = new ShaderProgram(vertex, fragment);
            if (!result.isCompiled()) { throw new GdxRuntimeException(name + ": " + result.getLog()); }
            return result;
        } finally {
            ShaderProgram.prependVertexCode = vertexPrefix;
            ShaderProgram.prependFragmentCode = fragmentPrefix;
        }
    }

    private static void export(String name, String vertex, String fragment) {
        String directory = System.getProperty(EXPORT_PROPERTY);
        if (directory == null || directory.isBlank()) { return; }
        String id = UUID.nameUUIDFromBytes((vertex + "\0" + fragment).getBytes(StandardCharsets.UTF_8)).toString();
        String file = name.replaceAll("[^a-zA-Z0-9._-]", "-") + "-" + id;
        try {
            new FileHandle(new File(directory, file + ".vert")).writeString(vertex, false, "UTF-8");
            new FileHandle(new File(directory, file + ".frag")).writeString(fragment, false, "UTF-8");
        } catch (GdxRuntimeException failure) {
            LOGGER.warn("Could not export shader {} to {}", name, directory, failure);
        }
    }

    private static int validateStage(String name, int type, String source) {
        var gl = Gdx.gl20;
        int handle = gl.glCreateShader(type);
        if (handle == 0) { throw new GdxRuntimeException(name + ": cannot create shader"); }
        try {
            gl.glShaderSource(handle, source);
            gl.glCompileShader(handle);
            var status = BufferUtils.newIntBuffer(1);
            gl.glGetShaderiv(handle, GL20.GL_COMPILE_STATUS, status);
            if (status.get(0) == 0) { throw new GdxRuntimeException(name + ": " + gl.glGetShaderInfoLog(handle)); }
            return handle;
        } catch (RuntimeException failure) {
            gl.glDeleteShader(handle);
            throw failure;
        }
    }

    /**
     * Requests the newest core context this computer creates and compiles every shader as GLSL 3.30 until
     * {@link #detect()} runs.
     */
    static void configure(Lwjgl3ApplicationConfiguration configuration) {
        if (newest == null) { newest = newestContext(); }
        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, newest[0], newest[1]);
        use(MINIMUM);
    }

    /**
     * Tries a hidden window for each version in {@link #CONTEXTS}; 3.3 when GLFW cannot start. libGDX's own window
     * then finds GLFW initialized; the init hints it would add concern joysticks and ANGLE, which the board does not use.
     */
    private static int[] newestContext() {
        Lwjgl3NativesLoader.load();
        // These windows are the run's first OpenGL contexts, which fix the graphics card it draws with.
        GpuGraphicsCard.apply();
        if (!GLFW.glfwInit()) { return CONTEXTS[CONTEXTS.length - 1]; }
        try {
            for (int[] candidate : CONTEXTS) {
                GLFW.glfwDefaultWindowHints();
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, candidate[0]);
                GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, candidate[1]);
                GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
                if (SharedLibraryLoader.os == Os.MacOsX) {
                    GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
                }
                long window = GLFW.glfwCreateWindow(1, 1, "", 0, 0);
                if (window != 0) {
                    GLFW.glfwDestroyWindow(window);
                    return candidate;
                }
            }
            return CONTEXTS[CONTEXTS.length - 1];
        } finally {
            GLFW.glfwDefaultWindowHints();
        }
    }

    /**
     * Compiles every later shader for the version of the current context. Runs on the render thread once the window's
     * context exists and before the first shader is compiled.
     */
    static void detect() {
        int major = GL11.glGetInteger(GL30.GL_MAJOR_VERSION), minor = GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        int chosen = select(major, minor, Integer.getInteger(CAP_PROPERTY, MAXIMUM));
        use(chosen);
        context = String.format(Locale.ROOT, "OpenGL %d.%d", major, minor);
        renderer = GL11.glGetString(GL11.GL_RENDERER);
        LOGGER.info("3D board: {} on {}, shaders compiled as GLSL {}", GL11.glGetString(GL11.GL_VERSION), renderer,
              chosen);
    }

    /** The GLSL version for an OpenGL context version: the same version, capped, never below {@link #MINIMUM}. */
    static int select(int major, int minor, int cap) {
        return Math.clamp(Math.min(major * 100 + minor * 10, cap), MINIMUM, MAXIMUM);
    }

    /** The graphics card the board draws with, as its driver names it; empty before the board's context exists. */
    static String renderer() {
        return renderer == null ? "" : renderer;
    }

    /** The context and shading language in use, as the tuning panel shows them. */
    static String description() {
        return String.format(Locale.ROOT, "%s, GLSL %d.%02d", context, version / 100, version % 100);
    }

    private static void use(int glsl) {
        version = glsl;
        ShaderProgram.prependVertexCode = "#version " + glsl + " core\n" + VERTEX_DEFINES;
        ShaderProgram.prependFragmentCode = "#version " + glsl + " core\n" + FRAGMENT_DEFINES;
    }
}
