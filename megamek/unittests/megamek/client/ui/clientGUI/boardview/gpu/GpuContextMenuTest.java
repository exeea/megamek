/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.tooltip.HexTooltip;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

/**
 * The hex menu's subtitle (M5) reads MegaMek's own terrain line of the hex tooltip; these cases run real hexes through
 * {@code HexTooltip.getTerrainTip} and the source's plain-text conversion, so a change of that line's format fails
 * here.
 */
class GpuContextMenuTest {
    static final Coords HEX = new Coords(14, 11);

    @Test
    void theSubtitleNamesMegaMeksTerrainAndTheLevel() {
        assertEquals("Light woods (TF: 50) · Road (TF: 150) · Woods/Jungle elevation: 2 · level 0",
              GpuContextMenu.subtitle(terrainLine(0, "woods:1;foliage_elev:2;road:1:9"), HEX, 0));
        assertEquals("Water (depth 2) · level -2",
              GpuContextMenu.subtitle(terrainLine(-2, "water:2"), HEX, -2));
        // The line of a hex without terrain names none: the prototype's "Clear" (a key for the text pass T1).
        assertEquals(Messages.getString("GpuBoard.hud.context.hexLevel",
              Messages.getString("GpuBoard.hud.context.clearTerrain"), 3), GpuContextMenu.subtitle(terrainLine(3, ""),
              HEX, 3));
    }

    @Test
    void anotherHexOrLevelGivesNoSubtitle() {
        String woods = terrainLine(1, "woods:1");
        assertNull(GpuContextMenu.subtitle(woods, new Coords(14, 12), 1), "the tooltip of another hex");
        assertNull(GpuContextMenu.subtitle(terrainLine(10, "rough:1"), HEX, 1), "level 1 is not level 10");
        assertNull(GpuContextMenu.subtitle("", HEX, 1), "no terrain line (the hex tooltip is switched off)");
        assertNull(GpuContextMenu.subtitle(null, HEX, 1));
    }

    /**
     * The first line of the board tooltip for a hex at {@link #HEX} of that level and terrain (Hex's "name:level"
     * format), as the source's plain-text conversion publishes it, followed by a second line.
     */
    static String terrainLine(int level, String terrain) {
        Game game = new Game();
        Board board = new Board(16, 17);
        Hex hex = new Hex(level, terrain, "", HEX);
        board.setHex(HEX, hex);
        game.setBoard(board);
        return GpuBoardActions.plainText(HexTooltip.getTerrainTip(hex, 0, game)) + "\nDistance: 2 hexes";
    }
}
