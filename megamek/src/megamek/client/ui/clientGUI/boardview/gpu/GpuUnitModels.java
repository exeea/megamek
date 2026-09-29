/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.graphics.g3d.model.NodePart;
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
                // The descriptor only accepts GLB meshes, whose levels are named groups.
                var data = RigidGlb.loadLods(file, root);
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
        JsonValue value;
        try {
            value = descriptor(asset);
            // Only modular (schema 2) descriptors exist; the older pre-built variant format is no longer read.
            if (value.getInt("schema", 0) != 2) {
                throw new IllegalArgumentException("Unsupported unit-model schema " + value.getInt("schema", 0));
            }
        } catch (RuntimeException error) {
            failed.add(descriptor);
            LOGGER.warn("Cannot load 3D unit asset {}; using its fallback: {}", descriptor, error.getMessage());
            return null;
        }
        if (selection.state() == null) {
            return null;
        }
        try {
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
            return formation(parts, lod1Suits(value), UnitFamilyScale.forFamily(value.getString("family")));
        } catch (RuntimeException error) {
            // A bad custom loadout must not poison a shared descriptor for every other unit.
            LOGGER.warn("Cannot load 3D unit asset {}; using its fallback: {}", descriptor, error.getMessage());
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
          UnitFamilyScale familyScale) {
        // This Model owns only the assembly tree. NodeParts borrow mesh buffers from the shared asset library.
        Model assembled = new Model();
        List<UnitRig> rigs = new java.util.ArrayList<>();
        List<Set<Mesh>> meshes = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
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
                List<Model> models = new java.util.ArrayList<>(asset.levels());
                if (models.get(1) == models.getFirst() && lod1Suit != null) {
                    var far = modular(lod1Suit);
                    if (far != null) {
                        models.set(1, far.model());
                        if (asset.model(2) == asset.model(1)) { models.set(2, far.model()); }
                    }
                }
                var memberLevels = attachDetailLevels(models, id -> member.getNode(id, true), node -> { }, part.asset());
                for (int level = 0; level < 3; level++) { meshes.get(level).addAll(memberLevels.get(level)); }
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
            if (bodyTriangles > UnitModelDescriptor.MAX_TRIANGLES) {
                throw new IllegalArgumentException("Bare formation exceeds the " + UnitModelDescriptor.MAX_TRIANGLES
                      + " triangle ceiling: " + bodyTriangles);
            }
            assembled.calculateTransforms();
            // Troops and transports are authored at canonical size in the Mek standard, like every other body.
            var levels = new GpuUnitModel.DetailLevels(meshes.get(0), meshes.get(1), meshes.get(2),
                  FormationLod.LOD1_PIXELS, FormationLod.LOD2_PIXELS);
            return new GpuUnitModel(assembled, null, true, List.of(), null, rigs, familyScale).detailLevels(levels);
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }

    /** Attach each distinct mesh once to the original rig, retaining the loader's per-component fallback. */
    static List<Set<Mesh>> attachDetailLevels(List<Model> models, Function<String, Node> fullNodes,
          Consumer<Node> prepare, String asset) {
        List<Set<Mesh>> result = new java.util.ArrayList<>();
        for (int level = 0; level < models.size(); level++) {
            Model model = models.get(level);
            int previous = models.indexOf(model);
            if (previous < level) {
                result.add(new HashSet<>(result.get(previous)));
                continue;
            }
            Set<Mesh> meshes = new HashSet<>();
            if (level == 0) {
                model.meshes.forEach(meshes::add);
            } else {
                var nodes = new ModelInstance(model).nodes;
                nodes.forEach(prepare);
                attachDetailParts(nodes, fullNodes, "[UnitLod] " + asset + " LOD" + level, meshes);
                if (meshes.isEmpty()) { meshes.addAll(result.get(level - 1)); }
            }
            result.add(meshes);
        }
        return result;
    }

    /**
     * Copies every part of a far model, switched off, onto the node of the same name in the full model. Both are built
     * on one rig, so the far parts ride every joint the full model animates and only the drawn detail changes.
     *
     * @param farNodes  the far model's top nodes, already trimmed to the arm forms this unit uses
     * @param fullNodes finds a node of the full model by its name, or gives {@code null} when it has none
     * @param logPrefix the feature tag and model named in a warning
     * @param meshes collects the far parts' meshes, so the instance can tell them from the full ones
     */
    private static void attachDetailParts(Iterable<Node> farNodes, Function<String, Node> fullNodes, String logPrefix,
          Set<Mesh> meshes) {
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
                meshes.add(hidden.meshPart.mesh);
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
        descriptors.clear();
        failed.clear();
    }
}
