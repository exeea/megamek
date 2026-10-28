/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.prefs.AbstractPreferences;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("on-demand")
class UiFileDialogSmokeTest {
    @TempDir Path directory;

    @Test void nativeStageNavigationValidationPersistenceAndCancellation() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("Maps and scenarios"));
        Path map = Files.writeString(folder.resolve("Árvíz 東京.BOARD2"), "board");
        Files.writeString(folder.resolve("readme.txt"), "not a board");
        Files.writeString(folder.resolve("other.board"), "board");
        UiTestStage.run(ui -> {
            var preferences = new MemoryPreferences();
            AtomicReference<Path> result = new AtomicReference<>();
            AtomicInteger callbacks = new AtomicInteger();
            TextField previous = new TextField("previous focus", ui.kit.skin, "hud");
            ui.stage.addActor(previous);
            ui.stage.setKeyboardFocus(previous);
            try (UiFileDialog picker = new UiFileDialog(ui.kit, preferences)) {
                picker.show(ui.stage, options(UiFileDialog.Mode.OPEN, "boards"), file -> { result.set(file); callbacks.incrementAndGet(); });
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertNotNull(UiModal.active(ui.stage));
                assertSame(UiModal.active(ui.stage), ui.stage.hit(2, 2, true), "The entire viewport blocks board clicks");
                ui.draw();
                doubleClickFirstEntry(ui.stage);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(folder.toString(), field(ui.stage, "file-location").getText());
                button(ui.stage, "file-back").fire(new ChangeEvent());
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(directory.toString(), field(ui.stage, "file-location").getText());
                field(ui.stage, "file-location").setText(folder.toString());
                ui.stage.setKeyboardFocus(field(ui.stage, "file-location"));
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(folder.toString(), preferences.get("boards", ""));
                assertEquals(2, list(ui.stage).getItems().size);
                ui.draw();
                ui.capture("file-picker-open").dispose();

                field(ui.stage, "file-name").setText("missing.board2");
                ui.stage.setKeyboardFocus(field(ui.stage, "file-name"));
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertTrue(picker.isOpen());
                assertTrue(((Label) ui.stage.getRoot().findActor("file-status")).getText().toString().contains("missing.board2"));
                assertEquals(0, callbacks.get());

                field(ui.stage, "file-search").setText("東京");
                assertEquals(1, list(ui.stage).getItems().size);
                ui.stage.setKeyboardFocus(list(ui.stage));
                list(ui.stage).setSelectedIndex(0);
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !picker.isOpen());
                assertEquals(map, result.get());
                assertEquals(1, callbacks.get());
                assertSame(previous, ui.stage.getKeyboardFocus());

                picker.show(ui.stage, options(UiFileDialog.Mode.OPEN, "boards"), file -> callbacks.incrementAndGet());
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(folder.toString(), field(ui.stage, "file-location").getText());
                key(ui.stage, Input.Keys.ESCAPE);
                assertEquals(2, callbacks.get());
                assertFalse(picker.isOpen());
                // Reuse with a different purpose does not inherit the board folder.
                picker.show(ui.stage, options(UiFileDialog.Mode.OPEN, "panoramas"), file -> callbacks.incrementAndGet());
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(directory.toString(), field(ui.stage, "file-location").getText());
                key(ui.stage, Input.Keys.ESCAPE);
                assertEquals(3, callbacks.get());
            }
            try (UiFileDialog reopened = new UiFileDialog(ui.kit, preferences)) {
                reopened.show(ui.stage, options(UiFileDialog.Mode.OPEN, "boards"), file -> { });
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(folder.toString(), field(ui.stage, "file-location").getText(), "A new picker restores the saved preference");
            }
            assertNull(UiModal.active(ui.stage));
        });
    }

    @Test void saveConfirmsOverwriteAndFitsSmallAndFullscreenViewports() throws Exception {
        Path existing = Files.writeString(directory.resolve("existing.board2"), "unchanged");
        UiTestStage.run(ui -> {
            AtomicReference<Path> result = new AtomicReference<>();
            try (UiFileDialog picker = new UiFileDialog(ui.kit, new MemoryPreferences())) {
                picker.show(ui.stage, options(UiFileDialog.Mode.SAVE, "save"), result::set);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                field(ui.stage, "file-name").setText("existing");
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> ui.stage.getRoot().findActor("message-dialog") != null);
                ui.draw(); ui.capture("file-picker-overwrite").dispose();
                ui.stage.setKeyboardFocus(null); // Alt-tab / window focus loss.
                UiModal.active(ui.stage).focus();
                key(ui.stage, Input.Keys.TAB);
                assertTrue(ui.stage.getKeyboardFocus().getName().startsWith("message-answer-"));
                key(ui.stage, Input.Keys.ESCAPE);
                assertTrue(picker.isOpen());
                assertNull(result.get());
                assertEquals("unchanged", Files.readString(existing));

                // The picker uses stage units, so window sizes and UI scaling share the same layout path.
                ui.stage.getViewport().setWorldSize(720, 480);
                ui.draw();
                Actor card = ui.stage.getRoot().findActor("file-dialog-card");
                assertTrue(card.getWidth() <= 688 && card.getHeight() <= 448);
                assertTrue(field(ui.stage, "file-name").getWidth() > 100);
                assertTrue(button(ui.stage, "file-accept").getStage() == ui.stage);
                ui.capture("file-picker-small").dispose();
                ui.stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
                boolean fullscreen = Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
                try {
                    assertTrue(fullscreen, "This smoke test requires a desktop that can enter fullscreen");
                    if (fullscreen) {
                        ui.stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
                        ui.draw(); ui.capture("file-picker-fullscreen").dispose();
                        assertTrue(picker.isOpen());
                        assertSame(UiModal.active(ui.stage), ui.stage.hit(2, 2, true));
                    }
                    ui.stage.setKeyboardFocus(field(ui.stage, "file-name"));
                    key(ui.stage, Input.Keys.ENTER);
                    await(ui.stage, () -> ui.stage.getRoot().findActor("message-dialog") != null);
                    ui.stage.setKeyboardFocus(ui.stage.getRoot().findActor("message-answer-0"));
                    key(ui.stage, Input.Keys.ENTER);
                    assertEquals(existing, result.get());
                    assertFalse(picker.isOpen());
                    assertEquals("unchanged", Files.readString(existing), "The picker returns a path; only its caller writes");
                } finally { if (fullscreen) { Gdx.graphics.setWindowedMode(1920, 1080); } }
            }
        });
    }

    @Test void tabNavigationDoesNotInsertControlCharactersIntoPaths() throws Exception {
        UiTestStage.run(ui -> {
            try (UiFileDialog picker = new UiFileDialog(ui.kit, new MemoryPreferences())) {
                picker.show(ui.stage, options(UiFileDialog.Mode.SAVE, "save"), file -> { });
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                field(ui.stage, "file-name").setText("draft");
                ui.stage.setKeyboardFocus(list(ui.stage));
                typedKey(ui.stage, Input.Keys.TAB, '\t');
                assertSame(field(ui.stage, "file-name"), ui.stage.getKeyboardFocus());
                assertEquals("draft", field(ui.stage, "file-name").getText());
                ui.stage.setKeyboardFocus(button(ui.stage, "file-up"));
                typedKey(ui.stage, Input.Keys.TAB, '\t');
                assertSame(field(ui.stage, "file-location"), ui.stage.getKeyboardFocus());
                assertEquals(directory.toString(), field(ui.stage, "file-location").getText());
                ui.stage.setKeyboardFocus(field(ui.stage, "file-name"));
                field(ui.stage, "file-name").setText("");
                ui.stage.keyTyped('東');
                ui.stage.keyTyped('京');
                assertEquals("東京", field(ui.stage, "file-name").getText(), "Unicode path input remains available");
            }
        });
    }

    @Test void selectedFoldersAndEditedFileNamesHaveUnambiguousTargets() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("destination"));
        Path existing = Files.writeString(directory.resolve("existing.board2"), "board");
        UiTestStage.run(ui -> {
            AtomicReference<Path> result = new AtomicReference<>();
            try (UiFileDialog picker = new UiFileDialog(ui.kit, new MemoryPreferences())) {
                picker.show(ui.stage, options(UiFileDialog.Mode.OPEN, "open"), result::set);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                list(ui.stage).setSelectedIndex(1);
                assertEquals(existing.getFileName().toString(), field(ui.stage, "file-name").getText());
                ui.stage.setKeyboardFocus(list(ui.stage));
                list(ui.stage).setSelectedIndex(0);
                typedKey(ui.stage, Input.Keys.TAB, '\t'); // File name.
                typedKey(ui.stage, Input.Keys.TAB, '\t'); // Cancel.
                typedKey(ui.stage, Input.Keys.TAB, '\t'); // Open.
                assertSame(button(ui.stage, "file-accept"), ui.stage.getKeyboardFocus());
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !picker.isOpen() || !button(ui.stage, "file-accept").isDisabled());
                assertTrue(picker.isOpen(), "Open enters the selected folder, not the previously selected file");
                assertNull(result.get());
                assertEquals(folder.toString(), field(ui.stage, "file-location").getText());
                key(ui.stage, Input.Keys.ESCAPE);

                picker.show(ui.stage, options(UiFileDialog.Mode.SAVE, "save"), result::set);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                field(ui.stage, "file-name").setText("draft");
                list(ui.stage).setSelectedIndex(0);
                ui.stage.setKeyboardFocus(button(ui.stage, "file-accept"));
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(folder.toString(), field(ui.stage, "file-location").getText());
                assertEquals("draft", field(ui.stage, "file-name").getText(), "Navigation preserves the suggested save name");
                button(ui.stage, "file-up").fire(new ChangeEvent());
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                list(ui.stage).setSelectedIndex(0);
                ui.stage.setKeyboardFocus(field(ui.stage, "file-name"));
                field(ui.stage, "file-name").selectAll();
                ui.stage.keyTyped('n');
                assertNull(list(ui.stage).getSelected(), "An explicit filename edit replaces the folder selection");
                ui.stage.setKeyboardFocus(button(ui.stage, "file-accept"));
                key(ui.stage, Input.Keys.ENTER);
                await(ui.stage, () -> !picker.isOpen());
                assertEquals(directory.resolve("n.board2"), result.get());
            }
        });
    }

    @Test void successiveDoubleClicksCanEnterNestedFolders() throws Exception {
        Path outer = Files.createDirectory(directory.resolve("outer"));
        Path inner = Files.createDirectory(outer.resolve("inner"));
        UiTestStage.run(ui -> {
            try (UiFileDialog picker = new UiFileDialog(ui.kit, new MemoryPreferences())) {
                picker.show(ui.stage, options(UiFileDialog.Mode.OPEN, "open"), file -> { });
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                // Keep both double-clicks in one click sequence even on a slow test machine.
                for (var listener : list(ui.stage).getListeners()) {
                    if (listener instanceof ClickListener clicks) { clicks.setTapCountInterval(30); }
                }
                ui.draw();
                var rows = list(ui.stage);
                assertNull(rows.getItemAt(1));
                click(ui.stage, rows, 30, rows.getHeight() - rows.getItemHeight() / 2);
                click(ui.stage, rows, 30, 1); // Blank space must also start a new click sequence.
                doubleClickFirstEntry(ui.stage);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(outer.toString(), field(ui.stage, "file-location").getText());
                ui.draw();
                doubleClickFirstEntry(ui.stage);
                await(ui.stage, () -> !button(ui.stage, "file-accept").isDisabled());
                assertEquals(inner.toString(), field(ui.stage, "file-location").getText());
            }
        });
    }

    private UiFileDialog.Options options(UiFileDialog.Mode mode, String key) {
        return new UiFileDialog.Options(mode == UiFileDialog.Mode.OPEN ? "Open board" : "Save board", mode, key, directory, "",
              new UiFileDialog.Filter("Boards (*.board, *.board2)", "board2", "board"));
    }

    private static TextField field(Stage stage, String name) { return stage.getRoot().findActor(name); }
    private static UiButton button(Stage stage, String name) { return stage.getRoot().findActor(name); }
    private static com.badlogic.gdx.scenes.scene2d.ui.List<?> list(Stage stage) { return stage.getRoot().findActor("file-entries"); }
    private static void key(Stage stage, int key) { stage.keyDown(key); stage.keyUp(key); }

    private static void typedKey(Stage stage, int key, char character) {
        stage.keyDown(key); stage.keyTyped(character); stage.keyUp(key);
    }

    private static void doubleClickFirstEntry(Stage stage) {
        var list = list(stage);
        for (int count = 0; count < 2; count++) {
            click(stage, list, 30, list.getHeight() - list.getItemHeight() / 2);
        }
    }

    private static void click(Stage stage, Actor actor, float x, float y) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(x, y));
        stage.stageToScreenCoordinates(point);
        assertTrue(stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT));
        stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }

    private static void await(Stage stage, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do { stage.act(.016f); if (condition.getAsBoolean()) { return; } Thread.sleep(5); }
        while (System.nanoTime() < deadline);
        fail("Dialog did not complete its filesystem operation");
    }

    /** Exercises the standard preference contract without changing the player's saved folders. */
    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        MemoryPreferences() { super(null, ""); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() { }
    }
}
