/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoardDecorationTest {
    private static BoardDecoration tree(String id, double x, double rotation, BoardDecoration.Placement placement) {
        return new BoardDecoration(id, "prop", "birch-young", null, x, .2, rotation, false, .4, placement, 0);
    }

    /**
     * A twin is a hidden copy: id and group aside, the same object within 0.001 hex and 0.001 degrees. The legacy layouts'
     * closest real pair of trees, 0.0096 hex apart, and an object on a roof above one on the ground are not twins.
     */
    @Test
    void aTwinIsTheSameObjectInTheSamePlaceWhateverItsIdAndGroup() {
        var ground = BoardDecoration.Placement.ground();
        var tree = tree("a", .1, 0, ground);
        assertTrue(tree.twin(tree("b", .1004, 359.9995, ground).withGroup("stamp")), "A full turn is the same turn");
        assertFalse(tree.twin(tree("b", .1096, 0, ground)), "The closest pair in the legacy layouts");
        assertFalse(tree.twin(tree("b", .1, .01, ground)));
        assertFalse(tree.twin(tree("b", .1, 0, BoardDecoration.Placement.on("building", 0))), "Another support");
        assertFalse(tree.twin(tree.withColours(BoardDecoration.Colours.of("#ff0000"))), "Other colours");
    }
}
