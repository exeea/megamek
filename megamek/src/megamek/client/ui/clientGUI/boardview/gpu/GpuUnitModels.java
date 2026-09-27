/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.loader.G3dModelLoader;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.Configuration;
import megamek.logging.MMLogger;

/** GL-thread ownership of shared unit assets. Failed or absent assets fall back without breaking the board. */
final class GpuUnitModels implements Disposable {
    /** Enable authored unit meshes in both GPU camera views. */
    static final boolean ENABLED = true;

    private static final MMLogger LOGGER = MMLogger.create(GpuUnitModels.class);
    private final Path root;
    private final Map<Path, JsonValue> descriptors = new HashMap<>();
    private final Map<Path, GpuUnitModel> models = new HashMap<>();
    private final Map<Path, ModularAsset> modular = new HashMap<>();
    private final Map<Integer, Assembly> assemblies = new HashMap<>();
    private final Set<Path> failed = new HashSet<>();
    private final Map<String, Texture> modelTextures = new HashMap<>();
    private Texture bark;

    GpuUnitModels() {
        this(Configuration.dataDir().toPath().resolve("models"));
    }

    /** Review tests may use a separate asset root; the game always uses its deployed models directory. */
    GpuUnitModels(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The library owns Model disposal. Callers create independent ModelInstances, which share its buffers. */
    record ModularAsset(UnitModelDescriptor descriptor, List<Model> levels, List<Integer> triangleCounts) {
        Model model() { return levels.getFirst(); }
        Model model(int level) { return levels.get(level); }
        int triangles() { return triangleCounts.getFirst(); }
        int triangles(int level) { return triangleCounts.get(level); }
    }

    private record Assembly(String asset, String fallback, String variant, int figures,
          UnitModelState.Structure structure, GpuUnitModel model) {
        boolean matches(BoardScene.UnitModel selection) {
            return java.util.Objects.equals(asset, selection.asset())
                  && java.util.Objects.equals(fallback, selection.fallback())
                  && java.util.Objects.equals(variant, selection.variant()) && figures == selection.figures()
                  && structure.equals(selection.state().structure());
        }

        void dispose() {
            if (model != null && model.modularCoordinates()) {
                model.dispose();
            }
        }
    }

    /** GLB is the deployed format; existing custom G3DJ descriptors remain readable. No GPU allocation here. */
    static ModelData meshData(FileHandle file) {
        return file.extension().equals("glb") ? RigidGlb.loadLods(file).getFirst()
              : new G3dModelLoader(new JsonReader()).loadModelData(file);
    }

    JsonValue descriptor(String asset) {
        Path file = root.resolve(asset).normalize();
        try {
            UnitModelDescriptor.contained(root, file);
            return descriptors.computeIfAbsent(file, path -> new JsonReader().parse(new FileHandle(path.toFile())));
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read model descriptor: " + asset, error);
        }
    }

    ModularAsset modular(String asset) {
        Path descriptor = root.resolve(asset).normalize();
        if (!descriptor.startsWith(root) || failed.contains(descriptor)) {
            return null;
        }
        try {
            if (!modular.containsKey(descriptor)) {
                UnitModelDescriptor.contained(root, descriptor);
                var value = UnitModelDescriptor.read(descriptor);
                Path mesh = UnitModelDescriptor.contained(root, descriptor.getParent().resolve(value.mesh()));
                var file = new FileHandle(mesh.toFile());
                var data = file.extension().equals("glb") ? RigidGlb.loadLods(file, root) : List.of(meshData(file));
                List<Model> levels = new java.util.ArrayList<>();
                List<Integer> counts = new java.util.ArrayList<>();
                try {
                    for (int level = 0; level < 3; level++) {
                        var geometry = data.get(Math.min(level, data.size() - 1));
                        int previous = data.indexOf(geometry);
                        if (previous < level) {
                            levels.add(levels.get(previous));
                            counts.add(counts.get(previous));
                            continue;
                        }
                        counts.add(value.validate(geometry, level));
                        Model model = ModelTextures.create(geometry, modelTextures, filename -> modelTextures.computeIfAbsent(
                              filename, key -> new Texture(new FileHandle(key), true)));
                        levels.add(model);
                        for (var material : model.materials) {
                            if ("bark".equals(material.id) && !material.has(TextureAttribute.Diffuse)) {
                                if (bark == null) {
                                    Path texture = UnitModelDescriptor.contained(root, root.resolve("board/textures/foliage/bark.png"));
                                    bark = new Texture(new FileHandle(texture.toFile()), true);
                                    bark.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
                                    bark.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                                }
                                material.set(TextureAttribute.createDiffuse(bark));
                            }
                        }
                    }
                    modular.put(descriptor, new ModularAsset(value, List.copyOf(levels), List.copyOf(counts)));
                } catch (IOException | RuntimeException error) {
                    new HashSet<>(levels).forEach(Model::dispose);
                    throw error;
                }
            }
            return modular.get(descriptor);
        } catch (IOException | RuntimeException error) {
            failed.add(descriptor);
            LOGGER.warn("Cannot load modular unit asset {}; using its fallback: {}", descriptor, error.getMessage());
            return null;
        }
    }

    /** Ground props borrow the same module buffers as equipment held by a Mek. */
    ModularAsset equipment(String internalName) {
        String catalog = "units/modular/equipment.json";
        Path path = root.resolve(catalog);
        if (failed.contains(path)) {
            return null;
        }
        try {
            String asset = new UnitEquipmentModels(descriptor(catalog)).asset(internalName);
            return asset == null ? null : modular(asset);
        } catch (RuntimeException error) {
            failed.add(path);
            LOGGER.warn("Cannot read equipment catalog {}; keeping flat ground markers: {}", path, error.getMessage());
            return null;
        }
    }

    GpuUnitModel get(BoardScene.UnitModel selection) {
        return get(selection, Integer.MIN_VALUE);
    }

    /** Effects may use a displayed assembly, but must never rebuild a past loadout during recovery after a refit. */
    GpuUnitModel loaded(BoardScene.UnitModel selection, int unitId) {
        var assembly = assemblies.get(unitId);
        return selection != null && selection.state() != null && assembly != null && assembly.matches(selection)
              ? assembly.model() : null;
    }

    GpuUnitModel get(BoardScene.UnitModel selection, int unitId) {
        if (selection == null) {
            return null;
        }
        Assembly cached = assemblies.get(unitId);
        if (selection.state() != null && cached != null && cached.matches(selection)) {
            return cached.model();
        }
        GpuUnitModel model = load(selection.asset(), selection);
        if (model == null && !java.util.Objects.equals(selection.asset(), selection.fallback())) {
            model = load(selection.fallback(), selection);
        }
        if (selection.state() != null) {
            if (cached != null) {
                cached.dispose();
            }
            assemblies.put(unitId, new Assembly(selection.asset(), selection.fallback(), selection.variant(),
                  selection.figures(), selection.state().structure(), model));
        }
        return model;
    }

    private GpuUnitModel load(String asset, BoardScene.UnitModel selection) {
        if (asset == null) {
            return null;
        }
        Path descriptor = root.resolve(asset).normalize();
        if (!descriptor.startsWith(root) || failed.contains(descriptor)) {
            return null;
        }
        Path modelPath = descriptor;
        boolean assembly = false;
        try {
            JsonValue value = descriptor(asset);
            int schema = value.getInt("schema", 0);
            if (schema != 1 && schema != 2) {
                throw new IllegalArgumentException("Unsupported unit-model schema: " + descriptor);
            }
            if (value.getInt("schema") == 2) {
                assembly = true;
                if (selection.state() == null) {
                    return null;
                }
                String compatibility = value.getString("compatibility", null);
                if (compatibility != null && !compatibility.equals(selection.variant())) {
                    return null;
                }
                if ("mek".equals(value.getString("kind"))) {
                    return MekVisual.assemble(this, value, selection.state().structure());
                }
                if ("family".equals(value.getString("kind"))) {
                    return FamilyVisual.assemble(this, value, selection.state().structure());
                }
                if ("squadron".equals(value.getString("kind"))) {
                    return SquadronVisual.assemble(this, value, selection.state().structure());
                }
                if (!"formation".equals(value.getString("kind"))) {
                    throw new IllegalArgumentException("Unknown assembly kind");
                }
                List<InfantryVisual.Part> parts = switch (value.getString("family")) {
                    case "infantry" -> InfantryVisual.parts(selection.state().structure(), value, selection.figures());
                    case "battle-armor" -> BattleArmorVisual.parts(selection.state().structure(), value);
                    default -> throw new IllegalArgumentException("Unknown formation family");
                };
                String family = value.getString("family");
                return formation(parts, lod1Suits(value), UnitFamilyScale.forFamily(family),
                      UnitModelDescriptor.formationTriangleLimit(family, parts.size()));
            }
            modelPath = descriptor.getParent().resolve(selectModel(value, selection.variant(), selection.figures()))
                  .normalize();
            if (!modelPath.startsWith(root) || failed.contains(modelPath)) {
                return null;
            }
            if (!models.containsKey(modelPath)) {
                if (!Files.isRegularFile(modelPath)) {
                    throw new IllegalArgumentException("Missing unit mesh " + modelPath);
                }
                var data = meshData(new FileHandle(modelPath.toFile()));
                // Schema-1 variants already contain their loadouts; their total is not a bare-body budget.
                GpuUnitModel visual = new GpuUnitModel(new Model(data), value.getString("upperBodyNode", null),
                      UnitFamilyScale.forFamily(value.getString("family", value.getString("kind", ""))));
                boolean isMek = "mek".equals(value.getString("kind", ""));
                if (isMek && !visual.turnsUpperBody()) {
                    LOGGER.debug("[GpuTwist] {} has no upper body part to turn: a torso twist turns the whole unit",
                          modelPath);
                }
                // Say what loaded, not only what failed. Without this a unit showing its old artwork and a
                // unit whose asset never resolved produce the same empty log, and neither can be told apart
                // from the renderer simply not having run.
                LOGGER.debug("[GpuModel] loaded {} ({} triangles in the bare body)", modelPath,
                      visual.instance.model.meshParts.size);
                models.put(modelPath, visual);
            }
            return models.get(modelPath);
        } catch (RuntimeException error) {
            // A bad custom loadout must not poison a shared descriptor for every other unit.
            if (!assembly) {
                failed.add(modelPath);
            }
            LOGGER.warn("Cannot load 3D unit asset {}; using its fallback: {}", modelPath, error.getMessage());
            return null;
        }
    }

    /** A formation's optional LOD1 suit, keyed by its LOD0 trooper. Older custom field names remain readable. */
    private static Map<String, String> lod1Suits(JsonValue descriptor) {
        String trooper = descriptor.getString("trooper", null);
        String trooperLod1 = descriptor.getString("trooperLod1", descriptor.getString("farTrooper", null));
        return (trooper == null || trooperLod1 == null) ? Map.of() : Map.of(trooper, trooperLod1);
    }

    /**
     * @param lod1Suits simpler suits, keyed by the trooper asset they stand in for, attached to the same joints and
     *                 hidden until {@link GpuUnitInstance} shows them for a squad small on screen
     */
    private GpuUnitModel formation(List<InfantryVisual.Part> parts, Map<String, String> lod1Suits,
          UnitFamilyScale familyScale, int triangleLimit) {
        // This Model owns only the assembly tree. NodeParts borrow mesh buffers from the shared asset library.
        Model assembled = new Model();
        List<UnitRig> rigs = new java.util.ArrayList<>();
        Set<Mesh> lod1Meshes = new HashSet<>();
        try {
            int bodyTriangles = 0;
            for (var part : parts) {
                ModularAsset asset = modular(part.asset());
                if (asset == null) {
                    throw new IllegalArgumentException("Missing formation component: " + part.asset());
                }
                if (!"equipment".equals(asset.descriptor().kind())) {
                    bodyTriangles += asset.triangles();
                }
                ModelInstance member = new ModelInstance(asset.model());
                String lod1Suit = lod1Suits.get(part.asset());
                if (asset.model(1) != asset.model()) {
                    attachLod1Parts(new ModelInstance(asset.model(1)).nodes, id -> member.getNode(id, true),
                          "[FormationLod] " + part.id(), lod1Meshes);
                } else if (lod1Suit != null) {
                    attachLod1Suit(member, lod1Suit, part.id(), lod1Meshes);
                }
                Node placement = new Node();
                placement.id = part.id();
                placement.translation.set(part.x(), part.y(), 0);
                placement.rotation.set(Vector3.Z, part.heading() * MathUtils.radiansToDegrees);
                placement.scale.set(part.scale(), part.scale(), part.scale());
                for (Node node : member.nodes) {
                    placement.addChild(node);
                }
                assembled.nodes.add(placement);
                rigs.add(new UnitRig(asset.descriptor()).inside(part.id(), ""));
            }
            if (bodyTriangles > triangleLimit) {
                throw new IllegalArgumentException("Bare formation exceeds " + triangleLimit
                      + " triangles: " + bodyTriangles);
            }
            assembled.calculateTransforms();
            // Troops and transports are authored at canonical size in the Mek standard, like every other body.
            var levels = lod1Meshes.isEmpty() ? GpuUnitModel.DetailLevels.NONE
                  : new GpuUnitModel.DetailLevels(Set.of(), lod1Meshes, FormationLod.LOD1_PIXELS);
            return new GpuUnitModel(assembled, null, true, List.of(), null, rigs, familyScale).detailLevels(levels);
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }

    /**
     * Adds LOD1 parts, switched off, to matching LOD0 joints. Both levels follow the same animated rig.
     */
    private void attachLod1Suit(ModelInstance member, String lod1Suit, String partId, Set<Mesh> lod1Meshes) {
        ModularAsset far = modular(lod1Suit);
        if (far == null) {
            LOGGER.warn("[FormationLod] {}: LOD1 suit {} did not load; this figure keeps LOD0", partId, lod1Suit);
            return;
        }
        attachLod1Parts(new ModelInstance(far.model()).nodes, id -> member.getNode(id, true),
              "[FormationLod] " + partId + ": LOD1 suit " + lod1Suit, lod1Meshes);
    }

    /**
     * Copies every part of a far model, switched off, onto the node of the same name in the full model. Both are built
     * on one rig, so the far parts ride every joint the full model animates and only the drawn detail changes.
     *
     * @param farNodes  the far model's top nodes, already trimmed to the arm forms this unit uses
     * @param fullNodes finds a node of the full model by its name, or gives {@code null} when it has none
     * @param logPrefix the feature tag and model named in a warning
     * @param lod1Meshes collects the far parts' meshes, so the instance can tell them from the full ones
     */
    static void attachLod1Parts(Iterable<Node> farNodes, Function<String, Node> fullNodes, String logPrefix,
          Set<Mesh> lod1Meshes) {
        List<Node> nodes = new java.util.ArrayList<>();
        collectNodes(farNodes, nodes);
        for (Node farNode : nodes) {
            if (farNode.parts.isEmpty()) {
                continue;
            }
            Node joint = fullNodes.apply(farNode.id);
            if (joint == null) {
                LOGGER.warn("{} has node {}, which the full model lacks; its parts are left out", logPrefix, farNode.id);
                continue;
            }
            for (NodePart farPart : farNode.parts) {
                NodePart hidden = farPart.copy();
                hidden.enabled = false;
                joint.parts.add(hidden);
                lod1Meshes.add(hidden.meshPart.mesh);
            }
        }
    }

    private static void collectNodes(Iterable<Node> nodes, List<Node> into) {
        for (Node node : nodes) {
            into.add(node);
            collectNodes(node.getChildren(), into);
        }
    }

    void retainAssemblies(Set<Integer> visibleIds) {
        assemblies.entrySet().removeIf(entry -> {
            if (visibleIds.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().dispose();
            return true;
        });
    }

    static String selectModel(JsonValue descriptor, String variant, int figures) {
        boolean formation = "formation".equals(descriptor.getString("kind", ""));
        String key = formation ? Integer.toString(figures) : variant;
        if (formation) {
            JsonValue movements = descriptor.get("movementFormations");
            JsonValue choices = movements == null ? null : movements.get(variant);
            if (choices != null && choices.has(key)) {
                return choices.getString(key);
            }
        }
        // Older/custom formation descriptors and unsupported movement types retain their base poses.
        JsonValue choices = descriptor.get(formation ? "formations" : "variants");
        return choices != null && choices.has(key) ? choices.getString(key) : descriptor.getString("fallback");
    }

    @Override
    public void dispose() {
        assemblies.values().forEach(Assembly::dispose);
        assemblies.clear();
        modular.values().forEach(asset -> new HashSet<>(asset.levels()).forEach(Model::dispose));
        modular.clear();
        modelTextures.values().forEach(Texture::dispose);
        modelTextures.clear();
        if (bark != null) {
            bark.dispose();
            bark = null;
        }
        models.values().forEach(GpuUnitModel::dispose);
        models.clear();
        descriptors.clear();
        failed.clear();
    }
}
