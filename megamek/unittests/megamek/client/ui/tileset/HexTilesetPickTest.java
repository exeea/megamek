/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.tileset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
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

    /**
     * b13 ways: rails are terrain, and the 2D board draws them with the legacy art's sprites (data-only tileset lines): a
     * ground rail as its old {@code fluff:10} token did, a deck rail on its bridge, a destroyed rail nothing.
     */
    @Test
    void railTerrainDrawsTheLegacyRailSprites() throws Exception {
        assertEquals(picks("saxarba.tileset", "fluff:10:9", ""), picks("saxarba.tileset", "rail:1:9", ""));
        assertTrue(picks("saxarba.tileset", "water:1;bridge:1:9;bridge_cf:40;bridge_elev:1;bridge_rail:1:9", "").stream()
              .allMatch(pick -> pick.contains("hq_fluff/rail09.gif")));
        assertTrue(picks("saxarba.tileset", "rail:0:9", "").stream().noneMatch(pick -> pick.contains("rail")));
        for (String tileset : List.of("saxarba.tileset", "classic.tileset")) {
            assertEquals(picks(tileset, "fluff:9:0", ""), picks(tileset, "rail:2:9", ""));
            assertTrue(picks(tileset, "water:1;bridge:4:18;bridge_cf:650;bridge_elev:1;bridge_rail:2:18", "").stream()
                  .allMatch(pick -> pick.contains("fluff/maglevtrack2.gif")));
        }
    }

    /**
     * b13: a road on a bridge deck ({@code bridge_road}), a muddy deck ({@code bridge_mud}) and a destroyed rail
     * ({@code rail:0}) have no sprite, and the 2D board draws their hexes as it did without them: a terrain that no
     * tileset line takes would turn the base to the tileset's first base entry once the supers have taken the rest (a
     * deck's bridge terrain).
     */
    @Test
    void deckAndWayTerrainWithoutASpriteLeaveTheHexArtAsItWas() throws Exception {
        String deck = "bridge:1:18;bridge_cf:40;bridge_elev:1";
        for (String tileset : List.of("saxarba.tileset", "classic.tileset")) {
            assertEquals(drawn(picks(tileset, deck, "")), drawn(picks(tileset, deck + ";bridge_road:2:18", "")), tileset);
            assertEquals(drawn(picks(tileset, deck, "")), drawn(picks(tileset, deck + ";bridge_mud:1", "")), tileset);
            assertEquals(drawn(picks(tileset, "", "")), drawn(picks(tileset, "rail:0:9", "")), tileset);
        }
    }

    /** The picked files without blank supers, which draw nothing. */
    private static List<String> drawn(List<String> picks) {
        return picks.stream().map(pick -> String.join("|", Arrays.stream(pick.split("[ |]"))
              .filter(file -> !file.equals("saxarba/misc/blank.png")).toList())).toList();
    }
}
