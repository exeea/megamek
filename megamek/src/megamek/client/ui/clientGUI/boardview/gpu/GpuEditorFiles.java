/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.gdx.UiKit.text;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;

/** EDT-owned document interaction. Only the immutable prompt crosses to Scene2D; the session owns all board state. */
final class GpuEditorFiles {
    record Prompt(UiFileDialog.Options file, String title, String message, List<String> answers) { }

    private final BoardEditorSession editor;
    private volatile Prompt prompt;
    private Board promptedBoard;
    private Consumer<Path> fileAnswer;
    private IntConsumer questionAnswer;

    GpuEditorFiles(BoardEditorSession editor) { this.editor = editor; }

    Prompt prompt() { return prompt; }

    boolean command(Command command) {
        if (command.action() != Action.OPEN && command.action() != Action.SAVE && command.action() != Action.SAVE_AS
              && command.action() != Action.SAVE_IMAGE) { return false; }
        if (prompt != null) { return true; }
        editor.finishStroke();
        switch (command.action()) {
            case OPEN -> discard(() -> {
                if (command.value().isEmpty()) {
                    choose(UiFileDialog.Mode.OPEN, text("CommonMenuBar.fileBoardOpen"), "boards", null, "",
                          new UiFileDialog.Filter(text("BoardEditor.openFilter"), "board", "board2"), this::open);
                } else {
                    try { open(Path.of(command.value())); }
                    catch (IllegalArgumentException failure) { failed(failure); }
                }
            });
            case SAVE, SAVE_AS -> save(command.action() == Action.SAVE_AS, () -> { });
            case SAVE_IMAGE -> {
                Path near = editor.suggestedPath();
                choose(UiFileDialog.Mode.SAVE, text("BoardEditor.saveAsImage"), "board-images", near,
                      near == null ? "" : BoardFile.withoutExtension(near.getFileName().toString()) + ".png",
                      new UiFileDialog.Filter(text("FileDialog.pngFilter"), "png"), file -> {
                          try { editor.saveImage(file); }
                          catch (IOException | RuntimeException failure) { failed(failure); }
                      });
            }
            default -> { }
        }
        return true;
    }

    private void discard(Runnable action) {
        if (!editor.dirty()) { action.run(); return; }
        question(text("BoardEditor.unsavedTitle"), text("BoardEditor.unsavedMessage"),
              List.of(text("FileDialog.save"), text("FileDialog.discard"), text("Cancel")), answer -> {
                  if (answer == 0) { save(false, action); }
                  else if (answer == 1) { action.run(); }
              });
    }

    private void open(Path file) {
        try { editor.openChosen(file); }
        catch (IOException | RuntimeException failure) { failed(failure); }
    }

    private void save(boolean saveAs, Runnable after) {
        List<String> errors = new ArrayList<>();
        Runnable write = () -> {
            if (!saveAs && editor.path() != null) { save(editor.path(), after); return; }
            Path near = editor.suggestedPath();
            choose(UiFileDialog.Mode.SAVE, text("BoardEditor.saveTitle"), "boards", near,
                  near == null ? text("BoardEditor.untitledFile") + BoardFile.EXTENSION : near.getFileName().toString(),
                  new UiFileDialog.Filter(text("BoardEditor.fileFilter"), "board2"), file -> save(file, after));
        };
        if (editor.board().isValid(errors)) { write.run(); }
        else {
            question(text("BoardEditor.invalidBoard.title"), text("BoardEditor.invalidBoard.report")
                  + String.join("\n", errors) + "\n\n" + text("BoardEditor.invalidBoard.saved"),
                  List.of(text("FileDialog.save"), text("Cancel")), answer -> { if (answer == 0) { write.run(); } });
        }
    }

    private void save(Path file, Runnable after) {
        try { editor.saveChosen(file); after.run(); }
        catch (IOException | RuntimeException failure) { failed(failure); }
    }

    private void choose(UiFileDialog.Mode mode, String title, String key, Path near, String name,
          UiFileDialog.Filter filter, Consumer<Path> selected) {
        fileAnswer = selected;
        questionAnswer = null;
        promptedBoard = editor.board();
        prompt = new Prompt(new UiFileDialog.Options(title, mode, key,
              near == null ? Configuration.boardsDir().toPath() : near.toAbsolutePath().getParent(), name, filter), "", "", List.of());
    }

    private void question(String title, String message, List<String> answers, IntConsumer answered) {
        questionAnswer = answered;
        fileAnswer = null;
        promptedBoard = editor.board();
        prompt = new Prompt(null, title, message, answers);
    }

    /** A reply is valid only for this exact prompt and document. Cancellation has no document side effects. */
    void answer(Prompt expected, Path file, int answer) {
        if (expected != prompt) { return; }
        Consumer<Path> selected = fileAnswer;
        IntConsumer answered = questionAnswer;
        boolean valid = editor.board() == promptedBoard;
        clear();
        if (!valid) { return; }
        if (selected != null && file != null) { selected.accept(file); }
        else if (answered != null) { answered.accept(answer); }
    }

    void clear() { prompt = null; promptedBoard = null; fileAnswer = null; questionAnswer = null; }

    private void failed(Exception failure) {
        question(text("FileDialog.errorTitle"), String.valueOf(failure.getMessage()), List.of(text("Okay")), answer -> { });
    }

}
