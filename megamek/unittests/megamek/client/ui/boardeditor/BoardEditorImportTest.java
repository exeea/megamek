/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardIssue;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorImportTest {
    @Test
    void openingALegacyBoardConvertsItAndCountsWhatTheBoardHolds(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("Park.board");
        Board legacy = Board.createEmptyBoard(2, 2);
        Coords park = new Coords(1, 1), piece = new Coords(0, 0);
        legacy.setHex(park, new Hex(0, "fluff:93:8", ""));
        // The south piece of a set whose centre lies north of the board, cut by its edge (no other piece's hex is on the
        // board): the full emblem, and an issue for the rest.
        legacy.setHex(piece, new Hex(0, "pavement:1;fluff:62:13", ""));
        try (OutputStream output = Files.newOutputStream(file)) { legacy.save(output, false); }

        var session = new BoardEditorSession(BoardEditorBlueprint.get());
        assertTrue(session.board().isNativeFormat(), "The initial document is native");
        session.open(file);
        Board opened = session.board();
        assertTrue(opened.isNativeFormat(), "The opened board is converted");
        assertTrue(opened.getHex(park).getDecorations().size() > 10, "The picnic tables are objects");
        assertFalse(opened.getHex(park).containsTerrain(Terrains.FLUFF), "The editor holds the objects, not the token");
        long objects = 0;
        Set<String> groups = new HashSet<>();
        for (Coords at : List.of(piece, new Coords(1, 0), new Coords(0, 1), park)) {
            for (BoardDecoration object : opened.getHex(at).getDecorations()) {
                objects++;
                if (object.group() != null) { groups.add(object.group()); }
            }
        }
        assertEquals("Imported Park.board: " + objects + " objects in " + groups.size()
              + (groups.size() == 1 ? " group" : " groups") + ". Save creates a .board2 file; the original is preserved.",
              session.snapshot().message());
        assertNull(session.path(), "Saving an import asks for a new .board2 name");
        assertEquals("Park.board2 (imported, unsaved)", session.snapshot().title(), "The import keeps the map's name");

        // What the conversion could not decode exactly is listed with the board's own issues, until another document
        // replaces it.
        BoardDecoration emblem = opened.getHex(piece).getDecorations().getFirst();
        assertEquals("decal/emblems/red-cross", emblem.asset());
        assertEquals(List.of(new BoardIssue(piece, emblem.id(), "Emblem set with 1 of 7 pieces: the full emblem is placed")),
              session.snapshot().issues());
        session.command(new BoardEditorSession.Command(BoardEditorSession.Action.NEW, "4x4"), null);
        assertTrue(session.board().isNativeFormat(), "A new document is native");
        assertTrue(session.snapshot().issues().isEmpty());
    }

    /** An old arena board opens with its Arena flag set, and its .board2 keeps the tag that random selection reads. */
    @Test
    void anArenaBoardKeepsItsFlagThroughTheEditor(@TempDir Path folder) throws Exception {
        Path file = folder.resolve("Stadium.board");
        Board legacy = Board.createEmptyBoard(2, 2);
        legacy.addTag("Arena");
        try (OutputStream output = Files.newOutputStream(file)) { legacy.save(output, false); }
        assertTrue(Files.readString(file).contains("tag \"Arena\""));

        var session = new BoardEditorSession(BoardEditorBlueprint.get());
        session.open(file);
        assertTrue(session.snapshot().arena());
        assertFalse(session.dirty(), "Opening is no change");
        Path saved = folder.resolve("Stadium.board2");
        session.save(saved);
        assertTrue(megamek.common.board.BoardFile.metadata(saved).tags().contains("Arena"));
    }
}
