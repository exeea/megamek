/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one legacy conversion ({@link BoardSceneryLayouts#convert}): it keeps the tokens, the native flag means converted,
 * the hex-set form decodes the given hexes of a converted board, and it needs no image, so a headless server converts a
 * board exactly as a desktop client does.
 */
class BoardSceneryLayoutsTest {
    private static final Coords PICNIC = new Coords(0, 0), BRIDGE = new Coords(2, 0), PLAIN_BRIDGE = new Coords(7, 0),
          CAR_PARK = new Coords(1, 2), SECOND_PICNIC = new Coords(6, 2);

    /** A small legacy board: art the conversion decodes, a pillar-art bridge, a car park's lane and a cut emblem set. */
    static Board fixture() {
        Board board = Board.createEmptyBoard(9, 3);
        put(board, PICNIC, "fluff:93:8");
        put(board, new Coords(1, 0), "building:2;bldg_elev:2;bldg_cf:50;fluff:6:14");
        put(board, BRIDGE, "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:4:06");
        put(board, new Coords(3, 0), "road:2:09");
        put(board, new Coords(4, 0), "fluff:9:00");
        put(board, new Coords(5, 0), "woods:1;foliage_elev:2;fluff:80:01");
        put(board, new Coords(6, 0), "fluff:13:01");
        put(board, PLAIN_BRIDGE, "bridge:1:09;bridge_cf:40;bridge_elev:1");
        // The south piece of a set whose centre lies north of the board: the piece's hex owns the emblem.
        put(board, new Coords(8, 0), "pavement:1;fluff:62:13");
        put(board, CAR_PARK, "fluff:5:02");
        put(board, SECOND_PICNIC, "fluff:93:8");
        return board;
    }

    private static void put(Board board, Coords at, String terrain) { board.setHex(at, new Hex(0, terrain, "")); }

    static String text(Board board) throws IOException {
        var out = new ByteArrayOutputStream();
        BoardFile.write(board, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theTokensStayForThe2dBoardBesideTheirObjects() {
        Board loaded = fixture(), board = fixture();
        assertFalse(board.isNativeFormat());
        BoardSceneryLayouts.convert(board);
        assertTrue(board.isNativeFormat(), "The native flag means converted");
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                Hex before = loaded.getHex(x, y), after = board.getHex(x, y);
                for (int type : before.getTerrainTypes()) {
                    Terrain was = before.getTerrain(type), is = after.getTerrain(type);
                    assertTrue(is != null && is.getLevel() == was.getLevel() && is.getExits() == was.getExits(),
                          x + "," + y + " keeps " + was);
                }
            }
        }
        assertTrue(board.getHex(PICNIC).getDecorations().size() > 10, "The picnic tables are objects");
        assertEquals(HexAppearance.PILLARS, board.getHex(BRIDGE).getAppearance().get("bridge"), "Pillar art is the toggle");
        assertEquals(9, board.getHex(CAR_PARK).getTerrain(Terrains.ROAD).getExits(), "The one rule terrain added: the lane");
    }

    @Test
    void convertingTwiceIsConvertingOnce() throws Exception {
        Board board = fixture();
        assertEquals(1, BoardSceneryLayouts.convert(board).size(), "The cut emblem set is the one issue");
        String once = text(board);
        assertEquals(List.of(), BoardSceneryLayouts.convert(board), "A converted board decodes nothing again");
        assertEquals(once, text(board));
    }

    @Test
    void aConvertedBoardOnlyGetsItsUntypedBridgesTyped() {
        Board board = fixture();
        board.setNativeFormat(true);
        BoardSceneryLayouts.convert(board);
        assertTrue(board.getHex(PICNIC).getDecorations().isEmpty(), "A native board's tokens are converted already");
        assertEquals(HexAppearance.BUILT_BRIDGE, board.getHex(BRIDGE).getAppearance().get("bridge"),
              "The decode still sees pillar art when it types a bridge");
        assertEquals(HexAppearance.NATURAL_BRIDGE, board.getHex(PLAIN_BRIDGE).getAppearance().get("bridge"));
    }

    @Test
    void theHexesItReplacesKeepTheirFireTurn() {
        // An old save is converted at load (Game.initializeAfterLoad): a burning hex is not reported as a new fire.
        Board board = fixture();
        List<Coords> burning = List.of(new Coords(5, 0), PLAIN_BRIDGE, CAR_PARK);
        List<Hex> before = burning.stream().map(board::getHex).toList();
        for (Hex hex : before) {
            hex.addTerrain(new Terrain(Terrains.FIRE, 1));
            hex.incrementFireTurn();
            hex.incrementFireTurn();
        }
        BoardSceneryLayouts.convert(board);
        BoardSceneryLayouts.joinSheets(board);
        for (int i = 0; i < burning.size(); i++) {
            assertNotSame(before.get(i), board.getHex(burning.get(i)), burning.get(i) + " is replaced");
            assertEquals(2, board.getHex(burning.get(i)).getFireTurn(), burning.get(i).toString());
        }
    }

    @Test
    void joinedSheetsKeepOneEmblemOfASetCutBetweenARoofAndTheGround() {
        // Each sheet's half placed the full emblem on the set's centre, on its owner hex's support.
        Board board = Board.createEmptyBoard(2, 1);
        Coords ground = new Coords(0, 0), roof = new Coords(1, 0);
        put(board, roof, "building:2;bldg_elev:2;bldg_cf:50");
        String emblem = "decal/emblems/red-cross";
        board.getHex(ground).setDecorations(List.of(new BoardDecoration("a", "decal", emblem, null, 0, 0, 0, false, 1,
              BoardSceneryLayouts.receiver(board.getHex(ground), true), 0, false)));
        board.getHex(roof).setDecorations(List.of(new BoardDecoration("b", "decal", emblem, null,
              HexTile.centerX(ground, 1) - HexTile.centerX(roof, 1), HexTile.centerY(ground, 1) - HexTile.centerY(roof, 1), 0,
              false, 1, BoardSceneryLayouts.receiver(board.getHex(roof), true), 0, false)));
        BoardSceneryLayouts.joinSheets(board);
        assertEquals(List.of("a"), List.of(board.getHex(ground), board.getHex(roof)).stream()
              .flatMap(hex -> hex.getDecorations().stream()).map(BoardDecoration::id).toList());
    }

    @Test
    void theHexSetFormDecodesTheGivenHexesOfAConvertedBoard() {
        Board board = fixture();
        board.setNativeFormat(true);
        BoardSceneryLayouts.convert(board, List.of(SECOND_PICNIC));
        assertTrue(board.isNativeFormat());
        var objects = board.getHex(SECOND_PICNIC).getDecorations();
        assertTrue(objects.size() > 10);
        assertEquals("L6_2_0", objects.getFirst().id());
        assertTrue(board.getHex(SECOND_PICNIC).containsTerrain(Terrains.FLUFF), "The token stays");
        assertTrue(board.getHex(PICNIC).getDecorations().isEmpty(), "Other hexes are left alone");
        assertEquals(HexAppearance.NATURAL_BRIDGE, board.getHex(PLAIN_BRIDGE).getAppearance().get("bridge"));
    }

    /**
     * A legacy board's aliases become the values they draw as: pavement 4 (here a runway corner design with typed exits)
     * plain pavement with its exits derived again, a stray pavement level the plaza, mud 2 mud 1 keeping its exits. A
     * native board is taken as it is.
     */
    @Test
    void aLegacyBoardsPavementAndMudAliasesBecomeTheirValues() {
        Coords runway = new Coords(0, 0), apron = new Coords(1, 0), slip = new Coords(3, 0), mud = new Coords(5, 0);
        for (boolean nativeBoard : new boolean[] { false, true }) {
            Board board = Board.createEmptyBoard(7, 3);
            put(board, runway, "pavement:4:65");
            put(board, apron, "pavement:1");
            put(board, slip, "pavement:30");
            put(board, mud, "mud:2:5");
            board.setNativeFormat(nativeBoard);
            BoardSceneryLayouts.convert(board, List.of(runway, apron, slip, mud));
            Terrain pavement = board.getHex(runway).getTerrain(Terrains.PAVEMENT), muddy = board.getHex(mud).getTerrain(Terrains.MUD);
            if (nativeBoard) {
                assertEquals("pavement:4:65", pavement.toString(), "A .board2 is taken as it is");
                assertEquals(30, board.getHex(slip).terrainLevel(Terrains.PAVEMENT));
                assertEquals(2, muddy.getLevel());
                continue;
            }
            assertEquals(1, pavement.getLevel());
            assertFalse(pavement.hasExitsSpecified());
            assertEquals(1 << runway.direction(apron), pavement.getExits(), "Its exits are derived: towards the apron");
            assertEquals(3, board.getHex(slip).terrainLevel(Terrains.PAVEMENT));
            assertEquals(1, muddy.getLevel());
            assertTrue(muddy.hasExitsSpecified());
            assertEquals(5, muddy.getExits());
        }
    }

    /**
     * A hex changes exactly when one of its layers is marked taken: the predicate the 3D board will use to skip the art
     * that the conversion turned into objects.
     */
    @Test
    void theConversionChangesExactlyTheHexesWithATakenLayer() {
        Board loaded = fixture(), board = fixture();
        BoardSceneryLayouts.convert(board);
        int taken = 0;
        for (int y = 0; y < board.getHeight(); y++) {
            for (int x = 0; x < board.getWidth(); x++) {
                boolean any = TilesetScenery.resolve(loaded.getHex(x, y), false).layers().stream()
                      .anyMatch(TilesetScenery.Layer::taken);
                Hex hex = board.getHex(x, y);
                assertEquals(any, !hex.getDecorations().isEmpty() || HexAppearance.pillars(hex.getAppearance()),
                      x + "," + y);
                if (any) { taken++; }
            }
        }
        // The container yard (5,0) is no taken layer: its look, a hex design, draws it.
        assertEquals(9, taken);
    }

    /**
     * Converts {@link #fixture()} in a headless JVM and writes the board's text. There every desktop image would load as
     * the one placeholder, so an image-based resolution would pick the wrong art.
     */
    public static final class Headless {
        public static void main(String[] args) throws IOException {
            Board board = fixture();
            BoardSceneryLayouts.convert(board);
            Files.writeString(Path.of(args[0]), text(board), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aHeadlessJvmConvertsExactlyAsThisOne(@TempDir Path folder) throws Exception {
        Path written = folder.resolve("headless.board2"), output = folder.resolve("output.txt");
        String java = ProcessHandle.current().info().command().orElse(System.getProperty("java.home") + "/bin/java");
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true",
              "-cp", System.getProperty("java.class.path"), Headless.class.getName(), written.toString())
              .directory(new File(System.getProperty("user.dir"))).redirectErrorStream(true)
              .redirectOutput(output.toFile()).start();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "The headless conversion finishes");
        assertEquals(0, process.exitValue(), Files.readString(output));
        Board board = fixture();
        BoardSceneryLayouts.convert(board);
        String here = text(board);
        assertTrue(here.contains("scenery/parks/picnic-table") && here.contains("decal/car-park/"), "Art was decoded");
        assertEquals(here, Files.readString(written, StandardCharsets.UTF_8));
    }
}
