/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Tool;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GpuMapSourceTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void viewCommandsUseSharedPreferencesWithoutChangingTheDocument(boolean editing) throws Exception {
        onEdt(() -> {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean coordinates = preferences.getCoordsEnabled();
            float scale = preferences.getGUIScale();
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(4, 4));
            boolean dirty = editor != null && editor.dirty();
            try {
                preferences.setValue(GUIPreferences.SHOW_COORDS, true);
                preferences.setValue(GUIPreferences.GUI_SCALE, 1.0);
                BoardScene.Command stale;
                try (var source = new GpuMapSource(game, null, editor);
                      var other = new GpuMapSource(game, null, null)) {
                    long generation = source.takeFrame().boardGeneration();
                    Coords at = new Coords(2, 2);
                    assertTrue(source.takeFrame().scene().tile(at).text().stream()
                          .anyMatch(label -> label.text().equals(at.getBoardNum())));
                    stale = viewCommand(source, ClientGUI.VIEW_TOGGLE_HEX_COORDS);
                    assertEquals(Boolean.TRUE, stale.selected());
                    assertEquals(editing ? "" : KeyCommandBind.getDesc(KeyCommandBind.HEX_COORDS), stale.shortcut());
                    stale.action().run();
                    source.refresh(); other.refresh();
                    assertFalse(preferences.getCoordsEnabled());
                    for (var workspace : List.of(source, other)) {
                        assertEquals(Boolean.FALSE, viewCommand(workspace, ClientGUI.VIEW_TOGGLE_HEX_COORDS).selected());
                        assertTrue(workspace.takeFrame().scene().tile(at).text().stream()
                              .noneMatch(label -> label.text().equals(at.getBoardNum())), "The toggle removes rendered coordinate labels");
                    }
                    source.key(KeyCommandBind.HEX_COORDS.key, true, KeyCommandBind.HEX_COORDS.modifiers);
                    assertEquals(!editing, preferences.getCoordsEnabled(), "The editor keeps Ctrl+G for Group");
                    source.key(KeyCommandBind.LOS_SETTING.key, true, KeyCommandBind.LOS_SETTING.modifiers);
                    assertTrue(source.takeFrame().panels().los().open());
                    viewCommand(source, ClientGUI.VIEW_INC_GUI_SCALE).action().run();
                    assertEquals(1.1f, source.uiPreferences().scale(), .001f);
                    source.key(KeyCommandBind.DEC_GUI_SCALE.key, true, KeyCommandBind.DEC_GUI_SCALE.modifiers);
                    assertEquals(1f, source.uiPreferences().scale(), .001f);
                    assertEquals(generation, source.takeFrame().boardGeneration());
                    if (editor != null) {
                        assertEquals(dirty, editor.dirty());
                        assertFalse(editor.snapshot().canUndo(), "View commands do not edit the board");
                    }
                }
                boolean before = preferences.getCoordsEnabled();
                stale.action().run();
                assertEquals(before, preferences.getCoordsEnabled(), "A closed workspace cannot toggle shared preferences");
            } finally {
                preferences.setValue(GUIPreferences.SHOW_COORDS, coordinates);
                preferences.setValue(GUIPreferences.GUI_SCALE, scale);
            }
            return null;
        });
    }

    private static BoardScene.Command viewCommand(GpuMapSource source, String id) {
        return source.takeFrame().globalCommands().stream().filter(command -> command.id().equals("view"))
              .flatMap(command -> command.children().stream()).filter(command -> command.id().equals(id))
              .findFirst().orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(Tool.class)
    void elevationWheelChangesOnlyHoveredHexInEveryTool(Tool tool) throws Exception {
        Coords center = new Coords(4, 4);
        for (int delta : new int[] { -3, 3 }) {
            var editor = onEdt(() -> {
                var session = new BoardEditorSession();
                session.game().setBoard(Board.createEmptyBoard(9, 9));
                session.command(new Command(Action.TOOL, "", "SCULPT"), null);
                session.command(new Command(Action.BRUSH, "", "2"), null);
                return session;
            });
            var source = onEdt(() -> new GpuMapSource(editor.game(), null, editor));
            try {
                onEdt(() -> {
                    long generation = source.takeFrame().boardGeneration();
                    source.editorCommand(new Command(Action.TOOL, "", tool.name()), generation);
                    source.adjustEditorElevation(center, delta, generation);
                    return null;
                });
                // The wheel input is queued on Swing; finish after it has been accepted.
                onEdt(() -> {
                    source.endEditorStroke();
                    for (int x = 0; x < 9; x++) {
                        for (int y = 0; y < 9; y++) {
                            Coords at = new Coords(x, y);
                            int expected = at.equals(center) ? delta : 0;
                            assertEquals(expected, editor.board().getHex(at).getLevel(), tool + " at " + at);
                        }
                    }
                    source.editorCommand(new Command(Action.UNDO, "", ""), source.takeFrame().boardGeneration());
                    for (int x = 0; x < 9; x++) {
                        for (int y = 0; y < 9; y++) { assertEquals(0, editor.board().getHex(x, y).getLevel()); }
                    }
                    assertFalse(editor.snapshot().canUndo(), "A wheel gesture is one undo step");
                    return null;
                });
            } finally {
                onEdt(() -> { source.close(); return null; });
            }
        }
    }

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

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void mapToolsUseAltClickAndPublishNativeMeasurementWithoutSwingWindows(boolean editing) throws Exception {
        onEdt(() -> {
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            game.setBoard(Board.createEmptyBoard(8, 8));
            var listeners = List.copyOf(game.getGameListeners());
            var windows = java.util.Set.of(java.awt.Window.getWindows());
            try (var source = new GpuMapSource(game, null, editor)) {
                source.measure(new Coords(2, 2), InputEvent.CTRL_DOWN_MASK, Float.NaN);
                assertFalse(source.takeFrame().panels().los().open(), "Ctrl-click is no longer a measurement");
                source.measure(new Coords(2, 2), InputEvent.ALT_DOWN_MASK, 2 * BoardGeometry.level() + .1f);
                assertEquals(InputEvent.ALT_DOWN_MASK, source.takeFrame().panels().los().pending());
                source.measure(new Coords(2, 6), 0, Float.NaN);
                var frame = source.takeFrame();
                assertEquals(0, frame.panels().los().pending());
                assertEquals(2, frame.panels().los().start().height());
                assertEquals(4, frame.panels().los().distance());
                assertEquals(frame.panels().los().ruler(), frame.scene().tactical().ruler());
                source.changeRuler(frame.boardGeneration(), model -> model.height(false, 4));
                assertEquals(4, source.takeFrame().scene().tactical().ruler().endHeight());
                source.measure(new Coords(3, 3), 0, Float.NaN);
                assertEquals(frame.panels().los().end().coords(), source.takeFrame().panels().los().end().coords());
                game.setBoard(Board.createEmptyBoard(8, 8));
                source.refresh();
                source.changeRuler(frame.boardGeneration(), model -> model.open());
                assertFalse(source.takeFrame().panels().los().open(), "An old panel cannot edit a replacement board");
                assertEquals(windows, java.util.Set.of(java.awt.Window.getWindows()), "Native LOS creates no Swing window");
            }
            assertEquals(listeners, game.getGameListeners());
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
