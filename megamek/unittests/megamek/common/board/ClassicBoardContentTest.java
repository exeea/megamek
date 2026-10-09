/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import org.junit.jupiter.api.Test;

/** The classic 2D board loads .board2 maps, detects their 3D-only content and saves them as legacy text. */
class ClassicBoardContentTest {
    private static String nativeText(Board board) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    /** Loads like the classic 2D editor: Board.load from a stream, then a one-sheet combine. */
    private static Board loadLikeClassicEditor(String text) {
        Board loaded = new Board();
        loaded.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null, true);
        return BoardUtilities.combine(loaded.getWidth(), loaded.getHeight(), 1, 1, new Board[] { loaded },
              MapSettings.MEDIUM_GROUND);
    }

    @Test void onlyPlacedObjectsOrAppearanceCountAsThreeDOnlyContent() throws Exception {
        Board terrainOnly = Board.createEmptyBoard(2, 1);
        terrainOnly.setHex(1, 0, new Hex(2, "woods:1", ""));
        terrainOnly.setNativeFormat(true);
        Board opened = loadLikeClassicEditor(nativeText(terrainOnly));
        assertTrue(opened.isNativeFormat());
        assertFalse(opened.hasThreeDOnlyContent(), "a terrain-only .board2 opens silently");

        Board appearance = Board.createEmptyBoard(1, 1);
        appearance.getHex(0, 0).setAppearance(Map.of("building",
              new HexAppearance(null, "buildings/missing-but-retained", null, null)));
        assertTrue(loadLikeClassicEditor(nativeText(appearance)).hasThreeDOnlyContent());
    }

    @Test void classicSaveOfADecoratedBoard2WritesLegacyTextWithTheTerrain() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        Hex hex = new Hex(1, "woods:1;fluff:0:0", "lunar");
        hex.setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", "Car",
              0, 0, 30, false, 1, BoardDecoration.Placement.ground(), 0, false, 0, 0, "group-a")));
        board.setHex(0, 0, hex);
        board.setNativeFormat(true);
        Board opened = loadLikeClassicEditor(nativeText(board));
        assertTrue(opened.hasThreeDOnlyContent());
        assertTrue(opened.requiresNativeFormat(), "the generic save would still write JSON for this board");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        opened.saveLegacy(output, false);
        String legacy = output.toString(StandardCharsets.UTF_8);
        assertTrue(legacy.startsWith("size 2 1"), legacy);
        assertFalse(BoardFile.looksNative(legacy));
        assertFalse(legacy.contains("vehicles/car"));

        Board reloaded = new Board();
        reloaded.load(new ByteArrayInputStream(legacy.getBytes(StandardCharsets.UTF_8)), null, true);
        assertFalse(reloaded.isNativeFormat());
        assertFalse(reloaded.hasThreeDOnlyContent());
        Hex saved = reloaded.getHex(0, 0);
        assertEquals(1, saved.getLevel());
        assertEquals(1, saved.terrainLevel(Terrains.WOODS));
        assertTrue(saved.containsTerrain(Terrains.FLUFF));
        assertEquals("lunar", saved.getTheme());
    }
}
