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

import static megamek.common.alphaStrike.BattleForceSUA.COM;
import static megamek.common.alphaStrike.BattleForceSUA.LEAD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.Allocation;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.util.SerializationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFCombatDamageTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final SBFFormation attacker = new SBFFormation();
    private final SBFFormation target = new SBFFormation();
    private final SBFUnit unit = new SBFUnit();

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        game.addPlayer(10, new Player(10, "Attacker"));
        game.addPlayer(20, new Player(20, "Defender"));
        attacker.setOwnerId(10);
        target.setOwnerId(20);
        attacker.setId(1);
        target.setId(2);
        unit.setArmor(9);
        unit.setMovement(5);
        unit.setDamage(ASDamageVector.createNormRndDmg(4, 3, 2));
        target.addUnit(unit);
        target.setMovement(5);
        game.addUnit(attacker);
        game.addUnit(target);
    }

    @Test
    void recordsEachHitAgainstRemainingArmorAndPreservesReturnFireUntilEnd() {
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(8, 7);
            SBFCombatDamage.recordHit(manager, attacker, target, 0, 4);
            SBFCombatDamage.recordHit(manager, attacker, target, 0, 1);
            SBFCombatDamage.recordHit(manager, attacker, target, 0, 1);
        }

        assertEquals(List.of(0, 8, 7), game.getPendingHits().stream().map(PendingHit::criticalRoll).toList());
        assertEquals(9, unit.getCurrentArmor());
        assertEquals(2, unit.getCurrentDamage().L().damage);
        assertEquals(0, unit.getTargetingCrits());

        var losses = SBFCombatDamage.applyEndPhase(manager).getFirst();

        assertTrue(losses.armorAtHalf());
        assertEquals(3, unit.getCurrentArmor());
        assertEquals(1, unit.getCurrentDamage().L().damage);
        assertEquals(1, unit.getTargetingCrits());
        assertTrue(SBFCombatDamage.applyEndPhase(manager).isEmpty());
        assertEquals(3, unit.getCurrentArmor());
    }

    @Test
    void simplifiedAllocationHonorsConcentrationPerExchange() {
        game.getOptions().getOption(SBFRuleOptions.BASE_SIMPLIFIED_DAMAGE).setValue(true);
        target.addUnit(new SBFUnit());
        game.getPendingHits().add(new PendingHit(1, 2, 0, 1, 0));
        game.getPendingHits().add(new PendingHit(1, 2, 0, 1, 0));
        game.getPendingHits().add(new PendingHit(9, 2, 1, 1, 0));

        assertEquals(List.of(1), SBFCombatDamage.eligibleUnits(game, attacker, target));
        SBFCombatDamage.recordStandardHit(manager, attacker, target, 1);
        assertEquals(1, game.getPendingHits().getLast().unitIndex());
        assertEquals(List.of(1), SBFCombatDamage.eligibleUnits(game, attacker, target));
        SBFCombatDamage.recordStandardHit(manager, attacker, target, 1);
        assertEquals(List.of(0, 1), SBFCombatDamage.eligibleUnits(game, attacker, target));
    }

    @Test
    void tacticsTieGoesToDefenderAfterDifferenceAndLeaderBonusAndInvalidChoicesChangeNothing() {
        var second = new SBFUnit();
        second.setArmor(15);
        target.addUnit(second);
        unit.getSpecialAbilities().setSUA(LEAD);
        attacker.setTactics(4);
        target.setTactics(6);
        try (var dice = mockStatic(Compute.class)) {
            // Attacker 3 + 2 Tactics, defender 3 + 2 LEAD.
            dice.when(Compute::d6).thenReturn(3);
            SBFCombatDamage.recordStandardHit(manager, attacker, target, 1);
            SBFCombatDamage.recordStandardHit(manager, attacker, target, 2);
            SBFCombatDamage.recordStandardHit(manager, attacker, target, 3);
            dice.verify(Compute::d6, times(2));
        }
        assertTrue(SBFCombatDamage.hasPendingChoices(game));
        assertFalse(SBFCombatDamage.allocate(manager, 20, 1, 2, List.of(0, 0, 1)));
        game.setPhase(GamePhase.FIRING_REPORT);
        assertFalse(SBFCombatDamage.allocate(manager, 10, 1, 2, List.of(0, 0, 1)));
        assertFalse(SBFCombatDamage.allocate(manager, 20, 1, 2, List.of(0, 0, 0)));
        assertFalse(SBFCombatDamage.allocate(manager, 20, 1, 2, List.of(0, 0)));
        assertTrue(game.getPendingHits().isEmpty());
        assertTrue(game.getDamageAllocations().stream().allMatch(hit -> hit.unitIndex() == -1));

        assertTrue(SBFCombatDamage.allocate(manager, 20, 1, 2, List.of(1, 0, 1)));
        assertFalse(SBFCombatDamage.hasPendingChoices(game));
        assertEquals(List.of(1, 0, 1), game.getPendingHits().stream().map(PendingHit::unitIndex).toList());
        assertEquals(9, unit.getCurrentArmor());
        SBFCombatDamage.applyEndPhase(manager);
        assertEquals(7, unit.getCurrentArmor());
        assertEquals(11, second.getCurrentArmor());
    }

    @Test
    void attackWinnerCanAssignAndRepliesCannotReorderHitsAtTheThreshold() {
        var second = new SBFUnit();
        second.setArmor(20);
        target.addUnit(second);
        var otherAttacker = new SBFFormation();
        otherAttacker.setId(3);
        otherAttacker.setOwnerId(20);
        game.addUnit(otherAttacker);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(Compute::d6).thenReturn(6, 1, 6, 1);
            SBFCombatDamage.recordStandardHit(manager, attacker, target, 4);
            SBFCombatDamage.recordStandardHit(manager, otherAttacker, target, 1);
            SBFCombatDamage.recordStandardHit(manager, attacker, target, 1);
            game.setPhase(GamePhase.FIRING_REPORT);

            assertTrue(SBFCombatDamage.allocate(manager, 20, 3, 2, List.of(0)));
            assertTrue(game.getPendingHits().isEmpty());
            dice.when(() -> Compute.d6(2)).thenReturn(11, 8);
            assertTrue(SBFCombatDamage.allocate(manager, 10, 1, 2, List.of(0, 0)));
        }
        assertEquals(List.of(1, 3, 1), game.getPendingHits().stream().map(PendingHit::attackerId).toList());
        assertEquals(List.of(0, 11, 8), game.getPendingHits().stream().map(PendingHit::criticalRoll).toList());
        assertFalse(SBFCombatDamage.allocate(manager, 10, 1, 2, List.of(0, 0)));
    }

    @Test
    void missionKillRemovesOnlyItsUnitAndRecalculatesOnlyMovement() {
        SBFUnit survivor = new SBFUnit();
        survivor.setArmor(9);
        survivor.setMovement(7);
        survivor.setMovementMode(SBFMovementMode.TRACKED);
        target.addUnit(survivor);
        target.setTmm(4);
        target.setTactics(6);
        unit.getSpecialAbilities().setSUA(COM);
        unit.getSpecialAbilities().setSUA(LEAD);
        game.getPendingHits().add(new PendingHit(1, 2, 0, 5, 12));
        game.getPendingHits().add(new PendingHit(1, 2, 1, 5, 11));
        game.getPendingHits().add(new PendingHit(1, 2, 0, 1, 0));

        var losses = SBFCombatDamage.applyEndPhase(manager).getFirst();

        assertEquals(1, losses.destroyedUnits());
        assertTrue(losses.leaderLost());
        assertTrue(losses.commanderLost());
        assertEquals(List.of(survivor), target.getUnits());
        assertEquals(6, target.getMovement());
        assertEquals(5, target.getStartingMovement());
        assertEquals(SBFMovementMode.TRACKED, target.getMovementMode());
        assertEquals(4, target.getTmm());
        assertEquals(6, target.getTactics());
        assertEquals(7, survivor.getMovement());
        assertEquals(6, survivor.getCurrentMovement());
        assertTrue(game.getGraveyard().isEmpty());
    }

    @Test
    void thirdTargetingCriticalDisablesFireAndKillsLeaderForMoraleOnce() {
        unit.getSpecialAbilities().setSUA(LEAD);
        unit.addTargetingCrit();
        unit.addTargetingCrit();
        unit.setCurrentArmor(4);
        game.getPendingHits().add(new PendingHit(1, 2, 0, 1, 6));

        var first = SBFCombatDamage.applyEndPhase(manager).getFirst();

        assertTrue(first.leaderLost());
        assertFalse(first.armorAtHalf());
        assertFalse(unit.canFire());
        assertEquals(0, first.destroyedUnits());

        game.getPendingHits().add(new PendingHit(1, 2, 0, 1, 5));
        assertFalse(SBFCombatDamage.applyEndPhase(manager).getFirst().leaderLost());
    }

    @Test
    void wipedFormationLeavesPlayOnceAndKeepsItsWreck() {
        game.getPendingHits().add(new PendingHit(1, 2, 0, 10, 3));

        SBFCombatDamage.applyEndPhase(manager);
        assertTrue(game.getFormation(2).isEmpty());
        assertEquals(List.of(target), game.getGraveyard());
        assertTrue(SBFCombatDamage.applyEndPhase(manager).isEmpty());
        assertEquals(List.of(target), game.getGraveyard());
    }

    @Test
    void recordedDamageAndRollSurviveSaveLoadBetweenFiringAndEnd() {
        game.getPendingHits().add(new PendingHit(1, 2, 0, 6, 10));
        String xml = SerializationHelper.getSaveGameXStream().toXML(game.getPendingHits());
        assertEquals(game.getPendingHits(), SerializationHelper.getLoadSaveGameXStream().fromXML(xml));
        game.getDamageAllocations().add(new Allocation(1, 2, 10, 4, -1));
        String choicesXml = SerializationHelper.getSaveGameXStream().toXML(game.getDamageAllocations());
        assertEquals(game.getDamageAllocations(), SerializationHelper.getLoadSaveGameXStream().fromXML(choicesXml));
    }
}
