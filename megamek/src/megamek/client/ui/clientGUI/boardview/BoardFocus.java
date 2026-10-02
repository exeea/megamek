/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import megamek.common.board.Coords;
import megamek.common.units.Entity;

    /** Immutable navigation intent from the client; a unit request retains its identity even in a stacked hex. */
public record BoardFocus(long sequence, Coords coords, int entityId) {
        public BoardFocus(long sequence, Coords coords) {
            this(sequence, coords, Entity.NONE);
        }
    }
