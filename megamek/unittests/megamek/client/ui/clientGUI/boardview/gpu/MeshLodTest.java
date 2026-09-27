/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MeshLodTest {
    @Test
    void optionalLevelsReuseTheClosestMoreDetailedMesh() {
        Object lod0 = new Object(), lod1 = new Object(), lod2 = new Object();
        var complete = Map.of("rock-lod0", lod0, "rock-lod1", lod1, "rock-lod2", lod2);
        assertEquals(List.of(lod0, lod1, lod2), MeshLod.load("rock", 3, complete::get));
        var two = Map.of("rock-lod0", lod0, "rock-lod1", lod1);
        var levels = MeshLod.load("rock", 3, two::get);
        assertEquals(List.of(lod0, lod1, lod1), levels);
        assertSame(levels.get(1), levels.get(2), "Fallback borrows the mesh rather than copying it");
        var one = Map.of("rock-lod0", lod0);
        assertEquals(List.of(lod0, lod0, lod0), MeshLod.load("rock", 3, one::get));
    }

    @Test
    void aGapDoesNotHideALaterAuthoredLevelOrBorrowAnotherShape() {
        Object lod0 = new Object(), lod2 = new Object();
        var shapes = Map.of("stone-lod0", lod0, "stone-lod2", lod2, "boulder-lod1", new Object());
        assertEquals(List.of(lod0, lod0, lod2), MeshLod.load("stone", 3, shapes::get));
    }

    @Test
    void missingLod0AndMalformedOptionalAssetsAreReported() {
        var noBase = Map.of("rock-lod1", new Object());
        assertThrows(IllegalArgumentException.class, () -> MeshLod.load("rock", 3, noBase::get));
        var malformed = new IllegalArgumentException("Corrupt mesh");
        assertSame(malformed, assertThrows(IllegalArgumentException.class, () -> MeshLod.load("rock", 3, name -> {
            if (name.equals("rock-lod0")) { return new Object(); }
            throw malformed;
        })));
    }
}
