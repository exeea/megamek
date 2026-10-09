/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorImportTest {
    @Test
    void openingALegacyBoardImportsItAndCountsWhatTheBoardHolds(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("Park.board");
        Board legacy = Board.createEmptyBoard(2, 2);
        legacy.setHex(new Coords(1, 1), new Hex(0, "fluff:93:8", ""));
        try (OutputStream output = Files.newOutputStream(file)) { legacy.save(output, false); }

        List<Board> imported = new ArrayList<>();
        var decoder = new BoardEditorSession.LegacyDecoder() {
            @Override public List<BoardEditorSession.Issue> importBoard(Board board) {
                imported.add(board);
                Hex hex = board.getHex(1, 1).duplicate();
                hex.setDecorations(List.of(object("a", "g"), object("b", "g"), object("c", null)));
                board.setHex(new Coords(1, 1), hex);
                return List.of(new BoardEditorSession.Issue(new Coords(1, 1), "c", "Emblem set: 5 of 7 pieces"));
            }
            @Override public List<BoardDecoration> stamp(String layout, Hex hex, Coords at) { return List.of(); }
        };
        var session = new BoardEditorSession(BoardEditorBlueprint.get(), decoder);
        assertTrue(session.board().isNativeFormat(), "The initial document is native");
        session.open(file);
        assertEquals(1, imported.size());
        assertEquals(session.board(), imported.getFirst());
        assertEquals("Imported Park.board: 3 objects in 1 group. Save creates a .board2 file; the original is preserved.",
              session.snapshot().message());
        assertTrue(session.board().isNativeFormat());
        assertNull(session.path(), "Saving an import asks for a new .board2 name");
        assertEquals("Park.board2 (imported, unsaved)", session.snapshot().title(), "The import keeps the map's name");

        // What import could not decode exactly is listed with the board's own issues, until another document replaces it.
        assertEquals(List.of(new BoardEditorSession.Issue(new Coords(1, 1), "c", "Emblem set: 5 of 7 pieces")),
              session.snapshot().issues());
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.NEW, "4x4"), null);
        assertTrue(session.board().isNativeFormat(), "A new document is native");
        assertTrue(session.snapshot().issues().isEmpty());
    }

    private static BoardDecoration object(String id, String group) {
        return new BoardDecoration(id, "prop", "scenery/parks/picnic-table", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0, false, 0, 0, group);
    }
}
