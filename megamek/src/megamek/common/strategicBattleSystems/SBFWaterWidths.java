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

import java.util.function.IntSupplier;

import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.compute.Compute;
import megamek.common.units.Terrains;

/**
 * Water, IO:BF p. 166: "If there is any ground terrain in the hex, roll 2D6; the result is the water's width in terms of
 * the number of Total Warfare (30 meter) water hexes present", noted for the hex "so all future movement through it
 * encounters the same modifiers". The server rolls every such hex once, before play begins; the width travels with the
 * board ({@link Hex#getStrategicWaterWidth()}) and {@link SBFTerrainMovement#widthCost(Hex)} charges it.
 */
public final class SBFWaterWidths {

    /** Rolls 2D6 for each ground hex with water and ground terrain that has no width yet. */
    public static void roll(SBFGame game) {
        roll(game, () -> Compute.d6(2));
    }

    static void roll(SBFGame game, IntSupplier twoD6) {
        for (Board board : game.getBoards().values()) {
            if (!board.isGround()) {
                continue;
            }
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    Hex hex = board.getHex(x, y);
                    if ((hex != null) && (hex.terrainLevel(Terrains.WATER) > 0) && !SBFTerrainMovement.isFullWater(hex)
                          && (hex.getStrategicWaterWidth() == 0)) {
                        hex.setStrategicWaterWidth(twoD6.getAsInt());
                    }
                }
            }
        }
    }

    private SBFWaterWidths() {}
}
