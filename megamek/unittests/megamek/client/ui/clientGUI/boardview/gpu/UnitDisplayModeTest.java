/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;

import megamek.client.ui.tileset.MekTileset;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Mek;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UnitDisplayModeTest {
    @ParameterizedTest
    @ValueSource(strings = { "Atlas AS7-D.mtf", "Bulldog Medium Tank.blk", "Chippewa CHP-W7.blk",
          "Elemental BA [Laser] (Sqd5).blk", "Foot Platoon (AFFS) (Laser 3067+).blk" })
    void modesUseCapturedIdentityEvenWhenNoModelIsAvailable(String file) throws Exception {
        var entity = new MekFileParser(new File("testresources/megamek/common/units", file)).getEntity();
        var tileset = mock(MekTileset.class);
        when(tileset.genericModelFor(entity, -1)).thenReturn("unused-fallback.json");
        var selection = UnitModelSelection.capture(entity, -1, false, tileset);
        assertNotNull(selection);
        assertNull(selection.asset());
        assertNull(selection.fallback(), "Missing model entries must retain the existing flat fallback in 3D Models mode");
        assertTrue(UnitDisplayMode.ALL_MEEPLES.meeple(selection));
        assertEquals(entity instanceof Mek, UnitDisplayMode.DEFAULT.meeple(selection));
        assertFalse(UnitDisplayMode.MODELS.meeple(selection));
        assertNull(UnitModelSelection.capture(entity, -1, true, mock(MekTileset.class)),
              "Sensor contacts must not expose unit identity");
    }
}
