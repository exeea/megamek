/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.tileset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

/**
 * The 2D tileset picks the same files with the common matcher ({@link megamek.common.board.TilesetMatcher}) as before:
 * the expected names were recorded with the 2D tileset's own matcher, for entries that list several files, so both the
 * coordinate-seeded super pick and the drawn base pick are covered.
 */
class HexTilesetPickTest {
    private static final List<Coords> AT = List.of(new Coords(0, 0), new Coords(1, 0), new Coords(2, 3), new Coords(5, 7),
          new Coords(10, 11));

    /** "base super" per hex of {@link #AT}. */
    private static List<String> picks(String tilesetFile, String terrain, String theme) throws Exception {
        try (HexTileset tileset = new HexTileset(Configuration.hexesDir())) {
            tileset.loadFromFile(tilesetFile);
            Board board = Board.createEmptyBoard(12, 12);
            return AT.stream().map(at -> {
                board.setHex(at, new Hex(0, terrain, theme));
                Hex hex = board.getHex(at);
                String supers = String.join("|", tileset.getSupers(hex).stream().map(tileset::imageSource).toList());
                return tileset.imageSource(tileset.getBase(hex)) + " " + supers;
            }).toList();
        }
    }

    @Test
    void theSeededSuperPickIsUnchanged() throws Exception {
        assertEquals(List.of("saxarba/theme_grass/base_grass_0.png saxarba/base/base_snow_light_0b.png",
              "saxarba/theme_grass/base_grass_0.png saxarba/base/base_snow_light_0.png",
              "saxarba/theme_grass/base_grass_0.png saxarba/base/base_snow_light_0.png",
              "saxarba/theme_grass/base_grass_0.png saxarba/base/base_snow_light_0b.png",
              "saxarba/theme_grass/base_grass_0.png saxarba/base/base_snow_light_0.png"), picks("saxarba.tileset", "snow:1", ""));
        assertEquals(List.of("saxarba/theme_lunar/base_lunar_0c.png saxarba/theme_lunar/swamp_lunar_b.png",
              "saxarba/theme_lunar/base_lunar_0e.png saxarba/theme_lunar/swamp_lunar_a.png",
              "saxarba/theme_lunar/base_lunar_0e.png saxarba/theme_lunar/swamp_lunar_c.png",
              "saxarba/theme_lunar/base_lunar_0.png saxarba/theme_lunar/swamp_lunar_d.png",
              "saxarba/theme_lunar/base_lunar_0c.png saxarba/theme_lunar/swamp_lunar_c.png"),
              picks("saxarba.tileset", "swamp:1", "lunar"));
    }

    @Test
    void theDrawnBasePickIsUnchanged() throws Exception {
        assertEquals(List.of("boring/light_forest_2.gif ", "boring/light_forest_1.gif ", "boring/light_forest_1.gif ",
              "boring/light_forest_0.gif ", "boring/light_forest_2.gif "), picks("classic.tileset", "woods:1;foliage_elev:2", ""));
    }
}
