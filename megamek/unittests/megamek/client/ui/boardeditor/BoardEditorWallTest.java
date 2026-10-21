/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorWallTest {
    @TempDir Path directory;
    private static final Coords CENTER = new Coords(5, 5);

    @Test
    void aNewSegmentJoinsBothSidesInEveryDirectionAndUndoAndSaveKeepThoseExits() throws Exception {
        for (int direction = 0; direction < 6; direction++) {
            var session = wallBrush();
            Coords next = CENTER.translated(direction);
            paint(session, CENTER);
            paint(session, next);
            assertEquals(1 << direction, exits(session, CENTER));
            assertEquals(1 << ((direction + 3) % 6), exits(session, next));
            send(session, Action.UNDO, "", "");
            assertEquals(0, exits(session, CENTER), "Undo restores the previously placed neighbor too");
            assertFalse(session.board().getHex(next).containsTerrain(Terrains.BUILDING));
            send(session, Action.REDO, "", "");
            Path file = directory.resolve("walls-" + direction + ".board2");
            session.save(file);
            var loaded = BoardFile.read(file);
            assertEquals(exits(session, CENTER), loaded.getHex(CENTER).getTerrain(Terrains.BUILDING).getExits());
            assertEquals(exits(session, next), loaded.getHex(next).getTerrain(Terrains.BUILDING).getExits());
        }
    }

    @Test
    void erasingASegmentRemovesOnlyItsNeighborsBackEdgeAndUndoRestoresTheJoin() {
        var session = wallBrush();
        Coords north = CENTER.translated(0), far = north.translated(0);
        session.board().setHex(north, wall(1));
        session.board().setHex(far, wall(8));
        paint(session, CENTER);
        assertEquals(9, exits(session, north));
        send(session, Action.TOOL, "", "ERASE");
        paint(session, CENTER);
        assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BUILDING));
        assertEquals(1, exits(session, north), "The neighbor's other authored connection survives");
        assertEquals(8, exits(session, far));
        send(session, Action.UNDO, "", "");
        assertEquals(1, exits(session, CENTER));
        assertEquals(9, exits(session, north));
        send(session, Action.REDO, "", "");
        assertFalse(session.board().getHex(CENTER).containsTerrain(Terrains.BUILDING));
        assertEquals(1, exits(session, north));
    }

    @Test
    void repaintingIdenticalUnjoinedWallsPreviewsAndCommitsOnlyTheMissingConnections() {
        var session = wallBrush();
        Coords next = CENTER.translated(0);
        session.board().setHex(CENTER, wall(0));
        session.board().setHex(next, wall(0));
        var preview = session.preview(CENTER, 0, 0, "ground");
        assertEquals(1, preview.after().get(CENTER).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(8, preview.after().get(next).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(0, exits(session, CENTER), "Preview restores both live hexes");
        assertEquals(0, exits(session, next));
        paint(session, CENTER);
        assertEquals(1, exits(session, CENTER));
        assertEquals(8, exits(session, next));
        send(session, Action.UNDO, "", "");
        assertEquals(0, exits(session, CENTER));
        assertEquals(0, exits(session, next));
    }

    @Test
    void wallBrushUsesTheExistingBuildingTypeClassAndEmplacementConnectionRules() {
        for (String neighbor : new String[] {
              "building:4:0;bldg_cf:120;bldg_elev:2",
              "building:5:0;bldg_cf:120;bldg_elev:2;bldg_class:1",
              "building:5:0;bldg_cf:120;bldg_elev:2;bldg_class:3" }) {
            var session = wallBrush();
            if (neighbor.endsWith("bldg_class:3")) { send(session, Action.BRUSH_VALUE, "terrain:bldg_class", "3"); }
            Coords next = CENTER.translated(0);
            session.board().setHex(next, new Hex(0, neighbor, ""));
            paint(session, CENTER);
            assertEquals(0, exits(session, CENTER), neighbor);
            assertEquals(0, exits(session, next), neighbor);
        }
    }

    @Test
    void unrelatedEditsDoNotReconnectAuthoredWallExits() {
        var session = wallBrush();
        Coords next = CENTER.translated(0);
        session.board().setHex(CENTER, wall(0));
        session.board().setHex(next, wall(0));
        session.adjustElevation(CENTER, 1);
        session.finishStroke();
        assertEquals(0, exits(session, CENTER), "Arming a wall brush does not rewrite an elevation edit");
        assertEquals(0, exits(session, next));
        send(session, Action.CHOOSE_BRUSH, "ground", "clear");
        session.adjustElevation(next, 1);
        session.finishStroke();
        assertEquals(0, exits(session, CENTER));
        assertEquals(0, exits(session, next));
    }

    private static Hex wall(int exits) {
        return new Hex(0, "building:5:" + exits + ";bldg_cf:120;bldg_elev:2;bldg_basement_type:1", "");
    }

    private static BoardEditorSession wallBrush() {
        var session = new BoardEditorSession();
        send(session, Action.CHOOSE_BRUSH, "building", "wall");
        return session;
    }

    private static void paint(BoardEditorSession session, Coords at) {
        session.pointer(at, 0, 0, false);
        session.finishStroke();
    }

    private static int exits(BoardEditorSession session, Coords at) {
        return session.board().getHex(at).getTerrain(Terrains.BUILDING).getExits();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
