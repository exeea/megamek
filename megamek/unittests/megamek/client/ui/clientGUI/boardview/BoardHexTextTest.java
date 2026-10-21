/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;
import static megamek.client.ui.tileset.HexTileset.HEX_W;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.List;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * The hex labels (b11 scope, "Hex TYPE labels"): one method writes them, with View > Hex Types as its parameter, in the
 * official sheets' words and order, stacked down to the bottom edge; off, only today's labels in the same wording.
 */
class BoardHexTextTest {
    private static final Font FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    private static final Coords COORDS = new Coords(2, 3);
    /** The bottom edge's baseline at scale 1, where the last line stands. */
    private static final int BOTTOM = HEX_H - 6;

    @Test
    void mixedPlatformBoardsUseReadableTextForSpaceAndSky() {
        var preferences = GUIPreferences.getInstance();
        for (String terrain : List.of("space:1", "sky:1", "pavement:1")) {
            var labels = BoardHexText.capture(COORDS, new Hex(0, terrain, ""), new Board(), 1,
                  FONT, FONT, true, true, false);
            int expected = (terrain.equals("space:1") ? preferences.getBoardSpaceTextColor()
                  : preferences.getBoardTextColor()).getRGB();
            assertTrue(labels.stream().allMatch(label -> label.argb() == expected));
        }
    }

    @Test
    void theTypeLinesUseTheOfficialWordsInTheirFixedOrder() {
        // Woods and jungle (container yards are woods) write only the cover word; a canopy of height 1 is FOLIAGE.
        assertEquals(List.of("LIGHT"), types("woods:1;foliage_elev:2"));
        assertEquals(List.of("HEAVY"), types("jungle:2;foliage_elev:2"));
        assertEquals(List.of("SUPER HEAVY"), types("woods:3;foliage_elev:3"));
        assertEquals(List.of("FOLIAGE"), types("woods:1;foliage_elev:1"));
        // Structures write their type and their height after the CF chip's black triangle; a bridge's height is its
        // deck's, a bridge of height 0 included.
        assertEquals(List.of("MEDIUM▲5"), types("building:2;bldg_elev:5;bldg_cf:40"));
        assertEquals(List.of("WALL▲1"), types("building:5;bldg_elev:1;bldg_cf:120"));
        assertEquals(List.of("INDUSTRIAL▲3"), types("heavy_industrial:3"));
        assertEquals(List.of("FUEL TANK▲2"), types("fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:40"));
        // A gun emplacement keeps its construction type (the official sheets' TURRET was not taken).
        assertEquals(List.of("HARDENED▲1"), types("building:4;bldg_elev:1;bldg_cf:150;bldg_class:3"));
        // DEPTH right after the height line; a water hex reads as liquid at depth 0 too, and with a basement of any
        // type (unknown, none, two deep), which hides the depth only without types.
        assertEquals(List.of("DEPTH 2", "BRIDGE▲0"), types("bridge:1;bridge_elev:0;bridge_cf:40;water:2"));
        assertEquals(List.of("DEPTH 0"), types("water:0"));
        assertEquals(List.of("DEPTH 1", "ICE"), types("ice:1;water:1"));
        for (int basement = 0; basement <= 2; basement++) {
            String hex = "building:1;bldg_elev:2;bldg_cf:15;water:1;bldg_basement_type:" + basement;
            assertEquals(List.of("DEPTH 1", "LIGHT▲2"), types(hex), hex);
            assertEquals(List.of("HEIGHT 2"), block(0, hex, false).stream().map(Line::text).toList(), hex);
        }
        // PAVED, but not under a standing building or fuel tank; a collapsed one leaves RUBBLE on the pavement.
        assertEquals(List.of("PAVED"), types("pavement:1"));
        assertEquals(List.of("RUBBLE", "PAVED"), types("pavement:1;rubble:1"));
        assertEquals(List.of("FUEL TANK▲2"), types("fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:40;pavement:1"));
        assertEquals(List.of("LIGHT▲1", "HEAVY", "ROUGH", "RUBBLE", "SAND", "MUD"),
              types("building:1;bldg_elev:1;bldg_cf:15;woods:2;foliage_elev:2;mud:1;sand:1;pavement:1;rubble:1;"
                    + "rough:1"));
        // Clear ground and the types not approved (building classes, metal, roads, smoke) write nothing.
        assertEquals(List.of(), types(""));
        assertEquals(List.of("MEDIUM▲2"), types("building:2;bldg_elev:2;bldg_cf:40;bldg_class:2"));
        assertEquals(List.of(), types("metal_deposit:3;road:1;smoke:1"));
    }

    @Test
    void eachHazardAndSpecialTerrainWritesItsWordAndTheLevelsWithAWordOfTheirOwn() {
        String[][] words = { { "swamp:1", "SWAMP" }, { "swamp:2", "QUICKSAND" }, { "swamp:3", "QUICKSAND" },
              { "geyser:1", "GEYSER" }, { "geyser:2", "GEYSER" }, { "geyser:3", "MAGMA VENT" }, { "fire:1", "FIRE" },
              { "fire:2", "FIRE" }, { "rough:1", "ROUGH" }, { "rough:2", "ULTRA ROUGH" }, { "rubble:5", "RUBBLE" },
              { "rubble:6", "U. RUBBLE" }, { "tundra:1", "TUNDRA" }, { "planted_fields:1", "FIELDS" },
              { "snow:1", "SNOW" }, { "snow:2", "DEEP SNOW" }, { "magma:1", "MAGMA CRUST" },
              { "magma:2", "LAVA" }, { "hazardous_liquid:1", "HAZ. LIQUID" },
              { "impassable:1", "IMPASSABLE" }, { "fortified:1", "FORTIFIED" }, { "solaris_elevator:1", "ELEVATOR" },
              { "industrial_elevator:0", "ELEVATOR" }, { "space:1", "SPACE" } };
        for (String[] word : words) {
            assertEquals(List.of(word[1]), types(word[0]), word[0]);
        }
        assertEquals(List.of("DEPTH 1", "RAPIDS"), types("water:1;rapids:1"));
        assertEquals(List.of("DEPTH 2", "TORRENT"), types("water:2;rapids:2"));
    }

    @Test
    void swampFollowsTheDepthAndFireIsAlwaysTheLastLine() {
        // As the official sheets: DEPTH 1 / SWAMP, LEVEL 1 / LIGHT / FIRE.
        assertEquals(List.of("DEPTH 1", "SWAMP"), types("water:1;swamp:1"));
        assertEquals(List.of(new Line("LEVEL 1", BOTTOM - 20), new Line("LIGHT", BOTTOM - 10),
              new Line("FIRE", BOTTOM)), block(1, "woods:1;foliage_elev:2;fire:1", true));
        // Height line, DEPTH, SWAMP, structures, cover, ground, liquids, modifiers, FIRE.
        assertEquals(List.of("LEVEL 2", "DEPTH 1", "QUICKSAND", "BRIDGE▲1", "HEAVY", "ULTRA ROUGH", "DEEP SNOW",
              "TORRENT", "FORTIFIED", "FIRE"), block(2, "fire:1;fortified:1;rapids:2;snow:2;rough:2;jungle:2;"
              + "foliage_elev:2;bridge:1;bridge_elev:1;bridge_cf:40;swamp:3;water:1", true).stream().map(Line::text)
              .toList());
    }

    @Test
    void blackIceShowsOnceDetectedInTheViewsAndNeverInTheSavedImage() {
        Hex hex = new Hex(0, "black_ice:1;pavement:1", "");
        assertEquals(List.of("PAVED"), printed(hex, false), "hidden information until a unit detects it");
        assertEquals(List.of("PAVED"), printed(hex, true));
        hex.getTerrain(Terrains.BLACK_ICE).detectBlackIce();
        assertEquals(List.of("PAVED", "BLACK ICE"), printed(hex, false), "the hex tooltip's rule");
        assertEquals(List.of("PAVED"), printed(hex, true), "the saved image never prints it");
    }

    @Test
    void theLongestLinesAreMadeSmallerToStayInsideTheHex() {
        // The narrow-line rule keeps a line inside the hex's lower half at its baseline: the bottom edge here.
        for (String terrain : List.of("fuel_tank:1;fuel_tank_elev:12;fuel_tank_cf:40",
              "building:4;bldg_elev:12;bldg_cf:150", "heavy_industrial:3")) {
            BoardHexText line = BoardHexText.capture(COORDS, new Hex(0, terrain, ""), board(), 1, FONT, FONT, false,
                  true, false).getFirst();
            double width = line.font().getStringBounds(line.text(), new FontRenderContext(null, true, true))
                  .getWidth();
            assertEquals(BOTTOM, line.baseline());
            assertTrue(line.font().getSize2D() < FONT.getSize2D(), line.text());
            assertTrue(width <= HEX_W / 2.0 + (HEX_H - BOTTOM) * HEX_W / (double) HEX_H, line.text() + ": " + width);
        }
    }

    @Test
    void theBlockStacksFromTheHeightLineDownToTheBottomEdge() {
        assertEquals(List.of(new Line("LIGHT", BOTTOM)), block(0, "woods:1;foliage_elev:2", true), "the type alone");
        assertEquals(List.of(new Line("LEVEL 5", BOTTOM - 10), new Line("LIGHT", BOTTOM)),
              block(5, "woods:1;foliage_elev:2", true));
        assertEquals(List.of(new Line("LEVEL 3", BOTTOM - 10), new Line("MEDIUM▲5", BOTTOM)),
              block(3, "building:2;bldg_elev:5;bldg_cf:40", true));
        assertEquals(List.of(new Line("SUBLEVEL 2", BOTTOM - 20), new Line("DEPTH 1", BOTTOM - 10),
              new Line("ROUGH", BOTTOM)), block(-2, "water:1;rough:1", true), "the official sheets' example");
        assertEquals(List.of(new Line("LEVEL 1", BOTTOM)), block(1, "", true));
        assertEquals(List.of(new Line("ULTRA", BOTTOM - 10), new Line("SUBLEVEL", BOTTOM)),
              block(-2, "ultra_sublevel:1", true), "an ultra sublevel is its own height line");
        assertEquals(List.of(), block(0, "", true));

        // In the height line's font and colour, at the ground; a line wider than the hex is made smaller to fit.
        Hex hex = new Hex(1, "woods:3;foliage_elev:3", "");
        List<BoardHexText> labels = BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT, true, true, false);
        assertEquals("0304", labels.get(0).text(), "the coordinates stay");
        BoardHexText level = labels.get(1), cover = labels.get(2);
        assertEquals(FONT, level.font());
        assertEquals(level.argb(), cover.argb());
        assertEquals(0, cover.elevation());
        assertTrue(cover.font().getSize2D() < FONT.getSize2D(), "SUPER HEAVY narrowed: " + cover.font());
    }

    @Test
    void withoutTypesTheSameMethodWritesOnlyTodaysLabelsInTheSameWordingAndLayout() {
        Hex hex = new Hex(2, "building:1;bldg_elev:3;bldg_cf:15;water:1;woods:1;foliage_elev:1;rough:1", "");
        List<BoardHexText> bare = BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT, false, false, false);
        assertEquals(List.of(new Line("LEVEL 2", BOTTOM - 30), new Line("DEPTH 1", BOTTOM - 20),
              new Line("HEIGHT 3", BOTTOM - 10), new Line("FOLIAGE", BOTTOM)), lines(bare));
        GUIPreferences preferences = GUIPreferences.getInstance();
        assertEquals(List.of(0, 0, 3, 0), bare.stream().map(BoardHexText::elevation).toList(), "HEIGHT on the roof");
        assertEquals(preferences.getBuildingTextColor().getRGB(), bare.get(2).argb());
        assertEquals(preferences.getLowFoliageColor().getRGB(), bare.get(3).argb());
        List<BoardHexText> types = BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT, false, true, false);
        assertEquals(List.of("LEVEL 2", "DEPTH 1", "LIGHT▲3", "FOLIAGE", "ROUGH"),
              types.stream().map(BoardHexText::text).toList());
        // Its structure line lies on the roof in 3D where HEIGHT did, as the model hides the ground in angled views.
        assertEquals(List.of(0, 0, 3, 0, 0), types.stream().map(BoardHexText::elevation).toList(), "LIGHT▲3 above");
        // Of two structures only the taller one's line lies on its roof, as HEIGHT tells only the tallest.
        List<BoardHexText> two = BoardHexText.capture(COORDS, new Hex(0, "building:2;bldg_elev:2;bldg_cf:40;"
              + "heavy_industrial:3", ""), board(), 1, FONT, FONT, false, true, false);
        assertEquals(List.of("MEDIUM▲2", "INDUSTRIAL▲3"), two.stream().map(BoardHexText::text).toList());
        assertEquals(List.of(0, 3), two.stream().map(BoardHexText::elevation).toList());

        assertEquals(List.of(), block(0, "water:0;pavement:1;rough:1", false), "no DEPTH 0, no types");
        assertEquals(List.of(new Line("SUBLEVEL 2", BOTTOM)), block(-2, "", false));
        assertEquals(List.of(new Line("ULTRA", BOTTOM - 10), new Line("SUBLEVEL", BOTTOM)),
              block(0, "ultra_sublevel:1", false));
    }

    @Test
    void theTacticalViewsDepthNumberLeavesTheOtherLinesOnTheBottomEdge() {
        List<BoardHexText> board = BoardHexText.capture(COORDS, new Hex(1, "water:2", ""), board(), 1, FONT, FONT,
              true, false, false);
        List<BoardHexText> tactical = BoardHexText.tactical(board, 2, 0, 0, false);
        assertEquals(List.of("0304", "LEVEL 1", "2"), tactical.stream().map(BoardHexText::text).toList());
        assertEquals(board.get(0), tactical.get(0));
        assertEquals(BOTTOM, tactical.get(1).baseline(), "LEVEL 1 moves down where DEPTH 2 stood");
    }

    @Test
    void withTypesTheTacticalViewTellsTheDepthAndTheHeightOnlyInTheTypeBlock() {
        // Off: the depth number, and the roof chip with the height after its triangle instead of HEIGHT.
        Hex hex = new Hex(1, "building:2;bldg_elev:2;bldg_cf:40;water:1", "");
        List<BoardHexText> off = BoardHexText.tactical(BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT,
              false, false, false), 1, 40, 2, false);
        assertEquals(List.of("LEVEL 1", "1", "CF 40 ▲2"), off.stream().map(BoardHexText::text).toList());
        // On (the view passes no depth): the type block's DEPTH and MEDIUM▲2 stay, and the chip's▲2 does not repeat
        // them.
        List<BoardHexText> on = BoardHexText.tactical(BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT,
              false, true, false), 0, 40, 2, false);
        assertEquals(List.of(new Line("LEVEL 1", BOTTOM - 20), new Line("DEPTH 1", BOTTOM - 10),
              new Line("MEDIUM▲2", BOTTOM), new Line("CF 40", HEX_H / 2 + 5)), lines(on));
        assertEquals(List.of(0, 0, 0, 2), on.stream().map(BoardHexText::elevation).toList(),
              "the chip takes the roof, and the top view shows MEDIUM▲2 in the block at the ground");
        // The captured labels decide, not the live setting: a tile still holding the other setting's capture, with
        // or without the depth number, tells the height exactly once too.
        for (boolean captured : new boolean[] { false, true }) {
            for (int depth = 0; depth <= 1; depth++) {
                List<String> texts = BoardHexText.tactical(BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT,
                      false, captured, false), depth, 40, 2, false).stream().map(BoardHexText::text).toList();
                assertEquals(1, texts.stream().filter(text -> text.contains("▲2")).count(), texts.toString());
            }
        }
        // A fuel tank has no HEIGHT label without Hex Types: its chip keeps the height, as before.
        Hex tank = new Hex(0, "fuel_tank:1;fuel_tank_elev:2;fuel_tank_cf:40", "");
        for (boolean types : new boolean[] { false, true }) {
            List<BoardHexText> labels = BoardHexText.tactical(BoardHexText.capture(COORDS, tank, board(), 1, FONT,
                  FONT, false, types, false), 0, 40, 2, false);
            assertEquals(types ? List.of("FUEL TANK▲2", "CF 40") : List.of("CF 40 ▲2"),
                  labels.stream().map(BoardHexText::text).toList());
        }
    }

    @Test
    void aRoofLabelLeavesTheGroundLinesOnTheBottomEdgeOfThe3dBoard() {
        // Without Hex Types a structure's HEIGHT is the last line, but the 3D board lays it on the roof: the ground
        // lines keep the bottom edge, and HEIGHT takes the slot above them, where it shows without a roof.
        List<BoardHexText> building = BoardHexText.capture(COORDS, new Hex(1, "building:1;bldg_elev:2;bldg_cf:15", ""),
              board(), 1, FONT, FONT, true, false, false);
        List<BoardHexText> settled = BoardHexText.settled(building, building);
        assertEquals(List.of(new Line("0304", 6), new Line("LEVEL 1", BOTTOM), new Line("HEIGHT 2", BOTTOM - 10)),
              lines(settled));
        assertEquals(2, settled.get(2).elevation());
        assertEquals(settled, BoardHexText.settled(settled, settled), "settling again changes nothing");
        // With Hex Types the structure line takes HEIGHT's place on the roof; the rest of the block keeps the ground.
        List<BoardHexText> typed = BoardHexText.capture(COORDS, new Hex(1, "building:1;bldg_elev:2;bldg_cf:15;"
              + "rough:1", ""), board(), 1, FONT, FONT, false, true, false);
        assertEquals(List.of(new Line("LEVEL 1", BOTTOM - 10), new Line("LIGHT▲2", BOTTOM - 20),
              new Line("ROUGH", BOTTOM)), lines(BoardHexText.settled(typed, typed)));
        // The Tactical View keeps a heavy-industrial hex's HEIGHT (it has no CF chip) by the same rule.
        List<BoardHexText> industrial = BoardHexText.capture(COORDS, new Hex(1, "heavy_industrial:2", ""),
              board(), 1, FONT, FONT, false, false, false);
        assertEquals(List.of(new Line("LEVEL 1", BOTTOM), new Line("HEIGHT 2", BOTTOM - 10)),
              lines(BoardHexText.tactical(industrial, 0, 0, 2, false)));
    }

    @Test
    void everyLabelIsDrawnOnAnOutlineThatContrastsWithIt() {
        // Dark text on white, light text (a space board's) on black, and the saved image's black on white.
        record Case(int argb, boolean printed, int text, int outline) { }
        withOutline(true, () -> {
            for (Case drawn : List.of(new Case(0xFF000000, false, 0x000000, 0xFFFFFF),
                  new Case(0xFFC8C8C8, false, 0xC8C8C8, 0x000000), new Case(0xFFFFD700, true, 0x000000, 0xFFFFFF))) {
                int[] pixels = pixels(drawn.argb(), drawn.printed(), drawn.text(), drawn.outline());
                assertTrue(pixels[0] > 15 && pixels[1] > 40,
                      drawn + ": " + pixels[0] + " text and " + pixels[1] + " outline pixels");
            }
        });
    }

    @Test
    void withTheOutlineSettingOffTheViewsDrawPlainTextButTheSavedImageKeepsItsOutline() {
        withOutline(false, () -> {
            int[] view = pixels(0xFF000000, false, 0x000000, 0xFFFFFF);
            assertTrue(view[0] > 15 && view[1] == 0, view[0] + " text and " + view[1] + " outline pixels in a view");
            int[] printed = pixels(0xFFFFD700, true, 0x000000, 0xFFFFFF);
            assertTrue(printed[0] > 15 && printed[1] > 40,
                  printed[0] + " text and " + printed[1] + " outline pixels in the saved image");
        });
    }

    /** Runs {@code check} with Client Settings > Hex label outline set to {@code outline}, then restores it. */
    private static void withOutline(boolean outline, Runnable check) {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean before = preferences.getHexLabelOutline();
        preferences.setHexLabelOutline(outline);
        try {
            check.run();
        } finally {
            preferences.setHexLabelOutline(before);
        }
    }

    /**
     * The opaque pixels of {@code text} and of {@code outline} colour in a label 3 of colour {@code argb}, its font large
     * enough for the outline (a small share of its size) to reach past a whole pixel.
     */
    private static int[] pixels(int argb, boolean printed, int text, int outline) {
        BufferedImage image = new BufferedImage(HEX_W, HEX_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        BoardHexText.draw(graphics, new Point(), HEX_W, List.of(new BoardHexText("3", BOTTOM,
              FONT.deriveFont(30f), argb, false, 0)), printed);
        graphics.dispose();
        int[] counts = new int[2];
        for (int y = 0; y < HEX_H; y++) {
            for (int x = 0; x < HEX_W; x++) {
                int pixel = image.getRGB(x, y);
                if (pixel >>> 24 > 230 && near(pixel, text)) { counts[0]++; }
                if (pixel >>> 24 > 230 && near(pixel, outline)) { counts[1]++; }
            }
        }
        return counts;
    }

    private record Line(String text, int baseline) { }

    private static boolean near(int pixel, int rgb) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((pixel >> shift & 255) - (rgb >> shift & 255)) > 30) { return false; }
        }
        return true;
    }

    private static List<String> types(String terrain) {
        return block(0, terrain, true).stream().map(Line::text).toList();
    }

    private static List<Line> block(int level, String terrain, boolean types) {
        return lines(BoardHexText.capture(COORDS, new Hex(level, terrain, ""), board(), 1, FONT, FONT, false, types,
              false));
    }

    /** The type block of {@code hex} in a view, or {@code printed} in the saved image. */
    private static List<String> printed(Hex hex, boolean printed) {
        return BoardHexText.capture(COORDS, hex, board(), 1, FONT, FONT, false, true, printed).stream()
              .map(BoardHexText::text).toList();
    }

    private static List<Line> lines(List<BoardHexText> labels) {
        return labels.stream().map(label -> new Line(label.text(), label.baseline())).toList();
    }

    private static Board board() {
        return Board.createEmptyBoard(4, 4);
    }
}
