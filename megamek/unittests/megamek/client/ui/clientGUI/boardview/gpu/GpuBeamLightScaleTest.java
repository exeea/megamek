/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A beam's light follows its weapon's damage at the shot's range, against a medium laser and a standard PPC. */
class GpuBeamLightScaleTest {
    @BeforeAll
    static void types() { EquipmentType.initializeTypes(); }

    private static float scale(String weapon, int range) {
        return GpuAttackEffects.beamScale((WeaponType) EquipmentType.get(weapon), range);
    }

    @Test
    void everyLaserColourLightsAsBrightlyAsTheMediumLaser() {
        float medium = luminance(GpuAttackEffects.laserLight(GpuAttackEffects.laserTint(weapon("ISMediumLaser"), false)));
        for (String laser : new String[] { "ISSmallLaser", "ISLargeLaser", "ISERLargeLaser", "CLERSmallLaser", "CLHeavyLargeLaser" }) {
            float other = luminance(GpuAttackEffects.laserLight(GpuAttackEffects.laserTint(weapon(laser), laser.startsWith("CL"))));
            assertEquals(medium, other, medium * 1e-4, laser + ": only its damage changes its brightness, not its colour");
        }
    }

    private static WeaponType weapon(String name) { return (WeaponType) EquipmentType.get(name); }

    private static float luminance(com.badlogic.gdx.math.Vector3 linear) {
        return .2126f * linear.x + .7152f * linear.y + .0722f * linear.z;
    }

    @Test
    void laserLightGrowsWithDamageFromTheMediumLasersCalibration() {
        assertEquals(1, scale("ISMediumLaser", 3));
        assertTrue(scale("ISSmallLaser", 3) < scale("ISMediumLaser", 3));
        assertTrue(scale("ISMediumLaser", 3) < scale("ISLargeLaser", 3));
        assertTrue(scale("ISLargeLaser", 3) < scale("CLHeavyLargeLaser", 3));
        assertEquals(3, scale("CLHeavyLargeLaser", 3), "16 damage is held at three times the medium laser's light");
    }

    @Test
    void ppcLightGrowsWithDamageFromTheStandardPpcsCalibration() {
        assertEquals(1, scale("ISPPC", 6));
        assertTrue(scale("ISLightPPC", 6) < scale("ISPPC", 6));
        assertTrue(scale("ISPPC", 6) < scale("ISHeavyPPC", 6));
    }

    @Test
    void aVariableDamageWeaponLightsWithItsDamageAtTheShotsRange() {
        // A medium VSP laser deals 9 at short range (to 2 hexes) and 5 at long range (6 to 9 hexes).
        assertEquals(9 / 5f, scale("ISMediumVSPLaser", 1), 1e-6f);
        assertEquals(1, scale("ISMediumVSPLaser", 7));
    }

    @Test
    void anUnknownWeaponKeepsTheReferenceLight() {
        assertEquals(1, GpuAttackEffects.beamScale(null, 5));
    }
}
