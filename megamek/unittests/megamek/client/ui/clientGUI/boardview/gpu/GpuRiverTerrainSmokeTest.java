/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * Shared terrain scenes for the river, shore and paving tests: water hexes inside sculpted land after a user's desert
 * map (a depth-1 river at level 2 between land at levels 1 to 4, falling into a lake at level 1), a quay, a dock,
 * paved maps, drops and a printed map, and the board tuning they render with.
 */
class GpuRiverTerrainSmokeTest {
    /** Rows are y; characters are x. Digits are land levels, r is river (level 2, depth 1), w/W lake (level 1). */
    static final String[] LAYOUT = {
          "33r22233344433",
          "43r32233444443",
          "43rr3223344333",
          "3323r432233322",
          "2222rrrr333222",
          "11221112r33222",
          "11111122rr3322",
          "1111112222r332",
          "1111111121r122",
          "111111111www22",
          "111111111wWW11",
          "1111111111ww11",
    };

    static void tune(float grid, boolean transitions) {
        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, grid, BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE,
              transitions, BoardGeometry.DEFAULT_PADDING));
    }

    /** A paved waterfront, including an inlet that must stay open and buildings whose foundations stay intact. */
    static BoardScene quayScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 11; y++) {
                boolean land = x >= 4 && y >= 2 && y <= 8 || x >= 6 && y >= 1 && y <= 9;
                land &= !(x == 4 && y == 3);
                boolean building = land && (x == 4 && y == 5 || x == 5 && y == 7);
                List<BoardScene.Feature> features = building
                      ? List.of(new BoardScene.Feature("buildings/saxarba/building_hard/building_hard_00",
                            0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                Hex hex = new Hex(0);
                if (family == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                if (building) { hex.addTerrain(new Terrain(Terrains.BUILDING, 4)); }
                boolean detailed = BoardFeatures.detailedGround(hex, building
                      ? Map.of(Terrains.BUILDING, features.getFirst().asset()) : Map.of());
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, land ? -1 : 2, false, 0,
                      land ? family : BoardScene.Surface.GRASS, pixels, null, null, null, null, features, List.of(),
                      land ? BoardLiquid.NONE : BoardLiquid.WATER, null, detailed));
            }
        }
        return new BoardScene(0, 9, 11, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** A diagonal and a vertical paved finger connected to a broad waterfront. */
    static BoardScene dockScene() {
        Set<Coords> dock = new HashSet<>();
        Coords at = new Coords(8, 7);
        for (int i = 0; i < 6; i++) { dock.add(at); at = at.translated(5); }
        at = new Coords(10, 6);
        for (int i = 0; i < 5; i++) { dock.add(at); at = at.translated(0); }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 12; y++) {
                Coords coords = new Coords(x, y);
                boolean land = x >= 8 && y >= 6 || dock.contains(coords);
                tiles.add(new BoardScene.Tile(coords, 0, land ? -1 : 2, false, 0,
                      land ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, pixels, null, null, null, null,
                      List.of(), List.of(), land ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 14, 12, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The reported broad junctions and triangular terrain islands; buildings retain their protected hexes. */
    static BoardScene pavedMapScene(int number) {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 AeroBase " + number + ".board"));
        BoardScene.Pixels pixels = groundPixels();
        Map<Integer, String> models = Map.of(Terrains.BUILDING, "buildings/saxarba/building_hard/building_hard_00");
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                Map<Integer, String> selected = hex.containsTerrain(Terrains.BUILDING) ? models : Map.of();
                tiles.add(new BoardScene.Tile(at, hex.getLevel(), -1, false, 0, BoardFeatures.surface(hex),
                      pixels, null, null, null, null, BoardFeatures.capture(hex, at, selected), List.of(),
                      BoardLiquid.NONE, null, BoardFeatures.detailedGround(hex, selected)));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Parallel streams drop one, two and three surface levels, from left to right. */
    static BoardScene dropScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                int level = y < 3 ? x / 3 + 1 : 0;
                int depth = x % 3 == 1 ? 1 : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The printed reference's wrapped peninsulas, raised banks and depth-zero shallows. */
    static BoardScene mapScene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = board.getHex(x, y);
                int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), hex.getLevel(), depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Every hex, water included, carries the family, as the game gives a desert map's water hexes its theme. */
    static BoardScene scene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                int level = switch (cell) {
                    case 'r' -> 2;
                    case 'w', 'W' -> 1;
                    default -> cell - '0';
                };
                int depth = switch (cell) {
                    case 'r', 'w' -> 1;
                    case 'W' -> 2;
                    default -> -1;
                };
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(),
                      depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels groundPixels() {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        return new BoardScene.Pixels(image);
    }
}
