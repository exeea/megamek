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
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirDefenseArtilleryAttack;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFCounterBatteryAttack;
import megamek.common.actions.sbf.SBFDirectArtilleryAttack;
import megamek.common.actions.sbf.SBFIndirectArtilleryAttack;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.rolls.Roll;
import megamek.common.strategicBattleSystems.SBFArtilleryAmmo;
import megamek.common.strategicBattleSystems.SBFArtilleryState.Battery;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMinefields;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFScenarioProgress;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.util.SerializationHelper;
import megamek.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFArtilleryTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private SBFFormation battery;
    private SBFFormation target;

    @BeforeEach
    void setup() {
        when(manager.getGame()).thenReturn(game);
        game.addPlayer(1, new Player(1, "Alpha"));
        game.addPlayer(2, new Player(2, "Beta"));
        game.setBoard(0, Board.createEmptyBoard(45, 4));
        game.setCurrentRound(5);
        game.setPhase(GamePhase.FIRING);
        game.setTurns(List.of(new SBFFormationTurn(1)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
        game.getOptions().getOption(SBFRuleOptions.ADVANCED_ARTILLERY).setValue(true);
        battery = formation(1, 11, 1);
        target = formation(2, 22, 2);
        battery.getUnits().getFirst().getSpecialAbilities().mergeSUA(BattleForceSUA.ARTLT, 6);
    }

    private SBFFormation formation(int owner, int id, int x) {
        var f = new SBFFormation();
        f.setId(id);
        f.setOwnerId(owner);
        f.setDeployed(true);
        f.setType(SBFElementType.V);
        f.setSkill(3);
        f.setPosition(BoardLocation.of(new Coords(x, 1), 0));
        SBFUnit u = new SBFUnit();
        u.setType(SBFElementType.V);
        u.setArmor(15);
        f.addUnit(u);
        game.addUnit(f);
        return f;
    }

    @Test
    void directChatOrderUsesAuthoritativeBudgetAndPerHitAllocationBeforeStandardFire() {
        var command = new SBFCombatCommand(mock(Server.class), manager);
        String[] order = {"sbfcombat", "artillery", "11", "LT", "direct", "0", "3", "2", "22"};
        command.run(2, order);
        assertTrue(game.getActionsVector().isEmpty());
        var second = new SBFUnit();
        second.setArmor(15);
        target.addUnit(second);
        command.run(1, order);
        command.run(1, order);
        assertEquals(1, game.getActionsVector().size());
        assertFalse(battery.isDone());
        var shot = new SBFStandardUnitAttack(11, 0, 22, ASRange.LONG);
        assertTrue(SBFToHitData.compileToHit(game, shot).getDesc().contains("artillery attacks this turn"));
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(9);
            dice.when(Compute::d6).thenReturn(2);
            new SBFActionsProcessor(manager).handleActions();
        }
        assertTrue(game.getPendingHits().isEmpty());
        assertEquals(1, game.getDamageAllocations().size());
        int exchange = game.getDamageAllocations().getFirst().artilleryExchange();
        assertTrue(exchange > 0);
        game.setPhase(GamePhase.FIRING_REPORT);
        assertFalse(SBFCombatDamage.allocate(manager, 1, 11, 22, exchange, List.of(1)));
        assertTrue(SBFCombatDamage.allocate(manager, 2, 11, 22, exchange, List.of(1)));
        SBFCombatDamage.applyEndPhase(manager);
        assertEquals(9, second.getCurrentArmor());
        assertEquals(15, target.getUnits().getFirst().getCurrentArmor());
    }

    @Test
    void delayedSpotterAndIntelligencePreplotAreCapturedAtLaunchAndBatteryMayBeDestroyed() {
        target.setPosition(BoardLocation.of(new Coords(20, 1), 0));
        var spotter = formation(1, 33, 20);
        spotter.getSpecialAbilities().setSUA(BattleForceSUA.PRB);
        game.getOptions().getOption(SBFRuleOptions.INIT_BATTLEFIELD_INT).setValue(true);
        int side = SBFScenarioProgress.sideId(game, game.getPlayer(1));
        game.scenarioProgress().initiativeOrders(side).selectPreplot(target.getPosition(), 1, false);
        var direct = new SBFDirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        assertFalse(SBFArtillery.declare(manager, 1, direct));
        var indirect = new SBFIndirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), 33);
        assertTrue(SBFArtillery.declare(manager, 1, indirect));
        new SBFActionsProcessor(manager).handleActions();
        var inFlight = game.artillery().flights().getFirst();
        assertEquals(6, inFlight.arrival());
        assertEquals(6, inFlight.targetNumber()); // 3 skill + 7 indirect - 1 spotter - 2 probe - 1 prior BI pre-plot.
        String xml = SerializationHelper.getSaveGameXStream().toXML(game.artillery());
        var loaded = (megamek.common.strategicBattleSystems.SBFArtilleryState)
              SerializationHelper.getLoadSaveGameXStream().fromXML(xml);
        assertEquals(List.of(inFlight), loaded.flights());
        game.removeFormationFromPlay(battery);
        game.clearActions();
        game.setCurrentRound(6);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(8);
            new SBFActionsProcessor(manager).handleActions();
        }
        assertEquals(6, game.getPendingHits().getFirst().damage());
        assertTrue(game.artillery().flights().isEmpty());
        assertEquals(15, target.getUnits().getFirst().getCurrentArmor());
    }

    @Test
    void directArtilleryCombinesTargetMovementAndTypeWithoutAnImmobileTmmBonus() {
        target.setMovement(3);
        target.setTmm(2);
        target.setJumpUsedThisTurn(2);
        target.setSprintedThisTurn(true);
        var shot = new SBFDirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        new SBFArtillery(shot, manager).handle();
        assertEquals(10, game.artillery().flights().getLast().targetNumber());
        target.setMovement(0);
        new SBFArtillery(shot, manager).handle();
        assertEquals(8, game.artillery().flights().getLast().targetNumber());
        target.setType(SBFElementType.AS);
        new SBFArtillery(shot, manager).handle();
        assertEquals(4, game.artillery().flights().getLast().targetNumber());
    }

    @Test
    void delayedTerrainHitCannotRelockAfterRetargetingAFormationAndReturningToTheOriginalHex() {
        target.setPosition(BoardLocation.of(new Coords(20, 1), 0));
        var terrain = new SBFIndirectArtilleryAttack(11, Player.PLAYER_NONE, BattleForceSUA.ARTLT, target.getPosition(), null);
        var occupant = new SBFIndirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        new SBFArtillery(terrain, manager).handle();
        game.setCurrentRound(6);
        new SBFArtillery(occupant, manager).handle();
        new SBFArtillery(terrain, manager).handle();
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(12);
            SBFArtillery.resolveArrivals(manager);
        }
        assertFalse(game.artillery().mission(new Battery(11, BattleForceSUA.ARTLT)).locked());
        assertEquals(6, game.artillery().terrainDamage(target.getPosition()));
    }

    @Test
    void friendlyTagAttackResolvesAheadOfHomingRoundsAtFixedFourAndDoesNotDealWeaponDamage() {
        var unit = battery.getUnits().getFirst();
        unit.getSpecialAbilities().mergeSUA(BattleForceSUA.ARTAIS, 6);
        unit.getSpecialAbilities().setSUA(BattleForceSUA.TAG);
        var homing = new SBFIndirectArtilleryAttack(11, 22, BattleForceSUA.ARTAIS, target.getPosition(), true);
        assertTrue(SBFArtillery.declare(manager, 1, homing));
        var mark = new SBFStandardUnitAttack(11, 0, 22, ASRange.SHORT, SBFStandardUnitAttack.WeaponMode.TAG, -1);
        assertTrue(new SBFAttackProcessor(manager).processAttacks(List.of(mark), battery));
        Roll paint = mock(Roll.class);
        when(paint.getIntValue()).thenReturn(12);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.rollD6(2)).thenReturn(paint);
            dice.when(() -> Compute.d6(2)).thenReturn(4);
            new SBFActionsProcessor(manager).handleActions();
        }
        assertEquals(1, game.getPendingHits().size());
        assertEquals(4, game.getPendingHits().getFirst().damage());
        assertTrue(game.artillery().paintedByFriendly(game, 1, 22));
        game.setCurrentRound(6);
        game.clearActions();
        new SBFArtillery(homing, manager).handle();
        SBFArtillery.resolveArrivals(manager);
        assertEquals(1, game.getPendingHits().size());
        assertFalse(game.artillery().paintedByFriendly(game, 1, 22));
    }

    @Test
    void scenarioPreplotAutomaticallyHitsTerrainAndLowersTheFormationModifierToFive() {
        int side = SBFScenarioProgress.sideId(game, game.getPlayer(1));
        game.artillery().setScenarioPreplot(side, target.getPosition());
        var terrain = new SBFIndirectArtilleryAttack(11, Player.PLAYER_NONE, BattleForceSUA.ARTLT, target.getPosition(), null);
        var occupant = new SBFIndirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        new SBFArtillery(terrain, manager).handle();
        new SBFArtillery(occupant, manager).handle();
        assertTrue(game.artillery().flights().getFirst().automatic());
        assertFalse(game.artillery().flights().getLast().automatic());
        assertEquals(8, game.artillery().flights().getLast().targetNumber());
    }

    @Test
    void terrainFactorsPersistAndChangesExposeANewLayerWhileDamagedRoadsRetainExits() {
        BoardLocation aim = target.getPosition();
        game.getHex(aim).addTerrain(new Terrain(Terrains.WOODS, 1));
        assertFalse(SBFTerrainConversion.damage(game, aim, 1399));
        assertTrue(SBFTerrainConversion.damage(game, aim, 1));
        assertTrue(game.getHex(aim).containsTerrain(Terrains.ROUGH));
        assertFalse(game.getHex(aim).containsTerrain(Terrains.WOODS));
        assertTrue(SBFTerrainConversion.damage(game, aim, 1400));
        assertEquals(-1, game.getHex(aim).getLevel());
        game.getHex(aim).addTerrain(new Terrain(Terrains.ICE, 1));
        game.getHex(aim).addTerrain(new Terrain(Terrains.WATER, 2));
        assertTrue(SBFTerrainConversion.damage(game, aim, 840));
        assertEquals(2, game.getHex(aim).terrainLevel(Terrains.WATER));
        assertFalse(game.getHex(aim).containsTerrain(Terrains.ICE));
        game.getHex(aim).removeTerrain(Terrains.WATER);
        game.getHex(aim).addTerrain(new Terrain(Terrains.ROAD, Terrains.ROAD_LVL_DIRT, true, 9));
        assertTrue(SBFTerrainConversion.damage(game, aim, 420));
        assertTrue(game.getHex(aim).isStrategicRoadDamaged());
        assertEquals(Terrains.ROAD_LVL_DIRT, game.getHex(aim).terrainLevel(Terrains.ROAD));
        assertEquals(9, game.getHex(aim).getTerrain(Terrains.ROAD).getExits());
    }

    @Test
    void offMapBatteryIsAcquiredOnObservedImpactAndCounterFireHasTheSameFlightTime() {
        BoardLocation origin = BoardLocation.of(new Coords(-17, 1), 0);
        battery.deployOffMapArtillery(origin);
        assertFalse(battery.isEligibleForPhase(GamePhase.MOVEMENT));
        assertFalse(game.getVisibility(game.getPlayer(2), 11).isLocated());
        assertTrue(game.isVisible(1, 11));
        assertFalse(battery.occupiesGroundHex());
        target.getUnits().getFirst().getSpecialAbilities().mergeSUA(BattleForceSUA.ARTLT, 7);
        target.getUnits().getFirst().getSpecialAbilities().mergeSUA(BattleForceSUA.ARTT, 5);
        int defenders = SBFScenarioProgress.sideId(game, game.getPlayer(2));
        var direct = new SBFDirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        var fire = new SBFIndirectArtilleryAttack(11, 22, BattleForceSUA.ARTLT, target.getPosition(), null);
        assertFalse(SBFArtillery.declare(manager, 1, direct));
        assertTrue(SBFArtillery.declare(manager, 1, fire));
        new SBFActionsProcessor(manager).handleActions();
        assertTrue(game.artillery().contacts(defenders).isEmpty());
        game.setTurns(List.of(new SBFFormationTurn(2)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
        game.setCurrentRound(6);
        game.clearActions();
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(2);
            SBFArtillery.resolveArrivals(manager);
        }
        var contact = game.artillery().contacts(defenders).getFirst();
        assertEquals(1, contact.number());
        assertEquals(0, contact.subsequentOccupiedImpacts());
        var answer = new SBFCounterBatteryAttack(22, BattleForceSUA.ARTLT, contact.number());
        assertFalse(SBFArtillery.declare(manager, 2, answer));
        game.setCurrentRound(7);
        assertFalse(SBFArtillery.declare(manager, 2, new SBFCounterBatteryAttack(22, BattleForceSUA.ARTT, 1)));
        assertTrue(SBFArtillery.declare(manager, 2, answer));
        new SBFActionsProcessor(manager).handleActions();
        assertEquals(8, game.artillery().returnFlights().getFirst().arrival());
        assertTrue(game.getPendingHits().isEmpty());
        game.setCurrentRound(8);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(10);
            SBFArtillery.resolveArrivals(manager);
        }
        assertEquals(3, game.getPendingHits().getFirst().damage());
        game.removeFormationFromPlay(battery);
        assertFalse(game.isVisible(2, 11));
    }

    @Test
    void unlocatedOnBoardBatteryTriangulationCountsOnlySubsequentOccupiedImpactsAndCapsAtFour() {
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        var origin = battery.getPosition();
        var impact = target.getPosition();
        var observer = target;
        var source = new Battery(battery.getId(), BattleForceSUA.ARTLT);
        var flight = new megamek.common.strategicBattleSystems.SBFArtilleryState.Flight(source, impact, -1, 5, 8, 4, false,
              1, false, origin);
        int defenders = SBFScenarioProgress.sideId(game, game.getPlayer(2));
        SBFCounterBattery.observeImpact(manager, battery, flight);
        assertEquals(0, game.artillery().contacts(defenders).getFirst().subsequentOccupiedImpacts());
        observer.setPosition(impact.translated(2));
        SBFCounterBattery.observeImpact(manager, battery, flight);
        assertEquals(0, game.artillery().contacts(defenders).getFirst().subsequentOccupiedImpacts());
        observer.setPosition(impact);
        for (int i = 0; i < 6; i++) {
            SBFCounterBattery.observeImpact(manager, battery, flight);
        }
        assertEquals(4, game.artillery().contacts(defenders).getFirst().subsequentOccupiedImpacts());
        game.visibilityHelper().setVisible(2, battery.getId());
        game.setCurrentRound(6);
        assertFalse(SBFCounterBattery.canFire(game, target, new SBFCounterBatteryAttack(22, BattleForceSUA.ARTLT, 1)));
    }

    @Test
    void flakNeedsLoadedClusterAndResolvesItsOwnersFlightChoiceBeforeOrdinaryCombat() {
        var shot = new SBFAirDefenseArtilleryAttack(11, 22, BattleForceSUA.ARTLT, SBFArtilleryAmmo.CLUSTER);
        target.setType(SBFElementType.AS);
        target.setRadarPosition(SBFRadarZone.CENTRAL, List.of(target.getPosition()));
        SBFUnit other = new SBFUnit();
        other.setArmor(15);
        target.addUnit(other);
        assertFalse(SBFArtillery.declare(manager, 1, shot));
        assertTrue(battery.getUnits().getFirst().loadArtillery(BattleForceSUA.ARTLT, Set.of(SBFArtilleryAmmo.CLUSTER)));
        assertTrue(SBFArtillery.declare(manager, 1, shot));
        assertEquals(8, SBFArtilleryAirDefense.targetNumber(game, battery, target, shot));
        game.addAction(new SBFAirGroundAttack(22, SBFAirGroundAttack.Type.STRIKE, List.of(11), List.of(0), List.of()));
        assertEquals(6, SBFArtilleryAirDefense.targetNumber(game, battery, target, shot));
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(9);
            dice.when(Compute::d6).thenReturn(2, 5);
            assertFalse(new SBFActionsProcessor(manager).handleActions());
        }
        assertEquals(1, game.artillery().airHits().size());
        assertFalse(SBFArtilleryAirDefense.choose(manager, 1, 1));
        assertTrue(SBFArtilleryAirDefense.choose(manager, 2, 1));
        assertEquals(10, other.getCurrentArmor());
        assertEquals(15, target.getUnits().getFirst().getCurrentArmor());
        assertTrue(game.artillery().airHits().isEmpty());
    }

    @Test
    void twoArrowAlternatesReplaceHomingAndStandardLoadout() {
        var unit = battery.getUnits().getFirst();
        unit.getSpecialAbilities().mergeSUA(BattleForceSUA.ARTAIS, 6);
        assertTrue(unit.loadArtillery(BattleForceSUA.ARTAIS, Set.of(SBFArtilleryAmmo.AIR_DEFENSE)));
        assertTrue(unit.carriesArtillery(BattleForceSUA.ARTAIS, SBFArtilleryAmmo.HOMING));
        assertTrue(unit.loadArtillery(BattleForceSUA.ARTAIS, Set.of(SBFArtilleryAmmo.AIR_DEFENSE, SBFArtilleryAmmo.SMOKE)));
        assertFalse(unit.carriesArtillery(BattleForceSUA.ARTAIS, SBFArtilleryAmmo.HOMING));
        assertEquals(0, SBFArtillery.damage(battery, BattleForceSUA.ARTAIS, SBFArtilleryAmmo.STANDARD));
        assertFalse(unit.loadArtillery(BattleForceSUA.ARTAIS, Set.of(SBFArtilleryAmmo.CLUSTER)));
    }

    @Test
    void conventionalMineTriggersTwiceAgainstSeparateUnitsAndConsumesOneDensity() {
        var second = new SBFUnit();
        second.setArmor(15);
        target.addUnit(second);
        var hex = target.getPosition();
        game.minefields().fields().put(hex, new megamek.common.strategicBattleSystems.SBFMinefields.Field(1,
              megamek.common.strategicBattleSystems.SBFMinefields.Type.CONVENTIONAL, 12, 4, false));
        SBFMinefieldControl.enter(manager, target, hex, () -> 6, () -> 6);
        assertEquals(4, game.minefields().fields().get(hex).density());
        try (var dice = mockStatic(Compute.class)) {
            SBFMinefieldControl.enter(manager, target, hex, () -> 7, () -> 6);
        }
        assertEquals(11, target.getUnits().getFirst().getCurrentArmor());
        assertEquals(13, second.getCurrentArmor());
        assertEquals(3, game.minefields().fields().get(hex).density());
    }

    @Test
    void hostileCommandEmpIgnoresEntryAndDamagesOnlySusceptibleUnitsOnDetonation() {
        var hex = target.getPosition();
        var infantry = new SBFUnit();
        infantry.setType(SBFElementType.CI);
        infantry.setArmor(10);
        target.addUnit(infantry);
        game.minefields().fields().put(hex, new megamek.common.strategicBattleSystems.SBFMinefields.Field(1,
              megamek.common.strategicBattleSystems.SBFMinefields.Type.EMP, 1, 1, true));
        SBFMinefieldControl.enter(manager, target, hex, () -> 12, () -> 6);
        assertEquals(10, infantry.getCurrentArmor());
        assertFalse(SBFMinefieldControl.detonate(manager, 2, 22, hex));
        assertTrue(SBFMinefieldControl.detonate(manager, 1, 11, hex));
        assertTrue(target.disruptedByEmp(game.getCurrentRound()));
        assertEquals(9, infantry.getCurrentArmor());
        assertFalse(game.minefields().fields().containsKey(hex));
    }

    @Test
    void illuminationRetainsWeatherAndExpiresAfterThreeTurnsAndThunderDeploysRealMines() {
        var unit = battery.getUnits().getFirst();
        unit.getSpecialAbilities().mergeSUA(BattleForceSUA.ARTAIS, 6);
        assertTrue(unit.loadArtillery(BattleForceSUA.ARTAIS, Set.of(SBFArtilleryAmmo.ILLUMINATION, SBFArtilleryAmmo.THUNDER_ACTIVE)));
        game.getPlanetaryConditions().setLight(megamek.common.planetaryConditions.Light.PITCH_BLACK);
        var impact = target.getPosition();
        int side = SBFScenarioProgress.sideId(game, game.getPlayer(1));
        game.artillery().setScenarioPreplot(side, impact);
        var flare = new SBFIndirectArtilleryAttack(11, -1, BattleForceSUA.ARTAIS, impact, null);
        flare.setAmmo(SBFArtilleryAmmo.ILLUMINATION);
        when(manager.getPacketHelper()).thenReturn(mock(megamek.server.GameManagerPacketHelper.class));
        assertEquals(0, SBFDetectionHelper.visualRange(game, battery, impact));
        new SBFArtillery(flare, manager).handle();
        SBFArtillery.resolveArrivals(manager);
        assertEquals(4, SBFDetectionHelper.visualRange(game, battery, impact));
        var mines = new SBFIndirectArtilleryAttack(11, -1, BattleForceSUA.ARTAIS, impact, null);
        mines.setAmmo(SBFArtilleryAmmo.THUNDER_ACTIVE);
        new SBFArtillery(mines, manager).handle();
        SBFArtillery.resolveArrivals(manager);
        assertEquals(2, game.minefields().fields().get(impact).density());
        assertEquals(1, game.minefields().fields().get(impact).size());
        assertEquals(SBFMinefields.Type.ACTIVE, game.minefields().fields().get(impact).type());
        game.setCurrentRound(7);
        assertEquals(4, SBFDetectionHelper.visualRange(game, battery, impact));
        game.setCurrentRound(8);
        assertEquals(0, SBFDetectionHelper.visualRange(game, battery, impact));
    }

    @Test
    void conventionalThunderStartsAtSizeOneAndPreservesAnExistingLargerField() {
        var impact = target.getPosition();
        SBFMinefieldControl.thunder(game, impact, 1, false);
        assertEquals(new SBFMinefields.Field(1, SBFMinefields.Type.CONVENTIONAL, 1, 2, false),
              game.minefields().fields().get(impact));
        game.minefields().fields().put(impact, new SBFMinefields.Field(1, SBFMinefields.Type.CONVENTIONAL, 4, 1, false));
        SBFMinefieldControl.thunder(game, impact, 1, true);
        assertEquals(new SBFMinefields.Field(1, SBFMinefields.Type.ACTIVE, 4, 2, false),
              game.minefields().fields().get(impact));
    }

    @Test
    void emptyMapEffectsStayAbsentInTheSetupRound() {
        game.setCurrentRound(-1);
        assertFalse(game.minefields().inEmp(target.getPosition(), -1));
        assertTrue(game.minefields().canDetonate(battery.getId(), -1));
        assertFalse(game.isIlluminated(target.getPosition()));
        assertFalse(game.getHex(target.getPosition()).containsTerrain(Terrains.SMOKE));
        assertFalse(game.getHex(target.getPosition()).hasStrategicInferno());
        game.minefields().igniteEmp(target.getPosition(), -1);
        assertTrue(game.minefields().inEmp(target.getPosition(), -1));
        assertFalse(game.minefields().inEmp(target.getPosition(), 0));
    }

    @Test
    void sawThreeFullyClearsLightWoodsOnTheSixteenthCombatTurn() {
        var site = battery.getPosition();
        game.getHex(site).addTerrain(new Terrain(Terrains.WOODS, 1));
        game.getHex(site).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        var unit = battery.getUnits().getFirst();
        unit.getSpecialAbilities().replaceSUA(BattleForceSUA.SAW, 3);
        battery.getSpecialAbilities().setSUA(BattleForceSUA.SAW);
        when(manager.getPacketHelper()).thenReturn(mock(megamek.server.GameManagerPacketHelper.class));
        for (int turn = 1; turn <= 16; turn++) {
            game.setCurrentRound(turn);
            game.clearActions();
            battery.setDone(false);
            var order = new megamek.common.actions.sbf.SBFTerrainAttack(battery.getId(), site, 0);
            assertFalse(SBFTerrainAttackHandler.declare(manager, 2, order));
            assertTrue(SBFTerrainAttackHandler.declare(manager, 1, order));
            assertFalse(SBFTerrainAttackHandler.declare(manager, 1, order));
            new SBFActionsProcessor(manager).handleActions();
            assertEquals(turn < 16, game.getHex(site).containsTerrain(Terrains.WOODS));
        }
        assertTrue(game.getHex(site).containsTerrain(Terrains.ROUGH));
        assertFalse(game.getHex(site).containsTerrain(Terrains.FOLIAGE_ELEV));
        assertFalse(game.getHex(site).hasPavementOrRoad());
        assertEquals(0, game.artillery().terrainDamage(site));
    }

    @Test
    void twoTurnsOfEngThreeOpenOnlyTheChosenPassageAndPreserveJungleCover() {
        var site = battery.getPosition();
        game.getHex(site).addTerrain(new Terrain(Terrains.JUNGLE, 2));
        game.getHex(site).addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        int passage = megamek.common.Hex.strategicPassage(0, 3);
        battery.getUnits().getFirst().getSpecialAbilities().replaceSUA(BattleForceSUA.ENG, 3);
        assertFalse(SBFTerrainAttackHandler.declare(manager, 1,
              new megamek.common.actions.sbf.SBFTerrainAttack(battery.getId(), site, passage)));
        battery.getSpecialAbilities().setSUA(BattleForceSUA.ENG);
        when(manager.getPacketHelper()).thenReturn(mock(megamek.server.GameManagerPacketHelper.class));
        for (int turn = 1; turn <= 2; turn++) {
            var order = new megamek.common.actions.sbf.SBFTerrainAttack(battery.getId(), site, passage);
            assertTrue(SBFTerrainAttackHandler.declare(manager, 1, order));
            new SBFActionsProcessor(manager).handleActions();
            assertEquals(turn == 2, game.getHex(site).hasStrategicPassage(passage));
            game.clearActions();
            battery.setDone(false);
            game.incrementCurrentRound();
        }
        var hex = game.getHex(site);
        assertEquals(2, hex.terrainLevel(Terrains.JUNGLE));
        assertEquals(2, hex.terrainLevel(Terrains.FOLIAGE_ELEV));
        assertFalse(hex.hasPavementOrRoad());
        assertEquals(Set.of(passage), hex.duplicate().strategicPassages());
        assertFalse(SBFTerrainConversion.canClear(hex, passage));
        assertEquals(0, game.artillery().terrainDamage(site));
        assertTrue(SBFTerrainConversion.damage(game, site, 1750));
        assertTrue(game.getHex(site).strategicPassages().isEmpty());
    }

    @Test
    void formationCanRazeAnUnoccupiedWoodThroughNormalDeclarations() {
        var location = BoardLocation.of(new Coords(2, 2), 0);
        game.getHex(location).addTerrain(new Terrain(Terrains.WOODS, 1));
        battery.getUnits().getFirst().setDamage(megamek.common.alphaStrike.ASDamageVector.createNormRndDmg(2, 2, 2));
        var shot = new megamek.common.actions.sbf.SBFTerrainAttack(battery.getId(), location);
        assertFalse(SBFTerrainAttackHandler.declare(manager, 2, shot));
        assertTrue(SBFTerrainAttackHandler.declare(manager, 1, shot));
        assertTrue(battery.isDone());
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(12);
            new SBFActionsProcessor(manager).handleActions();
        }
        assertEquals(2, game.artillery().terrainDamage(location));
    }

    @Test
    void destroyedFiringUnitCannotTransferItsDeclarationToTheNextSurvivor() {
        var second = new SBFUnit();
        second.setArmor(15);
        second.setDamage(megamek.common.alphaStrike.ASDamageVector.createNormRndDmg(5, 5, 5));
        battery.addUnit(second);
        var fromLost = new SBFStandardUnitAttackHandler(new SBFStandardUnitAttack(11, 0, 22, ASRange.LONG), manager);
        var fromSurvivor = new SBFStandardUnitAttackHandler(new SBFStandardUnitAttack(11, 1, 22, ASRange.LONG), manager);
        battery.getUnits().getFirst().setCurrentArmor(0);
        SBFCombatDamage.removeDestroyedUnits(manager, battery);
        fromLost.handle();
        assertTrue(game.getPendingHits().isEmpty());
        var roll = mock(Roll.class);
        when(roll.getIntValue()).thenReturn(12);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.rollD6(2)).thenReturn(roll);
            fromSurvivor.handle();
        }
        assertEquals(5, game.getPendingHits().getFirst().damage());
    }

    @Test
    void ordinaryAndCruiseFlightTimeUseDifferentPrintedBoundaries() {
        assertEquals(0, SBFArtillery.travelTurns(BattleForceSUA.ARTLT, 18));
        assertEquals(1, SBFArtillery.travelTurns(BattleForceSUA.ARTLT, 19));
        assertEquals(1, SBFArtillery.travelTurns(BattleForceSUA.ARTLT, 34));
        assertEquals(2, SBFArtillery.travelTurns(BattleForceSUA.ARTLT, 35));
        assertEquals(0, SBFArtillery.travelTurns(BattleForceSUA.ARTCM5, 34));
        assertEquals(1, SBFArtillery.travelTurns(BattleForceSUA.ARTCM7, 35));
        assertEquals(2, SBFArtillery.travelTurns(BattleForceSUA.ARTCM7, 69));
    }
}
