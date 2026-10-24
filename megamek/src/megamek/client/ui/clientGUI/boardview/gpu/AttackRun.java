/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

/**
 * An aircraft's attack run, played as one action: it flies its held move while each of its shots fires as it reaches
 * the hex the shot was made from. {@link AttackRunAssembler} builds it from the firing results' burst.
 *
 * @param flight the aircraft's released move
 * @param shots  its shots, in the order they were resolved
 */
record AttackRun(BoardScene.Movement flight, List<BoardScene.Combat> shots) implements BoardScene.Animation {

    AttackRun {
        shots = List.copyOf(shots);
    }

    @Override
    public int entityId() {
        return flight.entityId();
    }

    @Override
    public int boardId() {
        return flight.boardId();
    }
}
