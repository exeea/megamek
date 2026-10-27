/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.model.Node;
import com.badlogic.gdx.utils.JsonValue;

/** A small shared fighter body per actual squadron member, each with its own live equipment bindings. */
final class SquadronVisual {
    private SquadronVisual() { }

    static GpuUnitModel assemble(GpuUnitModels library, JsonValue descriptor, UnitModelState.Structure structure) {
        var members = structure.bodyForm().fighters();
        var body = library.modular(descriptor.getString("body"));
        if (body == null || body.triangles() * members.size() > UnitModelDescriptor.MAX_TRIANGLES) {
            throw new IllegalArgumentException("Missing or over-budget squadron body");
        }
        Model assembled = new Model();
        List<UnitEquipmentAssembly.Binding> bindings = new ArrayList<>();
        List<UnitRig> rigs = new ArrayList<>();
        try {
            int columns = Math.min(3, Math.max(1, members.size()));
            int rows = (members.size() + columns - 1) / columns;
            for (int index = 0; index < members.size(); index++) {
                var member = members.get(index);
                var model = FamilyVisual.assemble(library, descriptor, member.structure());
                try {
                    Node placement = UnitEquipmentAssembly.nest(model, "fighter-" + member.id(), member.id(), rigs, bindings);
                    placement.translation.set((index % columns - (columns - 1) / 2f) * 25,
                          ((rows - 1) / 2f - index / columns) * 25, 0);
                    placement.scale.set(.24f, .24f, .24f);
                    assembled.nodes.add(placement);
                } finally {
                    model.dispose();
                }
            }
            assembled.calculateTransforms();
            return new GpuUnitModel(assembled, null, true, bindings, null, rigs,
                  UnitFamilyScale.forFamily(descriptor.getString("family")));
        } catch (RuntimeException error) {
            assembled.dispose();
            throw error;
        }
    }
}
