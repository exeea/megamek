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

package megamek.common.strategicBattleSystems;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFAttackAction;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.game.InGameObject;
import megamek.common.rolls.TargetRoll;
import megamek.common.units.Terrains;

public class SBFToHitData extends TargetRoll {

    private ASRange range;

    /** The position-derived range when this attack can be declared. */
    public Optional<ASRange> attackRange() {
        return Optional.ofNullable(range);
    }

    public SBFToHitData(int value, String desc) {
        super(value, desc);
    }

    public SBFToHitData() {}

    public static SBFToHitData compileToHit(SBFGame game, SBFStandardUnitAttack attack) {
        return compileToHit(game, attack, game.getActionsVector());
    }

    /** Use the local declaration list for a firing preview, or the accepted declarations for resolution. */
    public static SBFToHitData compileToHit(SBFGame game, SBFStandardUnitAttack attack,
          List<? extends EntityAction> declarations) {
        if (!attack.isDataValid(game)) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "Invalid attack");
        }
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation attackingFormation = game.getFormation(attack.getEntityId()).get();
        SBFToHitData toHit = new SBFToHitData(attackingFormation.getSkill(), "Skill");
        SBFUnit firingUnit = attackingFormation.getUnits().get(attack.getUnitNumber());
        if (!firingUnit.canFire()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "targeting systems destroyed");
        }
        if (firingUnit.getTargetingCrits() > 0) {
            toHit.addModifier(firingUnit.getTargetingCrits(), "targeting damage");
        }
        SBFFormation target = game.getFormation(attack.getTargetId()).orElseThrow();
        Optional<ASRange> range = standardAttackRange(attackingFormation, target);
        if (range.isEmpty()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "target outside standard attack range");
        }
        // IO:BF pp. 169, 173: engaged Formations must fire within an engagement; restricted morale requires one.
        if ((attackingFormation.isMoraleRestricted() || !attackingFormation.getEngagedOpponents().isEmpty())
              && !attackingFormation.isEngagedWith(target.getId())) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "attack requires an engagement with target");
        }
        if (isTerrainLosBlocked(game, attackingFormation, target)) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "line of sight blocked by terrain");
        }
        toHit.range = range.get();
        if (toHit.range == ASRange.EXTREME) {
            toHit.addModifier(3, "extreme range");
        } else {
            toHit.addModifier(2, "long range");
        }
        if (firingUnit.hasSUA(BattleForceSUA.BFC)) {
            toHit.addModifier(1, "basic fire control");
        }
        if (firingUnit.hasSUA(BattleForceSUA.DRO)) {
            toHit.addModifier(1, "drone");
        }
        processTargetTerrain(toHit, game.getHex(target.getPosition()));
        processTMM(toHit, game, attack);
        processSprinting(toHit, game, attack);
        processJUMP(toHit, game, attack);
        processMorale(toHit, game, attack);
        processDeclarations(toHit, attackingFormation, game, attack, declarations);
        return toHit;
    }

    /** The automatic adjacent-hex terrain block in the Line of Sight Determination Table (IO:BF p. 169). */
    public static boolean isTerrainLosBlocked(SBFGame game, SBFFormation attacker, SBFFormation target) {
        return game.onSameBoard(attacker, target)
              && (attacker.getPosition().coords().distance(target.getPosition().coords()) == 1)
              && isLosCover(game.getHex(attacker.getPosition())) && isLosCover(game.getHex(target.getPosition()));
    }

    public static boolean isLosCover(Hex hex) {
        return (hex != null) && (hex.containsTerrain(Terrains.WOODS) || hex.containsTerrain(Terrains.RUBBLE)
              || hex.containsTerrain(Terrains.BUILDING));
    }

    /** IO:BF p. 170: target terrain modifiers also apply within the same hex. */
    private static void processTargetTerrain(SBFToHitData toHit, Hex hex) {
        if (hex == null) {
            return;
        }
        if (hex.containsTerrain(Terrains.WOODS)) {
            toHit.addModifier(Math.min(2, hex.terrainLevel(Terrains.WOODS)), "target woods");
        }
        if (hex.containsTerrain(Terrains.BUILDING)) {
            toHit.addModifier(hex.terrainLevel(Terrains.BUILDING) > 2 ? 2 : 1, "target urban terrain");
        }
    }

    /**
     * Returns the range of a standard attack between the two Formations (IO:BF p.169-170): Long Range in the same or
     * an adjacent hex, Extreme Range two hexes away; empty when the target is farther, on another board or either has
     * no position. In the same hex an opposed Maneuver Roll would choose the range; it is not implemented, and Long
     * Range is the rule's result when it is not made.
     */
    public static Optional<ASRange> standardAttackRange(SBFFormation attacker, SBFFormation target) {
        if ((attacker.getPosition() == null) || (target.getPosition() == null)
              || !attacker.getPosition().isSameBoardAs(target.getPosition())) {
            return Optional.empty();
        }
        return switch (attacker.getPosition().coords().distance(target.getPosition().coords())) {
            case 0, 1 -> Optional.of(ASRange.LONG);
            case 2 -> Optional.of(ASRange.EXTREME);
            default -> Optional.empty();
        };
    }

    private static void processTMM(SBFToHitData toHit, SBFGame game, SBFStandardUnitAttack attack) {
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation target = game.getFormation(attack.getTargetId()).get();
        if (target.getTmm() > 0) {
            toHit.addModifier(target.getTmm(), "TMM");
        }
    }

    /** Attacks against sprinting units receive an additional -1 to-hit modifier (IO:BF p.199). */
    private static void processSprinting(SBFToHitData toHit, SBFGame game, SBFStandardUnitAttack attack) {
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation target = game.getFormation(attack.getTargetId()).get();
        if (target.hasSprintedThisTurn()) {
            toHit.addModifier(-1, "target sprinted");
        }
    }

    private static void processJUMP(SBFToHitData toHit, SBFGame game, SBFStandardUnitAttack attack) {
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation attacker = game.getFormation(attack.getEntityId()).get();
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation target = game.getFormation(attack.getTargetId()).get();
        if (attacker.getJumpUsedThisTurn() > 0) {
            toHit.addModifier(attacker.getJumpUsedThisTurn(), "attacker JUMP");
        }
        if (target.getJumpUsedThisTurn() > 0) {
            toHit.addModifier(target.getJumpUsedThisTurn(), "target JUMP");
        }
    }

    private static void processMorale(SBFToHitData toHit, SBFGame game, SBFStandardUnitAttack attack) {
        //noinspection OptionalGetWithoutIsPresent
        SBFFormation target = game.getFormation(attack.getTargetId()).get();
        switch (target.moraleStatus()) {
            case SHAKEN -> toHit.addModifier(-1, "shaken");
            case BROKEN -> toHit.addModifier(-2, "broken");
            case ROUTED -> toHit.addModifier(-3, "routed");
            default -> toHit.doNothing();
        }
    }

    /** IO:BF pp. 169-170: declaration order sets the secondary target; only capable Units can withhold fire. */
    private static void processDeclarations(SBFToHitData toHit, SBFFormation attacker, SBFGame game,
          SBFStandardUnitAttack attack, List<? extends EntityAction> declarations) {
        List<SBFStandardUnitAttack> attacks = declarations.stream().filter(SBFStandardUnitAttack.class::isInstance)
              .map(SBFStandardUnitAttack.class::cast).filter(a -> a.getEntityId() == attacker.getId()).toList();
        List<Integer> targets = Stream.concat(attacks.stream().map(SBFStandardUnitAttack::getTargetId),
              Stream.of(attack.getTargetId())).distinct().toList();
        if (targets.size() > 2) {
            toHit.addModifier(TargetRoll.IMPOSSIBLE, "too many targets");
            return;
        }
        if (!targets.getFirst().equals(attack.getTargetId())) {
            toHit.addModifier(1, "secondary target");
        }

        Set<Integer> firingUnits = new HashSet<>();
        attacks.forEach(declaration -> firingUnits.add(declaration.getUnitNumber()));
        firingUnits.add(attack.getUnitNumber());
        int withheld = 0;
        for (int i = 0; i < attacker.getUnits().size(); i++) {
            SBFUnit unit = attacker.getUnits().get(i);
            if (!firingUnits.contains(i) && unit.canFire()
                  && (SBFStandardUnitAttack.damageAtRange(attacker, unit, toHit.range, game.getCurrentRound()) > 0)) {
                withheld++;
            }
        }
        if (withheld > 0) {
            toHit.addModifier(-Math.min(2, withheld), "withheld fire");
        }
    }

    /**
     * Returns a list of target IDs of all the targets of all attacks that the attacker of the given attack is
     * performing this round. The result can be empty (the unit isn't attacking anything, or it is not the firing
     * phase), it can have one or two entries.
     *
     * @param unit The attacker to check attacks for
     * @param game The game
     *
     * @return A list of all target IDs
     */
    public static List<Integer> targetsOfFormation(InGameObject unit, SBFGame game) {
        return game.getActionsVector().stream()
              .filter(a -> a.getEntityId() == unit.getId())
              .filter(a -> a instanceof SBFAttackAction)
              .map(a -> ((SBFAttackAction) a).getTargetId())
              .distinct()
              .toList();
    }
}
