/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
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

import megamek.common.alphaStrike.BattleForceSUA;

/** Available artillery ammunition by weapon family, IO:BF pp. 205–206. */
public enum SBFArtilleryAmmo {
    STANDARD, HOMING, CLUSTER, AIR_DEFENSE, COPPERHEAD, FLECHETTE, ILLUMINATION, INFERNO, SMOKE, THUNDER, THUNDER_ACTIVE;

    public static boolean isArrow(BattleForceSUA weapon) {
        return weapon.isAnyOf(BattleForceSUA.ARTAIS, BattleForceSUA.ARTAC);
    }

    public boolean fits(BattleForceSUA weapon) {
        boolean tube = weapon.isAnyOf(BattleForceSUA.ARTLT, BattleForceSUA.ARTS, BattleForceSUA.ARTT);
        return switch (this) {
            case STANDARD -> weapon.isArtillery() && !weapon.isArtilleryCannon();
            case HOMING, AIR_DEFENSE, INFERNO, THUNDER, THUNDER_ACTIVE -> isArrow(weapon);
            case CLUSTER, COPPERHEAD, FLECHETTE -> tube;
            case ILLUMINATION, SMOKE -> tube || isArrow(weapon);
        };
    }

    public boolean requiresTag() {
        return this == HOMING || this == COPPERHEAD;
    }
}
