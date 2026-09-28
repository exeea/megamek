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
        var preferences = GUIPreferences.getInstance();
        boolean nag = preferences.getNagForMapEdReadme();
        var path = Files.createTempFile("native-editor-", ".board");
        var image = Files.createTempFile("native-editor-", ".png");
        Coords edited = new Coords(4, 4);
        Board initial = Board.createEmptyBoard(8, 8);
        initial.setHex(edited, new Hex(0, new Terrain[] {new Terrain(Terrains.FIELDS, 1)}, null));
        try (var output = Files.newOutputStream(path)) { initial.save(output); }
        preferences.setNagForMapEdReadme(false);
        MockedConstruction<BoardView> constructions = onEdt(() -> mockConstruction(BoardView.class));
        BoardEditorPanel editor = null;
        GpuMapSource source = null;
        try {
            editor = onEdt(() -> {
                var result = new BoardEditorPanel(null);
                result.loadBoard(path.toFile());
                assertFalse(result.hasClassicView());
                return result;
            });
            BoardEditorPanel tools = editor;
            var game = tools.getGame();
            source = onEdt(() -> new GpuMapSource(game, tools.getFrame(), tools));
            GpuMapSource nativeSource = source;
            BoardScene before = nativeSource.takeFrame().scene();
            long generation = nativeSource.takeFrame().boardGeneration();
            nativeSource.adjustEditorElevation(edited, 2, generation);
            onEdt(() -> { nativeSource.endEditorStroke(); return null; });
            onEdt(() -> {
                assertEquals(2, game.getBoard().getHex(edited).getLevel());
                assertEquals(BoardScene.Biome.FIELD, nativeSource.takeFrame().scene().tile(edited).biome());
                assertSame(before.tile(new Coords(0, 0)), nativeSource.takeFrame().scene().tile(new Coords(0, 0)));
                button(tools, "buttonUndo").doClick(0);
                assertEquals(0, game.getBoard().getHex(edited).getLevel());
                button(tools, "buttonRedo").doClick(0);
                assertEquals(2, game.getBoard().getHex(edited).getLevel());
                button(tools, "buttonDeployZone").doClick(0);
                return null;
            });
            nativeSource.paintEditor(edited, 0, generation);
            onEdt(() -> { nativeSource.endEditorStroke(); return null; });
            onEdt(() -> {
                assertTrue(game.getBoard().getHex(edited).containsTerrain(Terrains.DEPLOYMENT_ZONE));
                var previous = nativeSource.takeFrame().scene().tile(edited);
                assertNotNull(previous.tactical());
                var chooser = BoardEditorPanel.class.getDeclaredField("deploymentZoneChooser");
                chooser.setAccessible(true);
                ((JSpinner) chooser.get(tools)).setValue(2);
                nativeSource.refresh();
                var next = nativeSource.takeFrame().scene().tile(edited);
                assertNotEquals(previous.tactical(), next.tactical());
                assertSame(previous.ground(), next.ground());
                assertSame(previous.features(), next.features(), "Changing zone selection only changes tactical artwork");
                // Save/reload the authoritative board, not a render snapshot.
                try (var output = Files.newOutputStream(path)) { game.getBoard().save(output); }
                button(tools, "buttonUndo").doClick(0);
                button(tools, "buttonUndo").doClick(0);
                tools.loadBoard(path.toFile());
                assertNotEquals(generation, nativeSource.takeFrame().boardGeneration());
                assertEquals(2, game.getBoard().getHex(edited).getLevel());
                assertTrue(game.getBoard().getHex(edited).containsTerrain(Terrains.DEPLOYMENT_ZONE));
                var exportFile = BoardEditorPanel.class.getDeclaredField("curFileImage");
                exportFile.setAccessible(true);
                exportFile.set(tools, image.toFile());
                var export = BoardEditorPanel.class.getDeclaredMethod("boardSaveImage");
                export.setAccessible(true);
                export.invoke(tools);
                var savedImage = ImageIO.read(image.toFile());
                assertNotNull(savedImage);
                assertEquals(525, savedImage.getWidth(), "Export covers the entire eight-column board");
                assertEquals(612, savedImage.getHeight(), "Export is independent of native camera and viewport");
                var reviewImage = java.nio.file.Path.of(System.getProperty("megamek.gpu.screenshots",
                      "build/gpu-board-review"), "native-editor-printable.png");
                Files.createDirectories(reviewImage.getParent());
                Files.copy(image, reviewImage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                assertTrue(constructions.constructed().isEmpty(), "No native editor operation may instantiate BoardView");
                assertFalse(tools.hasClassicView());
                return null;
            });
            nativeSource.adjustEditorElevation(edited, 10, generation);
            onEdt(() -> {
                nativeSource.endEditorStroke();
                assertEquals(2, game.getBoard().getHex(edited).getLevel(), "Stale input cannot alter the loaded board");
                nativeSource.close();
                tools.dispose();
                assertTrue(game.getGameListeners().isEmpty(), "The editor session releases its model listeners");
                return null;
            });
        } finally {
            GpuMapSource remainingSource = source;
            BoardEditorPanel remainingEditor = editor;
            onEdt(() -> {
                if (remainingSource != null) { remainingSource.close(); }
                if (remainingEditor != null) { remainingEditor.dispose(); }
                constructions.close();
                return null;
            });
            preferences.setNagForMapEdReadme(nag);
            Files.deleteIfExists(path);
            Files.deleteIfExists(image);
        }
    }

    private static AbstractButton button(BoardEditorPanel editor, String name) throws Exception {
        var field = BoardEditorPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AbstractButton) field.get(editor);
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
