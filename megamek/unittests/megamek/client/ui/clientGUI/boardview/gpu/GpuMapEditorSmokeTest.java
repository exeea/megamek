/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real tools and model events, with construction of the legacy renderer intercepted for the entire session. */
@Tag("on-demand")
class GpuMapEditorSmokeTest {
    @Test
    void nativeEditingLoadingAndTacticalChangesNeverConstructBoardView() throws Exception {
        var path = Files.createTempFile("native-editor-", ".board2");
        onEdt(() -> {
            try (var views = mockConstruction(BoardView.class)) {
                var editor = new megamek.client.ui.boardeditor.BoardEditorSession();
                var game = editor.game();
                Coords edited = new Coords(4, 4);
                try (var source = new GpuMapSource(game, null, editor)) {
                    long generation = source.takeFrame().boardGeneration();
                    var before = source.takeFrame().scene();
                    source.editorPointer(edited, 0, 0, false, generation);
                    source.endEditorStroke();
                    source.editorCommand(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                          megamek.client.ui.boardeditor.BoardEditorSession.Action.ELEVATION, "2"), generation);
                    assertEquals(2, game.getBoard().getHex(edited).getLevel());
                    assertSame(before.tile(new Coords(0, 0)), source.takeFrame().scene().tile(new Coords(0, 0)));
                    source.editorCommand(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                          megamek.client.ui.boardeditor.BoardEditorSession.Action.UNDO), generation);
                    assertEquals(0, game.getBoard().getHex(edited).getLevel());
                    source.editorCommand(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                          megamek.client.ui.boardeditor.BoardEditorSession.Action.REDO), generation);
                    editor.save(path);
                    editor.open(path);
                    source.refresh();
                    assertNotEquals(generation, source.takeFrame().boardGeneration());
                    source.editorPointer(edited, 0, 0, false, generation);
                    source.editorCommand(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                          megamek.client.ui.boardeditor.BoardEditorSession.Action.ELEVATION, "20"), generation);
                    assertEquals(2, game.getBoard().getHex(edited).getLevel(), "Old render input cannot edit a replacement document");
                    assertTrue(views.constructed().isEmpty(), "Native editing never builds the 2D renderer");
                }
                assertTrue(game.getGameListeners().isEmpty());
            }
            return null;
        });
        Files.deleteIfExists(path);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
