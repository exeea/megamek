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

import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.options.OptionsConstants;

/**
 * Design Quirks that SBF conversion counts as special abilities (Design Quirks Table, IO:BF p. 228). Conversion runs
 * without the game's options, so these are counted where the ability is used, with the conversion's own thresholds
 * (IO:BF p. 261, Steps 5A and 5B), and only with the optional Design Quirks rules.
 */
public final class SBFDesignQuirks {

    private SBFDesignQuirks() { }

    /** @return true when the Element has the ability or, with the Design Quirks option, a quirk that grants it */
    public static boolean elementHas(SBFRuleOptionsUser game, AlphaStrikeElement element, BattleForceSUA sua) {
        return element.hasSUA(sua) || (game.usesDesignQuirks() && grants(element, sua));
    }

    /** @return true when the Unit has the ability or enough of its Elements have it, counting quirks */
    public static boolean unitHas(SBFRuleOptionsUser game, SBFUnit unit, BattleForceSUA sua) {
        return unit.hasSUA(sua) || (game.usesDesignQuirks()
              && SBFUnitConverter.unitHasSUA(sua, unit.getElements(), element -> elementHas(game, element, sua)));
    }

    /** @return true when the Formation has the ability or enough of its Units have it, counting quirks */
    public static boolean formationHas(SBFRuleOptionsUser game, SBFFormation formation, BattleForceSUA sua) {
        return formation.hasSUA(sua) || (game.usesDesignQuirks()
              && BaseFormationConverter.formationHasSUA(sua, formation.getUnits(), unit -> unitHas(game, unit, sua)));
    }

    /**
     * Improved Sensors: "Element is treated as having PRB for SBF Conversion purposes". Mobile Structures cannot have
     * a quirk (footnote **).
     */
    private static boolean grants(AlphaStrikeElement element, BattleForceSUA sua) {
        return (sua == BattleForceSUA.PRB) && element.hasQuirk(OptionsConstants.QUIRK_POS_IMPROVED_SENSORS)
              && (SBFElementType.getUnitType(element) != SBFElementType.MS);
    }
}
