/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.ARBParallelShaderCompile;

/** Real GL handle ownership, libGDX adoption and non-blocking preparation, including failed editor source. */
@Tag("on-demand")
class GpuShaderCompileSmokeTest {
    private static final String VERTEX = "in vec3 a_position; void main(){gl_Position=vec4(a_position,1);}";
    private static final String FRAGMENT = "out vec4 color; uniform vec4 u_testColor; void main(){color=u_testColor;}";

    @Test
    void linksOnceAndKeepsUnfinishedProgramsOutOfLibGdx() {
        var failure = new AtomicReference<Throwable>();
        stage("opening render context");
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GL20 original = Gdx.gl20;
                var calls = new Calls(original);
                try {
                    stage("render context ready; compiling single link");
                    Gdx.gl20 = calls.proxy();
                    ShaderProgram program = GpuGlsl.compile("single link", VERTEX, FRAGMENT);
                    stage("single link compiled; checking adoption and disposal");
                    assertEquals(2, calls.compiles);
                    assertEquals(1, calls.links);
                    program.bind();
                    program.setUniformf("u_testColor", .25f, .5f, .75f, 1);
                    var value = BufferUtils.newFloatBuffer(4);
                    Gdx.gl20.glGetUniformfv(program.getHandle(), program.getUniformLocation("u_testColor"), value);
                    assertEquals(.75f, value.get(2), .0001f, "libGDX must upload to the adopted program");
                    Gdx.gl20.glUseProgram(0);
                    program.dispose();
                    calls.assertReleased();

                    int invalid = 0;
                    for (String[] stages : List.of(new String[] { "invalid", FRAGMENT },
                          new String[] { VERTEX, "invalid" },
                          new String[] { "uniform float u_link; void main(){gl_Position=vec4(u_link);}",
                                "uniform vec4 u_link; out vec4 color; void main(){color=u_link;}" })) {
                        stage("invalid main-context source " + ++invalid);
                        assertThrows(GdxRuntimeException.class, () -> GpuGlsl.compile("invalid edit", stages[0], stages[1]));
                        calls.assertReleased();
                        stage("invalid main-context source released " + invalid);
                    }
                    if (Gdx.graphics.supportsExtension("GL_ARB_parallel_shader_compile")
                          || Gdx.graphics.supportsExtension("GL_KHR_parallel_shader_compile")) {
                        checkPreparation(calls);
                    }
                    checkSharedContext();
                    calls.assertReleased();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    stage("all shader ownership assertions passed");
                } catch (Throwable error) { failure.set(error); }
                finally { stage("exiting render context"); Gdx.gl20 = original; Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        stage("render context closed");
        if (failure.get() != null) { throw new AssertionError("Shader preparation", failure.get()); }
    }

    /** The Intel fallback compiles in another GL context, but libGDX must receive usable shared handles. */
    private static void checkSharedContext() {
        stage("creating shared compiler context");
        var preparation = new GpuGlsl.Preparation(true);
        stage("shared compiler context created");
        var program = new AtomicReference<ShaderProgram>();
        var cancelledPrograms = new ArrayList<ShaderProgram>();
        long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        try {
            int invalid = 0;
            for (String[] stages : List.of(new String[] { "invalid", FRAGMENT },
                  new String[] { VERTEX, "invalid" },
                  new String[] { "uniform float u_link; void main(){gl_Position=vec4(u_link);}",
                        "uniform vec4 u_link; out vec4 color; void main(){color=u_link;}" })) {
                stage("invalid shared-context source " + ++invalid);
                assertThrows(GdxRuntimeException.class, () -> {
                    long deadline = System.nanoTime() + 20_000_000_000L;
                    while (!preparation.run(() -> GpuGlsl.compile("invalid worker edit", stages[0], stages[1]))) {
                        assertTrue(System.nanoTime() < deadline, "The worker must report invalid source");
                        java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    }
                });
                stage("invalid shared-context source released " + invalid);
            }
            stage("compiling valid shared-context source");
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!preparation.run(() -> program.set(GpuGlsl.compile("shared context", VERTEX, FRAGMENT)))) {
                assertTrue(System.nanoTime() < deadline, "The worker must finish a trivial shader");
                assertEquals(window, org.lwjgl.glfw.GLFW.glfwGetCurrentContext(), "The render context remains current");
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
            stage("shared-context source adopted; checking uniform upload and disposal");
            ShaderProgram linked = program.get();
            int handle = linked.getHandle();
            var count = BufferUtils.newIntBuffer(1);
            var stages = BufferUtils.newIntBuffer(2);
            Gdx.gl20.glGetAttachedShaders(handle, 2, count, stages);
            assertEquals(2, count.get(0));
            linked.bind();
            linked.setUniformf("u_testColor", .2f, .4f, .6f, 1);
            var value = BufferUtils.newFloatBuffer(4);
            Gdx.gl20.glGetUniformfv(handle, linked.getUniformLocation("u_testColor"), value);
            assertEquals(.6f, value.get(2), .0001f);
            Gdx.gl20.glUseProgram(0);
            linked.dispose();
            program.set(null);
            assertFalse(Gdx.gl20.glIsProgram(handle));
            for (int i = 0; i < 2; i++) { assertFalse(Gdx.gl20.glIsShader(stages.get(i))); }
            stage("shared-context program released; queueing cancelled sources");
            // Close with jobs queued: cancel unstarted work and drain any active call before destroying its context.
            for (String name : List.of("cancelled worker one", "cancelled worker two")) {
                preparation.run(() -> cancelledPrograms.add(GpuGlsl.compile(name, VERTEX, FRAGMENT)));
            }
        } finally {
            stage("disposing shared compiler and pending sources");
            preparation.dispose();
            stage("shared compiler disposed");
            if (program.get() != null) { program.get().dispose(); }
            cancelledPrograms.forEach(ShaderProgram::dispose);
        }
        assertEquals(window, org.lwjgl.glfw.GLFW.glfwGetCurrentContext());
    }

    private static void checkPreparation(Calls calls) {
        stage("creating parallel preparation");
        var program = new AtomicReference<ShaderProgram>();
        var preparation = new GpuGlsl.Preparation();
        int links = calls.links, compiles = calls.compiles, checks = calls.blockingChecks;
        calls.holdCompletion = true;
        Runnable compile = () -> program.set(GpuGlsl.compile("deferred link", VERTEX, FRAGMENT));
        try {
            for (int i = 0; i < 3; i++) {
                assertFalse(preparation.run(compile));
                assertEquals(checks, calls.blockingChecks, "A loading frame must not wait for compile/link status");
                assertEquals(links + 1, calls.links, "Polling must reuse the pending link");
                assertEquals(compiles + 2, calls.compiles);
            }
            stage("parallel polling reused pending link; awaiting completion");
            calls.holdCompletion = false;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!preparation.run(compile)) {
                assertTrue(System.nanoTime() < deadline, "The trivial shader must finish");
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            }
            assertTrue(program.get().isCompiled());
            assertEquals(links + 1, calls.links, "Adoption must not link again");
            assertEquals(compiles + 2, calls.compiles);
        } finally {
            stage("disposing completed parallel preparation");
            preparation.dispose();
            if (program.get() != null) { program.get().dispose(); }
        }
        calls.assertReleased();

        // Closing or replacing the renderer during preparation must also release never-published handles.
        stage("queueing cancelled parallel source");
        var cancelled = new GpuGlsl.Preparation();
        calls.holdCompletion = true;
        try { assertFalse(cancelled.run(compile)); }
        finally { stage("disposing cancelled parallel source"); cancelled.dispose(); calls.holdCompletion = false; }
        calls.assertReleased();
        stage("cancelled parallel source released");
    }

    /** Persist each boundary before entering native calls, so a driver stall does not hide the last stage. */
    private static void stage(String message) {
        String line = Instant.now() + " SHADER_COMPILE " + message + System.lineSeparator();
        System.out.print(line);
        System.out.flush();
        new FileHandle(new File(System.getProperty("megamek.gpu.screenshots", "build"), "shader-compile-stages.log"))
              .writeString(line, true, "UTF-8");
    }

    private static final class Calls {
        private final GL20 original;
        private final List<Integer> programs = new ArrayList<>(), shaders = new ArrayList<>();
        private int links, compiles, blockingChecks;
        private boolean holdCompletion;

        Calls(GL20 original) { this.original = original; }

        GL20 proxy() {
            return (GL20) Proxy.newProxyInstance(GL20.class.getClassLoader(), new Class<?>[] { GL20.class },
                  (proxy, method, args) -> {
                      String name = method.getName();
                      if (name.equals("glLinkProgram")) { links++; }
                      if (name.equals("glCompileShader")) { compiles++; }
                      if (name.equals("glGetProgramiv") && (int) args[1] == ARBParallelShaderCompile.GL_COMPLETION_STATUS_ARB
                            && holdCompletion) {
                          ((java.nio.IntBuffer) args[2]).put(0, 0);
                          return null;
                      }
                      if (name.equals("glGetShaderiv") && (int) args[1] == GL20.GL_COMPILE_STATUS
                            || name.equals("glGetProgramiv") && (int) args[1] == GL20.GL_LINK_STATUS) { blockingChecks++; }
                      Object value;
                      try { value = method.invoke(original, args); }
                      catch (InvocationTargetException error) { throw error.getCause(); }
                      if (name.equals("glCreateProgram")) { programs.add((int) value); }
                      if (name.equals("glCreateShader")) { shaders.add((int) value); }
                      return value;
                  });
        }

        void assertReleased() {
            for (int program : programs) { assertFalse(original.glIsProgram(program), "Leaked program " + program); }
            for (int shader : shaders) { assertFalse(original.glIsShader(shader), "Leaked shader " + shader); }
            programs.clear(); shaders.clear();
        }
    }
}
