/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.enums.BombType.BombTypeEnum;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Arrow IV rounds fly as the Arrow IV model; every other missile keeps the plain missile body. */
class GpuArrowIVRoundTest {
    @BeforeAll
    static void loadEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void arrowIVLaunchersFireTheArrowIVRound() {
        assertTrue(firesArrowIV("ISArrowIV"), "Inner Sphere Arrow IV");
        assertTrue(firesArrowIV("CLArrowIV"), "Clan Arrow IV");
        assertTrue(firesArrowIV(BombTypeEnum.ARROW.getWeaponName()), "An aircraft's Arrow IV homing missile");
    }

    @Test
    void otherMissilesKeepThePlainBody() {
        assertFalse(firesArrowIV("ISLRM20"));
        assertFalse(firesArrowIV("ISSRM6"));
    }

    /** A launch from the shared shot fixture, as the 3D board captures it from the weapon's resolved attack. */
    private static boolean firesArrowIV(String weaponName) {
        var round = new ResolvedAttack.Shot("", Set.of("M_STANDARD"), true, false, 1, 1, true, null, null, null, false,
              20, false, false, false, null, 20);
        var shot = GroundDamagePlaybackTest.shot(weaponName, true, round);
        shot.seconds = shot.contactSeconds;
        var effects = new GpuAttackEffects();
        effects.update(List.of(shot), null, Map.of());
        // The flight is captured when the effects are prepared, as the ground-mark pass does every frame.
        effects.groundImpacts(List.of(), ignored -> { });
        List<GpuMissileEffects.Launch> launches = effects.missileLaunches(shot);
        assertEquals(1, launches.size(), weaponName + " flies one missile");
        return GpuMissileEffects.firesArrowIV(launches.getFirst());
    }
}
