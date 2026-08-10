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
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
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

package megamek.common.alphaStrike.conversion;

import static megamek.common.alphaStrike.BattleForceSUA.LTAG;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.client.ui.clientGUI.calculationReport.DummyCalculationReport;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.Mounted;
import megamek.common.exceptions.LocationFullException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ASBattleArmorDamageConverterTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void squadSupportLightTagGrantsLtag() throws LocationFullException {
        BattleArmor battleArmor = new BattleArmor();
        battleArmor.setSquadSize(4);
        battleArmor.autoSetInternal();
        Mounted<?> lightTag = battleArmor.addEquipment(EquipmentType.get("CLBALightTAG"),
              BattleArmor.LOC_SQUAD,
              false,
              BattleArmor.MOUNT_LOC_LEFT_ARM,
              false);
        lightTag.setSquadSupportWeapon(true);

        AlphaStrikeElement element = new AlphaStrikeElement();
        element.setType(ASUnitType.BA);
        ASDamageConverter converter = ASDamageConverter.getASDamageConverter(battleArmor,
              element,
              new DummyCalculationReport());

        converter.processSpecialAbilities();

        assertTrue(converter.locations[0].hasSUA(LTAG));
    }
}