/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;

class BlackIceDiscoveryTest {
    @Test
    void enteringAuthoredIceDiscoversItOnceWithoutChangingItsRulesType() {
        var manager = mock(TWGameManager.class);
        var coords = new Coords(1, 1);
        var hex = new Hex(0, "pavement:1;black_ice:1", "");
        assertFalse(hex.getTerrain(Terrains.BLACK_ICE).isBlackIceDetected());
        assertTrue(ServerHelper.checkEnteringBlackIce(manager, coords, hex, true, true, false));
        assertTrue(hex.getTerrain(Terrains.BLACK_ICE).isBlackIceDetected());
        assertFalse(hex.containsTerrain(Terrains.ICE), "Discovery must not change movement or ice-breaking rules");
        assertTrue(ServerHelper.checkEnteringBlackIce(manager, coords, hex, true, true, false));
        verify(manager, times(1)).sendChangedHex(coords);
    }

    @Test
    void conditionsThatDoNotCheckBlackIceDoNotRevealIt() {
        var manager = mock(TWGameManager.class);
        var coords = new Coords(1, 1);
        var pavement = new Hex(0, "pavement:1;black_ice:1", "");
        var grass = new Hex(0, "black_ice:1", "");
        assertFalse(ServerHelper.checkEnteringBlackIce(manager, coords, pavement, false, true, false));
        assertFalse(ServerHelper.checkEnteringBlackIce(manager, coords, grass, true, true, true));
        assertFalse(pavement.getTerrain(Terrains.BLACK_ICE).isBlackIceDetected());
        verifyNoInteractions(manager);
    }

    @Test
    void discoverySurvivesSnapshotsAndGameSerializationButNotMapAuthoring() throws Exception {
        var ice = new Terrain(Terrains.BLACK_ICE, 1);
        ice.detectBlackIce();
        assertTrue(new Terrain(ice).isBlackIceDetected());
        var bytes = new ByteArrayOutputStream();
        try (var output = new ObjectOutputStream(bytes)) { output.writeObject(ice); }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertTrue(((Terrain) input.readObject()).isBlackIceDetected());
        }
        assertFalse(new Terrain(ice.toString()).isBlackIceDetected(), "A saved map starts undiscovered in a new game");
    }
}
