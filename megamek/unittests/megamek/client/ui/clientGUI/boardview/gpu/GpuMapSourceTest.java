/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuMapSourceTest {
    @Test
    void previewUsesOnlyTheModelAndReleasesItsListeners() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            var listeners = List.copyOf(game.getGameListeners());
            try (var source = new GpuMapSource(game, null, null)) {
                assertEquals(272, source.takeFrame().scene().tiles().size());
                assertTrue(source.takeFrame().scene().units().isEmpty());
                assertTrue(game.getGameListeners().stream().noneMatch(listener ->
                      listener.getClass().getName().contains("BoardView")), "Preview must not construct a BoardView");
                long generation = source.takeFrame().boardGeneration();
                Board oldBoard = game.getBoard();
                game.setBoard(Board.createEmptyBoard(6, 8));
                assertNotEquals(generation, source.takeFrame().boardGeneration());
                assertEquals(48, source.takeFrame().scene().tiles().size());
                oldBoard.setHex(new Coords(4, 4), new Hex(7));
                source.refresh();
                assertEquals(0, source.takeFrame().scene().tile(new Coords(4, 4)).elevation());
            }
            assertEquals(listeners, game.getGameListeners(), "Closing preview must detach every owned game listener");
            return null;
        });
    }

    @Test
    void incrementalEditsRetainDistantTilesAndMatchFreshCapture() throws Exception {
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(17, 16));
            Coords edited = new Coords(8, 8);
            try (var source = new GpuMapSource(game, null, null)) {
                for (Hex replacement : List.of(new Hex(3),
                      new Hex(0, new Terrain[] { new Terrain(Terrains.WATER, 2) }, null),
                      new Hex(-2, new Terrain[] { new Terrain(Terrains.FIELDS, 1) }, null), new Hex(1))) {
                    BoardScene before = source.takeFrame().scene();
                    game.getBoard().setHex(edited, replacement);
                    source.refresh();
                    BoardScene after = source.takeFrame().scene();
                    int retained = 0;
                    for (int i = 0; i < before.tiles().size(); i++) {
                        if (before.tiles().get(i) == after.tiles().get(i)) { retained++; }
                    }
                    assertTrue(retained >= 247, "A local edit must retain distant tile objects: " + retained);
                    try (var fresh = new GpuMapSource(game, null, null)) {
                        assertEquals(fresh.takeFrame().scene().tiles(), after.tiles());
                    }
                    source.refresh();
                    assertSame(after.tiles(), source.takeFrame().scene().tiles(), "Idle capture retains terrain snapshots");
                }
            }
            return null;
        });
    }

    @Test
    void nativeArtworkMatchesTheGameplayCaptureBoundary() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 2.board"));
        try (var fixture = GpuBoardFixture.create(board)) {
            onEdt(() -> {
                try (var source = new GpuMapSource(fixture.game, null, null)) {
                    var expected = fixture.source.takeFrame().scene();
                    var actual = source.takeFrame().scene();
                    assertEquals(expected.width(), actual.width());
                    for (var tile : actual.tiles()) {
                        var previous = expected.tile(tile.coords());
                        assertEquals(previous.ground(), tile.ground());
                        assertEquals(previous.normals(), tile.normals());
                        assertEquals(previous.decals(), tile.decals());
                        assertEquals(previous.features(), tile.features());
                        assertEquals(previous.text(), tile.text());
                    }
                }
                return null;
            });
        }
    }

    /**
     * The map tools' hex card (the user's decision of 2026-10-03): the hex with its level and theme, then the terrains
     * a player sees with their terrain factors; automated and cosmetic terrains and terrain codes stay out.
     */
    @Test
    void theHexCardListsWhatAPlayerSeesWithoutAutomatedTerrains() {
        Board board = Board.createEmptyBoard(3, 3);
        Coords coords = new Coords(1, 2);
        board.setHex(coords, new Hex(1, "woods:1;foliage_elev:2;incline_top:1:28;ground_fluff:1:1", "grass"));
        board.setHex(new Coords(0, 0), new Hex(0));
        assertEquals(List.of("Hex 0203\tLevel 1 \u00B7 grass", "Light woods\tTF 50", "Woods/Jungle elevation: 2\t"),
              List.of(GpuMapSource.hexCard(board.getHex(coords)).split("\n")));
        assertEquals(List.of("Hex 0101\tLevel 0", "Clear\t"),
              List.of(GpuMapSource.hexCard(board.getHex(new Coords(0, 0))).split("\n")));
    }

    /**
     * The preview's line of sight is MegaMek's ruler (the user's decision of 2026-10-03): a Ctrl click starts it at the
     * height the pointer showed, the status line says what it waits for, a plain click ends it and the ruler shows
     * with its line on the board. Closing the preview releases the ruler with the board state it measures on.
     */
    @Test
    void aPreviewMeasuresWithMegaMeksRuler() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows the Swing ruler");
        onEdt(() -> {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(8, 8));
            var listeners = List.copyOf(game.getGameListeners());
            RulerDialog ruler;
            try (var source = new GpuMapSource(game, null, null)) {
                source.measure(new Coords(2, 2), InputEvent.CTRL_DOWN_MASK, 2 * BoardGeometry.level() + .1f);
                assertEquals(Messages.getString("GpuBoard.hud.hint.completeLos"), source.phaseStatus().text());
                source.measure(new Coords(2, 6), 0, Float.NaN);
                assertEquals("", source.phaseStatus().text(), "A plain click ends the measurement");
                var field = GpuMapSource.class.getDeclaredField("ruler");
                field.setAccessible(true);
                ruler = (RulerDialog) field.get(source);
                assertTrue(ruler.isVisible(), "The ruler shows the measurement");
                assertEquals(2, GpuDialogRoutingTest.components(ruler, JSpinner.class).getFirst().getValue(),
                      "from the floor two levels up that the pointer showed");
                assertNotEquals(BoardTactical.EMPTY, source.takeFrame().scene().tactical(),
                      "with its line on the board");
                ruler.setHeight(new Coords(2, 6), 4);
                source.refresh();
                assertEquals(4, source.takeFrame().scene().tactical().ruler().endHeight(),
                      "Changing only a height must invalidate the preview's captured measurement");
                source.measure(new Coords(3, 3), 0, Float.NaN);
                assertEquals("", source.phaseStatus().text(), "A plain click alone measures nothing");
            }
            assertFalse(ruler.isDisplayable(), "Closing the preview disposes of its ruler");
            assertEquals(listeners, game.getGameListeners(), "and of the board state it measured on");
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
