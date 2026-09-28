/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.Disposable;

/**
 * One board's in-memory shader drafts. All compilation and GL ownership stay on its render thread. The editor
 * submits immutable snapshots; a whole edit is prepared before any live program or uniform binding is replaced.
 */
final class GpuShaderManager {
    private static final ThreadLocal<GpuShaderManager> CURRENT = new ThreadLocal<>();
    private final List<Target> targets = new ArrayList<>();
    private final Map<ShaderProgram, Program> programs = new IdentityHashMap<>();
    private final Map<String, String> baseline = new HashMap<>();
    private Map<String, String> drafts = Map.of();
    private Set<String> reads;
    private Sources compiling;
    private boolean usingOriginal;
    private final AtomicReference<Request> pending = new AtomicReference<>();
    private volatile boolean closed;
    private long revision;
    /** EDT-owned, including creation and closing. */
    private GpuShaderEditor editor;

    record Sources(String name, String vertex, String fragment) { }
    record Result(boolean success, String message, Sources failure, int updated) { }
    private record Request(Map<String, String> drafts, Consumer<Result> completed) { }
    record Captured<T>(T value, Set<String> files) { }
    record Change(Runnable commit, Disposable discard, int count) { }

    interface Target {
        Set<String> files();
        Change prepare();
        List<Sources> sources();
    }

    static GpuShaderManager current() { return CURRENT.get(); }

    void run(Runnable action) {
        GpuShaderManager previous = CURRENT.get();
        CURRENT.set(this);
        try { action.run(); }
        finally {
            if (previous == null) { CURRENT.remove(); }
            else { CURRENT.set(previous); }
        }
    }

    String read(String name) {
        if (reads != null) { reads.add(name); }
        if (usingOriginal && baseline.containsKey(name)) { return baseline.get(name); }
        String disk = GpuShaderSource.readDisk(name);
        baseline.putIfAbsent(name, disk);
        return usingOriginal ? baseline.get(name) : drafts.getOrDefault(name, disk);
    }

    <T> Captured<T> capture(Supplier<T> factory) {
        Set<String> previous = reads;
        reads = new HashSet<>();
        try { return new Captured<>(factory.get(), Set.copyOf(reads)); }
        finally { reads = previous; }
    }

    /** A newly encountered material/effect can fall back if a draft's previously unused variant fails. */
    <T> T original(Supplier<T> factory) {
        boolean previous = usingOriginal;
        usingOriginal = true;
        try { return factory.get(); }
        finally { usingOriginal = previous; }
    }

    void add(Target target) { targets.add(target); }
    void remove(Target target) { targets.remove(target); }
    long revision() { return revision; }
    boolean hasDrafts() { return !drafts.isEmpty(); }

    static void compiling(String name, String vertex, String fragment) {
        var session = current();
        if (session != null) { session.compiling = new Sources(name, vertex, fragment); }
    }

    /** Direct shader owners retain their fields; this callback publishes a replacement between frames. */
    static ShaderProgram program(Supplier<ShaderProgram> factory, Consumer<ShaderProgram> install) {
        var session = current();
        if (session == null) { return factory.get(); }
        Captured<ShaderProgram> captured;
        try { captured = session.capture(factory); }
        catch (RuntimeException failure) {
            if (session.drafts.isEmpty()) { throw failure; }
            session.reportFallback(failure);
            captured = session.original(() -> session.capture(factory));
        }
        var target = session.new Program(factory, install, captured);
        session.programs.put(captured.value(), target);
        session.add(target);
        return captured.value();
    }

    static void dispose(ShaderProgram program) {
        if (program == null) { return; }
        var session = current();
        if (session != null) {
            Program target = session.programs.remove(program);
            if (target != null) { session.remove(target); }
        }
        program.dispose();
    }

    static ShaderProvider provider(String name, Supplier<ShaderProvider> factory) {
        return current() == null ? factory.get() : new GpuShaderProvider(current(), name, factory);
    }

    /** Called once at the frame boundary; pending keystrokes coalesce to the newest complete editor snapshot. */
    boolean update() {
        Request request = pending.getAndSet(null);
        if (request == null || closed) { return false; }
        Result result = apply(request.drafts());
        SwingUtilities.invokeLater(() -> { if (!closed) { request.completed().accept(result); } });
        return result.success() && result.updated() > 0;
    }

    void submit(Map<String, String> sources, Consumer<Result> completed) {
        if (!closed) { pending.set(new Request(Map.copyOf(sources), completed)); }
    }

    /** Render-thread entry point, also exercised by the native GL integration test. */
    Result apply(Map<String, String> next) {
        Set<String> changed = new HashSet<>(drafts.keySet());
        changed.addAll(next.keySet());
        changed.removeIf(name -> drafts.getOrDefault(name, baseline.computeIfAbsent(name, GpuShaderSource::readDisk))
              .equals(next.getOrDefault(name, baseline.get(name))));
        if (changed.isEmpty()) { return new Result(true, "Preview is up to date.", null, 0); }
        Map<String, String> previous = drafts;
        drafts = Map.copyOf(next);
        List<Change> prepared = new ArrayList<>();
        compiling = null;
        try {
            for (Target target : List.copyOf(targets)) {
                if (target.files().stream().anyMatch(changed::contains)) {
                    compiling = null;
                    prepared.add(target.prepare());
                }
            }
        } catch (RuntimeException failure) {
            prepared.forEach(change -> change.discard().dispose());
            drafts = previous;
            return new Result(false, "Preview unchanged.\n" + failure.getMessage(), compiling, 0);
        }
        prepared.forEach(change -> change.commit().run());
        int count = prepared.stream().mapToInt(Change::count).sum();
        revision++;
        return new Result(true, count == 0
              ? "Draft accepted; no active program uses this file. It will be checked when its effect is drawn."
              : "Live preview updated (" + count + " programs).", null, count);
    }

    void reportFallback(RuntimeException failure) {
        Sources source = compiling;
        SwingUtilities.invokeLater(() -> {
            if (!closed && editor != null) {
                editor.fallback(new Result(false, "A new shader variant could not use the draft.\n"
                      + "Using its original shader.\n" + failure.getMessage(), source, 0));
            }
        });
    }

    void showEditor() {
        var application = Gdx.app;
        var files = GpuShaderSource.files();
        Map<String, GpuShaderSource.FileSource> sources = new LinkedHashMap<>();
        files.forEach(name -> sources.put(name, GpuShaderSource.file(name)));
        String graphics = GpuGlsl.description();
        SwingUtilities.invokeLater(() -> {
            if (closed) { return; }
            if (editor == null) {
                editor = new GpuShaderEditor(sources, graphics, this::submit, callback ->
                      application.postRunnable(() -> run(() -> {
                          List<Sources> snapshot = targets.stream().flatMap(target -> target.sources().stream()).toList();
                          SwingUtilities.invokeLater(() -> { if (!closed) { callback.accept(snapshot); } });
                      })));
            }
            editor.show();
        });
    }

    void close() {
        closed = true;
        pending.set(null);
        targets.clear();
        programs.clear();
        drafts = Map.of();
        baseline.clear();
        SwingUtilities.invokeLater(() -> { if (editor != null) { editor.close(); editor = null; } });
    }

    /** Changing a bound input's type would compile, but the renderer would then upload incompatible values. */
    record Input(int type, int size) { }
    record Inputs(Map<String, Input> uniforms, Map<String, Integer> attributes) {
        static Inputs of(ShaderProgram program) {
            Map<String, Input> uniforms = new HashMap<>();
            Map<String, Integer> attributes = new HashMap<>();
            for (String name : program.getUniforms()) {
                uniforms.put(name, new Input(program.getUniformType(name), program.getUniformSize(name)));
            }
            for (String name : program.getAttributes()) { attributes.put(name, program.getAttributeType(name)); }
            return new Inputs(Map.copyOf(uniforms), Map.copyOf(attributes));
        }

        void check(ShaderProgram next) {
            uniforms.forEach((name, input) -> {
                if (next.hasUniform(name) && (input.type() != next.getUniformType(name)
                      || input.size() != next.getUniformSize(name))) {
                    throw new IllegalArgumentException("Keep the renderer's uniform type and size: " + name);
                }
            });
            attributes.forEach((name, type) -> {
                if (next.hasAttribute(name) && type != next.getAttributeType(name)) {
                    throw new IllegalArgumentException("Keep the mesh's vertex attribute type: " + name);
                }
            });
        }
    }

    private final class Program implements Target {
        private final Supplier<ShaderProgram> factory;
        private final Consumer<ShaderProgram> install;
        private final Inputs inputs;
        private Captured<ShaderProgram> active;

        Program(Supplier<ShaderProgram> factory, Consumer<ShaderProgram> install, Captured<ShaderProgram> active) {
            this.factory = factory;
            this.install = install;
            this.active = active;
            inputs = Inputs.of(active.value());
        }

        @Override
        public Set<String> files() { return active.files(); }

        @Override
        public Change prepare() {
            Captured<ShaderProgram> next = capture(factory);
            try { inputs.check(next.value()); }
            catch (RuntimeException failure) { next.value().dispose(); throw failure; }
            return new Change(() -> {
                ShaderProgram old = active.value();
                install.accept(next.value());
                active = next;
                programs.remove(old);
                programs.put(next.value(), this);
                old.dispose();
            }, next.value(), 1);
        }

        @Override
        public List<Sources> sources() {
            ShaderProgram shader = active.value();
            return List.of(new Sources(String.join(", ", active.files().stream().sorted().toList()),
                  shader.getVertexShaderSource(), shader.getFragmentShaderSource()));
        }
    }
}
