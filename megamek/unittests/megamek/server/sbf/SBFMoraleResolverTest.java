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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormation.MoraleStatus;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.FormationLosses;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFMoraleResolverTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final Player owner = new Player(1, "Blue");
    private SBFFormation formation;

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        owner.setStartingPos(Board.START_N);
        game.addPlayer(owner.getId(), owner);
        game.addPlayer(2, new Player(2, "Red"));
        Board board = new Board(8, 8);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }
        game.setBoard(0, board);
        formation = formation(20, 1, 4, 4);
    }

    @Test
    void halfArmorRoundsUpAndTiePassesWithoutRepeatCheckOnLaterTurn() {
        formation.getUnits().getFirst().setArmor(5);
        formation.getUnits().getFirst().setCurrentArmor(3);
        // 7 morale - 1 outside enemy LOS + 1 for the damaged Unit.
        resolve(List.of(halfArmor()), 7);
        assertEquals(MoraleStatus.NORMAL, formation.moraleStatus());
        resolve(List.of());
        resolve(List.of(halfArmor()), 6);
        assertEquals(MoraleStatus.SHAKEN, formation.moraleStatus());
    }

    @Test
    void failedChecksSkipUnsteadyAndRoutingRemovesOnlyUnitsAtOrBelowHalf() {
        formation.addUnit(unit(5));
        formation.getUnits().getFirst().setCurrentArmor(5);
        formation.getUnits().get(1).setCurrentArmor(3);
        resolve(List.of(halfArmor()), 2);
        resolve(List.of(halfArmor()), 2);
        assertEquals(MoraleStatus.BROKEN, formation.moraleStatus());
        resolve(List.of(halfArmor()), 2);
        assertEquals(MoraleStatus.ROUTED, formation.moraleStatus());
        assertEquals(1, formation.getUnits().size());
        assertEquals(3, formation.getUnits().getFirst().getCurrentArmor());
        resolve(List.of());
        assertEquals(MoraleStatus.ROUTED, formation.moraleStatus());
    }

    @Test
    void aRoutedFormationCanRallyOnlyWithItsOwnCommanderWithinTwoHexes() {
        formation.setMoraleStatus(MoraleStatus.ROUTED);
        SBFFormation commander = formation(22, 1, 4, 7);
        commander.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.COM);
        resolve(List.of());
        commander.setPosition(at(4, 6));
        resolve(List.of(), 12);
        assertEquals(MoraleStatus.BROKEN, formation.moraleStatus());
    }

    @Test
    void shakenFormationMustReachCoverBeforeItCanRally() {
        formation.setMoraleStatus(MoraleStatus.SHAKEN);
        SBFFormation enemy = formation(22, 2, 4, 5);
        resolve(List.of());
        assertEquals(MoraleStatus.SHAKEN, formation.moraleStatus());
        game.forget(enemy.getId());
        resolve(List.of(), 12);
        assertEquals(MoraleStatus.NORMAL, formation.moraleStatus());
    }

    @Test
    void commanderLossCanEnrageTheEntireForceInsteadOfRequiringChecks() {
        game.setCurrentRound(8);
        SBFFormation other = formation(22, 1, 1, 4);
        resolve(List.of(new FormationLosses(formation, false, 1, true, true, false)), 11);
        assertTrue(other.isEnraged());
        assertEquals(MoraleStatus.NORMAL, formation.moraleStatus());
        assertFalse(other.hasEnragedDamageBonus(8));
        assertTrue(other.hasEnragedDamageBonus(9));
        assertTrue(other.hasEnragedDamageBonus(10));
        assertFalse(other.hasEnragedDamageBonus(11));
    }

    @Test
    void losingCommanderAndLeaderRequiresTwoChecksForThatFormation() {
        SBFFormation other = formation(22, 1, 1, 4);
        resolve(List.of(new FormationLosses(formation, false, 1, true, true, false)), 10, 2, 2, 2);
        assertEquals(MoraleStatus.BROKEN, formation.moraleStatus());
        assertEquals(MoraleStatus.SHAKEN, other.moraleStatus());
    }

    @Test
    void crippledRequiresHalfTheUnitsInOneCategoryAndRespectsOriginalMove() {
        formation.addUnit(unit(10));
        formation.addUnit(unit(19));
        formation.getUnits().getFirst().setCurrentArmor(2);
        formation.getUnits().get(1).addTargetingCrit();
        formation.getUnits().get(1).addTargetingCrit();
        formation.getUnits().get(2).setCurrentArmor(4);
        assertFalse(formation.isCrippled());
        formation.getUnits().get(2).setCurrentArmor(3);
        assertTrue(formation.isCrippled());
        formation.getUnits().get(2).setCurrentArmor(19);
        formation.setMovement(2);
        formation.setCurrentMovement(1);
        assertFalse(formation.isCrippled());
        formation.setMovement(3);
        formation.setCurrentMovement(1);
        assertTrue(formation.isCrippled());
    }

    @Test
    void exemptDronesStillWithdrawWhenOptionalRuleIsEnabled() {
        formation.getUnits().getFirst().getSpecialAbilities().setSUA(BattleForceSUA.RBT);
        formation.getUnits().getFirst().setCurrentArmor(1);
        resolve(List.of(halfArmor()));
        assertFalse(formation.mustWithdraw());
        game.getOptions().getOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL).setValue(true);
        resolve(List.of(halfArmor()));
        assertTrue(formation.mustWithdraw());
        assertEquals(MoraleStatus.NORMAL, formation.moraleStatus());
    }

    @Test
    void withdrawingMoveMustMakeBestReachableProgressToTheAssignedHomeEdge() {
        formation.startForcedWithdrawal();
        assertFalse(SBFWithdrawal.permitsMove(game, formation, path(at(4, 3))));
        assertTrue(SBFWithdrawal.permitsMove(game, formation, path(at(4, 3), at(4, 2))));
        formation.setPosition(at(4, 0));
        assertTrue(SBFWithdrawal.withdrawIfAtHomeEdge(manager, formation));
        assertTrue(formation.hasWithdrawn());
        assertTrue(game.getFormation(formation.getId()).isEmpty());
        assertTrue(game.getGraveyard().contains(formation));
        assertEquals(10, formation.getUnits().getFirst().getCurrentArmor());
    }

    @Test
    void immobilizedWithdrawalScattersBeforeReachingHomeButWithdrawsAtHome() {
        formation.startForcedWithdrawal();
        formation.setCurrentMovement(0);
        SBFFormation home = formation(21, 1, 4, 0);
        home.startForcedWithdrawal();
        home.setCurrentMovement(0);
        resolve(List.of());
        assertEquals(0, formation.getUnits().getFirst().getCurrentArmor());
        assertFalse(formation.hasWithdrawn());
        assertTrue(home.hasWithdrawn());
        assertEquals(2, game.getGraveyard().size());
    }

    @Test
    void shakenFormationMayFireOnlyAtAnEngagedOpponentInItsHex() {
        formation.setMoraleStatus(MoraleStatus.SHAKEN);
        SBFFormation target = formation(22, 2, 4, 4);
        var attack = new SBFStandardUnitAttack(formation.getId(), 0, target.getId(), ASRange.LONG);
        assertTrue(SBFToHitData.compileToHit(game, attack).cannotSucceed());
        formation.setEngagedWith(target.getId(), true);
        target.setEngagedWith(formation.getId(), true);
        assertFalse(SBFToHitData.compileToHit(game, attack).cannotSucceed());
    }

    @Test
    void noConfiguredHomeEdgeDoesNotTurnAnyMapBorderIntoAnExit() {
        owner.setStartingPos(Board.START_ANY);
        formation.startForcedWithdrawal();
        formation.setPosition(at(0, 2));
        assertFalse(SBFWithdrawal.withdrawIfAtHomeEdge(manager, formation));
        assertTrue(game.getFormation(formation.getId()).isPresent());
    }

    private SBFMovePath path(BoardLocation... destinations) {
        BoardLocation start = formation.getPosition();
        SBFMovePath result = new SBFMovePath(formation.getId(), start, game);
        for (BoardLocation end : destinations) {
            result.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), start, end));
            start = end;
        }
        return result;
    }

    private FormationLosses halfArmor() {
        return new FormationLosses(formation, true, 0, false, false, false);
    }

    private void resolve(List<FormationLosses> losses, Integer... dice) {
        Queue<Integer> rolls = new ArrayDeque<>(Arrays.asList(dice));
        new SBFMoraleResolver(manager, rolls::remove).resolve(losses);
        assertTrue(rolls.isEmpty());
    }

    private SBFFormation formation(int id, int ownerId, int x, int y) {
        SBFFormation created = new SBFFormation();
        created.setId(id);
        created.setName("Formation " + id);
        created.setOwnerId(ownerId);
        created.setType(SBFElementType.V);
        created.setMovementMode(SBFMovementMode.TRACKED);
        created.setMovement(2);
        created.setMorale(7);
        created.setDeployed(true);
        created.setPosition(at(x, y));
        created.addUnit(unit(10));
        game.addUnit(created);
        return created;
    }

    private static SBFUnit unit(int armor) {
        SBFUnit unit = new SBFUnit();
        unit.setArmor(armor);
        unit.setType(SBFElementType.V);
        unit.setMovement(2);
        unit.setMovementMode(SBFMovementMode.TRACKED);
        return unit;
    }

    private static BoardLocation at(int x, int y) {
        return BoardLocation.of(new Coords(x, y), 0);
    }
}
