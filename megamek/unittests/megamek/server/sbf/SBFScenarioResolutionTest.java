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
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.jacksonAdapters.TriggerDeserializer;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.server.trigger.TriggerSituation;
import megamek.server.victory.VictoryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFScenarioResolutionTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final BoardLocation objectiveHex = BoardLocation.of(new Coords(1, 1), 0);
    private final ObjectiveMarker marker = new ObjectiveMarker();
    private SBFFormation blue;
    private SBFFormation red;

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        game.addPlayer(1, new Player(1, "Blue"));
        game.addPlayer(2, new Player(2, "Red"));
        Board board = new Board(8, 8);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }
        game.setBoard(0, board);
        blue = formation(11, 1, objectiveHex);
        red = formation(12, 2, BoardLocation.of(new Coords(7, 7), 0));
        marker.setName("Relay");
        marker.setVictoryPointValue(5);
        game.placeGroundObject(objectiveHex.coords(), marker);
    }

    @Test
    void aSquadronCanOccupyAnObjectiveAfterLanding() {
        blue.setType(SBFElementType.AS);
        blue.setRadarPosition(SBFRadarZone.CENTRAL, List.of(objectiveHex));
        observe(1);
        assertEquals(0L, score(1));
        blue.setRadarPosition(null, List.of());
        blue.setPosition(objectiveHex);
        observe(2);
        assertEquals(25L, score(1));
        observe(3);
        assertEquals(100L, score(1));
    }

    @Test
    void oneOccupationAwardBecomesOneFullCaptureAwardIncludingFractionalPoints() {
        observe(1);
        observe(1);
        assertEquals(25L, score(1));
        assertTrue(game.getGroundObjects(objectiveHex.coords()).contains(marker));
        observe(2);
        assertEquals(100L, score(1));
        assertTrue(game.getGroundObjects(objectiveHex.coords()).isEmpty());
        observe(3);
        assertEquals(100L, score(1));
    }

    @Test
    void parsedCaptureTriggerMatchesTheNamedMarkerAndCapturingSideAfterItsCounterIsRemoved() {
        var config = JsonNodeFactory.instance.objectNode().put("type", "objectivecaptured").put("objective", "Relay");
        var eitherSide = TriggerDeserializer.parseNode(config);
        var blueSide = TriggerDeserializer.parseNode(config.put("player", "Blue"));
        var redSide = TriggerDeserializer.parseNode(config.put("player", "Red"));
        var otherMarker = TriggerDeserializer.parseNode(config.put("player", "Blue").put("objective", "Depot"));
        observe(1);
        assertFalse(eitherSide.isTriggered(game, TriggerSituation.ROUND_END));
        observe(2);
        assertTrue(eitherSide.isTriggered(game, TriggerSituation.ROUND_END));
        assertTrue(blueSide.isTriggered(game, TriggerSituation.ROUND_END));
        assertFalse(redSide.isTriggered(game, TriggerSituation.ROUND_END));
        assertFalse(otherMarker.isTriggered(game, TriggerSituation.ROUND_END));
    }

    @Test
    void returningToTheSameHexDuringMovementBreaksTheCaptureClock() {
        observe(1);
        game.setPhase(GamePhase.MOVEMENT);
        BoardLocation adjacent = BoardLocation.of(new Coords(1, 2), 0);
        SBFMovePath route = new SBFMovePath(blue.getId(), objectiveHex, game);
        route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, blue.getId(), objectiveHex, adjacent));
        route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, blue.getId(), adjacent, objectiveHex));
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, blue));
        observe(2);
        assertFalse(game.getGroundObjects(objectiveHex.coords()).isEmpty());
        observe(3);
        assertTrue(game.getGroundObjects(objectiveHex.coords()).isEmpty());
    }

    @Test
    void replacementUnitsAndShakenFormationsCannotInheritAnotherUnitsCapture() {
        observe(1);
        blue.removeUnit(blue.getUnits().getFirst());
        blue.addUnit(unit(13));
        observe(2);
        blue.setMoraleStatus(SBFFormation.MoraleStatus.SHAKEN);
        observe(3);
        blue.setMoraleStatus(SBFFormation.MoraleStatus.NORMAL);
        observe(4);
        assertFalse(game.getGroundObjects(objectiveHex.coords()).isEmpty());
        observe(5);
        assertTrue(game.getGroundObjects(objectiveHex.coords()).isEmpty());
    }

    @Test
    void simultaneousQualifyingEnemiesStayUnresolvedUntilOneRemains() {
        red.setPosition(objectiveHex);
        observe(1);
        observe(2);
        assertFalse(game.getGroundObjects(objectiveHex.coords()).isEmpty());
        red.setPosition(BoardLocation.of(new Coords(4, 5), 0));
        observe(3);
        assertEquals(100L, score(1));
        assertEquals(0L, score(2));
    }

    @Test
    void partialFormationKillRetainsItsCreditAndWithdrawalScoresOnlySurvivingUnits() {
        SBFUnit destroyed = red.getUnits().getFirst();
        red.addUnit(unit(7));
        game.getPendingHits().add(new PendingHit(blue.getId(), red.getId(), 0, 10, 0));
        SBFCombatDamage.applyEndPhase(manager);
        assertFalse(red.getUnits().contains(destroyed));
        assertEquals(52L, score(1)); // 0.2 * 13 PV = 2.6 VP.
        SBFWithdrawal.leaveMap(manager, red, true);
        assertEquals(66L, score(1)); // Plus 0.1 * 7 PV, without counting a dead Unit twice.
    }

    @Test
    void voluntaryWithdrawalEndsEliminationWithoutCreatingSuggestedVictoryPoints() {
        SBFWithdrawal.leaveMap(manager, red, false);
        assertEquals(1, SBFVictory.atRoundEnd(game).getWinningPlayer());
        game.getOptions().getOption(SBFRuleOptions.BASE_VICTORY_POINTS).setValue(true);
        assertEquals(0L, score(1));
        assertTrue(SBFVictory.atRoundEnd(game).isDraw());
    }

    @Test
    void salvageOnlyOnFinalAgreedRollsAndMissionKillAddsTwo() {
        SBFUnit missionKill = red.getUnits().getFirst();
        SBFUnit combatKill = unit(5);
        SBFUnit infantry = unit(5);
        infantry.setType(SBFElementType.CI);
        red.addUnit(combatKill);
        red.addUnit(infantry);
        game.getPendingHits().add(new PendingHit(11, 12, 0, 1, 12));
        game.getPendingHits().add(new PendingHit(11, 12, 1, 10, 0));
        game.getPendingHits().add(new PendingHit(11, 12, 2, 10, 0));
        SBFCombatDamage.applyEndPhase(manager);
        Queue<Integer> dice = new ArrayDeque<>(List.of(6, 7));
        SBFScenarioResolution.reportFinal(manager, VictoryResult.forPlayerOrTeam(game.getPlayer(1)), dice::remove);
        assertEquals(2, dice.size());
        game.getOptions().getOption(SBFRuleOptions.BASE_SALVAGE).setValue(true);
        SBFScenarioResolution.reportFinal(manager, VictoryResult.forPlayerOrTeam(game.getPlayer(1)), dice::remove);
        assertTrue(game.scenarioProgress().salvageRoll(missionKill).salvageable());
        assertFalse(game.scenarioProgress().salvageRoll(combatKill).salvageable());
        assertNull(game.scenarioProgress().salvageRoll(infantry));
        SBFScenarioResolution.reportFinal(manager, VictoryResult.forPlayerOrTeam(game.getPlayer(1)), dice::remove);
        assertTrue(dice.isEmpty());
    }

    @Test
    void withdrawalRoutAndArtilleryLossesCannotYieldRepairableSalvage() {
        List<LossCause> excluded = List.of(LossCause.ARTILLERY_OR_BOMB, LossCause.CRASH, LossCause.ROUT,
              LossCause.SCATTER, LossCause.TRANSPORT, LossCause.FORCED_WITHDRAWAL, LossCause.VOLUNTARY_WITHDRAWAL);
        for (LossCause cause : excluded) {
            game.scenarioProgress().recordLoss(game.getCurrentRound(), red, unit(1), 1, cause);
        }
        game.getOptions().getOption(SBFRuleOptions.BASE_SALVAGE).setValue(true);
        SBFScenarioResolution.reportFinal(manager, VictoryResult.drawResult(), () -> {
            throw new AssertionError("Ineligible Unit rolled salvage");
        });
    }

    private void observe(int round) {
        game.setCurrentRound(round);
        SBFScenarioResolution.updateObjectives(manager);
    }

    private long score(int player) {
        return game.scenarioProgress().scores(game, SBFScenarioResolution.sides(game)).get(player);
    }

    private SBFFormation formation(int id, int owner, BoardLocation hex) {
        SBFFormation f = new SBFFormation();
        f.setId(id);
        f.setOwnerId(owner);
        f.setType(SBFElementType.V);
        f.setName("Formation " + id);
        f.setMovement(4);
        f.setMovementMode(SBFMovementMode.TRACKED);
        f.setDeployed(true);
        f.setPosition(hex);
        f.setPointValue(13);
        f.addUnit(unit(13));
        game.addUnit(f);
        return f;
    }

    private SBFUnit unit(int pv) {
        SBFUnit u = new SBFUnit();
        u.setMovement(4);
        u.setMovementMode(SBFMovementMode.TRACKED);
        u.setType(SBFElementType.V);
        u.setPointValue(pv);
        u.setArmor(10);
        return u;
    }
}
