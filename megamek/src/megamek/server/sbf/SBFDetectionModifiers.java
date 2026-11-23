/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.server.sbf;

import static megamek.common.alphaStrike.BattleForceSUA.*;

import java.util.ArrayList;
import java.util.List;
import megamek.common.alphaStrike.ASSpecialAbilityCollector;
import megamek.common.alphaStrike.AlphaStrikeElement;

import megamek.common.board.BoardLocation;
import megamek.common.rolls.TargetRoll;
import megamek.common.TargetRollModifier;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;

public class SBFDetectionModifiers extends TargetRoll {

    public SBFDetectionModifiers(SBFFormation viewer, SBFFormation target) {
        this(viewer, target, target.getPosition());
    }

    SBFDetectionModifiers(SBFFormation viewer, SBFFormation target, BoardLocation position) {
        sensorTargetRollModifiers(viewer, target, position).forEach(this::addModifier);
    }

    /**
     * Returns the sensor range for sensor detection, IO:BF p.197
     *
     * @param formation The detecting formation
     *
     * @return the sensor range
     */
    static int sensorRange(SBFFormation formation) {
        // Summary-only and scenario-authored counters may have only a Formation-level sensor rating.
        boolean allEquipmentKnown = !formation.getUnits().isEmpty()
              && formation.getUnits().stream().allMatch(unit -> !unit.getElements().isEmpty());
        int best = allEquipmentKnown ? 0 : range(formation, formation.getType(), formation.hasSUA(RCN));
        for (var unit : formation.getUnits()) {
            if (unit.getArmor() > 0 && unit.getCurrentArmor() <= 0) {
                continue;
            }
            best = Math.max(best, range(unit, unit.getType(), formation.hasSUA(RCN)));
            for (var element : unit.getElements()) {
                var type = element.getASUnitType() == null ? unit.getType() : SBFElementType.getUnitType(element);
                best = Math.max(best, range(element, type, formation.hasSUA(RCN)));
            }
        }
        return best;
    }

    private static int range(ASSpecialAbilityCollector sensors, SBFElementType type, boolean recon) {
        int basic = basicRange(type);
        int probe = sensors.hasSUA(BH) ? 10 : sensors.hasSUA(PRB)
              ? (sensors instanceof AlphaStrikeElement element && element.hasClanActiveProbe() ? 9 : 8)
              : sensors.hasAnySUAOf(LPRB, WAT) ? 7 : 0;
        return Math.max(basic > 0 && recon ? basic + 2 : basic, probe);
    }

    private static int basicRange(SBFElementType type) {
        return switch (type) {
            case BM -> 6;
            case V, BA -> 5;
            case null, default -> 0;
        };
    }

    private List<TargetRollModifier> sensorTargetRollModifiers(SBFFormation viewer, SBFFormation target, BoardLocation position) {
        List<TargetRollModifier> result = new ArrayList<>();
        int range = sensorRange(viewer);
        int distance = viewer.getPosition().coords()
              .distance(position.coords());
        if (distance < range) {
            result.add(new TargetRollModifier(Math.min(4, range - distance), "distance below sensor range"));
        } else if ((range == 0) || (distance > range)) {
            result.add(new TargetRollModifier(TargetRoll.CHECK_FALSE, "distance above sensor range"));
        }
        if (target.hasSUA(AECM) && target.hasAnySUAOf(STL, MAS, LMAS)) {
            result.add(new TargetRollModifier(-3, "target has AECM and STL, MAS or LMAS"));
        } else if (target.hasAnySUAOf(STL, MAS, LMAS)) {
            result.add(new TargetRollModifier(-2, "target has STL, MAS or LMAS"));
        } else if (target.hasSUA(AECM) && viewer.hasSUA(BH)) {
            result.add(new TargetRollModifier(-1, "target has AECM, viewer has BH"));
        } else if (target.hasSUA(AECM)) {
            result.add(new TargetRollModifier(-2, "target has AECM"));
        } else if (target.hasAnySUAOf(ECM, WAT, LECM) && !viewer.hasSUA(BH)) {
            result.add(new TargetRollModifier(-1, "target has ECM, WAT or LECM"));
        }
        return result;
    }
}
