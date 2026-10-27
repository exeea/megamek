/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.JsonValue;
import megamek.client.ui.tileset.EquipmentModelPolicy;
import megamek.client.ui.tileset.UnitModelEquipment;

/** Authored turret art uses the same equipment identity, socket fitting and emitters as Meks and vehicles. */
final class BuildingTurretVisual {
    static final String ROOT = "buildings/turrets/modular/";
    /** Visual depression stop of a roof gun on a head with front bays: its barrel stays above theirs. */
    static final float FRONT_BAY_DEPRESSION = 6;
    /**
     * The shared mounts external weapons ride: the side-arm bracket and the roof post reach a trunnion pin to a weapon's
     * side, its tip their "pin"; the roof pedestal carries a weapon resting on its "seat", pitching about its "hinge".
     */
    static final String ARM = ROOT + "mount-arm.json", POST = ROOT + "mount-post.json", PEDESTAL = ROOT + "mount-pedestal.json";
    /**
     * How a roof launcher rides the roof unless the recipe's {@code roofMount} says otherwise: "posts", a fork of two posts
     * holding its sides; "post", one post on its inboard side; or "pedestal", a pedestal under it.
     */
    static final String ROOF_MOUNT = "posts";
    /** What a gun leaves out: in a bay its receiver, inside the head; on the roof its bay mantlet. */
    private static final List<String> BAY_OMITS = List.of("receiver"), ROOF_OMITS = List.of("mantlet");

    private BuildingTurretVisual() { }

    private record Weapon(UnitModelEquipment.Mount mount, GpuUnitModels.ModularAsset module) {
        /** Its size as a front bay shows it: a gun's receiver stays inside the head. */
        Vector3 baySize() {
            return UnitEquipmentAssembly.restBounds(module, omit(BAY_OMITS)).getDimensions(new Vector3());
        }

        float area() {
            var size = baySize();
            return size.x * size.z;
        }

        boolean launcher() {
            return "missile".equals(module.descriptor().family());
        }
    }

    /** An exposed weapon the carrier cannot hold goes into a front bay; launchers then sit 75% encased. */
    private static void toBay(List<Weapon> main, Map<Integer, JsonValue> settings, Weapon weapon, boolean encased) {
        main.add(weapon);
        var setting = new JsonValue(JsonValue.ValueType.object);
        if (encased) { setting.addChild("encased", new JsonValue(true)); }
        settings.put(weapon.mount().index(), setting);
    }

    /**
     * A roof turret or wall casemate of one building hex, its nodes renamed with {@code prefix} in the merged model; a
     * casemate is number {@code slot} of the {@code slots} side by side on its wall.
     */
    private record Piece(String prefix, GpuUnitModel model, UnitModelState.Wall wall, int slot, int slots) { }

    /**
     * The turret of a gun emplacement or building hex; a building hex with wall weapons adds casemates of up to two
     * weapons on each wall face and floor that holds weapons, and the roof turret only for its turret-mounted weapons.
     */
    static GpuUnitModel assemble(GpuUnitModels library, JsonValue recipe, UnitModelState.Structure structure) {
        var building = structure.building();
        if (building == null || building.walls().isEmpty()) { return head(library, recipe, structure); }
        List<UnitModelEquipment.Mount> roof = new ArrayList<>();
        Map<UnitModelState.Wall, List<UnitModelEquipment.Mount>> walls = new LinkedHashMap<>();
        for (var mount : structure.equipment()) {
            var wall = building.walls().get(mount.index());
            if (wall == null) { roof.add(mount); }
            else { walls.computeIfAbsent(wall, ignored -> new ArrayList<>()).add(mount); }
        }
        List<Piece> pieces = new ArrayList<>();
        try {
            if (roof.stream().anyMatch(mount -> mount.policy() == EquipmentModelPolicy.WEAPON)) {
                pieces.add(new Piece("roof", head(library, recipe, structure.withEquipment(roof)), null, 0, 1));
            }
            for (var entry : walls.entrySet()) {
                var wall = entry.getKey();
                // Alike weapons pair up in one casemate; a wall with more than two holds several side by side.
                var weapons = entry.getValue().stream().filter(mount -> mount.policy() == EquipmentModelPolicy.WEAPON)
                      .sorted(Comparator.comparing(UnitModelEquipment.Mount::internalName)).toList();
                int slots = (weapons.size() + 1) / 2;
                for (int slot = 0; slot < slots; slot++) {
                    var pair = weapons.subList(2 * slot, Math.min(weapons.size(), 2 * slot + 2));
                    pieces.add(new Piece("wall-" + wall.face() + "-" + wall.floor() + "-" + slot,
                          casemate(library, recipe, structure.withEquipment(pair)), wall, slot, slots));
                }
            }
            return merge(pieces, building.height());
        } catch (RuntimeException error) {
            pieces.forEach(piece -> piece.model().dispose());
            throw error;
        }
    }

    /** A wall casemate: one gun, or a pair side by side, in a gun shield that yaws within the weapons' arc. */
    private static GpuUnitModel casemate(GpuUnitModels library, JsonValue recipe, UnitModelState.Structure structure) {
        var catalog = new UnitEquipmentModels(library.descriptor(recipe.getString("equipment")));
        var profile = new JsonValue(JsonValue.ValueType.object);
        profile.addChild("profile", new JsonValue("turret"));
        var weapons = structure.equipment().stream().filter(mount -> mount.policy() == EquipmentModelPolicy.WEAPON).toList();
        String path = ROOT + (weapons.size() == 1 ? "head-casemate.json" : "head-casemate-twin.json");
        var body = library.modular(path);
        if (body == null) { throw new IllegalArgumentException("Missing wall casemate: " + path); }
        var planned = new JsonValue(JsonValue.ValueType.object);
        planned.addChild("equipment", new JsonValue(recipe.getString("equipment")));
        planned.addChild("body", new JsonValue(path));
        var mounts = new JsonValue(JsonValue.ValueType.array);
        planned.addChild("mounts", mounts);
        var sockets = weapons.size() == 1 ? List.of("center") : List.of("left", "right");
        for (int i = 0; i < weapons.size(); i++) {
            var mount = weapons.get(i);
            if (!catalog.hasProfile(mount, "turret")) {
                throw new IllegalArgumentException("No authored turret profile for " + mount.internalName());
            }
            var visual = catalog.resolve(mount, profile);
            var module = visual == null ? null : library.modular(visual.asset());
            if (module == null) { throw new IllegalArgumentException("Missing turret weapon: " + mount.internalName()); }
            // A launcher sits 75% encased in the shield, as in a front bay.
            var setting = new JsonValue(JsonValue.ValueType.object);
            if ("missile".equals(module.descriptor().family())) { setting.addChild("encased", new JsonValue(true)); }
            bay(preference(mounts, mount.index(), sockets.get(i), "turret", setting, 0), module);
        }
        return build(library, planned, body, structure);
    }

    /** The roof turret: its head, front bays, roof mounts and launcher arms for the given weapons. */
    private static GpuUnitModel head(GpuUnitModels library, JsonValue recipe, UnitModelState.Structure structure) {
        var catalog = new UnitEquipmentModels(library.descriptor(recipe.getString("equipment")));
        var profile = new JsonValue(JsonValue.ValueType.object);
        profile.addChild("profile", new JsonValue("turret"));
        List<Weapon> main = new ArrayList<>(), auxiliary = new ArrayList<>();
        for (var mount : structure.equipment()) {
            if (mount.policy() != EquipmentModelPolicy.WEAPON) { continue; }
            if (!catalog.hasProfile(mount, "turret")) {
                throw new IllegalArgumentException("No authored turret profile for " + mount.internalName());
            }
            var visual = catalog.resolve(mount, profile);
            var module = visual == null ? null : library.modular(visual.asset());
            if (module == null) { throw new IllegalArgumentException("Missing turret weapon: " + mount.internalName()); }
            var weapon = new Weapon(mount, module);
            var size = weapon.baySize();
            boolean small = size.x <= 1.6f && size.z <= 1.5f;
            if (small && auxiliary.size() < 2) { auxiliary.add(weapon); } else { main.add(weapon); }
        }
        // One/two small weapons alone still deserve the main carrier, not an empty head above them.
        if (main.isEmpty()) { main.addAll(auxiliary); auxiliary.clear(); }
        main.sort(Comparator.comparingDouble(Weapon::area).reversed().thenComparingInt(w -> w.mount().index()));
        // Installation keys describe logical loadout positions. Keep them stable when
        // an exposed weapon moves from a front bay onto a roof mount.
        Map<Integer, JsonValue> settings = new HashMap<>();
        var logical = frontSockets(main.size());
        for (int i = 0; i < main.size(); i++) {
            settings.put(main.get(i).mount().index(), installation(recipe, logical.get(Math.min(i, logical.size() - 1))));
        }
        for (int i = 0; i < auxiliary.size(); i++) {
            settings.put(auxiliary.get(i).mount().index(), installation(recipe, i == 0 ? "aux-left" : "aux-right"));
        }
        // Exposed weapons leave the front bays: guns ride roof mounts, launchers side arms or roof mounts.
        List<Weapon> launchers = new ArrayList<>(), roofGuns = new ArrayList<>();
        java.util.function.Predicate<Weapon> external = weapon -> {
            var setting = settings.get(weapon.mount().index());
            boolean exposed = !(setting == null ? !weapon.launcher() : setting.getBoolean("encased", !weapon.launcher()));
            if (exposed) { (weapon.launcher() ? launchers : roofGuns).add(weapon); }
            return exposed;
        };
        main.removeIf(external);
        auxiliary.removeIf(external);
        // Any mix assembles: what a carrier cannot hold moves into the armored front bays instead.
        while (roofGuns.size() > 2) { toBay(main, settings, roofGuns.removeLast(), false); }
        launchers.sort(Comparator.comparingDouble(Weapon::area).reversed().thenComparingInt(w -> w.mount().index()));
        // Missile-led loadouts use the thin pillar with stacked side arms; a broad head has one arm per flank.
        boolean pillar = launchers.size() >= 2 && main.size() <= 1 && roofGuns.size() <= 1;
        int armed = pillar ? Math.min(launchers.size(), 4)
              : main.isEmpty() && roofGuns.isEmpty() ? 0 : Math.min(launchers.size(), 2);
        int yokes = roofGuns.isEmpty() ? Math.min(launchers.size() - armed, pillar ? 1 : 4) : 0;
        List<Weapon> roof = new ArrayList<>(roofGuns);
        roof.addAll(launchers.subList(armed, armed + yokes));
        for (var launcher : launchers.subList(armed + yokes, launchers.size())) { toBay(main, settings, launcher, true); }
        String form = pillar ? "pillar" : main.isEmpty() ? "pods" : main.size() == 1 ? "center" : main.size() == 2 ? "twin"
              : roof.isEmpty() ? "shoulder" : "mixed";
        var body = library.modular(ROOT + "head-" + form + ".json");
        if (body == null) { throw new IllegalArgumentException("Missing turret head: " + form); }
        var planned = new JsonValue(JsonValue.ValueType.object);
        planned.addChild("equipment", new JsonValue(recipe.getString("equipment")));
        planned.addChild("body", new JsonValue(ROOT + "head-" + form + ".json"));
        var mounts = new JsonValue(JsonValue.ValueType.array);
        planned.addChild("mounts", mounts);
        List<String> sockets = form.equals("pillar") ? List.of("center")
              : form.equals("mixed") ? List.of("center", "left", "right") : frontSockets(main.size());
        String bayProfile = form.equals("shoulder") || form.equals("mixed") ? "turret-compact" : "turret";
        for (int i = 0; i < main.size(); i++) {
            // Additional custom equipment shares the last pad through the existing stack/area fitter.
            var mount = main.get(i).mount();
            bay(preference(mounts, mount.index(), sockets.get(Math.min(i, sockets.size() - 1)), bayProfile,
                  settings.get(mount.index()), 0), installed(library, catalog, mount, bayProfile));
        }
        List<String> roofSockets = pillar ? List.of("pod-center") : switch (roof.size()) {
            case 1 -> List.of("pod-center");
            case 2 -> List.of("pod-left", "pod-right");
            case 3 -> List.of("pod-wide-left", "pod-center", "pod-wide-right");
            default -> List.of("pod-outer-left", "pod-inner-left", "pod-inner-right", "pod-outer-right");
        };
        String roofMount = recipe.getString("roofMount", ROOF_MOUNT);
        for (int i = 0; i < roof.size(); i++) {
            var weapon = roof.get(i);
            if (!catalog.hasProfile(weapon.mount(), "turret-exposed")) {
                throw new IllegalArgumentException("No exposed weapon profile for " + weapon.mount().internalName());
            }
            // A roof gun above front bays stops depressing before its barrel reaches theirs.
            var setting = preference(mounts, weapon.mount().index(), roofSockets.get(i), "turret-exposed",
                  settings.get(weapon.mount().index()), !weapon.launcher() && !main.isEmpty() ? FRONT_BAY_DEPRESSION : 0);
            roofMount(library, setting, weapon.module(), roofMount, socketX(body, roofSockets.get(i)));
        }
        List<String> armSockets = switch (armed) {
            case 0 -> List.of();
            case 1 -> List.of("arm-right");
            case 2 -> List.of("arm-left", "arm-right");
            case 3 -> List.of("arm-left", "arm-right-0", "arm-right-1");
            default -> List.of("arm-left-0", "arm-right-0", "arm-left-1", "arm-right-1");
        };
        var pin = mountPoint(library, ARM, "pin");
        for (int i = 0; i < armed; i++) {
            var weapon = launchers.get(i);
            if (!catalog.hasProfile(weapon.mount(), "turret-arm")) {
                throw new IllegalArgumentException("No side-arm launcher profile for " + weapon.mount().internalName());
            }
            // One bracket and one launcher serve either side: the left bracket is the right one rolled half a turn, and
            // the launcher hangs outboard of it, its trunnion boss against the pin's tip.
            float trunnion = point(weapon.module(), "trunnion").x;
            boolean left = armSockets.get(i).startsWith("arm-left");
            var setting = preference(mounts, weapon.mount().index(), armSockets.get(i), "turret-arm", null, 0);
            setting.addChild("offsetX", new JsonValue(left ? -pin.x - trunnion : pin.x + trunnion));
            fixture(setting, ARM, 0, 0, 0, left ? 180 : 0);
        }
        for (int i = 0; i < auxiliary.size(); i++) {
            String socket = i == 0 ? "aux-left" : "aux-right";
            bay(preference(mounts, auxiliary.get(i).mount().index(), socket, "turret",
                  settings.get(auxiliary.get(i).mount().index()), 0), auxiliary.get(i).module());
        }
        for (var mount : structure.equipment()) {
            if ("lamp".equals(catalog.family(mount)) || "searchlight".equals(mount.family())) {
                preference(mounts, mount.index(), "external-searchlight", "turret", null, 0);
            }
        }
        if (structure.externalSearchlight()) { preference(mounts, -1, "external-searchlight", "turret", null, 0); }
        return build(library, planned, body, structure);
    }

    /** Attaches the planned equipment to the body; a weapon the body cannot fit throws, for the unit fallback. */
    private static GpuUnitModel build(GpuUnitModels library, JsonValue planned, GpuUnitModels.ModularAsset body,
          UnitModelState.Structure structure) {
        Model assembled = new Model();
        try {
            for (Node node : new ModelInstance(body.model()).nodes) { assembled.nodes.add(node); }
            var meshes = GpuUnitModels.attachDetailLevels(body.levels(), id -> assembled.getNode(id, true),
                  node -> { }, planned.getString("body"));
            assembled.calculateTransforms();
            var bindings = UnitEquipmentAssembly.attachAll(library, planned, body, structure, assembled);
            for (var binding : bindings) {
                if (binding.embedded()) {
                    throw new IllegalArgumentException("Turret carrier cannot fit " + binding.index()
                          + "; use the existing unit fallback rather than hide its weapon");
                }
                var module = library.modular(binding.asset());
                var levels = GpuUnitModels.attachDetailLevels(module.levels(),
                      id -> assembled.getNode(binding.node() + "-" + id, true), node -> { }, binding.asset());
                for (int level = 0; level < 3; level++) { meshes.get(level).addAll(levels.get(level)); }
            }
            // The shared mounts the external weapons ride, beside or under them; a weapon on a pedestal pitches about
            // the hinge pin under it rather than its own trunnion.
            for (var mount : planned.get("mounts")) {
                if (mount.has("fixtures")) { attachFixtures(library, body, assembled, mount, meshes); }
            }
            bindings = bindings.stream().map(binding -> pivoted(assembled, planned, binding)).toList();
            for (var mount : planned.get("mounts")) {
                if (mount.has("omit")) { leaveOut(assembled, bindings, mount); }
            }
            assembled.calculateTransforms();
            var size = new ModelInstance(assembled).calculateBoundingBox(new BoundingBox())
                  .getDimensions(new Vector3());
            return new GpuUnitModel(assembled, null, true, bindings, size,
                  List.of(new UnitRig(body.descriptor())), UnitFamilyScale.STATIC)
                  .detailLevels(new GpuUnitModel.DetailLevels(meshes.get(0), meshes.get(1), meshes.get(2),
                        FormationLod.MEK_LOD1_PIXELS, FormationLod.MEK_LOD2_PIXELS));
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }

    /**
     * One building hex's roof turret and wall casemates as one model, as squadrons merge their fighters. The casemates
     * are placed on their walls in world units each time the model is placed ({@link GpuUnitModel#walls}).
     */
    private static GpuUnitModel merge(List<Piece> pieces, int height) {
        Model assembled = new Model();
        List<UnitEquipmentAssembly.Binding> bindings = new ArrayList<>();
        List<UnitRig> rigs = new ArrayList<>();
        List<Set<Mesh>> meshes = List.of(new HashSet<>(), new HashSet<>(), new HashSet<>());
        List<GpuUnitModel.WallAnchor> walls = new ArrayList<>();
        // Rest proportions come from the roof turret, or the first casemate: wall placement never changes them.
        var size = pieces.getFirst().model().instance.calculateBoundingBox(new BoundingBox())
              .getDimensions(new Vector3());
        for (var piece : pieces) {
            var model = piece.model();
            if (piece.wall() != null) {
                walls.add(new GpuUnitModel.WallAnchor(piece.prefix(), piece.wall().face(), piece.wall().floor(), height,
                      piece.slot(), piece.slots(), model.instance.calculateBoundingBox(new BoundingBox())));
            }
            assembled.nodes.add(UnitEquipmentAssembly.nest(model, piece.prefix(), -1, rigs, bindings));
            var levels = model.detailLevels();
            meshes.get(0).addAll(levels.lod0());
            meshes.get(1).addAll(levels.lod1());
            meshes.get(2).addAll(levels.lod2());
            model.dispose();
        }
        assembled.calculateTransforms();
        return new GpuUnitModel(assembled, null, true, bindings, size, rigs, UnitFamilyScale.STATIC)
              .detailLevels(new GpuUnitModel.DetailLevels(meshes.get(0), meshes.get(1), meshes.get(2),
                    FormationLod.MEK_LOD1_PIXELS, FormationLod.MEK_LOD2_PIXELS))
              .walls(walls);
    }

    /**
     * A roof weapon's shared mount, on its planned mount: a fork of two posts holding its trunnion bosses, one post on
     * its inboard side, or a pedestal under it whose hinge it then pitches about ({@link #pivoted}). A gun leaves its
     * front-bay mantlet out.
     */
    private static void roofMount(GpuUnitModels library, JsonValue setting, GpuUnitModels.ModularAsset weapon, String style,
          float socketX) {
        setting.addChild("omit", omit(ROOF_OMITS));
        var bounds = UnitEquipmentAssembly.restBounds(weapon, setting.get("omit"));
        // A roof socket marks the rear of its weapon; the weapon is centred on its trunnion.
        float y = -bounds.min.y;
        setting.addChild("offsetY", new JsonValue(y));
        if ("pedestal".equals(style)) {
            float height = mountPoint(library, PEDESTAL, "seat").z - bounds.min.z;
            setting.addChild("offsetZ", new JsonValue(height));
            setting.addChild("pivotZ", new JsonValue(mountPoint(library, PEDESTAL, "hinge").z - height));
            fixture(setting, PEDESTAL, 0, y, 0, 0);
            return;
        }
        // A post's pin reaches the weapon's trunnion boss; the left post is the right one turned half a turn about Z.
        var pin = mountPoint(library, POST, "pin");
        float trunnion = point(weapon, "trunnion").x;
        setting.addChild("offsetZ", new JsonValue(pin.z));
        boolean single = "post".equals(style);
        if (!single || socketX < 0) { fixture(setting, POST, trunnion - pin.x, y, 0, 0); }
        if (!single || socketX >= 0) { fixture(setting, POST, pin.x - trunnion, y, 180, 0); }
    }

    /**
     * A gun in a front bay, on a pad or in a wall casemate: its barrel leaves the face at the socket, and its receiver,
     * inside, is left out. A launcher sits encased by its own rule.
     */
    private static void bay(JsonValue setting, GpuUnitModels.ModularAsset weapon) {
        if ("missile".equals(weapon.descriptor().family())) { return; }
        setting.addChild("offsetY", new JsonValue(-point(weapon, "face").y));
        setting.addChild("omit", omit(BAY_OMITS));
    }

    /** The module a weapon takes for an installation profile. */
    private static GpuUnitModels.ModularAsset installed(GpuUnitModels library, UnitEquipmentModels catalog,
          UnitModelEquipment.Mount mount, String profile) {
        var placement = new JsonValue(JsonValue.ValueType.object);
        placement.addChild("profile", new JsonValue(profile));
        var visual = catalog.resolve(mount, placement);
        var module = visual == null ? null : library.modular(visual.asset());
        if (module == null) { throw new IllegalArgumentException("Missing turret weapon: " + mount.internalName()); }
        return module;
    }

    /** A planned mount's list of module nodes to leave out. */
    private static JsonValue omit(List<String> nodes) {
        var list = new JsonValue(JsonValue.ValueType.array);
        nodes.forEach(node -> list.addChild(new JsonValue(node)));
        return list;
    }

    /** A point of a weapon module, in its own model units: its "trunnion" boss tip, or the "face" its barrel leaves. */
    private static Vector3 point(GpuUnitModels.ModularAsset module, String id) {
        var point = module.descriptor().hardpoints().stream().filter(hardpoint -> hardpoint.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException(module.descriptor().mesh() + " has no " + id)).position();
        return new Vector3(point.get(0), point.get(1), point.get(2));
    }

    /**
     * Adds a shared mount on a planned mount at ({@code x}, {@code y}) on its socket, turned {@code yaw} about Z and
     * {@code roll} about Y.
     */
    private static void fixture(JsonValue setting, String asset, float x, float y, float yaw, float roll) {
        if (!setting.has("fixtures")) { setting.addChild("fixtures", new JsonValue(JsonValue.ValueType.array)); }
        var fixture = new JsonValue(JsonValue.ValueType.object);
        fixture.addChild("asset", new JsonValue(asset));
        fixture.addChild("x", new JsonValue(x));
        fixture.addChild("y", new JsonValue(y));
        fixture.addChild("yaw", new JsonValue(yaw));
        fixture.addChild("roll", new JsonValue(roll));
        setting.get("fixtures").addChild(fixture);
    }

    /** Where a shared mount holds its weapon, in its own model units. */
    private static Vector3 mountPoint(GpuUnitModels library, String mount, String id) {
        var asset = library.modular(mount);
        if (asset == null) { throw new IllegalArgumentException("Missing weapon mount: " + mount); }
        var point = asset.descriptor().hardpoints().stream().filter(hardpoint -> hardpoint.id().equals(id)).findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Weapon mount " + mount + " has no " + id)).position();
        return new Vector3(point.get(0), point.get(1), point.get(2));
    }

    private static float socketX(GpuUnitModels.ModularAsset body, String socket) {
        return body.descriptor().hardpoints().stream().filter(point -> point.id().equals(socket)).findFirst().orElseThrow()
              .position().get(0);
    }

    /** Places a planned mount's shared mounts at its socket, as parts of the turret that never move with the weapon. */
    private static void attachFixtures(GpuUnitModels library, GpuUnitModels.ModularAsset body, Model assembled, JsonValue mount,
          List<Set<Mesh>> meshes) {
        var point = body.descriptor().hardpoints().stream().filter(hardpoint -> hardpoint.id().equals(mount.getString("hardpoint")))
              .findFirst().orElseThrow();
        Node parent = assembled.getNode(point.node(), true);
        int index = 0;
        for (var fixture : mount.get("fixtures")) {
            String asset = fixture.getString("asset"), id = point.id() + "-mount-" + index++;
            var shape = library.modular(asset);
            if (shape == null) { throw new IllegalArgumentException("Missing weapon mount: " + asset); }
            var transform = point.transform().translate(fixture.getFloat("x"), fixture.getFloat("y"), 0)
                  .rotate(Vector3.Z, fixture.getFloat("yaw")).rotate(Vector3.Y, fixture.getFloat("roll"));
            Node placement = new Node();
            placement.id = id;
            transform.getTranslation(placement.translation);
            transform.getRotation(placement.rotation, true);
            for (Node node : new ModelInstance(shape.model()).nodes) {
                UnitEquipmentAssembly.rename(node, id);
                placement.addChild(node);
            }
            parent.addChild(placement);
            var levels = GpuUnitModels.attachDetailLevels(shape.levels(), lod -> assembled.getNode(id + "-" + lod, true),
                  node -> { }, asset);
            for (int level = 0; level < 3; level++) { meshes.get(level).addAll(levels.get(level)); }
        }
    }

    /** Removes the module parts a planned mount's installation leaves out ({@link UnitEquipmentAssembly#restBounds}). */
    private static void leaveOut(Model assembled, List<UnitEquipmentAssembly.Binding> bindings, JsonValue mount) {
        for (var binding : bindings) {
            if (binding.index() != mount.getInt("equipmentIndex")) { continue; }
            for (String id : mount.get("omit").asStringArray()) {
                Node node = assembled.getNode(binding.node() + "-" + id, true);
                if (node != null) { node.detach(); }
            }
        }
    }

    /** A weapon on a pedestal pitches about the hinge pin under it: a hinge joint carries its trunnion joint. */
    private static UnitEquipmentAssembly.Binding pivoted(Model assembled, JsonValue planned, UnitEquipmentAssembly.Binding binding) {
        for (var mount : planned.get("mounts")) {
            if (mount.getInt("equipmentIndex") != binding.index() || !mount.has("pivotZ") || binding.aimNode() == null) {
                continue;
            }
            Node aim = assembled.getNode(binding.aimNode(), true);
            Node hinge = new Node();
            hinge.id = binding.node() + "-hinge";
            float below = mount.getFloat("pivotZ");
            hinge.translation.set(aim.translation).add(0, 0, below);
            Node parent = aim.getParent();
            aim.detach();
            parent.addChild(hinge);
            hinge.addChild(aim);
            aim.translation.set(0, 0, -below);
            return new UnitEquipmentAssembly.Binding(binding.index(), binding.location(), binding.node(), binding.asset(),
                  binding.embedded(), binding.emitters(), binding.memberId(), hinge.id, binding.recoilNode(),
                  binding.pitchOnly(), binding.depression(), binding.spinNode());
        }
        return binding;
    }

    private static List<String> frontSockets(int count) {
        return count <= 1 ? List.of("center") : count == 2 ? List.of("left", "right")
              : count % 2 == 1 ? List.of("center", "left-1", "right-1", "left-2", "right-2", "left-0", "right-0")
              : List.of("left-1", "right-1", "left-2", "right-2", "left-0", "right-0", "center");
    }

    private static JsonValue installation(JsonValue recipe, String socket) {
        return recipe.has("installations") ? recipe.get("installations").get(socket) : null;
    }

    /**
     * @param depression a pitch-only mount's visual depression stop in degrees; 0 keeps the weapon's own stop
     * @return the planned mount, for any further setting
     */
    private static JsonValue preference(JsonValue mounts, int index, String socket, String profile, JsonValue installation,
          float depression) {
        var setting = new JsonValue(JsonValue.ValueType.object);
        setting.addChild("equipmentIndex", new JsonValue(index));
        setting.addChild("hardpoint", new JsonValue(socket));
        setting.addChild("area", new JsonValue(socket));
        setting.addChild("profile", new JsonValue(profile));
        if (depression > 0) { setting.addChild("depression", new JsonValue(depression)); }
        if (installation != null) {
            if (installation.has("insertion")) { setting.addChild("insertion", new JsonValue(installation.getFloat("insertion"))); }
            if (installation.has("encased")) { setting.addChild("encased", new JsonValue(installation.getBoolean("encased"))); }
        }
        mounts.addChild(setting);
        return setting;
    }
}
