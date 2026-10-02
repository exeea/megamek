/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.actions;

import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.rolls.PilotingRollData;
import megamek.common.units.Entity;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.LandAirMek;

/**
 * The piloting skill rolls kicks and pushes cause. The server adds them to the game and rolls them later against the
 * unit's base piloting roll; an attack preview can show the same rolls.
 */
public final class PhysicalAttackPsr {

    private PhysicalAttackPsr() { }

    /** The roll a unit makes after missing a kick (a control roll when {@link #isControlRoll} says so). */
    public static PilotingRollData missedKick(Entity attacker) {
        return new PilotingRollData(attacker.getId(), 0, "missed a kick");
    }

    /** Whether the unit makes a control roll instead of a PSR for a missed kick or a push: an airborne LAM. */
    public static boolean isControlRoll(Entity attacker) {
        return (attacker instanceof LandAirMek) && attacker.isAirborneVTOLorWIGE();
    }

    /**
     * The PSR of a unit that was kicked, pushed or tripped (or pushed back), modified by the target's weight class
     * under the TacOps physical attack PSR option.
     *
     * @param psrEntity the unit that makes the roll
     * @param target    the target of the attack, whose weight class sets the modifier
     * @param reason    the reason shown with the roll, such as "was kicked"
     */
    public static PilotingRollData kickOrPush(Game game, Entity psrEntity, Entity target, String reason) {
        int mod = 0;
        PilotingRollData psr = new PilotingRollData(psrEntity.getId(), mod, reason);
        if (psrEntity.hasQuirk(OptionsConstants.QUIRK_POS_STABLE)) {
            psr.addModifier(-1, "stable", false);
        }
        if (game.getOptions().booleanOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_PHYSICAL_PSR)) {

            mod = switch (target.getWeightClass()) {
                case EntityWeightClass.WEIGHT_LIGHT -> 1;
                case EntityWeightClass.WEIGHT_MEDIUM -> 0;
                case EntityWeightClass.WEIGHT_HEAVY -> -1;
                case EntityWeightClass.WEIGHT_ASSAULT -> -2;
                default -> mod;
            };
            String reportStr;
            if (mod > 0) {
                reportStr = ("weight class modifier +") + mod;
            } else {
                reportStr = ("weight class modifier ") + mod;
            }
            psr.addModifier(mod, reportStr, false);
        }
        return psr;
    }
}
