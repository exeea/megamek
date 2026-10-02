/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.equipment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Mek;
import megamek.common.weapons.Weapon;
import megamek.common.weapons.bayWeapons.PointDefenseBayWeapon;
import megamek.common.weapons.other.innerSphere.ISAMS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The weapons FiringDisplay refuses to aim because they fire on their own; the fire preview skips the same ones. */
class WeaponMountedFiresAutomaticallyTest {
    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void automaticDefencesFireOnTheirOwnUntilSetForManualUse() throws Exception {
        GameOptions options = new GameOptions();
        options.getOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_MANUAL_AMS).setValue(true);
        ISAMS amsType = new ISAMS();
        amsType.adaptToGameOptions(options);
        BipedMek mek = new BipedMek();
        WeaponMounted ams = (WeaponMounted) mek.addEquipment(amsType, Mek.LOC_CENTER_TORSO);
        WeaponMounted laser = (WeaponMounted) mek.addEquipment(EquipmentType.get("ISMediumLaser"), Mek.LOC_RIGHT_ARM);
        WeaponMounted pointDefenseBay = new WeaponMounted(mek, new PointDefenseBayWeapon());

        assertTrue(ams.firesAutomatically(), "AMS in its default mode");
        assertTrue(pointDefenseBay.firesAutomatically(), "A weapon in point defense mode");
        assertFalse(laser.firesAutomatically(), "An ordinary weapon");

        assertTrue(ams.setModeImmediately(Weapon.MODE_AMS_MANUAL) >= 0);
        assertFalse(ams.firesAutomatically(), "AMS used as a weapon");
    }
}
