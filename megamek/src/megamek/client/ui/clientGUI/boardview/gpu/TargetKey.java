/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.actions.WeaponAttackAction;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;

/**
 * A target as MegaMek's attacks name it: its Targetable type and id, which Game.getTarget resolves. It names a unit, a
 * hex, a building or a minefield alike, so every target the firing display accepts is one the HUD can focus, assign
 * and show (the user's report of 2026-10-03: a terrain target kept "Hold fire").
 */
record TargetKey(int type, int id) {
    /** No target. */
    static final TargetKey NONE = unit(Entity.NONE);

    static TargetKey of(Targetable target) {
        return new TargetKey(target.getTargetType(), target.getId());
    }

    /** The target of the weapon attack. */
    static TargetKey of(WeaponAttackAction attack) {
        return new TargetKey(attack.getTargetType(), attack.getTargetId());
    }

    static TargetKey unit(int id) {
        return new TargetKey(Targetable.TYPE_ENTITY, id);
    }

    /** The unit's id, or Entity.NONE for another target. */
    int unitId() {
        return (type == Targetable.TYPE_ENTITY) ? id : Entity.NONE;
    }
}
