/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Coords;
import megamek.common.enums.BuildingType;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class GpuWallPreviewTest {
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
