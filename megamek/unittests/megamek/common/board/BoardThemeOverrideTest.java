/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardThemeOverrideTest {
    @Test
    void nativeGroundMatchingPreviousThemeFollowsOverrideWhileDifferentGroundAndRoughRemain() {
        Hex clear = new Hex(0, "", "grass");
        Hex grass = new Hex(0, "", "grass");
        grass.setAppearance(Map.of("ground", blend("grass")));
        Hex desert = new Hex(0, "", "grass");
        desert.setAppearance(Map.of("ground", blend("desert")));
        Hex rough = new Hex(0, "rough:1", "grass");
        Board board = new Board(4, 1, new Hex[] { clear, grass, desert, rough });

        board.overrideTheme("volcano");

        for (Hex hex : List.of(clear, grass, desert, rough)) { assertEquals("volcano", hex.getTheme()); }
        assertFalse(clear.getAppearance().containsKey("ground"));
        assertFalse(grass.getAppearance().containsKey("ground"));
        assertEquals(blend("desert"), desert.getAppearance().get("ground"));
        assertEquals(1, rough.terrainLevel(Terrains.ROUGH));
    }

    @Test
    void legacyTransitionsAreComparedAgainstEachHexOriginalTheme() {
        Hex grass = new Hex(0, "ground_fluff:2:3", "GRASS");
        Hex implicitGrass = new Hex(0, "ground_fluff:2:4", null);
        Hex desert = new Hex(0, "ground_fluff:1:2", "desert");
        Hex different = new Hex(0, "ground_fluff:1:5", "grass");
        Hex unrecognized = new Hex(0, "ground_fluff:2:3", "custom-grass");
        Hex clearedRubble = new Hex(0, "ground_fluff:2000", "grass");
        Hex nonTransition = new Hex(0, "ground_fluff:2", "grass");
        Board board = new Board(7, 1,
              new Hex[] { grass, implicitGrass, desert, different, unrecognized, clearedRubble, nonTransition });

        board.overrideTheme("snow");

        for (Hex hex : List.of(grass, implicitGrass, desert)) { assertFalse(hex.containsTerrain(Terrains.GROUND_FLUFF)); }
        assertEquals(5, different.getTerrain(Terrains.GROUND_FLUFF).getExits());
        assertEquals(3, unrecognized.getTerrain(Terrains.GROUND_FLUFF).getExits());
        assertEquals(2000, clearedRubble.terrainLevel(Terrains.GROUND_FLUFF));
        assertTrue(nonTransition.containsTerrain(Terrains.GROUND_FLUFF));
    }

    @Test
    void overridePreservesRulesOtherAppearancesAndDecorations() {
        Hex hex = new Hex(3, "rough:1;sand:1;snow:1;tundra:1;mud:1;woods:1;foliage_elev:2;road:1:9", "grass");
        HexAppearance road = new HexAppearance("road/legacy-dirt", null, null, null);
        hex.setAppearance(Map.of("ground", blend("grass"), "road", road));
        var decoration = new BoardDecoration("object", "prop", "scenery/vehicles/car", null, .2, .1, 30,
              false, 1, BoardDecoration.Placement.ground(), 0);
        hex.setDecorations(List.of(decoration));
        Board board = new Board(1, 1, new Hex[] { hex });
        int[] types = Arrays.stream(hex.getTerrainTypes()).sorted().toArray();
        List<String> terrains = Arrays.stream(types).mapToObj(type -> hex.getTerrain(type).toString()).toList();

        board.overrideTheme("volcano");

        assertArrayEquals(types, Arrays.stream(hex.getTerrainTypes()).sorted().toArray());
        assertEquals(terrains, Arrays.stream(types).mapToObj(type -> hex.getTerrain(type).toString()).toList());
        assertEquals(3, hex.getLevel());
        assertEquals(Map.of("road", road), hex.getAppearance());
        assertEquals(List.of(decoration), hex.getDecorations());
    }

    private static HexAppearance blend(String theme) {
        return new HexAppearance("ground/blend/" + theme, null, null, .5);
    }
}
