/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drawing, editing and erasing rail and maglev routes in the editor (one route model, {@link RailRoute}). */
class RailRouteEditorTest {
    @TempDir Path directory;

    @Test void paintsGaplessRoutesWithoutDuplicatesAndReusesUndoAndSave() throws Exception {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        send(session, Action.BRUSH_VALUE, "object:offset", "2.5");
        var start = new Coords(2, 1); var end = new Coords(2, 6);
        session.pointer(start, .3, .2, false);
        session.pointer(end, -.1, .2, true);
        session.finishStroke();
        // A second stroke back over the route repaints its markers (dragging back within one stroke would retrace).
        session.pointer(end, 0, 0, false);
        session.pointer(start, 0, 0, true);
        session.finishStroke();
        for (var at : Coords.intervening(start, end)) {
            var hex = session.board().getHex(at);
            assertEquals(1, hex.getDecorations().size());
            var route = RailRoute.find(hex, RailRoute.Kind.MAGLEV);
            assertNotNull(route);
            assertEquals(0, route.x()); assertEquals(0, route.y());
            assertEquals(2.5, route.placement().offset());
            assertFalse(hex.containsAnyTerrainOf(Terrains.ROAD, Terrains.BRIDGE));
        }
        // The repaint changed nothing, so it is no undo step: one Undo takes the route back.
        send(session, Action.UNDO, "", "");
        for (var at : Coords.intervening(start, end)) { assertNull(RailRoute.find(session.board().getHex(at), RailRoute.Kind.MAGLEV)); }
        send(session, Action.REDO, "", "");
        Path file = directory.resolve("maglev.board2"); session.save(file);
        var restored = BoardFile.read(file);
        for (var at : Coords.intervening(start, end)) {
            assertEquals(session.board().getHex(at).getDecorations(), restored.getHex(at).getDecorations());
        }
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(new Coords(2, 3), 0, 0, false); session.finishStroke();
        assertNull(RailRoute.find(session.board().getHex(new Coords(2, 3)), RailRoute.Kind.MAGLEV));
        assertEquals(RailRoute.side(0), RailRoute.find(session.board().getHex(new Coords(2, 2)), RailRoute.Kind.MAGLEV).connections(),
              "Erasing clears the neighbours' sides towards the erased hex");
        assertEquals(RailRoute.side(3), RailRoute.find(session.board().getHex(new Coords(2, 4)), RailRoute.Kind.MAGLEV).connections());
    }

    @Test void consecutiveStrokeHexesJoinWhileParallelStrokesStayApartAndSidesToggleInPairs() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        for (int x : new int[] { 2, 3 }) {
            session.pointer(new Coords(x, 1), 0, 0, false);
            session.pointer(new Coords(x, 4), 0, 0, true);
            session.finishStroke();
        }
        for (int x : new int[] { 2, 3 }) {
            for (int y = 1; y <= 4; y++) {
                int expected = (y > 1 ? RailRoute.side(0) : 0) | (y < 4 ? RailRoute.side(3) : 0);
                assertEquals(expected, route(session, x, y).connections(), x + "," + y + ": only its own stroke's neighbours");
            }
        }
        // A stroke that starts on a route extends it.
        session.pointer(new Coords(2, 4), 0, 0, false);
        session.pointer(new Coords(2, 5), 0, 0, true);
        session.finishStroke();
        assertTrue(RailRoute.joined(route(session, 2, 4), 3, route(session, 2, 5)));
        // The inspector's side toggle joins two parallel markers, and parts them again, as one undo step each.
        var west = new Coords(2, 2);
        int towardsEast = west.direction(new Coords(3, 2));
        session.pointer(west, 0, 0, false); session.finishStroke();
        send(session, Action.OBJECT_VALUE, "side", Integer.toString(towardsEast));
        assertTrue(RailRoute.joined(route(session, 2, 2), towardsEast, route(session, 3, 2)));
        send(session, Action.UNDO, "", "");
        assertFalse(RailRoute.joined(route(session, 2, 2), towardsEast, route(session, 3, 2)));
        assertEquals(RailRoute.side(0) | RailRoute.side(3), route(session, 3, 2).connections());
        // Removing a marker clears the sides that pointed at it.
        send(session, Action.REMOVE_OBJECT, route(session, 2, 2).id(), "");
        assertNull(RailRoute.find(session.board().getHex(west), RailRoute.Kind.MAGLEV));
        assertEquals(0, route(session, 2, 1).connections());
        assertEquals(RailRoute.side(3), route(session, 2, 3).connections());
    }

    private static megamek.common.board.BoardDecoration route(BoardEditorSession session, int x, int y) {
        return RailRoute.find(session.board().getHex(new Coords(x, y)), RailRoute.Kind.MAGLEV);
    }

    @Test void fixedElevationsSurvivePaintingAndRepaintingKeepsIdentity() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        var at = new Coords(2, 2);
        session.pointer(at, 0, 0, false); session.finishStroke();
        var before = RailRoute.find(session.board().getHex(at), RailRoute.Kind.MAGLEV);
        assertEquals("absolute", before.placement().mode());
        assertEquals(4, before.placement().level());
        // Within a level of the brush the hex's maglev is repainted, keeping its identity; a level or more away is another route.
        send(session, Action.BRUSH_VALUE, "object:level", "4.5");
        session.pointer(at, 0, 0, false); session.finishStroke();
        var after = RailRoute.find(session.board().getHex(at), RailRoute.Kind.MAGLEV);
        assertEquals(before.id(), after.id()); assertEquals(4.5, after.placement().level());
        assertEquals(1, RailRoute.markers(session.board().getHex(at), RailRoute.Kind.MAGLEV).size());
        send(session, Action.OBJECT_VALUE, "rotation", "60");
        assertEquals(0, RailRoute.find(session.board().getHex(at), RailRoute.Kind.MAGLEV).rotation());
        send(session, Action.BRUSH_VALUE, "object:level", "5.5");
        session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(2, RailRoute.markers(session.board().getHex(at), RailRoute.Kind.MAGLEV).size());
    }

    @Test void editsKeepAMarkersSidesAndMovesAndPastesKeepOnlyJoinedSides() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        session.pointer(new Coords(2, 1), 0, 0, false); session.pointer(new Coords(2, 4), 0, 0, true); session.finishStroke();
        int through = RailRoute.side(0) | RailRoute.side(3);
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
        assertNull(RailRoute.find(session.board().getHex(new Coords(2, 2)), RailRoute.Kind.MAGLEV));
        assertEquals(0, route(session, 2, 1).connections(), "No side points at the vacated hex");
        assertEquals(0, route(session, 2, 4).connections());
        var top = new Coords(3, 2);
        var below = top.translated(3);
        assertTrue(RailRoute.joined(route(session, top.getX(), top.getY()), 3, route(session, below.getX(), below.getY())),
              "The moved markers stay joined");
        assertEquals(RailRoute.side(3), route(session, top.getX(), top.getY()).connections(), "No open side towards the old hex");
        send(session, Action.UNDO, "", "");
        assertEquals(through, route(session, 2, 2).connections());
        assertEquals(RailRoute.side(3), route(session, 2, 1).connections());

        // A pasted marker keeps no side that the neighbour does not return, and the neighbours part from what it replaced.
        session.pointer(new Coords(2, 2), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.COPY, "", "");
        session.pointer(new Coords(5, 5), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.PASTE, "", "");
        assertEquals(0, route(session, 5, 5).connections());
        session.pointer(new Coords(2, 4), 0, 0, false); session.finishStroke(); session.release();
        send(session, Action.PASTE, "", "");
        assertEquals(RailRoute.side(0), route(session, 2, 4).connections(), "Joined only to the marker north of it");
        assertEquals(through, route(session, 2, 3).connections());
    }

    @Test void aBoxLeavesRouteMarkersOutAndAMovingSelectionLeavesThemInPlace() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
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
        assertEquals("Paint or erase hexes to change a route.", session.snapshot().message());
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

    /**
     * A stroke entering a hex by one side and leaving by another, for every pair and both kinds: a gentle pair (opposite
     * sides, or two apart) lays exactly that piece; a 120-degree step (adjacent sides) lays none, so both sides end in
     * stubs, closed by buffer stops on a rail. Each step joins the neighbours it passed.
     */
    @Test void aStrokeLaysTheGentlePieceItDrawsAndATightStepEndsInStubs() {
        var centre = new Coords(4, 4);
        for (var kind : RailRoute.Kind.values()) {
            var session = new BoardEditorSession();
            send(session, Action.ASSET, "", kind.asset);
            for (int entered = 0; entered < 6; entered++) {
                for (int left = 0; left < 6; left++) {
                    if (left == entered) { continue; }
                    String name = kind + " " + entered + " -> " + left;
                    stroke(session, centre.translated(entered), centre, centre.translated(left));
                    var marker = route(session, kind, centre);
                    int sides = RailRoute.side(entered) | RailRoute.side(left);
                    assertEquals(sides, marker.connections(), name);
                    assertEquals(0, marker.tracks(), name + ": a two-sided hex lays its default");
                    int piece = RailRoute.piece(entered, left);
                    if (piece >= 0) {
                        assertEquals(1 << piece, RailRoute.pieces(marker, sides), name);
                        assertEquals(0, RailRoute.stranded(sides), name);
                    } else {
                        assertEquals(0, RailRoute.pieces(marker, sides), name + ": never a tight turn");
                        assertEquals(sides, RailRoute.stranded(sides), name + ": both sides end in stubs");
                        assertEquals(2, RailRoute.stops(sides).size(), name + ": a buffer stop on each stub");
                    }
                    assertNotNull(RailRoute.joined(marker, entered, session.board().getHex(centre.translated(entered))), name);
                    assertNotNull(RailRoute.joined(marker, left, session.board().getHex(centre.translated(left))), name);
                    send(session, Action.UNDO, "", "");
                    assertNull(RailRoute.find(session.board().getHex(centre), kind), name + ": one undo step");
                }
            }
        }
    }

    /** A curve drawn across a line keeps both (mask 29 would otherwise build a three-way turnout), and stores it. */
    @Test void aCurveDrawnAcrossALineKeepsBothAndTheDrawnLayoutIsSaved() throws Exception {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        var centre = new Coords(4, 4);
        stroke(session, centre.translated(0), centre, centre.translated(3));
        stroke(session, centre.translated(2), centre, centre.translated(4));
        var marker = route(session, RailRoute.Kind.RAIL, centre);
        assertEquals(29, marker.connections());
        int drawn = 1 << RailRoute.piece(0, 3) | 1 << RailRoute.piece(2, 4);
        assertEquals(drawn, marker.tracks());
        assertNotEquals(RailRoute.pieces(29), drawn, "The default would be a three-way turnout");
        assertTrue(RailRoute.variants(29).contains(drawn), "The junction card offers it");
        Path file = directory.resolve("rails.board2"); session.save(file);
        assertEquals(drawn, RailRoute.find(BoardFile.read(file).getHex(centre), RailRoute.Kind.RAIL).tracks());
        // A third stroke turning sharply (S into SE) adds no piece: both sides already have gentle partners here.
        stroke(session, centre.translated(3), centre, centre.translated(2));
        assertEquals(drawn, route(session, RailRoute.Kind.RAIL, centre).tracks());
    }

    /**
     * The junction card: each layout of a Y (stems N, SE, SW and the wye triangle) is one pick and one undo step; the
     * default is stored as none; tracks that are no layout of the sides are refused; a side edit that breaks the
     * layout drops it.
     */
    @Test void theJunctionCardCyclesTheLayoutsOneUndoStepEach() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        var centre = new Coords(4, 4);
        stroke(session, centre.translated(0), centre, centre.translated(2));
        stroke(session, centre, centre.translated(4));
        var marker = route(session, RailRoute.Kind.RAIL, centre);
        assertEquals(21, marker.connections());
        assertEquals(0, marker.tracks(), "The drawn Y is the default, stemmed at N");
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(centre, 0, 0, false, marker.id()); session.finishStroke(); session.release();
        var layouts = RailRoute.variants(21);
        assertEquals(4, layouts.size());
        for (int layout : layouts.subList(1, layouts.size())) {
            send(session, Action.OBJECT_VALUE, "tracks", Integer.toString(layout));
            assertEquals(layout, route(session, RailRoute.Kind.RAIL, centre).tracks());
        }
        send(session, Action.OBJECT_VALUE, "tracks", Integer.toString(layouts.getFirst()));
        assertEquals(0, route(session, RailRoute.Kind.RAIL, centre).tracks(), "The default stores nothing");
        for (int index = layouts.size() - 1; index >= 1; index--) {
            send(session, Action.UNDO, "", "");
            assertEquals(layouts.get(index), RailRoute.pieces(route(session, RailRoute.Kind.RAIL, centre), 21), "undo " + index);
        }
        send(session, Action.UNDO, "", "");
        assertEquals(0, route(session, RailRoute.Kind.RAIL, centre).tracks());
        send(session, Action.REDO, "", "");
        assertEquals(layouts.get(1), route(session, RailRoute.Kind.RAIL, centre).tracks());
        send(session, Action.OBJECT_VALUE, "tracks", Integer.toString(1 << RailRoute.piece(1, 4)));
        assertEquals(layouts.get(1), route(session, RailRoute.Kind.RAIL, centre).tracks(), "NE-SW is no piece of this Y");
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.junctionInvalid"), session.snapshot().message());
        // Toggling the NW side on leaves the stored stem without a piece for NW: the default is laid again.
        send(session, Action.OBJECT_VALUE, "side", "5");
        var widened = route(session, RailRoute.Kind.RAIL, centre);
        assertEquals(21 | RailRoute.side(5), widened.connections());
        assertTrue(RailRoute.valid(widened.connections(), widened.tracks()));
    }

    /**
     * Two maglevs cross over one hex at levels 2 and 4: separate markers with disjoint sides, each joined to its own
     * route. Each is selected (a 3D pick or a Layers row) and has its own side-view level, and edits, side toggles and
     * the brush's erase band touch only it; a stroke may not take a side the other owns.
     */
    @Test void twoMaglevsAtDifferentLevelsInOneHexAreSelectedAndEditedSeparately() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        var centre = new Coords(4, 4);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, centre.translated(0), centre, centre.translated(3));
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        stroke(session, centre.translated(1), centre, centre.translated(4));
        var markers = RailRoute.markers(session.board().getHex(centre), RailRoute.Kind.MAGLEV);
        assertEquals(2, markers.size());
        var low = markers.getFirst();
        var high = markers.getLast();
        assertEquals(2, low.placement().level()); assertEquals(4, high.placement().level());
        assertEquals(RailRoute.side(0) | RailRoute.side(3), low.connections());
        assertEquals(RailRoute.side(1) | RailRoute.side(4), high.connections());
        assertEquals(low, RailRoute.joined(route(session, RailRoute.Kind.MAGLEV, centre.translated(0)), 3,
              session.board().getHex(centre)));
        assertEquals(high, RailRoute.joined(route(session, RailRoute.Kind.MAGLEV, centre.translated(1)), 4,
              session.board().getHex(centre)));

        // A stroke at level 4 from the south may not take the N side the lower route owns there: it ends open. (It starts
        // beyond the lower route's end: a stroke that starts on a route's end extends that route.)
        stroke(session, centre.translated(3, 2), centre.translated(3), centre);
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.routeSideUsed", "N", 2.0), session.snapshot().message());
        assertEquals(high.connections(), marker(session, centre, high.id()).connections());
        send(session, Action.UNDO, "", "");

        // Picked in 3D (or a Layers row), each is the selected object with its own side-view level.
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(centre, 0, 0, false, low.id()); session.finishStroke(); session.release();
        assertEquals(low.id(), session.snapshot().object());
        var levels = session.snapshot().levels();
        assertEquals(2, levels.stream().filter(l -> l.key().equals(low.id())).findFirst().orElseThrow().level());
        assertEquals(4, levels.stream().filter(l -> l.key().equals(high.id())).findFirst().orElseThrow().level());
        send(session, Action.OBJECT_VALUE, "level", "2.5");
        assertEquals(2.5, marker(session, centre, low.id()).placement().level());
        assertEquals(4, marker(session, centre, high.id()).placement().level());
        send(session, Action.SELECT_OBJECT, "", high.id());
        assertEquals(high.id(), session.snapshot().object());
        send(session, Action.HEIGHT, high.id(), "5");
        assertEquals(5, marker(session, centre, high.id()).placement().level());
        assertEquals(2.5, marker(session, centre, low.id()).placement().level());
        // Its side toggle may not take the lower route's N side, and parts only its own route.
        send(session, Action.OBJECT_VALUE, "side", "0");
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.routeSideUsed", "N", 2.5), session.snapshot().message());
        send(session, Action.OBJECT_VALUE, "side", "1");
        assertEquals(RailRoute.side(4), marker(session, centre, high.id()).connections());
        assertEquals(RailRoute.side(0) | RailRoute.side(3), marker(session, centre, low.id()).connections());
        assertEquals(0, route(session, RailRoute.Kind.MAGLEV, centre.translated(1)).connections() & RailRoute.side(4));
        send(session, Action.UNDO, "", "");

        // The maglev brush erases within a level of its height: the upper route goes, the lower stays joined.
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        send(session, Action.BRUSH_VALUE, "object:level", "5");
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertEquals(java.util.List.of(low.id()), RailRoute.markers(session.board().getHex(centre), RailRoute.Kind.MAGLEV).stream()
              .map(megamek.common.board.BoardDecoration::id).toList());
        assertNotNull(RailRoute.joined(route(session, RailRoute.Kind.MAGLEV, centre.translated(0)), 3, session.board().getHex(centre)));
        assertEquals(0, route(session, RailRoute.Kind.MAGLEV, centre.translated(1)).connections() & RailRoute.side(4),
              "The upper route's neighbour no longer points at the hex");
    }

    /**
     * A maglev's Pillars, as a bridge's: the Edit panel's toggle sets the whole connected route (through its junction) in
     * one undo step, never the maglev that crosses it at another level; a stroke that extends a pillared route carries
     * them on; they are saved only where on; a rail has none.
     */
    @Test void pillarsApplyToTheWholeConnectedMaglevRouteAsOneUndoStep() throws Exception {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        var centre = new Coords(4, 4);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, centre.translated(0, 2), centre.translated(0), centre, centre.translated(3), centre.translated(3, 2));
        stroke(session, centre, centre.translated(2), centre.translated(2, 2));
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        stroke(session, centre.translated(1), centre, centre.translated(4));
        var route = java.util.List.of(centre.translated(0, 2), centre.translated(0), centre, centre.translated(3),
              centre.translated(3, 2), centre.translated(2), centre.translated(2, 2));
        var high = RailRoute.markers(session.board().getHex(centre), RailRoute.Kind.MAGLEV).getLast();
        assertEquals(4, high.placement().level());

        send(session, Action.TOOL, "", "SELECT");
        var end = route(session, RailRoute.Kind.MAGLEV, centre.translated(3, 2));
        session.pointer(centre.translated(3, 2), 0, 0, false, end.id()); session.finishStroke(); session.release();
        send(session, Action.PILLARS, "", "true");
        for (var at : route) { assertTrue(level2(session, at).pillars(), at + " is on the route"); }
        assertFalse(marker(session, centre, high.id()).pillars(), "the crossing route at level 4 is another route");
        assertFalse(route(session, RailRoute.Kind.MAGLEV, centre.translated(1)).pillars());
        send(session, Action.UNDO, "", "");
        for (var at : route) { assertFalse(level2(session, at).pillars(), "one undo step"); }
        send(session, Action.REDO, "", "");
        for (var at : route) { assertTrue(level2(session, at).pillars()); }

        // A stroke that extends the route carries its pillars on, as a bridge card's stroke keeps its span's piers.
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, centre.translated(0, 2), centre.translated(0, 3));
        assertTrue(route(session, RailRoute.Kind.MAGLEV, centre.translated(0, 3)).pillars());
        Path file = directory.resolve("pillars.board2"); session.save(file);
        String saved = java.nio.file.Files.readString(file);
        assertEquals(route.size() + 1, saved.split("\"pillars\"", -1).length - 1, "saved only where on");
        assertEquals(session.board().getHex(centre).getDecorations(), BoardFile.read(file).getHex(centre).getDecorations());

        send(session, Action.TOOL, "", "SELECT");
        session.pointer(centre, 0, 0, false, level2(session, centre).id()); session.finishStroke(); session.release();
        send(session, Action.PILLARS, "", "false");
        for (var at : route) { assertFalse(level2(session, at).pillars()); }
        assertFalse(route(session, RailRoute.Kind.MAGLEV, centre.translated(0, 3)).pillars());

        // A rail lies on its support: no pillars.
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, new Coords(1, 1), new Coords(1, 2));
        var rail = route(session, RailRoute.Kind.RAIL, new Coords(1, 1));
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(1, 1), 0, 0, false, rail.id()); session.finishStroke(); session.release();
        send(session, Action.PILLARS, "", "true");
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.railNoPillars"), session.snapshot().message());
        assertFalse(route(session, RailRoute.Kind.RAIL, new Coords(1, 1)).pillars());
    }

    /** The hex's maglev at level 2. */
    private static megamek.common.board.BoardDecoration level2(BoardEditorSession session, Coords at) {
        return RailRoute.markers(session.board().getHex(at), RailRoute.Kind.MAGLEV).stream()
              .filter(marker -> marker.placement().level() == 2).findFirst().orElseThrow();
    }

    /** A rail drawn over a bridge hex rides its deck; the Edit panel's Beneath puts it on the ground, one undo step. */
    @Test void aRailDrawnOverABridgeRidesTheDeckOrRunsBeneath() {
        var session = new BoardEditorSession();
        var centre = new Coords(4, 4);
        session.board().setHex(centre, new megamek.common.Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:2", ""));
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, centre.translated(0), centre, centre.translated(3));
        var rail = route(session, RailRoute.Kind.RAIL, centre);
        assertTrue(RailRoute.onDeck(rail));
        assertFalse(RailRoute.onDeck(route(session, RailRoute.Kind.RAIL, centre.translated(0))));
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(centre, 0, 0, false, rail.id()); session.finishStroke(); session.release();
        send(session, Action.OBJECT_VALUE, "receiver", "ground/top");
        assertFalse(RailRoute.onDeck(route(session, RailRoute.Kind.RAIL, centre)));
        assertEquals(rail.connections(), route(session, RailRoute.Kind.RAIL, centre).connections());
        send(session, Action.OBJECT_VALUE, "level", "3");
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.routeCentred"), session.snapshot().message(), "A rail has no level");
        send(session, Action.UNDO, "", "");
        assertTrue(RailRoute.onDeck(route(session, RailRoute.Kind.RAIL, centre)));

        // Removing the bridge puts its deck rail on the ground with all its sides, in the same undo step.
        send(session, Action.REMOVE_TERRAIN, "", "bridge");
        var bare = route(session, RailRoute.Kind.RAIL, centre);
        assertFalse(RailRoute.onDeck(bare));
        assertEquals(rail.connections(), RailRoute.drawnSides(bare, session.board().getHex(centre)));
        send(session, Action.UNDO, "", "");
        assertTrue(RailRoute.onDeck(route(session, RailRoute.Kind.RAIL, centre)));

        // A stroke across a bridge (SW to NE over its N-S deck) runs beneath it, all its sides drawn, without stubs.
        var across = new Coords(8, 4);
        session.board().setHex(across, new megamek.common.Hex(0, "bridge:1:09;bridge_cf:40;bridge_elev:2", ""));
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, across.translated(4), across, across.translated(1));
        var crossing = route(session, RailRoute.Kind.RAIL, across);
        assertFalse(RailRoute.onDeck(crossing));
        assertEquals(RailRoute.side(1) | RailRoute.side(4), RailRoute.drawnSides(crossing, session.board().getHex(across)));
        assertTrue(RailRoute.stops(crossing.connections()).isEmpty());
    }

    /**
     * Rolling stock placed on a route rides it: a wagon onto the track's centre line, turned along it (the way nearer the
     * brush's turn) at the rail top; a freight train stamp round a curve with each wagon on the track and the stamp's
     * spacing; a maglev cab onto the nearer of two crossing beams, at its level. Off a route, or a platform, as placed.
     */
    @Test void rollingStockPlacedOnARouteRidesItsTrack() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        // South from (4, 1), then a 60-degree curve at (4, 4) onto the south-east.
        var cells = new java.util.ArrayList<>(java.util.List.of(new Coords(4, 1)));
        for (int direction : new int[] { 3, 3, 3, 2, 2, 2 }) { cells.add(cells.getLast().translated(direction)); }
        stroke(session, cells.toArray(Coords[]::new));
        var straight = new Coords(4, 2);
        var curve = new Coords(4, 4);
        send(session, Action.ASSET, "", "scenery/rail/box-car");
        send(session, Action.BRUSH_VALUE, "object:rotation", "170");
        session.pointer(straight, 0, 0, false); session.finishStroke();
        var wagon = last(session, straight);
        var rail = route(session, RailRoute.Kind.RAIL, straight);
        assertEquals(0, wagon.x(), 1e-6); assertEquals(0, wagon.y(), 1e-6);
        assertEquals(180, wagon.rotation(), 1e-4, "Along the track, the way nearer the brush's 170 degrees");
        assertEquals(RailRoute.ride(rail), wagon.placement(), "On the rail top");
        send(session, Action.BRUSH_VALUE, "precision", "true");
        send(session, Action.BRUSH_VALUE, "magnetic", "false");
        session.pointer(straight, .3, .2, false); session.finishStroke();
        assertEquals(0, last(session, straight).x(), 1e-6, "Onto the centre line");
        assertEquals(.2, last(session, straight).y(), 1e-6, "Where it was put along the track");
        send(session, Action.BRUSH_VALUE, "precision", "false");
        session.pointer(new Coords(8, 1), 0, 0, false); session.finishStroke();
        assertEquals(170, last(session, new Coords(8, 1)).rotation(), 1e-6, "Without a route, as any object");

        send(session, Action.ASSET, "", "stamp/rail-train");
        session.pointer(curve, 0, 0, false); session.finishStroke();
        // The stamp selects its whole train; each vehicle belongs to the hex of the track it stands on.
        var train = session.snapshot().selection();
        assertEquals(4, train.size());
        assertTrue(train.stream().map(BoardEditorSession.Selection::coords).distinct().count() > 1, "A train spans hexes");
        var rows = megamek.common.board.BoardSceneryLayouts.layout("scenery/rail/train").components();
        float[] previous = null;
        for (int i = 0; i < train.size(); i++) {
            Coords home = train.get(i).coords();
            var vehicle = marker(session, home, train.get(i).object());
            float[] at = inFrame(home, curve, vehicle);
            float x = at[0], y = at[1];
            assertTrue(onTrack(session, cells, curve, x, y, vehicle.rotation()) < .5, vehicle.asset() + " stands on the track");
            assertEquals(RailRoute.ride(route(session, RailRoute.Kind.RAIL, home)), vehicle.placement());
            if (previous != null) {
                float spacing = rows.get(i - 1).y() - rows.get(i).y();
                assertEquals(spacing, Math.hypot(x - previous[0], y - previous[1]), 2.5, "The stamp's spacing, round the curve");
            }
            previous = new float[] { x, y };
        }

        // Two maglevs cross over (8, 5): a cab put on the north-east half rides the NE-SW beam, at its level.
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        var crossing = new Coords(8, 5);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, crossing.translated(0), crossing, crossing.translated(3));
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        stroke(session, crossing.translated(1), crossing, crossing.translated(4));
        var high = RailRoute.markers(session.board().getHex(crossing), RailRoute.Kind.MAGLEV).getLast();
        send(session, Action.ASSET, "", "scenery/maglev/cab");
        send(session, Action.BRUSH_VALUE, "precision", "true");
        float[] northEast = RailRoute.border(1);
        session.pointer(crossing, .4 * northEast[0] / megamek.common.board.HexTile.WIDTH,
              .4 * northEast[1] / megamek.common.board.HexTile.HEIGHT, false);
        session.finishStroke();
        var cab = last(session, crossing);
        assertEquals(high.placement(), cab.placement(), "The beam nearer the cab");
        double cabX = cab.x() * megamek.common.board.HexTile.WIDTH, cabY = cab.y() * megamek.common.board.HexTile.HEIGHT;
        assertEquals(0, cabX * northEast[1] - cabY * northEast[0], 1e-2, "On the NE-SW line");
        // At the crossing point both beams are as near: the cab rides the one nearer its own level.
        send(session, Action.BRUSH_VALUE, "precision", "false");
        session.pointer(crossing, 0, 0, false); session.finishStroke();
        assertEquals(RailRoute.markers(session.board().getHex(crossing), RailRoute.Kind.MAGLEV).getFirst().placement(),
              last(session, crossing).placement(), "A cab at the ground's level rides the lower beam");
        send(session, Action.BRUSH_VALUE, "object:offset", "4");
        session.pointer(crossing, 0, 0, false); session.finishStroke();
        assertEquals(high.placement(), last(session, crossing).placement(), "A cab four levels up rides the upper beam");
        send(session, Action.BRUSH_VALUE, "precision", "true");
        send(session, Action.ASSET, "", RailRoute.PLATFORM);
        session.pointer(crossing, .3, 0, false); session.finishStroke();
        assertEquals(.3, last(session, crossing).x(), 1e-6, "A platform stands beside the track");
    }

    /**
     * A freight train stamped on a ground rail beside a bridge: the wagons that run onto the deck ride the deck's rail,
     * belong to the deck hex and stand on its deck; the others on the ground rail.
     */
    @Test void aTrainStampedBesideADeckRidesTheDeckWhereItRunsOntoIt() {
        var session = new BoardEditorSession();
        var deck = new Coords(4, 4);
        session.board().setHex(deck, new megamek.common.Hex(0, "water:2;bridge:1:09;bridge_cf:40;bridge_elev:3", ""));
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, deck.translated(0, 3), deck.translated(0, 2), deck.translated(0), deck, deck.translated(3),
              deck.translated(3, 2), deck.translated(3, 3));
        assertTrue(RailRoute.onDeck(route(session, RailRoute.Kind.RAIL, deck)));
        send(session, Action.ASSET, "", "stamp/rail-train");
        var at = deck.translated(3);
        session.pointer(at, 0, 0, false); session.finishStroke();
        var homes = session.snapshot().selection().stream().map(BoardEditorSession.Selection::coords).toList();
        assertTrue(homes.contains(deck), "The locomotive, 68 px north of the stamp, stands over the bridge hex");
        for (var member : session.snapshot().selection()) {
            var vehicle = marker(session, member.coords(), member.object());
            boolean onDeck = member.coords().equals(deck);
            assertEquals(onDeck ? BoardDecoration.Placement.on("bridge", 0) : BoardDecoration.Placement.ground(),
                  vehicle.placement(), vehicle.asset());
        }
        var train = session.snapshot().selection().stream().map(BoardEditorSession.Selection::object).toList();
        assertTrue(session.snapshot().issues().stream().noneMatch(issue -> train.contains(issue.object())),
              "Every vehicle has its support in its own hex");
    }

    /**
     * The default Maglev brush (a level above the ground) continues a converted maglev that lies on the ground from its
     * end, at its placement; a single click there, or a stroke that starts elsewhere, is another route.
     */
    @Test void aStrokeFromAConvertedMaglevsEndExtendsIt() {
        var session = new BoardEditorSession();
        Coords[] line = { new Coords(5, 3), new Coords(5, 4), new Coords(5, 5) };
        int[] sides = { 8, 9, 1 };
        for (int i = 0; i < line.length; i++) {
            var hex = session.board().getHex(line[i]);
            hex.setDecorations(java.util.List.of(new BoardDecoration("legacy" + i, "prop", RailRoute.Kind.MAGLEV.asset, null,
                  0, 0, 0, false, 1, megamek.common.board.BoardSceneryLayouts.routePlacement(hex), 0, false).withConnections(sides[i])));
        }
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        Coords next = new Coords(5, 6);
        stroke(session, line[2], next);
        var end = RailRoute.markers(session.board().getHex(line[2]), RailRoute.Kind.MAGLEV);
        assertEquals(1, end.size());
        assertEquals("legacy2", end.getFirst().id());
        assertEquals(RailRoute.side(0) | RailRoute.side(3), end.getFirst().connections());
        var added = RailRoute.find(session.board().getHex(next), RailRoute.Kind.MAGLEV);
        assertEquals(BoardDecoration.Placement.ground(), added.placement(), "At the route's placement, on the ground");
        assertEquals(end.getFirst(), RailRoute.joined(added, 0, session.board().getHex(line[2])));
    }

    /**
     * A refused step's notice shows in the preview of the route being drawn, for the rest of its drag, and stays after
     * its release; the next stroke clears it. Editing a maglev's name or a pillared brush's level keeps its Pillars.
     */
    @Test void aRefusalStaysShownForItsStrokeAndEditsKeepPillars() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        var centre = new Coords(4, 4);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, centre.translated(0), centre, centre.translated(3));
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        var south = centre.translated(3);
        session.pointer(south.translated(3), 0, 0, false);
        session.pointer(south, 0, 0, true);
        session.pointer(centre, 0, 0, true);
        String refused = megamek.client.ui.Messages.getString("BoardEditor.routeSideUsed", "N", 2.0);
        assertEquals(refused, session.preview(centre, 0, 0, "ground").notice());
        session.pointer(centre, .1, .1, true);
        assertEquals(refused, session.preview(centre, .1, .1, "ground").notice(), "a later move of the same drag");
        session.finishStroke();
        assertEquals(refused, session.snapshot().message(), "after the stroke");
        stroke(session, new Coords(1, 1), new Coords(1, 2));
        assertEquals("", session.snapshot().message(), "the next stroke");

        var lone = new Coords(8, 8);
        stroke(session, lone);
        var marker = RailRoute.find(session.board().getHex(lone), RailRoute.Kind.MAGLEV);
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(lone, 0, 0, false, marker.id()); session.finishStroke(); session.release();
        send(session, Action.PILLARS, "", "true");
        send(session, Action.OBJECT_VALUE, "name", "Line 4");
        assertTrue(RailRoute.find(session.board().getHex(lone), RailRoute.Kind.MAGLEV).pillars());
        send(session, Action.SAMPLE, "", "");
        send(session, Action.BRUSH_VALUE, "object:level", "3");
        assertTrue(session.snapshot().activeBrush().object().pillars());
    }

    /**
     * Batch-10 answer 6: a train stamped on a track shorter than it places only the vehicles whose length fits on the
     * track (here the two wagons between the three hexes' dead ends; the locomotive and the flatbed would overhang), and
     * says how many.
     */
    @Test void aStampedTrainPlacesOnlyTheVehiclesThatFitOnItsTrack() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        stroke(session, new Coords(4, 3), new Coords(4, 4), new Coords(4, 5));
        send(session, Action.ASSET, "", "stamp/rail-train");
        session.pointer(new Coords(4, 4), 0, 0, false); session.finishStroke();
        var train = session.snapshot().selection();
        assertEquals(java.util.List.of("scenery/rail/box-car", "scenery/rail/tank-car"),
              train.stream().map(member -> marker(session, member.coords(), member.object()).asset()).toList());
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.vehiclesPlaced", 2, 4), session.snapshot().message());
        for (var member : train) {
            var vehicle = marker(session, member.coords(), member.object());
            assertEquals(RailRoute.ride(route(session, RailRoute.Kind.RAIL, member.coords())), vehicle.placement());
        }
    }

    /**
     * The fit rule measures a car by the other cars, not by the coupler between them. On a lone curved maglev piece
     * (an arc of about 65 px, open at both ends) the City train's 30 px wagon and 31 px cab, centred 17 px either side,
     * would reach past the beam's ends: only the coupler is placed. Measured by the coupler, each car's reach was 8.5 px
     * and both stood up to 7 px past the beam's end.
     */
    @Test void aCouplerIsNoMeasureOfTheCarsItJoins() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        Coords curve = new Coords(4, 4);
        stroke(session, curve.translated(0), curve, curve.translated(2));
        for (Coords end : new Coords[] { curve.translated(0), curve.translated(2) }) {
            var hex = session.board().getHex(end).duplicate();
            hex.setDecorations(java.util.List.of());
            session.board().setHex(end, hex);
        }
        assertEquals(1 | 4, route(session, RailRoute.Kind.MAGLEV, curve).connections(), "The curve from N to SE");
        send(session, Action.ASSET, "", "stamp/maglev-train");
        session.pointer(curve, 0, 0, false); session.finishStroke();
        assertEquals(java.util.List.of(RailRoute.COUPLER), session.snapshot().selection().stream()
              .map(member -> marker(session, member.coords(), member.object()).asset()).toList());
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.vehiclesPlaced", 1, 3), session.snapshot().message());
    }

    /**
     * Batch-10 answer 11: a stroke whose level is refused where it starts (the side belongs to another maglev there)
     * leaves no stub behind, neither where it started nor where it was refused; a single click still starts a route.
     */
    @Test void aStrokeThatCouldNotJoinLeavesNoMarker() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        var centre = new Coords(4, 4);
        send(session, Action.BRUSH_VALUE, "object:level", "2");
        stroke(session, centre.translated(0), centre, centre.translated(3));
        send(session, Action.BRUSH_VALUE, "object:level", "4");
        stroke(session, centre, centre.translated(0));
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.routeSideUsed", "N", 2.0), session.snapshot().message());
        assertEquals(1, RailRoute.markers(session.board().getHex(centre), RailRoute.Kind.MAGLEV).size(), "no stub at the start");
        assertEquals(1, RailRoute.markers(session.board().getHex(centre.translated(0)), RailRoute.Kind.MAGLEV).size());
        stroke(session, centre);
        assertEquals(2, RailRoute.markers(session.board().getHex(centre), RailRoute.Kind.MAGLEV).size(),
              "a click places its marker");
    }

    /** {@code object}'s anchor, owned by {@code home}, in {@code frame}'s model px. */
    private static float[] inFrame(Coords home, Coords frame, BoardDecoration object) {
        return new float[] { (float) object.x() * megamek.common.board.HexTile.WIDTH
              + megamek.common.board.HexTile.centerX(home, megamek.common.board.HexTile.WIDTH)
              - megamek.common.board.HexTile.centerX(frame, megamek.common.board.HexTile.WIDTH),
              (float) object.y() * megamek.common.board.HexTile.HEIGHT
                    + megamek.common.board.HexTile.centerY(home, megamek.common.board.HexTile.HEIGHT)
                    - megamek.common.board.HexTile.centerY(frame, megamek.common.board.HexTile.HEIGHT) };
    }

    /** The gap from a point of {@code at}'s frame (model px) to the nearest track of {@code cells}' rails. */
    private static double onTrack(BoardEditorSession session, java.util.List<Coords> cells, Coords at, float x, float y, double rotation) {
        double best = Double.POSITIVE_INFINITY;
        for (Coords cell : cells) {
            float lx = x - (megamek.common.board.HexTile.centerX(cell, megamek.common.board.HexTile.WIDTH)
                  - megamek.common.board.HexTile.centerX(at, megamek.common.board.HexTile.WIDTH));
            float ly = y - (megamek.common.board.HexTile.centerY(cell, megamek.common.board.HexTile.HEIGHT)
                  - megamek.common.board.HexTile.centerY(at, megamek.common.board.HexTile.HEIGHT));
            var hex = session.board().getHex(cell);
            var spot = RailRoute.snap(hex, RailRoute.find(hex, RailRoute.Kind.RAIL), lx, ly, (float) rotation);
            best = Math.min(best, Math.hypot(spot.x() - lx, spot.y() - ly));
        }
        return best;
    }

    private static megamek.common.board.BoardDecoration last(BoardEditorSession session, Coords at) {
        return session.board().getHex(at).getDecorations().getLast();
    }

    /** One stroke through {@code cells}, each pointer event a drag after the first. */
    private static void stroke(BoardEditorSession session, Coords... cells) {
        for (int i = 0; i < cells.length; i++) { session.pointer(cells[i], 0, 0, i > 0); }
        session.finishStroke();
    }

    private static megamek.common.board.BoardDecoration route(BoardEditorSession session, RailRoute.Kind kind, Coords at) {
        return RailRoute.find(session.board().getHex(at), kind);
    }

    private static megamek.common.board.BoardDecoration marker(BoardEditorSession session, Coords at, String id) {
        return session.board().getHex(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }

    private static double x(BoardEditorSession session, Coords at, String id) {
        return session.board().getHex(at).getDecorations().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow().x();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
