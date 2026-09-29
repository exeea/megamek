/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Set;

import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.utils.JsonValue;
import megamek.common.annotations.Nullable;
import megamek.logging.MMLogger;

/** Mek anatomy and size; equipment fitting is shared with the other families. */
final class MekVisual {
    private static final MMLogger LOGGER = MMLogger.create(MekVisual.class);

    private MekVisual() { }

    static GpuUnitModel assemble(GpuUnitModels library, JsonValue descriptor, UnitModelState.Structure structure) {
        if (structure.anatomy() != null && !descriptor.getString("configuration").equals(structure.anatomy().configuration())) {
            throw new IllegalArgumentException("Body topology does not match this Mek's configuration");
        }
        var body = library.modular(descriptor.getString("body"));
        if (body == null || !"body".equals(body.descriptor().kind())) {
            throw new IllegalArgumentException("Missing Mek body");
        }
        Model assembled = new Model();
        try {
            for (Node node : new ModelInstance(body.model()).nodes) {
                filterAnatomy(node, structure.anatomy());
                assembled.nodes.add(node);
            }
            // Before the weapons: detaching an unused hand or gun body must detach all its detail levels.
            var meshes = attachBodyLevels(library, descriptor, body, structure.anatomy(), assembled);
            assembled.calculateTransforms();
            var bindings = UnitEquipmentAssembly.attachAll(library, descriptor, body, structure, assembled);
            for (var binding : bindings) {
                Node placement = assembled.getNode(binding.node(), true);
                if (binding.embedded() || placement == null || !placement.hasChildren()) { continue; }
                var module = library.modular(binding.asset());
                var equipment = GpuUnitModels.attachDetailLevels(List.of(module.model(), module.model(), module.model(2)),
                      id -> assembled.getNode(binding.node() + "-" + id, true), node -> { }, binding.asset());
                for (int level = 0; level < 3; level++) { meshes.get(level).addAll(equipment.get(level)); }
            }
            assembled.calculateTransforms();
            return new GpuUnitModel(assembled, body.descriptor().joints().get("torso"), true, bindings, null,
                  java.util.List.of(new UnitRig(body.descriptor())), UnitFamilyScale.forFamily(body.descriptor().family()))
                  .detailLevels(new GpuUnitModel.DetailLevels(meshes.get(0), meshes.get(1), meshes.get(2),
                        FormationLod.MEK_LOD1_PIXELS, FormationLod.MEK_LOD2_PIXELS));
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }

    /**
     * Adds optional detail parts to the LOD0 joints, preserving legacy separate LOD1 descriptors.
     */
    private static List<Set<Mesh>> attachBodyLevels(GpuUnitModels library, JsonValue descriptor,
          GpuUnitModels.ModularAsset body, @Nullable UnitModelState.MekAnatomy anatomy, Model assembled) {
        List<Model> models = new java.util.ArrayList<>(body.levels());
        if (body.model(1) == body.model()) {
            // Older custom recipes may still refer to a separate component.
            String asset = descriptor.getString("bodyLod1", descriptor.getString("farBody", null));
            if (asset != null) {
                var lod1 = library.modular(asset);
                String problem = lod1BodyProblem(body, lod1);
                if (problem == null) {
                    models.set(1, lod1.model());
                    if (body.model(2) == body.model(1)) { models.set(2, lod1.model()); }
                } else {
                    LOGGER.warn("[MekLod] LOD1 body {} is unusable ({}); retaining LOD0 at this level", asset, problem);
                }
            }
        }
        return GpuUnitModels.attachDetailLevels(models, id -> assembled.getNode(id, true),
              node -> filterAnatomy(node, anatomy), descriptor.getString("body"));
    }

    /** @return why {@code lod1} cannot stand in for {@code lod0}, or {@code null} when it can */
    private static @Nullable String lod1BodyProblem(GpuUnitModels.ModularAsset lod0,
          @Nullable GpuUnitModels.ModularAsset lod1) {
        if (lod1 == null) {
            return "it did not load";
        }
        var descriptor = lod1.descriptor();
        if (!"body".equals(descriptor.kind())) {
            return "it is a " + descriptor.kind() + ", not a body";
        }
        if (descriptor.lod0Detail()) {
            return "it is itself marked as LOD0";
        }
        if (!descriptor.rig().equals(lod0.descriptor().rig())) {
            return "its rig " + descriptor.rig() + " differs from LOD0's " + lod0.descriptor().rig();
        }
        return null;
    }

    private static void filterAnatomy(Node node, UnitModelState.MekAnatomy anatomy) {
        for (int index = node.getChildCount() - 1; index >= 0; index--) {
            Node child = node.getChild(index);
            int separator = child.id.indexOf('@');
            if (separator >= 0) {
                String arm = child.id.substring(0, separator);
                String part = child.id.substring(separator + 1);
                String form = UnitEquipmentAssembly.armForm(anatomy, arm);
                // A gun body can only be held in a hand; whether it or the hand is shown is settled once the
                // equipment is attached and it is known whether this arm holds a gun.
                boolean keep = part.equals(form) || (part.equals("forearm") && !form.equals("elbow"))
                      || (part.equals("held") && form.equals("hand"));
                if (!keep) {
                    node.removeChild(child);
                    continue;
                }
            }
            filterAnatomy(child, anatomy);
        }
    }
}
