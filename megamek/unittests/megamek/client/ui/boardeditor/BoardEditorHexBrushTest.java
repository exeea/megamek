/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.MaglevRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorHexBrushTest {
    @TempDir Path directory;
    private final Coords source = new Coords(1, 1), first = new Coords(4, 4), second = new Coords(6, 4);

    @Test void stampsCapturedContentsWithFreshIdsAndOneUndoRestoresTheWholeStroke() throws Exception {
        var session = new BoardEditorSession();
        Hex sample = new Hex(3, "road:4:9;woods:2;foliage_elev:2;rough:1", "snow");
        sample.setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "gravel", null)));
        var prop = new BoardDecoration("source-prop", "prop", "scenery/vehicles/car", "Parked car",
              .2, -.1, 35, true, .8, BoardDecoration.Placement.surface("ground", "top", .3), 0).withGroup("source-group");
        var decal = new BoardDecoration("source-decal", "decal", "decal/damage/rubble-light-path", null,
              -.2, .1, 20, false, 1.2, BoardDecoration.Placement.ground(), 3, false).withGroup("source-group");
        sample.setDecorations(List.of(prop, decal));
        session.board().setHex(source, sample);
        Hex replaced = new Hex(-1, "water:2", "desert");
        replaced.setDecorations(List.of(prop.duplicate()));
        session.board().setHex(first, replaced);
        Hex before = session.board().getHex(first).duplicate();
        select(session, source);
        // Sampling any terrain component captures the entire hex.
        send(session, Action.COMPONENT, "", "road");
        send(session, Action.SAMPLE, "", "");
        var brush = session.snapshot().activeBrush();
        assertEquals(source, brush.sampledHex());
        assertTrue(brush.label().contains("0202"));
        assertTrue(brush.themeEnabled()); assertTrue(brush.elevationEnabled());
        assertNull(brush.object(), "A stamp's contents must not become an object placement brush");
        assertEquals(sample.getDecorations(), brush.objects());
        assertFalse(session.dirty(), "Sampling itself does not edit the board");
        ObjectNode captured = contents(sample);
        send(session, Action.THEME, "", "lunar");
        send(session, Action.ELEVATION, "", "6");
        session.pointer(first, 0, 0, false);
        var firstIds = session.board().getHex(first).getDecorations();
        session.pointer(second, 0, 0, true);
        session.pointer(first, 0, 0, true);
        session.finishStroke();
        assertEquals(firstIds, session.board().getHex(first).getDecorations(), "A hex is stamped once per stroke");
        var ids = new HashSet<>(List.of(prop.id(), decal.id()));
        var groups = new HashSet<>(List.of(prop.group()));
        for (Coords at : List.of(first, second)) {
            Hex painted = session.board().getHex(at);
            assertEquals(captured, contents(painted));
            painted.getDecorations().forEach(object -> assertTrue(ids.add(object.id())));
            String group = painted.getDecorations().getFirst().group();
            assertTrue(groups.add(group));
            assertTrue(painted.getDecorations().stream().allMatch(object -> group.equals(object.group())));
        }
        assertEquals("lunar", session.board().getHex(source).getTheme());
        assertEquals(6, session.board().getHex(source).getLevel());
        assertEquals(source, session.snapshot().activeBrush().sampledHex(), "Painting cannot change the stamp's origin");
        var after = BoardFile.hexNode(session.board().getHex(first));
        send(session, Action.UNDO, "", "");
        assertEquals(BoardFile.hexNode(before), BoardFile.hexNode(session.board().getHex(first)));
        assertEquals(BoardFile.hexNode(new Hex(0)), BoardFile.hexNode(session.board().getHex(second)));
        send(session, Action.REDO, "", "");
        assertEquals(after, BoardFile.hexNode(session.board().getHex(first)));
        Path file = directory.resolve("hex-stamp.board2"); session.save(file);
        assertEquals(after, BoardFile.hexNode(BoardFile.read(file).getHex(first)));
    }

    @Test void aDefaultThemeAndEmptyContentsReplaceThePreviousHexToo() {
        for (String theme : java.util.Arrays.asList(null, "")) {
            var session = new BoardEditorSession();
            session.board().setHex(source, new Hex(0, "", theme));
            Hex target = new Hex(4, "woods:2;foliage_elev:2;road:3:9", "desert");
            target.setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "dirt", null)));
            target.setDecorations(List.of(car("old")));
            session.board().setHex(first, target);
            select(session, source); send(session, Action.SAMPLE, "", "");
            session.pointer(first, 0, 0, false); session.finishStroke();
            assertEquals(BoardFile.hexNode(new Hex(0)), BoardFile.hexNode(session.board().getHex(first)));
        }
    }

    @Test void levelAndThemeCanBeDisabledAndPaletteOrObjectSamplingLeavesHexStampMode() {
        var session = new BoardEditorSession();
        Hex sample = new Hex(3, "woods:1;foliage_elev:2", "snow"); sample.setDecorations(List.of(car("car")));
        session.board().setHex(source, sample);
        session.board().setHex(first, new Hex(1, "", "desert"));
        select(session, source); send(session, Action.SAMPLE, "", "");
        send(session, Action.BRUSH_VALUE, "applyTheme", "false");
        send(session, Action.BRUSH_VALUE, "applyElevation", "false");
        session.pointer(first, 0, 0, false); session.finishStroke();
        assertEquals("desert", session.board().getHex(first).getTheme());
        assertEquals(1, session.board().getHex(first).getLevel());
        assertEquals(1, session.board().getHex(first).terrainLevel(Terrains.WOODS));
        assertEquals(1, session.board().getHex(first).getDecorations().size());
        select(session, source); send(session, Action.SAMPLE, "", "");
        assertTrue(session.snapshot().activeBrush().themeEnabled());
        assertTrue(session.snapshot().activeBrush().elevationEnabled());
        send(session, Action.CHOOSE_BRUSH, "ground", "clear");
        assertNull(session.snapshot().activeBrush().sampledHex());
        assertNull(session.snapshot().activeBrush().object());
        session.pointer(first, 0, 0, false); session.finishStroke();
        assertEquals("desert", session.board().getHex(first).getTheme());
        assertEquals(1, session.board().getHex(first).getLevel());
        assertEquals(1, session.board().getHex(first).terrainLevel(Terrains.WOODS));
        select(session, source); send(session, Action.SAMPLE, "", "");
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        assertNull(session.snapshot().activeBrush().sampledHex());
        session.pointer(first, 0, 0, false); session.finishStroke();
        assertEquals(2, session.board().getHex(first).getDecorations().size());
        select(session, source); send(session, Action.SAMPLE, "", "");
        send(session, Action.SELECT_OBJECT, "", "car"); send(session, Action.SAMPLE, "", "");
        assertNull(session.snapshot().activeBrush().sampledHex());
        assertNotNull(session.snapshot().activeBrush().object());
        session.pointer(second, 0, 0, false); session.finishStroke();
        assertEquals(1, session.board().getHex(second).getDecorations().size());
        assertFalse(session.board().getHex(second).containsTerrain(Terrains.WOODS));
        assertEquals(0, session.board().getHex(second).getLevel());
    }

    @Test void stampedRoutesKeepJoinedSidesAndErasingAStampIsUndoable() {
        var session = new BoardEditorSession();
        var route = new BoardDecoration("route", "prop", MaglevRoute.ASSET, null,
              0, 0, 0, false, 1, BoardDecoration.Placement.surface("ground", "top", 1), 0)
              .withConnections(MaglevRoute.side(0) | MaglevRoute.side(3));
        session.board().getHex(source).setDecorations(List.of(route));
        select(session, source); send(session, Action.SAMPLE, "", "");
        Coords joined = first.translated(3);
        session.pointer(first, 0, 0, false); session.pointer(joined, 0, 0, true); session.finishStroke();
        assertEquals(MaglevRoute.side(3), MaglevRoute.find(session.board().getHex(first)).connections());
        assertEquals(MaglevRoute.side(0), MaglevRoute.find(session.board().getHex(joined)).connections());
        send(session, Action.TOOL, "", "ERASE");
        session.pointer(first, 0, 0, false); session.finishStroke();
        assertEquals(BoardFile.hexNode(new Hex(0)), BoardFile.hexNode(session.board().getHex(first)));
        assertEquals(0, MaglevRoute.find(session.board().getHex(joined)).connections());
        send(session, Action.UNDO, "", "");
        assertTrue(MaglevRoute.joined(MaglevRoute.find(session.board().getHex(first)), 3,
              MaglevRoute.find(session.board().getHex(joined))));
    }

    private static BoardDecoration car(String id) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
    }

    /** Compare every saved field except the identities that a copy must replace. */
    private static ObjectNode contents(Hex hex) {
        ObjectNode node = BoardFile.hexNode(hex);
        node.path("decorations").forEach(object -> ((ObjectNode) object).remove(List.of("id", "group")));
        return node;
    }

    private static void select(BoardEditorSession session, Coords at) {
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false); session.finishStroke();
        send(session, Action.COMPONENT, "", "ground");
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
