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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.options.OptionsConstants;
import megamek.common.options.SBFRuleOptions;
import org.junit.jupiter.api.Test;

class SBFDesignQuirksTest {

    @Test
    void improvedSensorsCountAsProbeWithTheFormationConversionThreshold() {
        // Design Quirks Table, IO:BF p. 228: Improved Sensors "Element is treated as having PRB for SBF Conversion
        // purposes". A Formation has PRB when two-thirds of its Units do (p. 261, Step 5B): 2 of 3 Units.
        var game = new SBFGame();
        var formation = new SBFFormation();
        var units = List.of(unit(true), unit(true), unit(false));
        units.forEach(formation::addUnit);
        assertFalse(SBFDesignQuirks.formationHas(game, formation, BattleForceSUA.PRB));
        game.getOptions().getOption(SBFRuleOptions.DESIGN_QUIRKS).setValue(true);
        assertTrue(SBFDesignQuirks.formationHas(game, formation, BattleForceSUA.PRB));
        assertTrue(SBFDesignQuirks.unitHas(game, units.getFirst(), BattleForceSUA.PRB));
        assertFalse(SBFDesignQuirks.unitHas(game, units.getLast(), BattleForceSUA.PRB));

        var formationOfOne = new SBFFormation();
        List.of(unit(true), unit(false), unit(false)).forEach(formationOfOne::addUnit);
        assertFalse(SBFDesignQuirks.formationHas(game, formationOfOne, BattleForceSUA.PRB));
    }

    private static SBFUnit unit(boolean improvedSensors) {
        var element = new AlphaStrikeElement();
        element.setType(ASUnitType.BM);
        element.getQuirks().getOption(OptionsConstants.QUIRK_POS_IMPROVED_SENSORS).setValue(improvedSensors);
        var unit = new SBFUnit();
        unit.setType(SBFElementType.BM);
        unit.setElements(List.of(element));
        return unit;
    }
}
