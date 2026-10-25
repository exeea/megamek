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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drawing, editing and erasing rail and maglev routes in the editor (one route model, {@link RailRoute}). */
class RailRouteEditorTest {
    @TempDir Path directory;

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
            send(session, Action.WAY_TRACKS, "rail", Integer.toString(layout));
            assertEquals(layout, route(session, RailRoute.Kind.RAIL, centre).tracks());
        }
        send(session, Action.WAY_TRACKS, "rail", Integer.toString(layouts.getFirst()));
        assertEquals(0, route(session, RailRoute.Kind.RAIL, centre).tracks(), "The default stores nothing");
        for (int index = layouts.size() - 1; index >= 1; index--) {
            send(session, Action.UNDO, "", "");
            assertEquals(layouts.get(index), RailRoute.pieces(route(session, RailRoute.Kind.RAIL, centre), 21), "undo " + index);
        }
        send(session, Action.UNDO, "", "");
        assertEquals(0, route(session, RailRoute.Kind.RAIL, centre).tracks());
        send(session, Action.REDO, "", "");
        assertEquals(layouts.get(1), route(session, RailRoute.Kind.RAIL, centre).tracks());
        send(session, Action.WAY_TRACKS, "rail", Integer.toString(1 << RailRoute.piece(1, 4)));
        assertEquals(layouts.get(1), route(session, RailRoute.Kind.RAIL, centre).tracks(), "NE-SW is no piece of this Y");
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.junctionInvalid"), session.snapshot().message());
        // Toggling the NW side on leaves the stored stem without a piece for NW: the default is laid again.
        send(session, Action.EDGE, "rail", "5");
        var widened = route(session, RailRoute.Kind.RAIL, centre);
        assertEquals(21 | RailRoute.side(5), widened.connections());
        assertTrue(RailRoute.valid(widened.connections(), widened.tracks()));
    }

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

    }

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

    @Test void aCouplerIsNoMeasureOfTheCarsItJoins() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", RailRoute.Kind.MAGLEV.asset);
        Coords curve = new Coords(4, 4);
        stroke(session, curve.translated(0), curve, curve.translated(2));
        for (Coords end : new Coords[] { curve.translated(0), curve.translated(2) }) {
            var hex = session.board().getHex(end).duplicate();
            hex.removeBridge();
            session.board().setHex(end, hex);
        }
        assertEquals(1 | 4, route(session, RailRoute.Kind.MAGLEV, curve).connections(), "The curve from N to SE");
        send(session, Action.ASSET, "", "stamp/maglev-train");
        session.pointer(curve, 0, 0, false); session.finishStroke();
        assertEquals(java.util.List.of(RailRoute.COUPLER), session.snapshot().selection().stream()
              .map(member -> marker(session, member.coords(), member.object()).asset()).toList());
        assertEquals(megamek.client.ui.Messages.getString("BoardEditor.vehiclesPlaced", 1, 3), session.snapshot().message());
    }

    /** {@code object}'s anchor, owned by {@code home}, in {@code frame}'s model px. */
    private static float[] inFrame(Coords home, Coords frame, BoardDecoration object) {
        return new float[] { (float) object.x() * megamek.common.board.HexTile.MODEL_WIDTH
              + megamek.common.board.HexTile.centerX(home, megamek.common.board.HexTile.MODEL_WIDTH)
              - megamek.common.board.HexTile.centerX(frame, megamek.common.board.HexTile.MODEL_WIDTH),
              (float) object.y() * megamek.common.board.HexTile.HEIGHT
                    + megamek.common.board.HexTile.centerY(home, megamek.common.board.HexTile.HEIGHT)
                    - megamek.common.board.HexTile.centerY(frame, megamek.common.board.HexTile.HEIGHT) };
    }

    /** The gap from a point of {@code at}'s frame (model px) to the nearest track of {@code cells}' rails. */
    private static double onTrack(BoardEditorSession session, java.util.List<Coords> cells, Coords at, float x, float y, double rotation) {
        double best = Double.POSITIVE_INFINITY;
        for (Coords cell : cells) {
            float lx = x - (megamek.common.board.HexTile.centerX(cell, megamek.common.board.HexTile.MODEL_WIDTH)
                  - megamek.common.board.HexTile.centerX(at, megamek.common.board.HexTile.MODEL_WIDTH));
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
