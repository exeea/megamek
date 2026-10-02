/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import megamek.common.CriticalSlot;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscMounted;
import megamek.common.equipment.MiscType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * How many hits destroy a Mek's gyro, sensors and a LAM's avionics, and when a shield still works, as the rules code
 * decides it (recorded before U1 moved each threshold into one method that the record sheet reads too).
 */
class MekHitThresholdsTest {
    private static final String UNITS = "testresources/megamek/common/units/";
    private static final File ZIP = new File("data/mekfiles/unit_files.zip");

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void aStandardGyroFailsAtTwoHitsAndAHeavyDutyGyroAtThree() throws Exception {
        Mek atlas = (Mek) load(new File(UNITS + "Atlas AS7-D.mtf"));
        List<Boolean> destroyed = new ArrayList<>();
        for (int gyroType : List.of(Mek.GYRO_STANDARD, Mek.GYRO_HEAVY_DUTY)) {
            atlas.setGyroType(gyroType);
            for (int hits = 0; hits <= 3; hits++) {
                atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_GYRO, Mek.LOC_CENTER_TORSO, hits);
                destroyed.add(atlas.isGyroDestroyed());
            }
        }
        assertEquals(List.of(false, false, true, true, false, false, false, true), destroyed);
    }

    @Test
    void aTorsoMountedCockpitLosesItsSensorsAtThreeHits() throws Exception {
        Game game = new Game();
        Mek turtle = (Mek) load(new File(UNITS + "Great Turtle GTR-1.mtf"));
        game.addEntity(turtle, false);
        List<String> rolls = new ArrayList<>();
        for (int[] hits : new int[][] { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 1, 1 }, { 2, 1 } }) {
            turtle.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_SENSORS, Mek.LOC_HEAD, hits[0]);
            turtle.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_SENSORS, Mek.LOC_CENTER_TORSO, hits[1]);
            rolls.add(turtle.getBasePilotingRoll().getValueAsString() + " " + turtle.getBasePilotingRoll().getDesc());
        }
        String base = "5 (Base piloting skill) - 2 (Quad bonus) + 1 (Torso-Mounted Cockpit)";
        assertEquals(List.of("5 " + base + " + 1 (Hardened Armor)", "5 " + base + " + 1 (Hardened Armor)",
              "9 " + base + " + 4 (Head Sensors Destroyed for Torso-Mounted Cockpit) + 1 (Hardened Armor)",
              "5 " + base + " + 1 (Hardened Armor)",
              "9 " + base + " + 4 (Sensors Completely Destroyed for Torso-Mounted Cockpit) + 1 (Hardened Armor)"),
              rolls);
    }

    @Test
    void aFighterModeLamLosesItsAvionicsAtThreeHits() throws Exception {
        Game game = new Game();
        LandAirMek lam = (LandAirMek) load(new File(UNITS + "Shadow Hawk LAM SHD-X2.mtf"));
        game.addEntity(lam, false);
        lam.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
        List<String> rolls = new ArrayList<>();
        for (int hits = 0; hits <= 4; hits++) {
            lam.damageSystem(CriticalSlot.TYPE_SYSTEM, LandAirMek.LAM_AVIONICS, hits);
            rolls.add(lam.getAvionicsHits() + ": " + lam.getBasePilotingRoll().getDesc());
        }
        // The LAM has three avionics slots, so a fourth hit finds none left
        String air = " + 1 (Atmospheric operations)";
        assertEquals(List.of("0: 5 (Base piloting skill)" + air,
              "1: 5 (Base piloting skill) + 1 (avionics damage)" + air,
              "2: 5 (Base piloting skill) + 2 (avionics damage)" + air,
              "3: 5 (Base piloting skill) + 5 (avionics destroyed)" + air,
              "3: 5 (Base piloting skill) + 5 (avionics destroyed)" + air), rolls);
    }

    @Test
    void aShieldWorksWhileItsArmHasStructureAndOneOfItsSlotsIsIntact() throws Exception {
        List<Boolean> works = new ArrayList<>();
        BipedMek centurion = centurion();
        works.add(centurion.hasShield());
        List<CriticalSlot> slots = shieldSlots(centurion);
        slots.getFirst().setHit(true);
        works.add(centurion.hasShield());
        slots.forEach(slot -> slot.setDestroyed(true));
        works.add(centurion.hasShield());

        centurion = centurion();
        centurion.destroyLocation(Mek.LOC_LEFT_ARM, true);
        works.add(centurion.hasShield());

        centurion = centurion();
        centurion.setInternal(0, Mek.LOC_LEFT_ARM);
        works.add(centurion.hasShield());

        centurion = centurion();
        shield(centurion).setDestroyed(true);
        works.add(centurion.hasShield());
        assertEquals(List.of(true, true, false, false, false, false), works);
    }

    private static BipedMek centurion() throws Exception {
        return (BipedMek) new MekFileParser(ZIP, "meks/3145/Davion/Centurion CN11-OD.mtf").getEntity();
    }

    private static MiscMounted shield(Mek mek) {
        return mek.getMisc().stream().filter(misc -> misc.getType().hasFlag(MiscType.F_SHIELD)).findFirst()
              .orElseThrow();
    }

    private static List<CriticalSlot> shieldSlots(Mek mek) {
        MiscMounted shield = shield(mek);
        List<CriticalSlot> slots = new ArrayList<>();
        for (int slot = 0; slot < mek.getNumberOfCriticalSlots(shield.getLocation()); slot++) {
            CriticalSlot critical = mek.getCritical(shield.getLocation(), slot);
            if ((critical != null) && (critical.getMount() == shield)) {
                slots.add(critical);
            }
        }
        return slots;
    }

    private static Entity load(File file) throws Exception {
        return new MekFileParser(file).getEntity();
    }
}
