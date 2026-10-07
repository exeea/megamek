/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;

import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;

import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

/** Real tools and model events, with construction of the legacy renderer intercepted for the entire session. */
@Tag("on-demand")
class GpuMapEditorSmokeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingTheEditorDisposesItsHiddenOwnerAndAnyClassicView(boolean classic) throws Exception {
        var preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForMapEdReadme();
        preferences.setNagForMapEdReadme(false);
        AtomicInteger closed = new AtomicInteger();
        try {
            onEdt(() -> {
                var controller = new MegaMekController();
                var editor = new BoardEditorPanel(controller);
                controller.boardEditor = editor;
                try {
                    assertFalse(editor.getFrame().isVisible());
                    editor.getFrame().addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent event) {
                            closed.incrementAndGet();
                        }
                    });
                    editor.boardNew(false);
                    if (classic) {
                        editor.showClassicEditor();
                        editor.getFrame().setVisible(true);
                        assertTrue(editor.hasClassicView());
                    }
                    editor.getFrame().dispatchEvent(new WindowEvent(editor.getFrame(), WindowEvent.WINDOW_CLOSING));
                    assertFalse(editor.getFrame().isDisplayable());
                    assertFalse(editor.hasClassicView());
                    assertNull(controller.boardEditor);
                    assertTrue(editor.getGame().getGameListeners().isEmpty());
                    for (Window owned : editor.getFrame().getOwnedWindows()) {
                        assertFalse(owned.isDisplayable());
                    }
                } finally { editor.dispose(); }
                return null;
            });
            onEdt(() -> null);
            assertEquals(1, closed.get(), "A hidden owner still notifies the main menu when the session closes");
        } finally { preferences.setNagForMapEdReadme(nag); }
    }

    @Test
    void switchingBackFromTheClassicEditorReleasesOnlyItsSubscriptions() throws Exception {
        var preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForMapEdReadme();
        preferences.setNagForMapEdReadme(false);
        try {
            onEdt(() -> {
                var controller = new MegaMekController();
                var editor = new BoardEditorPanel(controller);
                try {
                    editor.boardNew(false);
                    var listeners = java.util.List.copyOf(editor.getGame().getGameListeners());
                    int actions = keyActions(controller);
                    for (int i = 0; i < 2; i++) {
                        editor.showClassicEditor();
                        assertTrue(editor.hasClassicView());
                        assertTrue(keyActions(controller) > actions);
                        editor.enter3DEditor();
                        assertFalse(editor.hasClassicView());
                        assertEquals(listeners, editor.getGame().getGameListeners(), "Disposed views leave no model listeners");
                        assertEquals(actions, keyActions(controller), "Only the closed view's key registrations are removed");
                        editor.leave3DEditor(false);
                    }
                } finally { editor.dispose(); }
                return null;
            });
        } finally { preferences.setNagForMapEdReadme(nag); }
    }

    private static int keyActions(MegaMekController controller) throws Exception {
        var field = MegaMekController.class.getDeclaredField("cmdActionMap");
        field.setAccessible(true);
        var actions = (java.util.Map<?, ?>) field.get(controller);
        return actions.values().stream().mapToInt(value -> ((java.util.List<?>) value).size()).sum();
    }

    @Test
    void nativeEditingLoadingAndTacticalChangesNeverConstructBoardView() throws Exception {
        var path = Files.createTempFile("native-editor-", ".board2");
        onEdt(() -> {
            try (var views = mockConstruction(BoardView.class); var panels = mockConstruction(BoardEditorPanel.class)) {
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
                    assertTrue(views.constructed().isEmpty());
                    assertTrue(panels.constructed().isEmpty(), "Native editing cannot depend on even a hidden classic editor panel");
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
