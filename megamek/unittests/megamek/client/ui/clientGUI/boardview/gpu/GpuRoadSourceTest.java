/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuRoadSourceTest {
    @Test
    void minesCapturesEveryAuthoredRoadExitWithSandUnderTheCurves() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 Mines 1.board"));
        BoardScene scene = minesScene();
        int roads = 0, decorated = 0;
        for (var tile : scene.tiles()) {
            Hex hex = board.getHex(tile.coords());
            if (!hex.containsTerrain(Terrains.ROAD)) { continue; }
            roads++;
            if (hex.containsTerrain(Terrains.ROAD_FLUFF)) { decorated++; }
            assertEquals(BoardRoad.Kind.PAVED, tile.road(), tile.coords().getBoardNum());
            assertEquals(hex.getTerrain(Terrains.ROAD).getExits(), tile.roadExits(), tile.coords().getBoardNum());
            assertTrue(BoardRoad.rendered(tile), "Native road including curved artwork: " + tile.coords().getBoardNum());
            assertEquals(BoardScene.Surface.SAND, tile.surface(), "Desert ground: " + tile.coords().getBoardNum());
        }
        assertEquals(30, roads);
        assertEquals(9, decorated);
    }

    static BoardScene minesScene() throws Exception {
        return scene("Deserts/16x17 Mines 1.board");
    }

    static BoardScene scene(String path) throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/" + path));
        var scene = new AtomicReference<BoardScene>();
        try (var fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                scene.set(fixture.source.takeFrame().scene());
            });
        }
        return scene.get();
    }

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
                custom.setTheme("desert");
                custom.addTerrain(new Terrain(Terrains.ROAD_FLUFF, 1));
                fixture.game.getBoard().setHex(at, custom);
                fixture.source.refresh();
                var curve = fixture.source.takeFrame().scene().tile(at);
                assertTrue(BoardRoad.rendered(curve));
                assertEquals(BoardScene.Surface.SAND, curve.surface());
                custom = custom.duplicate();
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
