/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Selection;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexIssue;
import org.junit.jupiter.api.Test;

/**
 * The twin guard: a placement where its exact twin ({@link BoardDecoration#twin}) stands adds nothing and selects that
 * twin with its group, a stamp is placed all or nothing, and the issues list reports the copies made before (by
 * Duplicate here), which one command removes in one undo step.
 */
class BoardEditorTwinTest {
    private static final String BEACON = "scenery/roofs/landing-beacon-hex", CLUSTER = "stamp/tree-cluster-3";
    private final Coords at = new Coords(1, 1), east = new Coords(2, 1);

    @Test
    void aThousandClicksInOneHexPlaceOneObjectWithoutAnUndoStepEach() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", BEACON);
        click(session, at, 0, 0);
        String first = session.snapshot().object();
        // Without precision every click puts the beacon on the hex centre, wherever in the hex it lands.
        for (int i = 0; i < 999; i++) { click(session, at, .3, -.2); }
        assertEquals(1, objects(session, at).size());
        assertEquals(List.of(new Selection(at, first)), session.snapshot().selection(), "The twin is selected");
        assertEquals(Messages.getString("BoardEditor.alreadyPlaced"), session.snapshot().message());
        assertEquals(BoardEditorSession.Tool.PAINT, session.snapshot().tool(), "Paint stays the tool");
        session.pointer(at, 0, 0, false);
        session.pointer(east, 0, 0, true);
        session.finishStroke();
        assertTrue(objects(session, east).isEmpty(), "A press on the twin places nothing where it drags");
        send(session, Action.UNDO, "", "");
        assertTrue(objects(session, at).isEmpty());
        assertFalse(session.snapshot().canUndo(), "Only the first click was an undo step");
    }

    @Test
    void withPrecisionOnlyTheSameSpotFindsTheTwinAndAnotherTurnIsAnotherObject() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", BEACON);
        send(session, Action.BRUSH_VALUE, "precision", "true");
        send(session, Action.BRUSH_VALUE, "magnetic", "false");
        click(session, at, .2, .1);
        click(session, at, .2, .1);
        assertEquals(1, objects(session, at).size(), "The same spot");
        click(session, at, -.2, .1);
        assertEquals(2, objects(session, at).size(), "Another spot");
        send(session, Action.BRUSH_VALUE, "object:rotation", "90");
        click(session, at, .2, .1);
        assertEquals(3, objects(session, at).size(), "Another turn on the same spot");
    }

    @Test
    void theSideViewsPlaceOnATwinSelectsIt() {
        var session = new BoardEditorSession();
        click(session, at, 0, 0);
        send(session, Action.ASSET, "", BEACON);
        send(session, Action.PLACE, "ground", ".1,.2,0");
        String first = session.snapshot().object();
        send(session, Action.PLACE, "ground", ".1,.2,0");
        assertEquals(1, objects(session, at).size());
        assertEquals(List.of(new Selection(at, first)), session.snapshot().selection());
        assertEquals(Messages.getString("BoardEditor.alreadyPlaced"), session.snapshot().message());
    }

    @Test
    void aStampOnItsTwinSelectsThatStampAndOneMissingAMemberIsPlacedWhole() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", CLUSTER);
        click(session, at, 0, 0);
        var stamp = objects(session, at);
        var members = Set.copyOf(session.snapshot().selection());
        assertTrue(stamp.size() > 1);
        send(session, Action.CLEAR_SELECTION, "", "");
        click(session, at, .3, .3);
        assertEquals(stamp, objects(session, at), "The stamp is there already");
        assertEquals(members, Set.copyOf(session.snapshot().selection()), "Its whole group is selected");
        assertEquals(Messages.getString("BoardEditor.alreadyPlaced"), session.snapshot().message());
        send(session, Action.REMOVE_OBJECT, stamp.getFirst().id(), "");
        click(session, at, 0, 0);
        assertEquals(2 * stamp.size() - 1, objects(session, at).size(), "Without one member it is placed whole again");
    }

    @Test
    void identicalCopiesAreIssuesThatOneUndoStepRemovesKeepingTheFirstAndItsGroup() {
        var session = new BoardEditorSession();
        send(session, Action.ASSET, "", BEACON);
        click(session, east, 0, 0);
        String beacon = session.snapshot().object();
        send(session, Action.DUPLICATE_OBJECT, "", "");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        send(session, Action.ASSET, "", CLUSTER);
        click(session, at, 0, 0);
        var stamp = objects(session, at);
        // Duplicate copies the whole selected group in place, on purpose: every tree of the stamp now has one copy.
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(2 * stamp.size(), objects(session, at).size());

        var snapshot = session.snapshot();
        assertEquals(2 + stamp.size(), snapshot.copies());
        assertTrue(snapshot.issues().contains(new HexIssue(east, beacon,
              Messages.getString("BoardEditor.issueCopies", 2, "Hex landing beacon"))), "One issue at the first beacon");
        assertEquals(stamp.stream().map(BoardDecoration::id).toList(), snapshot.issues().stream()
              .filter(issue -> issue.coords().equals(at)).map(HexIssue::object).toList(), "One issue per copied tree");

        send(session, Action.REMOVE_COPIES, "", "");
        assertEquals(stamp, objects(session, at), "The first stamp stays, in its group");
        assertEquals(1, objects(session, east).size());
        assertEquals(0, session.snapshot().copies());
        assertTrue(session.snapshot().issues().isEmpty());
        send(session, Action.UNDO, "", "");
        assertEquals(2 * stamp.size(), objects(session, at).size(), "One undo step brings back every copy");
        assertEquals(3, objects(session, east).size());
    }

    @Test
    void aFluffValueTypedAgainAddsNoCopiesOfItsArt() {
        var session = new BoardEditorSession();
        click(session, at, 0, 0);
        send(session, Action.TERRAIN, "fluff", "5");
        var converted = objects(session, at);
        assertFalse(converted.isEmpty());
        send(session, Action.TERRAIN, "fluff", "5");
        assertEquals(converted, objects(session, at));
    }

    @Test
    void aFluffValueTypedAgainBesideAMovedPieceAddsItsWholeArtUnderFreshIdsAndGroups() {
        var session = new BoardEditorSession();
        click(session, at, 0, 0);
        send(session, Action.TERRAIN, "fluff", "5");
        var converted = objects(session, at);
        send(session, Action.SELECT_OBJECT, "", converted.getFirst().id());
        send(session, Action.OBJECT_VALUE, "x", Double.toString(converted.getFirst().x() + .1));
        send(session, Action.TERRAIN, "fluff", "5");
        var objects = objects(session, at);
        assertEquals(2 * converted.size(), objects.size(), "Not every piece has its twin: the whole art is added");
        assertEquals(objects.size(), objects.stream().map(BoardDecoration::id).distinct().count(), "Every id is fresh");
        var groups = converted.stream().map(BoardDecoration::group).filter(java.util.Objects::nonNull).toList();
        assertTrue(objects.subList(converted.size(), objects.size()).stream().map(BoardDecoration::group).noneMatch(groups::contains),
              "The new art is a group of its own");
    }

    private static List<BoardDecoration> objects(BoardEditorSession session, Coords coords) {
        return session.board().getHex(coords).getDecorations();
    }

    private static void click(BoardEditorSession session, Coords coords, double x, double y) {
        session.pointer(coords, x, y, false);
        session.finishStroke();
        session.release();
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
