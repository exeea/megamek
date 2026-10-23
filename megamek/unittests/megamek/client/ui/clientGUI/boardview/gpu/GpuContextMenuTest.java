/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

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
     * The hex menu's header and the hover card follow the terrain line with the hex tooltip's lines below it, as
     * MegaMek words them: a bridge's height and CF, which no terrain name carries. A tooltip of another hex shows
     * neither, so lines of one capture never pair with another hex.
     */
    @Test
    void aBridgesHeightAndCfFollowTheTerrainLine() {
        Hex bridge = new Hex(0, "bridge:1:9;bridge_elev:1;bridge_cf:40", "", HEX);
        String details = GpuBoardSource.hexDetails(bridge, null, 0);
        assertEquals(List.of("Bridge", "Height: 1, CF: 40"), details.lines().toList());

        BoardScene scene = mock(BoardScene.class);
        BoardScene.Tile tile = mock(BoardScene.Tile.class);
        when(scene.tile(HEX)).thenReturn(tile);
        String tooltip = terrainLine(0, "bridge:1:9;bridge_elev:1;bridge_cf:40");
        assertEquals(GpuContextMenu.subtitle(tooltip, HEX, 0) + "\n" + details,
              GpuContextMenu.hexLines(frame(scene, tooltip, details), HEX));
        when(scene.tile(new Coords(14, 12))).thenReturn(tile);
        assertNull(GpuContextMenu.hexLines(frame(scene, tooltip, details), new Coords(14, 12)));
    }

    private static BoardSource.Frame frame(BoardScene scene, String tooltip, String details) {
        return new BoardSource.Frame(scene, List.of(), null, List.of(), tooltip, details, null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, GpuBattleStatus.Snapshot.EMPTY, GpuHudData.EMPTY);
    }

    /**
     * The units of a hex that the unit menus list first and repeated clicks take in turn: every unit the scene draws
     * there, in the scene's order, a grounded DropShip in each hex of its footprint and a unit drawn in parts once.
     */
    @Test
    void aHexsStackHoldsEveryUnitDrawnThereOnce() {
        Coords centre = new Coords(10, 10);
        Coords edge = centre.translated(2);
        BoardScene.Unit dropShip = new BoardScene.Unit(1, -1, "Union", new BoardScene.Waypoint(centre, 0, 0), null,
              false, null, 1, false, null, 0, List.of(centre, edge, centre.translated(5)));
        BoardScene scene = mock(BoardScene.class);
        when(scene.units()).thenReturn(List.of(dropShip, drawn(2, -1, edge), drawn(3, 0, edge), drawn(3, 1, edge),
              drawn(4, -1, new Coords(1, 1))));
        assertEquals(List.of(1, 2, 3), GpuContextMenu.stack(scene, edge));
        assertEquals(List.of(1), GpuContextMenu.stack(scene, centre));
        assertEquals(List.of(), GpuContextMenu.stack(scene, new Coords(5, 5)));
        assertEquals(List.of(), GpuContextMenu.stack(null, edge));
    }

    private static BoardScene.Unit drawn(int id, int part, Coords hex) {
        return new BoardScene.Unit(id, part, "Unit " + id, new BoardScene.Waypoint(hex, 0, 0), null, false, null, 1,
              false);
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
