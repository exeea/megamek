/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package megamek.common.strategicBattleSystems;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.List;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class SBFWaterWidthsTest {

    @Test
    void onlyWaterWithGroundTerrainIsRolledAndOnlyOnce() {
        Hex land = new Hex();
        Hex lake = new Hex();
        lake.addTerrain(new Terrain(Terrains.WATER, 2));
        Hex river = new Hex();
        river.addTerrain(new Terrain(Terrains.WATER, 1));
        river.addTerrain(new Terrain(Terrains.WOODS, 1));
        Board board = new Board(3, 1);
        board.setHex(0, 0, land);
        board.setHex(1, 0, lake);
        board.setHex(2, 0, river);
        SBFGame game = new SBFGame();
        game.setBoard(0, board);
        var rolls = new ArrayDeque<>(List.of(9, 4));

        SBFWaterWidths.roll(game, rolls::pop);
        assertEquals(0, board.getHex(0, 0).getStrategicWaterWidth());
        assertEquals(0, board.getHex(1, 0).getStrategicWaterWidth());
        assertEquals(9, board.getHex(2, 0).getStrategicWaterWidth());

        // A reloaded game passes the exchange again; the noted width stays, also on a converted copy of the hex
        SBFWaterWidths.roll(game, rolls::pop);
        assertEquals(9, board.getHex(2, 0).getStrategicWaterWidth());
        assertEquals(9, board.getHex(2, 0).duplicate().getStrategicWaterWidth());
    }
}
