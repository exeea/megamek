/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.List;

import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Lasers take the class and size colours of the BattleTech convention from their weapon types. */
class GpuLaserTintTest {
    @BeforeAll
    static void types() { EquipmentType.initializeTypes(); }

    private static com.badlogic.gdx.graphics.Color tint(String internalName) {
        return GpuAttackEffects.laserTint((WeaponType) EquipmentType.get(internalName), false);
    }

    @Test
    void eachLaserClassAndSizeHasItsOwnColour() {
        List<String> lasers = List.of("ISSmallLaser", "ISMediumLaser", "ISLargeLaser", "ISERSmallLaser",
              "ISERMediumLaser", "ISERLargeLaser", "CLERSmallLaser", "CLERMediumLaser", "CLERLargeLaser",
              "CLHeavySmallLaser", "CLHeavyMediumLaser", "CLHeavyLargeLaser");
        var colours = new HashSet<>(lasers.stream().map(GpuLaserTintTest::tint).toList());
        assertEquals(lasers.size(), colours.size(), "Twelve classes and sizes, twelve colours");
    }

    @Test
    void variantsTakeTheirClassAndSizeColour() {
        assertEquals(tint("ISSmallLaser"), tint("ISSmallPulseLaser"));
        assertEquals(tint("ISLargeLaser"), tint("ISLargeXPulseLaser"));
        assertEquals(tint("ISERMediumLaser"), tint("ISBAERMediumLaser"));
        assertEquals(tint("CLERSmallLaser"), tint("CLBAERMicroLaser"));
        assertEquals(tint("CLERMediumLaser"),
              tint("BACLERMediumPulseLaser"));
        assertEquals(tint("CLHeavyMediumLaser"),
              tint("CLImprovedMediumHeavyLaser"));
        assertNotEquals(tint("ISERSmallLaser"), tint("CLERSmallLaser"));
    }
}
