/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderableSorter;
import com.badlogic.gdx.utils.Array;

/** Optional, one-frame inspection of actual terrain submissions; never installed during measured frames. */
final class GpuDrawCallAudit implements RenderableSorter, AutoCloseable {
    private record Origin(String category, int chunk) { }
    private record Row(String pass, String category, String family, int shader, long materialMask,
          long environmentMask, long vertexMask, boolean blended) { }
    private record ChunkMaterial(int chunk, Attributes attributes) { }

    private static final class Counts {
        int draws, knownChunkDraws;
        long submittedIndices;
        final Set<Mesh> meshes = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Attributes, Integer> materials = new HashMap<>();
        final Set<ChunkMaterial> chunkMaterials = new HashSet<>();
        final Set<Integer> chunks = new HashSet<>();
        final Set<List<String>> textureSets = new HashSet<>();
        final Set<Object> waterFields = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<String> ids = new LinkedHashSet<>();
    }

    private final GpuTerrain terrain;
    private final ModelBatch batch;
    private final Field sorterField;
    private final RenderableSorter original;
    private final Map<Mesh, Origin> origins = new IdentityHashMap<>();
    private final Map<Shader, Integer> shaders = new IdentityHashMap<>();
    private final Map<Row, Counts> rows = new LinkedHashMap<>();
    private final Map<String, Integer> passes = new HashMap<>();
    private final Map<String, Integer> categories = new LinkedHashMap<>();
    private final Map<Long, String> masks = new LinkedHashMap<>();
    private String stage = "unlabelled";
    private boolean indexed;

    GpuDrawCallAudit(GpuBattleView view) {
        terrain = (GpuTerrain) field(view, "terrain");
        batch = (ModelBatch) field(terrain, "batch");
        original = batch.getRenderableSorter();
        try {
            sorterField = ModelBatch.class.getDeclaredField("sorter");
            sorterField.setAccessible(true);
            sorterField.set(batch, this);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    void stage(String stage) { this.stage = stage; }

    @Override
    public void sort(Camera camera, Array<Renderable> renderables) {
        original.sort(camera, renderables);
        // Capture installed ownership after this frame's scene update, without asking providers to submit again.
        if (!indexed) { indexOrigins(); indexed = true; }
        String pass = stage + "/" + passes.merge(stage, 1, Integer::sum);
        for (Renderable part : renderables) {
            if (part.meshPart.size == 0) { continue; }
            Origin origin = origin(part);
            String family = family(part);
            BlendingAttribute blend = part.material.get(BlendingAttribute.class, BlendingAttribute.Type);
            Row row = new Row(pass, origin.category(), family,
                  shaders.computeIfAbsent(part.shader, ignored -> shaders.size() + 1), part.material.getMask(),
                  part.environment == null ? 0 : part.environment.getMask(),
                  part.meshPart.mesh.getVertexAttributes().getMaskWithSizePacked(), blend != null && blend.blended);
            Counts counts = rows.computeIfAbsent(row, ignored -> new Counts());
            counts.draws++;
            counts.submittedIndices += part.meshPart.size;
            counts.meshes.add(part.meshPart.mesh);
            // Attributes equality ignores Material.id, which does not affect shader inputs.
            Attributes attributes = new Attributes();
            attributes.set(part.material);
            counts.materials.merge(attributes, 1, Integer::sum);
            if (origin.chunk() >= 0) {
                counts.knownChunkDraws++;
                counts.chunks.add(origin.chunk());
                counts.chunkMaterials.add(new ChunkMaterial(origin.chunk(), attributes));
            }
            if (counts.ids.size() < 3) { counts.ids.add(part.material.id); }
            List<String> textures = new ArrayList<>();
            List<String> aliases = new ArrayList<>();
            for (Attribute attribute : part.material) {
                aliases.add(Attribute.getAttributeAlias(attribute.type));
                if (attribute instanceof TextureAttribute texture) {
                    textures.add(Attribute.getAttributeAlias(attribute.type) + "="
                          + texture.textureDescription.texture.getTextureObjectHandle());
                } else if (attribute instanceof GpuWaterShader) {
                    Object field = field(attribute, "field");
                    if (field != null) {
                        counts.waterFields.add(field);
                        textures.add("waterField=" + ((Texture) field(field, "texture")).getTextureObjectHandle());
                    }
                }
            }
            counts.textureSets.add(List.copyOf(textures));
            masks.putIfAbsent(row.materialMask(), String.join("+", aliases));
            categories.merge(stage + "/" + origin.category(), 1, Integer::sum);
        }
    }

    void report(String scenario) {
        System.out.printf("DRAW AUDIT %s: terrain colour ModelBatch only; stage/category submitted draws=%s%n",
              scenario, categories);
        System.out.println("pass,source,family,shader,materialMask,environmentMask,vertexMask,blended,draws,indices,"
              + "meshes,usedVertices,vertexCapacity,maxMeshVertices,meshesAt48k,meshesAt65532,materialValues,"
              + "repeatedMaterialDraws,knownChunks,knownChunkDraws,chunkMaterialBuckets,sameChunkMaterialExtraDraws,"
              + "textureSets,waterFields,largestMeshUsedCapacity,materialIdExamples");
        var ordered = rows.entrySet().stream()
              .sorted(Comparator.<Map.Entry<Row, Counts>>comparingInt(entry -> entry.getValue().draws).reversed()).toList();
        for (var entry : ordered.stream().limit(24).toList()) {
            Row row = entry.getKey();
            Counts counts = entry.getValue();
            long used = 0, capacity = 0;
            int maximum = 0, near48k = 0, nearCeiling = 0;
            for (Mesh mesh : counts.meshes) {
                int vertices = mesh.getNumVertices();
                used += vertices;
                capacity += mesh.getMaxVertices();
                maximum = Math.max(maximum, vertices);
                if (vertices >= 48000) { near48k++; }
                if (vertices >= 65532) { nearCeiling++; }
            }
            String largest = String.join("|", counts.meshes.stream()
                  .sorted(Comparator.comparingInt(Mesh::getNumVertices).reversed()).limit(3)
                  .map(mesh -> mesh.getNumVertices() + "/" + mesh.getMaxVertices()).toList());
            System.out.printf(java.util.Locale.ROOT,
                  "%s,%s,%s,%d,%x,%x,%x,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%s,%s%n",
                  row.pass(), row.category(), row.family(), row.shader(), row.materialMask(), row.environmentMask(),
                  row.vertexMask(), row.blended(), counts.draws, counts.submittedIndices, counts.meshes.size(),
                  used, capacity, maximum, near48k, nearCeiling, counts.materials.size(),
                  counts.draws - counts.materials.size(), counts.chunks.size(), counts.knownChunkDraws,
                  counts.chunkMaterials.size(), counts.knownChunkDraws - counts.chunkMaterials.size(),
                  counts.textureSets.size(), counts.waterFields.size(), largest, String.join("|", counts.ids));
        }
        int omitted = ordered.stream().skip(24).mapToInt(entry -> entry.getValue().draws).sum();
        System.out.printf("DRAW AUDIT omittedRows=%d omittedDraws=%d%n", Math.max(0, rows.size() - 24), omitted);
        masks.entrySet().stream().limit(24).forEach(entry ->
              System.out.printf("DRAW AUDIT materialMask=%x attributes=%s%n", entry.getKey(), entry.getValue()));
        System.out.println("DRAW AUDIT limits: counts describe submissions, not GPU work or guaranteed mergeable draws. "
              + "Indices exclude instance multiplication. Vertices/capacity count each mesh once per row; parts can share meshes. "
              + "48k is Layer's pre-shape split threshold, 65536 its unsigned-short address space. "
              + "Chunk/material buckets exclude shared or instanced meshes with ambiguous ownership. "
              + "Material equality retains texture/UV/uniform values; different water fields are per-chunk inputs. "
              + "Blended order, pass boundaries, transforms and vertex limits still constrain batching.");
    }

    private Origin origin(Renderable part) {
        if (GpuTreeInstances.instanced(part)) { return new Origin("instanced-trees", -1); }
        if (part.material.has(GpuGroundCover.Wind.TYPE)) { return new Origin("grass", -1); }
        return origins.getOrDefault(part.meshPart.mesh, new Origin("other", -1));
    }

    private void indexOrigins() {
        // Frozen baseline classes predate this optional aggregate. Read its existing ranges without re-submitting
        // through their provider, which would clear shader/environment on the batch's queued objects.
        Object props = field(terrain, "propBatch", true);
        if (props != null) {
            for (Object page : ((Map<?, ?>) field(props, "pages")).values()) {
                for (Object value : (Array<?>) field(page, "cached")) {
                    addOrigin(((Renderable) value).meshPart.mesh, "props-batched", -1);
                }
            }
        }
        List<?> chunks = (List<?>) field(terrain, "chunks");
        for (int index = 0; index < chunks.size(); index++) {
            Object chunk = chunks.get(index);
            for (String category : List.of("opaque", "scatter", "overlays", "water", "tactical", "flatTrees")) {
                for (Object value : (List<?>) field(chunk, category)) {
                    for (Mesh mesh : ((ModelInstance) value).model.meshes) { addOrigin(mesh, category, index); }
                }
            }
            for (String cache : List.of("propRenderables", "shadowPropRenderables")) {
                for (Object value : (Array<?>) field(chunk, cache)) {
                    addOrigin(((Renderable) value).meshPart.mesh, "props", index);
                }
            }
            for (Object prop : (Set<?>) field(chunk, "faded")) {
                for (Mesh mesh : ((ModelInstance) field(prop, "instance")).model.meshes) {
                    addOrigin(mesh, "faded-props", index);
                }
            }
        }
    }

    private void addOrigin(Mesh mesh, String category, int chunk) {
        Origin previous = origins.get(mesh);
        origins.put(mesh, previous == null ? new Origin(category, chunk)
              : new Origin(previous.category().equals(category) ? category : "shared", previous.chunk() == chunk ? chunk : -1));
    }

    private static String family(Renderable part) {
        for (Attribute attribute : part.material) {
            String alias = Attribute.getAttributeAlias(attribute.type);
            if (("boardSculpt".equals(alias) || "boardTerrainDetail".equals(alias))
                  && attribute instanceof FloatAttribute value) {
                int family = (int) value.value;
                return family >= 0 && family < BoardScene.Surface.values().length
                      ? BoardScene.Surface.values()[family].name() : "bed-" + family;
            }
            if (attribute instanceof GpuWaterShader) {
                return "water-" + field(attribute, "palette") + (Boolean.TRUE.equals(field(attribute, "falling")) ? "-fall" : "")
                      + (Boolean.TRUE.equals(field(attribute, "spray")) ? "-spray" : "");
            }
        }
        return "-";
    }

    private static Object field(Object object, String name) {
        return field(object, name, false);
    }

    private static Object field(Object object, String name, boolean optional) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                // The benchmark uses an anonymous GpuBattleView subclass.
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        if (optional) { return null; }
        throw new IllegalStateException("Missing audit field " + name + " on " + object.getClass());
    }

    @Override
    public void close() {
        try { sorterField.set(batch, original); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
}
