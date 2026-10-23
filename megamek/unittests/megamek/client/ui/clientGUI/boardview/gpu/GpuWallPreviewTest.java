/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.BuildingType;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuWallPreviewTest {
    @Test void nearbyCommittedWallsStayOpaqueUnlessTheStrokeChangesThem() {
        var session = new BoardEditorSession();
        session.game().setBoard(Board.createEmptyBoard(12, 12));
        GpuRoutePreviewTest.choose(session, "wall");
        Coords start = new Coords(5, 3), middle = start.translated(3), join = middle.translated(3);
        GpuRoutePreviewTest.drag(session, List.of(start, middle, join));
        session.finishStroke();
        var before = scene(session.board());
        try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
            for (Coords nearby : List.of(join.translated(2), join.translated(3), join.translated(4))) {
                var data = GpuRoutePreviewTest.data(session, capture, nearby);
                assertTrue(data.routes().containsKey(join), "Committed neighbours still supply connection context");
                assertEquals(Set.of(nearby), changed(before, data), "Hovering nearby must not ghost the existing wall");
            }
            var unchanged = GpuRoutePreviewTest.data(session, capture, middle);
            assertTrue(changed(before, unchanged).isEmpty(), "Painting the same wall changes no model");
            Coords extension = join.translated(2);
            GpuRoutePreviewTest.drag(session, List.of(join, extension));
            var branch = GpuRoutePreviewTest.data(session, capture, extension);
            assertEquals(Set.of(join, extension), changed(before, branch), "Only the old terminal and new arm change");
            session.cancelStroke();
            assertEquals(Set.of(extension), changed(before, GpuRoutePreviewTest.data(session, capture, extension)));
        }
    }

    @Test void connectedArmHeightAndPristineFamilyChangesStillGhostTheWall() {
        var board = Board.createEmptyBoard(12, 12);
        Coords at = new Coords(5, 5), neighbor = at.translated(0);
        board.setHex(at, wall(1, 3, 40));
        board.setHex(neighbor, wall(8, 3, 40));
        var before = scene(board);
        board.setHex(neighbor, wall(8, 1, 40));
        assertEquals(Set.of(at, neighbor), changed(before, scene(board), Set.of(at, neighbor)),
              "A neighbour's lower roof changes the connected arm even when the wall's own fields are unchanged");
        board.setHex(neighbor, wall(8, 3, 40));
        board.setHex(at, wall(1, 3, 39));
        assertTrue(changed(before, scene(board), Set.of(at, neighbor)).isEmpty(),
              "CF edits within one family do not change its model");
        board.setHex(at, wall(1, 3, 41));
        assertEquals(Set.of(at), changed(before, scene(board), Set.of(at, neighbor)));
    }

    private static Hex wall(int exits, int height, int cf) {
        var hex = new Hex();
        hex.addTerrain(new Terrain(Terrains.BUILDING, BuildingType.WALL.getTypeValue(), true, exits));
        hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, height));
        hex.addTerrain(new Terrain(Terrains.BLDG_CF, cf));
        return hex;
    }

    private static BoardScene scene(Board board) {
        return new BoardScene(0, board.getWidth(), board.getHeight(), BoardTiles.captureAll(board), List.of(), List.of(),
              -1, "Editor", List.of(), new BoardScene.Light(1, -1));
    }

    private static Set<Coords> changed(BoardScene before, GpuEditorPreview.Data data) {
        var tiles = new ArrayList<>(before.tiles());
        data.routes().forEach((at, tile) -> tiles.set(at.getX() * before.height() + at.getY(), tile));
        return changed(before, before.withTiles(tiles), data.routes().keySet());
    }

    /** The tiles whose route pieces the committed builders make differently, as the editor preview replaces them. */
    private static Set<Coords> changed(BoardScene before, BoardScene after, Set<Coords> candidates) {
        return Set.copyOf(GpuTerrain.changedRoutes(before, after, List.copyOf(candidates), TerrainLod.FULL,
              TerrainSettings.capture(), false));
    }

    @Test void wallPreviewShowsThePendingPathHeightAndConnectionLinesWithoutChangingTheBoard() {
        var session = new BoardEditorSession();
        session.command(new Command(Action.CHOOSE_BRUSH, "building", "wall"), null);
        Coords start = new Coords(5, 5), middle = start.translated(0), end = middle.translated(1);
        session.pointer(start, 0, 0, false);
        session.pointer(middle, 0, 0, true);
        session.pointer(end, 0, 0, true);
        try (var capture = new BoardTiles(new BoardScene.PixelPool())) {
            var drawn = session.preview(end, 0, 0, "ground");
            var preview = GpuEditorPreview.data(drawn, false, session.snapshot().activeBrush(), session.snapshot().drawsRoutes(),
                  (at, hex) -> capture.preview(session.board(), at, hex));
            assertEquals(List.of(start, middle, end), preview.hexes());
            assertTrue(preview.label().contains(BuildingType.WALL.toString()));
            assertEquals(3, preview.standIns().size());
            var junction = preview.standIns().stream().filter(part -> part.coords().equals(middle)).findFirst().orElseThrow();
            assertEquals(10, junction.exits());
            assertEquals(0, junction.low());
            assertEquals(2, junction.high());
            for (Coords at : preview.hexes()) { assertFalse(session.board().getHex(at).containsTerrain(Terrains.BUILDING)); }

            session.pointer(middle, 0, 0, true);
            var retraced = GpuEditorPreview.data(session.preview(middle, 0, 0, "ground"), false, session.snapshot().activeBrush(),
                  session.snapshot().drawsRoutes(), (at, hex) -> capture.preview(session.board(), at, hex));
            assertEquals(List.of(start, middle), retraced.hexes());
            assertEquals(8, retraced.standIns().stream().filter(part -> part.coords().equals(middle)).findFirst().orElseThrow().exits());
        }
    }
}
