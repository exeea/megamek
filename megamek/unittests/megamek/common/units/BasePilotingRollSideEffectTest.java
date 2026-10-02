/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.units;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;

import megamek.common.CriticalSlot;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reading a unit's base piloting roll changes nothing about the unit, so the unit record may show it (the Base PSR
 * line) whenever it captures the unit: every field that travels with the unit is the same before and after the call.
 */
class BasePilotingRollSideEffectTest {
    private static final String UNITS = "testresources/megamek/common/units/";

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void readingTheBasePilotingRollLeavesEveryUnitUnchanged() throws Exception {
        Game game = new Game();
        game.setPhase(GamePhase.MOVEMENT);
        Mek atlas = (Mek) load("Atlas AS7-D.mtf");
        atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_GYRO, Mek.LOC_CENTER_TORSO, 1);
        atlas.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.ACTUATOR_HIP, Mek.LOC_LEFT_LEG, 1);
        atlas.getCrew().setHits(2, 0);
        Mek turtle = (Mek) load("Great Turtle GTR-1.mtf");
        turtle.damageSystem(CriticalSlot.TYPE_SYSTEM, Mek.SYSTEM_SENSORS, Mek.LOC_HEAD, 2);
        LandAirMek lam = (LandAirMek) load("Shadow Hawk LAM SHD-X2.mtf");
        lam.setConversionMode(LandAirMek.CONV_MODE_FIGHTER);
        List<Entity> units = List.of(atlas, turtle, lam, load("Triskelion TRK-4V.mtf"),
              load("Bulldog Medium Tank.blk"), load("SOAR VTOL.blk"), load("Cheetah F-11.blk"),
              load("Union (3055).blk"), load("Elemental BA [Laser] (Sqd5).blk"),
              load("Foot Platoon (AFFS) (Laser 3067+).blk"));
        int id = 1;
        for (Entity unit : units) {
            unit.setId(id++);
            game.addEntity(unit, false);
        }
        for (Entity unit : units) {
            // Its trace log lines name the unit, which fills the unit's display name cache: a derived value, not state
            unit.getDisplayName();
            byte[] before = serialized(unit);
            unit.getBasePilotingRoll();
            assertArrayEquals(before, serialized(unit), unit.getShortName());
        }
    }

    private static byte[] serialized(Entity unit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(unit);
        }
        return bytes.toByteArray();
    }

    private static Entity load(String file) throws Exception {
        return new MekFileParser(new File(UNITS + file)).getEntity();
    }
}
