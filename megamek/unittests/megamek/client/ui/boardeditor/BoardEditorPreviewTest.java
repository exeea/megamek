/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Preview;
import megamek.client.ui.boardeditor.BoardEditorSession.Selection;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.event.board.BoardEvent;
import megamek.common.event.board.BoardListenerAdapter;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The Paint and Erase preview ({@link BoardEditorSession#preview}) is the click itself, run and undone unseen: for every
 * kind of brush its hexes are exactly those the click then changes, as the click leaves them (new objects' ids aside),
 * and the document, the session's state and the board's listeners never notice it.
 */
class BoardEditorPreviewTest {
    private final Coords centre = new Coords(6, 6);

    @Test void everyPaintPreviewIsExactlyWhatItsClickPaints() {
        var session = board();
        // Objects: at the hex centre, placed precisely (snapped by the magnets), on a roof, a decal.
        brush(session, Action.ASSET, "", "scenery/vehicles/car");
        same(session, centre, .3, -.2, "ground");
        brush(session, Action.BRUSH_VALUE, "precision", "true");
        same(session, centre, .3, -.2, "ground");
        same(session, centre.translated(0), .1, .1, "building");
        brush(session, Action.BRUSH_VALUE, "precision", "false");
        brush(session, Action.ASSET, "", "decal/emblems/red-cross");
        same(session, centre.translated(2), 0, 0, "ground");
        // Stamps: a tree cluster, and a train that rides the rail route with every wagon on its track.
        brush(session, Action.ASSET, "", "stamp/tree-cluster-2");
        same(session, new Coords(2, 2), 0, 0, "ground");
        brush(session, Action.ASSET, "", "scenery/rail/box-car");
        Preview wagon = same(session, new Coords(10, 4), .2, .1, "ground");
        assertEquals(RailRoute.Kind.RAIL, RailRoute.rides(wagon.placed().getFirst().getValue().asset()));
        assertEquals(0, wagon.placed().getFirst().getValue().x(), 1e-6, "Snapped onto the track's centre line");
        brush(session, Action.ASSET, "", "stamp/rail-train");
        Preview train = same(session, new Coords(10, 5), 0, 0, "ground");
        assertTrue(train.placed().stream().map(Map.Entry::getKey).distinct().count() > 1, "The train spans the hexes of its track");
        // A route's press: a marker without sides.
        brush(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        same(session, new Coords(3, 9), 0, 0, "ground");

        // Terrain cards at radius 3: Only overwrite clear hexes leaves the building and the water out.
        brush(session, Action.BRUSH, "", "3");
        woods(session, 2);
        brush(session, Action.BRUSH_VALUE, "clearOnly", "true");
        Preview woods = same(session, centre, 0, 0, "ground");
        assertFalse(woods.after().containsKey(centre.translated(0)), "The building is not clear");
        assertFalse(woods.after().containsKey(centre.translated(3)), "nor is the water");
        brush(session, Action.BRUSH_VALUE, "clearOnly", "false");
        // A card, then optionally a level of one of its fields (the Brush rows).
        for (String[] card : new String[][] { { "water", "shallow", "water", "2" }, { "water", "magma", "water", "2" },
              { "theme", "mars" }, { "building", "default" },
              { "road", "1", "road", "2" }, { "hazards", "fire" }, { "hazards", "hazard-liquid" }, { "weather", "ice" },
              { "ground", "swamp" }, { "scenario", "deployment" }, { "bridge", "bridge/truss" } }) {
            brush(session, Action.CHOOSE_BRUSH, card[0], card[1]);
            if (card.length > 2) { brush(session, Action.BRUSH_VALUE, "terrain:" + card[2], card[3]); }
            same(session, centre, 0, 0, "ground");
        }
        brush(session, Action.CHOOSE_BRUSH, "ground", "clear");
        brush(session, Action.BRUSH_VALUE, "theme", "snow");
        brush(session, Action.BRUSH_VALUE, "elevation", "2");
        same(session, centre, 0, 0, "ground");
        // A hex stamp: the whole captured hex, including its rail terrain.
        session.command(new Command(Action.TOOL, "", "SELECT"), null);
        session.pointer(new Coords(10, 4), 0, 0, false); session.finishStroke();
        brush(session, Action.SAMPLE, "", "");
        brush(session, Action.BRUSH, "", "2");
        same(session, new Coords(2, 9), 0, 0, "ground");
    }

    @Test void everyErasePreviewHighlightsExactlyWhatItsClickRemoves() {
        var session = board();
        woods(session, 2);
        brush(session, Action.BRUSH, "", "2");
        session.pointer(centre, 0, 0, false); session.finishStroke();
        brush(session, Action.ASSET, "", "scenery/vehicles/car");
        session.pointer(centre.translated(4), 0, 0, false); session.finishStroke();
        session.pointer(centre.translated(5), 0, 0, false); session.finishStroke();
        brush(session, Action.TOOL, "", "ERASE");
        // The car brush erases the cars, and only those, in its radius.
        assertEquals(2, same(session, centre, 0, 0, "ground").removed().size());
        // Erase never depends on the pointer's place in its hex: precise or not, a move within the hex reuses the preview.
        brush(session, Action.BRUSH_VALUE, "precision", "true");
        assertSame(session.preview(centre, .2, -.1, "ground"), session.preview(centre, -.3, .25, "ground"));
        brush(session, Action.BRUSH_VALUE, "precision", "false");
        woods(session, 2);
        brush(session, Action.TOOL, "", "ERASE");
        Preview woods = same(session, centre, 0, 0, "ground");
        assertTrue(woods.removed().isEmpty(), "Erasing a component keeps the objects");
        assertTrue(woods.after().values().stream().noneMatch(hex -> hex.containsTerrain(Terrains.WOODS)));
        // A route brush erases its terrain layer and cuts the neighbours' sides; no stored object is removed.
        brush(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        brush(session, Action.TOOL, "", "ERASE");
        Preview rail = same(session, new Coords(10, 5), 0, 0, "ground");
        assertTrue(rail.removed().isEmpty());
        assertFalse(rail.after().get(new Coords(10, 5)).containsTerrain(Terrains.RAIL));
        // A hex stamp erases whole hexes.
        brush(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(10, 4), 0, 0, false); session.finishStroke();
        brush(session, Action.SAMPLE, "", "");
        brush(session, Action.TOOL, "", "ERASE");
        same(session, new Coords(10, 4), 0, 0, "ground");
    }

    /**
     * While a route is drawn, the preview is the route drawn so far and the step into the neighbour the pointer heads for,
     * the gentle piece or a tight step's stubs, exactly as the release then builds it once the pointer has entered that
     * hex; barely moved from where it entered its hex (here the centre) it heads nowhere, and the preview is the route as
     * drawn. Other strokes and tools preview nothing.
     */
    @Test void aRouteStrokePreviewsTheStepThePointerHeadsFor() {
        for (int side : new int[] { 3, 2, 1 }) {
            var session = new BoardEditorSession();
            brush(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
            Coords start = new Coords(5, 2), turn = start.translated(3);
            session.pointer(start, 0, 0, false);
            session.pointer(turn, 0, 0, true);
            Preview drawn = session.preview(turn, .05, .05, "ground");
            assertEquals(List.of(start, turn), drawn.path());
            assertEquals(turn, drawn.at(), "Near the centre the pointer heads nowhere");
            assertEquals(java.util.Set.of(start, turn), drawn.after().keySet(), "No step ahead");
            float[] border = RailRoute.border(side);
            double x = border[0] / megamek.common.board.HexTile.MODEL_WIDTH * .6, y = border[1] / megamek.common.board.HexTile.HEIGHT * .6;
            assertEquals(side, BoardEditorSession.heading(x, y));
            Map<Coords, JsonNode> document = document(session);
            Preview next = session.preview(turn, x, y, "ground");
            assertEquals(document, document(session), "The preview leaves the stroke's document as it was");
            Coords ahead = turn.translated(side);
            assertEquals(ahead, next.at());
            assertTrue(next.after().containsKey(turn) && next.after().containsKey(ahead));
            session.pointer(ahead, 0, 0, true);
            session.finishStroke();
            next.after().forEach((at, hex) -> assertEquals(normal(BoardFile.hexNode(session.board().getHex(at))),
                  normal(BoardFile.hexNode(hex)), "Side " + side + " at " + at));
        }
        var session = new BoardEditorSession();
        brush(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        session.pointer(centre, 0, 0, false);
        assertSame(Preview.NONE, session.preview(centre.translated(1), 0, 0, "ground"), "A terrain stroke previews nothing");
        session.finishStroke();
        brush(session, Action.TOOL, "", "SCULPT");
        assertSame(Preview.NONE, session.preview(centre, 0, 0, "ground"));
        brush(session, Action.TOOL, "", "SELECT");
        assertSame(Preview.NONE, session.preview(centre, 0, 0, "ground"));
    }

    /**
     * The step a route stroke previews follows the pointer's movement, not its place: where it takes the hex it entered
     * (just inside that hex's core), the pointer still lies in the half it came in by, centred or off-centre. There it
     * heads nowhere (not back, nor into a side neighbour by a tight turn) until it moves; moving on straight, even with a
     * little drift, it heads straight on. Just inside the hex's border, before its core, the hex is not taken yet: the
     * preview is the step into it, which a release there builds.
     */
    @Test void aRouteStrokeHeadsWhereItsPointerMovesNotWhereItLies() {
        for (double across : new double[] { 0, .15, -.15 }) {
            var session = new BoardEditorSession();
            brush(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
            Coords start = new Coords(5, 2), turn = start.translated(3);
            session.pointer(start, 0, 0, false);
            session.pointer(turn, across, .42, true);
            assertEquals(List.of(start, turn), session.preview(turn, across, .42, "ground").path(), "A release builds it");
            assertEquals(turn, session.preview(turn, across, .42, "ground").at(), "The step into it");
            // The pointer reaches the core of the hex to the south just inside it, in its north half.
            double y = .25;
            session.pointer(turn, across, y, true);
            assertEquals(turn, session.preview(turn, across, y, "ground").at(), "Entering at " + across + " heads nowhere yet");
            assertEquals(turn.translated(3), session.preview(turn, across, y - .3, "ground").at(), "Moving south from " + across);
            assertEquals(turn.translated(3), session.preview(turn, across + .1, y - .3, "ground").at(), "with drift");
        }
    }

    /** A 16 x 17 board with a building, water and a rail route with a curve. */
    private BoardEditorSession board() {
        var session = new BoardEditorSession();
        session.board().setHex(centre.translated(0), new Hex(0, "building:2;bldg_cf:40;bldg_elev:2", ""));
        session.board().setHex(centre.translated(3), new Hex(0, "water:1", ""));
        brush(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
        List<Coords> cells = new ArrayList<>(List.of(new Coords(10, 2)));
        for (int direction : new int[] { 3, 3, 3, 2, 2 }) { cells.add(cells.getLast().translated(direction)); }
        session.pointer(cells.getFirst(), 0, 0, false);
        cells.forEach(cell -> session.pointer(cell, 0, 0, true));
        session.finishStroke();
        return session;
    }

    /** The Woods card at {@code level} (its Brush row). */
    private static void woods(BoardEditorSession session, int level) {
        brush(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
        brush(session, Action.BRUSH_VALUE, "terrain:woods", Integer.toString(level));
    }

    private static void brush(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
        assertTrue(session.snapshot().message().isEmpty() || action != Action.ASSET && action != Action.CHOOSE_BRUSH,
              session.snapshot().message());
    }

    /**
     * The preview at the pointer leaves the document, the session's state and the board's listeners alone and is kept
     * while nothing changes; the click then changes exactly its hexes, as it shows them. The click is undone again.
     */
    private static Preview same(BoardEditorSession session, Coords at, double x, double y, String receiver) {
        Map<Coords, JsonNode> document = document(session);
        var snapshot = session.snapshot();
        AtomicInteger events = new AtomicInteger();
        var listener = new BoardListenerAdapter() {
            @Override public void boardChangedHex(BoardEvent event) { events.incrementAndGet(); }
            @Override public void boardChangedAllHexes(BoardEvent event) { events.incrementAndGet(); }
            @Override public void boardNewBoard(BoardEvent event) { events.incrementAndGet(); }
        };
        session.board().addBoardListener(listener);
        Preview preview = session.preview(at, x, y, receiver);
        session.board().removeBoardListener(listener);
        assertEquals(0, events.get(), "No listener sees the preview");
        assertEquals(document, document(session), "The preview leaves the document as it was");
        assertEquals(snapshot, session.snapshot(), "and the session's state");
        assertSame(preview, session.preview(at, x, y, receiver), "It is kept while nothing changes");
        assertNotSame(Preview.NONE, preview, "The click at " + at + " changes something");

        boolean undoable = session.snapshot().canUndo();
        session.pointer(at, x, y, false, null, false, receiver);
        session.finishStroke();
        Map<Coords, JsonNode> changed = new LinkedHashMap<>();
        document(session).forEach((cell, node) -> { if (!node.equals(document.get(cell))) { changed.put(cell, normal(node)); } });
        Map<Coords, JsonNode> previewed = new LinkedHashMap<>();
        preview.after().forEach((cell, hex) -> previewed.put(cell, normal(BoardFile.hexNode(hex))));
        assertEquals(changed, previewed, "The preview shows the click's hexes as the click leaves them");
        preview.before().forEach((cell, hex) -> assertEquals(document.get(cell), BoardFile.hexNode(hex)));
        // The removed objects are exactly those whose ids the click took off the board.
        List<Selection> removed = new ArrayList<>();
        preview.before().forEach((cell, hex) -> hex.getDecorations().forEach(object -> {
            if (session.board().getHex(cell).getDecorations().stream().noneMatch(o -> o.id().equals(object.id()))
                  && preview.after().keySet().stream().noneMatch(other -> session.board().getHex(other).getDecorations().stream()
                        .anyMatch(o -> o.id().equals(object.id())))) {
                removed.add(new Selection(cell, object.id()));
            }
        }));
        assertEquals(removed, preview.removed());
        session.command(new Command(Action.UNDO, "", ""), null);
        assertEquals(document, document(session), "Undone for the next case");
        assertEquals(undoable, session.snapshot().canUndo());
        return preview;
    }

    private static Map<Coords, JsonNode> document(BoardEditorSession session) {
        Map<Coords, JsonNode> result = new LinkedHashMap<>();
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) {
                result.put(new Coords(x, y), BoardFile.hexNode(session.board().getHex(x, y)));
            }
        }
        return result;
    }

    /** A hex's file form without its objects' ids and groups, which every click makes new. */
    private static JsonNode normal(JsonNode hex) {
        JsonNode copy = hex.deepCopy();
        if (copy.has("decorations")) {
            copy.get("decorations").forEach(object -> ((ObjectNode) object).remove(List.of("id", "group")));
        }
        return copy;
    }
}
