/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board.postprocess;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BridgeSpan;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.HexTile;
import megamek.common.loaders.MapSettings;
import megamek.common.options.GameOptions;
import megamek.common.planetaryConditions.PlanetaryConditions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sheets are converted one by one, so the game assembly joins what crosses a seam: a bridge whose evidence lies on one
 * sheet is one built span, and a 7-hex emblem cut by the seam is one emblem.
 */
class SheetSeamTest {
    private static final String WEST = "seamWest", EAST = "seamEast";
    private static final String EMBLEM = "decal/emblems/red-cross";
    /** The bridge's western hex and the emblem set's centre, on the west sheet's last column. */
    private static final Coords DECK = new Coords(3, 0), CENTRE = new Coords(3, 3);

    @TempDir
    private static Path boards;
    private static File originalBoards;

    @BeforeAll
    static void writeSheets() throws IOException {
        Board west = Board.createEmptyBoard(4, 6);
        // Pavement at the deck's west end makes the bridge built; its east half is on the other sheet.
        put(west, new Coords(2, 0), "pavement:1");
        put(west, DECK, "water:1;bridge:1:36;bridge_cf:40;bridge_elev:1");
        put(west, CENTRE, "fluff:62:10");
        put(west, new Coords(3, 2), "fluff:62:7");
        put(west, new Coords(2, 3), "fluff:62:8");
        put(west, new Coords(2, 4), "fluff:62:11");
        put(west, new Coords(3, 4), "fluff:62:13");
        Board east = Board.createEmptyBoard(4, 6);
        put(east, new Coords(0, 1), "water:1;bridge:1:36;bridge_cf:40;bridge_elev:1");
        put(east, new Coords(0, 3), "fluff:62:9");
        put(east, new Coords(0, 4), "fluff:62:12");
        save(west, WEST);
        save(east, EAST);
        originalBoards = Configuration.boardsDir();
        Configuration.setBoardsDir(boards.toFile());
    }

    @AfterAll
    static void restoreBoardsDirectory() { Configuration.setBoardsDir(originalBoards); }

    private static void put(Board board, Coords at, String terrain) { board.setHex(at, new Hex(0, terrain, "")); }

    private static void save(Board board, String name) throws IOException {
        try (OutputStream output = Files.newOutputStream(boards.resolve(name + ".board"))) { board.save(output, false); }
    }

    @Test
    void eachSheetAloneDecodesItsHalf() {
        Board east = BoardFile.loadSheet(boards.resolve(EAST + ".board").toFile(), false, true);
        assertEquals(HexAppearance.NATURAL_BRIDGE, east.getHex(0, 1).getAppearance().get("bridge"),
              "No evidence on the east sheet");
        assertEquals(1, emblems(east).size(), "The east half places the full emblem at the set's centre, off the sheet");
    }

    @Test
    void theGameJoinsTheBridgeAndTheEmblemAcrossTheSeam() {
        MapSettings settings = MapSettings.getInstance();
        settings.setBoardSize(4, 6);
        settings.setMapSize(2, 1);
        settings.setBoardsSelectedVector(List.of(WEST, EAST));
        Board board = TWBoardTransformer.instantiateBoard(settings, new PlanetaryConditions(), new GameOptions());

        Coords east = new Coords(4, 1);
        assertEquals(List.of(DECK, east), BridgeSpan.of(board::getHex, DECK), "One span across the seam");
        assertEquals(HexAppearance.BUILT_BRIDGE, board.getHex(DECK).getAppearance().get("bridge"));
        assertEquals(HexAppearance.BUILT_BRIDGE, board.getHex(east).getAppearance().get("bridge"), "One type: built");

        var emblems = emblems(board);
        assertEquals(1, emblems.size(), "One emblem");
        var emblem = emblems.getFirst();
        Coords at = emblem.getKey();
        BoardDecoration decal = emblem.getValue();
        assertEquals(HexTile.centerX(CENTRE, 1), HexTile.centerX(at, 1) + decal.x(), 1e-6, "On the set's centre");
        assertEquals(HexTile.centerY(CENTRE, 1), HexTile.centerY(at, 1) + decal.y(), 1e-6, "On the set's centre");
    }

    private static List<Map.Entry<Coords, BoardDecoration>> emblems(Board board) {
        List<Map.Entry<Coords, BoardDecoration>> result = new ArrayList<>();
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Coords at = new Coords(x, y);
                board.getHex(at).getDecorations().stream().filter(object -> object.asset().equals(EMBLEM))
                      .forEach(object -> result.add(Map.entry(at, object)));
            }
        }
        return result;
    }
}
