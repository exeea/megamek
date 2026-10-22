/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/**
 * Batch 10 and b13 ways: legacy rail art ({@code fluff:10:N}: hq_fluff rail00-63 and the level crossings rail_road01-06)
 * converts into rail terrain with the art's sides, on the ground ({@code rail}) or a bridge's deck ({@code bridge_rail}),
 * which yields the rail's route marker and draws its sprite in 2D, so the token goes; art without sides joins the rails
 * beside it.
 */
class BoardLegacyRailTest {
    private static final Coords AT = new Coords(2, 2);
    /** The rail axis of each level crossing, rail_road01-06 (fluff:10:64-69). */
    private static final int[] CROSSINGS = { 9, 9, 18, 36, 36, 18 };

    private static Board board(Map<Coords, String> terrains) {
        Board board = Board.createEmptyBoard(5, 5);
        terrains.forEach((at, terrain) -> board.setHex(at, new Hex(0, terrain, "")));
        return board;
    }

    private static Board written(Board board) throws Exception {
        var text = new ByteArrayOutputStream();
        BoardFile.write(board, text);
        return BoardFile.readNative(text.toString(StandardCharsets.UTF_8));
    }

    private static List<BoardDecoration> rails(Hex hex) { return RailRoute.markers(hex, RailRoute.Kind.RAIL); }

    @Test
    void roadFollowingHairpinStubsAreDroppedOnlyByLegacyConversion() {
        for (int sides : new int[] { 3, 7 }) {
            Board board = board(Map.of(AT, "road:1:" + sides + ";fluff:10:" + sides));
            BoardSceneryLayouts.convert(board);
            int kept = sides & ~RailRoute.stranded(sides);
            if (kept == 0) { assertFalse(board.getHex(AT).containsTerrain(Terrains.RAIL)); }
            else { assertEquals(kept, board.getHex(AT).getTerrain(Terrains.RAIL).getExits()); }
            assertTrue(board.getHex(AT).containsTerrain(Terrains.ROAD), "The road still follows its full hairpin");
        }
        Board offRoad = board(Map.of(AT, "road:1:9;fluff:10:3"));
        BoardSceneryLayouts.convert(offRoad);
        assertEquals(3, offRoad.getHex(AT).getTerrain(Terrains.RAIL).getExits(), "Unrelated rail stubs stay");
        Board deck = board(Map.of(AT, "water:1;bridge:1:3;bridge_elev:1;bridge_cf:40;road:1:3;fluff:10:3"));
        BoardSceneryLayouts.convert(deck);
        assertEquals(3, deck.getHex(AT).getTerrain(Terrains.BRIDGE_RAIL).getExits(), "Deck rails are not dropped");
    }

    @Test
    void everyLegacyRailSpriteBecomesRailTerrainWithItsSidesAndLeavesNoToken() throws Exception {
        for (int value = 1; value < 70; value++) {
            String terrain = "fluff:10:" + value;
            Board board = board(Map.of(AT, terrain));
            BoardSceneryLayouts.convert(board);
            Hex game = board.getHex(AT);
            assertFalse(game.containsTerrain(Terrains.FLUFF), terrain + ": the rail terrain draws the sprite in 2D");
            var rail = game.getTerrain(Terrains.RAIL);
            assertEquals(1, rail.getLevel(), terrain);
            assertEquals(value < 64 ? value : CROSSINGS[value - 64], rail.getExits(), terrain);
            assertTrue(game.getDecorations().stream().noneMatch(RailRoute::isRoute), terrain + ": no stored rail marker");
            var markers = rails(game);
            // The marker the terrain yields is, but for its id, the one the conversion stored before rails were terrain.
            assertEquals(List.of(stored(value, BoardDecoration.Placement.ground(), RailRoute.GROUND_RAIL)), markers, terrain);
            var marker = markers.getFirst();
            assertEquals(value < 64 ? value : CROSSINGS[value - 64], marker.connections(), terrain);
            assertEquals(0, marker.tracks(), terrain + ": the default layout reproduces the sprite's gentle pieces");
            assertEquals(null, marker.group(), "A route marker is never a group member");
            Hex saved = written(board).getHex(AT);
            assertEquals(String.valueOf(rail), String.valueOf(saved.getTerrain(Terrains.RAIL)), terrain);
            assertEquals(game.getDecorations(), saved.getDecorations(), terrain);
        }
        Board bridge = board(Map.of(AT, "water:1;bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:9"));
        BoardSceneryLayouts.convert(bridge);
        assertEquals(List.of(stored(9, BoardDecoration.Placement.on("bridge", 0), RailRoute.DECK_RAIL)),
              rails(bridge.getHex(AT)), "A deck rail: the stored marker on its deck");
    }

    /** The route marker the conversion stored for rail art {@code fluff:10:value}: its layout row's, at {@code placement}. */
    private static BoardDecoration stored(int value, BoardDecoration.Placement placement, String id) {
        String art = value < 64 ? "scenery/hq_fluff/rail%02d".formatted(value) : "scenery/hq_fluff/rail_road%02d".formatted(value - 63);
        var row = BoardSceneryLayouts.layout(art).decorations(AT, new Hex(0), () -> id, null, false).stream()
              .filter(RailRoute::isRoute).findFirst().orElseThrow();
        return row.transform(row.x(), row.y(), row.rotation(), row.mirror(), row.scale(), placement);
    }

    @Test
    void aLevelCrossingKeepsItsRoadAndPlacesFourSignalsBesideIt() throws Exception {
        Board board = board(Map.of(AT, "road:1:18;fluff:10:66"));
        BoardSceneryLayouts.convert(board);
        Hex hex = board.getHex(AT);
        assertTrue(hex.containsTerrain(Terrains.ROAD), "The road stays rule terrain");
        assertEquals(18, rails(hex).getFirst().connections());
        var pieces = hex.getDecorations().stream().filter(object -> !RailRoute.isRoute(object)).toList();
        assertEquals(0, pieces.stream().filter(object -> object.asset().equals("decal/rail/level-crossing")).count(),
              "The live road and embedded rails replace the old concrete panel");
        assertEquals(4, pieces.stream().filter(object -> object.asset().equals("scenery/rail/crossing-signal")).count());
        assertEquals(1, pieces.stream().map(BoardDecoration::group).distinct().count(), "One group, without the marker");
        assertTrue(pieces.stream().allMatch(object -> object.placement().equals(BoardDecoration.Placement.ground())),
              "The signals stand on the ground, not on the rail top");
    }

    @Test
    void railArtWithoutSidesJoinsTheRailsBesideItOrPlacesNothing() throws Exception {
        // A column of two rail00 hexes ending in a N-S line, which leaves back towards them, and an isolated rail00.
        Coords north = AT.translated(0), south = AT.translated(3);
        Board board = board(Map.of(north, "fluff:10:0", AT, "fluff:10:0", south, "fluff:10:9", new Coords(0, 4), "fluff:10:0"));
        BoardSceneryLayouts.convert(board);
        assertEquals(RailRoute.side(3), rails(board.getHex(north)).getFirst().connections(), "The end of the column");
        assertEquals(RailRoute.side(0) | RailRoute.side(3), rails(board.getHex(AT)).getFirst().connections());
        assertTrue(rails(board.getHex(new Coords(0, 4))).isEmpty(), "An isolated sideless rail places nothing");
        var lone = board.getHex(new Coords(0, 4));
        assertEquals(Map.of(Terrains.FLUFF, BoardSceneryLayouts.Taken.OBJECTS), BoardSceneryLayouts.takenTokens(lone),
              "Its token is taken all the same");
        assertFalse(written(board).getHex(0, 4).containsTerrain(Terrains.FLUFF));
    }

    @Test
    void aRailRidesTheDeckWhereItLeavesTheSpanAlongItAndRunsBeneathAnOverpass() throws Exception {
        // A bridge over water: the rail rides its deck.
        Board water = board(Map.of(AT, "water:1;bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:9"));
        BoardSceneryLayouts.convert(water);
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(water.getHex(AT)).getFirst().placement());
        // A one-hex bridge one level up whose N-S deck meets ground at its own level on both ends: the rail rides it.
        Coords north = AT.translated(0), south = AT.translated(3);
        Board level = board(Map.of(AT, "bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:9"));
        level.getHex(north).setLevel(1);
        level.getHex(south).setLevel(1);
        BoardSceneryLayouts.convert(level);
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(level.getHex(AT)).getFirst().placement());
        // The same deck with sideless rail art (rail00) joined to the rails on both banks rides it too.
        Board sideless = board(Map.of(AT, "bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:0", north, "fluff:10:9",
              south, "fluff:10:9"));
        sideless.getHex(north).setLevel(1);
        sideless.getHex(south).setLevel(1);
        BoardSceneryLayouts.convert(sideless);
        assertEquals(9, rails(sideless.getHex(AT)).getFirst().connections());
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(sideless.getHex(AT)).getFirst().placement());
        // A two-hex span that meets the bank at deck level only past its rail00 hex: both hexes' rails ride the deck.
        Board span = board(Map.of(AT, "bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:9",
              north, "bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:0", north.translated(0), "fluff:10:9"));
        span.getHex(north.translated(0)).setLevel(1);
        span.getHex(south).setLevel(-2);
        BoardSceneryLayouts.convert(span);
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(span.getHex(AT)).getFirst().placement());
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(span.getHex(north)).getFirst().placement());
        // An overpass: a rail N-S under a NE-SW road bridge two levels up runs on the ground beneath it.
        Board under = board(Map.of(AT, "bridge:1:18;bridge_elev:2;bridge_cf:40;fluff:10:9"));
        BoardSceneryLayouts.convert(under);
        var rail = rails(under.getHex(AT)).getFirst();
        assertEquals(BoardDecoration.Placement.ground(), rail.placement());
        assertEquals(9, RailRoute.groundSides(under.getHex(AT)), "It shapes the ground beneath the deck");
        // A rail crossing under a bridge over water (45x45 Silverrock Hatchet Bridge 2431): no side of it runs along the
        // deck, so it lies on the bed as its submerged neighbours do and draws all its sides, with no stub or stop.
        Board across = board(Map.of(AT, "water:1;bridge:1:36;bridge_elev:1;bridge_cf:40;fluff:10:18",
              AT.translated(1), "water:1;fluff:10:18", AT.translated(4), "water:1;fluff:10:18"));
        BoardSceneryLayouts.convert(across);
        rail = rails(across.getHex(AT)).getFirst();
        assertEquals(BoardDecoration.Placement.ground(), rail.placement());
        assertEquals(18, RailRoute.drawnSides(rail, across.getHex(AT)));
        assertTrue(RailRoute.stops(RailRoute.drawnSides(rail, across.getHex(AT))).isEmpty());
        // Under a junction deck whose other hexes carry a rail along it onto the bank (Grass Mangrove Coast 6104): the
        // rail along the deck rides it, the one across it runs beneath.
        Board junction = board(Map.of(AT, "bridge:1:27;bridge_elev:1;bridge_cf:40;fluff:10:36",
              north, "bridge:1:9;bridge_elev:1;bridge_cf:40;fluff:10:9", north.translated(0), "fluff:10:9"));
        BoardSceneryLayouts.convert(junction);
        assertEquals(BoardDecoration.Placement.on("bridge", 0), rails(junction.getHex(north)).getFirst().placement());
        assertEquals(BoardDecoration.Placement.ground(), rails(junction.getHex(AT)).getFirst().placement());
    }

    /**
     * Batch-10 answer 3: a rail that enters a bridge span along its deck at both ends crosses the whole span on the deck,
     * also where the legacy art left the middle hexes without rail (45x45 Desert Mesa Oasis 3333-3533); with rail art at
     * one end only, the span keeps what its art shows; a native board is never fixed up.
     */
    @Test
    void aRailBridgeCarriesItsRailAcrossTheWholeSpan() throws Exception {
        java.util.function.BiFunction<String, Boolean, Board> span = (south, nativeBoard) -> {
            Board board = Board.createEmptyBoard(5, 5);
            String middle = "water:1;bridge:1:9;bridge_cf:40;bridge_elev:1";
            board.setHex(new Coords(2, 0), new Hex(0, "fluff:10:9", ""));
            board.setHex(new Coords(2, 1), new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:0;fluff:10:9", ""));
            board.setHex(new Coords(2, 2), new Hex(-1, middle, ""));
            board.setHex(new Coords(2, 3), new Hex(-1, middle, ""));
            // The south end runs off the board.
            board.setHex(new Coords(2, 4), new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:0" + south, ""));
            board.setNativeFormat(nativeBoard);
            return board;
        };
        Board board = span.apply(";fluff:10:9", false);
        BoardSceneryLayouts.convert(board);
        for (int y = 1; y <= 4; y++) {
            var rail = rails(board.getHex(2, y));
            assertEquals(1, rail.size(), "row " + y);
            assertEquals(9, board.getHex(2, y).getTerrain(Terrains.BRIDGE_RAIL).getExits(), "row " + y + ": a deck rail");
            assertEquals(9, rail.getFirst().connections(), "row " + y);
            assertEquals(BoardDecoration.Placement.on("bridge", 0), rail.getFirst().placement(), "row " + y);
            assertNotNull(RailRoute.joined(rail.getFirst(), 0, board.getHex(2, y - 1)), "row " + y + " joins the north");
        }
        assertEquals(Boolean.TRUE, HexAppearance.bridgeBuilt(board.getHex(2, 2).getAppearance()), "A built rail bridge");
        assertEquals(board.getHex(2, 2).getDecorations(), written(board).getHex(2, 2).getDecorations());

        Board oneEnd = span.apply("", false);
        BoardSceneryLayouts.convert(oneEnd);
        assertTrue(rails(oneEnd.getHex(2, 2)).isEmpty() && rails(oneEnd.getHex(2, 4)).isEmpty(), "Rail at one end only");

        // A native board holding the same tokens (an editor's typed values) is decoded but never fixed up.
        Board nativeBoard = span.apply(";fluff:10:9", true);
        BoardSceneryLayouts.convert(nativeBoard, List.of(new Coords(2, 1), new Coords(2, 4)));
        assertEquals(1, rails(nativeBoard.getHex(2, 1)).size());
        assertTrue(rails(nativeBoard.getHex(2, 2)).isEmpty(), "No legacy fix-up of a native board");
    }

    @Test
    void aStampedTrainRidesTheRailTopOfTheRouteOnItsHex() {
        Hex hex = new Hex(0);
        var rail = new BoardDecoration("r", "prop", RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.on("bridge", 0), 0, false).withConnections(9);
        hex.addTerrain(new megamek.common.units.Terrain(Terrains.BRIDGE, 1, true, 9));
        RailRoute.lay(hex, true, 9, 0);
        var train = BoardSceneryLayouts.stamp("scenery/rail/train", hex, AT);
        assertFalse(train.isEmpty());
        for (var vehicle : train) {
            assertEquals(RailRoute.ride(rail), vehicle.placement(), vehicle.asset() + " rides the rail top on the deck");
        }
        var plain = BoardSceneryLayouts.stamp("scenery/rail/train", new Hex(0), AT);
        assertTrue(plain.stream().allMatch(vehicle -> vehicle.placement().equals(BoardDecoration.Placement.ground())),
              "Without a route the train stands as any object");
        assertNotNull(BoardSceneryLayouts.layout("scenery/rail/express-train"));
    }
}
