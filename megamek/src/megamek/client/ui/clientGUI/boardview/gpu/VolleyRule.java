/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

/**
 * Which shots play together as one unit's volley, live and in replay: the same unit's shots from the same pose, and the
 * point defense answering the volley's incoming missiles.
 */
final class VolleyRule {

    private VolleyRule() {
    }

    /**
     * @param primary the volley's first own shot, or its first point-defense shot when it has no own one
     * @param next    the next shot
     *
     * @return {@code true} if {@code next} joins the volley
     */
    static boolean joins(BoardScene.Combat primary, BoardScene.Combat next) {
        boolean primaryDefensive = defensive(primary);
        if (defensive(next)) {
            int incoming = primaryDefensive ? primary.result().target().entityId() : primary.entityId();
            return next.result().target().entityId() == incoming;
        }
        if (primaryDefensive) {
            return next.entityId() == primary.result().target().entityId();
        }
        return (next.entityId() == primary.entityId())
              && next.attacker().location().samePose(primary.attacker().location());
    }

    /** @return the volley's first own shot, or its first shot when all of them are point defense */
    static BoardScene.Combat primary(List<BoardScene.Combat> volley) {
        for (BoardScene.Combat shot : volley) {
            if (!defensive(shot)) {
                return shot;
            }
        }
        return volley.getFirst();
    }

    /** @return {@code true} if the shot is point defense against another unit's missiles */
    static boolean defensive(BoardScene.Combat shot) {
        return (shot.result().shot() != null) && shot.result().shot().defensive();
    }
}
