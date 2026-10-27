/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.BoardUtilities;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.Test;

/**
 * The common data of b13's ways: ground rails ({@code rail}), the road and rail a bridge deck carries ({@code bridge_road},
 * {@code bridge_rail}), the implicit bridge they make ({@link BridgeSpan#carry}), and the deck as one unit when it falls.
 */
class DeckWaysTest {
    private static final String SPAN = "bridge:1:9;bridge_cf:40;bridge_elev:2";

    private static List<String> errors(String terrain) {
        List<String> errors = new ArrayList<>();
        new Hex(0, terrain, "").isValid(errors);
        return errors;
    }

    private static String legacy(Board board) {
        var output = new ByteArrayOutputStream();
        board.saveLegacy(output, false);
        return output.toString(StandardCharsets.UTF_8).replace("\r", "");
    }

    /** The terrain tokens a legacy board text writes for the hex in row {@code row} of column 1. */
    private static Set<String> tokens(String text, int row) {
        String line = text.lines().filter(l -> l.startsWith("hex 01%02d ".formatted(row))).findFirst().orElseThrow();
        return Set.of(line.substring(line.indexOf('"') + 1, line.indexOf('"', line.indexOf('"') + 1)).split(";"));
    }

    @Test
    void waysAreTerrainWithTheirSidesAsExitsAndRoundTripAsLegacyTokens() {
        String text = """
              size 1 3
              hex 0101 0 "road:1;rail:1" ""
              hex 0102 0 "water:2;bridge:1:09;bridge_cf:40;bridge_elev:2;bridge_road:4:09;bridge_rail:1:01" ""
              hex 0103 0 "road:2:09;rail:0:09" ""
              end""";
        Board board = BoardLoader.initializeBoard(text);
        Terrain sideless = board.getHex(0, 0).getTerrain(Terrains.RAIL);
        assertTrue(sideless.hasExitsSpecified(), "a rail never joins its neighbours by itself");
        assertEquals("rail:1:0", sideless.toString());
        assertFalse(board.getHex(0, 0).getTerrain(Terrains.ROAD).hasExitsSpecified(), "legacy roads keep automatic exits");
        assertEquals(9, board.getHex(0, 1).getTerrain(Terrains.BRIDGE_ROAD).getExits());
        assertEquals("Road on bridge", Terrains.getDisplayName(Terrains.BRIDGE_ROAD, 4));
        assertEquals("Destroyed rail", Terrains.getDisplayName(Terrains.RAIL, 0));

        String saved = legacy(board);
        assertEquals(Set.of("road:1", "rail:1:0"), tokens(saved, 1));
        assertEquals(Set.of("water:2", "bridge:1:9", "bridge_cf:40", "bridge_elev:2", "bridge_road:4:9", "bridge_rail:1:1"),
              tokens(saved, 2));
        assertEquals(Set.of("road:2:9", "rail:0:9"), tokens(saved, 3));
        assertEquals(saved, legacy(BoardLoader.initializeBoard(saved)), "the tokens read back as written");
    }

    @Test
    void aDeckWayNeedsItsBridgeAndRunsOnlyAlongItsExits() {
        assertTrue(errors(SPAN + ";bridge_road:1:9;bridge_rail:1:8").isEmpty());
        assertTrue(errors("bridge_road:1:9").getFirst().contains("needs a bridge"));
        assertTrue(errors("bridge_rail:1:9").getFirst().contains("needs a bridge"));
        assertTrue(errors(SPAN + ";bridge_road:1:13").getFirst().contains("no exit"), "SE is no exit of the bridge");
        assertTrue(errors("bridge:1;bridge_cf:40;bridge_elev:2;bridge_rail:1:13").isEmpty(),
              "automatic bridge exits follow the neighbours, so the hex alone cannot judge them");
        assertTrue(errors("rail:0:9").isEmpty() && errors("rail:1:9").isEmpty());
        assertTrue(errors("rail:2:9").isEmpty() && errors(SPAN + ";bridge_rail:2:9").isEmpty(),
              "a maglev is rail at level 2, on the ground or on a deck");
        assertFalse(errors("rail:3:9").isEmpty());
        assertFalse(errors(SPAN + ";bridge_rail:3:9").isEmpty());
        assertFalse(errors(SPAN + ";bridge_road:5:9").isEmpty(), "a deck road has the road's four kinds");
        assertTrue(errors("water:1;rail:1:9").isEmpty(), "submerged track is legal (TO:AR p.32 fn 21)");
    }

    @Test
    void aCollapseTakesTheDeckWithItsRoadAndRailAndLeavesTheGround() {
        Board board = BoardLoader.initializeBoard("""
              size 1 3
              hex 0101 0 "" ""
              hex 0102 0 "bridge:1:09;bridge_cf:40;bridge_elev:2;bridge_road:1:09;bridge_rail:1:01;road:1:18;rail:1:36" ""
              hex 0103 0 "" ""
              end""");
        Coords at = new Coords(0, 1);
        board.collapseBuilding(at);
        Hex hex = board.getHex(at);
        for (int deck : new int[] { Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV, Terrains.BRIDGE_ROAD,
                                    Terrains.BRIDGE_RAIL }) {
            assertFalse(hex.containsTerrain(deck), Terrains.getName(deck));
        }
        assertEquals(18, hex.getTerrain(Terrains.ROAD).getExits(), "the road beneath stays");
        assertEquals(36, hex.getTerrain(Terrains.RAIL).getExits(), "the rail beneath stays");
        assertTrue(hex.containsTerrain(Terrains.RUBBLE));
    }

    @Test
    void aFlipTurnsRailsDeckWaysAndTheRailsJunctionLayouts() {
        int crossing = 1 | 1 << RailRoute.piece(2, 4);
        Board board = Board.createEmptyBoard(1, 1);
        Hex hex = new Hex(0, "rail:1:29;" + SPAN + ";bridge_road:1:1;bridge_rail:1:8", "");
        hex.setAppearance(Map.of("rail", HexAppearance.tracks(crossing)));
        board.setHex(0, 0, hex);
        BoardUtilities.flip(board, false, true);
        Hex flipped = board.getHex(0, 0);
        assertEquals(1 | 2 | 8 | 32, flipped.getTerrain(Terrains.RAIL).getExits());
        assertEquals(8, flipped.getTerrain(Terrains.BRIDGE_ROAD).getExits());
        assertEquals(1, flipped.getTerrain(Terrains.BRIDGE_RAIL).getExits());
        int turned = 1 | 1 << RailRoute.piece(5, 1);
        assertEquals(turned, HexAppearance.tracks(flipped.getAppearance(), "rail"), "as a stored marker's pieces turn");
        assertEquals(turned, RailRoute.find(flipped, RailRoute.Kind.RAIL).tracks());
    }

    @Test
    void carryWritesAnImplicitBridgeShapedByItsWaysAndRemovesItWithTheLast() {
        Hex hex = new Hex(0, "water:1;bridge_road:1:9", "");
        BridgeSpan.carry(hex, 1, "bridge:1;bridge_cf:40");
        Terrain bridge = hex.getTerrain(Terrains.BRIDGE);
        assertTrue(bridge.hasExitsSpecified());
        assertEquals(9, bridge.getExits());
        assertEquals(1, bridge.getLevel());
        assertEquals(40, hex.terrainLevel(Terrains.BRIDGE_CF));
        assertEquals(1, hex.terrainLevel(Terrains.BRIDGE_ELEV));
        assertSame(HexAppearance.IMPLICIT_BRIDGE, hex.getAppearance().get("bridge"));
        assertSame(Boolean.TRUE, HexAppearance.bridgeBuilt(hex.getAppearance()), "an implicit bridge counts as built");
        assertTrue(hex.isValid(null));

        hex.addTerrain(new Terrain(Terrains.BRIDGE_RAIL, 1, true, 18));
        hex.setAppearance(Map.of("bridge", HexAppearance.IMPLICIT_PILLARS));
        BridgeSpan.carry(hex, 3, "bridge:4;bridge_cf:650");
        assertEquals(27, hex.getTerrain(Terrains.BRIDGE).getExits(), "the union of its ways' sides");
        assertEquals(1, hex.terrainLevel(Terrains.BRIDGE), "an existing deck keeps its weight");
        assertEquals(1, hex.terrainLevel(Terrains.BRIDGE_ELEV), "and its height");
        assertTrue(HexAppearance.pillars(hex.getAppearance()), "and its piers");

        hex.removeTerrain(Terrains.BRIDGE_ROAD);
        hex.removeTerrain(Terrains.BRIDGE_RAIL);
        BridgeSpan.carry(hex, 1, "bridge:1;bridge_cf:40");
        assertFalse(hex.containsAnyTerrainOf(Terrains.BRIDGE, Terrains.BRIDGE_CF, Terrains.BRIDGE_ELEV));
        assertNull(hex.getAppearance().get("bridge"));
        assertEquals(1, hex.terrainLevel(Terrains.WATER), "the ground stays");

        assertThrows(IllegalArgumentException.class, () -> BridgeSpan.carry(new Hex(0, "bridge_road:1:9", ""), 1, "bridge:1"));
        Hex empty = new Hex(0, "water:1", "");
        BridgeSpan.carry(empty, 1, "bridge:1;bridge_cf:40");
        assertFalse(empty.hasBridge(), "no ways, no bridge");
    }

    @Test
    void carryNeverReshapesADrawnBridge() {
        for (HexAppearance type : new HexAppearance[] { HexAppearance.BUILT_BRIDGE, HexAppearance.NATURAL_BRIDGE, null }) {
            Hex hex = new Hex(0, "bridge:1:63;bridge_cf:90;bridge_elev:2;bridge_road:1:9", "");
            if (type != null) { hex.setAppearance(Map.of("bridge", type)); }
            BridgeSpan.carry(hex, 1, "bridge:1;bridge_cf:40");
            assertEquals(63, hex.getTerrain(Terrains.BRIDGE).getExits(), String.valueOf(type));
            hex.removeTerrain(Terrains.BRIDGE_ROAD);
            BridgeSpan.carry(hex, 1, "bridge:1;bridge_cf:40");
            assertTrue(hex.hasBridge(), "a drawn bridge stays without ways: " + type);
            assertEquals(90, hex.terrainLevel(Terrains.BRIDGE_CF));
        }
    }
}
