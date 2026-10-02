/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.Cell;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.DamageTier;
import megamek.client.ui.clientGUI.boardview.gpu.GpuPaperdoll.View;
import org.junit.jupiter.api.Test;

/** The paperdoll's one damage ramp and its cell rules (unit panel design 6.3, 6.4), as tables of literal cases. */
class GpuPaperdollTest {
    @Test
    void damageFillTiersAtTheCardThresholds() {
        assertEquals(DamageTier.INTACT, GpuPaperdoll.damageFill(34, 34, false));
        assertEquals(DamageTier.LIGHT, GpuPaperdoll.damageFill(33, 34, false));
        assertEquals(DamageTier.LIGHT, GpuPaperdoll.damageFill(71, 100, false));
        assertEquals(DamageTier.DAMAGED, GpuPaperdoll.damageFill(70, 100, false));
        assertEquals(DamageTier.DAMAGED, GpuPaperdoll.damageFill(41, 100, false));
        assertEquals(DamageTier.CRITICAL, GpuPaperdoll.damageFill(40, 100, false));
        assertEquals(DamageTier.CRITICAL, GpuPaperdoll.damageFill(1, 100, false));
        assertEquals(DamageTier.EXPOSED, GpuPaperdoll.damageFill(0, 47, false));
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.damageFill(0, 34, true));
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.damageFill(20, 34, true));
        assertEquals(DamageTier.NONE, GpuPaperdoll.damageFill(0, 0, false));
    }

    @Test
    void cellsPerView() {
        // The Atlas of the approved mockup: CT armor gone over 24 structure with a critical hit, LA destroyed.
        assertEquals(new Cell(DamageTier.EXPOSED, "(24)", true, false, 0), GpuPaperdoll.cell(0, 47, false, 24, true,
              View.CARD));
        assertEquals(new Cell(DamageTier.EXPOSED, "", true, false, 0), GpuPaperdoll.cell(0, 47, false, 24, true,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false, 0), GpuPaperdoll.cell(0, 34,
              true, 0, true, View.CARD));
        assertEquals(new Cell(DamageTier.DESTROYED, "", false, false, 0), GpuPaperdoll.cell(0, 34, true, 0, false,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false, 0), GpuPaperdoll.cell(0, 17,
              true, 0, false, View.STRUCTURE));
        // Armor and structure both gone is destroyed, even before MegaMek marks the location.
        assertEquals(DamageTier.DESTROYED, GpuPaperdoll.cell(0, 41, false, 0, false, View.CARD).tier());
        assertEquals(new Cell(DamageTier.CRITICAL, "12", true, false, 0), GpuPaperdoll.cell(12, 41, false, 21, true,
              View.ARMOR));
        assertEquals(new Cell(DamageTier.DAMAGED, "18", false, false, 0), GpuPaperdoll.cell(18, 32, false, 21, false,
              View.CARD));
        assertEquals(new Cell(DamageTier.NONE, "\u2014", false, false, 0), GpuPaperdoll.cell(0, 0, false, 5, false,
              View.CARD));
        assertEquals(new Cell(DamageTier.DAMAGED, "18", false, true, 5), GpuPaperdoll.cell(18, 32, false, 21, false,
              View.CARD).with(true, 5));
    }

    @Test
    void shieldCells() {
        // Shield (Medium): capacity 18, absorption 5 (MiscMounted base values).
        assertEquals(Map.of("DCLA", new Cell(DamageTier.DAMAGED, "11", false, false, 0), "DALA",
              new Cell(DamageTier.INTACT, "5", false, false, 0)), GpuPaperdoll.shieldCells("LA", true, 11, 18, 5, 5));
        // Capacity spent: one destroyed object, one cross in the panel and the destroyed fill in the strip.
        Map<String, Cell> destroyed = Map.of("DCRA", new Cell(DamageTier.DESTROYED, GpuPaperdoll.CROSS, false, false,
              0), "DARA", new Cell(DamageTier.DESTROYED, "", false, false, 0));
        assertEquals(destroyed, GpuPaperdoll.shieldCells("RA", true, 0, 25, 7, 7));
        // Not active (its arm blown off): MegaMek's getters still report full values.
        assertEquals(destroyed, GpuPaperdoll.shieldCells("RA", false, 25, 25, 7, 7));
        // No absorption left alone: the strip is destroyed, the panel keeps its number; never exposed.
        assertEquals(Map.of("DCLA", new Cell(DamageTier.CRITICAL, "4", false, false, 0), "DALA",
              new Cell(DamageTier.DESTROYED, "", false, false, 0)), GpuPaperdoll.shieldCells("LA", true, 4, 18, 0, 5));
    }
}
