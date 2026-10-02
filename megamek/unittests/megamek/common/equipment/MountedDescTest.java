/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.equipment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A mount's description, as the Unit Display's lists, the reports and the tooltips show it: its state mark, its mount
 * marks and facings, and the states that follow it (recorded before U1 split out its state-free part).
 */
class MountedDescTest {
    private static final String UNITS = "testresources/megamek/common/units/";

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void theDescriptionKeepsStateMarksMountMarksFacingsAndTrailingStates() throws Exception {
        RulesManager rules = Game.rulesManager;
        Game game = new Game();
        // Ammunition dumping exists under the Total Warfare rules only.
        game.initializeRulesManager(OptionsConstants.RULES_TW);
        game.getOptions().getOption(OptionsConstants.ADVANCED_STRATOPS_QUIRKS).setValue(true);
        Entity atlas = new MekFileParser(new File(UNITS + "Atlas AS7-D.mtf")).getEntity();
        atlas.setId(1);
        game.addEntity(atlas, false);
        List<WeaponMounted> weapons = atlas.getWeaponList();
        WeaponMounted laser = weapons.getFirst();
        WeaponMounted srm = weapons.get(3);
        WeaponMounted rear = weapons.get(5);
        Mounted<?> lrmAmmo = atlas.getAmmo().getFirst();
        Mounted<?> grenades = atlas.addEquipment(EquipmentType.get("ISVehicularGrenadeLauncher"),
              Mek.LOC_LEFT_TORSO);
        List<Mounted<?>> mounts = List.of(laser, srm, rear, lrmAmmo, grenades);

        List<String> descs = new ArrayList<>();
        descs.add(laser.getDesc());
        laser.setDestroyed(true);
        descs.add(laser.getDesc());
        laser.setRepairable(false);
        descs.add(laser.getDesc());
        laser.setDestroyed(false);
        laser.setMissing(true);
        descs.add(laser.getDesc());
        laser.setMissing(false);
        laser.setBreached(true);
        descs.add(laser.getDesc());
        laser.setBreached(false);
        laser.setUsedThisRound(true);
        descs.add(laser.getDesc());
        laser.setUsedThisRound(false);
        laser.setJammedImmediately(true);
        descs.add(laser.getDesc());
        laser.setJammedImmediately(false);
        laser.setFired(true);
        descs.add(laser.getDesc());
        laser.setFired(false);
        laser.setMekTurretMounted(true);
        descs.add(laser.getDesc());
        laser.setMekTurretMounted(false);
        laser.setSponsonTurretMounted(true);
        laser.setPintleTurretMounted(true);
        descs.add(laser.getDesc());
        laser.setSponsonTurretMounted(false);
        laser.setPintleTurretMounted(false);
        laser.setWeaponGroup(true);
        laser.setNWeapons(2);
        laser.setArmored(true);
        descs.add(laser.getDesc());
        descs.add(rear.getDesc());
        rear.setDestroyed(true);
        descs.add(rear.getDesc());
        srm.getQuirks().getOption(OptionsConstants.QUIRK_WEAPON_POS_DIRECT_TORSO_MOUNT).setValue(true);
        descs.add(srm.getDesc());
        srm.setDirectionalMountFacing(2);
        srm.setDirectionalMountLocked(true);
        descs.add(srm.getDesc());
        descs.add(lrmAmmo.getDesc());
        lrmAmmo.setPendingDump(true);
        descs.add(lrmAmmo.getDesc());
        lrmAmmo.setDumping(true);
        descs.add(lrmAmmo.getDesc());
        grenades.setFacing(1);
        descs.add(grenades.getDesc());
        Game.rulesManager = rules;
        assertEquals(List.of("Medium Laser", "*Medium Laser", "**Medium Laser", "x Medium Laser", "x Medium Laser",
              "+Medium Laser", "j Medium Laser", "- Medium Laser", "Medium Laser (T)", "Medium Laser (ST) (PT)",
              "Medium Laser (2) (armored)", "Medium Laser (R)", "*Medium Laser (R)", "SRM 6 (DTM)",
              "SRM 6 (DTM) (RR) (Locked)", "LRM 20 Ammo (6)", "d LRM 20 Ammo (6)", "d LRM 20 Ammo (6) (dumping)",
              "Vehicular Grenade Launcher (FR)"), descs, mounts.toString());
    }
}
