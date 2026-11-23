/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 of the License, or (at your option) any later version,
 * as published by the Free Software Foundation.
 */

package megamek.server.sbf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack.WeaponMode;
import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.planetaryConditions.Light;
import megamek.common.strategicBattleSystems.SBFCombatOrders;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFCombatControlTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final SBFFormation first = formation(1, 10);
    private final SBFFormation second = formation(2, 20);

    private SBFFormation formation(int id, int owner) {
        var formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(owner);
        formation.setTactics(6);
        formation.setMovement(3);
        formation.setMpSpentThisTurn(3);
        formation.setPosition(BoardLocation.of(new Coords(1, 1), 0));
        formation.setType(SBFElementType.BM);
        formation.addUnit(new SBFUnit());
        formation.setDeployed(true);
        return formation;
    }

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        game.setPhase(GamePhase.FIRING);
        game.setBoard(0, Board.createEmptyBoard(4, 4));
        game.addPlayer(10, new Player(10, "A"));
        game.addPlayer(20, new Player(20, "B"));
        game.addUnit(first);
        game.addUnit(second);
    }

    private SBFCombatControl dice(int... results) {
        return new SBFCombatControl(manager, Arrays.stream(results).iterator()::nextInt);
    }

    @Test
    void oneManeuverPerEngagementRequiresTheWinnersChoiceAndAppliesVtolException() {
        first.setEngagedWith(2, true);
        second.setEngagedWith(1, true);
        first.setMpSpentThisTurn(2);
        second.setMovementMode(SBFMovementMode.VTOL);
        var control = dice(5, 2);
        control.startFiring();
        control.startFiring();
        assertTrue(game.combatOrders().isBehind(first, second));
        assertTrue(SBFToHitData.standardAttackRange(game, first, second).isEmpty());
        assertFalse(control.choose(20, 2, 1, "range", "short"));
        assertTrue(control.choose(10, 1, 2, "range", "short"));
        assertFalse(control.choose(10, 1, 2, "range", "long"));
        assertEquals(ASRange.MEDIUM, SBFToHitData.standardAttackRange(game, first, second).orElseThrow());
        assertEquals(ASRange.SHORT, SBFToHitData.standardAttackRange(game, second, first).orElseThrow());
    }

    @Test
    void partialContactCanManeuverChooseRangeAndFireButUnlocatedContactCannot() {
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.visibilityHelper().setVisibility(10, 2, SBFVisibilityStatus.EYES_ON_TARGET);
        first.setEngagedWith(2, true);
        second.setEngagedWith(1, true);
        var control = dice(8, 2);
        control.startFiring();
        assertFalse(control.choose(20, 2, 1, "range", "short"));
        assertTrue(control.choose(10, 1, 2, "range", "short"));
        game.combatOrders().setSpotter(2, 1);
        var view = game.combatOrders().visibleTo(id -> game.getVisibility(game.getPlayer(10), id).isLocated(),
              id -> game.isVisible(10, id));
        assertEquals(ASRange.SHORT, view.maneuver(1, 2).orElseThrow().range());
        assertTrue(view.spottedTarget(2).isEmpty());
        var shot = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG);
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(shot), first));
        assertEquals(ASRange.SHORT, ((SBFStandardUnitAttack) game.getActionsVector().getFirst()).getRange());
        assertFalse(game.isVisible(10, 2));
    }

    @Test
    void bothFailAtLongAndEqualMarginWithEqualMpRerolls() {
        assertEquals(ASRange.LONG, dice(2, 4).rollManeuver(first, second).range());
        var result = dice(6, 6, 7, 9).rollManeuver(first, second);
        assertEquals(2, result.winner());
        assertEquals(-1, result.outmaneuvered());
        first.setMovement(5);
        first.setMpSpentThisTurn(5);
        assertEquals(1, dice(8, 8).rollManeuver(first, second).winner());
    }

    @Test
    void engagedAirborneFlightsUseZoneThrustTailingAndAerospaceGunneryWithoutGroundTmm() {
        first.setRadarPosition(SBFRadarZone.A, List.of());
        second.setRadarPosition(SBFRadarZone.A, List.of());
        first.setEngagedWith(2, true);
        second.setEngagedWith(1, true);
        first.setType(SBFElementType.AS);
        second.setType(SBFElementType.AS);
        first.getUnits().getFirst().addTargetingCrit();
        second.setTmm(5);
        var control = dice(8, 2);
        control.startFiring();
        assertTrue(control.choose(10, 1, 2, "range", "medium"));
        var shot = new SBFStandardUnitAttack(1, 0, 2, ASRange.MEDIUM);
        var hit = SBFToHitData.compileToHit(game, shot);
        assertEquals(first.getSkill() + 1 + 2 - 2, hit.getValue());
        second.setRadarPosition(SBFRadarZone.B, List.of());
        assertTrue(SBFToHitData.compileToHit(game, shot).cannotSucceed());
    }

    @Test
    void occupantControlsAdjacentCoverAndFiringLocksTheEstablishedLos() {
        second.setPosition(BoardLocation.of(new Coords(2, 1), 0));
        game.getBoard(0).setHex(second.getPosition().coords(), new Hex(0, "woods:1", ""));
        var action = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG);
        var control = dice();
        assertTrue(SBFToHitData.compileToHit(game, action).cannotSucceed());
        assertFalse(control.choose(10, 2, 1, "sight", "open"));
        assertTrue(control.choose(20, 2, 1, "sight", "open"));
        assertFalse(SBFToHitData.compileToHit(game, action).cannotSucceed());
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(action), first));
        assertFalse(control.choose(20, 2, 1, "sight", "block"));
    }

    @Test
    void roughTerrainOppositeWoodsDoesNotGiveTheOccupantALosChoiceButWaterDoes() {
        second.setPosition(BoardLocation.of(new Coords(2, 1), 0));
        game.getBoard(0).setHex(second.getPosition().coords(), new Hex(0, "woods:1", ""));
        game.getBoard(0).setHex(first.getPosition().coords(), new Hex(0, "rough:1", ""));
        assertFalse(SBFCombatOrders.controlsSight(game, second, first));
        game.getBoard(0).setHex(first.getPosition().coords(), new Hex(0, "water:1", ""));
        assertTrue(SBFCombatOrders.controlsSight(game, second, first));
    }

    @Test
    void friendlyGroundSpotterEnablesIndirectFireThroughBlockedDirectLosAndUsesTheUnitIfValue() {
        second.setPosition(BoardLocation.of(new Coords(2, 1), 0));
        var spotter = formation(3, 10);
        spotter.setPosition(second.getPosition());
        game.addUnit(spotter);
        game.getBoard(0).setHex(first.getPosition().coords(), new Hex(0, "woods:1", ""));
        game.getBoard(0).setHex(second.getPosition().coords(), new Hex(0, "woods:1", ""));
        first.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.IF, new ASDamage(3, false));
        var attack = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG, WeaponMode.INDIRECT, 3);
        assertTrue(SBFToHitData.compileToHit(game, attack).cannotSucceed());
        assertFalse(dice().spot(20, 3, 2));
        assertTrue(dice().spot(10, 3, 2));
        assertFalse(SBFToHitData.compileToHit(game, attack).cannotSucceed());
        assertEquals(3, attack.weaponDamage(game, first, first.getUnits().getFirst(), ASRange.LONG));
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(attack), first));
        assertEquals(WeaponMode.INDIRECT, ((SBFStandardUnitAttack) game.getActionsVector().getFirst()).getWeaponMode());
    }

    @Test
    void aerialSpotterEnablesIndirectFireWithoutOccupyingTheTargetHexAndForfeitsItsOwnAttacks() {
        second.setPosition(BoardLocation.of(new Coords(2, 1), 0));
        var spotter = formation(3, 10);
        spotter.setType(SBFElementType.AS);
        spotter.setRadarPosition(SBFRadarZone.INNER, List.of());
        game.addUnit(spotter);
        first.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.IF, new ASDamage(3, false));
        assertTrue(dice().spot(10, 3, 2));
        var indirect = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG, WeaponMode.INDIRECT, 3);
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(indirect), first));
        var attack = new SBFStandardUnitAttack(3, 0, 2, ASRange.LONG);
        assertTrue(SBFToHitData.compileToHit(game, attack).getDesc().contains("aerospace squadron is spotting"));
    }

    @Test
    void aerialSpottingChecksWeatherTerrainAndPreviouslyDeclaredShots() {
        first.setType(SBFElementType.AS);
        first.setRadarPosition(SBFRadarZone.INNER, List.of());
        game.getPlanetaryConditions().setLight(Light.PITCH_BLACK);
        assertFalse(dice().spot(10, 1, 2));
        first.setRadarPosition(SBFRadarZone.CENTRAL, List.of(BoardLocation.of(new Coords(0, 1), 0)));
        assertTrue(SBFDetectionHelper.inAerialVisualRange(game, first, second));
        game.getBoard(0).setHex(second.getPosition().coords(), new Hex(0, "woods:3", ""));
        assertFalse(dice().spot(10, 1, 2));
        game.getBoard(0).setHex(second.getPosition().coords(), new Hex());
        game.addAction(new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG));
        assertFalse(dice().spot(10, 1, 2));
    }

    @Test
    void submergedAttackHalvesStandardDamageAndCanAddATorpedoAttack() {
        game.getBoard(0).setHex(first.getPosition().coords(), new Hex(0, "water:2", ""));
        first.setMovementMode(SBFMovementMode.SUBMARINE);
        second.setMovementMode(SBFMovementMode.SUBMARINE);
        var unit = first.getUnits().getFirst();
        var damage = ASDamageVector.createNormRndDmg(5, 5, 5);
        unit.setDamage(damage);
        unit.getSpecialAbilities().setSUA(BattleForceSUA.TOR, damage);
        var ordinary = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG);
        var torpedo = new SBFStandardUnitAttack(1, 0, 2, ASRange.LONG, WeaponMode.TORPEDO, -1);
        assertEquals(2, ordinary.weaponDamage(game, first, unit, ASRange.LONG));
        assertEquals(5, torpedo.weaponDamage(game, first, unit, ASRange.LONG));
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(ordinary, torpedo), first));
        second.setPosition(BoardLocation.of(new Coords(2, 1), 0));
        assertTrue(SBFToHitData.compileToHit(game, ordinary).cannotSucceed());
    }
}
