/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

/**
 * A game shows the board exactly as the 3D preview and editor do: both sources deliver the same terrain tiles, and only
 * the game adds its tactical markings. Legacy scenery used to reach the preview only.
 */
class GpuSourceParityTest {
    private static final int SIZE = 8;
    private static final Coords GEYSER = new Coords(1, 1), CAR_PARK = new Coords(2, 1), HELIPAD = new Coords(1, 3),
          STYLED_ROAD = new Coords(3, 3);

    @Test
    void gameAndPreviewCaptureTheSameTiles() throws Exception {
        List<BoardScene.Tile> game;
        try (var fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            game = fixture.source.takeFrame().scene().tiles();
        }
        var task = new FutureTask<>(() -> {
            var map = new Game();
            map.setBoard(board());
            try (var source = new GpuMapSource(map, null, null)) { return source.takeFrame().scene().tiles(); }
        });
        SwingUtilities.invokeAndWait(task);
        List<BoardScene.Tile> preview = task.get();

        assertEquals(preview.size(), game.size());
        for (int i = 0; i < preview.size(); i++) {
            assertEquals(preview.get(i).withTactical(null), game.get(i).withTactical(null),
                  "Hex " + preview.get(i).coords().getBoardNum());
        }
        assertTrue(tile(game, GEYSER).features().stream().anyMatch(feature -> feature.asset().contains("geyser")),
              "The legacy geyser model reaches the game");
        assertNotNull(tile(game, CAR_PARK).tilesetScenery(), "The game's Tactical View keeps the car park sprite");
        assertNotNull(tile(game, HELIPAD).tilesetDecals(), "The game's Tactical View keeps the painted helipad");
        assertEquals("gravel", tile(game, STYLED_ROAD).appearance().get("road").material(),
              "The authored road material survives the game's tactical repaint");
    }

    /** Tiles are in column order. */
    private static BoardScene.Tile tile(List<BoardScene.Tile> tiles, Coords at) {
        return tiles.get(at.getX() * SIZE + at.getY());
    }

    /** A legacy board with the scenery tokens a game used to drop, and one authored road material. */
    private static Board board() {
        Hex[] hexes = new Hex[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) { put(hexes, new Coords(x, y), ""); }
        }
        put(hexes, GEYSER, "geyser:1;water:1");
        put(hexes, CAR_PARK, "pavement:1;road:1:36;fluff:5:4");
        put(hexes, new Coords(3, 1), "rubble:3;pavement:1");
        put(hexes, new Coords(4, 1), "fortified:1");
        put(hexes, HELIPAD, "fluff:2:0");
        put(hexes, new Coords(2, 3), "ground_fluff:2:1");
        put(hexes, STYLED_ROAD, "road:4:9")
              .setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "gravel", null)));
        return new Board(SIZE, SIZE, hexes);
    }

    private static Hex put(Hex[] hexes, Coords at, String terrain) {
        return hexes[at.getY() * SIZE + at.getX()] = new Hex(0, terrain, "", at);
    }
}
