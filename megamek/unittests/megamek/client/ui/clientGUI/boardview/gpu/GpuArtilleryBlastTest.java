/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.Test;

/** Which landings draw an artillery blast, which shape they take, and how long the blasts live. */
class GpuArtilleryBlastTest {

    private static ResolvedAttack.Shot shot(boolean artillery, String... munitions) {
        return new ResolvedAttack.Shot("", Set.of(munitions), artillery, false, 1, 0, artillery, null, null, null,
              true, 20, false, false, false, null, 20);
    }

    @Test
    void highExplosiveArtilleryExplodes() {
        assertTrue(GpuArtilleryBlast.explodes(shot(true)));
        assertTrue(GpuArtilleryBlast.explodes(shot(true, "M_STANDARD")));
    }

    @Test
    void directFireWeaponsAndMissingProfilesDoNotExplode() {
        assertFalse(GpuArtilleryBlast.explodes(shot(false)));
        assertFalse(GpuArtilleryBlast.explodes(null));
    }

    @Test
    void minesSmokeAndFlaresKeepTheirOwnPresentation() {
        for (String munition : new String[] { "M_FASCAM", "M_THUNDER", "M_SMOKE", "M_FLARE", "M_LASER_INHIB" }) {
            assertFalse(GpuArtilleryBlast.explodes(shot(true, munition)), munition);
        }
    }

    @Test
    void specialWarheadsChooseTheirShape() {
        assertEquals(GpuArtilleryBlast.Style.MUSHROOM, GpuArtilleryBlast.style(shot(true, "M_DAVY_CROCKETT_M"), 1));
        assertEquals(GpuArtilleryBlast.Style.FIREBALL, GpuArtilleryBlast.style(shot(true, "M_FAE"), 1));
        assertEquals(GpuArtilleryBlast.Style.FIREBALL, GpuArtilleryBlast.style(shot(true, "M_ADA"), 1));
    }

    @Test
    void aBarrageUsesEveryShapeAndTheSameShellAlwaysLooksTheSame() {
        var seen = EnumSet.noneOf(GpuArtilleryBlast.Style.class);
        for (int seed = 0; seed < 64; seed++) {
            var style = GpuArtilleryBlast.style(shot(true), seed);
            assertEquals(style, GpuArtilleryBlast.style(shot(true), seed));
            seen.add(style);
        }
        assertEquals(EnumSet.allOf(GpuArtilleryBlast.Style.class), seen);
    }

    @Test
    void aShellReportedOnEveryFrameOfItsContactStartsOneBlast() {
        var blasts = new GpuArtilleryBlast();
        for (int frame = 0; frame < 10; frame++) {
            blasts.detonate(Vector3.Zero, shot(true), null, 99);
            blasts.advance(.016f);
        }
        assertEquals(1, blasts.size());
        blasts.detonate(Vector3.Zero, shot(true), null, 100);
        assertEquals(2, blasts.size());
    }

    @Test
    void blastsOutliveTheirAttackThenClear() {
        var blasts = new GpuArtilleryBlast();
        blasts.detonate(Vector3.Zero, shot(true), null, 7);
        blasts.advance(1);
        assertEquals(1, blasts.size(), "Still burning a second after contact, long after the attack's own clock");
        blasts.advance(GpuArtilleryBlast.SECONDS);
        assertEquals(0, blasts.size());
    }

    /** The R values of the Artillery Ordnance Table, asked of the same rule that deals the damage. */
    @Test
    void debrisReachesTheRingsTheShellDamages() {
        EquipmentType.initializeTypes();
        assertEquals(2, GpuArtilleryBlast.hexRadius("ISLongTom", shot(true, "M_STANDARD")), "Long Tom 25/15/5 (R2)");
        assertEquals(1, GpuArtilleryBlast.hexRadius("ISSniper", shot(true, "M_STANDARD")), "Sniper 20/10 (R1)");
        assertEquals(1, GpuArtilleryBlast.hexRadius("ISThumper", shot(true, "M_STANDARD")), "Thumper 15/5 (R1)");
        assertEquals(1, GpuArtilleryBlast.hexRadius("ISArrowIV", shot(true, "M_STANDARD")), "Arrow IV 20/10 (R1)");
        assertEquals(1, GpuArtilleryBlast.hexRadius("ISLongTom", shot(true, "M_CLUSTER")), "Long Tom cluster 20/10 (R1)");
        assertEquals(2, GpuArtilleryBlast.hexRadius("ISLongTom", shot(true)), "No munitions captured is standard");
    }

    @Test
    void anUnknownWeaponKeepsTheDebrisInItsHex() {
        EquipmentType.initializeTypes();
        assertEquals(0, GpuArtilleryBlast.hexRadius("NoSuchWeapon", shot(true, "M_STANDARD")));
        assertEquals(0, GpuArtilleryBlast.hexRadius(null, shot(true, "M_STANDARD")));
        assertEquals(0, GpuArtilleryBlast.hexRadius("ISLongTom", null));
    }

    @Test
    void aHeavyBarrageIsBounded() {
        var blasts = new GpuArtilleryBlast();
        for (int key = 0; key < GpuArtilleryBlast.MAX_BLASTS + 3; key++) {
            blasts.detonate(Vector3.Zero, shot(true), null, key);
        }
        assertEquals(GpuArtilleryBlast.MAX_BLASTS, blasts.size());
    }
}
