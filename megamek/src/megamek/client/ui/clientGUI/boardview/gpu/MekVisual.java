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
            // Before the weapons: detaching an unused hand or gun body must also detach its LOD1 parts.
            var levels = attachLod1Body(library, descriptor, body, structure.anatomy(), assembled);
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
     * Adds optional LOD1 parts, switched off, to the LOD0 joints. Missing LOD1 keeps the LOD0 body at every distance.
     *
     * @return both levels, or {@link GpuUnitModel.DetailLevels#NONE} when LOD0 is the only usable body
     */
    private static GpuUnitModel.DetailLevels attachLod1Body(GpuUnitModels library, JsonValue descriptor,
          GpuUnitModels.ModularAsset body, @Nullable UnitModelState.MekAnatomy anatomy, Model assembled) {
        String lod1Asset = descriptor.getString("body");
        Model lod1Model = body.model(1);
        int lod1Triangles = body.triangles(1);
        if (lod1Model == body.model()) {
            // Older custom recipes may still refer to a separate component.
            lod1Asset = descriptor.getString("bodyLod1", descriptor.getString("farBody", null));
            if (lod1Asset == null) { return GpuUnitModel.DetailLevels.NONE; }
            var lod1 = library.modular(lod1Asset);
            String problem = lod1BodyProblem(body, lod1);
            if (problem != null) {
                LOGGER.warn("[MekLod] LOD1 body {} is unusable ({}); this Mek keeps LOD0", lod1Asset, problem);
                return GpuUnitModel.DetailLevels.NONE;
            }
            lod1Model = lod1.model();
            lod1Triangles = lod1.triangles();
        }
        Set<Mesh> lod1Meshes = new HashSet<>();
        var lod1Nodes = new ModelInstance(lod1Model).nodes;
        for (Node node : lod1Nodes) {
            filterAnatomy(node, anatomy);
        }
        GpuUnitModels.attachLod1Parts(lod1Nodes, id -> assembled.getNode(id, true), "[MekLod] LOD1 body " + lod1Asset,
              lod1Meshes);
        if (lod1Meshes.isEmpty()) {
            LOGGER.warn("[MekLod] LOD1 body {} shares no node with LOD0; this Mek keeps LOD0", lod1Asset);
            return GpuUnitModel.DetailLevels.NONE;
        }
        Set<Mesh> lod0Meshes = new HashSet<>();
        body.model().meshes.forEach(lod0Meshes::add);
        LOGGER.debug("[MekLod] {} carries LOD1 body {} ({} LOD0 triangles, {} LOD1)", descriptor.getString("body"),
              lod1Asset, body.triangles(), lod1Triangles);
        return new GpuUnitModel.DetailLevels(lod0Meshes, lod1Meshes, FormationLod.MEK_LOD1_PIXELS);
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
