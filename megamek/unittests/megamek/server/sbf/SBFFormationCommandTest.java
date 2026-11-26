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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationConverter;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.util.SerializationHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SBFFormationCommandTest {
    private final SBFGame game = new SBFGame();

    SBFFormationCommandTest() {
        game.setPhase(GamePhase.END);
        game.incrementCurrentRound();
        game.getOptions().getOption(SBFRuleOptions.BASE_ADJUST_FORMATIONS).setValue(true);
        game.setBoard(0, Board.createEmptyBoard(8, 8));
        game.addPlayer(0, new Player(0, "Owner"));
    }

    private SBFFormation formation(int id, int x, int count) {
        var parent = new SBFFormation();
        parent.setName("F" + id);
        parent.setId(id);
        parent.setOwnerId(0);
        parent.setDeployed(true);
        parent.setPosition(BoardLocation.of(new Coords(x, 2), 0));
        for (int i = 0; i < count; i++) {
            var unit = new SBFUnit();
            unit.setName("U" + i);
            unit.setType(SBFElementType.BM);
            unit.setMovementMode(SBFMovementMode.MEK_WALK);
            unit.setTrspMovementMode(SBFMovementMode.MEK_WALK);
            unit.setSize(2);
            unit.setSkill(4);
            unit.setMovement(4);
            unit.setArmor(10);
            unit.setPointValue(15);
            parent.addUnit(unit);
        }
        SBFFormationConverter.calculateStatsFromUnits(parent);
        game.addUnit(parent);
        return parent;
    }

    @Test
    void detachUsesHalfRoundedDownAndTakesOneMovementTurnWithItsParent() {
        var parent = formation(20, 2, 3);
        var other = formation(21, 4, 1);
        var scout = parent.getUnits().get(1);
        scout.setCurrentArmor(5);
        assertFalse(SBFFormationCommand.detach(game, 0, parent, List.of(1, 2), false));
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(2), false));
        var child = game.getFormation(22).orElseThrow();
        assertSame(scout, child.getUnits().getFirst());
        assertEquals(5, scout.getCurrentArmor());
        assertEquals(45, parent.getPointValue() + child.getPointValue());
        assertFalse(SBFFormationCommand.detach(game, 0, parent, List.of(1), false));
        var manager = new SBFGameManager();
        manager.setGame(game);
        game.setPhase(GamePhase.MOVEMENT);
        assertEquals(2, manager.initiativeHelper.formationTurns(GamePhase.MOVEMENT).size());
        child.setDone(true);
        assertTrue(game.continueDetachmentMovement(child));
        var turn = new SBFFormationTurn(0);
        assertFalse(turn.isValidEntity(other, game));
        assertTrue(turn.isValidEntity(parent, game));
        parent.setDone(true);
        assertFalse(game.continueDetachmentMovement(parent));
        assertTrue(turn.isValidEntity(other, game));
    }

    @Test
    void splitMovesIndependentlyAndReformingRequiresTheWholeSurvivingFamilyInOneHex() {
        var parent = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        var sibling = game.getFormation(21).orElseThrow();
        assertEquals(21, sibling.movementGroup());
        sibling.setPosition(BoardLocation.of(new Coords(3, 2), 0));
        assertFalse(SBFFormationCommand.reform(game, 0, parent));
        sibling.setPosition(parent.getPosition());
        assertTrue(SBFFormationCommand.reform(game, 0, sibling));
        assertEquals(3, parent.getUnits().size());
        assertEquals(1, game.getActiveFormations().size());
        assertTrue(game.getGraveyard().isEmpty());
        game.incrementCurrentRound();
        assertEquals(3, SBFMovePath.normalMovementPoints(parent, game));
        game.incrementCurrentRound();
        assertEquals(4, SBFMovePath.normalMovementPoints(parent, game));
    }

    @Test
    void lastReturningCounterMayStackOnlyToCompleteAnAnnouncedReunion() {
        var parent = formation(20, 2, 2);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        var child = game.getFormation(21).orElseThrow();
        child.setPosition(BoardLocation.of(new Coords(3, 2), 0));
        game.setPhase(GamePhase.MOVEMENT);
        assertTrue(SBFMovePath.exceedsStackingLimit(game, child, parent.getPosition()));
        assertTrue(SBFFormationCommand.planReunion(game, 0, parent, parent.getPosition()));
        assertFalse(SBFMovePath.exceedsStackingLimit(game, child, parent.getPosition()));
        var outsider = formation(22, 2, 1);
        assertTrue(SBFMovePath.exceedsStackingLimit(game, child, parent.getPosition()));
        outsider.setPosition(BoardLocation.of(new Coords(4, 2), 0));
        child.setPosition(parent.getPosition());
        game.setPhase(GamePhase.END);
        SBFFormationCommand.finishReunions(game);
        assertEquals(2, parent.getUnits().size());
        assertTrue(game.getFormation(21).isEmpty());
    }

    @Test
    void interceptedOriginalCounterLeavesAnAdHocPairAtTheDeclaredRendezvous() {
        var parent = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        var a = game.getFormation(21).orElseThrow();
        var b = game.getFormation(22).orElseThrow();
        var site = a.getPosition();
        parent.setPosition(BoardLocation.of(new Coords(3, 2), 0));
        b.setPosition(parent.getPosition());
        game.setPhase(GamePhase.MOVEMENT);
        assertTrue(SBFFormationCommand.planReunion(game, 0, parent, site));
        assertFalse(SBFMovePath.exceedsStackingLimit(game, b, site));
        var stranger = formation(30, 2, 1);
        assertTrue(SBFMovePath.exceedsStackingLimit(game, b, site));
        stranger.setPosition(parent.getPosition());
        b.setPosition(site);
        var enemy = formation(31, 2, 1);
        enemy.setOwnerId(1);
        SBFEngagementControl.setEngaged(b, enemy, true);
        game.setPhase(GamePhase.END);
        game.getPlayer(0).setDone(true);
        SBFFormationCommand.finishReunions(game);

        assertEquals(2, a.getUnits().size());
        assertEquals(List.of(enemy.getId()), a.getEngagedOpponents());
        assertEquals(List.of(a.getId()), enemy.getEngagedOpponents());
        assertEquals(1, parent.getUnits().size());
        assertTrue(a.inactiveAfterAssembly(game.getCurrentRound() + 1));
        assertEquals(0, a.reformMovementPenalty(game.getCurrentRound() + 1));
        assertEquals(List.of(parent), game.originalFamily(parent));
        SBFFormationCommand.finishReunions(game);
        assertEquals(2, a.getUnits().size());
    }

    @Test
    void destructionOfTheThirdCounterLeavesAValidOriginalFormation() {
        var parent = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        var a = game.getFormation(21).orElseThrow();
        game.setPhase(GamePhase.MOVEMENT);
        assertTrue(SBFFormationCommand.planReunion(game, 0, parent, a.getPosition()));
        game.forget(parent.getId());
        game.setPhase(GamePhase.END);
        SBFFormationCommand.finishReunions(game);
        assertEquals(2, a.getUnits().size());
        assertFalse(a.inactiveAfterAssembly(game.getCurrentRound() + 1));
        assertEquals(1, a.reformMovementPenalty(game.getCurrentRound() + 1));
    }

    @Test
    void detachmentsNoLongerMoveWithAParentThatBecameAnAdHocFormation() {
        var parent = formation(20, 2, 2);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(2), false));
        var detached = game.getFormation(21).orElseThrow();
        formation(30, 2, 1);
        assertTrue(SBFFormationCommand.assemble(game, 0, parent, List.of("30:1")));
        game.incrementCurrentRound();
        game.incrementCurrentRound();
        game.setPhase(GamePhase.MOVEMENT);
        var manager = new SBFGameManager();
        manager.setGame(game);
        assertEquals(2, manager.initiativeHelper.formationTurns(GamePhase.MOVEMENT).size());
        assertFalse(game.continueDetachmentMovement(detached));
    }

    @Test
    void assemblingIntoTheOldParentDoesNotMakeForeignUnitsEligibleForOriginalReformation() {
        var parent = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        formation(30, 2, 1);
        assertTrue(SBFFormationCommand.assemble(game, 0, parent, List.of("30:1")));
        game.incrementCurrentRound();
        var sibling = game.getFormation(21).orElseThrow();
        assertFalse(SBFFormationCommand.reform(game, 0, sibling));
        assertEquals(List.of(sibling), game.originalFamily(sibling));
        assertEquals(List.of(parent), game.originalFamily(parent));
    }

    @Test
    void aLegacyDetachedFamilyCannotReformWithAForeignUnitAfterLoading() {
        var parent = formation(20, 2, 1);
        var first = formation(21, 2, 1);
        var second = formation(22, 2, 1);
        first.assignOrigin(parent.getId(), true);
        second.assignOrigin(parent.getId(), true);
        formation(30, 2, 1);
        assertTrue(SBFFormationCommand.assemble(game, 0, parent, List.of("30:1")));
        game.incrementCurrentRound();
        assertFalse(SBFFormationCommand.reform(game, 0, first));
        assertEquals(List.of(first), game.originalFamily(first));
        assertEquals(List.of(second), game.originalFamily(second));
        assertEquals(first.movementGroup(), second.movementGroup());
        assertFalse(parent.movementGroup() == first.movementGroup());
    }

    @Test
    void assemblingAllOriginalUnitsAtTheirSiteUsesTheReformationCost() {
        var parent = formation(20, 2, 2);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(2), false));
        assertTrue(SBFFormationCommand.assemble(game, 0, parent, List.of("21:1")));
        assertEquals(1, parent.reformMovementPenalty(game.getCurrentRound() + 1));
        assertFalse(parent.inactiveAfterAssembly(game.getCurrentRound() + 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void anAdHocPairAndItsFormerSiblingCreateAnotherAdHocSheetInEitherOrder(boolean pairReceives) {
        var pair = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, pair, List.of(), true));
        var third = game.getFormation(22).orElseThrow();
        third.setPosition(BoardLocation.of(new Coords(3, 2), 0));
        game.setPhase(GamePhase.MOVEMENT);
        assertTrue(SBFFormationCommand.planReunion(game, 0, pair, pair.getPosition()));
        game.setPhase(GamePhase.END);
        assertTrue(SBFFormationCommand.finishReunions(game));
        assertTrue(pair.inactiveAfterAssembly(game.getCurrentRound() + 1));
        game.incrementCurrentRound();
        game.incrementCurrentRound();
        third.setPosition(pair.getPosition());

        assertTrue(SBFFormationCommand.assemble(game, 0, pairReceives ? pair : third,
              pairReceives ? List.of("22:1") : List.of("20:1", "20:2")));
        var assembled = game.getActiveFormations().getFirst();
        assertEquals(1, game.getActiveFormations().size());
        assertEquals(3, assembled.getUnits().size());
        assertEquals(0, assembled.reformMovementPenalty(game.getCurrentRound() + 1));
        assertTrue(assembled.inactiveAfterAssembly(game.getCurrentRound() + 1));
    }

    @Test
    void aLaterSplitReformsOnlyTheLatestAdHocSheet() {
        var pair = formation(20, 2, 3);
        assertTrue(SBFFormationCommand.detach(game, 0, pair, List.of(), true));
        var third = game.getFormation(22).orElseThrow();
        assertTrue(SBFFormationCommand.assemble(game, 0, pair, List.of("21:1")));
        game.incrementCurrentRound();
        assertFalse(SBFFormationCommand.detach(game, 0, pair, List.of(), true));
        game.incrementCurrentRound();
        assertTrue(SBFFormationCommand.detach(game, 0, pair, List.of(), true));
        var child = game.getFormation(23).orElseThrow();
        assertEquals(List.of(pair, child), game.originalFamily(pair));
        game.setPhase(GamePhase.MOVEMENT);
        assertFalse(SBFFormationCommand.planReunion(game, 0, third, pair.getPosition()));
        assertTrue(SBFFormationCommand.planReunion(game, 0, child, pair.getPosition()));
        game.setPhase(GamePhase.END);
        assertTrue(SBFFormationCommand.finishReunions(game));
        assertEquals(2, pair.getUnits().size());
        assertEquals(1, pair.reformMovementPenalty(game.getCurrentRound() + 1));
        assertFalse(pair.inactiveAfterAssembly(game.getCurrentRound() + 1));
        assertEquals(List.of(third), game.originalFamily(third));
    }

    @Test
    void aPartialDonorAndItsOffsiteDetachmentKeepTheirMovementTurnButRetireTheDonorSheet() {
        var donor = formation(20, 2, 4);
        assertTrue(SBFFormationCommand.detach(game, 0, donor, List.of(4), false));
        var detached = game.getFormation(21).orElseThrow();
        detached.setPosition(BoardLocation.of(new Coords(5, 2), 0));
        var receiver = formation(30, 2, 1);
        assertTrue(SBFFormationCommand.assemble(game, 0, receiver, List.of("20:2")));
        assertEquals(2, donor.getUnits().size());
        assertEquals(List.of(donor), game.originalFamily(donor));
        assertEquals(List.of(detached), game.originalFamily(detached));
        assertEquals(donor.movementGroup(), detached.movementGroup());
        detached.setPosition(donor.getPosition());
        assertFalse(SBFFormationCommand.reform(game, 0, detached));
        assertTrue(SBFFormationCommand.detach(game, 0, donor, List.of(2), false));
        assertTrue(SBFFormationCommand.reform(game, 0, donor));
        assertEquals(2, donor.getUnits().size());
    }

    @Test
    void reunitingANonAnchorCounterKeepsItsSharedMovementTurn() {
        var anchor = formation(20, 2, 5);
        assertTrue(SBFFormationCommand.detach(game, 0, anchor, List.of(4, 5), false));
        var other = game.getFormation(21).orElseThrow();
        var receiver = formation(30, 2, 1);
        assertTrue(SBFFormationCommand.assemble(game, 0, receiver, List.of("20:1")));
        assertEquals(anchor.movementGroup(), other.movementGroup());
        assertTrue(SBFFormationCommand.detach(game, 0, other, List.of(2), false));
        assertTrue(SBFFormationCommand.reform(game, 0, other));
        assertEquals(anchor.movementGroup(), other.movementGroup());
        assertEquals(2, other.getUnits().size());
    }

    @ParameterizedTest
    @CsvSource({ "false,false", "true,false", "false,true" })
    void loadingAnObsoleteSheetFindsTransfersAcrossSurvivorsWithdrawalsAndCasualties(boolean leftPlay,
          boolean transferredUnitLost) throws Exception {
        var pair = formation(20, 2, 2);
        var first = formation(21, 2, 1);
        var second = formation(22, 2, 1);
        first.assignOrigin(21, false);
        second.assignOrigin(21, true);
        var legacy = SBFGame.class.getDeclaredField("splitRosters");
        legacy.setAccessible(true);
        var transferred = pair.getUnits().getFirst();
        var record = new java.util.ArrayList<>(List.of(transferred, first.getUnits().getFirst(), second.getUnits().getFirst()));
        if (transferredUnitLost) {
            game.scenarioProgress().recordLoss(game.getCurrentRound(), pair, transferred, 1,
                  megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause.COMBAT);
            pair.removeUnit(transferred);
        }
        legacy.set(game, new java.util.HashMap<>(Map.of(21, record, 22, record)));
        if (leftPlay) {
            game.removeFormationFromPlay(pair);
        }
        var writer = SerializationHelper.getSaveGameXStream();
        var loaded = (SBFGame) SerializationHelper.getLoadSaveGameXStream().fromXML(writer.toXML(game));
        var leftover = loaded.getFormation(21).orElseThrow();
        var sibling = loaded.getFormation(22).orElseThrow();
        assertEquals(List.of(leftover), loaded.originalFamily(leftover));
        assertEquals(leftover.movementGroup(), sibling.movementGroup());
        assertFalse(writer.toXML(loaded).contains("splitRosters"));
    }

    @Test
    void loadingAnIntactSplitSheetStillAllowsReformingAfterAnOrdinaryCasualty() throws Exception {
        var parent = formation(20, 2, 3);
        var record = new java.util.ArrayList<>(parent.getUnits());
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        var legacy = SBFGame.class.getDeclaredField("splitRosters");
        legacy.setAccessible(true);
        legacy.set(game, new java.util.HashMap<>(Map.of(20, record, 21, record, 22, record)));
        game.removeFormationFromPlay(game.getFormation(22).orElseThrow());
        var xml = SerializationHelper.getSaveGameXStream().toXML(game);
        var loaded = (SBFGame) SerializationHelper.getLoadSaveGameXStream().fromXML(xml);
        var surviving = loaded.getFormation(20).orElseThrow();
        assertTrue(SBFFormationCommand.reform(loaded, 0, surviving));
        assertEquals(2, surviving.getUnits().size());
        assertEquals(1, surviving.reformMovementPenalty(loaded.getCurrentRound() + 1));
    }

    @Test
    void adjustingOnlyForForcedWithdrawalLetsOnlyACrippledFormationDetachOrSplit() {
        // Forced Withdrawal, IO:BF p. 172: "players may decide to use the Adjusting Formations rule only for Forced
        // Withdrawals and not for other play. Note: This optional rule does not apply to failed Morale Checks"
        game.getOptions().getOption(SBFRuleOptions.BASE_ADJUST_FORMATIONS).setValue(false);
        game.getOptions().getOption(SBFRuleOptions.BASE_ADJUST_FOR_WITHDRAWAL).setValue(true);
        game.getOptions().getOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL).setValue(true);
        var healthy = formation(20, 2, 3);
        assertFalse(game.takingAdjustmentOrders());
        assertFalse(SBFFormationCommand.detach(game, 0, healthy, List.of(1), false));

        var crippled = formation(30, 4, 3);
        for (int i = 0; i < 3; i++) {
            crippled.getUnits().getFirst().addMovementCrit();
        }
        SBFFormationConverter.calculateStatsFromUnits(crippled);
        crippled.startForcedWithdrawal();
        assertTrue(game.takingAdjustmentOrders());
        assertTrue(SBFFormationCommand.detach(game, 0, crippled, List.of(1), false));
        assertFalse(SBFFormationCommand.reform(game, 0, crippled));

        var routed = formation(40, 6, 3);
        routed.startForcedWithdrawal();
        routed.setMoraleStatus(SBFFormation.MoraleStatus.ROUTED);
        assertFalse(SBFFormationCommand.detach(game, 0, routed, List.of(), true));
    }

    @Test
    void crippledDetachedUnitKeepsItsPrintedMoveAndCannotClearMoraleWithdrawalByRejoining() {
        game.getOptions().getOption(SBFRuleOptions.BASE_FORCED_WITHDRAWAL).setValue(true);
        var parent = formation(20, 2, 3);
        var damaged = parent.getUnits().getFirst();
        for (int i = 0; i < 3; i++) {
            damaged.addMovementCrit();
        }
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(1), false));
        var child = game.getFormation(21).orElseThrow();
        assertEquals(4, child.getStartingMovement());
        assertEquals(1, child.getMovement());
        assertTrue(child.mustWithdraw());
        assertFalse(parent.mustWithdraw());
        child.setMoraleStatus(SBFFormation.MoraleStatus.ROUTED);
        assertTrue(SBFFormationCommand.reform(game, 0, parent));
        assertTrue(parent.isRouted());
        assertTrue(parent.mustWithdraw());
    }

    @Test
    void setupAllowsSplittingButReformAndAssemblyWaitForEndPhase() {
        game.setPhase(GamePhase.LOUNGE);
        var parent = formation(20, 2, 2);
        var other = formation(21, 3, 1);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(), true));
        assertFalse(SBFFormationCommand.reform(game, 0, parent));
        assertFalse(SBFFormationCommand.assemble(game, 0, other, List.of("20:1")));
    }

    @Test
    void anyLocatedMemberCountsItsWholeMovementGroupEvenIfAnUnseenMemberMovesLast() {
        game.addPlayer(1, new Player(1, "Enemy"));
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        var parent = formation(20, 2, 2);
        assertTrue(SBFFormationCommand.detach(game, 0, parent, List.of(1), false));
        var child = game.getFormation(21).orElseThrow();
        game.visibilityHelper().setVisible(1, 20);
        game.visibilityHelper().beginMovement();
        assertTrue(game.visibilityHelper().isUncountedMover(21));
        assertFalse(game.visibilityHelper().isUncountedMovementGroup(child));
        game.visibilityHelper().setInvisible(1, 20);
        assertFalse(game.visibilityHelper().isUncountedMovementGroup(child));
        game.visibilityHelper().beginMovement();
        assertTrue(game.visibilityHelper().isUncountedMovementGroup(child));
    }

    @Test
    void adHocAssemblyIsAtomicAndIdleForOneCompleteTurn() {
        var receiver = formation(20, 2, 1);
        var source = formation(21, 3, 2);
        var distant = formation(22, 6, 1);
        var selected = source.getUnits().getFirst();
        selected.getSpecialAbilities().setSUA(BattleForceSUA.COM);
        assertFalse(SBFFormationCommand.assemble(game, 0, receiver, List.of("21:1", "22:1")));
        assertFalse(SBFFormationCommand.assemble(game, 0, receiver, List.of("21:1", "21:1")));
        assertEquals(1, receiver.getUnits().size());
        assertEquals(1, distant.getUnits().size());
        assertTrue(SBFFormationCommand.assemble(game, 0, receiver, List.of("21:1")));
        assertSame(selected, receiver.getUnits().getLast());
        assertTrue(receiver.hasSUA(BattleForceSUA.COM));
        game.incrementCurrentRound();
        assertFalse(SBFFormationTurn.isEligible(receiver, GamePhase.MOVEMENT, game.getCurrentRound()));
        assertFalse(SBFFormationTurn.isEligible(receiver, GamePhase.FIRING, game.getCurrentRound()));
        game.incrementCurrentRound();
        assertTrue(SBFFormationTurn.isEligible(receiver, GamePhase.MOVEMENT, game.getCurrentRound()));
    }
}
