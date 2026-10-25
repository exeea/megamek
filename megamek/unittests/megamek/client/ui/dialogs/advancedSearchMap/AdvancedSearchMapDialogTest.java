/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedSearchMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.utilities.BoardClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdvancedSearchMapDialogTest {

    @Test
    void refreshReloadsMetadataAndPreviewWhilePreservingFilters(@TempDir Path directory) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Swing dialog");
        var originalBoards = Configuration.boardsDir();
        try {
            Configuration.setBoardsDir(directory.toFile());
            BoardClassifier.invalidate();
            Path editedPath = directory.resolve("edited.board2");
            Board original = Board.createEmptyBoard(2, 3);
            original.addTag("shared");
            BoardFile.save(original, editedPath);

            FutureTask<Void> test = new FutureTask<>(() -> {
                AdvancedSearchMapDialog dialog = new AdvancedSearchMapDialog(null);
                try {
                    JLabel image = field(dialog, "boardImage", JLabel.class);
                    JLabel info = field(dialog, "boardInfo", JLabel.class);
                    JList<?> tags = field(dialog, "listBoardTags", JList.class);
                    JList<?> paths = field(dialog, "listBoardPaths", JList.class);
                    JTable table = field(dialog, "boardTable", JTable.class);
                    JTextField name = field(dialog, "nameTextField", JTextField.class);
                    tags.setSelectedValue("shared", true);
                    name.setText("edited");
                    String selectedPath = dialog.getPath();
                    var oldImage = image.getIcon();
                    assertNotNull(oldImage);

                    Board edited = Board.createEmptyBoard(4, 5);
                    edited.addTag("shared");
                    edited.addTag("added");
                    BoardFile.save(edited, editedPath);
                    Path newPath = Files.createDirectories(directory.resolve("new-folder")).resolve("new.board2");
                    BoardFile.save(edited, newPath);
                    BoardClassifier.invalidate();
                    dialog.refreshBoards();

                    assertEquals(selectedPath, dialog.getPath());
                    assertEquals("edited", name.getText());
                    assertEquals(List.of("shared"), tags.getSelectedValuesList());
                    assertEquals(2, paths.getModel().getSize());
                    assertEquals(2, paths.getSelectedValuesList().size(), "All paths includes newly saved folders");
                    assertEquals(2, table.getModel().getRowCount(), "The catalogue includes the newly saved map");
                    assertEquals(1, table.getRowCount(), "Existing filters still apply");
                    assertNotSame(oldImage, image.getIcon(), "The selected map thumbnail is regenerated");
                    assertNotNull(image.getIcon());
                    assertTrue(info.getText().contains("4x5"));
                    assertTrue(info.getText().contains("added"));
                } finally {
                    dialog.dispose();
                }
                return null;
            });
            SwingUtilities.invokeAndWait(test);
            test.get();
        } finally {
            Configuration.setBoardsDir(originalBoards);
            BoardClassifier.invalidate();
        }
    }

    private static <T> T field(AdvancedSearchMapDialog dialog, String name, Class<T> type) throws Exception {
        var field = AdvancedSearchMapDialog.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(dialog));
    }
}
