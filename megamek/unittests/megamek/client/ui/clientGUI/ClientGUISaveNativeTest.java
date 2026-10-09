/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientGUISaveNativeTest {
    @Test
    void savingALegacyGameBoardAsBoard2WritesDecodedSceneryAndKeepsTheGameBoardLegacy(@TempDir Path folder) throws Exception {
        Board game = Board.createEmptyBoard(2, 1);
        Coords park = new Coords(1, 0);
        game.setHex(park, new Hex(0, "fluff:93:8", ""));
        game.setDescription("A quiet park");
        game.setAnnotations(park, java.util.List.of("Picnic here"));
        game.setSourceHeader("# Map by the MegaMek Team");
        game.setBoardType(megamek.common.board.BoardType.FAR_SPACE);
        Path file = folder.resolve("Park.board2");

        ClientGUI.saveNative(game, file);

        assertFalse(game.isNativeFormat(), "The live game board stays legacy");
        assertTrue(game.getHex(park).getDecorations().isEmpty());
        assertEquals(93, game.getHex(park).terrainLevel(Terrains.FLUFF));
        Board saved = BoardFile.read(file);
        assertTrue(saved.isNativeFormat());
        assertFalse(saved.getHex(park).containsTerrain(Terrains.FLUFF));
        assertEquals(17, saved.getHex(park).getDecorations().size());
        assertEquals("A quiet park", saved.getDescription(), "Metadata the legacy text cannot hold survives");
        assertEquals(java.util.List.of("Picnic here"), java.util.List.copyOf(saved.getAnnotations(park)));
        assertEquals("# Map by the MegaMek Team", saved.getSourceHeader());
        assertEquals(megamek.common.board.BoardType.FAR_SPACE, saved.getBoardType());
    }
}
