/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.ShaderProvider;

/** Stable material shader references, with fresh libGDX uniform/attribute caches for every successful edit. */
final class GpuShaderProvider implements ShaderProvider, GpuShaderManager.Target {
    private final GpuShaderManager session;
    private final String name;
    private final Supplier<ShaderProvider> factory;
    private final List<Handle> handles = new ArrayList<>();
    private GpuShaderManager.Captured<ShaderProvider> active;
    private ShaderProvider fallback;

    GpuShaderProvider(GpuShaderManager session, String name, Supplier<ShaderProvider> factory) {
        this.session = session;
        this.name = name;
        this.factory = factory;
        active = session.capture(factory);
        session.add(this);
    }

    static Shader unwrap(Shader shader) { return shader instanceof Handle handle ? handle.shader : shader; }
    ShaderProvider active() { return active.value(); }

    @Override
    public Shader getShader(Renderable renderable) {
        if (renderable.shader != null && renderable.shader.canRender(renderable)) { return renderable.shader; }
        for (Handle handle : handles) { if (handle.canRender(renderable)) { return handle; } }
        Shader shader;
        Shader suggested = renderable.shader;
        renderable.shader = null;
        try {
            try { shader = active.value().getShader(renderable); }
            catch (GpuGlsl.Pending unfinished) { throw unfinished; }
            catch (RuntimeException failure) {
                if (!session.hasDrafts()) { throw failure; }
                session.reportFallback(failure);
                if (fallback == null) { fallback = session.original(factory); }
                shader = fallback.getShader(renderable);
            }
        } finally { renderable.shader = suggested; }
        var handle = new Handle(shader, renderable);
        handles.add(handle);
        if (shader instanceof BaseShader base) { session.configureInputs(variantName(handles.size()), base.program); }
        return handle;
    }

    @Override
    public Set<String> files() { return active.files(); }

    @Override
    public GpuShaderManager.Change prepare() {
        var next = session.capture(factory);
        List<Shader> replacements = new ArrayList<>();
        try {
            for (Handle handle : handles) {
                Shader replacement = next.value().getShader(handle.sample);
                if (handle.inputs != null && replacement instanceof BaseShader candidate) {
                    handle.inputs.check(candidate.program);
                }
                replacements.add(replacement);
            }
        } catch (RuntimeException failure) { next.value().dispose(); throw failure; }
        return new GpuShaderManager.Change(() -> {
            for (int i = 0; i < handles.size(); i++) {
                handles.get(i).shader = replacements.get(i);
                if (replacements.get(i) instanceof BaseShader base) { session.configureInputs(variantName(i + 1), base.program); }
            }
            active.value().dispose();
            if (fallback != null) { fallback.dispose(); fallback = null; }
            active = next;
        }, next.value(), replacements.size());
    }

    @Override
    public List<GpuShaderManager.Sources> sources() {
        List<GpuShaderManager.Sources> result = new ArrayList<>();
        for (int i = 0; i < handles.size(); i++) {
            if (handles.get(i).shader instanceof BaseShader base) {
                result.add(new GpuShaderManager.Sources(name + " / variant " + (i + 1),
                      base.program.getVertexShaderSource(), base.program.getFragmentShaderSource()));
            }
        }
        return result;
    }

    private String variantName(int index) { return name + " / variant " + index; }

    @Override
    public List<GpuShaderInputs.Program> uniformPrograms() {
        List<GpuShaderInputs.Program> result = new ArrayList<>();
        for (int i = 0; i < handles.size(); i++) {
            if (handles.get(i).shader instanceof BaseShader base && base.program instanceof GpuShaderUniforms shader) {
                result.add(new GpuShaderInputs.Program(variantName(i + 1), shader));
            }
        }
        return result;
    }

    @Override
    public void dispose() {
        session.remove(this);
        active.value().dispose();
        if (fallback != null) { fallback.dispose(); }
        handles.forEach(handle -> { handle.shader = null; handle.sample.meshPart.mesh.dispose(); });
        handles.clear();
    }

    private static VertexAttribute[] attributes(VertexAttributes attributes) {
        VertexAttribute[] result = new VertexAttribute[attributes.size()];
        for (int i = 0; i < result.length; i++) { result[i] = attributes.get(i).copy(); }
        return result;
    }

    private static final class Handle implements Shader {
        private Shader shader;
        private final Renderable sample = new Renderable();
        private final GpuShaderManager.Inputs inputs;

        Handle(Shader shader, Renderable renderable) {
            this.shader = shader;
            inputs = shader instanceof BaseShader base ? GpuShaderManager.Inputs.of(base.program) : null;
            // Retain a layout-only mesh, never the terrain/model's vertex buffers or its instance.
            Mesh original = renderable.meshPart.mesh;
            sample.meshPart.mesh = new Mesh(true, 0, 0, attributes(original.getVertexAttributes()));
            if (original.isInstanced()) {
                sample.meshPart.mesh.enableInstancedRendering(true, 1, attributes(original.getInstancedAttributes()));
            }
            sample.material = new Material(renderable.material);
            if (renderable.environment != null) {
                sample.environment = new Environment();
                sample.environment.set(renderable.environment);
                sample.environment.shadowMap = renderable.environment.shadowMap;
            }
            sample.bones = renderable.bones == null ? null : renderable.bones.clone();
        }

        @Override
        public void init() { }
        @Override
        public boolean canRender(Renderable renderable) { return shader != null && shader.canRender(renderable); }
        @Override
        public int compareTo(Shader other) { return shader.compareTo(unwrap(other)); }
        @Override
        public void begin(Camera camera, RenderContext context) { shader.begin(camera, context); }
        @Override
        public void render(Renderable renderable) { shader.render(renderable); }
        @Override
        public void end() { shader.end(); }
        @Override
        public void dispose() { /* The provider owns the delegate and the layout sample. */ }
    }
}
