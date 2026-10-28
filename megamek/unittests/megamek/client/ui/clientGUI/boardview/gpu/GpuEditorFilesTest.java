/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.clientGUI.RecentBoardList;
import megamek.client.ui.gdx.UiFileDialog;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GpuEditorFilesTest {
    @TempDir Path directory;

    @Test void openSavesDirtyBoardBeforeChoosingAnotherAndRecordsSuccessfulFiles() throws Exception {
        edt(() -> {
            try (var recent = mockStatic(RecentBoardList.class)) {
                var session = new BoardEditorSession();
                session.board().addTag("unsaved change");
                var files = new GpuEditorFiles(session);
                assertTrue(files.command(new Command(Action.OPEN)));
                assertNull(files.prompt().file());
                assertEquals(3, files.prompt().answers().size());
                files.answer(files.prompt(), null, 0); // Save first.
                assertEquals(UiFileDialog.Mode.SAVE, files.prompt().file().mode());
                Path saved = directory.resolve("saved.board2");
                files.answer(files.prompt(), saved, -1);
                assertTrue(Files.exists(saved));
                assertTrue(BoardFile.read(saved).getTags().contains("unsaved change"));
                assertFalse(session.dirty());
                assertEquals(UiFileDialog.Mode.OPEN, files.prompt().file().mode());
                Path opened = directory.resolve("opened.board2");
                BoardFile.save(Board.createEmptyBoard(3, 4), opened);
                files.answer(files.prompt(), opened, -1);
                assertNull(files.prompt());
                assertEquals(opened, session.path());
                assertEquals(3, session.board().getWidth());
                recent.verify(() -> RecentBoardList.addBoard(saved.toAbsolutePath().normalize().toString()));
                recent.verify(() -> RecentBoardList.addBoard(opened.toAbsolutePath().normalize().toString()));
            }
        });
    }

    @Test void cancelAtEitherPromptPreservesDirtyDocumentAndFailedSaveDoesNotOpen() throws Exception {
        edt(() -> {
            try (var recent = mockStatic(RecentBoardList.class)) {
                var session = new BoardEditorSession();
                session.board().addTag("keep me");
                var original = session.board();
                var files = new GpuEditorFiles(session);
                files.command(new Command(Action.OPEN));
                files.answer(files.prompt(), null, -1);
                assertNull(files.prompt());
                assertTrue(session.dirty());
                files.command(new Command(Action.OPEN));
                files.answer(files.prompt(), null, 0);
                files.answer(files.prompt(), null, -1); // Cancel Save As.
                assertNull(files.prompt());
                files.command(new Command(Action.OPEN));
                files.answer(files.prompt(), null, 0);
                files.answer(files.prompt(), directory.resolve("missing/failure.board2"), -1);
                assertNotNull(files.prompt());
                assertNull(files.prompt().file(), "A save failure is an in-scene error, never an Open picker");
                assertSame(original, session.board());
                assertTrue(session.dirty());
                assertNull(session.path());
                recent.verifyNoInteractions();
            }
        });
    }

    @Test void unreadableOpenAndStaleRepliesCannotReplaceTheDocument() throws Exception {
        edt(() -> {
            try (var recent = mockStatic(RecentBoardList.class)) {
                var session = new BoardEditorSession();
                var files = new GpuEditorFiles(session);
                var original = session.board();
                files.command(new Command(Action.OPEN));
                var stale = files.prompt();
                files.answer(stale, directory.resolve("missing.board2"), -1);
                assertSame(original, session.board());
                assertNull(files.prompt().file());
                files.answer(files.prompt(), null, 0);
                files.command(new Command(Action.OPEN));
                var current = files.prompt();
                files.answer(stale, directory.resolve("missing.board2"), -1);
                assertSame(current, files.prompt());
                session.open(Board.createEmptyBoard(2, 2), null);
                files.answer(current, directory.resolve("missing.board2"), -1);
                assertNull(files.prompt());
                assertEquals(2, session.board().getWidth());
                recent.verifyNoInteractions();
            }
        });
    }

    private static void edt(Checked action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
