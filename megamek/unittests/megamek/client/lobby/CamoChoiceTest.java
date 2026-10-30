/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.battlefieldSupport.StripeDirection;
import megamek.common.icons.Camouflage;
import org.junit.jupiter.api.Test;

class CamoChoiceTest {
    @Test
    void itStartsFromTheInitialCamosAdjustments() {
        Camouflage initial = new Camouflage("Clans/Wolf/", "Alpha.png");
        initial.setRotationAngle(30);
        initial.setScale(7);
        CamoChoice choice = new CamoChoice(initial, true);
        assertTrue(choice.individual());
        assertEquals(new Camouflage("Clans/Wolf/", "Alpha.png"), choice.selected());
        assertEquals(30, choice.rotation());
        assertEquals(7, choice.scale());
        assertEquals(0, choice.overlayColour());
    }

    @Test
    void rotationAndScaleAreClampedAndSnapped() {
        CamoChoice choice = new CamoChoice(null, false);
        assertNull(choice.selected());
        choice.rotation(44);
        assertEquals(40, choice.rotation());
        choice.rotation(400);
        assertEquals(CamoChoice.MAX_ROTATION, choice.rotation());
        choice.scale(1);
        assertEquals(CamoChoice.MIN_SCALE, choice.scale());
        choice.scale(99);
        assertEquals(CamoChoice.MAX_SCALE, choice.scale());
    }

    @Test
    void thePaletteHoldsNoColourTwice() {
        CamoChoice choice = new CamoChoice(null, false);
        long distinct = choice.palette().stream().map(Camouflage::getOverlayColor).distinct().count();
        assertEquals(choice.palette().size(), distinct);
        assertTrue(choice.palette().size() > 2);
        assertThrows(IndexOutOfBoundsException.class, () -> choice.overlayColour(choice.palette().size()));
    }

    @Test
    void theResultCarriesTheChosenCamoAndAdjustments() {
        CamoChoice choice = new CamoChoice(null, false);
        choice.select(new Camouflage(Camouflage.COLOUR_CAMOUFLAGE, "RED"));
        choice.rotation(90);
        choice.scale(5);
        choice.overlayColour(1);
        choice.direction(StripeDirection.HORIZONTAL);
        Camouflage result = choice.result();
        assertEquals(Camouflage.COLOUR_CAMOUFLAGE, result.getCategory());
        assertEquals("RED", result.getFilename());
        assertEquals(90, result.getRotationAngle());
        assertEquals(5, result.getScale());
        assertEquals(choice.palette().get(1).getOverlayColor(), result.getOverlayColor());
        assertEquals(StripeDirection.HORIZONTAL, result.getOverlayDirection());
        Camouflage owner = choice.ownerResult();
        assertTrue(owner.isDefault());
        assertEquals(90, owner.getRotationAngle());
    }
}
