/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import megamek.common.Hex;
import org.junit.jupiter.api.Test;

/** Corridors are shared local ways; their authored side and the rolling stock use one geometry. */
class RailCorridorTest {
    private static final Coords AT = new Coords(2, 2);

    private static Board line(boolean deck) {
        Board board = Board.createEmptyBoard(5, 5);
        for (int y = 0; y < 5; y++) {
            board.setHex(2, y, new Hex(0, deck
                  ? "bridge:1:9;bridge_elev:1;bridge_cf:40;bridge_road:1:9;bridge_rail:1:9"
                  : "road:1:9;rail:1:9", ""));
        }
        return board;
    }

    @Test
    void localSharedAndOffBoardBordersAgreeWithoutASpanGate() {
        for (boolean deck : new boolean[] { false, true }) {
            Board board = line(deck);
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(2, y);
                assertEquals(9, RailRoute.corridor(board.getHex(at), at, board::getHex, deck).sides());
            }
            board.setHex(2, 1, new Hex(0));
            assertEquals(8, RailRoute.corridor(board.getHex(AT), AT, board::getHex, deck).sides());
        }
        Board board = line(false);
        board.setHex(AT, new Hex(0, "road:1:3;rail:1:3", ""));
        assertEquals(0, RailRoute.corridor(board.getHex(AT), AT, board::getHex).sides(), "Hairpins are not corridors");
    }

    @Test
    void stackedCorridorsStayOnTheirOwnLayerAndDoNotJoinInaccessibleDecks() {
        Board board = line(false);
        for (int y = 0; y < 5; y++) {
            board.setHex(2, y, new Hex(0,
                  "road:1:9;rail:1:9;bridge:1:9;bridge_elev:4;bridge_cf:40;bridge_road:1:9;bridge_rail:1:9", ""));
        }
        for (boolean deck : new boolean[] { false, true }) {
            var marker = RailRoute.markers(board.getHex(AT), RailRoute.Kind.RAIL).stream()
                  .filter(m -> RailRoute.onDeck(m) == deck).findFirst().orElseThrow();
            var run = RailRoute.corridorRun(board::getHex, AT, marker);
            assertEquals(5, run.size());
            assertTrue(run.stream().allMatch(m -> RailRoute.onDeck(m.marker()) == deck));
        }
        board.setHex(2, 1, new Hex(0, "bridge:1:9;bridge_elev:4;bridge_cf:40;bridge_road:1:9;bridge_rail:1:9", ""));
        assertEquals(8, RailRoute.corridor(board.getHex(AT), AT, board::getHex).sides());
        assertEquals(9, RailRoute.corridor(board.getHex(AT), AT, board::getHex, true).sides());
    }

    @Test
    void besideRunKeepsBorderJoinsAndRidersThenSwapsWithoutTouchingWays() throws Exception {
        Board board = line(false);
        var marker = RailRoute.find(board.getHex(AT), RailRoute.Kind.RAIL);
        var run = RailRoute.corridorRun(board::getHex, AT, marker);
        assertEquals(5, run.size());
        var bits = RailRoute.besideBits(board::getHex, run, new RailRoute.Member(AT, marker));
        bits.forEach((member, side) -> RailRoute.beside(board.getHex(member.at()), false, side));
        var here = RailRoute.corridor(board.getHex(AT), AT, board::getHex);
        var there = RailRoute.corridor(board.getHex(AT.translated(0)), AT.translated(0), board::getHex);
        assertEquals(9, here.beside());
        var line = RailRoute.lines(9, RailRoute.pieces(9), here).getFirst();
        var next = RailRoute.lines(9, RailRoute.pieces(9), there).getFirst();
        float[] north = line.at(0), south = next.at(1), edge = RailRoute.border(0);
        assertEquals(north[0], south[0] + edge[0] * 2, .0001f);
        assertEquals(north[1], south[1] + edge[1] * 2, .0001f);
        var snapped = RailRoute.snap(board.getHex(AT), marker, 0, 0, 0, here);
        assertEquals(RailRoute.BESIDE_RAIL, Math.abs(snapped.x()), .001f);
        var travelled = RailRoute.follow(board::getHex, AT, marker, snapped, 80);
        assertEquals(snapped.x(), travelled.spot().x(), .001f);
        var bytes = new ByteArrayOutputStream();
        BoardFile.write(board, bytes);
        Board saved = BoardFile.readNative(bytes.toString(StandardCharsets.UTF_8));
        assertEquals(board.getHex(AT).getAppearance(), saved.getHex(AT).getAppearance());
        for (var member : run) {
            Hex hex = board.getHex(member.at());
            RailRoute.beside(hex, false, HexAppearance.beside(hex.getAppearance(), "rail") ^ member.marker().connections());
        }
        var swapped = RailRoute.corridor(board.getHex(AT), AT, board::getHex);
        assertEquals(-snapped.x(), RailRoute.snap(board.getHex(AT), marker, 0, 0, 0, swapped).x(), .001f);
        RailRoute.lay(board.getHex(AT), false, 1, 0);
        assertEquals(0, HexAppearance.beside(board.getHex(AT).getAppearance(), "rail") & ~65);
    }

    @Test
    void besideTerminusKeepsItsOffsetAllTheWayToTheBufferStop() {
        var c = new RailRoute.Corridor(1, 1, 1);
        var line = RailRoute.lines(1, 0, c).getFirst();
        for (float t : new float[] { 0, .001f, .5f, 1 }) { assertEquals(RailRoute.BESIDE_RAIL, line.at(t)[0], .001f); }
        assertEquals(line.at(0)[0], RailRoute.stops(1, c).getFirst().x(), .001f);
    }

    @Test
    void disagreementFallsBackToMiddleAndFlipRetainsPhysicalPlacement() {
        Board board = line(false);
        RailRoute.beside(board.getHex(AT), false, 73);
        assertEquals(0, RailRoute.corridor(board.getHex(AT), AT, board::getHex).beside());
        HexAppearance a = HexAppearance.rail(0, 73);
        for (boolean horizontal : new boolean[] { false, true }) {
            for (boolean vertical : new boolean[] { false, true }) {
                HexAppearance flipped = RailRoute.flip(a, horizontal, vertical);
                assertNotNull(flipped);
                assertEquals(a, RailRoute.flip(flipped, horizontal, vertical));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> HexAppearance.rail(0, 63));
        assertThrows(IllegalArgumentException.class, () -> a.validateOwner("road"));
        assertFalse(RailRoute.corridorRun(board::getHex, new Coords(0, 0),
              RailRoute.find(board.getHex(AT), RailRoute.Kind.RAIL)).size() > 0);
        assertTrue(RailRoute.corridorRun(board::getHex, AT, RailRoute.find(board.getHex(AT), RailRoute.Kind.RAIL)).size() > 0);
    }
}
