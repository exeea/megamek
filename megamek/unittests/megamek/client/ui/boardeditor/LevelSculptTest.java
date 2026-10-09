/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class LevelSculptTest {
    private final Coords centre = new Coords(4, 4);

    private static void sculpt(Board board, List<Coords> cells, int delta, boolean slope) {
        LevelSculpt.raise(board, cells, delta, slope).forEach((at, level) -> {
            Hex next = board.getHex(at).duplicate(); next.setLevel(level); board.setHex(at, next);
        });
    }

    private static int steepestEdge(Board board) {
        int steepest = 0;
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                for (Coords neighbour : at.allAdjacent()) {
                    if (board.contains(neighbour)) {
                        steepest = Math.max(steepest, Math.abs(board.getHex(at).getLevel() - board.getHex(neighbour).getLevel()));
                    }
                }
            }
        }
        return steepest;
    }

    private static int level(Board board, Coords at) { return board.getHex(at).getLevel(); }

    @Test void threeRaisesWithSlopeBuildAHillWithoutCliffs() {
        Board board = Board.createEmptyBoard(11, 11);
        for (int i = 0; i < 3; i++) { sculpt(board, List.of(centre), 1, true); }
        assertEquals(3, level(board, centre));
        for (int ring = 1; ring <= 4; ring++) {
            for (Coords at : centre.allAtDistance(ring).stream().filter(board::contains).toList()) { assertEquals(Math.max(0, 3 - ring), level(board, at), at + " in ring " + ring); }
        }
        assertEquals(1, steepestEdge(board));
    }

    @Test void loweringWithSlopeDigsAPit() {
        Board board = Board.createEmptyBoard(11, 11);
        for (int i = 0; i < 2; i++) { sculpt(board, List.of(centre), -1, true); }
        assertEquals(-2, level(board, centre));
        centre.allAdjacent().forEach(at -> assertEquals(-1, level(board, at)));
        centre.allAtDistance(2).forEach(at -> assertEquals(0, level(board, at)));
        assertEquals(1, steepestEdge(board));
    }

    @Test void raisingABankKeepsItsExistingCliffAndNeverFillsTheValley() {
        Board board = Board.createEmptyBoard(9, 9);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 9; y++) { board.setHex(new Coords(x, y), new Hex(3)); }
        }
        Coords edge = new Coords(3, 4);
        sculpt(board, List.of(edge), 1, true);
        assertEquals(4, level(board, edge));
        for (int x = 4; x < 9; x++) {
            for (int y = 0; y < 9; y++) { assertEquals(0, level(board, new Coords(x, y)), "The valley stays put"); }
        }
        // On the bank itself the new top is still sloped toward its unchanged neighbours.
        edge.allAdjacent().stream().filter(at -> at.getX() < 4).forEach(at -> assertEquals(3, level(board, at)));
    }

    @Test void slopeOffRaisesAPlateauWithCliffs() {
        Board board = Board.createEmptyBoard(9, 9);
        List<Coords> footprint = centre.allAtDistanceOrLess(1);
        for (int i = 0; i < 3; i++) { sculpt(board, footprint, 1, false); }
        footprint.forEach(at -> assertEquals(3, level(board, at)));
        centre.allAtDistance(2).forEach(at -> assertEquals(0, level(board, at)));
        assertEquals(3, steepestEdge(board));
    }

    @Test void boardEdgesAndCornersSlopeInsideTheBoardOnly() {
        Board board = Board.createEmptyBoard(5, 5);
        Coords corner = new Coords(0, 0);
        for (int i = 0; i < 3; i++) { sculpt(board, List.of(corner), 1, true); }
        assertEquals(3, level(board, corner));
        assertEquals(1, steepestEdge(board));
        assertEquals(0, level(board, new Coords(4, 4)));
    }
}
