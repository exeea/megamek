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
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.TargetRoll;
import megamek.common.TargetRollModifier;
import megamek.common.strategicBattleSystems.SBFDesignQuirks;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFUnit;

public class SBFDetectionModifiers extends TargetRoll {

    public SBFDetectionModifiers(SBFGame game, SBFFormation viewer, SBFFormation target) {
        this(game, viewer, target, target.getPosition());
    }

    SBFDetectionModifiers(SBFGame game, SBFFormation viewer, SBFFormation target, BoardLocation position) {
        this(game, viewer, target, position, false);
    }

    SBFDetectionModifiers(SBFGame game, SBFFormation viewer, SBFFormation target, BoardLocation position,
          boolean empField) {
        sensorTargetRollModifiers(game, viewer, target, position, empField).forEach(this::addModifier);
    }

    /**
     * Returns the sensor range for sensor detection, IO:BF p.197
     *
     * @param game      The game, for the optional Design Quirks rules
     * @param formation The detecting formation
     *
     * @return the sensor range
     */
    static int sensorRange(SBFGame game, SBFFormation formation) {
        // Summary-only and scenario-authored counters may have only a Formation-level sensor rating.
        boolean allEquipmentKnown = !formation.getUnits().isEmpty()
              && formation.getUnits().stream().allMatch(unit -> !unit.getElements().isEmpty());
        int best = allEquipmentKnown ? 0 : range(formation, formation.getType(), formation.hasSUA(RCN),
              SBFDesignQuirks.formationHas(game, formation, PRB));
        for (var unit : formation.getUnits()) {
            if (unit.getArmor() > 0 && unit.getCurrentArmor() <= 0) {
                continue;
            }
            int unitRange = range(unit, unit.getType(), formation.hasSUA(RCN),
                  SBFDesignQuirks.unitHas(game, unit, PRB));
            for (var element : unit.getElements()) {
                var type = element.getASUnitType() == null ? unit.getType() : SBFElementType.getUnitType(element);
                unitRange = Math.max(unitRange, range(element, type, formation.hasSUA(RCN),
                      SBFDesignQuirks.elementHas(game, element, PRB)));
            }
            best = Math.max(best, (unitRange > 0) && hasImprovedSensors(game, unit) ? unitRange + 2 : unitRange);
        }
        return best;
    }

    /**
     * Design Quirks Table, IO:BF p. 228: Improved Sensors "If 50% of Elements in Unit have PQIS, then increase
     * detection range by 2 hexes". Mobile Structures cannot have it (footnote **).
     */
    private static boolean hasImprovedSensors(SBFGame game, SBFUnit unit) {
        if (!game.usesDesignQuirks()) {
            return false;
        }
        long count = unit.getElements().stream().filter(e -> e.hasQuirk(OptionsConstants.QUIRK_POS_IMPROVED_SENSORS)
              && (SBFElementType.getUnitType(e) != SBFElementType.MS)).count();
        return (count > 0) && (count * 2 >= unit.getElements().size());
    }

    /** @param probe whether the sensors have PRB, counting Improved Sensors ({@link SBFDesignQuirks}) */
    private static int range(ASSpecialAbilityCollector sensors, SBFElementType type, boolean recon, boolean probe) {
        int basic = basicRange(type);
        int probeRange = sensors.hasSUA(BH) ? 10 : probe
              ? (sensors instanceof AlphaStrikeElement element && element.hasClanActiveProbe() ? 9 : 8)
              : sensors.hasAnySUAOf(LPRB, WAT) ? 7 : 0;
        return Math.max(basic > 0 && recon ? basic + 2 : basic, probeRange);
    }

    private static int basicRange(SBFElementType type) {
        return switch (type) {
            case BM -> 6;
            case V, BA -> 5;
            case null, default -> 0;
        };
    }

    private List<TargetRollModifier> sensorTargetRollModifiers(SBFGame game, SBFFormation viewer, SBFFormation target,
          BoardLocation position, boolean empField) {
        List<TargetRollModifier> result = new ArrayList<>();
        int range = sensorRange(game, viewer);
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
        } else if ((empField || target.hasAnySUAOf(ECM, WAT, LECM)) && !viewer.hasSUA(BH)) {
            result.add(new TargetRollModifier(-1, "target has ECM, WAT or LECM"));
        }
        return result;
    }
}
