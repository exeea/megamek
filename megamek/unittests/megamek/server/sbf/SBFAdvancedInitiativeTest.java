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

import static megamek.common.alphaStrike.BattleForceSUA.*;
import static megamek.common.options.SBFRuleOptions.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.compute.Compute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.ObjectiveMarker;
import megamek.common.options.OptionsConstants;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFGame.PendingHit;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.Declaration;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.IntelBonus;
import megamek.common.strategicBattleSystems.SBFScenarioProgress.LossCause;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SBFUnit.InitiativeAbility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFAdvancedInitiativeTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = new SBFGameManager();
    private final Player blue = new Player(1, "Blue");
    private final Player ally = new Player(2, "Ally");
    private final Player red = new Player(3, "Red");

    @BeforeEach
    void setup() {
        manager.setGame(game);
        blue.setTeam(1);
        ally.setTeam(1);
        red.setTeam(2);
        for (Player owner : List.of(blue, ally, red)) {
            game.addPlayer(owner.getId(), owner);
        }
        game.setPhase(GamePhase.INITIATIVE);
        game.setCurrentRound(1);
    }

    @Test
    void alliedEquipmentAndLeadershipStackWithOnlyBestLastRoundBattlefieldEvent() {
        enable(INIT_MODIFIERS);
        SBFUnit hq = formation(10, blue).getUnits().getFirst();
        hq.getSpecialAbilities().mergeSUA(MHQ, 7);
        hq.getSpecialAbilities().setSUA(COM);
        hq.setCommandTier(3);
        hq.setElements(List.of(element(false, true)));
        hq.getElements().getFirst().getQuirks().getOption(OptionsConstants.QUIRK_POS_BATTLE_COMP).setValue(true);
        // Four individual Recon Elements spread across two allied owners.
        hq.getElements().getFirst().getSpecialAbilities().setSUA(RCN);
        var scouts = formation(11, ally).getUnits().getFirst();
        scouts.setElements(List.of(element(true, false), element(true, false), element(true, false)));
        SBFUnit eliminated = formation(12, red).getUnits().getFirst();
        eliminated.setCommandTier(4);
        game.scenarioProgress().recordLoss(1, formation(13, red), eliminated, ally.getId(), LossCause.COMBAT);
        var marker = new ObjectiveMarker();
        game.scenarioProgress().objective(marker).observe(1, Map.of(hq, blue.getId()));
        manager.rollInitiative();
        assertEquals(9, game.getTeamForPlayer(blue).getInitiative().getBonus(0)); // 4 equipment + 3 tier + 2 battlefield
        assertEquals(0, ally.getInitiative().getBonus(0));
        game.setCurrentRound(2);
        assertEquals(7, modifiers(blue).total());
        game.scenarioProgress().objective(marker).observe(2, Map.of(hq, blue.getId()));
        assertEquals(9, modifiers(ally).total());
        game.setCurrentRound(3);
        assertEquals(7, modifiers(ally).total());
        game.getOptions().getOption(INIT_MODIFIERS).setValue(false);
        assertEquals(0, modifiers(blue).total());
    }

    @Test
    void forceWideMhqSelectsOneAdvancedInitiativeModifier() {
        enable(INIT_MODIFIERS);
        var hq = formation(10, blue).getUnits().getFirst();
        hq.getSpecialAbilities().setSUA(RCN); // Avoid the separate no-Recon penalty.
        hq.getSpecialAbilities().mergeSUA(MHQ, 2);
        var alliedHq = formation(11, ally).getUnits().getFirst();
        formation(12, red).getUnits().getFirst().getSpecialAbilities().mergeSUA(MHQ, 7);
        assertEquals(0, modifiers(blue).total());

        alliedHq.getSpecialAbilities().mergeSUA(MHQ, 2);
        assertEquals(1, modifiers(blue).total());
        alliedHq.getSpecialAbilities().replaceSUA(MHQ, 5);
        assertEquals(2, modifiers(ally).total());
        alliedHq.setCurrentArmor(0);
        assertEquals(0, modifiers(blue).total());
    }

    @Test
    void worstStatusOnlyAndEnemyCapturedHQPersistsAfterFollowingTurn() {
        enable(INIT_MODIFIERS);
        SBFFormation scout = formation(11, blue);
        SBFFormation other = formation(12, ally);
        var enemy = formation(13, red).getUnits().getFirst();
        scout.setMoraleStatus(SBFFormation.MoraleStatus.BROKEN);
        assertEquals(-1, modifiers(blue).total());
        scout.setMoraleStatus(SBFFormation.MoraleStatus.ROUTED);
        assertEquals(-2, modifiers(ally).total());
        other.getUnits().getFirst().setCurrentArmor(0);
        game.removeFormationFromPlay(other);
        assertEquals(-3, modifiers(blue).total());
        var hq = new ObjectiveMarker();
        hq.setHeadquarters(true);
        hq.setOwnerId(ally.getId());
        game.scenarioProgress().objective(hq).observe(1, Map.of(enemy, red.getId()));
        assertEquals(-3, modifiers(blue).total());
        game.scenarioProgress().objective(hq).observe(2, Map.of(enemy, red.getId()));
        game.setCurrentRound(4);
        assertEquals(-4, modifiers(blue).total());
    }

    @Test
    void intelligenceRatingCountsElementsAndAssignmentsUseUnitDensityAndSharedECMHex() {
        enable(INIT_MODIFIERS);
        enable(INIT_BATTLEFIELD_INT);
        var a = formation(10, blue);
        var b = formation(11, ally);
        var jammer = formation(12, red);
        var com = a.getUnits().getFirst();
        com.getSpecialAbilities().mergeSUA(MHQ, 7);
        com.getSpecialAbilities().setSUA(RCN);
        a.getSpecialAbilities().setSUA(RCN);
        com.setElements(List.of(element(true, true), element(true, true)));
        b.getUnits().getFirst().getSpecialAbilities().mergeSUA(MHQ, 1);
        var force = modifiers(blue);
        force.orders.assign(IntelBonus.NETWORK, b.getId(), b.getUnits().getFirst());
        assertEquals(4, force.intelligenceRating()); // 2 for ground RCN, one per MHQ Element
        assertEquals(3, force.total()); // BI replaces other MHQ/RCN equipment
        jammer.setPosition(a.getPosition());
        jammer.getSpecialAbilities().setSUA(ECM);
        assertEquals(1, modifiers(ally).total());
        jammer.setPosition(b.getPosition());
        assertEquals(2, modifiers(blue).total());
        // Two Formations but just one surviving Unit with MHQ, regardless of the Unit's numeric rating.
        b.getUnits().getFirst().getSpecialAbilities().removeSUA(MHQ);
        assertEquals(2, modifiers(blue).total());
        b.setType(SBFElementType.AS);
        assertEquals(5, modifiers(ally).intelligenceRating());
        var ship = element(false, false);
        ship.setType(ASUnitType.DS);
        b.getUnits().getFirst().setElements(List.of(ship));
        b.setDeployed(false);
        assertEquals(4, modifiers(blue).intelligenceRating());
    }

    @Test
    void bankingYieldsAtOnePointMarginThenWinsOnceAndChecksCommandOwnership() {
        enable(INIT_BANKING);
        SBFUnit commander = formation(10, blue).getUnits().getFirst();
        commander.getSpecialAbilities().setSUA(COM);
        commander.getInitiativeAbilities().add(InitiativeAbility.BANKING);
        formation(11, ally);
        formation(12, red);
        var force = modifiers(blue);
        assertFalse(SBFInitiativeCommand.declare(game, ally, force, Declaration.YIELD));
        for (int round = 1; round <= 2; round++) {
            game.setCurrentRound(round);
            assertTrue(SBFInitiativeCommand.declare(game, blue, force, Declaration.YIELD));
            manager.rollInitiative();
            assertEquals(1, game.getTeamForPlayer(red).getInitiative().compareTo(game.getTeamForPlayer(blue).getInitiative()));
        }
        assertEquals(1, force.orders.bankedPoints());
        assertTrue(SBFInitiativeCommand.declare(game, blue, force, Declaration.SPEND));
        manager.rollInitiative();
        assertEquals(1, game.getTeamForPlayer(blue).getInitiative().compareTo(game.getTeamForPlayer(red).getInitiative()));
        assertTrue(game.getTeamForPlayer(blue).getInitiative().getKeptDice(0).isEmpty());
        assertEquals(0, force.orders.bankedPoints());
        assertFalse(SBFInitiativeCommand.declare(game, blue, force, Declaration.SPEND));
    }

    @Test
    void forcingTracksCrossingsStrictlyBelowHalfFromAppliedHitsIncludingAlliedOwners() {
        enable(INIT_FORCING);
        var com = formation(10, blue);
        var attacker = formation(11, ally);
        var target = formation(12, red);
        SBFUnit commander = com.getUnits().getFirst();
        commander.getSpecialAbilities().setSUA(COM);
        commander.getInitiativeAbilities().add(InitiativeAbility.FORCING);
        var force = modifiers(blue);
        game.setCurrentRound(0);
        assertFalse(SBFInitiativeCommand.declare(game, blue, force, Declaration.FORCE));
        game.setCurrentRound(1);
        assertTrue(SBFInitiativeCommand.declare(game, blue, force, Declaration.FORCE));
        game.getPendingHits().add(new PendingHit(attacker.getId(), target.getId(), 0, 4, 0)); // armor 8 to 4
        SBFCombatDamage.applyEndPhase(manager);
        assertEquals(0, force.total());
        game.getPendingHits().add(new PendingHit(attacker.getId(), target.getId(), 0, 1, 0));
        game.getPendingHits().add(new PendingHit(attacker.getId(), target.getId(), 0, 1, 0));
        SBFCombatDamage.applyEndPhase(manager);
        assertEquals(1, force.total());
        manager.rollInitiative();
        assertEquals(1, game.getTeamForPlayer(blue).getInitiative().getBonus(0));
        assertEquals(Declaration.NORMAL, force.orders.declaration());
        game.setCurrentRound(2);
        assertTrue(SBFInitiativeCommand.declare(game, blue, force, Declaration.FORCE));
        assertEquals(0, force.total());
        game.scenarioProgress().recordLoss(2, com, commander, red.getId(), LossCause.COMBAT);
        commander.setCurrentArmor(0);
        assertEquals(-2, modifiers(blue).total());
    }

    @Test
    void alliedSideKeepsTheSameModifierWhenTheFirstTeamRollTies() {
        enable(INIT_MODIFIERS);
        var hq = formation(10, blue).getUnits().getFirst();
        hq.getSpecialAbilities().setSUA(RCN);
        hq.getSpecialAbilities().mergeSUA(MHQ, 3);
        // Individual owners roll 3, 4, 5; the sides roll 4+1 and 5; tiebreaks are 7+1 and 7.
        var dice = new ArrayDeque<>(List.of(1, 2, 2, 2, 2, 3, 2, 2, 2, 3, 3, 4, 3, 4));
        var random = org.mockito.Mockito.mock(MMRandom.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(random.randomInt(6)).thenAnswer(call -> dice.removeFirst() - 1);
        try {
            Compute.setRNG(random);
            manager.rollInitiative();
            var friendly = game.getTeamForPlayer(ally).getInitiative();
            assertEquals(2, friendly.size());
            assertEquals(1, friendly.getBonus(0));
            assertEquals(1, friendly.getBonus(1));
            assertEquals(1, friendly.compareTo(game.getTeamForPlayer(red).getInitiative()));
            assertTrue(dice.isEmpty());
        } finally {
            Compute.setRNG(MMRandom.R_DEFAULT);
        }
    }

    @Test
    void authoredCommandSpecializationsUseSharedRoleAndFactionAndHalfMapThreshold() throws Exception {
        enable(INIT_SPECIALIZATION);
        var mapper = new ObjectMapper();
        String authored = """
              {"type":"SBFUnit","generalname":"Command","sbftype":"BM","size":2,"armor":8,"move":4,
               "movemode":"MEK_WALK","pv":10,"tmm":1,"status":"com",
               "tacticalspecialization":{"attack":2,"defense":1},
               "environmentspecialization":{"terrain":"woods","terrainLevel":2,"levels":1},
               "enemyspecialization":{"enemies":["DC"],"levels":2}}
              """;
        SBFUnit commander = mapper.readValue(authored, SBFUnit.class);
        SBFUnit reloaded = mapper.readValue(mapper.writeValueAsString(commander), SBFUnit.class);
        assertTrue(reloaded.hasSUA(COM));
        assertEquals(commander.getTacticalSpecialization(), reloaded.getTacticalSpecialization());
        assertEquals(commander.getEnvironmentSpecialization(), reloaded.getEnvironmentSpecialization());
        assertEquals(commander.getEnemySpecialization(), reloaded.getEnemySpecialization());
        var formation = formation(10, blue);
        formation.addUnit(reloaded);
        formation(11, red);
        game.getTeamForPlayer(red).setFaction("DC");
        game.scenarioProgress().setRole(blue.getId(), megamek.common.strategicBattleSystems.SBFScenarioProgress.ScenarioRole.ATTACKER);
        game.scenarioProgress().setScenarioType("Raid");
        Board board = Board.createEmptyBoard(4, 1);
        game.setBoard(0, board);
        assertEquals(2, modifiers(ally).total()); // +1 attacker, -1 absent woods, +2 preferred enemy
        board.getHex(0, 0).addTerrain(new Terrain(Terrains.WOODS, 2));
        assertEquals(3, modifiers(blue).total()); // present, less than half the map
        board.getHex(1, 0).addTerrain(new Terrain(Terrains.WOODS, 2));
        assertEquals(4, modifiers(blue).total());
        game.getTeamForPlayer(red).setFaction("LC");
        assertEquals(0, modifiers(blue).total());
        reloaded.setEnvironmentSpecialization(new SBFUnit.EnvironmentSpecialization(null, null,
              game.getPlanetaryConditions().getWeather().getExternalId(), 2));
        reloaded.setTacticalSpecialization(new SBFUnit.TacticalSpecialization(0, 0, "raid", 1));
        assertEquals(1, modifiers(blue).total());
        game.scenarioProgress().setScenarioType("Pursuit");
        assertEquals(-1, modifiers(blue).total());
        reloaded.setCurrentArmor(0);
        assertEquals(0, modifiers(blue).total());
    }

    @Test
    void deploymentDrawLeavesBonusesAndUnassignedTokensForTheFirstTurn() {
        enable(INIT_MODIFIERS);
        enable(INIT_BATTLEFIELD_INT);
        var hq = formation(10, blue).getUnits().getFirst();
        hq.getSpecialAbilities().mergeSUA(MHQ, 7);
        hq.setCommandTier(4);
        formation(11, red);
        game.setCurrentRound(0);
        game.setPhase(GamePhase.EXCHANGE);
        blue.setConstantInitBonus(2);
        manager.rollInitiative();
        assertEquals(0, game.getTeamForPlayer(blue).getInitiative().getBonus(0));
        assertNull(modifiers(blue).orders.assignment(IntelBonus.HQ));
        game.setPhase(GamePhase.INITIATIVE);
        manager.rollInitiative();
        assertEquals(7, game.getTeamForPlayer(blue).getInitiative().getBonus(0)); // +2 BI, +4 tier, -1 no recon, +2 player
        assertSame(hq, modifiers(blue).orders.assignment(IntelBonus.HQ).unit());
    }

    @Test
    void subCommanderIsAnExplicitLeaderDesignationSharedAcrossAlliedOwners() {
        enable(INIT_FORCING);
        game.setPhase(GamePhase.LOUNGE);
        var sub = formation(11, ally);
        var second = formation(12, blue);
        var lead = sub.getUnits().getFirst();
        lead.getSpecialAbilities().setSUA(LEAD);
        for (int id = 20; id < 23; id++) {
            formation(id, blue);
        }
        assertFalse(SBFCommandDesignation.designate(game, ally.getId(), sub, 0, SCOM));
        formation(23, blue);
        assertTrue(SBFCommandDesignation.designate(game, ally.getId(), sub, 0, SCOM));
        second.getUnits().getFirst().getSpecialAbilities().setSUA(LEAD);
        assertFalse(SBFCommandDesignation.designate(game, blue.getId(), second, 0, SCOM));
        assertFalse(modifiers(blue).hasAbility(InitiativeAbility.FORCING)); // designation alone grants no ability
        assertTrue(SBFInitiativeCommand.select(game, ally, modifiers(ally), "FORCING", sub, 0));
        assertTrue(modifiers(blue).hasAbility(InitiativeAbility.FORCING));
        assertFalse(SBFInitiativeCommand.select(game, blue, modifiers(blue), "FORCING", second, 0));
    }

    @Test
    void initialSurveyLimitsSharedPlotsAndSeparatelyWeightsTheDefendersAreaKnowledge() {
        enable(INIT_BATTLEFIELD_INT);
        enable(ADVANCED_ARTILLERY);
        game.setCurrentRound(0);
        game.setPhase(GamePhase.DEPLOYMENT);
        game.setBoard(0, Board.createEmptyBoard(8, 8));
        var spotter = formation(10, blue);
        spotter.getSpecialAbilities().setSUA(RCN);
        spotter.getUnits().getFirst().setElements(List.of(element(true, true))); // raw blue BI 3
        var gun = formation(11, ally).getUnits().getFirst();
        gun.getSpecialAbilities().mergeSUA(ARTLT, 3);
        formation(12, ally);
        var opposing = formation(13, red);
        opposing.getSpecialAbilities().setSUA(RCN); // raw red BI 2; defender-adjusted 3
        formation(14, red);
        var snapshot = SBFInitiativeModifiers.initialSurvey(game);
        game.scenarioProgress().saveInitialIntelligence(snapshot);
        assertEquals(1, game.scenarioProgress().areaKnowledgeAllowance(1));
        game.scenarioProgress().setRole(3, megamek.common.strategicBattleSystems.SBFScenarioProgress.ScenarioRole.DEFENDER);
        assertEquals(0, game.scenarioProgress().areaKnowledgeAllowance(1)); // adjusted tie
        BoardLocation hex = BoardLocation.of(new Coords(0, 1), 0);
        BoardLocation another = BoardLocation.of(new Coords(1, 2), 0);
        assertTrue(SBFInitiativeCommand.preplot(game, ally, hex, false));
        assertFalse(SBFInitiativeCommand.preplot(game, blue, another, false)); // one gun Unit, three tubes
        assertFalse(SBFInitiativeCommand.preplot(game, red, another, false));
        assertTrue(game.scenarioProgress().isPreplotted(game, blue.getId(), hex));
        assertFalse(game.scenarioProgress().isPreplotted(game, red.getId(), hex));
        game.removeFormationFromPlay(spotter);
        assertEquals(snapshot, SBFInitiativeModifiers.initialSurvey(game));
        game.setPhase(GamePhase.MOVEMENT);
        assertFalse(SBFInitiativeCommand.preplot(game, blue, hex, true));
    }

    @Test
    void twoBankingForcesCancelMatchingBidsAndOnlyTheLoserBanksAMutualYield() {
        enable(INIT_BANKING);
        for (var owner : List.of(blue, red)) {
            var unit = formation(owner.getId() + 20, owner).getUnits().getFirst();
            unit.getSpecialAbilities().setSUA(COM);
            unit.getInitiativeAbilities().add(InitiativeAbility.BANKING);
        }
        var a = modifiers(blue);
        var b = modifiers(red);
        a.orders.creditYield();
        b.orders.creditYield();
        assertTrue(SBFInitiativeCommand.declare(game, blue, a, Declaration.YIELD));
        assertTrue(SBFInitiativeCommand.declare(game, red, b, Declaration.YIELD));
        manager.rollInitiative();
        assertEquals(1, a.orders.bankedPoints() + b.orders.bankedPoints());
        assertEquals(2, game.getTeamForPlayer(red).getInitiative().getKeptDice(0).size());
        // Give each at least one point. Tied expenditures cancel and both sides spend the declared point.
        var noPoint = a.orders.bankedPoints() == 0 ? a : b;
        noPoint.orders.creditYield();
        assertTrue(SBFInitiativeCommand.declare(game, blue, a, Declaration.SPEND));
        assertTrue(SBFInitiativeCommand.declare(game, red, b, Declaration.SPEND));
        manager.rollInitiative();
        assertEquals(0, a.orders.bankedPoints() + b.orders.bankedPoints());
        assertEquals(2, game.getTeamForPlayer(blue).getInitiative().getKeptDice(0).size());
    }

    private void enable(String option) {
        game.getOptions().getOption(option).setValue(true);
    }

    private SBFInitiativeModifiers modifiers(Player player) {
        return new SBFInitiativeModifiers(game, player);
    }

    private static AlphaStrikeElement element(boolean recon, boolean mhq) {
        var element = new AlphaStrikeElement();
        element.setType(ASUnitType.BM);
        element.setName("Element");
        if (recon) {
            element.getSpecialAbilities().setSUA(RCN);
        }
        if (mhq) {
            element.getSpecialAbilities().mergeSUA(MHQ, 1);
        }
        return element;
    }

    private SBFFormation formation(int id, Player owner) {
        var unit = new SBFUnit();
        unit.setArmor(8);
        unit.setType(SBFElementType.BM);
        var formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(owner.getId());
        formation.setType(SBFElementType.BM);
        formation.addUnit(unit);
        formation.setDeployed(true);
        formation.setPosition(BoardLocation.of(new Coords(id, 0), 0));
        game.addUnit(formation);
        return formation;
    }
}
