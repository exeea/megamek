/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaglevRouteEditorTest {
    @TempDir Path directory;

    @Test void paintsGaplessRoutesWithoutDuplicatesAndReusesUndoAndSave() throws Exception {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", MaglevRoute.ASSET);
        send(session, Action.BRUSH_VALUE, "object:offset", "2.5");
        var start = new Coords(2, 1); var end = new Coords(2, 6);
        session.pointer(start, .3, .2, false);
        session.pointer(end, -.2, .4, true);
        session.pointer(start, 0, 0, true);
        session.finishStroke();
        for (var at : Coords.intervening(start, end)) {
            var hex = session.board().getHex(at);
            assertEquals(1, hex.getDecorations().size());
            var route = MaglevRoute.find(hex);
            assertNotNull(route);
            assertEquals(0, route.x()); assertEquals(0, route.y());
            assertEquals(2.5, route.placement().offset());
            assertFalse(hex.containsAnyTerrainOf(Terrains.ROAD, Terrains.BRIDGE));
        }
        send(session, Action.UNDO, "", "");
        for (var at : Coords.intervening(start, end)) { assertNull(MaglevRoute.find(session.board().getHex(at))); }
        send(session, Action.REDO, "", "");
        Path file = directory.resolve("maglev.board2"); session.save(file);
        var restored = BoardFile.read(file);
        for (var at : Coords.intervening(start, end)) {
            assertEquals(session.board().getHex(at).getDecorations(), restored.getHex(at).getDecorations());
        }
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(new Coords(2, 3), 0, 0, false); session.finishStroke();
        assertNull(MaglevRoute.find(session.board().getHex(new Coords(2, 3))));
        assertEquals(MaglevRoute.side(0), MaglevRoute.find(session.board().getHex(new Coords(2, 2))).connections(),
              "Erasing clears the neighbours' sides towards the erased hex");
        assertEquals(MaglevRoute.side(3), MaglevRoute.find(session.board().getHex(new Coords(2, 4))).connections());
    }

    @Test void consecutiveStrokeHexesJoinWhileParallelStrokesStayApartAndSidesToggleInPairs() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", MaglevRoute.ASSET);
        for (int x : new int[] { 2, 3 }) {
            session.pointer(new Coords(x, 1), 0, 0, false);
            session.pointer(new Coords(x, 4), 0, 0, true);
            session.finishStroke();
        }
        for (int x : new int[] { 2, 3 }) {
            for (int y = 1; y <= 4; y++) {
                int expected = (y > 1 ? MaglevRoute.side(0) : 0) | (y < 4 ? MaglevRoute.side(3) : 0);
                assertEquals(expected, route(session, x, y).connections(), x + "," + y + ": only its own stroke's neighbours");
            }
        }
        // A stroke that starts on a route extends it.
        session.pointer(new Coords(2, 4), 0, 0, false);
        session.pointer(new Coords(2, 5), 0, 0, true);
        session.finishStroke();
        assertTrue(MaglevRoute.joined(route(session, 2, 4), 3, route(session, 2, 5)));
        // The inspector's side toggle joins two parallel markers, and parts them again, as one undo step each.
        var west = new Coords(2, 2);
        int towardsEast = west.direction(new Coords(3, 2));
        session.pointer(west, 0, 0, false); session.finishStroke();
        send(session, Action.OBJECT_VALUE, "side", Integer.toString(towardsEast));
        assertTrue(MaglevRoute.joined(route(session, 2, 2), towardsEast, route(session, 3, 2)));
        send(session, Action.UNDO, "", "");
        assertFalse(MaglevRoute.joined(route(session, 2, 2), towardsEast, route(session, 3, 2)));
        assertEquals(MaglevRoute.side(0) | MaglevRoute.side(3), route(session, 3, 2).connections());
        // Removing a marker clears the sides that pointed at it.
        send(session, Action.REMOVE_OBJECT, route(session, 2, 2).id(), "");
        assertNull(MaglevRoute.find(session.board().getHex(west)));
        assertEquals(0, route(session, 2, 1).connections());
        assertEquals(MaglevRoute.side(3), route(session, 2, 3).connections());
    }

    private static megamek.common.board.BoardDecoration route(BoardEditorSession session, int x, int y) {
        return MaglevRoute.find(session.board().getHex(new Coords(x, y)));
    }

    @Test void fixedElevationsSurvivePaintingAndRepaintingKeepsIdentity() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", MaglevRoute.ASSET);
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        var at = new Coords(2, 2);
        session.pointer(at, 0, 0, false); session.finishStroke();
        var before = MaglevRoute.find(session.board().getHex(at));
        assertEquals("absolute", before.placement().mode());
        assertEquals(4, before.placement().level());
        send(session, Action.BRUSH_VALUE, "object:level", "5");
        session.pointer(at, 0, 0, false); session.finishStroke();
        var after = MaglevRoute.find(session.board().getHex(at));
        assertEquals(before.id(), after.id()); assertEquals(5, after.placement().level());
        send(session, Action.OBJECT_VALUE, "rotation", "60");
        assertEquals(0, MaglevRoute.find(session.board().getHex(at)).rotation());
    }

    @Test void editsKeepAMarkersSidesAndMovesAndPastesKeepOnlyJoinedSides() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", MaglevRoute.ASSET);
        session.pointer(new Coords(2, 1), 0, 0, false); session.pointer(new Coords(2, 4), 0, 0, true); session.finishStroke();
        int through = MaglevRoute.side(0) | MaglevRoute.side(3);
        // Level, height, support and name edits keep the stored sides (OBJECT_VALUE rebuilds the marker).
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(2, 2), 0, 0, false, route(session, 2, 2).id()); session.finishStroke(); session.release();
        String[][] edits = { { "level", "3" }, { "receiver", "ground/top" }, { "offset", "2" }, { "receiver", "absolute" }, { "name", "Spur" } };
        for (String[] edit : edits) {
            send(session, Action.OBJECT_VALUE, edit[0], edit[1]);
            assertEquals(through, route(session, 2, 2).connections(), edit[0] + " keeps the sides");
        }
        assertEquals("Spur", route(session, 2, 2).name());

        // Moving the middle two hexes one hex east keeps their own join and parts them from the hexes they left.
        var middle = java.util.List.of(new BoardEditorSession.Selection(new Coords(2, 2), ""),
              new BoardEditorSession.Selection(new Coords(2, 3), ""));
        session.select(middle, BoardEditorSession.SelectMode.REPLACE);
        session.pointer(new Coords(2, 2), 0, 0, false); session.pointer(new Coords(3, 2), 0, 0, true); session.finishStroke(); session.release();
        assertNull(MaglevRoute.find(session.board().getHex(new Coords(2, 2))));
        assertEquals(0, route(session, 2, 1).connections(), "No side points at the vacated hex");
        assertEquals(0, route(session, 2, 4).connections());
        var top = new Coords(3, 2);
        var below = top.translated(3);
        assertTrue(MaglevRoute.joined(route(session, top.getX(), top.getY()), 3, route(session, below.getX(), below.getY())),
              "The moved markers stay joined");
        assertEquals(MaglevRoute.side(3), route(session, top.getX(), top.getY()).connections(), "No open side towards the old hex");
        send(session, Action.UNDO, "", "");
        assertEquals(through, route(session, 2, 2).connections());
        assertEquals(MaglevRoute.side(3), route(session, 2, 1).connections());

        // A pasted marker keeps no side that the neighbour does not return, and the neighbours part from what it replaced.
        session.pointer(new Coords(2, 2), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.COPY, "", "");
        session.pointer(new Coords(5, 5), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.PASTE, "", "");
        assertEquals(0, route(session, 5, 5).connections());
        session.pointer(new Coords(2, 4), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.PASTE, "", "");
        assertEquals(MaglevRoute.side(0), route(session, 2, 4).connections(), "Joined only to the marker north of it");
        assertEquals(through, route(session, 2, 3).connections());
    }

    @Test void aBoxLeavesRouteMarkersOutAndAMovingSelectionLeavesThemInPlace() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", MaglevRoute.ASSET);
        var at = new Coords(2, 2);
        session.pointer(at, 0, 0, false); session.finishStroke();
        send(session, Action.TOOL, "", "SELECT");
        var hex = session.board().getHex(at);
        var car = new megamek.common.board.BoardDecoration("car", "prop", "scenery/vehicles/car", null, .2, 0, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var objects = new java.util.ArrayList<>(hex.getDecorations()); objects.add(car); hex.setDecorations(objects);
        String marker = route(session, 2, 2).id();
        var routeItem = new BoardEditorSession.Selection(at, marker);
        var carItem = new BoardEditorSession.Selection(at, "car");
        session.select(java.util.List.of(routeItem, carItem), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.List.of(carItem), session.snapshot().selection(), "A box takes the car, not the marker");
        session.pointer(at, .2, 0, false, "car"); session.pointer(at, .3, 0, true, "car"); session.finishStroke(); session.release();
        assertEquals(.3, x(session, at, "car"), 1e-9);
        // A clicked-in marker stays where it is while the rest of the selection moves.
        session.pointer(at, 0, 0, false, marker, true); session.finishStroke(); session.release();
        assertEquals(2, session.snapshot().selection().size());
        session.pointer(at, 0, 0, false, marker); session.pointer(at, .1, 0, true, marker); session.finishStroke(); session.release();
        assertEquals(0, route(session, 2, 2).x());
        assertEquals(.4, x(session, at, "car"), 1e-9);
        // The marker alone does not move and says why.
        send(session, Action.CLEAR_SELECTION, "", "");
        session.pointer(at, 0, 0, false, marker); session.finishStroke(); session.release();
        session.pointer(at, 0, 0, false, marker); session.pointer(at, .1, 0, true, marker);
        assertEquals("Paint or erase hexes to change a maglev route.", session.snapshot().message());
        session.finishStroke(); session.release();
        assertEquals(0, route(session, 2, 2).x());
    }

    @Test void aClickOnOneItemOfABoxSelectionNarrowsToIt() {
        var session = new BoardEditorSession();
        var at = new Coords(2, 2);
        session.board().getHex(at).setDecorations(java.util.List.of(
              new megamek.common.board.BoardDecoration("a", "prop", "scenery/vehicles/car", null, -.2, 0, 0, false, 1,
                    megamek.common.board.BoardDecoration.Placement.ground(), 0),
              new megamek.common.board.BoardDecoration("b", "prop", "scenery/vehicles/car", null, .2, 0, 0, false, 1,
                    megamek.common.board.BoardDecoration.Placement.ground(), 0)));
        var a = new BoardEditorSession.Selection(at, "a");
        var b = new BoardEditorSession.Selection(at, "b");
        session.select(java.util.List.of(a, b), BoardEditorSession.SelectMode.REPLACE);
        session.pointer(at, .2, 0, false, "b"); session.finishStroke(); session.release();
        assertEquals(java.util.List.of(b), session.snapshot().selection());
        // A drag from the same press moves the whole selection instead.
        session.select(java.util.List.of(a, b), BoardEditorSession.SelectMode.REPLACE);
        session.pointer(at, .2, 0, false, "b"); session.pointer(at, .3, 0, true, "b"); session.finishStroke(); session.release();
        assertEquals(java.util.Set.of(a, b), java.util.Set.copyOf(session.snapshot().selection()));
        assertEquals(.3, x(session, at, "b"), 1e-9);
        assertEquals(-.1, x(session, at, "a"), 1e-9);
    }

    private static double x(BoardEditorSession session, Coords at, String id) {
        return session.board().getHex(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow().x();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
