/*
 * Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later
 */
package megamek.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;

class BoardClassifierTest {

    @Test
    void invalidationDiscoversSavedBoardsAndReloadsChangedMetadata(@TempDir Path directory) throws Exception {
        var originalBoards = Configuration.boardsDir();
        try {
            Configuration.setBoardsDir(directory.toFile());
            BoardClassifier.invalidate();
            Path editedPath = directory.resolve("edited.board2");
            Board original = Board.createEmptyBoard(2, 3);
            original.addTag("original");
            BoardFile.save(original, editedPath);
            BoardClassifier beforeSave = BoardClassifier.getInstance();
            assertEquals(2, beforeSave.getBoardWidth().get(editedPath.toString()));

            Board edited = Board.createEmptyBoard(4, 5);
            edited.addTag("edited");
            BoardFile.save(edited, editedPath);
            Path newPath = Files.createDirectories(directory.resolve("new-folder")).resolve("new.board2");
            BoardFile.save(edited, newPath);
            assertSame(beforeSave, BoardClassifier.getInstance());
            assertFalse(beforeSave.getBoardPaths().containsKey(newPath.toString()));

            BoardClassifier.invalidate();
            BoardClassifier afterSave = BoardClassifier.getInstance();
            assertEquals(2, afterSave.getBoardPaths().size());
            assertEquals(4, afterSave.getBoardWidth().get(editedPath.toString()));
            assertEquals(5, afterSave.getBoardHeight().get(editedPath.toString()));
            assertEquals("[edited]", afterSave.getBoardTags().get(editedPath.toString()));
            assertFalse(afterSave.getBoardsByManualTag().containsKey("original"));
            assertEquals(List.of(editedPath.toString(), newPath.toString()).stream().sorted().toList(),
                  afterSave.getBoardsByManualTag().get("edited").stream().sorted().toList());
            assertEquals(4, afterSave.getBoardWidth().get(newPath.toString()));
            assertEquals(2, beforeSave.getBoardWidth().get(editedPath.toString()),
                  "Existing references retain a complete snapshot until they reacquire the classifier");
        } finally {
            Configuration.setBoardsDir(originalBoards);
            BoardClassifier.invalidate();
        }
    }
}
