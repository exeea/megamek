/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuRoadSourceTest {
    @Test
    void roadAppearanceSurvivesRecaptureAndChangesWithoutDependingOnTheLegacyImage() throws Exception {
        try (var fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords at = new Coords(3, 4);
                for (int level = 1; level <= 4; level++) {
                    Hex hex = new Hex(0);
                    hex.addTerrain(new Terrain(Terrains.ROAD, level, true, 9));
                    fixture.game.getBoard().setHex(at, hex);
                    fixture.source.refresh();
                    var tile = fixture.source.takeFrame().scene().tile(at);
                    assertEquals(BoardRoad.capture(hex), tile.road());
                    assertEquals(9, tile.roadExits());
                    assertTrue(BoardRoad.rendered(tile));
                    fixture.view.centerOnHex(at);
                    fixture.source.refresh();
                    assertEquals(tile.road(), fixture.source.takeFrame().scene().tile(at).road());
                }
                Hex custom = fixture.game.getBoard().getHex(at).duplicate();
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 3));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                assertFalse(BoardRoad.rendered(fixture.source.takeFrame().scene().tile(at)));
                fixture.game.getBoard().setHex(at, new Hex(0));
                fixture.source.refresh();
                assertEquals(BoardRoad.Kind.NONE, fixture.source.takeFrame().scene().tile(at).road());
            });
        }
    }
}
