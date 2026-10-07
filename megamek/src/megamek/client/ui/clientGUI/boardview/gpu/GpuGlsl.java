/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3NativesLoader;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.ARBParallelShaderCompile;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.KHRParallelShaderCompile;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * The board's shading language. The board asks for the newest core context the driver creates, from 4.6 down to 3.3,
 * the least it needs: some drivers (Intel's on Windows) return exactly the version asked for, so asking for 3.3 would
 * keep them there. Current Windows and Linux drivers give 4.6, every Mac 4.1. Once the context exists,
 * {@link #detect()} reads its version and every shader compiled afterwards is GLSL of it, up to 4.60. Board shaders
 * use GLSL 3.30 core syntax. Only libGDX's built-in shaders need the legacy spelling adapter; custom programs compile
 * and export complete sources without it. Program ownership and libGDX prefix overrides belong to the render
 * thread; startup compilation may use the driver's parallel compiler or a private shared context.
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
    private static final ThreadLocal<Preparation> PREPARING = new ThreadLocal<>();

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

    /** Own every handle through failure, then transfer the successful link to libGDX without recompiling it. */
    private static ShaderProgram compilePrepared(String name, String vertex, String fragment) {
        GpuShaderManager.compiling(name, vertex, fragment);
        var source = new GpuShaderManager.Sources(name, vertex, fragment);
        Preparation preparation = PREPARING.get();
        Linked linked = preparation == null ? new Linked(source) : preparation.take(source);
        try { return linked.adopt(source); }
        finally { linked.dispose(); }
    }

    /** A provider construction can yield here before allocating its material shader or publishing any program. */
    static final class Pending extends RuntimeException {
        private Pending() { super(null, null, false, false); }
    }

    /**
     * Render-thread-owned startup links, polled once per loading frame. No link-status query, uniform lookup or
     * libGDX construction may force a pending driver job to finish. Without the extension, a worker owns a hidden
     * shared GL context and performs the blocking driver work there. Adoption always belongs to the render thread.
     */
    static final class Preparation implements Disposable {
        private final Map<GpuShaderManager.Sources, Linked> pending = new HashMap<>();
        private record Job(Linked linked, CompletableFuture<Void> done) { }
        private final Map<GpuShaderManager.Sources, Job> working = new HashMap<>();
        private final SharedCompiler worker;

        Preparation() { this(false); }

        /** Force the shared-context path in native tests even on a driver with parallel-compilation support. */
        Preparation(boolean sharedContext) {
            boolean khr = Gdx.graphics.supportsExtension("GL_KHR_parallel_shader_compile");
            boolean arb = Gdx.graphics.supportsExtension("GL_ARB_parallel_shader_compile");
            worker = sharedContext || !(khr || arb) ? new SharedCompiler() : null;
            if (worker == null) {
                if (khr) { KHRParallelShaderCompile.glMaxShaderCompilerThreadsKHR(-1); }
                else { ARBParallelShaderCompile.glMaxShaderCompilerThreadsARB(-1); }
            }
        }

        boolean run(Runnable action) {
            Preparation previous = PREPARING.get();
            PREPARING.set(this);
            try { action.run(); return true; }
            catch (Pending unfinished) { return false; }
            finally {
                if (previous == null) { PREPARING.remove(); }
                else { PREPARING.set(previous); }
            }
        }

        private Linked take(GpuShaderManager.Sources source) {
            if (worker != null) {
                Job job = working.computeIfAbsent(source, key -> {
                    Linked linked = new Linked(key, true);
                    Gdx.gl20.glFlush(); // Publish the newly allocated shared objects before the worker uses them.
                    return new Job(linked, worker.compile(linked, key));
                });
                if (!job.done().isDone()) { throw new Pending(); }
                working.remove(source);
                try {
                    job.done().join();
                    return job.linked();
                }
                catch (RuntimeException failure) {
                    job.linked().dispose();
                    if (failure instanceof CompletionException) {
                        throw new GdxRuntimeException(source.name(), failure.getCause());
                    }
                    throw failure;
                }
            }
            Linked linked = pending.computeIfAbsent(source, Linked::new);
            if (!linked.complete()) { throw new Pending(); }
            pending.remove(source);
            return linked;
        }

        @Override
        public void dispose() {
            // Teardown must release the worker's context before GLFW destroys either shared window. A native
            // compiler call cannot be interrupted safely; closing the renderer may wait for that call to return.
            if (worker != null) {
                working.values().forEach(job -> job.done().cancel(false));
                worker.dispose();
            }
            working.values().forEach(job -> job.linked().dispose());
            working.clear();
            pending.values().forEach(Linked::dispose);
            pending.clear();
        }
    }

    /** One private context/GL adapter per compiler thread; never use or replace Gdx globals on that thread. */
    private static final class SharedCompiler implements Disposable {
        private final long window;
        private final ExecutorService executor;
        private final CompletableFuture<GL20> context;

        SharedCompiler() {
            long owner = GLFW.glfwGetCurrentContext();
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GL11.glGetInteger(GL30.GL_MAJOR_VERSION));
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GL11.glGetInteger(GL30.GL_MINOR_VERSION));
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            if (SharedLibraryLoader.os == Os.MacOsX) { GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE); }
            try { window = GLFW.glfwCreateWindow(1, 1, "Terrain shader compiler", 0, owner); }
            finally { GLFW.glfwDefaultWindowHints(); }
            if (window == 0) { throw new GdxRuntimeException("Cannot create a shared terrain shader compiler context"); }
            executor = Executors.newSingleThreadExecutor(action -> {
                Thread thread = new Thread(action, "terrain-shader-compiler");
                thread.setDaemon(true);
                return thread;
            });
            context = CompletableFuture.supplyAsync(() -> {
                GLFW.glfwMakeContextCurrent(window);
                GL.createCapabilities();
                return compilerCalls();
            }, executor);
        }

        CompletableFuture<Void> compile(Linked linked, GpuShaderManager.Sources source) {
            return CompletableFuture.runAsync(() -> {
                GL20 gl = context.join();
                try {
                    linked.link(source, gl);
                    linked.validate(source, gl); // Intentionally blocking only this worker.
                } finally {
                    // Failed links also need a completed producer stream before the render thread deletes them.
                    gl.glFinish();
                }
            }, executor);
        }

        @Override
        public void dispose() {
            CompletableFuture.runAsync(() -> {
                GLFW.glfwMakeContextCurrent(0);
                GL.setCapabilities(null);
            }, executor).join();
            executor.shutdown();
            GLFW.glfwDestroyWindow(window);
        }

        /** libGDX's backend adapter is package-private and owns scratch buffers used by the render thread. */
        private static GL20 compilerCalls() {
            return (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                  (proxy, method, args) -> {
                      switch (method.getName()) {
                          case "glShaderSource": GL20C.glShaderSource((int) args[0], (String) args[1]); break;
                          case "glCompileShader": GL20C.glCompileShader((int) args[0]); break;
                          case "glAttachShader": GL20C.glAttachShader((int) args[0], (int) args[1]); break;
                          case "glLinkProgram": GL20C.glLinkProgram((int) args[0]); break;
                          case "glGetShaderiv": GL20C.glGetShaderiv((int) args[0], (int) args[1], (IntBuffer) args[2]); break;
                          case "glGetProgramiv": GL20C.glGetProgramiv((int) args[0], (int) args[1], (IntBuffer) args[2]); break;
                          case "glGetShaderInfoLog": return GL20C.glGetShaderInfoLog((int) args[0]);
                          case "glGetProgramInfoLog": return GL20C.glGetProgramInfoLog((int) args[0]);
                          case "glFinish": GL11.glFinish(); break;
                          default: throw new UnsupportedOperationException("Compiler GL call: " + method.getName());
                      }
                      return null;
                  });
        }
    }

    private static final class Linked implements Disposable {
        private int vertex, fragment, program;
        private final java.nio.IntBuffer status = BufferUtils.newIntBuffer(1);

        Linked(GpuShaderManager.Sources source) { this(source, false); }

        Linked(GpuShaderManager.Sources source, boolean deferred) {
            export(source.name(), source.vertex(), source.fragment());
            GL20 gl = Gdx.gl20;
            try {
                vertex = gl.glCreateShader(GL20.GL_VERTEX_SHADER);
                fragment = gl.glCreateShader(GL20.GL_FRAGMENT_SHADER);
                program = gl.glCreateProgram();
                if (vertex == 0 || fragment == 0 || program == 0) {
                    throw new GdxRuntimeException(source.name() + ": cannot create shader program");
                }
                if (!deferred) { link(source, gl); }
            } catch (RuntimeException failure) { dispose(gl); throw failure; }
        }

        void link(GpuShaderManager.Sources source, GL20 gl) {
            gl.glShaderSource(vertex, source.vertex());
            gl.glShaderSource(fragment, source.fragment());
            gl.glCompileShader(vertex);
            gl.glCompileShader(fragment);
            gl.glAttachShader(program, vertex);
            gl.glAttachShader(program, fragment);
            gl.glLinkProgram(program);
        }


        boolean complete() {
            Gdx.gl20.glGetProgramiv(program, ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB, status);
            return status.get(0) != 0;
        }

        void validate(GpuShaderManager.Sources source, GL20 gl) {
            // Resolve the link even when a stage failed before disposing its owned handles.
            gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
            boolean linked = status.get(0) != 0;
            for (int shader : new int[] { vertex, fragment }) {
                gl.glGetShaderiv(shader, GL20.GL_COMPILE_STATUS, status);
                if (status.get(0) == 0) {
                    String error = source.name() + (shader == vertex ? " vertex: " : " fragment: ")
                          + gl.glGetShaderInfoLog(shader);
                    clearFailedLink(gl);
                    throw new GdxRuntimeException(error);
                }
            }
            if (!linked) {
                String error = source.name() + " link: " + gl.glGetProgramInfoLog(program);
                clearFailedLink(gl);
                throw new GdxRuntimeException(error);
            }
        }

        /**
         * Intel 32.0.101.7088 retains failed-link state when another context later reuses the deleted program's
         * name. Resolve a tiny valid link before deletion, only on that vendor's error path. The original diagnostic
         * is retained, and successful terrain startup does no extra compilation or binary loading.
         */
        private void clearFailedLink(GL20 gl) {
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            if (vendor == null || !vendor.toLowerCase(Locale.ROOT).contains("intel")) { return; }
            gl.glShaderSource(vertex, "#version 330 core\nvoid main(){gl_Position=vec4(0.0);}");
            gl.glShaderSource(fragment, "#version 330 core\nout vec4 color; void main(){color=vec4(0.0);}");
            gl.glCompileShader(vertex);
            gl.glCompileShader(fragment);
            gl.glLinkProgram(program);
            gl.glGetProgramiv(program, GL20.GL_LINK_STATUS, status);
        }

        ShaderProgram adopt(GpuShaderManager.Sources source) {
            var gl = Gdx.gl20;
            validate(source, gl);
            String vertexPrefix = ShaderProgram.prependVertexCode, fragmentPrefix = ShaderProgram.prependFragmentCode;
            try {
                // ShaderProgram has no constructor for linked handles. Adapt only its construction calls; all
                // reflection/introspection uses the real GL implementation. No private libGDX fields are modified.
                Gdx.gl20 = (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                      (proxy, method, args) -> {
                          return switch (method.getName()) {
                              case "glCreateShader" -> (int) args[0] == GL20.GL_VERTEX_SHADER ? vertex : fragment;
                              case "glCreateProgram" -> program;
                              case "glShaderSource", "glCompileShader", "glAttachShader", "glLinkProgram" -> null;
                              default -> {
                                  try { yield method.invoke(gl, args); }
                                  catch (InvocationTargetException failure) { throw failure.getCause(); }
                              }
                          };
                      });
                ShaderProgram.prependVertexCode = "";
                ShaderProgram.prependFragmentCode = "";
                ShaderProgram result = new GpuShaderUniforms(source.name(), source.vertex(), source.fragment());
                if (!result.isCompiled()) { throw new GdxRuntimeException(source.name() + ": " + result.getLog()); }
                vertex = fragment = program = 0; // The libGDX owner now disposes these exact handles.
                return result;
            } finally {
                Gdx.gl20 = gl;
                ShaderProgram.prependVertexCode = vertexPrefix;
                ShaderProgram.prependFragmentCode = fragmentPrefix;
            }
        }

        @Override
        public void dispose() { dispose(Gdx.gl20); }

        private void dispose(GL20 gl) {
            if (vertex != 0) { gl.glDeleteShader(vertex); }
            if (fragment != 0) { gl.glDeleteShader(fragment); }
            if (program != 0) { gl.glDeleteProgram(program); }
            vertex = fragment = program = 0;
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
     * Tries a hidden window for each supported version. A failed probe reports its native error instead of pretending
     * that 3.3 is available. libGDX's own window then finds GLFW initialized; its remaining hints concern unused input
     * and ANGLE settings.
     */
    private static int[] newestContext() {
        Lwjgl3NativesLoader.load();
        // These windows are the run's first OpenGL contexts, which fix the graphics card it draws with.
        GpuGraphicsCard.apply();
        if (!GLFW.glfwInit()) { throw new GdxRuntimeException("Could not initialize GLFW. " + glfwError()); }
        boolean available = false;
        try {
            String error = "";
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
                    available = true;
                    return candidate;
                }
                error = glfwError();
            }
            throw new GdxRuntimeException("Could not create an OpenGL 3.3 or newer context. " + error);
        } finally {
            GLFW.glfwDefaultWindowHints();
            if (!available) { GLFW.glfwTerminate(); }
        }
    }

    /** GLFW errors belong to the calling thread; capture them before handing a failure to the EDT. */
    static String glfwError() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var description = stack.callocPointer(1);
            int error = GLFW.glfwGetError(description);
            if (error == GLFW.GLFW_NO_ERROR) { return ""; }
            String detail = description.get(0) == 0 ? "No description" : MemoryUtil.memUTF8(description.get(0));
            return String.format(Locale.ROOT, "GLFW 0x%08X: %s", error, detail);
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
