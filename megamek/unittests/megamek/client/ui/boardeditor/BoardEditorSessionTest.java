/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardEditorSessionTest {
    @TempDir Path directory;
    private final Coords at = new Coords(1, 1);

    @Test void classicNoOpStrokeKeepsTheDocumentCleanAndRedoAvailable() {
        var session = session();
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.UNDO, "", "");
        assertFalse(session.dirty());
        session.beginHexEdit(at);
        session.finishClassicStroke();
        assertFalse(session.dirty());
        assertTrue(session.snapshot().canRedo());
        send(session, Action.REDO, "", "");
        assertEquals(2, session.board().getHex(at).getLevel());
    }

    @Test void classicBoardSettingsRemainUnsavedAcrossHexUndoAndSaveTogether() throws Exception {
        var session = session();
        Path file = directory.resolve("classic-settings.board2");
        session.save(file);
        send(session, Action.ELEVATION, "", "2");
        session.board().addTag("Arena");
        session.board().setRoadsAutoExit(!session.board().getRoadsAutoExit());
        send(session, Action.UNDO, "", "");
        assertTrue(session.dirty(), "Undoing a hex cannot hide unsaved board settings");
        session.save(file);
        assertFalse(session.dirty());
        var loaded = BoardFile.read(file);
        assertEquals(session.board().getTags(), loaded.getTags());
        assertEquals(session.board().getRoadsAutoExit(), loaded.getRoadsAutoExit());
    }

    private BoardEditorSession session() {
        var session = new BoardEditorSession(); session.pointer(at, 0, 0, false); return session;
    }
    private void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }

    @Test void contentsReorderingUpdatesPaintAndSurvivesUndoAndSave() throws Exception {
        var session = session();
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        var car = new megamek.common.board.BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1, ground, 0);
        var a = new megamek.common.board.BoardDecoration("a", "decal", "decal/damage/rubble-light-path", null, 0, 0, 0, false, 1, ground, 4);
        var b = new megamek.common.board.BoardDecoration("b", "decal", "decal/damage/rubble-light-path", null, .2, 0, 0, false, 1, ground, 2);
        var original = java.util.List.of(car, a, b);
        session.board().getHex(at).setDecorations(original);
        send(session, Action.SELECT_OBJECT, "", "car");
        send(session, Action.REORDER_OBJECT, "b", "before:a");
        var objects = session.snapshot().objects();
        assertEquals(java.util.List.of("car", "b", "a"), objects.stream().map(d -> d.id()).toList());
        assertTrue(objects.get(1).drawOrder() > objects.get(2).drawOrder());
        assertEquals("car", session.snapshot().object(), "Reordering preserves selection");
        assertEquals(b.placement(), objects.get(1).placement());
        assertEquals(b.x(), objects.get(1).x());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        send(session, Action.REDO, "", "");
        send(session, Action.REORDER_OBJECT, "car", "after:a");
        assertEquals("car", session.snapshot().objects().getLast().id());
        Path file = directory.resolve("contents-order.board2"); session.save(file);
        assertEquals(session.board().getHex(at).getDecorations(), BoardFile.read(file).getHex(at).getDecorations());
        send(session, Action.SELECT_OBJECT, "", "a");
        send(session, Action.OBJECT_VALUE, "order", "13");
        assertEquals("a", session.snapshot().objects().getFirst().id(), "Numeric paint order and contents agree");
    }

    @Test void buildsRoadAndBuildingContextWithoutAnyClassicEditor() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.EDGE, "road", "2");
        assertEquals(4, session.board().getHex(at).terrainLevel(Terrains.ROAD));
        assertEquals(4, session.board().getHex(at).getTerrain(Terrains.ROAD).getExits());
        send(session, Action.ADD_COMPONENT, "", "building");
        assertEquals(15, session.board().getHex(at).terrainLevel(Terrains.BLDG_CF));
        assertEquals(1, session.board().getHex(at).terrainLevel(Terrains.BLDG_ELEV));
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.BUILDING));
        assertEquals(4, session.board().getHex(at).getTerrain(Terrains.ROAD).getExits());
        send(session, Action.REDO, "", "");
        assertEquals(15, session.board().getHex(at).terrainLevel(Terrains.BLDG_CF));
    }

    @Test void composesThreeCarsAndTreeWithIndependentTransformsAndHeights() throws Exception {
        var session = session();
        for (int i = 0; i < 3; i++) {
            send(session, Action.ASSET, "", "scenery/vehicles/car");
            session.pointer(at, -.25 + i * .2, .15, false); session.finishStroke();
            send(session, Action.OBJECT_VALUE, "rotation", Integer.toString(i * 45));
            send(session, Action.OBJECT_VALUE, "scale", "0.8");
            send(session, Action.OBJECT_VALUE, "level", Integer.toString(i));
        }
        send(session, Action.ASSET, "", "birch-young");
        session.pointer(at, .3, -.2, false); session.finishStroke();
        send(session, Action.OBJECT_VALUE, "scale", "0.4");
        assertEquals(4, session.snapshot().objects().size());
        var copy = session.snapshot().objects();
        Path target = directory.resolve("composition.board2"); session.save(target);
        assertFalse(session.dirty());
        assertEquals(copy, BoardFile.read(target).getHex(at).getDecorations());
        send(session, Action.COPY, "", "");
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(2, 1), 0, 0, false);
        send(session, Action.PASTE, "", "");
        assertEquals(4, session.snapshot().objects().size());
        assertTrue(session.snapshot().objects().stream().noneMatch(d -> copy.stream().anyMatch(old -> old.id().equals(d.id()))));
        send(session, Action.UNDO, "", "");
        assertFalse(session.dirty(), "Undo back to the saved document clears dirty state");
    }

    @Test void strokeHistoryAndFailedLoadsCannotLoseTheCurrentDocument() throws Exception {
        var session = session();
        long revision = session.snapshot().revision();
        session.adjustElevation(null, 1); session.adjustElevation(at, 0);
        assertEquals(revision, session.snapshot().revision(), "Empty wheel captures must not rebuild the inspector during another drag");
        session.adjustElevation(at, 3); session.adjustElevation(new Coords(2, 2), 4);
        session.finishStroke();
        assertEquals(3, session.board().getHex(at).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(at).getLevel());
        assertEquals(0, session.board().getHex(2, 2).getLevel());
        send(session, Action.REDO, "", "");
        Path broken = directory.resolve("future.board2"); Files.writeString(broken, "{\"version\":99}");
        assertThrows(java.io.IOException.class, () -> session.open(broken));
        assertEquals(3, session.board().getHex(at).getLevel());
        assertTrue(session.dirty());
    }

    @Test void blueprintIsCompleteAndOwnerQueriesDoNotMutateLegacyRules() {
        var blueprint = BoardEditorBlueprint.get();
        var covered = blueprint.components().stream().flatMap(c -> c.fields().stream()).map(BoardEditorBlueprint.Field::terrain).toList();
        assertEquals(covered.size(), covered.stream().distinct().count());
        for (int type = 1; type < Terrains.SIZE; type++) { assertTrue(covered.contains(Terrains.getName(type)), Terrains.getName(type)); }
        var hex = new megamek.common.Hex(0, "road:1:9;fluff:12;woods:1;foliage_elev:2", "");
        hex.setAppearance(Map.of("road", new megamek.common.board.HexAppearance("road/rounded", null, null, null)));
        var query = blueprint.artwork(hex, "road");
        assertEquals(1, query.terrainLevel(Terrains.ROAD_FLUFF));
        assertEquals(12, query.terrainLevel(Terrains.FLUFF));
        assertFalse(hex.containsTerrain(Terrains.ROAD_FLUFF));
        assertEquals(9, hex.getTerrain(Terrains.ROAD).getExits());
    }

    @Test void paintsConfiguredRoadShapeAndMaterialTogetherAsOneUndoableStroke() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "road");
        send(session, Action.COMPONENT, "", "road");
        send(session, Action.TERRAIN, "road", "4");
        send(session, Action.VARIANT, "road", "road/rounded");
        send(session, Action.VARIANT, "road", "road/material/gravel");
        send(session, Action.SAMPLE, "", "");
        Coords second = new Coords(2, 1), third = new Coords(3, 1);
        session.pointer(second, 0, 0, false); session.pointer(third, 0, 0, true); session.finishStroke();
        for (Coords coordinate : java.util.List.of(second, third)) {
            var hex = session.board().getHex(coordinate);
            assertEquals(4, hex.terrainLevel(Terrains.ROAD));
            assertEquals("road/rounded", hex.getAppearance().get("road").variant());
            assertEquals("gravel", hex.getAppearance().get("road").material());
        }
        send(session, Action.UNDO, "", "");
        assertFalse(session.board().getHex(second).containsTerrain(Terrains.ROAD));
        assertFalse(session.board().getHex(third).containsTerrain(Terrains.ROAD));
        assertEquals("gravel", session.board().getHex(at).getAppearance().get("road").material());
    }

    @Test void malformedInputsDoNotEscapeTheCommandBoundary() {
        var session = session();
        assertDoesNotThrow(() -> send(session, Action.NEW, "", "16"));
        assertEquals(16, session.board().getWidth());
        send(session, Action.VARIANT, "road", "road/material/dirt");
        assertTrue(session.board().getHex(at).getAppearance().isEmpty(), "A road finish needs a road");
    }

    @Test void choosingAndConfiguringABrushDoesNotReadOrChangeTheInspectedHex() {
        var session = session();
        send(session, Action.CHOOSE_BRUSH, "vegetation", "jungle-3");
        assertEquals(BoardEditorSession.Tool.PAINT, session.snapshot().tool());
        assertFalse(session.dirty()); assertFalse(session.board().getHex(at).containsTerrain(Terrains.JUNGLE));
        send(session, Action.TOOL, "", "SELECT");
        Coords another = new Coords(4, 4); session.pointer(another, 0, 0, false); session.finishStroke();
        send(session, Action.TERRAIN, "woods", "1");
        send(session, Action.TOOL, "", "PAINT"); session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.JUNGLE));
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV));
        assertTrue(session.board().getHex(at).isValid(null));
        assertEquals(1, session.board().getHex(another).terrainLevel(Terrains.WOODS));
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        send(session, Action.BRUSH_VALUE, "object:scale", "1.5");
        send(session, Action.BRUSH_VALUE, "object:rotation", "75");
        assertTrue(session.snapshot().objects().isEmpty());
        session.pointer(at, .1, -.1, false); session.finishStroke();
        assertEquals(1.5, session.snapshot().objects().getFirst().scale());
        assertEquals(75, session.snapshot().objects().getFirst().rotation());
        send(session, Action.OBJECT_VALUE, "scale", ".5");
        assertEquals(1.5, session.snapshot().activeBrush().object().scale(), "An inspector edit cannot silently change the brush");
        send(session, Action.CHOOSE_BRUSH, "road", "3");
        assertNull(session.snapshot().activeBrush().object(), "Terrain replaces the object brush instead of leaving a second active brush");
    }

    @Test void movingAnObjectGroupPreservesRelativeTransformsAndOneUndoRestoresEverything() {
        var session = session();
        send(session, Action.ASSET, "", "scenery/vehicles/car");
        send(session, Action.BRUSH_VALUE, "precision", "true");
        session.pointer(at, -.2, .1, false); session.finishStroke();
        String first = session.snapshot().object();
        session.pointer(at, .2, -.1, false); session.finishStroke();
        String second = session.snapshot().object();
        var before = placedObjects(session);
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(at, -.2, .1, false, first); session.finishStroke();
        session.pointer(at, .2, -.1, false, second, true); session.finishStroke();
        assertEquals(2, session.snapshot().selection().size());
        session.pointer(at, -.2, .1, false, first);
        session.pointer(at, 1.8, -.5, true, first);
        assertEquals(at, session.snapshot().selected(), "The original owner is stable until release");
        assertEquals(first, session.snapshot().object());
        assertEquals(2, session.board().getHex(at).getDecorations().size());
        var preview = new java.util.HashSet<>(session.snapshot().movePreview());
        assertFalse(preview.contains(at), "Destination ground footprints follow the moving group");
        session.finishStroke();
        var moved = placedObjects(session);
        assertEquals(2, moved.size());
        assertTrue(session.board().getHex(at).getDecorations().isEmpty(), "Moved anchors belong to their destination hex");
        for (var entry : moved.entrySet()) {
            Coords dest = entry.getKey().coords();
            var original = before.get(new BoardEditorSession.Selection(at, entry.getKey().object()));
            assertNotNull(original);
            assertEquals(at.getX() * .75 + original.x() + 2, dest.getX() * .75 + entry.getValue().x(), .00001);
            assertEquals(-at.getY() - (at.getX() & 1) * .5 + original.y() - .6,
                  -dest.getY() - (dest.getX() & 1) * .5 + entry.getValue().y(), .00001);
            assertTrue(Math.abs(entry.getValue().x()) <= .5 && Math.abs(entry.getValue().y()) <= .5);
        }
        assertEquals(moved.keySet(), new java.util.HashSet<>(session.snapshot().selection()));
        assertEquals(preview, moved.keySet().stream().map(BoardEditorSession.Selection::coords).collect(java.util.stream.Collectors.toSet()),
              "Release must use the exact highlighted destinations");
        send(session, Action.UNDO, "", ""); assertEquals(before, placedObjects(session));
        send(session, Action.REDO, "", ""); assertEquals(moved, placedObjects(session));
    }

    @Test void dragRetainsItsObjectAcrossHexBoundariesAndRejectsAnOutsideCentre() {
        var session = session();
        send(session, Action.ASSET, "", "birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        String id = session.snapshot().object();
        send(session, Action.TOOL, "", "SELECT"); session.pointer(at, 0, 0, false, id);
        for (double x : new double[] { .5, 1.2, .4, 1.7 }) {
            session.pointer(at, x, 0, true, id);
            assertEquals(id, session.snapshot().object()); assertEquals(at, session.snapshot().selected());
            assertEquals(java.util.List.of(new BoardEditorSession.Selection(at, id)), session.snapshot().selection());
            assertEquals(1, placedObjects(session).size());
        }
        var last = session.snapshot().objects(); var target = session.snapshot().movePreview().getFirst();
        session.pointer(at, -20, 0, true, id);
        assertEquals(last, session.snapshot().objects(), "An invalid move retains the last valid pose");
        assertEquals(java.util.List.of(target), session.snapshot().movePreview());
        session.finishStroke();
        assertEquals(target, session.snapshot().selected()); assertEquals(id, session.snapshot().object());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(target, id)), session.snapshot().selection());
    }

    @Test void clickingAndTransformingObjectsPreserveContentsOrderWithoutNoOpHistory() {
        var session = session();
        var first = new megamek.common.board.BoardDecoration("z", "prop", "scenery/vehicles/car", null,
              -.1, .1, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var middle = new megamek.common.board.BoardDecoration("a", "prop", "scenery/vehicles/car", null,
              0, -.1, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var last = new megamek.common.board.BoardDecoration("x", "prop", "birch-young", null,
              .1, .2, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var original = java.util.List.of(first, middle, last);
        session.board().getHex(at).setDecorations(original);
        for (var object : java.util.List.of(middle, last, first)) {
            session.pointer(at, object.x(), object.y(), false, object.id()); session.finishStroke();
            assertEquals(original, session.board().getHex(at).getDecorations(), "A click cannot reorder or round local offsets");
            assertFalse(session.dirty()); assertFalse(session.snapshot().canUndo(), "A click is not a document edit");
        }
        send(session, Action.OBJECT_VALUE, "scale", "1.5");
        assertEquals(java.util.List.of("z", "a", "x"), session.snapshot().objects().stream().map(object -> object.id()).toList());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        assertFalse(session.snapshot().canUndo());
        session.pointer(at, last.x(), last.y(), false, last.id()); session.finishStroke();
        session.pointer(at, first.x(), first.y(), false, first.id(), true); session.finishStroke();
        session.pointer(at, last.x(), last.y(), false, last.id());
        session.pointer(at, last.x() + .05, last.y(), true, last.id());
        assertEquals(java.util.List.of("z", "a", "x"), session.board().getHex(at).getDecorations().stream().map(object -> object.id()).toList(),
              "Group selection order cannot reorder same-owner objects during movement");
        session.finishStroke(); send(session, Action.UNDO, "", "");
        assertEquals(original, session.board().getHex(at).getDecorations());
        assertFalse(session.snapshot().canUndo(), "The move remains a single undo operation");
    }

    @Test void objectEditsKeepTheirGroupAndPastedGroupsAreFresh() {
        var session = session();
        var ground = megamek.common.board.BoardDecoration.Placement.ground();
        var table = new megamek.common.board.BoardDecoration("table", "prop", "scenery/vehicles/car", null,
              -.1, .1, 0, false, 1, ground, 0).withGroup("picnic");
        var bench = new megamek.common.board.BoardDecoration("bench", "prop", "scenery/vehicles/car", null,
              .1, .1, 0, false, 1, ground, 0).withGroup("picnic");
        var loose = new megamek.common.board.BoardDecoration("loose", "prop", "birch-young", null,
              0, -.2, 0, false, 1, ground, 0);
        session.board().getHex(at).setDecorations(java.util.List.of(table, bench, loose));
        send(session, Action.SELECT_OBJECT, "", "table");
        send(session, Action.OBJECT_VALUE, "rotation", "30");
        assertEquals("picnic", session.board().getHex(at).getDecorations().getFirst().group());
        send(session, Action.COPY, "", "");
        send(session, Action.TOOL, "", "SELECT");
        session.pointer(new Coords(2, 1), 0, 0, false);
        send(session, Action.PASTE, "", "");
        var pasted = session.board().getHex(new Coords(2, 1)).getDecorations();
        assertEquals(3, pasted.size());
        assertNotEquals("picnic", pasted.get(0).group(), "A pasted group never joins its original");
        assertEquals(pasted.get(0).group(), pasted.get(1).group());
        assertNull(pasted.get(2).group());
    }

    @Test void draggingOffASupportFallsBackToGroundAndKeepsItsHeightOffset() {
        for (String receiver : java.util.List.of("bridge", "building", "industrial", "fuelTank", "ice")) {
            var session = session();
            String terrain = switch (receiver) {
                case "bridge" -> "bridge:1:9;bridge_cf:40;bridge_elev:4";
                case "building" -> "building:1;bldg_cf:15;bldg_elev:4";
                case "fuelTank" -> "fuel_tank:1;fuel_tank_cf:15;fuel_tank_elev:4;fuel_tank_magn:100";
                case "ice" -> "water:1;ice:1";
                default -> "heavy_industrial:4";
            };
            session.board().setHex(at, new megamek.common.Hex(0, terrain, ""));
            Coords destination = new Coords(at.getX() + 2, at.getY());
            session.board().setHex(destination, new megamek.common.Hex(2));
            String surface = receiver.equals("bridge") ? "deck" : receiver.equals("building") ? "roof" : "top";
            var prop = new megamek.common.board.BoardDecoration("prop", "prop", "scenery/vehicles/car", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface(receiver, surface, .4), 0);
            var decal = new megamek.common.board.BoardDecoration("decal", "decal", "scenery/decals/scorch", null,
                  0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface(receiver, surface, 0), 0);
            session.board().getHex(at).setDecorations(java.util.List.of(prop, decal));
            session.pointer(at, 0, 0, false, prop.id()); session.finishStroke();
            session.pointer(at, 0, 0, false, decal.id(), true); session.finishStroke();
            session.pointer(at, 0, 0, false, prop.id());
            session.pointer(at, 1.5, 0, true, prop.id());
            assertEquals(at, session.snapshot().selected(), "The owner still changes only on release");
            assertTrue(session.snapshot().objects().stream().allMatch(object -> object.placement().receiver().terrain().equals("ground")));
            session.pointer(at, 0, 0, true, prop.id());
            assertEquals(java.util.List.of(prop, decal), session.snapshot().objects(), "Moving back restores the original attachments");
            session.pointer(at, 1.5, 0, true, prop.id()); session.finishStroke();
            assertEquals(destination, session.snapshot().selected());
            assertEquals(.4, session.snapshot().objects().getFirst().placement().offset());
            assertTrue(session.snapshot().objects().stream().allMatch(object -> object.placement().receiver().terrain().equals("ground")));
            send(session, Action.UNDO, "", "");
            assertEquals(java.util.List.of(prop, decal), session.board().getHex(at).getDecorations());
            assertTrue(session.board().getHex(destination).getDecorations().isEmpty());
        }
    }

    @Test void aPropPutDownOnIceStandsOnTheIce() {
        var session = session();
        session.board().setHex(at, new megamek.common.Hex(0, "water:1;ice:1", ""));
        send(session, Action.ASSET, "", "birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        assertEquals(megamek.common.board.BoardDecoration.Placement.surface("ice", "top", 0),
              session.snapshot().objects().getFirst().placement());
    }

    @Test void magneticConnectorsNeedMatchingSetOrientationScaleAndHeight() {
        var blueprint = BoardEditorBlueprint.get();
        var board = megamek.common.board.Board.createEmptyBoard(12, 12);
        Coords target = new Coords(5, 5);
        String barrier = "scenery/vehicles/parking-barrier";
        var fixed = new megamek.common.board.BoardDecoration("fixed", "prop", barrier, null,
              0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        board.getHex(target).setDecorations(java.util.List.of(fixed));
        // A barrier's ends lie half its length north and south of its anchor: the next barrier joins one length north.
        double join = 2 * blueprint.asset(barrier).snap().connectors().getFirst().y();
        var moving = new megamek.common.board.BoardDecoration("moving", "prop", barrier, null,
              .02, join + .03, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var snapped = BoardEditorSnapping.snap(board, blueprint, target, moving, java.util.Set.of());
        assertEquals(0, snapped.x(), .000001); assertEquals(join, snapped.y(), .000001);
        assertEquals(moving.id(), snapped.id()); assertEquals(moving.rotation(), snapped.rotation());
        for (var invalid : java.util.List.of(moving.transform(.02, join + .03, 30, false, 1, moving.placement()),
              new megamek.common.board.BoardDecoration(moving.id(), moving.kind(), moving.asset(), null, .02, join + .03, 0,
                    false, 1, moving.placement(), 0, false, 12, 0),
              moving.transform(.02, join + .03, 0, false, 2, moving.placement()),
              moving.transform(.02, join + .03, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface("ground", "top", 1)),
              moving.transform(.4, join + .03, 0, false, 1, moving.placement()))) {
            assertEquals(invalid, BoardEditorSnapping.snap(board, blueprint, target, invalid, java.util.Set.of()));
        }
        assertEquals(moving, BoardEditorSnapping.snap(board, blueprint, target, moving, java.util.Set.of("fixed")), "A moving group never snaps to itself");
        // Turned half round and mirrored, the barrier's ends are where they were.
        var mirrored = moving.transform(.02, join + .03, 180, true, 1, moving.placement());
        var mirrorSnap = BoardEditorSnapping.snap(board, blueprint, target, mirrored, java.util.Set.of());
        assertEquals(0, mirrorSnap.x(), .000001); assertEquals(join, mirrorSnap.y(), .000001);
        assertNull(blueprint.asset("scenery/construction/concrete-pipe").snap(), "A vertical pipe is not a horizontal rail connector");
        // Stretched twice as long, both barriers' ends lie a whole length from their anchors; a plain one does not join them.
        var longer = new megamek.common.board.BoardDecoration.Stretch(1, 2, 1);
        var longMoving = moving.transform(.02, 2 * join + .03, 0, false, 1, moving.placement()).withStretch(longer);
        assertEquals(longMoving, BoardEditorSnapping.snap(board, blueprint, target, longMoving, java.util.Set.of()));
        board.getHex(target).setDecorations(java.util.List.of(fixed.withStretch(longer)));
        var longSnap = BoardEditorSnapping.snap(board, blueprint, target, longMoving, java.util.Set.of());
        assertEquals(0, longSnap.x(), .000001); assertEquals(2 * join, longSnap.y(), .000001);
        assertEquals(longer, longSnap.stretch());
    }

    @Test void roadChoicesAlwaysCarryTheirDefinedFinishAndRemovingAnotherObjectKeepsSelection() {
        var session = session();
        for (var choice : BoardEditorBlueprint.get().component("road").fields().getFirst().choices()) {
            send(session, Action.CHOOSE_BRUSH, "road", Integer.toString(choice.value()));
            session.pointer(at, 0, 0, false); session.finishStroke();
            assertEquals(choice.material(), session.board().getHex(at).getAppearance().get("road").material());
            assertEquals(choice.value(), session.board().getHex(at).terrainLevel(Terrains.ROAD));
        }
        send(session, Action.TERRAIN, "road", "2");
        assertEquals("asphalt", session.board().getHex(at).getAppearance().get("road").material());
        send(session, Action.VARIANT, "road", "road/material/dirt");
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.ROAD));
        send(session, Action.ASSET, "", "birch-young"); session.pointer(at, 0, 0, false); session.finishStroke();
        String first = session.snapshot().object(); session.pointer(at, 0, 0, false); session.finishStroke();
        String second = session.snapshot().object(); send(session, Action.REMOVE_OBJECT, first, "");
        assertEquals(second, session.snapshot().object()); assertEquals(1, session.snapshot().objects().size());
    }

    private static megamek.common.board.BoardDecoration prop(String id, double x, double y) {
        return new megamek.common.board.BoardDecoration(id, "prop", "scenery/vehicles/car", null, x, y, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.ground(), 0);
    }
    private static java.util.Set<String> ids(BoardEditorSession session) {
        return session.snapshot().selection().stream().map(BoardEditorSession.Selection::object).collect(java.util.stream.Collectors.toSet());
    }
    private void click(BoardEditorSession session, Coords coords, String id, boolean shift) {
        session.pointer(coords, 0, 0, false, id, shift); session.finishStroke(); session.release();
    }
    /** Board-global metric (pixel) position of an object, as the renderer places it. */
    private double[] metric(BoardEditorSession session, String id) {
        var entry = placedObjects(session).entrySet().stream().filter(e -> e.getKey().object().equals(id)).findFirst().orElseThrow();
        Coords owner = entry.getKey().coords();
        return new double[] { (owner.getX() * .75 + entry.getValue().x()) * 84, (-owner.getY() - (owner.getX() & 1) * .5 + entry.getValue().y()) * 72 };
    }
    private String group(BoardEditorSession session, String id) {
        return placedObjects(session).entrySet().stream().filter(e -> e.getKey().object().equals(id)).findFirst().orElseThrow().getValue().group();
    }

    @Test void groupsAreOneUndoStepAndClicksSelectWholeGroupsWithDrillDown() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1), prop("loose", .2, -.2)));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("c", 0, 0)));
        session.select(java.util.List.of(new BoardEditorSession.Selection(at, "a"), new BoardEditorSession.Selection(east, "c"),
              new BoardEditorSession.Selection(at, "missing")), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "A box selection ignores unknown objects");
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        String group = group(session, "a");
        assertNotNull(group); assertEquals(group, group(session, "c")); assertNull(group(session, "loose"));
        assertEquals(java.util.List.of(group), session.snapshot().groups()); assertEquals(group, session.snapshot().group());
        send(session, Action.UNDO, "", "");
        assertNull(group(session, "a")); assertNull(group(session, "c"));
        assertFalse(session.snapshot().canUndo(), "Grouping members in two hexes is one undo step");
        send(session, Action.REDO, "", "");
        assertEquals(group, group(session, "c"));

        send(session, Action.CLEAR_SELECTION, "", "");
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "Clicking a member selects its whole group");
        assertEquals("a", session.snapshot().object());
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "A second click without a drag drills down to the member");
        assertEquals("", session.snapshot().group());
        click(session, east, "c", false);
        assertEquals(java.util.Set.of("c"), ids(session), "While drilled in, a sibling's click selects only the sibling");
        click(session, at, "loose", false);
        assertEquals(java.util.Set.of("loose"), ids(session));
        click(session, at, "a", true);
        assertEquals(java.util.Set.of("loose", "a", "c"), ids(session), "Shift adds the whole group");
        assertEquals(java.util.List.of("", group), session.snapshot().groups().stream().sorted().toList());
        assertEquals("", session.snapshot().group(), "A group plus a loose object is not one group");
        click(session, east, "c", true);
        assertEquals(java.util.Set.of("loose"), ids(session), "Shift removes the whole group");
        session.select(java.util.List.of(new BoardEditorSession.Selection(east, "c")), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.Set.of("loose", "a", "c"), ids(session), "An additive box selection expands to the group");
        session.select(java.util.List.of(new BoardEditorSession.Selection(east, "c")), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.Set.of("a", "c"), ids(session));

        double[] a = metric(session, "a"), c = metric(session, "c");
        session.pointer(at, -.2, .1, false, "a");
        session.pointer(at, .8, .1, true, "a");
        session.finishStroke(); session.release();
        assertEquals(java.util.Set.of("a", "c"), ids(session), "A press that drags moves the group instead of drilling down");
        double[] movedA = metric(session, "a"), movedC = metric(session, "c");
        assertEquals(84, movedA[0] - a[0], 1e-6); assertEquals(84, movedC[0] - c[0], 1e-6);
        assertEquals(a[1], movedA[1], 1e-6); assertEquals(c[1], movedC[1], 1e-6);
        assertEquals(group, group(session, "a"), "A move keeps membership across owner hexes");

        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK, null);
        assertNull(group(session, "a")); assertNull(group(session, "c"));
        send(session, Action.CLEAR_SELECTION, "", "");
        Coords ownerA = placedObjects(session).keySet().stream().filter(s -> s.object().equals("a")).findFirst().orElseThrow().coords();
        click(session, ownerA, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "The group index follows ungrouping");
        send(session, Action.UNDO, "", "");
        assertEquals(group, group(session, "a"), "Undoing ungroup is one step");
        click(session, ownerA, "a", false);
        assertEquals(java.util.Set.of("a", "c"), ids(session), "The group index is rebuilt after undo");
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        assertEquals("The selection is already one group.", session.snapshot().message());
        assertEquals(group, group(session, "a"));
    }

    @Test void groupTransformsPivotAboutTheCentroidAndDuplicateIntoAFreshGroup() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1).withGroup("g")));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("b", .1, -.1).withGroup("g")));
        send(session, Action.SELECT_OBJECT, "g", "");
        assertEquals("g", session.snapshot().group());
        assertEquals("a", session.snapshot().object(), "The inspected hex's member is the primary");
        double[] a = metric(session, "a"), b = metric(session, "b");
        double[] centre = { (a[0] + b[0]) / 2, (a[1] + b[1]) / 2 };
        double distance = Math.hypot(a[0] - b[0], a[1] - b[1]);

        send(session, Action.GROUP_VALUE, "rotation", "90");
        double[] turnedA = metric(session, "a"), turnedB = metric(session, "b");
        assertEquals(distance, Math.hypot(turnedA[0] - turnedB[0], turnedA[1] - turnedB[1]), 1e-6, "Yaw keeps metric distances");
        assertEquals(centre[0], (turnedA[0] + turnedB[0]) / 2, 1e-6); assertEquals(centre[1], (turnedA[1] + turnedB[1]) / 2, 1e-6);
        assertEquals(centre[0] - (a[1] - centre[1]), turnedA[0], 1e-6, "Counter-clockwise like an object's yaw");
        assertEquals(90, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        send(session, Action.UNDO, "", "");
        assertArrayEquals(a, metric(session, "a"), 1e-9); assertFalse(session.snapshot().canUndo(), "A group turn is one undo step");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "scale", "2");
        assertEquals(2 * distance, Math.hypot(metric(session, "a")[0] - metric(session, "b")[0], metric(session, "a")[1] - metric(session, "b")[1]), 1e-6);
        send(session, Action.UNDO, "", "");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "mirror", "true");
        assertEquals(2 * centre[0] - a[0], metric(session, "a")[0], 1e-6, "Mirror reflects about the centroid");
        assertEquals(a[1], metric(session, "a")[1], 1e-6);
        assertTrue(placedObjects(session).values().stream().allMatch(megamek.common.board.BoardDecoration::mirror));
        send(session, Action.UNDO, "", "");

        session.pointer(at, 0, 0, false); session.finishStroke(); // The mirror moved the inspected hex to b's owner.
        send(session, Action.SELECT_OBJECT, "g", "");
        assertEquals("a", session.snapshot().object());
        send(session, Action.GROUP_VALUE, "x", "0.3");
        assertEquals(a[0] + .3 * 84, metric(session, "a")[0], 1e-6); assertEquals(b[0] + .3 * 84, metric(session, "b")[0], 1e-6);
        send(session, Action.UNDO, "", "");

        // Values are relative to the group as it is (yaw 0, scale 1): a live drag's values are its total change, and
        // the next edit starts from the result.
        send(session, Action.SELECT_OBJECT, "g", "");
        for (String value : java.util.List.of("10", "20", "30")) { session.command(new Command(Action.GROUP_VALUE, "rotation", value), null, true); }
        session.finishStroke();
        assertEquals(30, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        send(session, Action.GROUP_VALUE, "rotation", "30");
        assertEquals(60, placedObjects(session).values().stream().filter(o -> o.id().equals("b")).findFirst().orElseThrow().rotation(), 1e-9);
        assertEquals(distance, Math.hypot(metric(session, "a")[0] - metric(session, "b")[0], metric(session, "a")[1] - metric(session, "b")[1]), 1e-6);
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertArrayEquals(a, metric(session, "a"), 1e-9); assertFalse(session.snapshot().canUndo(), "The live drag was one undo step");

        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(4, placedObjects(session).size());
        assertEquals(2, session.snapshot().selection().size());
        assertFalse(ids(session).contains("a") || ids(session).contains("b"), "The copies become the selection");
        String copy = session.snapshot().group();
        assertFalse(copy.isEmpty()); assertNotEquals("g", copy);
        assertEquals("g", group(session, "a"), "The original group is unchanged");
        send(session, Action.UNDO, "", "");
        assertEquals(2, placedObjects(session).size(), "A whole-group duplicate is one undo step");
    }

    @Test void groupingADrilledMemberWithALooseObjectSelectsAndTransformsTheWholeMergedGroup() {
        var session = session();
        Coords east = new Coords(2, 1);
        session.board().getHex(at).setDecorations(java.util.List.of(prop("a", -.2, .1).withGroup("g"), prop("c", .2, -.2)));
        session.board().getHex(east).setDecorations(java.util.List.of(prop("b", .1, -.1).withGroup("g")));
        click(session, at, "a", false);
        click(session, at, "a", false);
        assertEquals(java.util.Set.of("a"), ids(session), "Drilled into a");
        click(session, at, "c", true);
        assertEquals(java.util.Set.of("a", "c"), ids(session));
        session.key(java.awt.event.KeyEvent.VK_G, java.awt.event.InputEvent.CTRL_DOWN_MASK, null);
        String merged = group(session, "b");
        assertNotEquals("g", merged); assertEquals(merged, group(session, "a")); assertEquals(merged, group(session, "c"));
        assertEquals(java.util.Set.of("a", "b", "c"), ids(session), "The selection becomes the whole merged group");
        assertEquals(merged, session.snapshot().group());

        send(session, Action.GROUP_VALUE, "rotation", "90");
        assertTrue(placedObjects(session).values().stream().allMatch(o -> Math.abs(o.rotation() - 90) < 1e-9),
              "A group turn reaches the member that was outside the selection");

    }

    @Test void aGroupWithOneMemberLeftIsReportedAsUngrouped() {
        var session = session();
        var solo = prop("x", 0, 0).withGroup("solo");
        var pair = prop("y", .2, 0).withGroup("pair");
        session.board().getHex(at).setDecorations(java.util.List.of(solo, pair));
        session.board().getHex(new Coords(2, 1)).setDecorations(java.util.List.of(prop("z", 0, 0).withGroup("pair")));
        var snapshot = session.snapshot();
        assertEquals(Map.of("solo", 1, "pair", 2), snapshot.groupSizes(), "Sizes count members board-wide");
        assertFalse(snapshot.grouped(solo)); assertTrue(snapshot.grouped(pair));
    }

    @Test void onlyASelectedItemMovesAndABoxTakesObjectsBeforeHexes() {
        var session = session();
        Coords east = new Coords(2, 1), far = new Coords(6, 6);
        send(session, Action.ELEVATION, "", "3");
        session.board().getHex(east).setDecorations(java.util.List.of(prop("car", 0, 0)));
        session.board().getHex(far).setDecorations(java.util.List.of(prop("van", 0, 0)));
        send(session, Action.CLEAR_SELECTION, "", "");

        // A press on an unselected hex selects it; its drag moves nothing (the view boxes such drags).
        session.pointer(at, 0, 0, false); session.pointer(far, 0, 0, true); session.finishStroke(); session.release();
        assertEquals(3, session.board().getHex(at).getLevel()); assertEquals(0, session.board().getHex(far).getLevel());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(at, "")), session.snapshot().selection());
        // Dragging the now selected hex moves it, as one undo step.
        session.pointer(at, 0, 0, false); session.pointer(far, 0, 0, true); session.finishStroke(); session.release();
        assertEquals(0, session.board().getHex(at).getLevel()); assertEquals(3, session.board().getHex(far).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(3, session.board().getHex(at).getLevel()); assertEquals(0, session.board().getHex(far).getLevel());

        // The same for an object: the first press selects it in place, a press on the selected object drags it.
        send(session, Action.CLEAR_SELECTION, "", "");
        session.pointer(east, 0, 0, false, "car"); session.pointer(east, .9, 0, true, "car"); session.finishStroke(); session.release();
        assertEquals(0, placedObjects(session).get(new BoardEditorSession.Selection(east, "car")).x());
        assertEquals(java.util.Set.of("car"), ids(session));
        session.pointer(east, 0, 0, false, "car"); session.pointer(east, .9, 0, true, "car"); session.finishStroke(); session.release();
        assertFalse(placedObjects(session).containsKey(new BoardEditorSession.Selection(east, "car")), "The selected car moved east");

        // A box takes the objects it covers, or the hexes when it covers none; Shift adds and Ctrl removes the
        // selection's own kind.
        send(session, Action.UNDO, "", "");
        BoardEditorSession.Selection car = new BoardEditorSession.Selection(east, "car"), van = new BoardEditorSession.Selection(far, "van");
        BoardEditorSession.Selection hexAt = new BoardEditorSession.Selection(at, ""), hexFar = new BoardEditorSession.Selection(far, "");
        session.select(java.util.List.of(hexAt, hexFar, car), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.List.of(car), session.snapshot().selection());
        session.select(java.util.List.of(hexAt, hexFar), BoardEditorSession.SelectMode.REPLACE);
        assertEquals(java.util.List.of(hexAt, hexFar), session.snapshot().selection(), "A box over no object selects its hexes");
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.List.of(hexAt, hexFar), session.snapshot().selection(), "Shift adds only hexes to hexes");
        session.select(java.util.List.of(hexAt, van), BoardEditorSession.SelectMode.REMOVE);
        assertEquals(java.util.List.of(hexFar), session.snapshot().selection(), "Ctrl removes the covered hexes");
        assertEquals(far, session.snapshot().selected(), "The inspector follows what stays selected");
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.REPLACE);
        session.select(java.util.List.of(hexFar, van), BoardEditorSession.SelectMode.ADD);
        assertEquals(java.util.Set.of("car", "van"), ids(session));
        session.select(java.util.List.of(car), BoardEditorSession.SelectMode.REMOVE);
        assertEquals(java.util.List.of(van), session.snapshot().selection());
        session.select(java.util.List.of(van), BoardEditorSession.SelectMode.REMOVE);
        assertTrue(session.snapshot().selection().isEmpty()); assertEquals("", session.snapshot().object());

        // Shift drags never move; a Shift click still toggles.
        session.pointer(east, 0, 0, false, "car", true); session.pointer(east, .9, 0, true, "car", true); session.finishStroke();
        assertEquals(java.util.Set.of("car"), ids(session));
        assertEquals(0, placedObjects(session).get(car).x());
    }

    @Test void issuesFollowCommittedEditsUndoAndRedoAndAnIssueSelectsItsHex() throws Exception {
        var session = session();
        assertTrue(session.snapshot().issues().isEmpty(), "A new board has no issues");
        Coords east = new Coords(2, 1);
        int toward = java.util.stream.IntStream.range(0, 6).filter(d -> east.translated(d).equals(at)).findFirst().orElseThrow();
        // Set directly, without an edit: only the neighbour re-validation of the later edits can find east's issue.
        session.board().setHex(east, new megamek.common.Hex(0, "building:1:" + (1 << toward) + ";bldg_elev:1;bldg_cf:15", ""));

        send(session, Action.TERRAIN, "building", "2");
        assertEquals(java.util.Set.of(at, east), session.snapshot().issues().stream().map(BoardEditorSession.Issue::coords)
              .collect(java.util.stream.Collectors.toSet()), "The edited hex and its re-validated neighbour");
        assertTrue(session.snapshot().issues().stream().filter(issue -> issue.coords().equals(at)).findFirst().orElseThrow()
              .text().startsWith("Incomplete Building"), "The game's own rule text");
        send(session, Action.TERRAIN, "bldg_elev", "1");
        send(session, Action.TERRAIN, "bldg_cf", "40");
        assertEquals(java.util.List.of(new BoardEditorSession.Issue(east, "",
              "Building has an exit to a building of another Building Type (Light, Medium...).")), session.snapshot().issues(),
              "Completing the building leaves the neighbour's exit issue");
        var published = session.snapshot().issues();
        session.pointer(new Coords(9, 9), 0, 0, false); session.finishStroke();
        assertSame(published, session.snapshot().issues(), "Selection and other non-edits publish the same list");

        send(session, Action.UNDO, "", "");
        assertEquals(java.util.Set.of(at, east), session.snapshot().issues().stream().map(BoardEditorSession.Issue::coords)
              .collect(java.util.stream.Collectors.toSet()), "Undo re-validates");
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertTrue(session.snapshot().issues().isEmpty());
        send(session, Action.REDO, "", ""); send(session, Action.REDO, "", ""); send(session, Action.REDO, "", "");
        assertEquals(published, session.snapshot().issues(), "Redo re-validates");

        // An issue selects its hex, and the object when it is about one: a deck object whose bridge was removed.
        Coords deck = new Coords(5, 5);
        session.pointer(deck, 0, 0, false); session.finishStroke();
        send(session, Action.ADD_COMPONENT, "", "bridge");
        send(session, Action.ASSET, "", "birch-young");
        session.pointer(deck, 0, 0, false, null, false, "bridge"); session.finishStroke();
        String tree = session.snapshot().object();
        assertTrue(session.snapshot().issues().stream().noneMatch(issue -> issue.coords().equals(deck)));
        send(session, Action.REMOVE_COMPONENT, "", "bridge");
        var issue = session.snapshot().issues().stream().filter(found -> found.coords().equals(deck)).findFirst().orElseThrow();
        assertEquals(new BoardEditorSession.Issue(deck, tree, "Object on deck but no bridge"), issue);
        send(session, Action.CLEAR_SELECTION, "", ""); send(session, Action.TOOL, "", "PAINT");
        send(session, Action.SELECT_AT, deck.getX() + "," + deck.getY(), issue.object());
        assertEquals(deck, session.snapshot().selected()); assertEquals(tree, session.snapshot().object());
        assertEquals(java.util.List.of(new BoardEditorSession.Selection(deck, tree)), session.snapshot().selection());
        assertEquals(BoardEditorSession.Tool.SELECT, session.snapshot().tool());

        // Opening a document validates all of it: here an object whose art is missing.
        var board = megamek.common.board.Board.createEmptyBoard(4, 4);
        board.getHex(2, 3).setDecorations(java.util.List.of(new megamek.common.board.BoardDecoration("gone", "prop", "scenery/no-such-art",
              null, 0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0)));
        Path file = directory.resolve("missing.board2"); BoardFile.save(board, file);
        session.open(file);
        assertEquals(java.util.List.of(new BoardEditorSession.Issue(new Coords(2, 3), "gone", "Object art not found: scenery/no-such-art")),
              session.snapshot().issues());
    }

    private Map<BoardEditorSession.Selection, megamek.common.board.BoardDecoration> placedObjects(BoardEditorSession session) {
        var result = new java.util.LinkedHashMap<BoardEditorSession.Selection, megamek.common.board.BoardDecoration>();
        for (int x = 0; x < session.board().getWidth(); x++) {
            for (int y = 0; y < session.board().getHeight(); y++) {
                Coords coords = new Coords(x, y);
                for (var object : session.board().getHex(coords).getDecorations()) {
                    result.put(new BoardEditorSession.Selection(coords, object.id()), object);
                }
            }
        }
        return result;
    }

    @Test void movingHexGroupsHandlesOverlappingDestinationsAndRejectsPartialOutOfBoundsMoves() {
        var session = session();
        Coords second = at.translated(3), third = second.translated(3);
        send(session, Action.ELEVATION, "", "2");
        session.pointer(second, 0, 0, false); session.finishStroke(); send(session, Action.ELEVATION, "", "4");
        session.pointer(at, 0, 0, false); session.finishStroke();
        session.pointer(second, 0, 0, false, null, true); session.finishStroke();
        session.pointer(at, 0, 0, false); session.pointer(second, 0, 0, true);
        assertEquals(2, session.snapshot().movePreview().size());
        assertEquals(2, session.board().getHex(at).getLevel(), "Whole-hex movement is previewed until release");
        session.finishStroke();
        assertEquals(0, session.board().getHex(at).getLevel()); assertEquals(2, session.board().getHex(second).getLevel());
        assertEquals(4, session.board().getHex(third).getLevel());
        send(session, Action.UNDO, "", "");
        assertEquals(2, session.board().getHex(at).getLevel()); assertEquals(4, session.board().getHex(second).getLevel());
        assertEquals(0, session.board().getHex(third).getLevel());
        session.pointer(at, 0, 0, false); session.finishStroke();
        session.pointer(second, 0, 0, false, null, true); session.finishStroke();
        session.pointer(at, 0, 0, false); session.pointer(new Coords(15, 16), 0, 0, true); session.finishStroke();
        assertEquals(2, session.board().getHex(at).getLevel()); assertEquals(4, session.board().getHex(second).getLevel());
        assertTrue(session.snapshot().message().contains("whole selection"));
    }

    @Test void contextualFoliageCliffsAndElevatorsUseValidGameTerrain() {
        var session = session();
        send(session, Action.ADD_COMPONENT, "", "vegetation");
        send(session, Action.TERRAIN, "foliage_elev", "3");
        assertEquals(2, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV), "Light woods cannot use ultra-heavy canopy height");
        send(session, Action.TERRAIN, "jungle", "3");
        send(session, Action.TERRAIN, "foliage_elev", "2");
        assertEquals(3, session.board().getHex(at).terrainLevel(Terrains.FOLIAGE_ELEV), "Ultra-heavy jungle requires the rules' canopy height");
        var hex = session.board().getHex(at);
        assertFalse(hex.containsTerrain(Terrains.WOODS));
        assertEquals(3, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
        assertTrue(hex.isValid(null));
        send(session, Action.TERRAIN, "industrial_elevator", "-2");
        send(session, Action.ELEVATOR, "top", "4");
        send(session, Action.ELEVATOR, "capacity", "180");
        var terrain = session.board().getHex(at).getTerrain(Terrains.INDUSTRIAL_ELEVATOR);
        var elevator = megamek.common.IndustrialElevator.fromTerrain(megamek.common.board.BoardLocation.of(at, 0), terrain.getLevel(), terrain.getExits());
        assertEquals(4, elevator.getShaftTop()); assertEquals(180, elevator.getCapacityTons());
        send(session, Action.ELEVATION, "", "3");
        send(session, Action.EDGE, "cliff_top", "0");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.EDGE, "cliff_top", "0");
        assertEquals(1, session.board().getHex(at).getTerrain(Terrains.CLIFF_TOP).getExits());
    }

    @Test void undoRestoresNeighborCliffEdgesAfterElevationChanges() {
        var session = session();
        send(session, Action.ELEVATION, "", "2");
        send(session, Action.EDGE, "cliff_top", "0");
        Coords neighbor = at.translated(0);
        session.pointer(neighbor, 0, 0, false);
        send(session, Action.ELEVATION, "", "3");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(neighbor).getLevel());
        assertEquals(1, session.board().getHex(at).getTerrain(Terrains.CLIFF_TOP).getExits());
        assertTrue(session.board().getHex(neighbor).containsTerrain(Terrains.CLIFF_BOTTOM));
        send(session, Action.REDO, "", "");
        assertFalse(session.board().getHex(at).containsTerrain(Terrains.CLIFF_TOP));
    }

    @Test void liveNumericDragIsOneUndoStepAndRetainsItsSurfaceReceiver() {
        var session = session();
        send(session, Action.ASSET, "", "birch-young"); session.pointer(at, .2, .1, false); session.finishStroke();
        var original = session.snapshot().objects().getFirst();
        for (String value : java.util.List.of("1.1", "1.8", "3.5")) {
            session.command(new Command(Action.OBJECT_VALUE, "offset", value), null, true);
        }
        assertEquals(3.5, session.snapshot().objects().getFirst().placement().offset());
        assertEquals("ground", session.snapshot().objects().getFirst().placement().receiver().terrain());
        session.finishStroke(); send(session, Action.UNDO, "", "");
        assertEquals(original, session.snapshot().objects().getFirst());
        assertEquals(original.id(), session.snapshot().object(), "Undoing a value keeps its inspector selected");
        send(session, Action.REDO, "", "");
        assertEquals(3.5, session.snapshot().objects().getFirst().placement().offset());
        assertEquals(original.id(), session.snapshot().object());
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertTrue(session.snapshot().object().isEmpty(), "Undoing placement clears the removed selection");
    }

    @Test void stretchIsALiveObjectValueThatGroupEditsDuplicatesAndSavingKeep() throws Exception {
        var session = session();
        send(session, Action.ASSET, "", "scenery/vehicles/car"); session.pointer(at, .2, .1, false); session.finishStroke();
        var original = session.snapshot().objects().getFirst();
        for (String value : java.util.List.of("1.2", "1.6", "2")) {
            session.command(new Command(Action.OBJECT_VALUE, "stretchX", value), null, true);
        }
        session.finishStroke();
        send(session, Action.OBJECT_VALUE, "stretchZ", "0.5");
        var stretched = session.snapshot().objects().getFirst();
        assertEquals(new megamek.common.board.BoardDecoration.Stretch(2, 1, .5), stretched.stretch());
        assertEquals(original.scale(), stretched.scale(), "Stretch multiplies the uniform scale, it does not replace it");
        send(session, Action.OBJECT_VALUE, "stretchY", "0");
        assertEquals(stretched, session.snapshot().objects().getFirst(), "A stretch factor must be positive");
        send(session, Action.UNDO, "", "");
        assertEquals(new megamek.common.board.BoardDecoration.Stretch(2, 1, 1), session.snapshot().objects().getFirst().stretch());
        send(session, Action.UNDO, "", "");
        assertEquals(original, session.snapshot().objects().getFirst(), "The live stretch drag was one undo step");
        send(session, Action.REDO, "", ""); send(session, Action.REDO, "", "");
        assertEquals(stretched, session.snapshot().objects().getFirst());

        // Group edits keep each member's stretch on its own axes; a group's scale stays uniform.
        session.board().getHex(at).setDecorations(java.util.List.of(stretched.withGroup("g"), prop("other", -.2, -.1).withGroup("g")));
        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.GROUP_VALUE, "scale", "2");
        send(session, Action.GROUP_VALUE, "rotation", "90");
        send(session, Action.GROUP_VALUE, "mirror", "true");
        var member = placedObjects(session).values().stream().filter(o -> o.id().equals(stretched.id())).findFirst().orElseThrow();
        assertEquals(stretched.stretch(), member.stretch());
        assertEquals(2 * stretched.scale(), member.scale(), 1e-9);
        send(session, Action.SELECT_OBJECT, "g", "");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(2, placedObjects(session).values().stream().filter(o -> o.stretch().equals(stretched.stretch())).count());

        Path target = directory.resolve("stretch.board2"); session.save(target);
        var saved = placedObjects(session).values().stream().filter(o -> o.id().equals(stretched.id())).findFirst().orElseThrow();
        var loaded = BoardFile.read(target);
        assertEquals(saved.stretch(), java.util.stream.IntStream.range(0, loaded.getWidth()).boxed()
              .flatMap(x -> java.util.stream.IntStream.range(0, loaded.getHeight()).mapToObj(y -> loaded.getHex(x, y)))
              .flatMap(hex -> hex.getDecorations().stream()).filter(o -> o.id().equals(saved.id())).findFirst().orElseThrow()
              .stretch());
    }

    @Test void slotColoursAreObjectValuesThatPalettePresetsDuplicatesAndUndoKeep() {
        var session = session();
        // A palette pond is the freeform pool placed in the pond colours.
        send(session, Action.ASSET, "", "scenery/parks/pond-1"); session.pointer(at, .2, .1, false); session.finishStroke();
        var pond = session.snapshot().objects().getFirst();
        assertEquals("scenery/pools/freeform", pond.asset());
        assertEquals(java.util.List.of("#63753d", "#63753d", "#63753d", "#94c7ab"), pond.colours().slots());
        send(session, Action.OBJECT_VALUE, "colour3", "#E0F2FA");
        var clear = session.snapshot().objects().getFirst();
        assertEquals("#e0f2fa", clear.colours().slot(3));
        assertEquals(pond.colours().slot(0), clear.colours().slot(0), "Other slots keep their colours");
        send(session, Action.OBJECT_VALUE, "colour0", "teal");
        assertEquals(clear, session.snapshot().objects().getFirst(), "A colour is #rrggbb");
        send(session, Action.DUPLICATE_OBJECT, "", "");
        assertEquals(2, session.snapshot().objects().stream().filter(o -> o.colours().equals(clear.colours())).count());
        send(session, Action.UNDO, "", ""); send(session, Action.UNDO, "", "");
        assertEquals(pond, session.snapshot().objects().getFirst(), "Each colour choice is one undo step");
        send(session, Action.SELECT_OBJECT, "", pond.id());
        for (int slot = 0; slot < 4; slot++) { send(session, Action.OBJECT_VALUE, "colour" + slot, ""); }
        assertEquals(megamek.common.board.BoardDecoration.Colours.NONE, session.snapshot().objects().getFirst().colours(),
              "Empty returns a slot to the model's own colour");
    }

    @Test void globalThemeIsUndoableWithoutLosingHexOverridesOrObjects() {
        var session = session();
        send(session, Action.ASSET, "", "birch-young"); session.pointer(at, .2, .1, false); session.finishStroke();
        var objects = session.snapshot().objects();
        send(session, Action.THEME, "", "snow");
        send(session, Action.MAP_THEME, "", "desert");
        assertEquals("desert", session.board().getHex(at).getTheme());
        assertEquals("desert", session.board().getHex(0, 0).getTheme());
        send(session, Action.UNDO, "", "");
        assertEquals("snow", session.board().getHex(at).getTheme());
        assertNotEquals("desert", session.board().getHex(0, 0).getTheme());
        assertEquals(objects, session.snapshot().objects());
    }

    @Test void sculptStrokeMovesEachEnteredHexOnceAsOneUndoStepAndCtrlInverts() {
        var session = session();
        Coords first = new Coords(4, 4), second = new Coords(6, 4);
        send(session, Action.TOOL, "", "SCULPT");
        session.pointer(first, 0, 0, false);
        session.pointer(second, 0, 0, true); session.pointer(second, 0, 0, true); session.pointer(first, 0, 0, true);
        session.finishStroke();
        assertEquals(1, session.board().getHex(first).getLevel());
        assertEquals(1, session.board().getHex(second).getLevel(), "Re-entering a hex in one stroke does not raise it again");
        assertEquals("Raise · 1 hex", session.sculptHint(first, 1));
        send(session, Action.UNDO, "", "");
        assertEquals(0, session.board().getHex(first).getLevel());
        assertEquals(0, session.board().getHex(second).getLevel());
        assertFalse(session.snapshot().canUndo(), "The whole drag is a single undo step");
        session.pointer(first, 0, 0, false, null, false, "ground", true); session.finishStroke();
        assertEquals(-1, session.board().getHex(first).getLevel(), "Ctrl lowers in Raise mode");
        send(session, Action.BRUSH_VALUE, "sculpt", "LEVEL");
        assertEquals("Level to L0", session.sculptHint(second, 1));
        session.pointer(first, 0, 0, false); session.pointer(second, 0, 0, true); session.finishStroke();
        assertEquals(-1, session.board().getHex(second).getLevel(), "Level mode uses the first hex of the stroke");
    }

    @Test void ctrlWheelIgnoresSculptSlope() {
        var session = session();
        send(session, Action.TOOL, "", "SCULPT");
        Coords centre = new Coords(6, 6);
        for (int notch = 0; notch < 3; notch++) { session.adjustElevation(centre, 1); }
        session.finishStroke();
        assertEquals(3, session.board().getHex(centre).getLevel());
        centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        centre.allAtDistance(2).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        centre.allAtDistance(3).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        send(session, Action.UNDO, "", "");
        centre.allAtDistanceOrLess(2).forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        send(session, Action.BRUSH_VALUE, "slope", "false");
        session.adjustElevation(centre, 2); session.finishStroke();
        assertEquals(2, session.board().getHex(centre).getLevel());
        centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
    }

    @Test void ctrlWheelDirectionIgnoresSculptMode() {
        Coords centre = new Coords(6, 6);
        for (var mode : LevelSculpt.Mode.values()) {
            var session = session();
            send(session, Action.TOOL, "", "SCULPT");
            send(session, Action.BRUSH_VALUE, "sculpt", mode.name());
            session.adjustElevation(centre, -3); session.finishStroke();
            assertEquals(-3, session.board().getHex(centre).getLevel(), mode.name());
            centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
            session.adjustElevation(centre, 6); session.finishStroke();
            assertEquals(3, session.board().getHex(centre).getLevel(), mode.name());
            centre.allAdjacent().forEach(at -> assertEquals(0, session.board().getHex(at).getLevel()));
        }
    }

    @Test void fastSculptDragCoversSkippedHexes() {
        var session = session();
        Coords from = new Coords(2, 4), to = new Coords(7, 4);
        send(session, Action.TOOL, "", "SCULPT");
        send(session, Action.BRUSH_VALUE, "slope", "false");
        session.pointer(from, 0, 0, false); session.pointer(to, 0, 0, true); session.finishStroke();
        Coords.intervening(from, to).forEach(at -> assertEquals(1, session.board().getHex(at).getLevel(), "Ridge at " + at));
    }

    @Test void sculptKeepsWaterBridgeAndBuildingTerrainAndUndoRestoresSlopedNeighbours() {
        var session = session();
        send(session, Action.TOOL, "", "SCULPT");
        Coords centre = new Coords(4, 4), water = centre.translated(0), bridge = centre.translated(2), building = centre.translated(4);
        var board = session.board();
        board.setHex(centre, new megamek.common.Hex(0, "water:1", ""));
        board.setHex(bridge, new megamek.common.Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:1", ""));
        board.setHex(building, new megamek.common.Hex(0, "building:1;bldg_cf:15;bldg_elev:1", ""));
        board.setHex(water, new megamek.common.Hex(0, "water:1", ""));
        session.pointer(centre, 0, 0, false); session.finishStroke();
        session.pointer(centre, 0, 0, false); session.finishStroke();
        assertEquals(1, session.board().getHex(water).getLevel(), "Slope moves a neighbouring water surface too");
        assertEquals(1, session.board().getHex(centre).terrainLevel(Terrains.WATER));
        assertEquals(1, session.board().getHex(bridge).terrainLevel(Terrains.BRIDGE_ELEV));
        assertEquals(1, session.board().getHex(building).terrainLevel(Terrains.BLDG_ELEV));
        send(session, Action.UNDO, "", "");
        send(session, Action.UNDO, "", "");
        for (Coords hex : java.util.List.of(centre, water, bridge, building)) { assertEquals(0, session.board().getHex(hex).getLevel()); }
    }
}
