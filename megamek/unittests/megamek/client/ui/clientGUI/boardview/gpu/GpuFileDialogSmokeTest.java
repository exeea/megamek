/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.RecentBoardList;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiTestStage;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real map HUD, source command routing and Tuning controls, with the picker in their actual stage. */
@Tag("on-demand")
class GpuFileDialogSmokeTest {
    @TempDir Path directory;

    @Test void editorOpenAndTuningPickerStayAboveTheSceneAndOwnInput() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        try {
            UiTestStage.run(ui -> {
                var skin = new GpuBoardSkin();
                var camera = new BoardCamera();
                var model = new GpuBoardTuning(skin.skin, source, camera);
                var hud = new GpuMapHud(source, skin.skin, ui.stage.getBatch(), camera, model,
                      new GpuPlaybackHistory(new UnitPlayback()), () -> null);
                try {
                    hud.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 1);
                    frame(hud, source);
                    Actor open = findText(hud.stage().getRoot(), "OPEN");
                    assertNotNull(open);
                    open.fire(new ChangeEvent());
                    await(hud, source, () -> hud.stage().getRoot().findActor("file-dialog") != null);
                    await(hud, source, () -> !((TextButton) hud.stage().getRoot().findActor("file-accept")).isDisabled());
                    assertTrue(hud.isModal());
                    assertTrue(hud.keyDown(Input.Keys.C, KeyEvent.VK_C, 0), "Tool keys stay in the picker");
                    assertSame(UiModal.active(hud.stage()), hud.stage().hit(2, 2, true));
                    GpuBoardTestUi.capture(new java.io.File(UiTestStage.OUTPUT, "file-picker-editor.png"));
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    hud.keyUp(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                    await(hud, source, () -> source.editorFilePrompt() == null);

                    source.key(KeyEvent.VK_O, true, InputEvent.CTRL_DOWN_MASK);
                    await(hud, source, () -> hud.stage().getRoot().findActor("file-dialog") != null);
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    await(hud, source, () -> source.editorFilePrompt() == null);

                    hud.stage().getRoot().findActor("tuning-button").fire(new ChangeEvent());
                    frame(hud, source);
                    hud.stage().getRoot().findActor("tuning-atmosphere-tab").fire(new ChangeEvent());
                    frame(hud, source);
                    hud.stage().getRoot().findActor("tuning-environment-image").fire(new ChangeEvent());
                    await(hud, source, () -> !((TextButton) hud.stage().getRoot().findActor("file-accept")).isDisabled());
                    for (int index = 0; index < 5; index++) { frame(hud, source); }
                    assertNotNull(UiModal.active(hud.stage()));
                    assertTrue(hud.isModal());
                    assertSame(UiModal.active(hud.stage()), hud.stage().getActors().peek(),
                          "Tuning must not raise itself over its picker on later frames");
                    GpuBoardTestUi.capture(new java.io.File(UiTestStage.OUTPUT, "file-picker-tuning.png"));
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    assertNull(UiModal.active(hud.stage()));
                    assertFalse(hud.isModal());
                    assertNull(model.atmosphereOptions().environmentImage(), "Cancel leaves lighting unchanged");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } finally { hud.dispose(); skin.dispose(); }
            });
        } finally { SwingUtilities.invokeAndWait(source::close); }
    }

    @Test void editorSaveAndSaveAsWriteBoardsThroughTheFullscreenPicker() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("Saved maps"));
        Path saved = folder.resolve("First board.board2");
        Path copy = folder.resolve("Copy.board2");
        BoardFile.save(Board.createEmptyBoard(2, 3), copy);
        byte[] originalCopy = Files.readAllBytes(copy);
        BoardEditorSession editor = edt(BoardEditorSession::new);
        GpuMapSource source = edt(() -> {
            editor.board().addTag("first save");
            return new GpuMapSource(editor.game(), null, editor);
        });
        // The editor saves on the EDT. Its Recent Boards side effect is isolated on that same thread.
        var recent = edt(() -> mockStatic(RecentBoardList.class));
        try {
            UiTestStage.run(ui -> {
                // Exercise the real HUD without changing the player's remembered directories.
                var locations = new ConcurrentHashMap<String, String>();
                locations.put("boards", directory.toString());
                Preferences folders = mock(Preferences.class);
                when(folders.get(anyString(), anyString())).thenAnswer(call ->
                      locations.getOrDefault(call.getArgument(0), call.getArgument(1)));
                doAnswer(call -> { locations.put(call.getArgument(0), call.getArgument(1)); return null; })
                      .when(folders).put(anyString(), anyString());
                try (var preferences = mockStatic(Preferences.class)) {
                    preferences.when(() -> Preferences.userNodeForPackage(UiFileDialog.class)).thenReturn(folders);
                    var skin = new GpuBoardSkin();
                    var camera = new BoardCamera();
                    var model = new GpuBoardTuning(skin.skin, source, camera);
                    var hud = new GpuMapHud(source, skin.skin, ui.stage.getBatch(), camera, model,
                          new GpuPlaybackHistory(new UnitPlayback()), () -> null);
                    boolean fullscreen = false;
                    try {
                        fullscreen = Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
                        assertTrue(fullscreen, "This smoke test requires a desktop that can enter fullscreen");
                        hud.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 1);
                        frame(hud, source);
                        findText(hud.stage().getRoot(), "SAVE").fire(new ChangeEvent());
                        awaitPicker(hud, source);
                        assertEquals(UiFileDialog.Mode.SAVE, source.editorFilePrompt().file().mode());
                        assertEquals("SAVE", ((TextButton) hud.stage().getRoot().findActor("file-accept")).getText().toString());
                        ((TextField) hud.stage().getRoot().findActor("file-location")).setText(folder.toString());
                        hud.stage().getRoot().findActor("file-go").fire(new ChangeEvent());
                        awaitPicker(hud, source);
                        ((TextField) hud.stage().getRoot().findActor("file-name")).setText("First board");
                        frame(hud, source);
                        GpuBoardTestUi.capture(new java.io.File(UiTestStage.OUTPUT, "file-picker-editor-save.png"));
                        hud.stage().getRoot().findActor("file-accept").fire(new ChangeEvent());
                        await(hud, source, () -> source.editorFilePrompt() == null);
                        edt(() -> {
                            assertEquals(saved, editor.path());
                            assertFalse(editor.dirty());
                            assertTrue(BoardFile.read(saved).getTags().contains("first save"));
                            editor.board().addTag("saved again");
                            return null;
                        });
                        assertEquals(folder.toString(), locations.get("boards"));

                        // Once named, the ordinary Save button writes the current file without another picker.
                        findText(hud.stage().getRoot(), "SAVE").fire(new ChangeEvent());
                        edt(() -> {
                            assertNull(source.editorFilePrompt());
                            assertFalse(editor.dirty());
                            assertTrue(BoardFile.read(saved).getTags().contains("saved again"));
                            return null;
                        });

                        findText(hud.stage().getRoot(), "SAVE AS").fire(new ChangeEvent());
                        awaitPicker(hud, source);
                        assertEquals(folder.toString(), ((TextField) hud.stage().getRoot().findActor("file-location")).getText());
                        assertEquals(saved.getFileName().toString(), ((TextField) hud.stage().getRoot().findActor("file-name")).getText());
                        ((TextField) hud.stage().getRoot().findActor("file-name")).setText("Copy");
                        hud.stage().getRoot().findActor("file-accept").fire(new ChangeEvent());
                        await(hud, source, () -> hud.stage().getRoot().findActor("message-dialog") != null);
                        hud.stage().getRoot().findActor("message-answer-1").fire(new ChangeEvent());
                        assertNotNull(hud.stage().getRoot().findActor("file-dialog"));
                        assertArrayEquals(originalCopy, Files.readAllBytes(copy), "Cancel must not overwrite the other board");
                        edt(() -> { assertEquals(saved, editor.path()); return null; });

                        hud.stage().getRoot().findActor("file-accept").fire(new ChangeEvent());
                        await(hud, source, () -> hud.stage().getRoot().findActor("message-dialog") != null);
                        hud.stage().getRoot().findActor("message-answer-0").fire(new ChangeEvent());
                        await(hud, source, () -> source.editorFilePrompt() == null);
                        edt(() -> {
                            assertEquals(copy, editor.path());
                            assertFalse(editor.dirty());
                            assertTrue(BoardFile.read(copy).getTags().contains("saved again"));
                            editor.board().addTag("keyboard save");
                            return null;
                        });
                        source.key(KeyEvent.VK_S, true, InputEvent.CTRL_DOWN_MASK);
                        edt(() -> {
                            assertNull(source.editorFilePrompt());
                            assertFalse(editor.dirty());
                            assertTrue(BoardFile.read(copy).getTags().contains("keyboard save"));
                            assertFalse(BoardFile.read(saved).getTags().contains("keyboard save"));
                            return null;
                        });
                    } finally {
                        if (fullscreen) { Gdx.graphics.setWindowedMode(1920, 1080); }
                        hud.dispose(); skin.dispose();
                    }
                }
            });
        } finally { edt(() -> { source.close(); recent.close(); return null; }); }
    }

    private static void awaitPicker(GpuMapHud hud, GpuMapSource source) throws InterruptedException {
        await(hud, source, () -> hud.stage().getRoot().findActor("file-accept") instanceof TextButton accept && !accept.isDisabled());
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private static void frame(GpuMapHud hud, GpuMapSource source) {
        hud.update(source.takeFrame(), GpuHud.HudView.EMPTY, null, source.uiPreferences());
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        hud.draw();
    }

    private static void await(GpuMapHud hud, GpuMapSource source, BooleanSupplier ready) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        do { frame(hud, source); if (ready.getAsBoolean()) { return; } Thread.sleep(5); }
        while (System.nanoTime() < until);
        fail("The in-scene file prompt did not respond");
    }

    private static Actor findText(Actor actor, String text) {
        if (actor instanceof TextButton button && button.getText().toString().equalsIgnoreCase(text)) { return actor; }
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group group) {
            for (Actor child : group.getChildren()) { Actor found = findText(child, text); if (found != null) { return found; } }
        }
        return null;
    }
}
