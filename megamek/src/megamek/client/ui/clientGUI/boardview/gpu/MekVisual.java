/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashSet;
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
            // Before the weapons: the unused hand or gun body is detached afterwards, and takes its far parts along.
            var levels = attachFarBody(library, descriptor, body, structure.anatomy(), assembled);
            assembled.calculateTransforms();
            var bindings = UnitEquipmentAssembly.attachAll(library, descriptor, body, structure, assembled);
            assembled.calculateTransforms();
            return new GpuUnitModel(assembled, body.descriptor().joints().get("torso"), true, bindings, null,
                  java.util.List.of(new UnitRig(body.descriptor())), UnitFamilyScale.forFamily(body.descriptor().family()))
                  .detailLevels(levels);
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }

    /**
     * Adds the descriptor's far body, switched off, to the near body's nodes. A near body (one over the ordinary
     * budget) is refused without a usable far body, so a detailed Mek is never drawn at its full cost from afar.
     *
     * @return the near and far meshes, or {@link GpuUnitModel.DetailLevels#NONE} for a Mek with one level of detail
     */
    private static GpuUnitModel.DetailLevels attachFarBody(GpuUnitModels library, JsonValue descriptor,
          GpuUnitModels.ModularAsset body, @Nullable UnitModelState.MekAnatomy anatomy, Model assembled) {
        String farAsset = descriptor.getString("farBody", null);
        boolean isNearBody = body.descriptor().nearDetail();
        if (farAsset == null) {
            if (isNearBody) {
                throw new IllegalArgumentException("A near-detail Mek body needs a farBody in its descriptor");
            }
            return GpuUnitModel.DetailLevels.NONE;
        }
        var far = library.modular(farAsset);
        String problem = farBodyProblem(body, far);
        if (problem != null) {
            if (isNearBody) {
                throw new IllegalArgumentException("Unusable far body " + farAsset + ": " + problem);
            }
            LOGGER.warn("[MekLod] far body {} is unusable ({}); this Mek keeps one level of detail", farAsset, problem);
            return GpuUnitModel.DetailLevels.NONE;
        }
        Set<Mesh> farMeshes = new HashSet<>();
        var farNodes = new ModelInstance(far.model()).nodes;
        for (Node node : farNodes) {
            filterAnatomy(node, anatomy);
        }
        GpuUnitModels.attachFarParts(farNodes, id -> assembled.getNode(id, true), "[MekLod] far body " + farAsset,
              farMeshes);
        if (farMeshes.isEmpty()) {
            if (isNearBody) {
                throw new IllegalArgumentException("Far body " + farAsset + " shares no node with the near body");
            }
            LOGGER.warn("[MekLod] far body {} shares no node with the body; this Mek keeps one level of detail",
                  farAsset);
            return GpuUnitModel.DetailLevels.NONE;
        }
        Set<Mesh> nearMeshes = new HashSet<>();
        body.model().meshes.forEach(nearMeshes::add);
        LOGGER.debug("[MekLod] {} carries far body {} ({} near triangles, {} far)", descriptor.getString("body"),
              farAsset, body.triangles(), far.triangles());
        return new GpuUnitModel.DetailLevels(nearMeshes, farMeshes, FormationLod.MEK_FAR_PIXELS);
    }

    /** @return why {@code far} cannot stand in for {@code near}, or {@code null} when it can */
    private static @Nullable String farBodyProblem(GpuUnitModels.ModularAsset near,
          @Nullable GpuUnitModels.ModularAsset far) {
        if (far == null) {
            return "it did not load";
        }
        var farDescriptor = far.descriptor();
        if (!"body".equals(farDescriptor.kind())) {
            return "it is a " + farDescriptor.kind() + ", not a body";
        }
        if (farDescriptor.nearDetail()) {
            return "it is itself a near-detail body";
        }
        if (!farDescriptor.rig().equals(near.descriptor().rig())) {
            return "its rig " + farDescriptor.rig() + " differs from the near body's " + near.descriptor().rig();
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
