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
import com.badlogic.gdx.utils.IntArray;

/**
 * Reuses static terrain material state across consecutive compatible draws. Chunks own their original meshes;
 * neither camera movement nor an edit copies geometry. A group borrows renderables only until ModelBatch.flush()
 * returns. Grass keeps the original depth order, including its terrain ties. Perspective parts reuse state only
 * when all their projected-detail uniforms agree exactly.
 */
final class GpuTerrainBatch implements RenderableSorter {
    private static final float[] IDENTITY = new Matrix4().val;
    private static final Renderable EMPTY = new Renderable();
    private final GpuOpaqueSorter original = new GpuOpaqueSorter();
    private final Predicate<Material> terrain;
    private final Map<Material, Integer> materialIds = new IdentityHashMap<>();
    private final Map<Attributes, Integer> materialValues = new HashMap<>();
    private final Array<Renderable> ordered = new Array<>();
    private final List<Group> groups = new ArrayList<>();
    private boolean enabled = true;

    GpuTerrainBatch(Predicate<Material> terrain) { this.terrain = terrain; }

    void setEnabled(boolean value) { enabled = value; }

    /** Materials are immutable between terrain updates; do not retain replaced chunks through this lookup. */
    void clear() { materialIds.clear(); materialValues.clear(); ordered.clear(); groups.clear(); }

    @Override
    public void sort(Camera camera, Array<Renderable> parts) {
        original.sort(camera, parts);
        for (Group group : groups) { group.clear(); }
        if (!enabled) { return; }
        for (Renderable part : parts) {
            if (part.material.has(GpuGroundCover.Wind.TYPE)) { return; }
        }
        ordered.clear();
        GpuTerrain.ShadingDetail flat = camera.projection.val[Matrix4.M33] != 0 ? GpuTerrain.detail(camera, null) : null;
        int from = 0;
        int usedGroups = 0;
        while (from < parts.size) {
            Shader shader = parts.get(from).shader;
            int to = from + 1;
            while (to < parts.size && parts.get(to).shader == shader) { to++; }
            Shader implementation = GpuShaderProvider.unwrap(shader);
            boolean eligible = implementation instanceof DefaultShader;
            for (int i = from; eligible && i < to; i++) { eligible = eligible(parts.get(i)); }
            if (!eligible) {
                for (int i = from; i < to; i++) { ordered.add(parts.get(i)); }
            } else {
                // Material sorting changes winners at shared depth ties. Reuse state only in the existing order.
                if (usedGroups == groups.size()) { groups.add(new Group()); }
                Group group = groups.get(usedGroups++);
                group.shader = (DefaultShader) implementation;
                group.combined.set(parts.get(from));
                group.combined.shader = group;
                ordered.add(group.combined);
                for (int i = from; i < to; i++) {
                    Renderable part = parts.get(i);
                    GpuTerrain.ShadingDetail detail = flat == null ? GpuTerrain.detail(camera, part) : flat;
                    int material = materialId(part.material);
                    group.parts.add(part);
                    group.materials.add(material);
                    group.details.add(detail);
                }
            }
            from = to;
        }
        parts.clear();
        parts.addAll(ordered);
        ordered.clear();
    }

    boolean eligible(Renderable part) {
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
        return part.shader instanceof Group group ? group.parts : List.of(part);
    }

    private static final class Group implements Shader {
        DefaultShader shader;
        final Renderable combined = new Renderable();
        final Array<Renderable> parts = new Array<>();
        final IntArray materials = new IntArray();
        final Array<GpuTerrain.ShadingDetail> details = new Array<>();

        void clear() {
            parts.clear();
            materials.clear();
            details.clear();
            combined.set(EMPTY);
            shader = null;
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
            int bound = 0;
            shader.render(parts.get(bound));
            boolean borrowed = false;
            for (int i = 1; i < parts.size; i++) {
                Renderable next = parts.get(i);
                if (materials.get(bound) == materials.get(i) && details.get(bound).equals(details.get(i))
                      && parts.get(bound).environment == next.environment) {
                    next.meshPart.render(shader.program);
                    borrowed = true;
                } else {
                    // Direct mesh draws unbind their VAO. Restore the mesh BaseShader still believes is bound
                    // before handing control back; begin/end remain once per shader, never once per material.
                    if (borrowed) { parts.get(bound).meshPart.mesh.bind(shader.program); }
                    shader.render(next);
                    bound = i;
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
