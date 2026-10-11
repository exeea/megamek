/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardElectronicWarfare;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.equipment.MiscType;
import megamek.common.options.OptionsConstants;
import org.junit.jupiter.api.Test;

class GpuHexOverlayTest {
    static final class Options implements AutoCloseable {
        private final GUIPreferences prefs = GUIPreferences.getInstance();
        private final boolean sheets = prefs.getShowMapSheets();
        private final Color sheetColor = prefs.getMapsheetColor();
        private final int alpha = prefs.getECMTransparency(), zoom = prefs.getMapZoomIndex();

        Options() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                prefs.setShowMapSheets(false);
                prefs.setECMTransparency(96);
            });
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(() -> {
                prefs.setShowMapSheets(sheets);
                prefs.setMapSheetColor(sheetColor);
                prefs.setECMTransparency(alpha);
                prefs.setMapZoomIndex(zoom);
            });
        }
    }

    @Test
    void sheetSeamsAndEmbeddedBoardsUseNativeGeometryAndRefreshWithoutRasterChanges() throws Exception {
        Hex[] hexes = new Hex[18 * 19];
        for (int y = 0; y < 19; y++) {
            for (int x = 0; x < 18; x++) {
                hexes[y * 18 + x] = new Hex(x == 15 ? 3 : 0);
            }
        }
        try (Options options = new Options(); GpuBoardFixture fixture = GpuBoardFixture.create(new Board(18, 19, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                BoardScene before = fixture.source.takeFrame().scene();
                assertTrue(before.tactical().fills().isEmpty());
                var prefs = GUIPreferences.getInstance();
                prefs.setMapSheetColor(Color.CYAN);
                prefs.setShowMapSheets(true);
                Coords embedded = new Coords(15, 8);
                fixture.game.getBoard().setEmbeddedBoard(1, embedded);
                fixture.source.refresh();
                BoardScene marked = fixture.source.takeFrame().scene();
                marked.tactical().fills().forEach(fill -> assertNotNull(fill.planeAnchor(),
                      "Sheet borders and embedded-board indicators belong to their owner's flat plane"));
                assertEquals(raster(before), raster(marked));
                assertTrue(covers(marked.tactical(), Color.CYAN, new Coords(16, 8), 10.5, 18),
                      "The second sheet's left edge must be drawn at the actual hex seam");
                assertTrue(covers(marked.tactical(), Color.CYAN, new Coords(8, 17), 42, 0),
                      "The second row of sheets needs its top edge");
                assertFalse(covers(marked.tactical(), Color.CYAN, new Coords(8, 8), 42, 0),
                      "Interior hexes must not acquire sheet borders");
                assertTrue(covers(marked.tactical(), new Color(0, 140, 0, 120), embedded, 42, 36));
                List<BoardTacticalGeometry.Triangle> triangles = new ArrayList<>();
                // On the column top itself: with hex transitions its edges would drape down its slopes.
                BoardSculptTest.withTransitions(false, () -> BoardTacticalGeometry.drape(marked, triangles::add));
                assertTrue(triangles.stream().filter(t -> t.argb() == new Color(0, 140, 0, 120).getRGB())
                      .allMatch(t -> t.a().z > 3 * BoardGeometry.LEVEL && t.b().z > 3 * BoardGeometry.LEVEL
                            && t.c().z > 3 * BoardGeometry.LEVEL));
                BoardView legacy2D = fixture.legacy2DView();
                try {
                    legacy2D.getComponent();
                    legacy2D.zoomOut();
                    float scale = legacy2D.getScale();
                    assertEquals(marked.tactical(), fixture.view.captureTacticalGeometry());
                    assertEquals(scale, legacy2D.getScale());
                } finally {
                    legacy2D.dispose();
                }
                prefs.setMapSheetColor(Color.YELLOW);
                fixture.source.refresh();
                assertTrue(covers(fixture.source.takeFrame().scene().tactical(), Color.YELLOW, new Coords(8, 17), 42, 0));
                prefs.setShowMapSheets(false);
                fixture.game.getBoard().embeddedBoardCoords().clear();
                fixture.source.refresh();
                assertTrue(fixture.source.takeFrame().scene().tactical().fills().isEmpty());
                assertEquals(raster(before), raster(fixture.source.takeFrame().scene()));
                assertFalse(marked.tactical().fills().isEmpty(), "Published snapshots must remain immutable");
            });
        }
    }

    @Test
    void ecmAndEccmReachTheSnapshotNotTheMarkingsAndFollowModeStrengthAndPosition() throws Exception {
        try (Options options = new Options(); GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.addEcm();
            SwingUtilities.invokeAndWait(() -> {
                fixture.source.refresh();
                BoardScene ecm = fixture.source.takeFrame().scene();
                Coords sample = new Coords(4, 4);
                assertTrue(ecm.tactical().fills().isEmpty(), "No draped wash or floating source ring");
                assertNull(ecm.tile(sample).tactical(), "No static noise in the raster markings");
                assertEquals(BoardElectronicWarfare.OWN_ECM, ecm.electronicWarfare().hex(sample) & 15);
                assertEquals(96, ecm.electronicWarfare().strength(), "The ECM Transparency setting is the strength");

                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_ECCM).setValue(true);
                fixture.entity.setGameOptions();
                var suite = fixture.entity.getMisc().stream().filter(m -> m.getType().hasFlag(MiscType.F_ECM)).findFirst().orElseThrow();
                assertTrue(suite.setModeImmediately("ECCM") >= 0);
                fixture.view.updateEcmList();
                fixture.source.refresh();
                BoardScene eccm = fixture.source.takeFrame().scene();
                assertEquals(BoardElectronicWarfare.OWN_ECCM, eccm.electronicWarfare().hex(sample) & 15);

                GUIPreferences.getInstance().setECMTransparency(48);
                fixture.source.refresh();
                BoardScene faded = fixture.source.takeFrame().scene();
                assertSame(eccm.tile(sample).features(), faded.tile(sample).features(), "Opacity must not recapture terrain");
                assertSame(eccm.tile(sample).ground(), faded.tile(sample).ground());
                assertEquals(48, faded.electronicWarfare().strength());
                fixture.entity.setPosition(new Coords(14, 14));
                fixture.view.updateEcmList();
                fixture.source.refresh();
                BoardScene moved = fixture.source.takeFrame().scene();
                assertEquals(BoardElectronicWarfare.OWN_ECCM, moved.electronicWarfare().hex(new Coords(13, 13)) & 15);
                assertEquals(0, moved.electronicWarfare().hex(sample) & 15);
                assertEquals(BoardElectronicWarfare.OWN_ECM, ecm.electronicWarfare().hex(sample) & 15,
                      "Published snapshots must not move with the emitter");
                GUIPreferences.getInstance().setECMTransparency(0);
                fixture.source.refresh();
                assertSame(BoardElectronicWarfare.EMPTY, fixture.source.takeFrame().scene().electronicWarfare());
            });
        }
    }

    @Test
    void hiddenUnseenAndOtherBoardEmittersDoNotLeakFields() throws Exception {
        try (Options options = new Options(); GpuBoardFixture fixture = GpuBoardFixture.create()) {
            fixture.addEcm();
            SwingUtilities.invokeAndWait(() -> {
                Player enemy = new Player(1, "Opponent");
                enemy.setTeam(2);
                fixture.game.addPlayer(enemy.getId(), enemy);
                fixture.entity.setOwner(enemy);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                fixture.view.updateEcmList();
                assertTrue(empty(fixture.view.electronicWarfare()));
                fixture.entity.addBeenDetectedBy(fixture.player);
                fixture.view.updateEcmList();
                assertFalse(empty(fixture.view.electronicWarfare()), "Shared rules allow detected ECM");
                assertEquals(BoardElectronicWarfare.ENEMY_ECM | BoardElectronicWarfare.JAMMED,
                      fixture.view.electronicWarfare().hex(fixture.entity.getPosition()) & 31);
                fixture.entity.setHidden(true);
                fixture.view.updateEcmList();
                assertTrue(empty(fixture.view.electronicWarfare()));
                fixture.entity.setHidden(false);
                fixture.game.setBoard(1, new Board(16, 17));
                fixture.entity.setBoardId(1);
                fixture.view.updateEcmList();
                assertTrue(empty(fixture.view.electronicWarfare()),
                      "An emitter on another board must not leave a field or source at matching coordinates");
                assertTrue(fixture.view.captureTacticalGeometry().fills().isEmpty());
            });
        }
    }

    private static boolean empty(BoardElectronicWarfare warfare) {
        return warfare.sources().isEmpty() && warfare.hexes().stream().allMatch(hex -> hex == 0);
    }

    private static List<BoardScene.Pixels> raster(BoardScene scene) {
        return scene.tiles().stream().map(BoardScene.Tile::tactical).toList();
    }

    static boolean covers(BoardTactical tactical, Color color, Coords coords, double x, double y) {
        double boardX = coords.getX() * 63 + x;
        double boardY = coords.getY() * 72 + (coords.getX() & 1) * 36 + y;
        return tactical.fills().stream().anyMatch(fill -> fill.argb() == color.getRGB() && fill.shape().contains(boardX, boardY));
    }
}
