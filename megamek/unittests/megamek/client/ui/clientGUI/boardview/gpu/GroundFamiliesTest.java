/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The compact ground's rule kinds: which programs compile which rules. */
class GroundFamiliesTest {
    @Test
    void wallCellsCompileOnlyWhereAFamilyReadsItsWallsInCells() {
        String[] floors = new String[BoardScene.Surface.values().length];
        int cells = 1 << GroundFamilies.Rule.WALL_CELLS.ordinal();
        assertTrue(GroundFamilies.cellWalls(BoardScene.Surface.FUNGUS));
        assertFalse(GroundFamilies.cellWalls(BoardScene.Surface.ROCK));
        // A family program: its own row decides.
        assertEquals(cells, GroundFamilies.kinds(1 << BoardScene.Surface.FUNGUS.ordinal(), floors) & cells);
        assertEquals(0, GroundFamilies.kinds(1 << BoardScene.Surface.ROCK.ordinal(), floors) & cells);
        // A generic program reads every row: the kind is in, unless its board has no such family.
        int every = GroundFamilies.kinds(-1, floors);
        String define = "#define groundWallCellsRule\n";
        assertTrue(GroundFamilies.defines(every, s -> true).contains(define));
        assertFalse(GroundFamilies.defines(GroundFamilies.withoutWallCells(every), s -> true).contains(define));
        assertTrue(GroundFamilies.defines(GroundFamilies.withoutWallCells(every), s -> true)
              .contains("#define groundWallReadsRule\n"), "the wall reads themselves stay");
        // Varied cliff faces off: neither compiles.
        assertFalse(GroundFamilies.defines(every, s -> s != BoardSwitch.GROUND_WALL_READS).contains(define));
    }
}
