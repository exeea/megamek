/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuRoughCaptureTest {
    @ParameterizedTest
    @ValueSource(ints = { 0, 9 })
    void roughAddsGeometryWithoutPaintingDuplicateRocksUnderIt(int exits) throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Coords coords = new Coords(2, 2);
                Hex hex = new Hex(0);
                if (exits != 0) { hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, exits)); }
                fixture.game.getBoard().setHex(coords, hex);
                fixture.source.refresh();
                var clear = fixture.source.takeFrame().scene().tile(coords);
                Hex rough = hex.duplicate();
                rough.addTerrain(new Terrain(Terrains.ROUGH, 1));
                fixture.game.getBoard().setHex(coords, rough);
                fixture.source.refresh();
                var rocky = fixture.source.takeFrame().scene().tile(coords);
                assertEquals(clear.ground(), rocky.ground(), "GPU base/road artwork contains no painted boulders");
                assertEquals(clear.decals(), rocky.decals(), "Rough is not duplicated as a decal");
                assertEquals(exits == 0, rocky.detailedGround(), "Plain Rough uses the engine; roads keep their markings");
                assertTrue(rocky.features().stream().anyMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
                assertFalse(clear.sameGeometry(rocky), "Adding Rough invalidates the shared terrain mesh");
                assertTrue(fixture.game.getBoard().getHex(coords).containsTerrain(Terrains.ROUGH),
                      "Filtering artwork must not alter game terrain");
            });
        }
    }
}
