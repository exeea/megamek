/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardTacticalGraphics;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
import megamek.client.ui.clientGUI.boardview.sprite.CursorSprite;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Test;

class GpuOverlayCaptureTest {
    private static final Coords EDITED = new Coords(0, 0), RETAINED = new Coords(0, 2);
    private static final Color RETAINED_COLOR = new Color(31, 107, 193, 91);

    @Test
    void deploymentBordersRemainFloatingAtEveryClippedBoardEdge() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                GUIPreferences preferences = GUIPreferences.getInstance();
                boolean sheets = preferences.getShowMapSheets();
                var reference = new BoardTacticalGraphics();
                try {
                    preferences.setShowMapSheets(false);
                    fixture.player.setStartingPos(Board.START_ANY);
                    fixture.entity.setDeployed(false);
                    fixture.game.setPhase(GamePhase.DEPLOYMENT);
                    fixture.view.markDeploymentHexesFor(fixture.entity);
                    BoardTactical capture = fixture.view.captureTacticalGeometry();
                    List<BoardTactical.Fill> borders = capture.fills().stream()
                          .filter(fill -> fill.argb() == Color.YELLOW.getRGB()).toList();
                    assertEquals(7 * 6, borders.size(), "The real deployment painter covers the whole legal board");
                    reference.setClip(0, 0, 7 * 63 + 84, 6 * 72 + 72);
                    reference.setColor(Color.YELLOW);
                    for (int x = 0; x < 7; x++) {
                        for (int y = 0; y < 6; y++) {
                            BoardTactical.Fill border = borders.get(x * 6 + y);
                            assertNotNull(border.border(), "Deployment metadata at " + x + "," + y);
                            assertTrue(border.border().floating(), "Deployment stays flat, including column/row zero");
                            assertTrue(border.border().zone(), "Only the selected deployer's legal hexes form a zone");
                            assertEquals(new BoardTactical.Point(x * 63 + 42, y * 72 + (x & 1) * 36 + 36),
                                  border.border().anchor());
                            var shape = AffineTransform.getTranslateInstance(x * 63, y * 72 + (x & 1) * 36)
                                  .createTransformedShape(HexDrawUtilities.getHexFullBorderArea(1, 0));
                            BoardTacticalGraphics.draw(reference, BoardTactical.Playback.HIDE_DURING_MOVEMENT,
                                  graphics -> graphics.fill(shape));
                        }
                    }
                    assertEquals(reference.snapshot().fills(), borders.stream().map(fill ->
                          new BoardTactical.Fill(fill.contours(), fill.winding(), fill.argb(), fill.playback())).toList(),
                          "Floating metadata must preserve every original clipped XY contour and playback rule");
                    assertFresh(fixture.view, capture);
                    fixture.view.markDeploymentHexesFor(null);
                    fixture.view.showAllDeployment = true;
                    fixture.game.setPhase(GamePhase.SET_ARTILLERY_AUTO_HIT_HEXES);
                    BoardTactical allPlayers = fixture.view.captureTacticalGeometry();
                    assertEquals(7 * 6, allPlayers.fills().size());
                    for (var fill : allPlayers.fills()) {
                        assertEquals(fixture.player.getColour().getColour().getRGB(), fill.argb());
                        assertNotNull(fill.border());
                        assertTrue(fill.border().floating());
                        assertFalse(fill.border().zone(), "All-player deployment borders must remain individually styled");
                    }
                } finally {
                    reference.dispose();
                    preferences.setShowMapSheets(sheets);
                }
            });
        }
    }

    @Test
    void coverageReusesUntouchedCommandsAndMatchesFreshCaptureAfterEveryInputChange() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                GUIPreferences preferences = GUIPreferences.getInstance();
                boolean sheets = preferences.getShowMapSheets();
                Color sheetColor = preferences.getMapsheetColor();
                try {
                    preferences.setShowMapSheets(false);
                    Map<String, Map<Coords, Color>> fields = new LinkedHashMap<>();
                    for (String name : List.of("ecmHexes", "eccmHexes", "ecmCenters", "eccmCenters")) {
                        Map<Coords, Color> colors = new HashMap<>();
                        colors.put(EDITED, new Color(120, 40 + fields.size() * 25, 60, 83));
                        fields.put(name, colors);
                        setField(fixture.view, name, colors);
                    }
                    fields.get("ecmHexes").put(RETAINED, RETAINED_COLOR);
                    BoardTactical before = fixture.view.captureTacticalGeometry();
                    BoardTactical repeated = fixture.view.captureTacticalGeometry();
                    assertEquals(before, repeated);
                    assertSame(before, repeated, "An unchanged immutable snapshot must reach the renderer by identity");
                    assertSame(fill(before, RETAINED_COLOR), fill(repeated, RETAINED_COLOR),
                          "Unchanged coverage must reuse its immutable drawing commands");

                    for (var entry : fields.entrySet()) {
                        before = fixture.view.captureTacticalGeometry();
                        entry.getValue().put(EDITED, new Color(200, 110, 35, 109));
                        BoardTactical recolored = fixture.view.captureTacticalGeometry();
                        assertNotEquals(before, recolored, entry.getKey() + " recoloring must be visible");
                        assertSame(fill(before, RETAINED_COLOR), fill(recolored, RETAINED_COLOR));
                        assertFresh(fixture.view, recolored);
                        entry.getValue().remove(EDITED);
                        BoardTactical removed = fixture.view.captureTacticalGeometry();
                        assertNotEquals(recolored, removed, entry.getKey() + " removal must be visible");
                        assertFresh(fixture.view, removed);
                    }

                    BoardTactical unembedded = fixture.view.captureTacticalGeometry();
                    fixture.game.getBoard().setEmbeddedBoard(42, EDITED);
                    BoardTactical embedded = fixture.view.captureTacticalGeometry();
                    assertNotEquals(unembedded, embedded);
                    assertSame(fill(unembedded, RETAINED_COLOR), fill(embedded, RETAINED_COLOR));
                    assertFresh(fixture.view, embedded);
                    fixture.game.getBoard().embeddedBoardCoords().remove(EDITED);
                    assertEquals(unembedded, fixture.view.captureTacticalGeometry());
                    assertFresh(fixture.view, unembedded);

                    Color firstSheet = new Color(43, 219, 117), nextSheet = new Color(231, 67, 152);
                    preferences.setMapSheetColor(firstSheet);
                    preferences.setShowMapSheets(true);
                    BoardTactical withSheets = fixture.view.captureTacticalGeometry();
                    assertTrue(withSheets.fills().indexOf(fill(withSheets, RETAINED_COLOR))
                          < withSheets.fills().indexOf(fill(withSheets, firstSheet)),
                          "All coverage precedes map-sheet borders, including overlapping edge hexes");
                    assertFresh(fixture.view, withSheets);
                    preferences.setMapSheetColor(nextSheet);
                    BoardTactical recoloredSheets = fixture.view.captureTacticalGeometry();
                    assertNotEquals(withSheets, recoloredSheets);
                    assertFresh(fixture.view, recoloredSheets);
                    preferences.setShowMapSheets(false);
                    assertEquals(unembedded, fixture.view.captureTacticalGeometry());
                    assertFresh(fixture.view, unembedded);

                    fixture.game.getBoard().setEmbeddedBoard(42, EDITED);
                    fixture.view.captureTacticalGeometry();
                    fixture.game.setBoardDirect(board());
                    BoardTactical replacement = fixture.view.captureTacticalGeometry();
                    assertEquals(unembedded, replacement, "A same-sized replacement must not retain embedded markers");
                    assertFresh(fixture.view, replacement);
                } finally {
                    preferences.setShowMapSheets(sheets);
                    preferences.setMapSheetColor(sheetColor);
                }
            });
        }
    }

    @Test
    void liveSpriteVisibilityAndColorRemainLiveWithoutARepaintInvalidation() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                Map<Coords, Color> coverage = new HashMap<>(Map.of(RETAINED, RETAINED_COLOR));
                setField(fixture.view, "ecmHexes", coverage);
                CursorSprite cursor = new CursorSprite(fixture.view, Color.MAGENTA);
                cursor.setHexLocation(new Coords(3, 3));
                fixture.view.addSprites(List.of(cursor));
                BoardTactical shown = fixture.view.captureTacticalGeometry();
                assertEquals(BoardTactical.Playback.LIVE, fill(shown, Color.MAGENTA).playback());
                assertSame(shown, fixture.view.captureTacticalGeometry(),
                      "New but equal live-sprite commands must also reuse the complete snapshot");
                long revision = fixture.view.getPlanarRevision();
                cursor.setHidden(true);
                BoardTactical hidden = fixture.view.captureTacticalGeometry();
                assertEquals(revision, fixture.view.getPlanarRevision());
                assertFalse(hidden.fills().stream().anyMatch(fill -> fill.argb() == Color.MAGENTA.getRGB()));
                assertSame(fill(shown, RETAINED_COLOR), fill(hidden, RETAINED_COLOR));
                assertFresh(fixture.view, hidden);
                cursor.setColor(Color.ORANGE);
                cursor.setHidden(false);
                BoardTactical recolored = fixture.view.captureTacticalGeometry();
                assertEquals(BoardTactical.Playback.LIVE, fill(recolored, Color.ORANGE).playback());
                assertFalse(recolored.fills().stream().anyMatch(fill -> fill.argb() == Color.MAGENTA.getRGB()));
                assertFresh(fixture.view, recolored);
            });
        }
    }

    private static BoardTactical.Fill fill(BoardTactical tactical, Color color) {
        return tactical.fills().stream().filter(fill -> fill.argb() == color.getRGB()).findFirst().orElseThrow();
    }

    private static void assertFresh(BoardView view, BoardTactical incremental) {
        view.releasePlanarCapture();
        BoardTactical fresh = view.captureTacticalGeometry();
        assertNotSame(incremental, fresh, "Release must discard the previously published snapshot");
        assertEquals(incremental, fresh, "Reused commands must match an uncached capture");
    }

    private static void setField(BoardView view, String name, Object value) {
        try {
            Field field = BoardView.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(view, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Board board() {
        Hex[] hexes = new Hex[7 * 6];
        Arrays.setAll(hexes, index -> new Hex(0));
        Board board = new Board();
        board.newData(7, 6, hexes, null);
        return board;
    }
}
