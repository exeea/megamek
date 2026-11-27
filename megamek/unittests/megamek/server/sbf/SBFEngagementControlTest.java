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
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SBFEngagementControlTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private SBFFormation mover;
    private SBFFormation defender;
    private SBFMovePath route;

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        game.setPhase(GamePhase.MOVEMENT);
        Board board = new Board(1, 3);
        for (int y = 0; y < 3; y++) {
            board.setHex(0, y, new Hex());
        }
        game.setBoard(0, board);
        mover = formation(1, 0);
        defender = formation(2, 1);
        route = new SBFMovePath(mover.getId(), hex(0), game);
        route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, mover.getId(), hex(0), hex(1)));
        route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, mover.getId(), hex(1), hex(2)));
    }

    @Test
    void pendingContactRequiresBothOwnersAndKeepsTheSubmittedRoute() {
        SBFEngagementControl control = rolls();
        assertFalse(route.isIllegal());
        assertNull(control.resolveMovement(mover, route));
        assertEquals(route, game.engagementOrders().pendingMove());
        control.submitOrder(2, mover.getId(), defender.getId(), true);
        assertNull(game.engagementOrders().intent(mover.getId(), defender.getId()));
        control.submitOrder(1, mover.getId(), defender.getId(), false);
        control.submitOrder(2, defender.getId(), mover.getId(), false);
        assertEquals(route, control.resolveMovement(mover, route));
        assertTrue(game.engagementOrders().alreadyDeclined(mover.getId(), defender.getId()));
    }

    @Test
    void complementaryChatOrdersResumeOneSubmittedMoveAndAdvanceTheTurn() {
        SBFGameManager liveManager = new SBFGameManager();
        liveManager.setGame(game);
        game.setTurns(List.of(new SBFFormationTurn(1), new SBFFormationTurn(2)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
        try (var server = mockStatic(Server.class)) {
            server.when(Server::getServerInstance).thenReturn(mock(Server.class));
            assertTrue(liveManager.movementProcessor.processMovement(route, mover));
            assertEquals(hex(0), mover.getPosition());
            assertFalse(mover.isDone());

            SBFEngageCommand command = new SBFEngageCommand(Server.getServerInstance(), liveManager);
            command.run(1, new String[] { "sbfengage", "10", "avoid", "20" });
            assertEquals(hex(0), mover.getPosition());
            command.run(2, new String[] { "sbfengage", "20", "avoid", "10" });
            assertEquals(hex(2), mover.getPosition());
            assertTrue(mover.isDone());
            assertNull(game.engagementOrders().pendingMove());
            assertEquals(2, game.getTurn().playerId());
        }
    }

    @Test
    void zoneOfControlLowersTheTargetWhenEstablishingEngagementControl() {
        // Zone of Control, IO:BF p. 226: "-1 Target Number modifier to all Engagement Control Rolls when Establishing
        // Engagement Control during the Movement Phase"; the ability is carried by a surviving commander Unit.
        var commander = new SBFUnit();
        commander.setArmor(4);
        commander.setCurrentArmor(4);
        commander.getSpecialAbilities().setSUA(megamek.common.alphaStrike.BattleForceSUA.COM);
        commander.getInitiativeAbilities().add(SBFUnit.InitiativeAbility.ZONE_OF_CONTROL);
        mover.addUnit(commander);
        orders(false, true);
        rolls(5, 5, 5, 4).resolveMovement(mover, route);
        verify(manager).sendServerChat(eq(1), contains("10 rolled 5 against 3"));
    }

    @Test
    void opposedRollCanStopTheMoverMidRouteAndLockBothFormations() {
        orders(false, true);
        SBFMovePath actual = rolls(2, 6).resolveMovement(mover, route);
        assertEquals(hex(1), actual.getLastPosition());
        assertEquals(1, actual.getSteps().size());
        assertTrue(mover.isEngagedWith(defender.getId()));
        assertTrue(defender.isEngagedWith(mover.getId()));
        assertTrue(defender.isDone());
        assertFalse(mover.isEligibleForPhase(GamePhase.MOVEMENT));
    }

    @ParameterizedTest
    @CsvSource({ "SHAKEN,5", "BROKEN,6", "ROUTED,7" })
    void moraleModifiesTheOpposedTargetEvenWhenRestrictedToAvoid(SBFFormation.MoraleStatus status, int tn) {
        mover.setMoraleStatus(status);
        defender.setTactics(5);
        orders(false, true);
        assertEquals(hex(1), rolls(5, 6).resolveMovement(mover, route).getLastPosition());
        verify(manager).sendServerChat(eq(1), contains("10 rolled 5 against " + tn));
    }

    @Test
    void tiedMarginsUseLowerTacticsAndEqualTacticsReroll() {
        orders(false, true);
        defender.setTactics(5);
        assertEquals(hex(2), rolls(6, 7).resolveMovement(mover, route).getLastPosition());
        game.engagementOrders().startRound(100);
        defender.setTactics(4);
        assertEquals(hex(1), rolls(6, 6, 3, 8).resolveMovement(mover, route).getLastPosition());
    }

    @Test
    void winnerCanDecideAfterSeeingDiceWithoutRollingAgain() {
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.ASK);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);
        SBFEngagementControl control = rolls(9, 4);
        assertNull(control.resolveMovement(mover, route));
        assertEquals(mover.getId(), game.engagementOrders().decisionWinner());
        control.submitOrder(1, mover.getId(), defender.getId(), false);
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertFalse(mover.isEngagedWith(defender.getId()));
    }

    @Test
    void forcingRequiresLongDamageAndAppliesItsModifierAndDamageEffects() {
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.FORCE);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.AVOID);
        assertNull(rolls().resolveMovement(mover, route));
        SBFUnit unit = new SBFUnit();
        unit.setDamage(ASDamageVector.create(2, 2, 1, 0, 3));
        unit.setArmor(2);
        mover.addUnit(unit);
        assertEquals(hex(1), rolls(3, 4).resolveMovement(mover, route).getLastPosition());
        assertEquals(2, mover.adjustEngagementDamageAgainst(defender, 5));
        assertEquals(2, defender.adjustEngagementDamageAgainst(mover, 5));
        assertFalse(mover.canFireArtilleryAfterEngagement());
    }

    @Test
    void overrunAppliesBothDamageTotalsImmediatelyAndContinuesAfterWinning() {
        SBFUnit attackingUnit = combatUnit(mover, 8, 0);
        SBFUnit defendingUnit = combatUnit(defender, 0, 6);
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.OVERRUN);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);

        assertEquals(hex(2), rolls(9, 5).resolveMovement(mover, route).getLastPosition());
        assertEquals(17, attackingUnit.getCurrentArmor());
        assertEquals(18, defendingUnit.getCurrentArmor());
        assertTrue(mover.hasOverrunThisTurn());
        assertFalse(mover.canAttackAfterEngagement());
        assertTrue(defender.wasOverrunThisTurn());
        assertFalse(mover.isEngagedWith(defender.getId()));
    }

    @Test
    void failedEvadeStopsAndChangesBothSidesCombatModifiers() {
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.EVADE);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);
        assertEquals(hex(1), rolls(2, 6).resolveMovement(mover, route).getLastPosition());
        assertEquals(2, mover.getEngagementAttackModifier());
        assertEquals(1, mover.getEngagementDefenseModifier());
        assertEquals(4, mover.adjustEngagementDamageAgainst(defender, 9));
        assertEquals(6, defender.adjustEngagementDamageAgainst(mover, 9));
        assertFalse(mover.canFireArtilleryAfterEngagement());
    }

    @Test
    void successfulEvadeHoldsForTheDefendersFreeAttackChoiceAndDoesNotReroll() {
        combatUnit(mover, 2, 1);
        combatUnit(defender, 2, 1);
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.EVADE);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);
        SBFEngagementControl control = rolls(6, 4);
        assertNull(control.resolveMovement(mover, route));
        assertFalse(new SBFContactDamage(manager, () -> 12).select(1, defender.getId(), mover.getId(), true, 0));
        control.selectUnit(2, defender.getId(), mover.getId(), true, 0);
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertFalse(mover.canAttackAfterEngagement());
        assertTrue(defender.canAttackAfterEngagement());
    }

    @Test
    void jumpAppliesAtDeclarationWhileTheHostileContactIsStillPending() {
        mover.setJumpMove(2);
        route.setJumpUsed(2);
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, mover));
        assertNull(game.engagementOrders().intent(mover.getId(), defender.getId()));
        assertFalse(mover.isDone());
        assertEquals(2, mover.getJumpUsedThisTurn());
        mover.resetEngagementMovement();
        assertEquals(0, mover.getJumpUsedThisTurn());
    }

    @Test
    void successfulEvadeFiresOnlyTheChosenFreeShotAndAppliesDamageImmediately() {
        SBFUnit target = combatUnit(mover, 2, 1);
        combatUnit(defender, 2, 1);
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.EVADE);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);
        SBFEngagementControl control = rolls(6, 4, 12);
        assertNull(control.resolveMovement(mover, route));
        control.selectUnit(2, defender.getId(), mover.getId(), true, 1);
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertEquals(17, target.getCurrentArmor());
        assertTrue(game.getPendingHits().isEmpty());
    }

    @Test
    void submergedEvadeFreeShotHalvesUnderwaterFireBeforeThreeQuarterDamage() {
        SBFUnit target = combatUnit(mover, 2, 1);
        SBFUnit shooter = combatUnit(defender, 8, 8);
        shooter.setDamage(ASDamageVector.create(8, 8, 8, 0, 3));
        shooter.setMovementMode(SBFMovementMode.SUBMARINE);
        game.getHex(hex(1)).addTerrain(new Terrain(Terrains.WATER, 1));
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.EVADE);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.ENGAGE);
        SBFEngagementControl control = rolls(6, 4, 12);
        assertNull(control.resolveMovement(mover, route));
        control.selectUnit(2, defender.getId(), mover.getId(), true, 1);
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertEquals(17, target.getCurrentArmor());
    }

    @Test
    void specialEngagementNeedsEnoughMoveToExitIncludingHostileSurcharge() {
        mover.setMovement(2);
        combatUnit(mover, 8, 1);
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.OVERRUN);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.AVOID);
        assertNull(rolls().resolveMovement(mover, route));
        assertFalse(mover.hasOverrunThisTurn());
        assertNull(game.engagementOrders().exchange());
    }

    private SBFUnit combatUnit(SBFFormation side, int shortDamage, int mediumDamage) {
        SBFUnit unit = new SBFUnit();
        unit.setType(SBFElementType.BM);
        unit.setMovementMode(SBFMovementMode.MEK_WALK);
        unit.setMovement(5);
        unit.setArmor(20);
        unit.setDamage(ASDamageVector.create(shortDamage, mediumDamage, 4, 0, 3));
        side.addUnit(unit);
        return unit;
    }

    @Test
    void bothFailuresLetAFormationPassEvenIfBothWantToEngage() {
        orders(true, true);
        assertEquals(hex(2), rolls(2, 3).resolveMovement(mover, route).getLastPosition());
        assertFalse(mover.isEngagedWith(defender.getId()));
    }

    @Test
    void endPhaseResolvesEachPairAndHonorsASuccessfulBreakoff() {
        mover.setPosition(hex(1));
        mover.setEngagedWith(defender.getId(), true);
        defender.setEngagedWith(mover.getId(), true);
        orders(true, true);
        rolls().endEngagements();
        assertTrue(mover.isEngagedWith(defender.getId()));
        orders(false, true);
        rolls(9, 5).endEngagements();
        assertFalse(mover.isEngagedWith(defender.getId()));
        assertFalse(defender.isEngagedWith(mover.getId()));
        assertTrue(game.engagementOrders().alreadyDeclined(mover.getId(), defender.getId()));
    }

    @Test
    void endPhaseWaitsForAWinningOwnersPostRollChoice() {
        game.setPhase(GamePhase.END);
        mover.setPosition(hex(1));
        mover.setEngagedWith(defender.getId(), true);
        defender.setEngagedWith(mover.getId(), true);
        game.engagementOrders().order(mover.getId(), defender.getId(), Order.ASK);
        game.engagementOrders().order(defender.getId(), mover.getId(), Order.AVOID);
        SBFEngagementControl control = rolls(9, 4);
        control.endEngagements();
        assertTrue(game.isCurrentPhasePlayable());
        assertEquals(mover.getId(), game.engagementOrders().decisionWinner());
        control.submitOrder(1, mover.getId(), defender.getId(), false);
        assertFalse(mover.isEngagedWith(defender.getId()));
        assertFalse(game.isCurrentPhasePlayable());
    }

    @Test
    void initiativeWinnerSelectsWhichOfTwoEngagementsEndsFirst() {
        game.setPhase(GamePhase.END);
        mover.setPosition(hex(1));
        SBFFormation secondDefender = formation(3, 1);
        game.setupTeams();
        game.getTeamForPlayer(game.getPlayer(1)).getInitiative().addRoll(20, "");
        game.getTeamForPlayer(game.getPlayer(2)).getInitiative().addRoll(-20, "");
        game.getTeamForPlayer(game.getPlayer(3)).getInitiative().addRoll(-20, "");
        for (SBFFormation enemy : List.of(defender, secondDefender)) {
            mover.setEngagedWith(enemy.getId(), true);
            enemy.setEngagedWith(mover.getId(), true);
            game.engagementOrders().order(enemy.getId(), mover.getId(), Order.AVOID);
        }
        game.engagementOrders().order(mover.getId(), Player.PLAYER_NONE, Order.ASK);
        SBFEngagementControl control = rolls(9, 4, 4, 8);
        control.endEngagements();
        assertEquals(2, game.engagementOrders().remainingEnds().size());
        control.chooseEnd(2, defender.getId(), mover.getId());
        assertNull(game.engagementOrders().selectedEnd());
        control.chooseEnd(1, mover.getId(), secondDefender.getId());
        assertTrue(game.engagementOrders().selectedEnd().matches(mover.getId(), secondDefender.getId()));
        control.submitOrder(1, mover.getId(), secondDefender.getId(), false);
        assertTrue(game.engagementOrders().remainingEnds().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"2,2", "10,1"})
    void hiddenDefenderChargesDepartureOnlyIfDetectedOnEntry(int detectionRoll, int finalY) {
        mover.setMovement(2);
        mover.setSkill(4);
        defender.setHidden(true);
        var plan = new SBFMovePath(mover.getId(), hex(0), game);
        plan.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, mover.getId(), hex(0), hex(1)));
        plan.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, mover.getId(), hex(1), hex(2)));
        assertEquals(2, plan.getMpUsed());
        orders(false, false);
        SBFMovePath result = rolls(detectionRoll).resolveMovement(mover, plan);
        assertEquals(hex(finalY), result.getLastPosition());
        assertEquals(detectionRoll == 2, defender.isHidden());
    }

    @ParameterizedTest
    @CsvSource({"SHORT,3", "MEDIUM,5"})
    void ambushUsesChosenRangeAndOnlyTerrainAndCriticalsThenPenalizesThisRound(String range, int damage) {
        SBFUnit victim = combatUnit(mover, 1, 1);
        SBFUnit shooter = combatUnit(defender, 3, 5);
        defender.setSkill(4);
        defender.setHidden(true);
        mover.setTmm(4);
        game.getHex(hex(1)).addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.WOODS, 1));
        shooter.addTargetingCrit();
        SBFEngagementControl control = rolls(6);
        orders(false, false);
        assertNull(control.resolveMovement(mover, route));
        var choice = megamek.common.strategicBattleSystems.SBFEngagementOrders.Ambush.valueOf(range);
        assertFalse(control.chooseAmbush(mover.getOwnerId(), defender.getId(), choice));
        control.remind(defender.getOwnerId());
        assertTrue(control.chooseAmbush(defender.getOwnerId(), defender.getId(), choice));
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertFalse(defender.isHidden());
        assertEquals(20 - damage, victim.getCurrentArmor());
        assertEquals(3, SBFMovePath.normalMovementPoints(defender, game));
        var attack = new megamek.common.actions.sbf.SBFStandardUnitAttack(defender.getId(), 0, mover.getId(),
              megamek.common.alphaStrike.ASRange.LONG);
        int subsequent = megamek.common.strategicBattleSystems.SBFToHitData.compileToHit(game, attack).getValue();
        game.incrementCurrentRound();
        assertEquals(5, SBFMovePath.normalMovementPoints(defender, game));
        assertEquals(subsequent - 2, megamek.common.strategicBattleSystems.SBFToHitData.compileToHit(game, attack).getValue());
    }

    @Test
    void submergedAmbusherCanChooseMediumForHalfDamage() {
        SBFUnit victim = combatUnit(mover, 1, 1);
        combatUnit(defender, 4, 8);
        defender.setSkill(4);
        defender.setHidden(true);
        game.getHex(hex(1)).addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.WATER, 1));
        orders(false, false);
        SBFEngagementControl control = rolls(6);
        assertNull(control.resolveMovement(mover, route));
        assertFalse(control.chooseAmbush(defender.getOwnerId(), defender.getId(),
              megamek.common.strategicBattleSystems.SBFEngagementOrders.Ambush.SHORT));
        assertTrue(control.chooseAmbush(defender.getOwnerId(), defender.getId(),
              megamek.common.strategicBattleSystems.SBFEngagementOrders.Ambush.MEDIUM));
        control.resolveMovement(mover, route);
        assertEquals(16, victim.getCurrentArmor());
    }

    @Test
    void decliningAmbushKeepsDefenderHiddenWhenDetectionFails() {
        combatUnit(mover, 1, 1);
        combatUnit(defender, 1, 1);
        mover.setSkill(4);
        defender.setHidden(true);
        orders(false, false);
        SBFEngagementControl control = rolls(6);
        assertNull(control.resolveMovement(mover, route));
        assertTrue(control.chooseAmbush(defender.getOwnerId(), defender.getId(),
              megamek.common.strategicBattleSystems.SBFEngagementOrders.Ambush.HOLD));
        assertEquals(hex(2), control.resolveMovement(mover, route).getLastPosition());
        assertTrue(defender.isHidden());
        assertFalse(defender.ambushedIn(game.getCurrentRound()));
    }

    private void orders(boolean own, boolean other) {
        game.engagementOrders().order(mover.getId(), defender.getId(), own);
        game.engagementOrders().order(defender.getId(), mover.getId(), other);
    }

    private SBFEngagementControl rolls(Integer... values) {
        Queue<Integer> dice = new ArrayDeque<>(Arrays.asList(values));
        return new SBFEngagementControl(manager, dice::remove);
    }

    private SBFFormation formation(int id, int y) {
        Player player = new Player(id, "Owner " + id);
        player.setTeam(id);
        game.addPlayer(id, player);
        SBFFormation result = new SBFFormation();
        result.setId(id * 10);
        result.setOwnerId(id);
        result.setPosition(hex(y));
        result.setMovement(5);
        result.setType(SBFElementType.BM);
        result.setMovementMode(SBFMovementMode.MEK_WALK);
        result.setTactics(4);
        result.setDeployed(true);
        game.addUnit(result);
        return result;
    }

    private BoardLocation hex(int y) {
        return BoardLocation.of(new Coords(0, y), 0);
    }
}
