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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.actions.sbf.SBFAirGroundAttack;
import megamek.common.actions.sbf.SBFAirGroundAttack.Release;
import megamek.common.actions.sbf.SBFAirGroundAttack.Type;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFCombatOrders;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.AirResponse;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.RadarFlight;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFAerospaceTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final SBFAerospace aerospace = new SBFAerospace(manager);
    private final SBFFormation squadron = new SBFFormation();
    private final BoardLocation ground = BoardLocation.of(new Coords(1, 1), 0);

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        Board board = new Board(4, 4);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                board.setHex(x, y, new Hex());
            }
        }
        game.setBoard(0, board);
        Player player = new Player(1, "Owner");
        player.setStartingPos(Board.START_N);
        game.addPlayer(1, player);
        Player opponent = new Player(2, "Opponent");
        opponent.setStartingPos(Board.START_E);
        game.addPlayer(2, opponent);
        squadron.setType(SBFElementType.AS);
        squadron.setMovement(11);
        squadron.setMovementMode(SBFMovementMode.AERODYNE);
        squadron.setSkill(2);
        squadron.setMorale(7);
        squadron.addUnit(unit(SBFElementType.AS, SBFMovementMode.AERODYNE));
        squadron.setOwnerId(1);
        squadron.setId(11);
        game.addUnit(squadron);
        game.setCurrentRound(0);
        game.setTurns(List.of(new SBFFormationTurn(1)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
    }

    @Test
    void deployAtHomeWithOptionalBombLoadAndFlyThroughProperZones() {
        game.setPhase(GamePhase.DEPLOYMENT);
        squadron.getSpecialAbilities().mergeSUA(BattleForceSUA.BOMB, 2);
        assertFalse(aerospace.deploy(2, squadron, SBFRadarZone.OUTER_12, 1, 0));
        assertFalse(aerospace.deploy(1, squadron, SBFRadarZone.OUTER_6, 1, 0));
        assertFalse(aerospace.deploy(1, squadron, SBFRadarZone.OUTER_12, 3, 0));
        assertTrue(aerospace.deploy(1, squadron, SBFRadarZone.OUTER_12, 1, 0));
        assertEquals(10, squadron.getCurrentThrust());
        assertNull(squadron.getPosition());

        game.setPhase(GamePhase.MOVEMENT);
        assertFalse(aerospace.move(1, squadron, flight(SBFRadarZone.CENTRAL)));
        assertFalse(aerospace.move(1, squadron,
              new RadarFlight(List.of(SBFRadarZone.A, SBFRadarZone.INNER, SBFRadarZone.CENTRAL), List.of(ground), false)));
        assertTrue(aerospace.move(1, squadron, flight(SBFRadarZone.A, SBFRadarZone.INNER)));
        assertEquals(SBFRadarZone.INNER, squadron.getRadarZone());
        assertEquals(2, squadron.getMpSpentThisTurn());
        assertTrue(squadron.isDone());
    }

    @Test
    void centralRequiresARealGroundPathAndDoesNotUseGroundStacking() {
        game.setPhase(GamePhase.MOVEMENT);
        squadron.setDeployed(true);
        squadron.setRadarPosition(SBFRadarZone.INNER, List.of());
        assertFalse(aerospace.move(1, squadron, flight(SBFRadarZone.CENTRAL)));
        assertFalse(aerospace.move(1, squadron, new RadarFlight(List.of(SBFRadarZone.CENTRAL),
              List.of(ground, BoardLocation.of(new Coords(8, 8), 0)), false)));
        assertTrue(aerospace.move(1, squadron, new RadarFlight(List.of(SBFRadarZone.CENTRAL), List.of(ground), false)));
        assertEquals(ground, squadron.getPosition());
        assertTrue(game.getActiveFormationsAt(ground).isEmpty());
        squadron.setDone(false);
        assertFalse(new SBFMovementProcessor(manager).processMovement(new SBFMovePath(11, ground, game), squadron));
    }

    @Test
    void centralCapacityCountsAnEarlierDepartureAndResetsTheNextRound() {
        for (int id = 21; id <= 23; id++) {
            SBFFormation friendly = new SBFFormation();
            friendly.setId(id);
            friendly.setOwnerId(1);
            friendly.setRadarPosition(SBFRadarZone.CENTRAL, List.of(ground));
            game.addUnit(friendly);
        }
        assertFalse(game.canEnterCentral(squadron));
        game.getFormation(21).orElseThrow().setRadarPosition(SBFRadarZone.INNER, List.of());
        assertFalse(game.canEnterCentral(squadron));
        game.setCurrentRound(1);
        assertTrue(game.canEnterCentral(squadron));
    }

    @Test
    void brokenGroundedSquadronLiftsOffThenMustFlyHomeAtItsBestSpeed() {
        game.setPhase(GamePhase.MOVEMENT);
        squadron.setDeployed(true);
        squadron.setPosition(ground);
        squadron.setMoraleStatus(SBFFormation.MoraleStatus.BROKEN);
        assertTrue(new SBFMovementProcessor(manager).processMovement(new SBFMovePath(11, ground, game), squadron));
        assertEquals(SBFRadarZone.CENTRAL, squadron.getRadarZone());
        assertEquals(List.of(ground), squadron.getGroundFlightPath());
        squadron.setDone(false);
        assertFalse(aerospace.move(1, squadron, flight(SBFRadarZone.INNER)));
        assertTrue(aerospace.move(1, squadron, SBFAerospace.withdrawalFlight(squadron)));
        assertEquals(SBFRadarZone.OUTER_12, squadron.getRadarZone());
        squadron.setDone(false);
        assertTrue(aerospace.move(1, squadron, SBFAerospace.withdrawalFlight(squadron)));
        assertTrue(game.getFormation(11).isEmpty());
        assertTrue(squadron.hasWithdrawn());
        assertEquals(LossCause.FORCED_WITHDRAWAL, game.scenarioProgress().losses().getFirst().cause());
    }

    @Test
    void zeroThrustCrashesAtEndAndCannotUseAZoneMove() {
        squadron.setRadarPosition(SBFRadarZone.A, List.of());
        squadron.setMovement(0);
        assertFalse(SBFAerospace.validFlight(game, squadron, flight(SBFRadarZone.INNER)));
        assertTrue(SBFAerospace.validFlight(game, squadron, flight()));
        aerospace.removeCrashedSquadrons();
        assertTrue(game.getFormation(11).isEmpty());
        assertFalse(squadron.hasWithdrawn());
        assertEquals(LossCause.CRASH, game.scenarioProgress().losses().getFirst().cause());
    }

    @Test
    void crashOfCommanderTriggersSurvivingForceMoraleInTheSameEndPhase() {
        squadron.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.COM);
        squadron.setRadarPosition(SBFRadarZone.A, List.of());
        squadron.setMovement(0);
        SBFFormation ally = enemy(22, ground);
        ally.setOwnerId(1);
        ally.setMorale(9);
        game.setPhase(GamePhase.END);
        var losses = aerospace.removeCrashedSquadrons();
        assertTrue(losses.getFirst().commanderLost());
        new SBFMoraleResolver(manager, () -> 2).resolve(losses);
        assertTrue(ally.isShaken());
    }

    @Test
    void standardRadarExcludesLargeAerospaceAndOpposingZonesOverrideNonOppositeGroundEdges() {
        game.setPhase(GamePhase.DEPLOYMENT);
        squadron.setType(SBFElementType.LA);
        assertFalse(aerospace.deploy(1, squadron, SBFRadarZone.OUTER_12, 0, 0));
        squadron.setType(SBFElementType.AS);
        assertTrue(aerospace.deploy(1, squadron, SBFRadarZone.OUTER_12, 0, 0));
        SBFFormation enemy = enemy(22, ground);
        enemy.setType(SBFElementType.AS);
        enemy.setDeployed(false);
        game.setTurns(List.of(new SBFFormationTurn(2)));
        assertFalse(aerospace.deploy(2, enemy, SBFRadarZone.OUTER_3, 0, 0));
        assertTrue(aerospace.deploy(2, enemy, SBFRadarZone.OUTER_6, 0, 0));
    }

    @Test
    void centralTransitDeclaresItsContingencyGroundPathBeforeAnyContact() {
        game.setPhase(GamePhase.MOVEMENT);
        squadron.setDeployed(true);
        squadron.setRadarPosition(SBFRadarZone.INNER, List.of());
        List<SBFRadarZone> transit = List.of(SBFRadarZone.CENTRAL, SBFRadarZone.INNER);
        assertFalse(aerospace.move(1, squadron, new RadarFlight(transit, List.of(), false)));
        assertTrue(aerospace.move(1, squadron, new RadarFlight(transit, List.of(ground), false)));
        assertEquals(SBFRadarZone.INNER, squadron.getRadarZone());
        assertTrue(squadron.getGroundFlightPath().isEmpty());
    }

    @Test
    void losingMoverMayPassDuringContactAndCannotReturnFireAtThatOpponentThisRound() {
        game.setPhase(GamePhase.MOVEMENT);
        squadron.setDeployed(true);
        squadron.setRadarPosition(SBFRadarZone.A, List.of());
        SBFFormation interceptor = enemy(22, ground);
        interceptor.setType(SBFElementType.AS);
        interceptor.setRadarPosition(SBFRadarZone.INNER, List.of());
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.engagementOrders().order(11, 22, false);
        game.engagementOrders().order(22, 11, true);
        SBFEngagementControl engagement = new SBFEngagementControl(manager);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(3, 11);
            assertTrue(aerospace.move(1, squadron, flight(SBFRadarZone.INNER, SBFRadarZone.B)));
            assertEquals(11, game.engagementOrders().airDefense().defender());
            assertFalse(engagement.airResponse(2, 11, 22, AirResponse.RETURN));
            assertTrue(engagement.airResponse(1, 11, 22, AirResponse.PASS));
        }
        assertEquals(SBFRadarZone.B, squadron.getRadarZone());
        assertTrue(interceptor.isEngagedWith(11));
        assertTrue(interceptor.mayAttackAirOpponent(11));
        assertFalse(squadron.mayAttackAirOpponent(22));
        assertFalse(engagement.airResponse(1, 11, 22, AirResponse.RETURN));
        assertTrue(SBFCombatOrders.sameEngagementArea(game, squadron, interceptor));
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, game.visibilityHelper().getObservedVisibility(1, 22));
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, game.visibilityHelper().getObservedVisibility(2, 11));
    }

    @Test
    void combatDamageToGroundedAndAirborneSquadronsCanForceWithdrawal() {
        game.getOptions().getOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL).setValue(true);
        squadron.setDeployed(true);
        squadron.setPosition(ground);
        SBFFormation second = enemy(22, ground);
        second.setRadarPosition(SBFRadarZone.A, List.of());
        second.setType(SBFElementType.AS);
        second.setMovement(6);
        game.setPhase(GamePhase.FIRING);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(7);
            SBFCombatDamage.recordHit(manager, squadron, second, 0, 17);
            SBFCombatDamage.recordHit(manager, second, squadron, 0, 17);
            game.setPhase(GamePhase.END);
            new SBFMoraleResolver(manager, () -> 2).resolve(SBFCombatDamage.applyEndPhase(manager));
        }
        assertTrue(squadron.mustWithdraw());
        assertTrue(second.mustWithdraw());
        assertTrue(squadron.isShaken());
        game.setPhase(GamePhase.MOVEMENT);
        assertTrue(new SBFMovementProcessor(manager).processMovement(new SBFMovePath(11, ground, game), squadron));
        assertTrue(squadron.isOnRadarMap());
    }

    @Test
    void strafeRollsEachFlightAgainstEachOverflownTargetAndAppliesAtEnd() {
        BoardLocation next = BoardLocation.of(new Coords(2, 1), 0);
        airborneFiring(List.of(ground, next));
        SBFFormation first = enemy(21, ground);
        SBFFormation second = enemy(22, next);
        SBFFormation offPath = enemy(23, BoardLocation.of(new Coords(0, 0), 0));
        SBFAirGroundAttack strafe = new SBFAirGroundAttack(11, Type.STRAFE, List.of(21, 22), List.of(), List.of());
        assertFalse(SBFAirGroundRun.declare(manager, 2, strafe));
        assertFalse(SBFAirGroundRun.declare(manager, 1,
              new SBFAirGroundAttack(11, Type.STRIKE, List.of(offPath.getId()), List.of(), List.of())));
        assertTrue(SBFAirGroundRun.declare(manager, 1, strafe));
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(9, 9);
            resolveRun();
        }
        assertEquals(List.of(2, 2), game.getPendingHits().stream().map(SBFGame.PendingHit::damage).toList());
        assertEquals(20, first.getUnits().getFirst().getCurrentArmor());
        SBFCombatDamage.applyEndPhase(manager);
        assertEquals(18, first.getUnits().getFirst().getCurrentArmor());
        assertEquals(18, second.getUnits().getFirst().getCurrentArmor());
    }

    @Test
    void strikeStillUsesTheDeclaredFlightWhenFlakRemovesAPreviousFlight() {
        airborneFiring(List.of(ground));
        enemy(21, ground);
        var lost = squadron.getUnits().getFirst();
        var chosen = unit(SBFElementType.AS, SBFMovementMode.AERODYNE);
        chosen.setDamage(ASDamageVector.create(4, 3, 2, 0, 3));
        squadron.addUnit(chosen);
        var strike = new SBFAirGroundAttack(11, Type.STRIKE, List.of(21), List.of(1), List.of());
        assertTrue(SBFAirGroundRun.declare(manager, 1, strike));
        var handler = ((SBFAirGroundAttack) game.getActionsVector().getFirst()).getHandler(manager);
        squadron.removeUnit(lost);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(12);
            handler.handle();
        }
        assertEquals(4, game.getPendingHits().getFirst().damage());
    }

    @Test
    void altitudeBombingConsumesOnlyAcceptedLoadsAndCanHitAFriendlyOnAMiss() {
        BoardLocation next = BoardLocation.of(new Coords(2, 1), 0);
        airborneFiring(List.of(ground, next));
        squadron.getSpecialAbilities().mergeSUA(BattleForceSUA.BOMB, 4);
        squadron.setBombLoad(2, 2);
        enemy(21, ground);
        SBFFormation friendly = enemy(22, next);
        friendly.setOwnerId(1);
        Release he = new Release(ground, 2, 0);
        assertFalse(SBFAirGroundRun.declare(manager, 1,
              new SBFAirGroundAttack(11, Type.ALTITUDE, List.of(), List.of(), List.of(he))));
        assertEquals(2, squadron.getHighExplosiveBombs());
        assertTrue(SBFAirGroundRun.declare(manager, 1, new SBFAirGroundAttack(11, Type.ALTITUDE,
              List.of(), List.of(), List.of(he, new Release(next, 0, 2)))));
        assertEquals(0, squadron.getHighExplosiveBombs());
        assertEquals(0, squadron.getClusterBombs());
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(9, 2);
            dice.when(Compute::d6).thenReturn(6);
            resolveRun();
        }
        assertEquals(List.of(21, 22), game.getPendingHits().stream().map(SBFGame.PendingHit::targetId).toList());
        assertEquals(List.of(4, 2), game.getPendingHits().stream().map(SBFGame.PendingHit::damage).toList());
    }

    @Test
    void hoveringBattleArmorCanDiveBombItsHexAndRollsOncePerFiveBombs() {
        game.setPhase(GamePhase.DEPLOYMENT);
        squadron.setType(SBFElementType.BA);
        squadron.setMovementMode(SBFMovementMode.VTOL);
        squadron.getSpecialAbilities().mergeSUA(BattleForceSUA.BOMB, 6);
        assertFalse(aerospace.loadBattleArmor(2, squadron, 5, 1));
        assertTrue(aerospace.loadBattleArmor(1, squadron, 5, 1));
        squadron.setDeployed(true);
        squadron.setPosition(ground);
        assertFalse(aerospace.loadBattleArmor(1, squadron, 4, 2));
        game.setPhase(GamePhase.FIRING);
        enemy(21, ground);
        List<Release> release = List.of(new Release(ground, 5, 1));
        assertFalse(SBFAirGroundRun.declare(manager, 1, new SBFAirGroundAttack(11, Type.ALTITUDE,
              List.of(), List.of(), release)));
        assertTrue(SBFAirGroundRun.declare(manager, 1, new SBFAirGroundAttack(11, Type.DIVE,
              List.of(21), List.of(0), release)));
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(9, 3);
            resolveRun();
        }
        assertEquals(List.of(10, 1), game.getPendingHits().stream().map(SBFGame.PendingHit::damage).toList());
    }

    @Test
    void antiAircraftRangeUsesClosestPartOfFlightPathThenFinalGroundHex() {
        airborneFiring(List.of(ground, BoardLocation.of(new Coords(2, 1), 0), BoardLocation.of(new Coords(3, 1), 0)));
        SBFFormation battery = enemy(21, BoardLocation.of(new Coords(0, 1), 0));
        assertEquals(ASRange.SHORT, SBFToHitData.standardAttackRange(game, battery, squadron).orElseThrow());
        squadron.setRadarPosition(SBFRadarZone.CENTRAL, List.of(BoardLocation.of(new Coords(2, 1), 0)));
        assertEquals(ASRange.MEDIUM, SBFToHitData.standardAttackRange(game, battery, squadron).orElseThrow());
        squadron.setRadarPosition(SBFRadarZone.INNER, List.of());
        assertTrue(SBFToHitData.standardAttackRange(game, battery, squadron).isEmpty());
    }

    private void airborneFiring(List<BoardLocation> path) {
        game.setPhase(GamePhase.FIRING);
        squadron.setDeployed(true);
        squadron.setRadarPosition(SBFRadarZone.CENTRAL, path);
    }

    private void resolveRun() {
        var action = (SBFAirGroundAttack) game.getActionsVector().getFirst();
        action.getHandler(manager).handle();
    }

    private SBFFormation enemy(int id, BoardLocation location) {
        SBFFormation formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(2);
        formation.setType(SBFElementType.BM);
        formation.setMovementMode(SBFMovementMode.MEK_WALK);
        formation.setMovement(4);
        formation.setDeployed(true);
        formation.setPosition(location);
        formation.addUnit(unit(SBFElementType.BM, SBFMovementMode.MEK_WALK));
        game.addUnit(formation);
        return formation;
    }

    private SBFUnit unit(SBFElementType type, SBFMovementMode mode) {
        SBFUnit unit = new SBFUnit();
        unit.setType(type);
        unit.setMovementMode(mode);
        unit.setMovement(5);
        unit.setArmor(20);
        unit.setDamage(ASDamageVector.create(7, 4, 2, 0, 3));
        return unit;
    }

    private RadarFlight flight(SBFRadarZone... zones) {
        return new RadarFlight(List.of(zones), List.of(), false);
    }
}
