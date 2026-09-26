/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;

/**
 * Reuses static terrain material state across consecutive compatible draws. Chunks own their original meshes;
 * neither camera movement nor an edit copies geometry. A group borrows renderables only until ModelBatch.flush()
 * returns. Grass keeps the original depth order, including its terrain ties. Perspective parts reuse state only
 * when all their projected-detail uniforms agree exactly.
 */
final class GpuTerrainBatch implements RenderableSorter {
    private static final float[] IDENTITY = new Matrix4().val;
    private final GpuOpaqueSorter original = new GpuOpaqueSorter();
    private final Predicate<Material> terrain;
    private final Map<Material, Integer> materialIds = new IdentityHashMap<>();
    private final Map<Attributes, Integer> materialValues = new HashMap<>();
    private final Array<Renderable> ordered = new Array<>();
    private boolean enabled = true;

    GpuTerrainBatch(Predicate<Material> terrain) { this.terrain = terrain; }

    void setEnabled(boolean value) { enabled = value; }

    /** Materials are immutable between terrain updates; do not retain replaced chunks through this lookup. */
    void clear() { materialIds.clear(); materialValues.clear(); ordered.clear(); }

    @Override
    public void sort(Camera camera, Array<Renderable> parts) {
        original.sort(camera, parts);
        if (!enabled) { return; }
        for (Renderable part : parts) {
            if (part.material.has(GpuGroundCover.Wind.TYPE)) { return; }
        }
        ordered.clear();
        GpuTerrain.ShadingDetail flat = camera.projection.val[Matrix4.M33] != 0 ? GpuTerrain.detail(camera, null) : null;
        int from = 0;
        while (from < parts.size) {
            Shader shader = parts.get(from).shader;
            int to = from + 1;
            while (to < parts.size && parts.get(to).shader == shader) { to++; }
            boolean eligible = shader instanceof DefaultShader;
            for (int i = from; eligible && i < to; i++) { eligible = eligible(parts.get(i)); }
            if (!eligible) {
                for (int i = from; i < to; i++) { ordered.add(parts.get(i)); }
            } else {
                // Material sorting changes winners at shared depth ties. Reuse state only in the existing order.
                Group group = new Group((DefaultShader) shader);
                Renderable combined = new Renderable().set(parts.get(from));
                combined.shader = group;
                ordered.add(combined);
                for (int i = from; i < to; i++) {
                    Renderable part = parts.get(i);
                    GpuTerrain.ShadingDetail detail = flat == null ? GpuTerrain.detail(camera, part) : flat;
                    int material = materialId(part.material);
                    group.parts.add(new Range(part, material, detail));
                }
            }
            from = to;
        }
        parts.clear();
        parts.addAll(ordered);
        ordered.clear();
    }

    private boolean eligible(Renderable part) {
        return terrain.test(part.material) && part.bones == null && !part.meshPart.mesh.isInstanced()
              && part.meshPart.primitiveType == GL20.GL_TRIANGLES && part.meshPart.size > 0
              && !part.material.has(BlendingAttribute.Type) && !part.material.has(FloatAttribute.AlphaTest)
              && Arrays.equals(part.worldTransform.val, IDENTITY);
    }

    private int materialId(Material material) {
        return materialIds.computeIfAbsent(material, value -> {
            Attributes attributes = new Attributes();
            attributes.set(value);
            return materialValues.computeIfAbsent(attributes, ignored -> materialValues.size());
        });
    }

    /** The draw audit inspects the actual ranges rather than counting a material group as one hardware draw. */
    static Iterable<Renderable> ranges(Renderable part) {
        return part.shader instanceof Group group ? group.parts.stream().map(Range::part).toList() : List.of(part);
    }

    private record Range(Renderable part, int material, GpuTerrain.ShadingDetail detail) { }

    private static final class Group implements Shader {
        final DefaultShader shader;
        final List<Range> parts = new ArrayList<>();

        Group(DefaultShader shader) {
            this.shader = shader;
        }

        @Override
        public void init() { }

        @Override
        public int compareTo(Shader other) { return 0; }

        @Override
        public boolean canRender(Renderable part) { return part.shader == this; }

        @Override
        public void begin(Camera camera, RenderContext context) { shader.begin(camera, context); }

        @Override
        public void render(Renderable ignored) {
            Range bound = parts.getFirst();
            shader.render(bound.part());
            boolean borrowed = false;
            for (int i = 1; i < parts.size(); i++) {
                Range next = parts.get(i);
                if (bound.material() == next.material() && bound.detail().equals(next.detail())
                      && bound.part().environment == next.part().environment) {
                    next.part().meshPart.render(shader.program);
                    borrowed = true;
                } else {
                    // Direct mesh draws unbind their VAO. Restore the mesh BaseShader still believes is bound
                    // before handing control back; begin/end remain once per shader, never once per material.
                    if (borrowed) { bound.part().meshPart.mesh.bind(shader.program); }
                    shader.render(next.part());
                    bound = next;
                    borrowed = false;
                }
            }
        }

        @Override
        public void end() { shader.end(); }

        @Override
        public void dispose() { }
    }
}
