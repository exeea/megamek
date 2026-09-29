/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMaterial;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMesh;
import com.badlogic.gdx.graphics.g3d.model.data.ModelMeshPart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNode;
import com.badlogic.gdx.graphics.g3d.model.data.ModelNodePart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Quaternion;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import de.javagl.jgltf.model.AccessorByteData;
import de.javagl.jgltf.model.AccessorData;
import de.javagl.jgltf.model.AccessorFloatData;
import de.javagl.jgltf.model.AccessorIntData;
import de.javagl.jgltf.model.AccessorModel;
import de.javagl.jgltf.model.AccessorShortData;
import de.javagl.jgltf.model.MaterialModel;
import de.javagl.jgltf.model.MeshModel;
import de.javagl.jgltf.model.GltfModel;
import de.javagl.jgltf.model.MeshPrimitiveModel;
import de.javagl.jgltf.model.NodeModel;
import de.javagl.jgltf.model.TextureModel;
import de.javagl.jgltf.model.io.GltfAssetReader;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;
import de.javagl.jgltf.model.v2.GltfModelCreatorV2;
import de.javagl.jgltf.model.v2.MaterialModelV2;

/**
 * CPU-only importer for the rigid GLB kits. JglTF decodes buffers/accessors; this adapter preserves named parts
 * and converts Y-up/linear glTF data into the board's Z-up/display-color ModelData. Textures and animation remain
 * owned by the existing renderer. Unsupported asset features fail before any graphics resources are allocated.
 */
final class RigidGlb {
    static final int STRIDE = 12;
    private static final Matrix4 Z_UP = new Matrix4(new float[] {
          1, 0, 0, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 0, 0, 1 });
    private static final Matrix4 Y_UP = new Matrix4(Z_UP).tra();

    private RigidGlb() { }

    /** Encoded bytes are retained for managed GPU texture recreation; external files keep a shared path key. */
    record Image(String key, byte[] encoded, String file, int minFilter, int magFilter, int wrapS, int wrapT) { }

    static final class Data extends ModelData {
        final Map<String, Image> images = new HashMap<>();
    }

    static ModelData load(FileHandle file) {
        return load(file, file.file().toPath().toAbsolutePath().getParent());
    }

    static ModelData load(FileHandle file, Path textureRoot) {
        var asset = read(file, textureRoot);
        var source = asset.model();
        return convert(file, file.nameWithoutExtension(), asset, source.getSceneModels().get(asset.scene()).getNodeModels());
    }

    /** One file per component, identity groups named <component>-lodN. Missing levels reuse the preceding data. */
    static List<ModelData> loadLods(FileHandle file) {
        return loadLods(file, file.file().toPath().toAbsolutePath().getParent());
    }

    static List<ModelData> loadLods(FileHandle file, Path textureRoot) {
        var asset = read(file, textureRoot);
        var source = asset.model();
        var roots = source.getSceneModels().get(asset.scene()).getNodeModels();
        String shape = file.nameWithoutExtension();
        Map<String, NodeModel> groups = new HashMap<>();
        // Every level is named, so which mesh draws at a given size is always visible in the file itself.
        boolean grouped = roots.stream().anyMatch(node -> node.getName() != null && node.getName().matches(".*-lod[0-2]"));
        require(grouped, "Name the levels of " + file.name() + " as groups " + MeshLod.name(shape, 0)
              + " (and optionally " + MeshLod.name(shape, 1) + ", " + MeshLod.name(shape, 2) + ")");
        for (var group : roots) {
            require(Set.of(MeshLod.name(shape, 0), MeshLod.name(shape, 1), MeshLod.name(shape, 2))
                  .contains(group.getName()), "Unexpected LOD group: " + group.getName());
            require(groups.put(group.getName(), group) == null, "Duplicate LOD group: " + group.getName());
            require(group.getMeshModels().isEmpty() && !group.getChildren().isEmpty(), "LOD groups contain child nodes");
            require(java.util.Arrays.equals(new Matrix4().val, group.computeLocalTransform(null)),
                  "LOD group transforms must be identity; transform the child rig instead");
        }
        return MeshLod.load(shape, 3, name -> groups.containsKey(name)
              ? convert(file, name, asset, groups.get(name).getChildren()) : null);
    }

    private record Asset(GltfModel model, int scene, Map<TextureModel, Image> images) { }

    private static Asset read(FileHandle file, Path textureRoot) {
        try (var input = file.read()) {
            var reader = new GltfAssetReader();
            reader.setJsonErrorConsumer(error -> { throw new IllegalArgumentException(error.toString()); });
            var asset = reader.readWithoutReferences(input);
            require(asset instanceof GltfAssetV2, "Expected glTF 2.0");
            var binary = (GltfAssetV2) asset;
            var document = binary.getGltf();
            // Empty squad references contain a rigid hierarchy and no geometry buffer.
            require(document.getBuffers() == null || (binary.getBinaryData() != null
                  && document.getBuffers().stream().allMatch(buffer -> buffer.getUri() == null)),
                  "Expected embedded geometry buffers");
            require(document.getExtensionsRequired() == null || document.getExtensionsRequired().isEmpty(),
                  "Required glTF extensions are unsupported");
            require(document.getSkins() == null && document.getAnimations() == null,
                  "Rigid kits use runtime animation");
            require(document.getScene() != null, "GLB needs a default scene");
            // Resolve local images explicitly; the glTF reader never fetches a URI from the network.
            for (var reference : binary.getImageReferences()) {
                var path = imagePath(file, textureRoot, reference.getUri());
                reference.getTarget().accept(ByteBuffer.wrap(Files.readAllBytes(path)));
            }
            var model = GltfModelCreatorV2.create(binary);
            Map<TextureModel, Image> images = new IdentityHashMap<>();
            for (var texture : model.getTextureModels()) {
                var image = texture.getImageModel();
                require(Set.of("image/png", "image/jpeg").contains(image.getMimeType()), "Expected a PNG or JPEG image");
                String path = image.getUri() == null ? null : imagePath(file, textureRoot, image.getUri()).toString();
                byte[] encoded = null;
                String key = path;
                if (path == null) {
                    var bytes = image.getImageData().duplicate();
                    encoded = new byte[bytes.remaining()];
                    bytes.get(encoded);
                    key = file.file().toPath().toAbsolutePath().normalize() + "#image-" + model.getImageModels().indexOf(image);
                }
                Integer samplerIndex = document.getTextures().get(model.getTextureModels().indexOf(texture)).getSampler();
                var sampler = samplerIndex == null ? null : document.getSamplers().get(samplerIndex);
                int min = sampler == null || sampler.getMinFilter() == null ? 9987 : sampler.getMinFilter();
                int mag = sampler == null || sampler.getMagFilter() == null ? 9729 : sampler.getMagFilter();
                int wrapS = sampler == null || sampler.getWrapS() == null ? 10497 : sampler.getWrapS();
                int wrapT = sampler == null || sampler.getWrapT() == null ? 10497 : sampler.getWrapT();
                require(Set.of(9728, 9729, 9984, 9985, 9986, 9987).contains(min)
                      && Set.of(9728, 9729).contains(mag)
                      && Set.of(33071, 33648, 10497).contains(wrapS)
                      && Set.of(33071, 33648, 10497).contains(wrapT), "Unsupported texture sampler");
                if (min != 9987 || mag != 9729 || wrapS != 10497 || wrapT != 10497) {
                    key += "#sampler-" + min + "-" + mag + "-" + wrapS + "-" + wrapT;
                }
                images.put(texture, new Image(key, encoded, path, min, mag, wrapS, wrapT));
            }
            return new Asset(model, document.getScene(), images);
        } catch (IOException | RuntimeException error) {
            throw new IllegalArgumentException("Cannot read rigid GLB " + file + ": " + error.getMessage(), error);
        }
    }

    private static Path imagePath(FileHandle file, Path root, String value) throws IOException {
        var uri = URI.create(value);
        require(!uri.isAbsolute() && uri.getAuthority() == null && uri.getQuery() == null
              && uri.getFragment() == null, "Textures must use local relative paths");
        return UnitModelDescriptor.contained(root, file.file().toPath().toAbsolutePath().getParent().resolve(uri.getPath()));
    }

    private static ModelData convert(FileHandle file, String id, Asset asset, List<NodeModel> roots) {
        try {
            Data result = new Data();
            var source = asset.model();
            result.id = id;
            Map<MaterialModel, String> materials = new IdentityHashMap<>();
            Set<String> materialNames = new HashSet<>();
            for (var sourceMaterial : source.getMaterialModels()) {
                var material = (MaterialModelV2) sourceMaterial;
                require(material.getAlphaMode() == MaterialModelV2.AlphaMode.OPAQUE && !material.isDoubleSided(),
                      "Rigid kits use opaque materials and explicit back faces");
                var target = new ModelMaterial();
                target.id = material.getName();
                require(target.id != null && materialNames.add(target.id), "Material names must be unique");
                float[] color = material.getBaseColorFactor();
                target.diffuse = new Color(display(color[0]), display(color[1]), display(color[2]), color[3]);
                if (material.getBaseColorTexture() != null) {
                    require(material.getBaseColorTexcoord() == null || material.getBaseColorTexcoord() == 0,
                          "Only TEXCOORD_0 is supported");
                    var image = asset.images().get(material.getBaseColorTexture());
                    result.images.put(image.key(), image);
                    var texture = new ModelTexture();
                    texture.id = target.id;
                    texture.usage = ModelTexture.USAGE_DIFFUSE;
                    texture.fileName = image.key();
                    target.textures = new com.badlogic.gdx.utils.Array<>();
                    target.textures.add(texture);
                }
                result.materials.add(target);
                materials.put(material, target.id);
            }
            Map<MeshPrimitiveModel, String> partIds = new IdentityHashMap<>();
            Map<Map<String, AccessorModel>, Integer> offsets = new HashMap<>();
            Set<String> names = new HashSet<>();
            List<ModelMeshPart> parts = new ArrayList<>();
            FloatArray vertices = new FloatArray();
            Set<MeshModel> meshes = new java.util.LinkedHashSet<>();
            Set<NodeModel> collected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            for (var root : roots) { collectMeshes(root, meshes, collected); }
            for (var mesh : meshes) {
                for (var primitive : mesh.getMeshPrimitiveModels()) {
                    require(primitive.getMode() == GL20.GL_TRIANGLES && primitive.getTargets().isEmpty(),
                          "Expected rigid triangle primitives");
                    var attributes = primitive.getAttributes();
                    int base = offsets.computeIfAbsent(attributes, key -> append(vertices, key));
                    var positions = attributes.get("POSITION");
                    var index = primitive.getIndices();
                    int count = index == null ? positions.getCount() : index.getCount();
                    require(count > 0 && count % 3 == 0, "Incomplete triangle");
                    AccessorData indices = index == null ? null : index.getAccessorData();
                    require(index == null || !index.isNormalized() && indices.getNumComponentsPerElement() == 1
                          && Set.of(5121, 5123, 5125).contains(index.getComponentType()), "Invalid index accessor");
                    ModelMeshPart part = new ModelMeshPart();
                    Object authored = primitive.getExtras() instanceof Map<?, ?> extras ? extras.get("mmPart") : null;
                    part.id = authored instanceof String partId ? partId : "part-" + parts.size();
                    require(names.add(part.id), "Duplicate mesh part: " + part.id);
                    part.primitiveType = GL20.GL_TRIANGLES;
                    part.indices = new short[count];
                    for (int i = 0; i < count; i++) {
                        long value = index == null ? i : (long) component(indices, i, 0);
                        require(value >= 0 && value < positions.getCount(), "Vertex index outside primitive");
                        part.indices[i] = (short) (value + base);
                    }
                    parts.add(part);
                    partIds.put(primitive, part.id);
                }
            }
            if (!parts.isEmpty()) {
                var mesh = new ModelMesh();
                mesh.id = "geometry";
                mesh.attributes = new VertexAttribute[] { VertexAttribute.Position(), VertexAttribute.Normal(),
                      VertexAttribute.ColorUnpacked(), VertexAttribute.TexCoords(0) };
                mesh.vertices = vertices.toArray();
                mesh.parts = parts.toArray(ModelMeshPart[]::new);
                result.meshes.add(mesh);
            }
            Set<NodeModel> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            Set<String> nodeNames = new HashSet<>();
            for (var node : roots) {
                result.nodes.add(node(node, partIds, materials, visited, nodeNames));
            }
            return result;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cannot read rigid GLB " + file + ": " + error.getMessage(), error);
        }
    }

    private static void collectMeshes(NodeModel node, Set<MeshModel> meshes, Set<NodeModel> visited) {
        require(visited.add(node), "Node hierarchy contains a cycle or repeated child");
        meshes.addAll(node.getMeshModels());
        for (var child : node.getChildren()) { collectMeshes(child, meshes, visited); }
    }

    private static int append(FloatArray vertices, Map<String, AccessorModel> attributes) {
        require(attributes.keySet().stream().allMatch(Set.of("POSITION", "NORMAL", "COLOR_0", "TEXCOORD_0")::contains),
              "Unsupported rigid vertex attribute");
        var positions = attributes.get("POSITION");
        require(positions != null && attributes.containsKey("NORMAL"), "Positions and normals are required");
        int count = positions.getCount(), base = vertices.size / STRIDE;
        require(count > 0 && count + base <= 65535, "Rigid asset exceeds the vertex budget");
        float[] p = attribute(positions, count, 3);
        float[] n = attribute(attributes.get("NORMAL"), count, 3);
        var colors = attributes.get("COLOR_0");
        int channels = colors == null ? 4 : colors.getElementType().getNumComponents();
        require(channels == 3 || channels == 4, "Expected RGB or RGBA colors");
        float[] c = colors == null ? null : attribute(colors, count, channels);
        float[] uv = attributes.containsKey("TEXCOORD_0") ? attribute(attributes.get("TEXCOORD_0"), count, 2) : null;
        for (int i = 0; i < count; i++) {
            vertices.addAll(p[i * 3], -p[i * 3 + 2], p[i * 3 + 1]);
            vertices.addAll(n[i * 3], -n[i * 3 + 2], n[i * 3 + 1]);
            vertices.addAll(c == null ? 1 : display(c[i * channels]), c == null ? 1 : display(c[i * channels + 1]),
                  c == null ? 1 : display(c[i * channels + 2]), c == null || channels == 3 ? 1 : c[i * channels + 3]);
            vertices.addAll(uv == null ? 0 : uv[i * 2], uv == null ? 0 : uv[i * 2 + 1]);
        }
        return base;
    }

    private static float[] attribute(AccessorModel accessor, int count, int size) {
        var data = accessor.getAccessorData();
        require(accessor.getCount() == count && data.getNumComponentsPerElement() == size, "Mismatched attributes");
        float[] result = new float[count * size];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < size; j++) {
                float value = component(data, i, j);
                if (accessor.isNormalized()) {
                    value = switch (accessor.getComponentType()) {
                        case 5120 -> Math.max(value / 127, -1);
                        case 5121 -> value / 255;
                        case 5122 -> Math.max(value / 32767, -1);
                        case 5123 -> value / 65535;
                        default -> throw new IllegalArgumentException("Invalid normalized accessor");
                    };
                }
                require(Float.isFinite(value), "Non-finite vertex");
                result[i * size + j] = value;
            }
        }
        return result;
    }

    private static float component(AccessorData data, int index, int component) {
        return switch (data) {
            case AccessorFloatData values -> values.get(index, component);
            case AccessorByteData values -> values.getInt(index, component);
            case AccessorShortData values -> values.getInt(index, component);
            case AccessorIntData values -> values.getLong(index, component);
            default -> throw new IllegalArgumentException("Unsupported accessor data");
        };
    }

    private static ModelNode node(NodeModel source, Map<MeshPrimitiveModel, String> parts,
          Map<MaterialModel, String> materials, Set<NodeModel> visited, Set<String> names) {
        require(visited.add(source), "Node hierarchy contains a cycle or repeated child");
        var result = new ModelNode();
        result.id = source.getName();
        require(result.id != null && names.add(result.id), "Node names must be unique");
        result.meshId = "geometry";
        result.translation = new Vector3();
        result.rotation = new Quaternion();
        result.scale = new Vector3(1, 1, 1);
        if (source.getMatrix() != null) {
            Matrix4 transform = new Matrix4(Z_UP).mul(new Matrix4(source.getMatrix())).mul(Y_UP);
            transform.getTranslation(result.translation);
            transform.getRotation(result.rotation, true);
            transform.getScale(result.scale);
        } else {
            float[] t = source.getTranslation(), r = source.getRotation(), s = source.getScale();
            if (t != null) { result.translation.set(t[0], -t[2], t[1]); }
            if (r != null) { result.rotation.set(r[0], -r[2], r[1], r[3]); }
            if (s != null) { result.scale.set(s[0], s[2], s[1]); }
        }
        for (float value : new Matrix4(result.translation, result.rotation, result.scale).val) {
            require(Float.isFinite(value), "Non-finite node transform");
        }
        List<ModelNodePart> nodeParts = new ArrayList<>();
        for (var mesh : source.getMeshModels()) {
            for (var primitive : mesh.getMeshPrimitiveModels()) {
                var part = new ModelNodePart();
                part.meshPartId = parts.get(primitive);
                part.materialId = materials.get(primitive.getMaterialModel());
                require(part.materialId != null, "Missing named material");
                nodeParts.add(part);
            }
        }
        result.parts = nodeParts.toArray(ModelNodePart[]::new);
        result.children = source.getChildren().stream().map(child -> node(child, parts, materials, visited, names))
              .toArray(ModelNode[]::new);
        return result;
    }

    private static float display(float linear) {
        return linear <= .0031308f ? 12.92f * linear : (float) (1.055 * Math.pow(linear, 1 / 2.4) - .055);
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalArgumentException(message); }
    }
}
