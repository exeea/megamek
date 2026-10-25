/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedSearchMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.utilities.BoardClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardTableModelTest {

    @Test
    void refreshedUserDataMapsRetainTheirActualMetadata(@TempDir Path directory) throws Exception {
        Path boards = Files.createDirectories(directory.resolve("board-catalogue-test"));
        Path originalPath = boards.resolve("edited.board2");
        Board original = Board.createEmptyBoard(2, 3);
        original.addTag("original");
        BoardFile.save(original, originalPath);

        try (var configuration = mockStatic(Configuration.class)) {
            configuration.when(Configuration::boardsDir).thenReturn(new File("board-catalogue-test"));
            configuration.when(Configuration::userDataDir).thenReturn(directory.toFile());
            BoardClassifier.invalidate();
            BoardTableModel model = new BoardTableModel();
            model.setData(BoardClassifier.getInstance());
            assertEquals(1, model.getRowCount());
            assertEquals(2, model.getWidthAt(0));
            assertEquals(3, model.getHeightAt(0));
            assertEquals(List.of("original"), model.getTagAt(0));

            Board edited = Board.createEmptyBoard(4, 5);
            edited.addTag("edited");
            BoardFile.save(edited, originalPath);
            BoardFile.save(edited, boards.resolve("new.board2"));
            BoardClassifier.invalidate();
            model.setData(BoardClassifier.getInstance());
            assertEquals(2, model.getRowCount());
            for (int row = 0; row < model.getRowCount(); row++) {
                assertEquals(4, model.getWidthAt(row));
                assertEquals(5, model.getHeightAt(row));
                assertEquals(List.of("edited"), model.getTagAt(row));
            }
        } finally {
            BoardClassifier.invalidate();
        }
    }
}
