/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.JsonValue;
import megamek.client.ui.tileset.EquipmentModelPolicy;
import megamek.client.ui.tileset.UnitModelEquipment;

/** Authored turret art uses the same equipment identity, socket fitting and emitters as Meks and vehicles. */
final class BuildingTurretVisual {
    static final String ROOT = "buildings/turrets/modular/";

    private BuildingTurretVisual() { }

    private record Weapon(UnitModelEquipment.Mount mount, GpuUnitModels.ModularAsset module) {
        float area() {
            var bounds = module.descriptor().bounds();
            return (bounds.max().get(0) - bounds.min().get(0)) * (bounds.max().get(2) - bounds.min().get(2));
        }
    }

    static GpuUnitModel assemble(GpuUnitModels library, JsonValue recipe, UnitModelState.Structure structure) {
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
            var bounds = module.descriptor().bounds();
            boolean small = (bounds.max().get(0) - bounds.min().get(0) <= 1.6f)
                  && (bounds.max().get(2) - bounds.min().get(2) <= 1.5f);
            if (small && auxiliary.size() < 2) { auxiliary.add(weapon); } else { main.add(weapon); }
        }
        // One/two small weapons alone still deserve the main carrier, not an empty head above them.
        if (main.isEmpty()) { main.addAll(auxiliary); auxiliary.clear(); }
        main.sort(Comparator.comparingDouble(Weapon::area).reversed().thenComparingInt(w -> w.mount().index()));
        // Installation keys describe logical loadout positions. Keep them stable when
        // an exposed launcher moves from a front bay onto an external roof cradle.
        Map<Integer, JsonValue> settings = new HashMap<>();
        var logical = frontSockets(main.size());
        for (int i = 0; i < main.size(); i++) {
            settings.put(main.get(i).mount().index(), installation(recipe, logical.get(Math.min(i, logical.size() - 1))));
        }
        List<Weapon> pods = new ArrayList<>();
        main.removeIf(weapon -> {
            var setting = settings.get(weapon.mount().index());
            boolean exposed = "missile".equals(weapon.module().descriptor().family())
                  && (setting == null || !setting.getBoolean("encased", false));
            if (exposed) { pods.add(weapon); }
            return exposed;
        });
        if (pods.size() > 4 || (!pods.isEmpty() && main.size() > 3)) {
            throw new IllegalArgumentException("No authored carrier for this external pod battery");
        }
        String form = main.isEmpty() ? "pods" : main.size() == 1 ? "center" : main.size() == 2 ? "twin"
              : pods.isEmpty() ? "shoulder" : "mixed";
        var body = library.modular(ROOT + "head-" + form + ".json");
        if (body == null) { throw new IllegalArgumentException("Missing turret head: " + form); }
        var planned = new JsonValue(JsonValue.ValueType.object);
        planned.addChild("equipment", new JsonValue(recipe.getString("equipment")));
        planned.addChild("body", new JsonValue(ROOT + "head-" + form + ".json"));
        var mounts = new JsonValue(JsonValue.ValueType.array);
        planned.addChild("mounts", mounts);
        List<String> sockets = form.equals("mixed") ? List.of("center", "left", "right") : frontSockets(main.size());
        for (int i = 0; i < main.size(); i++) {
            // Additional custom equipment shares the last pad through the existing stack/area fitter.
            preference(mounts, main.get(i).mount().index(), sockets.get(Math.min(i, sockets.size() - 1)),
                  form.equals("shoulder") || form.equals("mixed") ? "turret-compact" : "turret",
                  settings.get(main.get(i).mount().index()));
        }
        List<String> podSockets = switch (pods.size()) {
            case 1 -> List.of("pod-center");
            case 2 -> List.of("pod-left", "pod-right");
            case 3 -> List.of("pod-wide-left", "pod-center", "pod-wide-right");
            default -> List.of("pod-outer-left", "pod-inner-left", "pod-inner-right", "pod-outer-right");
        };
        for (int i = 0; i < pods.size(); i++) {
            var weapon = pods.get(i);
            if (!catalog.hasProfile(weapon.mount(), "turret-pod")) {
                throw new IllegalArgumentException("No exposed pod profile for " + weapon.mount().internalName());
            }
            preference(mounts, weapon.mount().index(), podSockets.get(i), "turret-pod", settings.get(weapon.mount().index()));
        }
        for (int i = 0; i < auxiliary.size(); i++) {
            String socket = i == 0 ? "aux-left" : "aux-right";
            preference(mounts, auxiliary.get(i).mount().index(), socket, "turret", installation(recipe, socket));
        }
        for (var mount : structure.equipment()) {
            if ("lamp".equals(catalog.family(mount)) || "searchlight".equals(mount.family())) {
                preference(mounts, mount.index(), "external-searchlight", "turret", null);
            }
        }
        if (structure.externalSearchlight()) { preference(mounts, -1, "external-searchlight", "turret", null); }
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
            assembled.calculateTransforms();
            var size = new ModelInstance(assembled).calculateBoundingBox(new com.badlogic.gdx.math.collision.BoundingBox())
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

    private static List<String> frontSockets(int count) {
        return count <= 1 ? List.of("center") : count == 2 ? List.of("left", "right")
              : count % 2 == 1 ? List.of("center", "left-1", "right-1", "left-2", "right-2", "left-0", "right-0")
              : List.of("left-1", "right-1", "left-2", "right-2", "left-0", "right-0", "center");
    }

    private static JsonValue installation(JsonValue recipe, String socket) {
        return recipe.has("installations") ? recipe.get("installations").get(socket) : null;
    }

    private static void preference(JsonValue mounts, int index, String socket, String profile, JsonValue installation) {
        var setting = new JsonValue(JsonValue.ValueType.object);
        setting.addChild("equipmentIndex", new JsonValue(index));
        setting.addChild("hardpoint", new JsonValue(socket));
        setting.addChild("area", new JsonValue(socket));
        setting.addChild("profile", new JsonValue(profile));
        if (installation != null) {
            if (installation.has("insertion")) { setting.addChild("insertion", new JsonValue(installation.getFloat("insertion"))); }
            if (installation.has("encased")) { setting.addChild("encased", new JsonValue(installation.getBoolean("encased"))); }
        }
        mounts.addChild(setting);
    }
}
