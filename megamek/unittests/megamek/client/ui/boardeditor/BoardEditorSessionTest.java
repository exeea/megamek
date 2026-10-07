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

    private BoardEditorSession session() {
        var session = new BoardEditorSession(); session.pointer(at, 0, 0, false); return session;
    }
    private void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
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
            send(session, Action.ASSET, "", "scenery/components/car-silver");
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
        assertEquals(copy.stream().sorted(java.util.Comparator.comparing(d -> d.id())).toList(), BoardFile.read(target).getHex(at).getDecorations());
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
        session.adjustElevation(Map.of()); session.adjustElevation(Map.of(at, 0));
        assertEquals(revision, session.snapshot().revision(), "Empty wheel captures must not rebuild the inspector during another drag");
        session.adjustElevation(Map.of(at, 3, new Coords(2, 2), 4)); session.finishStroke();
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
        send(session, Action.ASSET, "", "scenery/components/car-red");
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
        send(session, Action.ASSET, "", "scenery/components/car-red");
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

    @Test void draggingOffASupportFallsBackToGroundAndKeepsItsHeightOffset() {
        for (String receiver : java.util.List.of("bridge", "building", "industrial")) {
            var session = session();
            String terrain = switch (receiver) {
                case "bridge" -> "bridge:1:9;bridge_cf:40;bridge_elev:4";
                case "building" -> "building:1;bldg_cf:15;bldg_elev:4";
                default -> "heavy_industrial:4";
            };
            session.board().setHex(at, new megamek.common.Hex(0, terrain, ""));
            Coords destination = new Coords(at.getX() + 2, at.getY());
            session.board().setHex(destination, new megamek.common.Hex(2));
            String surface = receiver.equals("bridge") ? "deck" : receiver.equals("building") ? "roof" : "top";
            var prop = new megamek.common.board.BoardDecoration("prop", "prop", "scenery/components/car-red", null,
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

    @Test void magneticConnectorsNeedMatchingSetOrientationScaleAndHeight() {
        var blueprint = BoardEditorBlueprint.get();
        var board = megamek.common.board.Board.createEmptyBoard(12, 12);
        Coords target = new Coords(5, 5), owner = new Coords(5, 4);
        var fixed = new megamek.common.board.BoardDecoration("fixed", "prop", "scenery/fluff/maglevstation1", null,
              0, 0, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        board.getHex(target).setDecorations(java.util.List.of(fixed));
        var moving = new megamek.common.board.BoardDecoration("moving", "prop", "scenery/fluff/maglevtrack1", null,
              .06, .04, 0, false, 1, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        var snapped = BoardEditorSnapping.snap(board, blueprint, owner, moving, java.util.Set.of());
        assertEquals(0, snapped.x(), .000001); assertEquals(0, snapped.y(), .000001);
        assertEquals(moving.id(), snapped.id()); assertEquals(moving.rotation(), snapped.rotation());
        for (var invalid : java.util.List.of(moving.transform(.06, .04, 30, false, 1, moving.placement()),
              moving.transform(.06, .04, 0, false, 2, moving.placement()),
              moving.transform(.06, .04, 0, false, 1, megamek.common.board.BoardDecoration.Placement.surface("ground", "top", 1)),
              moving.transform(.4, .04, 0, false, 1, moving.placement()))) {
            assertEquals(invalid, BoardEditorSnapping.snap(board, blueprint, owner, invalid, java.util.Set.of()));
        }
        assertEquals(moving, BoardEditorSnapping.snap(board, blueprint, owner, moving, java.util.Set.of("fixed")), "A moving group never snaps to itself");
        // The second authored track points at -60 degrees; a +60 user rotation restores a vertical join.
        var diagonal = new megamek.common.board.BoardDecoration("moving", "prop", "scenery/fluff/maglevtrack2", null,
              .06, .04, 60, false, 1, moving.placement(), 0);
        var aligned = BoardEditorSnapping.snap(board, blueprint, owner, diagonal, java.util.Set.of());
        assertEquals(0, aligned.x(), .000001); assertEquals(0, aligned.y(), .000001);
        var mirrored = moving.transform(.06, .04, 180, true, 1, moving.placement());
        var mirrorSnap = BoardEditorSnapping.snap(board, blueprint, owner, mirrored, java.util.Set.of());
        assertEquals(0, mirrorSnap.x(), .000001); assertEquals(0, mirrorSnap.y(), .000001);
        assertNotNull(blueprint.asset("scenery/components/parking-barrier").snap());
        assertNull(blueprint.asset("scenery/components/concrete-pipe").snap(), "A vertical pipe is not a horizontal rail connector");
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
}
