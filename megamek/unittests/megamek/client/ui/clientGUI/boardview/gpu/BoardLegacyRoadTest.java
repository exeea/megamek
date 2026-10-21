/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardLegacyRoadTest {
    @Test
    void corpusRoadLevelsUseTheGamesPavedRuleWithoutRewritingTerrainOrExits() {
        for (int level : new int[] { 0, 1, 5, 6, 7, 17, 34, 90 }) {
            for (int exits = 0; exits < 64; exits++) {
                var hex = new Hex(0, "road:" + level + ":" + exits, "");
                String terrain = hex.getTerrain(Terrains.ROAD).toString();
                assertTrue(hex.hasPavedRoad(), "The authoritative game rule accepts road level " + level);
                assertTrue(HexTile.road(hex));
                assertEquals(BoardRoad.Kind.PAVED, BoardRoad.capture(hex));
                assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
                var at = new Coords(3, 3);
                var road = BoardRoad.layout(at, exits, BoardRoad.capture(hex), direction -> BoardRoad.Kind.NONE);
                for (int edge = 0; edge < 6; edge++) {
                    int next = (edge + 1) % 6;
                    float x = (HexTile.cornerOffsetX(edge, HexTile.WIDTH)
                          + HexTile.cornerOffsetX(next, HexTile.WIDTH)) * .49f;
                    float y = (HexTile.cornerOffsetY(edge, HexTile.HEIGHT)
                          + HexTile.cornerOffsetY(next, HexTile.HEIGHT)) * .49f;
                    assertEquals((exits & 1 << HexTile.edgeDirection(edge)) != 0, road.footprint(0).contains(x, y),
                          "The drawn road keeps only its authored exits");
                }
                assertEquals(terrain, hex.getTerrain(Terrains.ROAD).toString());
            }
        }
    }

    @Test
    void unusualPavedApproachesGiveImportedBridgesPavedDecks() {
        for (int level : new int[] { 0, 5, 6, 7, 34, 90 }) {
            Board board = Board.createEmptyBoard(3, 5);
            var deck = new Coords(1, 2);
            var approach = deck.translated(0);
            board.setHex(deck, new Hex(0, "water:1;bridge:1:9;bridge_cf:40;bridge_elev:0", ""));
            board.setHex(approach, new Hex(0, "road:" + level + ":9", ""));
            BoardSceneryLayouts.convert(board);
            assertEquals(BoardRoad.Kind.PAVED, BoardRoad.deck(board.getHex(deck)));
            assertEquals(level, board.getHex(approach).terrainLevel(Terrains.ROAD));
        }
    }
}
