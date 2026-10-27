/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.utils.BoardLoader;
import org.junit.jupiter.api.Test;

/**
 * b13 ways: a legacy board's decks get the road the 3D board drew on them before decks stored their roads, once, in the
 * conversion: a built span the best road that meets it, over all its exits; one no road meets an Alley road, unless it
 * is a rail bridge; a natural span nothing. Its bridge exits are written out, so a deck drawn later never joins it by
 * itself, and a one-sided link between a road and a deck within a level of it joins. A native board is taken as it is.
 */
class BoardLegacyDeckRoadTest {
    /** A N-S span of two decks over water, 0102 and 0103, between the banks 0101 and 0104 (level and tokens given). */
    private static Board span(int northLevel, String north, String south, boolean nativeBoard) {
        Board board = BoardLoader.initializeBoard("""
              size 1 4
              hex 0101 %d "%s" ""
              hex 0102 0 "water:1;bridge:1:09;bridge_cf:40;bridge_elev:0" ""
              hex 0103 0 "water:1;bridge:1:09;bridge_cf:40;bridge_elev:0" ""
              hex 0104 0 "%s" ""
              end""".formatted(northLevel, north, south));
        board.setNativeFormat(nativeBoard);
        BoardSceneryLayouts.convert(board);
        return board;
    }

    private static Board span(String north, String south) { return span(0, north, south, false); }

    /** The ROAD level of both decks' road, 0 for none; both decks carry the same road over all their exits. */
    private static int deckRoad(Board board) {
        Terrain first = board.getHex(0, 1).getTerrain(Terrains.BRIDGE_ROAD);
        Terrain second = board.getHex(0, 2).getTerrain(Terrains.BRIDGE_ROAD);
        assertEquals(String.valueOf(first), String.valueOf(second), "One road over the whole span");
        if (first == null) { return 0; }
        assertTrue(first.hasExitsSpecified());
        assertEquals(board.getHex(0, 1).getTerrain(Terrains.BRIDGE).getExits(), first.getExits(), "Over all its exits");
        return first.getLevel();
    }

    @Test
    void aBuiltSpanTakesTheBestRoadThatMeetsItPavedAlleyGravelDirt() {
        assertEquals(4, deckRoad(span("road:3", "road:4")), "Gravel over dirt");
        assertEquals(2, deckRoad(span("road:4", "road:2")), "Alley over gravel");
        assertEquals(1, deckRoad(span("road:2", "road:1")), "Paved over alley");
        Board board = span("road:3", "road:3");
        assertEquals(3, deckRoad(board));
        assertEquals(3, board.getHex(0, 0).terrainLevel(Terrains.ROAD), "The bank roads stay as they are");
        assertEquals(Boolean.TRUE, HexAppearance.bridgeBuilt(board.getHex(0, 1).getAppearance()));
    }

    @Test
    void onlyARoadThatMeetsTheDeckAtItsHeightVotes() {
        // A paved road two levels below the deck does not meet it.
        assertEquals(3, deckRoad(span(-2, "road:1", "road:3", false)));
    }

    @Test
    void aRoadThroughAFordMeetsTheDeck() {
        // As Flooded City's streets: depth-0 water, whose road lies on its bed.
        assertEquals(1, deckRoad(span("road:1;water:0", "road:3")));
    }

    @Test
    void aMapsOneSidedLinksBetweenARoadAndADeckWithinALevelJoin() {
        // The deck leads onto a road (NE-SW) whose written exits miss it: the road leads back, and votes.
        Board beside = span(0, "road:1:18", "road:3", false);
        assertEquals(18 | 1 << 3, beside.getHex(0, 0).getTerrain(Terrains.ROAD).getExits());
        assertEquals(1, deckRoad(beside));
        // A road leads into a deck that does not lead back to it: the deck leads back, and the road votes (an Alley
        // otherwise: pavement is no road).
        Board into = BoardLoader.initializeBoard("""
              size 1 4
              hex 0101 0 "pavement:1" ""
              hex 0102 0 "water:1;bridge:1:09;bridge_cf:40;bridge_elev:0" ""
              hex 0103 0 "water:1;bridge:1:01;bridge_cf:40;bridge_elev:0" ""
              hex 0104 0 "road:1:01" ""
              end""");
        BoardSceneryLayouts.convert(into);
        assertEquals(9, into.getHex(0, 2).getTerrain(Terrains.BRIDGE).getExits());
        assertEquals(1, deckRoad(into));
    }

    @Test
    void aSpanCarriesTheRoadTheMapLaysOnItsHexesOverWaterAndWithItsDeckOnTheGround() {
        // As Rural vs Urban: decks on the ground at both ends, raised over the water, the map's paved road on each.
        Board board = BoardLoader.initializeBoard("""
              size 1 4
              hex 0101 0 "bridge:1:08;road:1;bridge_cf:40;bridge_elev:0" ""
              hex 0102 -2 "water:2;bridge:1:09;road:1;bridge_cf:40;bridge_elev:2" ""
              hex 0103 -2 "water:2;bridge:1:09;road:1;bridge_cf:40;bridge_elev:2" ""
              hex 0104 0 "bridge:1:01;road:1;bridge_cf:40;bridge_elev:0" ""
              end""");
        BoardSceneryLayouts.convert(board);
        assertEquals(1, deckRoad(board), "Paved, not the Alley of a span no road meets");
    }

    @Test
    void aSpanNoRoadMeetsIsAnAlleyUnlessItIsARailBridge() {
        assertEquals(2, deckRoad(span("pavement:1", "pavement:1")), "Plain asphalt, as the 3D board drew it");
        Board rail = span("fluff:10:9", "fluff:10:9");
        assertEquals(0, deckRoad(rail), "A rail bridge's deck is bare concrete and track");
        assertEquals(Boolean.TRUE, HexAppearance.bridgeBuilt(rail.getHex(0, 1).getAppearance()));
        assertEquals(9, rail.getHex(0, 1).getTerrain(Terrains.BRIDGE_RAIL).getExits(), "It carries its rail");
    }

    @Test
    void aNaturalSpanCarriesNothing() {
        Board board = span("", "");
        assertEquals(Boolean.FALSE, HexAppearance.bridgeBuilt(board.getHex(0, 1).getAppearance()));
        assertEquals(0, deckRoad(board));
        assertTrue(board.getHex(0, 1).isClearDeck(), "A bare rock arch is clear");
    }

    @Test
    void legacyBridgeExitsAreWrittenOutAndANativeBoardIsTakenAsItIs() {
        String text = """
              size 1 4
              hex 0101 0 "road:1" ""
              hex 0102 0 "water:1;bridge:1:09;bridge_cf:40;bridge_elev:0" ""
              hex 0103 0 "water:1;bridge:1;bridge_cf:40;bridge_elev:0" ""
              hex 0104 0 "water:1;bridge:1;bridge_cf:40;bridge_elev:0" ""
              end""";
        Board legacy = BoardLoader.initializeBoard(text);
        BoardSceneryLayouts.convert(legacy);
        for (int y = 1; y <= 3; y++) {
            Terrain bridge = legacy.getHex(0, y).getTerrain(Terrains.BRIDGE);
            assertTrue(bridge.hasExitsSpecified(), "row " + y + ": written out");
            assertEquals(y == 3 ? 1 : 9, bridge.getExits(), "row " + y + ": the exits the load derived");
        }
        Board drafted = BoardLoader.initializeBoard(text);
        drafted.setNativeFormat(true);
        BoardSceneryLayouts.convert(drafted);
        Hex automatic = drafted.getHex(0, 2);
        assertFalse(automatic.getTerrain(Terrains.BRIDGE).hasExitsSpecified(), "A native board keeps its automatic exits");
        assertFalse(automatic.containsTerrain(Terrains.BRIDGE_ROAD), "and gets no deck road");
        assertFalse(drafted.getHex(0, 1).containsTerrain(Terrains.BRIDGE_ROAD));
    }
}
