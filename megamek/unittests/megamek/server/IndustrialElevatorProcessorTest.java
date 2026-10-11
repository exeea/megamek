/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import megamek.common.Hex;
import megamek.common.IndustrialElevator;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;

class IndustrialElevatorProcessorTest {
    /**
     * A shaft whose bottom is above its top breaks the board rules, so no game loads such a board; one that arrives
     * anyway gives its hex no elevator instead of stopping the game with an exception.
     */
    @Test
    void aShaftWithItsBottomAboveItsTopBuildsNoElevatorAndTheGameGoesOn() {
        TWGameManager manager = new TWGameManager();
        Board board = Board.createEmptyBoard(2, 1);
        // Bottom -2, top 1, 100 t; and bottom 3 above top 1.
        board.setHex(0, 0, new Hex(0, "industrial_elevator:-2:266", ""));
        board.setHex(1, 0, new Hex(0, "industrial_elevator:3:266", ""));
        manager.getGame().setBoard(board);

        new IndustrialElevatorProcessor(manager).initializeElevators();

        var elevators = manager.getGame().getIndustrialElevators();
        assertEquals(1, elevators.size());
        IndustrialElevator elevator = elevators.iterator().next();
        assertEquals(new Coords(0, 0), elevator.getCoords());
        assertEquals(-2, elevator.getShaftBottom());
        assertEquals(1, elevator.getShaftTop());
    }
}
