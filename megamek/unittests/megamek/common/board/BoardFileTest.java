/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BoardFileTest {
    @Test void threeAxisRotationRoundTripsInVersionTwoAndOldObjectsDefaultToUpright() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var tilted = new BoardDecoration("tilted", "prop", "scenery/vehicles/car", null, .1, .2, 75,
              true, 1.2, BoardDecoration.Placement.ground(), 0, false, 30, -45);
        board.getHex(0, 0).setDecorations(List.of(tilted));
        String saved = encode(board);
        assertTrue(saved.contains("\"version\":2"));
        assertEquals(tilted, BoardFile.readNative(saved).getHex(0, 0).getDecorations().getFirst());
        assertEquals(30, tilted.duplicate().rotationX());
        assertEquals(-45, tilted.transform(.2, .3, 90, false, 2, tilted.placement()).rotationY());
        board.getHex(0, 0).setDecorations(List.of(prop("old", BoardDecoration.Placement.ground())));
        saved = encode(board);
        assertFalse(saved.contains("rotationX")); assertFalse(saved.contains("rotationY"));
        assertEquals(0, BoardFile.readNative(saved).getHex(0, 0).getDecorations().getFirst().rotationX());
    }
    @Test void stretchRoundTripsOnlyWhenSetAndEveryCopyOrTransformKeepsIt() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var stretch = new BoardDecoration.Stretch(.805556, 1, 1.5);
        var roof = prop("roof", BoardDecoration.Placement.ground()).withStretch(stretch).withGroup("g");
        board.getHex(0, 0).setDecorations(List.of(roof, prop("plain", BoardDecoration.Placement.ground())));
        String saved = encode(board);
        assertTrue(saved.contains("\"stretch\":[0.805556,1.0,1.5]"), saved);
        assertEquals(1, saved.split("\"stretch\"", -1).length - 1, "An unstretched object omits the field");
        assertEquals(board.getHex(0, 0).getDecorations(), BoardFile.readNative(saved).getHex(0, 0).getDecorations());
        assertEquals(BoardDecoration.Stretch.NONE, BoardFile.readNative(saved).getHex(0, 0).getDecorations().get(1).stretch());
        for (String invalid : List.of("[0.805556,1.0]", "[0.805556,0,1.5]", "[0.805556,-1,1.5]", "1.5")) {
            assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("[0.805556,1.0,1.5]", invalid)), invalid);
        }
        assertEquals(stretch, BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().stretch());
        assertEquals(stretch, roof.duplicate().stretch());
        assertEquals(stretch, BoardDecoration.copies(List.of(roof)).getFirst().stretch());
        assertEquals(stretch, roof.transform(.2, .3, 90, true, 2, roof.placement()).stretch());
        assertEquals(stretch, roof.withGroup(null).stretch());
        assertEquals(stretch, roof.flip(true, true).stretch(), "A reflection is the mirror flag; the local axes keep their factors");
        Board combined = megamek.common.util.BoardUtilities.combine(1, 1, 1, 2, new Board[] { board, board },
              megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        var second = combined.getHex(0, 1).getDecorations().getFirst();
        assertNotEquals(roof.id(), second.id(), "The second sheet's copy has a fresh identity");
        assertEquals(stretch, second.stretch(), "Combining sheets keeps it");
    }
    @Test void slotColoursRoundTripOnlyWhenSetAndEveryCopyOrTransformKeepsThem() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        var colours = BoardDecoration.Colours.of(null, "#63753D", null, null);
        assertEquals(java.util.Arrays.asList(null, "#63753d"), colours.slots(), "Lower case, trailing defaults dropped");
        var pond = prop("pond", BoardDecoration.Placement.ground()).withColours(colours).withGroup("g");
        board.getHex(0, 0).setDecorations(List.of(pond, prop("plain", BoardDecoration.Placement.ground())));
        String saved = encode(board);
        assertTrue(saved.contains("\"colours\":[null,\"#63753d\"]"), saved);
        assertEquals(1, saved.split("\"colours\"", -1).length - 1, "An object in its model's own colours omits the field");
        assertEquals(board.getHex(0, 0).getDecorations(), BoardFile.readNative(saved).getHex(0, 0).getDecorations());
        for (String invalid : List.of("[null,\"#63753\"]", "[null,\"red\"]", "[1,2]", "\"#63753d\"",
              "[null,null,null,null,\"#63753d\"]")) {
            assertThrows(Exception.class, () -> BoardFile.readNative(saved.replace("[null,\"#63753d\"]", invalid)), invalid);
        }
        assertEquals(colours, BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().colours());
        assertEquals(colours, pond.duplicate().colours());
        assertEquals(colours, BoardDecoration.copies(List.of(pond)).getFirst().colours());
        assertEquals(colours, pond.transform(.2, .3, 90, true, 2, pond.placement()).colours());
        assertEquals(colours, pond.withStretch(new BoardDecoration.Stretch(2, 1, 1)).withGroup(null).colours());
        assertEquals(colours, pond.flip(true, true).colours());
        Board combined = megamek.common.util.BoardUtilities.combine(1, 1, 1, 2, new Board[] { board, board },
              megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        assertEquals(colours, combined.getHex(0, 1).getDecorations().getFirst().colours(), "Combining sheets keeps them");
        assertEquals(BoardDecoration.Colours.NONE, colours.with(1, null), "Every slot back to its own colour stores nothing");
        assertThrows(IllegalArgumentException.class, () -> new BoardDecoration("paint", "decal", "decal/car-park/straight-4", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0).withColours(colours), "A decal has no colour slots");
    }

    @TempDir Path directory;

    private static BoardDecoration prop(String id, BoardDecoration.Placement placement) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", "Car", -.24, .18, 30,
              false, .8, placement, 0);
    }

    private static String encode(Board board) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoardFile.write(board, output);
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test void nativeRoundTripPreservesIndependentInstancesAndMetadata() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        board.setDocumentName("Park \"A\"");
        board.setDescription("First paragraph.\n\nSecond paragraph.");
        board.setSourceHeader("# Original author\r\n# License\r\n");
        board.addTag("example");
        board.setRoadsAutoExit(false);
        board.setAnnotations(new Coords(0, 0), List.of("Note one", "Note two"));
        board.setAnnotations(new Coords(5, 5), List.of("Keep an old out-of-bounds annotation"));
        Hex hex = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4;fluff:0:0", "CustomTheme");
        hex.setDecorations(List.of(prop("car-a", BoardDecoration.Placement.ground()),
              prop("car-b", BoardDecoration.Placement.absolute(4)),
              new BoardDecoration("decal-a", "decal", "hexes/marking.png", null, 0, 0, 15, true, 1,
                    BoardDecoration.Placement.surface("bridge", "deck", 0), 2)));
        hex.setAppearance(Map.of("building", new HexAppearance(null, "buildings/missing-but-retained", null, null),
              "bridge", HexAppearance.PILLARS));
        board.setHex(0, 0, hex);
        String serialized = encode(board);
        assertEquals(2, serialized.lines().filter(line -> line.contains("\"at\"") && line.contains("\"elevation\"")).count());
        assertTrue(serialized.contains("\"bridge\":{\"variant\":\"bridge/pillars\"}"), serialized);
        Board loaded = BoardFile.readNative(serialized);
        assertEquals(serialized, encode(loaded));
        assertTrue(HexAppearance.pillars(loaded.getHex(0, 0).getAppearance()), "The bridge's Pillars toggle round-trips");
        assertTrue(HexAppearance.pillars(BoardFile.fromClipboard(BoardFile.clipboard(hex)).getAppearance()), "and copies");
        // The other two bridge types round-trip the same way; only the 3D board reads them.
        for (var type : List.of(HexAppearance.BUILT_BRIDGE, HexAppearance.NATURAL_BRIDGE)) {
            Board typed = Board.createEmptyBoard(1, 1);
            Hex bridge = new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:1", "");
            bridge.setAppearance(Map.of("bridge", type));
            typed.setHex(0, 0, bridge);
            String text = encode(typed);
            assertTrue(text.contains("\"bridge\":{\"variant\":\"" + type.variant() + "\"}"), text);
            Board back = BoardFile.readNative(text);
            assertEquals(type, back.getHex(0, 0).getAppearance().get("bridge"));
            assertEquals(type == HexAppearance.BUILT_BRIDGE, HexAppearance.bridgeBuilt(back.getHex(0, 0).getAppearance()));
        }
        // A bridge appearance is a plain variant: an asset or a material belongs to another owner.
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, "buildings/a", null, null).validateOwner("bridge"));
        assertThrows(IllegalArgumentException.class, () -> new HexAppearance(null, null, "asphalt", null).validateOwner("bridge"));
        assertEquals(hex.getDecorations(), loaded.getHex(0, 0).getDecorations());
        assertEquals(board.getAnnotations(), loaded.getAnnotations());
        assertEquals(board.getSourceHeader(), loaded.getSourceHeader());
        assertTrue(loaded.getHex(0, 0).containsTerrain(Terrains.FLUFF));
        assertEquals(0, loaded.getHex(0, 0).terrainLevel(Terrains.FLUFF));
        assertTrue(loaded.isNativeFormat());
    }

    @Test void importKeepsLegacySelectorsAndNeverOverwritesTheOldFile() throws Exception {
        String legacy = "# Attribution\nsize 1 1\noption exit_roads_to_pavement false\n"
              + "description \"Old description\"\ntag \"Old tag\"\nnote 0101 \"Legacy note\"\n"
              + "hex 0101 0 \"building:1:65;bldg_elev:1;bldg_cf:15;fluff:0:0\" \"lunar\"\nend\n";
        Path original = directory.resolve("old.board"); Files.writeString(original, legacy);
        Board imported = BoardFile.read(original);
        assertEquals(65, imported.getHex(0, 0).getTerrain(Terrains.BUILDING).getExits());
        assertEquals(List.of("Legacy note"), imported.getAnnotations(new Coords(1, 1)));
        assertThrows(java.io.IOException.class, () -> BoardFile.save(imported, original));
        Path destination = directory.resolve("new.board2"); BoardFile.save(imported, destination);
        assertEquals(legacy, Files.readString(original));
        assertEquals(encode(imported), encode(BoardFile.read(destination)));
        try (var files = Files.list(directory)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }

    @Test void futureAndMalformedDocumentsCannotFallThroughToLegacyOrReplaceAnExistingBoard() {
        Board existing = Board.createEmptyBoard(3, 2);
        for (String invalid : List.of(
              "{\"format\":\"megamek-board\",\"version\":3,\"size\":{\"width\":1,\"height\":1},\"hexes\":[]}",
              "{\"format\":\"megamek-board\",\"version\":2,\"version\":2}",
              "{\"format\":\"megamek-board\",\"version\":2,\"unknownFutureField\":true}",
              "{broken json")) {
            assertThrows(IllegalArgumentException.class, () -> existing.load(invalid, null));
            assertEquals(3, existing.getWidth()); assertEquals(2, existing.getHeight());
        }
    }

    @Test void rejectDuplicateCoordinatesObjectIdsAndInvalidPlacement() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        board.getHex(0, 0).setDecorations(List.of(prop("same", BoardDecoration.Placement.ground())));
        board.getHex(1, 0).setDecorations(List.of(prop("different", BoardDecoration.Placement.ground())));
        String valid = encode(board);
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("different", "same")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("[2,1]", "[1,1]")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"scale\":0.8", "\"scale\":0")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"prop\"", "\"decal\"")
              .replace("\"offset\":0.0", "\"offset\":2.0")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"top\"", "\"future-surface\"")));
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(valid.replace("\"mode\":\"surface\"", "\"mode\":\"surface\",\"level\":4")));
    }

    @Test void copyClipboardAndJavaSerializationPreserveVisualsWithCorrectIdentity() throws Exception {
        Hex original = new Hex();
        original.setAppearance(Map.of("road", new HexAppearance("road/rounded", null, "roads/gravel", null)));
        original.setDecorations(List.of(prop("original", BoardDecoration.Placement.absolute(5))));
        Hex undo = original.duplicate();
        assertEquals(original.getDecorations(), undo.getDecorations());
        Hex pasted = Hex.parseClipboardString(original.getClipboardString());
        assertNotNull(pasted);
        assertNotEquals(original.getDecorations().getFirst().id(), pasted.getDecorations().getFirst().id());
        assertEquals(original.getAppearance(), pasted.getAppearance());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            Hex loaded = (Hex) input.readObject();
            assertEquals(original.getDecorations(), loaded.getDecorations());
            assertEquals(original.getAppearance(), loaded.getAppearance());
        }
        original.setDecorations(List.of());
        assertEquals(1, undo.getDecorations().size());
    }

    @Test void nativeMetadataAndLobbyNamesRemainDiscoverable() throws Exception {
        Board board = Board.createEmptyBoard(2, 3); board.addTag("test tag");
        Path file = directory.resolve("native.board2"); BoardFile.save(board, file);
        assertEquals(new BoardDimensions(2, 3), BoardFile.metadata(file).size());
        assertEquals(java.util.Set.of("test tag"), BoardFile.metadata(file).tags());
        assertEquals(new BoardDimensions(2, 3), Board.getSize(file.toFile()));
        assertEquals("native.board2", BoardFile.fileName(BoardFile.selectionName("native.board2")));
        assertEquals("legacy.board", BoardFile.fileName(BoardFile.selectionName("legacy.board")));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = json.readTree(Files.readString(file));
        var reordered = json.createObjectNode();
        reordered.set("hexes", original.get("hexes"));
        original.fields().forEachRemaining(entry -> { if (!entry.getKey().equals("hexes")) { reordered.set(entry.getKey(), entry.getValue()); } });
        Files.writeString(file, reordered.toString());
        assertEquals(new BoardDimensions(2, 3), BoardFile.metadata(file).size(), "Indexing must accept metadata after the hex array");
    }

    @Test void failedSaveKeepsThePreviousFileAndRejectsUnopenableTerrain() throws Exception {
        Board board = Board.createEmptyBoard(1, 1);
        Path file = directory.resolve("safe.board2"); BoardFile.save(board, file);
        String original = Files.readString(file);
        board.getHex(0, 0).addTerrain(new megamek.common.units.Terrain(Terrains.BUILDING, 1));
        assertThrows(java.io.IOException.class, () -> BoardFile.save(board, file));
        assertEquals(original, Files.readString(file));
    }

    @Test void shippedCompositionExampleLoadsWithIndependentHeightLayers() throws Exception {
        Board example = BoardFile.read(megamek.common.Configuration.boardsDir().toPath()
              .resolve("examples/6x6 Native Editor Composition.board2"));
        assertEquals(4, example.getHex(3, 1).getDecorations().size());
        var bridge = example.getHex(1, 1);
        assertEquals(4, bridge.terrainLevel(Terrains.BRIDGE_ELEV));
        assertTrue(bridge.getDecorations().stream().anyMatch(d -> d.placement().receiver().terrain().equals("ground")));
        assertTrue(bridge.getDecorations().stream().anyMatch(d -> d.placement().receiver().terrain().equals("bridge")));
        var elevated = example.getHex(1, 3).getDecorations();
        assertEquals(java.util.Set.of(4.0, 5.0), elevated.stream().map(d -> d.placement().level()).collect(java.util.stream.Collectors.toSet()));
        // The route marker keeps its N/S sides; a board flip reflects them as terrain exits are.
        var route = MaglevRoute.find(example.getHex(1, 3));
        assertEquals(9, route.connections());
        assertEquals(9, route.flip(true, false).connections());
        assertEquals(2 | 16, route.withConnections(4 | 32).flip(true, false).connections());
        assertEquals(4 | 32, route.withConnections(2 | 16).flip(false, true).connections());
        var paint = example.getHex(4, 4).getDecorations().getFirst();
        assertEquals(2.2, paint.scale()); assertFalse(paint.clipToHex());
        assertEquals(encode(example), encode(BoardFile.readNative(encode(example))));
    }

    @Test void versionTwoPreservesOlderClippingAndRoundTripsScaledSpanningPaint() throws Exception {
        Board board = Board.createEmptyBoard(2, 2);
        BoardDecoration decal = new BoardDecoration("paint", "decal", "decal/damage/rubble-light-path", null,
              1.2, -.4, 35, true, 3, BoardDecoration.Placement.ground(), 2, false);
        board.getHex(0, 0).setDecorations(List.of(decal));
        String text = encode(board);
        assertTrue(text.contains("\"version\":2"));
        assertEquals(decal, BoardFile.readNative(text).getHex(0, 0).getDecorations().getFirst());
        assertTrue(BoardFile.readNative(text.replace(",\"clipToHex\":false", ""))
              .getHex(0, 0).getDecorations().getFirst().clipToHex(), "Earlier development maps retain owner clipping");
        assertFalse(BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations().getFirst().clipToHex());
        assertEquals(decal.clipToHex(), decal.duplicate().clipToHex());
    }

    @Test void groupsRoundTripAndCopiesFormFreshGroups() throws Exception {
        Board board = Board.createEmptyBoard(2, 1);
        var table = prop("table", BoardDecoration.Placement.ground()).withGroup("L1_1_g0");
        var bench = prop("bench", BoardDecoration.Placement.ground()).withGroup("L1_1_g0");
        var loose = prop("loose", BoardDecoration.Placement.ground());
        board.getHex(0, 0).setDecorations(List.of(table, bench, loose));
        board.getHex(1, 0).setDecorations(List.of(prop("other", BoardDecoration.Placement.ground()).withGroup("L1_1_g0")));
        String saved = encode(board);
        assertTrue(saved.contains("\"group\":\"L1_1_g0\""));
        Board loaded = BoardFile.readNative(saved);
        assertEquals(board.getHex(0, 0).getDecorations(), loaded.getHex(0, 0).getDecorations());
        assertEquals("L1_1_g0", loaded.getHex(1, 0).getDecorations().getFirst().group(), "A group may span hexes");
        assertThrows(java.io.IOException.class, () -> BoardFile.readNative(saved.replace("\"L1_1_g0\"", "\" \"")));
        Hex ungrouped = board.getHex(0, 0).duplicate();
        ungrouped.setDecorations(List.of(table.withGroup(null), bench, loose));
        assertNotEquals(BoardFile.hexNode(board.getHex(0, 0)), BoardFile.hexNode(ungrouped),
              "The editor records an edit only when the serialized hex changes");

        assertNull(table.duplicate().group());
        assertEquals("L1_1_g0", table.transform(.1, .1, 90, true, 2, table.placement()).group());
        List<BoardDecoration> copies = BoardDecoration.copies(List.of(table, bench, loose));
        assertNotEquals("L1_1_g0", copies.get(0).group());
        assertEquals(copies.get(0).group(), copies.get(1).group());
        assertNull(copies.get(2).group());
        assertTrue(copies.stream().noneMatch(copy -> List.of("table", "bench", "loose").contains(copy.id())));
        var pasted = BoardFile.fromClipboard(BoardFile.clipboard(board.getHex(0, 0))).getDecorations();
        assertNotEquals("L1_1_g0", pasted.get(0).group());
        assertEquals(pasted.get(0).group(), pasted.get(1).group());
        assertNull(pasted.get(2).group());
    }
}
