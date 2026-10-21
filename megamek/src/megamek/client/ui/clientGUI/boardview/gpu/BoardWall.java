/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import com.badlogic.gdx.utils.ShortArray;
import megamek.common.Configuration;
import megamek.common.board.HexTile;
import megamek.common.enums.BuildingType;

/** Authored fortress components assembled identically for drawing, picking and ground-cover clearance. */
final class BoardWall {
    static final int LODS = 2;
    private static final String PREFIX = "buildings/wall-";
    private static final float SOURCE_HEIGHT = 54;
    private static final float TRIM = 6;
    private static final float BAY_START = 25;
    private static final float BAY_LENGTH = 18;
    private static final Map<File, BoardKit<List<ModelData>>> COMPONENTS = new ConcurrentHashMap<>();

    private BoardWall() { }

    static boolean supports(String asset) {
        if (!asset.startsWith(PREFIX)) { return false; }
        try {
            int exits = Integer.parseInt(asset.substring(PREFIX.length()));
            return exits >= 0 && exits <= 63 && asset.equals(PREFIX + exits);
        } catch (NumberFormatException invalid) { return false; }
    }

    /** An authored model changes a wall's appearance, not its solid building rules. */
    static boolean building(BoardScene.Cover cover, BoardScene.Feature feature) {
        return feature.kind() == BoardScene.FeatureKind.BUILDING
              && (supports(feature.asset()) || cover.structure() == BuildingType.WALL.getTypeValue());
    }

    /** A reload publishes new immutable source data; in-flight terrain workers keep their existing copies. */
    static void reload() { COMPONENTS.clear(); }

    private static ModelData component(String name, int lod) {
        File root = new File(Configuration.dataDir(), "models/board");
        File file = new File(root, "buildings/fortress/" + name + ".glb").getAbsoluteFile();
        return COMPONENTS.computeIfAbsent(file,
              key -> new BoardKit<>(() -> RigidGlb.loadLods(new FileHandle(key), root.toPath()))).get().get(lod);
    }

    /** Derived from the captured board; shared by CPU clearance, picking and the GPU model cache. */
    record Layout(String asset, int height, List<Integer> arms) {
        Layout { arms = List.copyOf(arms); }
    }

    static Layout layout(String asset, int height) {
        int levels = Math.max(1, height);
        return new Layout(asset, levels, java.util.Collections.nCopies(6, levels));
    }

    static Layout layout(BoardScene scene, BoardScene.Tile tile, BoardScene.Feature feature) {
        Layout own = layout(feature.asset(), Math.round(feature.height()));
        List<Integer> arms = new ArrayList<>(own.arms());
        int exits = Integer.parseInt(feature.asset().substring(PREFIX.length()));
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & 1 << direction) == 0) { continue; }
            var neighbor = scene.neighbor(tile, direction);
            if (neighbor == null) { continue; }
            int opposite = (direction + 3) % 6;
            var connected = neighbor.features().stream().filter(part -> supports(part.asset())
                  && (Integer.parseInt(part.asset().substring(PREFIX.length())) & 1 << opposite) != 0)
                  .findFirst().orElse(null);
            if (connected == null) { continue; }
            int top = neighbor.elevation() - tile.elevation() + Math.max(1, Math.round(connected.height()));
            // No overlapping vertical span means a terrain step separates the walls; do not invent a bridge.
            if (top > 0 && neighbor.elevation() < tile.elevation() + own.height()) {
                arms.set(direction, Math.min(own.height(), top));
            }
        }
        return new Layout(own.asset(), own.height(), arms);
    }

    /** Local +Y follows the exit; the exact board lattice also supplies the half-bay's endpoint. */
    private record Piece(String name, float turn, float start, float lengthScale, int height) { }

    private static List<Piece> pieces(Layout layout) {
        String asset = layout.asset();
        if (!supports(asset)) { throw new IllegalArgumentException("Invalid wall asset: " + asset); }
        int exits = Integer.parseInt(asset.substring(PREFIX.length()));
        if (exits == 0) { return List.of(new Piece("terminal", 0, 0, 1, layout.height())); }
        int first = Integer.numberOfTrailingZeros(exits);
        Vector3 end = joint(first);
        float turn = (float) Math.atan2(-end.x, end.y);
        if (exits == ((1 << first) | (1 << ((first + 3) % 6)))
              && layout.arms().get(first) == layout.height() && layout.arms().get((first + 3) % 6) == layout.height()) {
            return List.of(new Piece("straight", turn, 0, end.len() / (HexTile.HEIGHT / 2f), layout.height()));
        }
        List<Piece> result = new ArrayList<>();
        result.add(new Piece(Integer.bitCount(exits) == 1 ? "terminal" : "pier",
              Integer.bitCount(exits) == 1 ? turn + (float) Math.PI : 0, 0, 1, layout.height()));
        for (int direction = 0; direction < 6; direction++) {
            if ((exits & (1 << direction)) == 0) { continue; }
            end = joint(direction);
            result.add(new Piece("half-bay", (float) Math.atan2(-end.x, end.y),
                  BAY_START, (end.len() - BAY_START) / BAY_LENGTH, layout.arms().get(direction)));
        }
        return List.copyOf(result);
    }

    private static Vector3 joint(int direction) {
        for (int edge = 0; edge < 6; edge++) {
            if (HexTile.edgeDirection(edge) != direction) { continue; }
            int next = (edge + 1) % 6;
            return new Vector3((HexTile.cornerOffsetX(edge, HexTile.WIDTH)
                  + HexTile.cornerOffsetX(next, HexTile.WIDTH)) / 2,
                  (HexTile.cornerOffsetY(edge, HexTile.HEIGHT)
                        + HexTile.cornerOffsetY(next, HexTile.HEIGHT)) / 2, 0);
        }
        throw new IllegalArgumentException("Invalid wall direction: " + direction);
    }

    static ModelData model(String asset) { return model(asset, 1); }
    static ModelData model(String asset, int levels) { return model(asset, levels, 0); }

    /**
     * The source's base and parapet retain their depth at every rules height. Matching body sections repeat rather
     * than stretching vertically; atlas UVs, images and material factors remain authored. No GL resource belongs here.
     */
    static ModelData model(String asset, int levels, int lod) {
        return model(layout(asset, levels), lod);
    }

    static ModelData model(Layout layout, int lod) {
        if (lod < 0 || lod >= RigidGlb.LEVELS) { throw new IllegalArgumentException("Invalid wall LOD: " + lod); }
        var result = new RigidGlb.Data();
        result.id = layout + "-lod" + lod;
        var materials = new HashSet<String>();
        int index = 0;
        for (Piece piece : pieces(layout)) {
            var source = (RigidGlb.Data) component(piece.name(), lod);
            String materialPrefix = piece.name() + "/";
            result.images.putAll(source.images);
            // libGDX Array reuses its iterator: immutable kit data is read concurrently by terrain workers.
            for (int materialIndex = 0; materialIndex < source.materials.size; materialIndex++) {
                var material = source.materials.get(materialIndex);
                String id = materialPrefix + material.id;
                if (!materials.add(id)) { continue; }
                var copy = new ModelMaterial();
                copy.id = id;
                copy.ambient = material.ambient; copy.diffuse = material.diffuse; copy.specular = material.specular;
                copy.emissive = material.emissive; copy.reflection = material.reflection;
                copy.shininess = material.shininess; copy.opacity = material.opacity; copy.textures = material.textures;
                result.materials.add(copy);
                var surface = source.surfaces.get(material.id);
                if (surface != null) { result.surfaces.put(id, surface); }
                var cutoff = source.alphaTests.get(material.id);
                if (cutoff != null) { result.alphaTests.put(id, cutoff); }
            }
            String piecePrefix = piece.name() + "-" + index++ + "/";
            float height = piece.height() * HexTile.LEVEL_HEIGHT;
            for (Section section : sections(height)) {
                append(result, source, piece, section, piecePrefix + section.name() + "/", materialPrefix);
            }
        }
        return result;
    }

    private record Section(String name, float from, float to, float base, float scale) { }

    private static List<Section> sections(float height) {
        List<Section> result = new ArrayList<>();
        result.add(new Section("base", 0, TRIM, 0, 1));
        float body = height - 2 * TRIM;
        int count = (int) Math.ceil(body / (SOURCE_HEIGHT - 2 * TRIM));
        float size = body / count;
        for (int index = 0; index < count; index++) {
            result.add(new Section("body-" + index, TRIM, SOURCE_HEIGHT - TRIM, TRIM + index * size,
                  size / (SOURCE_HEIGHT - 2 * TRIM)));
        }
        result.add(new Section("cap", SOURCE_HEIGHT - TRIM, SOURCE_HEIGHT, height - TRIM, 1));
        return result;
    }

    private static void append(RigidGlb.Data result, RigidGlb.Data source, Piece piece, Section section,
          String prefix, String materialPrefix) {
        var included = new HashSet<String>();
        for (int meshIndex = 0; meshIndex < source.meshes.size; meshIndex++) {
            var mesh = source.meshes.get(meshIndex);
            var copy = new ModelMesh();
            copy.id = prefix + mesh.id;
            copy.attributes = mesh.attributes;
            var vertices = new FloatArray();
            int[] remap = new int[mesh.vertices.length / RigidGlb.STRIDE];
            Arrays.fill(remap, -1);
            List<ModelMeshPart> parts = new ArrayList<>();
            for (var original : mesh.parts) {
                var indices = new ShortArray();
                for (int offset = 0; offset < original.indices.length; offset += 3) {
                    float z = 0;
                    for (int corner = 0; corner < 3; corner++) {
                        z += mesh.vertices[Short.toUnsignedInt(original.indices[offset + corner]) * RigidGlb.STRIDE + 2] / 3;
                    }
                    boolean body = z >= TRIM && z < SOURCE_HEIGHT - TRIM;
                    if (!(section.name().equals("base") ? z < TRIM : section.name().equals("cap")
                          ? z >= SOURCE_HEIGHT - TRIM : body)) { continue; }
                    for (int corner = 0; corner < 3; corner++) {
                        int old = Short.toUnsignedInt(original.indices[offset + corner]);
                        int vertex = old * RigidGlb.STRIDE;
                        float vz = mesh.vertices[vertex + 2];
                        if (vz < section.from() - .001f || vz > section.to() + .001f) {
                            throw new IllegalArgumentException("Wall triangles must end at body section boundaries: " + piece.name());
                        }
                        if (remap[old] < 0) {
                            remap[old] = vertices.size / RigidGlb.STRIDE;
                            vertices.addAll(mesh.vertices, vertex, RigidGlb.STRIDE);
                        }
                        indices.add((short) remap[old]);
                    }
                }
                if (indices.isEmpty()) { continue; }
                var part = new ModelMeshPart();
                part.id = prefix + original.id;
                part.primitiveType = original.primitiveType;
                part.indices = indices.toArray();
                parts.add(part);
                included.add(original.id);
            }
            if (parts.isEmpty()) { continue; }
            copy.vertices = vertices.toArray();
            transform(copy.vertices, piece, section);
            copy.parts = parts.toArray(ModelMeshPart[]::new);
            result.meshes.add(copy);
        }
        for (int nodeIndex = 0; nodeIndex < source.nodes.size; nodeIndex++) {
            var node = source.nodes.get(nodeIndex);
            if (node.children.length != 0 || !Arrays.equals(new Matrix4().val,
                  new Matrix4(node.translation, node.rotation, node.scale).val)) {
                throw new IllegalArgumentException("Wall components need flat identity nodes: " + piece.name());
            }
            var copy = new ModelNode();
            copy.id = prefix + node.id; copy.meshId = prefix + node.meshId;
            copy.children = new ModelNode[0];
            List<ModelNodePart> parts = new ArrayList<>();
            for (var original : node.parts) {
                if (!included.contains(original.meshPartId)) { continue; }
                var part = new ModelNodePart();
                part.meshPartId = prefix + original.meshPartId;
                part.materialId = materialPrefix + original.materialId;
                parts.add(part);
            }
            if (parts.isEmpty()) { continue; }
            copy.parts = parts.toArray(ModelNodePart[]::new);
            result.nodes.add(copy);
        }
    }

    private static void transform(float[] vertices, Piece piece, Section section) {
        float c = (float) Math.cos(piece.turn()), s = (float) Math.sin(piece.turn());
        var normal = new Vector3();
        for (int i = 0; i < vertices.length; i += RigidGlb.STRIDE) {
            float x = vertices[i], y = vertices[i + 1] * piece.lengthScale() + piece.start(), z = vertices[i + 2];
            vertices[i] = c * x - s * y;
            vertices[i + 1] = s * x + c * y;
            vertices[i + 2] = section.base() + (z - section.from()) * section.scale();
            normal.set(vertices[i + 3], vertices[i + 4] / piece.lengthScale(),
                  vertices[i + 5] / section.scale()).nor();
            vertices[i + 3] = c * normal.x - s * normal.y;
            vertices[i + 4] = s * normal.x + c * normal.y;
            vertices[i + 5] = normal.z;
        }
    }

    /** Exact near-level triangles, copied so obstacle/world transforms never mutate the source kit. */
    static List<Vector3> triangles(String asset) { return triangles(asset, 1); }

    static List<Vector3> triangles(String asset, int levels) {
        return triangles(layout(asset, levels));
    }

    static List<Vector3> triangles(Layout layout) {
        List<Vector3> result = new ArrayList<>();
        for (var mesh : model(layout, 0).meshes) {
            for (var part : mesh.parts) {
                for (short index : part.indices) {
                    int offset = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                    result.add(new Vector3(mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2]));
                }
            }
        }
        return result;
    }
}
