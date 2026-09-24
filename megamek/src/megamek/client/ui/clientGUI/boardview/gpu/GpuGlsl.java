/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Locale;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
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
 * {@link #detect()} reads its version and every shader compiled afterwards is GLSL of it, up to 4.60. The board's own
 * shaders and libGDX's built-in ones are written in the GLSL 1.x spelling (attribute, varying, texture2D,
 * gl_FragColor); the prefixes map that spelling onto the chosen version, and the explicit {@code #version} makes every
 * driver check the same rules.
 */
final class GpuGlsl {
    private static final MMLogger LOGGER = MMLogger.create(GpuGlsl.class);
    /** The lowest and highest shading language versions the board compiles for. */
    static final int MINIMUM = 330;
    static final int MAXIMUM = 460;
    /** A system property that caps the version, for troubleshooting a driver: {@code -Dmegamek.gpu.glsl=330}. */
    static final String CAP_PROPERTY = "megamek.gpu.glsl";
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

    /** The GLSL version shaders are compiled as, such as 460; tessellation needs 400, compute shaders 430. */
    static int version() {
        return version;
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
