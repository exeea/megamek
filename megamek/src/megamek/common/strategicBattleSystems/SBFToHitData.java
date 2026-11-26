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
import megamek.common.board.BoardLocation;
import megamek.common.actions.EntityAction;
import megamek.common.actions.sbf.SBFArtilleryAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack.WeaponMode;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.AlphaStrikeElement;
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
        processAttackerStatus(toHit, game, attackingFormation);
        SBFUnit firingUnit = attackingFormation.getUnits().get(attack.getUnitNumber());
        if (attackingFormation.isOnRadarMap()
              && game.combatOrders().spottedTarget(attackingFormation.getId()).isPresent()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "aerospace squadron is spotting");
        }
        if (attackingFormation.unpackingCargo(game.getCurrentRound()) || !attackingFormation.canAttackAfterEngagement()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "cannot fire after evading");
        }
        if (firingUnit.isConstructing()) {
            // Fortified Positions, IO:BF p. 220: the TRN and ENG Units "may not make weapon attacks of any kind"
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "building a fortification");
        }
        if (!firingUnit.canFire()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "targeting systems destroyed");
        }
        SBFFormation target = game.getFormation(attack.getTargetId()).orElseThrow();
        if (attackingFormation.recoveringFighters(game.getCurrentRound())
              && (firingUnit.hasAnySUAOf(BattleForceSUA.AT, BattleForceSUA.ST)
              || attackingFormation.getUnits().stream().flatMap(u -> u.getElements().stream())
                    .noneMatch(AlphaStrikeElement::isDropShip)
              || !attackingFormation.isEngagedWith(target.getId()))) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "recovering aerospace Squadron");
        }
        if (target.recoveringFighters(game.getCurrentRound())) {
            toHit.addModifier(-1, "target recovering aerospace Squadron");
        }
        if (target.capitalSector() != null && target.isCrippled()) {
            toHit.addModifier(-2, "crippled or drifting space target");
        }
        if (attackingFormation.inHighDrop() || (target.inHighDrop() && !attackingFormation.isOnRadarMap())) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "high-altitude drop");
        }
        boolean airToAir = attackingFormation.sharesAerospaceMap(target);
        if (airToAir) {
            processDefensiveMovement(toHit, target);
        }
        if (firingUnit.getTargetingCrits() > 0) {
            toHit.addModifier(((airToAir || target.isOnRadarMap()) ? 2 : 1) * firingUnit.getTargetingCrits(), "targeting damage");
        }
        Optional<ASRange> range = standardAttackRange(game, attackingFormation, target);
        if (attack.getWeaponMode() == WeaponMode.TAG) {
            if (!firingUnit.hasSUA(BattleForceSUA.TAG) || !attackingFormation.occupiesGroundHex() || !target.occupiesGroundHex()
                  || !game.onSameBoard(attackingFormation, target)
                  || (attackingFormation.getPosition().coords().distance(target.getPosition().coords()) > 1)) {
                return new SBFToHitData(TargetRoll.IMPOSSIBLE, "TAG designation requires a target within one hex");
            }
            range = Optional.of(ASRange.LONG);
        }
        if (range.isEmpty()) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "target outside standard attack range");
        }
        if ((attack.getWeaponMode() != WeaponMode.TAG) && shelteredInUrbanHex(game, attackingFormation, target)) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "target is in an urban hex");
        }
        toHit.range = range.get();
        if (attackingFormation.isSharpShooters()) {
            // Sharp Shooters, IO:BF p. 225: "Short Range +2; Medium Range +1; Long Range +1; Extreme Range +2"
            switch (toHit.range) {
                case SHORT, EXTREME -> toHit.addModifier(2, "sharp shooters range");
                case MEDIUM, LONG -> toHit.addModifier(1, "sharp shooters range");
                default -> { }
            }
        } else if (attackingFormation.isBrawlers()) {
            // Brawlers, IO:BF p. 225: "Short Range -1; Medium Range +2; Long Range +3; Extreme Range +5"
            switch (toHit.range) {
                case SHORT -> toHit.addModifier(-1, "brawlers range");
                case MEDIUM -> toHit.addModifier(2, "brawlers range");
                case LONG -> toHit.addModifier(3, "brawlers range");
                case EXTREME -> toHit.addModifier(5, "brawlers range");
                default -> { }
            }
        } else {
            switch (toHit.range) {
                case SHORT -> { }
                case MEDIUM -> toHit.addModifier(1, "medium range");
                case LONG -> toHit.addModifier(2, "long range");
                case EXTREME -> toHit.addModifier(3, "extreme range");
                default -> { }
            }
        }
        int artillery = (int) declarations.stream().filter(SBFArtilleryAttack.class::isInstance)
              .filter(a -> a.getEntityId() == attackingFormation.getId()).count();
        if (artillery > 0) {
            toHit.addModifier(artillery, "artillery attacks this turn");
        }
        if (!attackingFormation.isOnRadarMap() && target.isOnRadarMap()) {
            if ((attack.getWeaponMode() != WeaponMode.STANDARD) || firingUnit.isUnderwater(game, attackingFormation)) {
                return new SBFToHitData(TargetRoll.IMPOSSIBLE, "ground-to-air weapon unavailable");
            }
            processGroundToAir(toHit, attackingFormation, firingUnit, target);
            return toHit;
        }
        if (airToAir) {
            if (!attackingFormation.mayAttackAirOpponent(target.getId())) {
                return new SBFToHitData(TargetRoll.IMPOSSIBLE, "squadron committed its attack elsewhere");
            }
            if (attack.getWeaponMode() != WeaponMode.STANDARD) {
                return new SBFToHitData(TargetRoll.IMPOSSIBLE, "air-to-air weapon required");
            }
            if (game.combatOrders().isBehind(attackingFormation, target)) {
                toHit.addModifier(-2, "tailing target");
            }
            aerospaceFireControl(toHit, firingUnit);
            if (target.getUnits().stream().flatMap(unit -> unit.getElements().stream()).anyMatch(AlphaStrikeElement::isDropShip)) {
                toHit.addModifier(-2, "airborne DropShip");
            } else if (target.getUnits().stream().flatMap(unit -> unit.getElements().stream()).anyMatch(AlphaStrikeElement::isSmallCraft)) {
                toHit.addModifier(-1, "small craft");
            }
            return toHit;
        }
        // IO:BF pp. 169, 173: engaged Formations must fire within an engagement; restricted morale requires one.
        if ((attackingFormation.isMoraleRestricted() || !attackingFormation.getEngagedOpponents().isEmpty())
              && !attackingFormation.isEngagedWith(target.getId())) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "attack requires an engagement with target");
        }
        boolean indirect = attack.getWeaponMode() == WeaponMode.INDIRECT;
        boolean torpedo = attack.getWeaponMode() == WeaponMode.TORPEDO;
        boolean submerged = firingUnit.isUnderwater(game, attackingFormation);
        boolean sameHex = attackingFormation.getPosition().equals(target.getPosition());
        if (indirect) {
            var spotter = game.getFormation(attack.getSpotterId()).orElse(null);
            if (!firingUnit.hasSUA(BattleForceSUA.IF) || sameHex || submerged || (spotter == null)
                  || (!spotter.isOnRadarMap() && !target.getPosition().equals(spotter.getPosition()))
                  || !game.combatOrders().spottedTarget(attack.getSpotterId()).filter(id -> id == target.getId()).isPresent()
                  || game.areHostile(spotter, game.getPlayer(attackingFormation.getOwnerId()))) {
                return new SBFToHitData(TargetRoll.IMPOSSIBLE, "indirect fire requires IF and a friendly spotter");
            }
            toHit.addModifier(1, "indirect fire");
        } else if (!game.combatOrders().hasLineOfSight(game, attackingFormation.getPosition(), target.getPosition(), true)) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "line of sight blocked");
        }
        if (torpedo && (!firingUnit.hasSUA(BattleForceSUA.TOR) || !sameHex
              || target.getUnits().stream().noneMatch(unit -> unit.isUnderwater(game, target)))) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "torpedo attack requires TOR and an underwater target");
        }
        if (submerged && (!sameHex || (!torpedo && ((range.get() == ASRange.SHORT)
              || (firingUnit.getCurrentDamage().getDamage(range.get()).damage == 0))))) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "underwater range");
        }
        if (torpedo && (attack.weaponDamage(game, attackingFormation, firingUnit, range.get()) == 0)) {
            return new SBFToHitData(TargetRoll.IMPOSSIBLE, "no torpedoes at this range");
        }
        if (submerged || torpedo) {
            toHit.addModifier(1, "underwater");
        }
        processGroundAttacker(toHit, game, attackingFormation, firingUnit);
        processTargetTerrain(toHit, game, game.getHex(target.getPosition()), target);
        processGroundTarget(toHit, target, toHit.range, false);
        switch (target.moraleStatus()) {
            case SHAKEN -> toHit.addModifier(-1, "shaken");
            case BROKEN -> toHit.addModifier(-2, "broken");
            case ROUTED -> toHit.addModifier(-3, "routed");
            default -> toHit.doNothing();
        }
        processDeclarations(toHit, attackingFormation, game, attack, declarations);
        return toHit;
    }

    public static void processAttackerStatus(SBFToHitData toHit, SBFGame game, SBFFormation attackingFormation) {
        if (attackingFormation.ambushedIn(game.getCurrentRound())) {
            toHit.addModifier(2, "attacked from hiding");
        }
        if (attackingFormation.disruptedByEmp(game.getCurrentRound())) {
            toHit.addModifier(2, "EMP minefield");
        }
    }

    /** Ordinary ground weapon modifiers shared by attacks on Formations and on terrain. */
    public static void processGroundAttacker(SBFToHitData toHit, SBFGame game, SBFFormation attackingFormation, SBFUnit firingUnit) {
        if (game.combatOrders().spottedTarget(attackingFormation.getId()).isPresent()) {
            toHit.addModifier(1, "spotting");
        }
        if (firingUnit.hasSUA(BattleForceSUA.BFC)) {
            toHit.addModifier(1, "basic fire control");
        }
        if (firingUnit.hasSUA(BattleForceSUA.DRO)) {
            toHit.addModifier(1, "drone");
        }
        if (attackingFormation.getEngagementAttackModifier() != 0) {
            toHit.addModifier(attackingFormation.getEngagementAttackModifier(), "failed evasion");
        }
        if (attackingFormation.dismountedIn(game.getCurrentRound())) {
            toHit.addModifier(attackingFormation.airDropMovementCost() == 3 ? 2 : 1, "dismounted this turn");
        }
        if (firingUnit.hasSUA(BattleForceSUA.SHLD)) {
            toHit.addModifier(1, "shields");
        }
        if (attackingFormation.getJumpUsedThisTurn() > 0) {
            toHit.addModifier(attackingFormation.getJumpUsedThisTurn(), "attacker JUMP");
        }
    }

    /** IO:BF p. 267: a BA Unit's range modifiers differ from those of other STL-equipped Units. */
    private static int stealthModifier(SBFFormation formation, SBFUnit unit, ASRange range) {
        boolean mimetic = unit.hasMimeticArmor();
        if ((!mimetic && !unit.hasSUA(BattleForceSUA.STL)) || unit.isType(SBFElementType.CI)
              || (mimetic && (formation.getMpSpentThisTurn() >= formation.getMovement() / 4))) {
            return 0;
        }
        if (unit.isType(SBFElementType.BA)) {
            return ((range == ASRange.SHORT) || (range == ASRange.MEDIUM)) ? 1 : 2;
        }
        return range == ASRange.SHORT ? 0 : 1;
    }

    public static boolean isLosCover(Hex hex) {
        return (hex != null) && ((woodsCover(hex) > 0) || hex.containsTerrain(Terrains.RUBBLE)
              || hex.containsTerrain(Terrains.BUILDING));
    }

    /** Aerospace To-Hit Modifiers table, IO:BF p. 177. */
    /** Environmental darkness table, IO:BF pp. 210, 212. */
    public static void applyDarkness(SBFToHitData toHit, SBFGame game, SBFFormation attacker, BoardLocation aim) {
        if (attacker.isAerospace() || attacker.hasSearchlight() || game.isIlluminated(aim)) {
            return;
        }
        int modifier = switch (game.getPlanetaryConditions().getLight()) {
            case DUSK_DAWN -> 1;
            case FULL_MOON -> 2;
            case MOONLESS -> 3;
            case PITCH_BLACK -> 4;
            default -> 0;
        };
        if (modifier > 0) {
            toHit.addModifier(modifier, "darkness");
        }
    }

    /** Ground-to-air target and firing system modifiers, including Air Defense Arrow IV (IO:BF pp. 178, 206). */
    public static void processGroundToAir(SBFToHitData toHit, SBFFormation attackingFormation, SBFUnit firingUnit,
          SBFFormation target) {
        processDefensiveMovement(toHit, target);
        toHit.addModifier(2, "airborne aerospace target");
        if (target.getUnits().stream().flatMap(unit -> unit.getElements().stream())
              .anyMatch(AlphaStrikeElement::isDropShip)) {
            toHit.addModifier(-2, "airborne DropShip");
        }
        if (attackingFormation.isAerospace() && attackingFormation.getUnits().stream()
              .flatMap(unit -> unit.getElements().stream()).anyMatch(AlphaStrikeElement::isDropShip)) {
            toHit.addModifier(-2, "grounded DropShip");
        }
        if (target.getUnits().stream().flatMap(unit -> unit.getElements().stream())
              .anyMatch(AlphaStrikeElement::isSmallCraft)) {
            toHit.addModifier(-1, "small craft");
        }
        aerospaceFireControl(toHit, firingUnit);
    }

    public static void aerospaceFireControl(SBFToHitData toHit, SBFUnit firingUnit) {
        if (firingUnit.hasSUA(BattleForceSUA.DRO)) {
            toHit.addModifier(1, "drone");
        }
        if (firingUnit.hasSUA(BattleForceSUA.BFC)) {
            toHit.addModifier(1, "basic fire control");
        } else if (!firingUnit.hasSUA(BattleForceSUA.AFC)
              && firingUnit.getElements().stream().anyMatch(AlphaStrikeElement::isSupportVehicle)) {
            toHit.addModifier(2, "no fire control");
        }
    }

    /** Artillery smoke counts as heavy woods for LOS and accuracy (IO:BF p. 206). */
    public static int forestDensity(Hex hex) {
        return hex == null ? 0 : Math.clamp(Math.max(hex.terrainLevel(Terrains.WOODS), hex.terrainLevel(Terrains.JUNGLE)), 0, 3);
    }

    private static int woodsCover(Hex hex) {
        return hex.containsTerrain(Terrains.SMOKE) ? 2 : Math.min(2, forestDensity(hex));
    }

    /**
     * Urban Hexes, IO:BF p. 216: "Formations in an urban hex may only be attacked by another Formation in the same hex
     * or by artillery or aerospace attacks." Artillery has its own attack path; TAG painting belongs to it.
     */
    private static boolean shelteredInUrbanHex(SBFGame game, SBFFormation attacker, SBFFormation target) {
        if (!game.usesUrbanCombat() || attacker.isAerospace() || !target.occupiesGroundHex()
              || target.getPosition().equals(attacker.getPosition())) {
            return false;
        }
        Hex hex = game.getHex(target.getPosition());
        return (hex != null) && hex.containsTerrain(Terrains.BUILDING);
    }

    /** IO:BF p. 170: target terrain modifiers also apply within the same hex. */
    public static void processTargetTerrain(SBFToHitData toHit, SBFGame game, Hex hex, SBFFormation target) {
        if (hex == null) {
            return;
        }
        int woods = game.usesExpandedWoods() ? Math.max(woodsCover(hex), forestDensity(hex)) : woodsCover(hex);
        if (woods > 0) {
            toHit.addModifier(woods, "target woods");
        }
        if (game.usesAdvancedTerrain() && hex.containsTerrain(Terrains.FIELDS)) {
            toHit.addModifier(target != null && target.isAnyTypeOf(SBFElementType.CI, SBFElementType.BA) ? 2 : 1, "planted fields");
        }
        if (game.usesAdvancedTerrain() && hex.containsTerrain(Terrains.INDUSTRIAL)) {
            toHit.addModifier(1, "heavy industrial terrain");
        } else if (hex.containsTerrain(Terrains.BUILDING)) {
            toHit.addModifier(hex.terrainLevel(Terrains.BUILDING) > 2 ? 2 : 1, "target urban terrain");
        }
    }

    /**
     * Returns the range of a standard attack between the two Formations (IO:BF p.169-170): Long Range in the same or
     * an adjacent hex, Extreme Range two hexes away; empty when the target is farther, on another board or either has
     * no position. Engaged Formations use the winner's chosen range once both sides have maneuvered.
     */
    public static Optional<ASRange> standardAttackRange(SBFGame game, SBFFormation attacker, SBFFormation target) {
        if (attacker.combatZone() != null || target.combatZone() != null) {
            if (!attacker.isOnRadarMap() && (target.getRadarZone() == SBFRadarZone.CENTRAL)
                  && (attacker.getPosition() != null) && (target.getPosition() != null)
                  && attacker.getPosition().isSameBoardAs(target.getPosition())) {
                // Ground-to-Air, IO:BF p. 178: one hex from any part of the flight path is Short.
                if (target.getGroundFlightPath().stream().anyMatch(hex ->
                      hex.coords().distance(attacker.getPosition().coords()) <= 1)) {
                    return Optional.of(ASRange.SHORT);
                }
                int distance = attacker.getPosition().coords().distance(target.getPosition().coords());
                return distance == 2 ? Optional.of(ASRange.MEDIUM)
                      : distance <= 4 ? Optional.of(ASRange.LONG) : Optional.empty();
            }
            if (attacker.sharesAerospaceMap(target) && !attacker.waitingForSpaceCombat(game.getCurrentRound())
                  && !target.waitingForSpaceCombat(game.getCurrentRound()) && attacker.isEngagedWith(target.getId())
                  && SBFCombatOrders.sameEngagementArea(game, attacker, target)) {
                return game.combatOrders().maneuver(attacker.getId(), target.getId()).map(SBFCombatOrders.Maneuver::range);
            }
            return Optional.empty();
        }
        if ((attacker.getPosition() == null) || (target.getPosition() == null)
              || !attacker.getPosition().isSameBoardAs(target.getPosition())) {
            return Optional.empty();
        }
        if (attacker.isEngagedWith(target.getId()) && attacker.getPosition().equals(target.getPosition())) {
            return game.combatOrders().maneuver(attacker.getId(), target.getId()).map(m -> {
                if ((m.range() == ASRange.SHORT) && (target.getMovementMode() == SBFMovementMode.VTOL)
                      && (m.winner() != target.getId())) {
                    return ASRange.MEDIUM;
                }
                return SBFCombatOrders.limitTerrainRange(game, attacker, target, m.range());
            });
        }
        return switch (attacker.getPosition().coords().distance(target.getPosition().coords())) {
            case 0, 1 -> Optional.of(SBFCombatOrders.limitTerrainRange(game, attacker, target, ASRange.LONG));
            case 2 -> Optional.of(ASRange.EXTREME);
            default -> Optional.empty();
        };
    }

    /** IO:BF pp. 198–199. These modifiers apply to attacks aimed at a Formation. */
    public static void processDefensiveMovement(SBFToHitData toHit, SBFFormation target) {
        if (target.evasiveModifier() > 0) {
            toHit.addModifier(target.evasiveModifier(), "evasive movement");
        }
        if (target.isHullDown()) {
            toHit.addModifier(1, "hull down");
        }
    }

    /** Target type and movement apply to both direct weapon and direct artillery fire (IO:BF pp. 170, 203). */
    public static void processGroundTarget(SBFToHitData toHit, SBFFormation target, ASRange range, boolean artillery) {
        processDefensiveMovement(toHit, target);
        if (target.isAerospace() && target.isDeployed() && !target.isOnRadarMap()) {
            toHit.addModifier(-4, "grounded aerospace");
        } else if (target.airDropMovementCost() > 0) {
            toHit.addModifier(3, "air-dropped this turn");
        } else if ((target.getTmm() > 0) && !target.isBoggedDown() && (!target.isHullDown() || target.getMpSpentThisTurn() > 0)
              && (!artillery || (target.getMovement() > 0))) {
            toHit.addModifier(target.getTmm(), "TMM");
        }
        if (target.hasSprintedThisTurn()) {
            toHit.addModifier(-1, "target sprinted");
        }
        if (target.getJumpUsedThisTurn() > 0) {
            toHit.addModifier(target.getJumpUsedThisTurn(), "target JUMP");
        }
        if (target.getEngagementDefenseModifier() != 0) {
            toHit.addModifier(target.getEngagementDefenseModifier(), "target evaded");
        }
        int stealth = target.getUnits().stream().mapToInt(unit -> stealthModifier(target, unit, range)).min().orElse(0);
        if (stealth != 0) {
            toHit.addModifier(stealth, "target stealth");
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
        long declaredSlots = Stream.concat(attacks.stream(), Stream.of(attack))
              .filter(a -> a.getWeaponMode() != WeaponMode.TORPEDO).map(SBFStandardUnitAttack::getUnitNumber)
              .distinct().count();
        long unusedSlots = game.combatOrders().standardAttackLimit(attacker) - declaredSlots;
        if (unusedSlots < 0) {
            toHit.addModifier(TargetRoll.IMPOSSIBLE, "outmaneuvered attack limit");
        } else if ((withheld > 0) && (unusedSlots > 0)) {
            toHit.addModifier(-(int) Math.min(2, Math.min(unusedSlots, withheld)), "withheld fire");
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
              .filter(SBFStandardUnitAttack.class::isInstance)
              .map(a -> ((SBFStandardUnitAttack) a).getTargetId())
              .distinct()
              .toList();
    }
}
