/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import org.junit.jupiter.api.Test;

/** The one route model of rails and maglev: kinds, joins, levels, disjoint sides and the gentle track pieces. */
class RailRouteTest {
    /**
     * The default layout of every side mask, from the rail-pairs study (pairs.json "built"): it reproduces the legacy
     * sprites' gentle pieces. Bit d (0-2) is the straight from side d, bit 3 + k the arc from side k to side k + 2.
     */
    private static final int[] STUDY_DEFAULTS = { 0, 0, 0, 0, 0, 8, 0, 8, 0, 1, 16, 17, 0, 9, 16, 25, 0, 128, 2, 130, 32,
          136, 34, 162, 0, 129, 18, 3, 32, 137, 50, 43, 0, 0, 256, 256, 4, 12, 260, 268, 64, 65, 80, 81, 68, 5, 324, 277, 0,
          128, 258, 386, 36, 44, 6, 142, 64, 193, 274, 323, 100, 165, 86, 7 };
    /** The study's stranded sides of every mask (two adjacent sides, or the middle of three). */
    private static final int[] STUDY_STRANDED = { 0, 0, 0, 3, 0, 0, 6, 2, 0, 0, 0, 0, 12, 0, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          24, 0, 0, 0, 8, 0, 0, 0, 0, 33, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 48, 32, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0,
          0, 0, 0 };
    /** The study's 18 masks with more than one sensible layout, default first (the junction card's choices). */
    private static final Map<Integer, List<Integer>> STUDY_VARIANTS = Map.ofEntries(Map.entry(21, List.of(136, 40, 160, 168)),
          Map.entry(23, List.of(162, 10, 170)), Map.entry(27, List.of(3, 147)), Map.entry(29, List.of(137, 33, 169)),
          Map.entry(31, List.of(43, 11, 35, 187)), Map.entry(42, List.of(80, 272, 320, 336)),
          Map.entry(43, List.of(81, 257, 337)), Map.entry(45, List.of(5, 77)), Map.entry(46, List.of(324, 20, 340)),
          Map.entry(47, List.of(277, 21, 261, 349)), Map.entry(53, List.of(44, 132, 172)), Map.entry(54, List.of(6, 294)),
          Map.entry(55, List.of(142, 134, 14, 430)), Map.entry(58, List.of(274, 66, 338)),
          Map.entry(59, List.of(323, 67, 259, 467)), Map.entry(61, List.of(165, 133, 37, 237)),
          Map.entry(62, List.of(86, 22, 70, 374)), Map.entry(63, List.of(7, 511)));

    private static BoardDecoration marker(RailRoute.Kind kind, String id, int sides, BoardDecoration.Placement placement) {
        return new BoardDecoration(id, "prop", kind.asset, null, 0, 0, 0, false, 1, placement, 0, false).withConnections(sides);
    }

    @Test
    void everyMaskLaysOnlyGentlePiecesTheStudysDefaultAndItsStubs() {
        for (int mask = 0; mask < 64; mask++) {
            int pieces = RailRoute.pieces(mask), covered = 0;
            assertEquals(STUDY_DEFAULTS[mask], pieces, "default layout of mask " + mask);
            assertEquals(STUDY_STRANDED[mask], RailRoute.stranded(mask), "stranded sides of mask " + mask);
            for (int piece = 0; piece < RailRoute.PIECES; piece++) {
                if ((pieces & 1 << piece) == 0) { continue; }
                int sides = RailRoute.pieceSides(piece);
                assertEquals(0, sides & ~mask, "a piece joins two of the mask's sides");
                int a = Integer.numberOfTrailingZeros(sides), b = 31 - Integer.numberOfLeadingZeros(sides);
                assertTrue(b - a >= 2 && b - a <= 4, "never a 120-degree piece between adjacent sides: " + mask);
                assertEquals(piece, RailRoute.piece(a, b));
                covered |= sides;
            }
            // Every side is reached: by a piece, or as a dead end, a stranded stub or the lone stub of no sides.
            int ends = Integer.bitCount(mask) == 1 ? mask : RailRoute.stranded(mask);
            assertEquals(mask, covered | ends, "mask " + mask);
            assertEquals(0, covered & RailRoute.stranded(mask), "a stranded side has no piece");
            assertTrue(RailRoute.valid(mask, pieces), "the default is a layout of its mask");
        }
        assertEquals(-1, RailRoute.piece(0, 1), "adjacent sides are no piece");
        assertEquals(-1, RailRoute.piece(5, 0));
    }

    @Test
    void eighteenMasksOfferTheStudysLayoutsAndEveryOtherMaskOne() {
        for (int mask = 0; mask < 64; mask++) {
            var variants = RailRoute.variants(mask);
            assertEquals(STUDY_VARIANTS.getOrDefault(mask, List.of(RailRoute.pieces(mask))), variants, "mask " + mask);
            for (int layout : variants) { assertTrue(RailRoute.valid(mask, layout), mask + ": " + layout); }
        }
    }

    /** Batch-10 answer 5: the junction card names each layout (side 0 is N, then clockwise), never "Layout n". */
    @Test
    void everyJunctionLayoutHasADescriptiveName() {
        java.util.function.IntFunction<List<String>> names = mask -> RailRoute.junctions(mask).stream()
              .map(layout -> layout.name() + (layout.side() < 0 ? "" : " " + layout.side())).toList();
        assertEquals(List.of("yFrom 0", "yFrom 2", "yFrom 4", "wye"), names.apply(21));
        assertEquals(List.of("yFrom 3", "yFrom 1", "yFrom 5", "wye"), names.apply(42));
        assertEquals(List.of("crossing", "doubleSlip"), names.apply(27));
        assertEquals(List.of("crossing", "allCurves"), names.apply(63));
        assertEquals(List.of("bothTurnouts", "turnoutTo 0", "turnoutTo 4", "allCurves"), names.apply(31));
        assertEquals(List.of("turnoutsTo 0", "crossingCurve", "allCurves"), names.apply(29));
        assertEquals(List.of("default"), names.apply(9));
        for (int mask = 0; mask < 64; mask++) {
            var layouts = RailRoute.junctions(mask);
            assertEquals(RailRoute.variants(mask), layouts.stream().map(RailRoute.Junction::tracks).toList());
            List<String> named = names.apply(mask);
            assertEquals(named.size(), new java.util.HashSet<>(named).size(), "distinct names: " + mask);
            assertTrue(layouts.size() == 1 || !named.contains("default"), "a choice names every layout: " + mask);
        }
    }

    @Test
    void storedTracksAreOnlyDrivableLayoutsAndAnEditThatBreaksThemDropsThem() {
        // N-S through line with the SE-SW curve crossing it (mask 29), as a drawing laid it.
        int crossing = 1 | 1 << RailRoute.piece(2, 4);
        assertTrue(RailRoute.valid(29, crossing));
        assertFalse(RailRoute.valid(29, 1), "the SE and SW sides would be left unreached");
        assertFalse(RailRoute.valid(9, 1 << RailRoute.piece(0, 2)), "a piece to a side the marker lacks");
        assertFalse(RailRoute.valid(29, 1 << RailRoute.PIECES), "only nine pieces exist");
        assertTrue(RailRoute.valid(3, 0) && !RailRoute.valid(3, 1), "two adjacent sides have no piece, only stubs");

        var route = marker(RailRoute.Kind.RAIL, "r", 29, BoardDecoration.Placement.ground());
        var drawn = route.withTracks(crossing);
        assertEquals(crossing, drawn.tracks());
        assertEquals(crossing, RailRoute.pieces(drawn, 29));
        assertEquals(0, route.withTracks(RailRoute.pieces(29)).tracks(), "the default layout is stored as none");
        assertEquals(crossing, drawn.withConnections(29).tracks(), "the same sides keep it");
        assertEquals(0, drawn.withConnections(29 | 32).tracks(), "a new side it does not reach drops the layout");
        assertEquals(0, drawn.withConnections(9).tracks(), "losing a piece's side drops the layout");
        assertThrows(IllegalArgumentException.class, () -> route.withTracks(1 << RailRoute.piece(1, 3)));
        assertThrows(IllegalArgumentException.class, () -> new BoardDecoration("p", "prop", "scenery/parks/bench", null, 0, 0,
              0, false, 1, BoardDecoration.Placement.ground(), 0, false, 0, 0, null, false, 0,
              BoardDecoration.Stretch.NONE, BoardDecoration.Colours.NONE, 1), "only a route marker has tracks");
        // On a deck only the bridge's exits are drawn; a layout that no longer fits them falls back to the default.
        assertEquals(RailRoute.pieces(9), RailRoute.pieces(drawn, 9));
    }

    @Test
    void flippingReflectsTheTracksWithTheSides() {
        int crossing = 1 | 1 << RailRoute.piece(2, 4);
        var drawn = marker(RailRoute.Kind.RAIL, "r", 29, BoardDecoration.Placement.ground()).withTracks(crossing);
        for (boolean horizontal : new boolean[] { false, true }) {
            for (boolean vertical : new boolean[] { false, true }) {
                var flipped = drawn.flip(horizontal, vertical);
                assertTrue(RailRoute.valid(flipped.connections(), flipped.tracks()), horizontal + " " + vertical);
                assertEquals(Integer.bitCount(crossing), Integer.bitCount(flipped.tracks()));
                var twice = flipped.flip(horizontal, vertical);
                assertEquals(drawn.connections(), twice.connections(), "flipping twice restores the sides");
                assertEquals(drawn.tracks(), twice.tracks(), "and the pieces");
            }
        }
        // Mirrored left to right, the SE-SW curve becomes the SW-SE curve: the same piece; N-S stays N-S.
        assertEquals(crossing, drawn.flip(true, false).tracks());
        // Upside down the N-S line stays, the curve moves to NE-NW.
        assertEquals(1 | 1 << RailRoute.piece(5, 1), drawn.flip(false, true).tracks());
    }

    @Test
    void bothKindsHaveConnectionsAndARailLiesOnTheGroundOrADeck() {
        var maglev = marker(RailRoute.Kind.MAGLEV, "m", 9, BoardDecoration.Placement.absolute(3));
        var rail = marker(RailRoute.Kind.RAIL, "r", 9, BoardDecoration.Placement.ground());
        var deck = marker(RailRoute.Kind.RAIL, "d", 9, BoardDecoration.Placement.on("bridge", 0));
        assertSame(RailRoute.Kind.MAGLEV, RailRoute.kind(maglev));
        assertSame(RailRoute.Kind.RAIL, RailRoute.kind(rail));
        assertNull(RailRoute.kind(new BoardDecoration("d", "decal", RailRoute.Kind.RAIL.asset, null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0)), "a decal is no marker");
        assertTrue(RailRoute.onDeck(deck) && !RailRoute.onDeck(rail));
        assertThrows(IllegalArgumentException.class, () -> marker(RailRoute.Kind.RAIL, "x", 9, BoardDecoration.Placement.absolute(2)),
              "a rail follows its support; only a maglev holds a level");
        assertThrows(IllegalArgumentException.class, () -> marker(RailRoute.Kind.RAIL, "x", 9,
              BoardDecoration.Placement.surface("ground", "top", 1)));
        assertThrows(IllegalArgumentException.class, () -> marker(RailRoute.Kind.RAIL, "x", 9, BoardDecoration.Placement.on("building", 0)));
    }

    @Test
    void joinsNeedTheSameKindAndBothSidesAndCrossingMaglevsOwnDisjointSides() {
        var north = marker(RailRoute.Kind.RAIL, "n", 8, BoardDecoration.Placement.ground());
        var here = marker(RailRoute.Kind.RAIL, "h", 9, BoardDecoration.Placement.ground());
        assertTrue(RailRoute.joined(here, 0, north));
        assertFalse(RailRoute.joined(here, 3, north), "a side the neighbour does not return");
        var maglevNorth = marker(RailRoute.Kind.MAGLEV, "mn", 8, BoardDecoration.Placement.absolute(2));
        assertFalse(RailRoute.joined(here, 0, maglevNorth), "rails and maglev never join");

        // A hex with a maglev N-S at level 2 and one NE-SW at level 4, crossing over it: each neighbour joins the marker
        // that owns its side.
        var low = marker(RailRoute.Kind.MAGLEV, "low", 9, BoardDecoration.Placement.absolute(2));
        var high = marker(RailRoute.Kind.MAGLEV, "high", 18, BoardDecoration.Placement.absolute(4));
        var crossing = new Hex(0);
        crossing.setDecorations(List.of(low, high));
        var southWest = marker(RailRoute.Kind.MAGLEV, "sw", 2, BoardDecoration.Placement.absolute(4));
        var south = marker(RailRoute.Kind.MAGLEV, "s", 1, BoardDecoration.Placement.absolute(2));
        assertEquals(List.of(low, high), RailRoute.markers(crossing, RailRoute.Kind.MAGLEV));
        assertSame(high, RailRoute.joined(southWest, 1, crossing), "the SW neighbour joins the upper route");
        assertSame(low, RailRoute.joined(south, 0, crossing), "the S neighbour joins the lower route");
        assertNull(RailRoute.joined(here, 0, crossing), "no rail there");
    }

    @Test
    void onlyGroundRailsShapeTheGroundAndADeckDrawsItsBridgesExits() {
        var hex = new Hex(0, "bridge:1:18;bridge_elev:1;bridge_cf:40", "");
        var ground = marker(RailRoute.Kind.RAIL, "g", 9, BoardDecoration.Placement.ground());
        var deck = marker(RailRoute.Kind.RAIL, "d", 18 | 1, BoardDecoration.Placement.on("bridge", 0));
        hex.setDecorations(List.of(ground, deck, marker(RailRoute.Kind.MAGLEV, "m", 36, BoardDecoration.Placement.absolute(3))));
        assertEquals(9, RailRoute.groundSides(hex), "a rail beneath the deck shapes the ground; the deck's and maglev's do not");
        assertEquals(18, RailRoute.drawnSides(deck, 18), "a deck carries track only along itself");
        assertEquals(9, RailRoute.drawnSides(ground, 18));
    }

    @Test
    void buffersCloseDeadEndsStubsAndTheLoneStubFacingTheirTrack() {
        assertEquals(List.of(), RailRoute.stops(9), "a through line has none");
        assertEquals(List.of(), RailRoute.stops(21), "a Y has none");
        var deadEnd = RailRoute.stops(1);
        assertEquals(1, deadEnd.size());
        var stop = deadEnd.getFirst();
        assertEquals(0, stop.x(), 1e-4);
        assertEquals(RailRoute.STOP_DEPTH, stop.y(), 1e-4, "it stands on the track, before its end at the centre");
        assertEquals(0, stop.rotation(), 1e-4, "its buffers face north, the way the track comes");
        assertEquals(2, RailRoute.stops(3).size(), "two adjacent sides: two stubs");
        var middle = RailRoute.stops(7);
        assertEquals(1, middle.size(), "only the middle of three adjacent sides is stranded");
        var edge = RailRoute.border(1);
        float length = (float) Math.hypot(edge[0], edge[1]);
        assertEquals(length - RailRoute.STUB_SHORT + RailRoute.STOP_DEPTH,
              (float) Math.hypot(middle.getFirst().x(), middle.getFirst().y()), 1e-3);
        assertEquals(2, RailRoute.stops(0).size(), "the lone stub of a marker without sides");
    }

    @Test
    void railVehiclesRideTheRailTopMaglevPiecesTheRouteAndFixedPiecesTheirSupport() {
        assertSame(RailRoute.Kind.RAIL, RailRoute.rides("scenery/rail/locomotive"));
        assertSame(RailRoute.Kind.MAGLEV, RailRoute.rides("scenery/maglev/wagon"));
        assertNull(RailRoute.rides("scenery/rail/crossing-signal"));
        assertNull(RailRoute.rides(RailRoute.BUFFER_STOP));
        assertNull(RailRoute.rides(RailRoute.Kind.RAIL.asset));
        assertNull(RailRoute.rides("scenery/vehicles/car"));
        // Rail riders stand on the rail's support; the renderer lifts them to the drawn rail top (BoardRail.railTop).
        var deck = marker(RailRoute.Kind.RAIL, "d", 9, BoardDecoration.Placement.on("bridge", 0));
        assertEquals(deck.placement(), RailRoute.ride(deck));
        assertTrue(RailRoute.railRider("scenery/rail/locomotive") && RailRoute.railRider(RailRoute.BUFFER_STOP));
        assertFalse(RailRoute.railRider("scenery/rail/crossing-signal") || RailRoute.railRider("scenery/maglev/wagon"));
        var maglev = marker(RailRoute.Kind.MAGLEV, "m", 9, BoardDecoration.Placement.absolute(3));
        assertEquals(maglev.placement(), RailRoute.ride(maglev));
    }

    @Test
    void tracksRoundTripThroughBoard2() throws Exception {
        var board = Board.createEmptyBoard(1, 1);
        var route = marker(RailRoute.Kind.RAIL, "r", 29, BoardDecoration.Placement.ground()).withTracks(1 | 1 << RailRoute.piece(2, 4));
        board.getHex(0, 0).setDecorations(List.of(route, marker(RailRoute.Kind.RAIL, "plain", 9, BoardDecoration.Placement.ground())));
        var output = new java.io.ByteArrayOutputStream();
        BoardFile.write(board, output);
        String saved = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, saved.split("\"tracks\"", -1).length - 1, "written only where it differs from the default");
        assertEquals(board.getHex(0, 0).getDecorations(), BoardFile.readNative(saved).getHex(0, 0).getDecorations());
    }

    /**
     * Pillars belong to a maglev marker only (rails lie on their support), stay through every copy, are written only
     * when on, and apply to its connected run: through a junction, not to the maglev crossing it at another level.
     */
    @Test
    void onlyAMaglevStandsOnPillarsWhichApplyToItsConnectedRun() throws Exception {
        var pillared = marker(RailRoute.Kind.MAGLEV, "m", 9, BoardDecoration.Placement.absolute(2)).withPillars(true);
        assertTrue(pillared.duplicate().pillars() && pillared.flip(true, false).pillars() && pillared.withConnections(1).pillars()
              && pillared.transform(0, 0, 0, false, 1, BoardDecoration.Placement.absolute(3)).pillars());
        var rail = marker(RailRoute.Kind.RAIL, "r", 9, BoardDecoration.Placement.ground());
        assertThrows(IllegalArgumentException.class, () -> rail.withPillars(true));
        assertThrows(IllegalArgumentException.class, () -> new BoardDecoration("b", "prop", "scenery/parks/bench", null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0).withPillars(true));

        // A N-S line of three with a branch to the SE from its middle, and a maglev at level 4 crossing the middle.
        var board = Board.createEmptyBoard(5, 5);
        Coords middle = new Coords(2, 2), north = middle.translated(0), south = middle.translated(3), east = middle.translated(2);
        board.getHex(north).setDecorations(List.of(marker(RailRoute.Kind.MAGLEV, "n", 8, BoardDecoration.Placement.absolute(2))));
        var low = marker(RailRoute.Kind.MAGLEV, "low", 1 | 8 | 4, BoardDecoration.Placement.absolute(2)).withPillars(true);
        var high = marker(RailRoute.Kind.MAGLEV, "high", 2 | 16, BoardDecoration.Placement.absolute(4));
        board.getHex(middle).setDecorations(List.of(low, high));
        board.getHex(south).setDecorations(List.of(marker(RailRoute.Kind.MAGLEV, "s", 1, BoardDecoration.Placement.absolute(2))));
        board.getHex(east).setDecorations(List.of(marker(RailRoute.Kind.MAGLEV, "e", 32, BoardDecoration.Placement.absolute(2))));
        board.getHex(middle.translated(1)).setDecorations(
              List.of(marker(RailRoute.Kind.MAGLEV, "ne", 16, BoardDecoration.Placement.absolute(4))));
        var run = RailRoute.run(board::getHex, south, RailRoute.find(board.getHex(south), RailRoute.Kind.MAGLEV));
        assertEquals(List.of("s", "low", "n", "e"), run.stream().map(member -> member.marker().id()).toList());
        assertEquals(List.of("high", "ne"), RailRoute.run(board::getHex, middle, high).stream()
              .map(member -> member.marker().id()).toList());

        var output = new java.io.ByteArrayOutputStream();
        BoardFile.write(board, output);
        String saved = output.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(1, saved.split("\"pillars\"", -1).length - 1, "written only when on");
        assertEquals(board.getHex(middle).getDecorations(), BoardFile.readNative(saved).getHex(middle).getDecorations());
    }
}
