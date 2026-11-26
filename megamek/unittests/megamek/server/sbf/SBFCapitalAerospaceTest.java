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

import static megamek.common.strategicBattleSystems.SBFCapitalSector.parse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.enums.GamePhase;
import megamek.common.options.OptionsConstants;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SBFCapitalAerospaceTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private SBFFormation fighter;

    @BeforeEach
    void setup() {
        when(manager.getGame()).thenReturn(game);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_AEROSPACE).setValue(true);
        game.addPlayer(1, new Player(1, "North"));
        game.addPlayer(2, new Player(2, "South"));
        game.setCurrentRound(0);
        fighter = squadron(11, 1);
        turn(1, GamePhase.DEPLOYMENT);
    }

    private SBFFormation squadron(int id, int owner) {
        var squadron = new SBFFormation();
        squadron.setId(id);
        squadron.setOwnerId(owner);
        squadron.setType(SBFElementType.AS);
        squadron.setMovement(24);
        squadron.setMovementMode(SBFMovementMode.AERODYNE);
        squadron.getSpecialAbilities().setSUA(BattleForceSUA.SPC);
        var unit = new SBFUnit();
        unit.setType(SBFElementType.AS);
        unit.setMovementMode(SBFMovementMode.AERODYNE);
        unit.setArmor(3);
        unit.setCurrentArmor(3);
        unit.setPointValue(15);
        unit.getSpecialAbilities().replaceSUA(BattleForceSUA.FUEL, 4);
        squadron.addUnit(unit);
        game.addUnit(squadron);
        return squadron;
    }

    private void turn(int owner, GamePhase phase) {
        game.setPhase(phase);
        game.setTurns(List.of(new SBFFormationTurn(owner)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
    }

    @Test
    void sectorGeometryIsSymmetricAndHonorsPrintedCardinalBearings() {
        var sectors = java.util.Arrays.stream(SBFCapitalSector.Ring.values())
              .flatMap(r -> java.util.stream.IntStream.rangeClosed(1, r.sectors)
                    .mapToObj(n -> new SBFCapitalSector(r, n))).toList();
        assertEquals(49, sectors.size());
        for (var a : sectors) {
            for (var b : sectors) {
                assertEquals(a.adjacent(b), b.adjacent(a));
                assertEquals(a.oppositeBearing(b), b.oppositeBearing(a));
            }
            assertEquals(a, parse(a.toString()));
        }
        assertTrue(parse("CENTRAL").adjacent(parse("I4")));
        assertTrue(parse("I1").adjacent(parse("M12")));
        assertTrue(parse("M1").adjacent(parse("O2")));
        assertTrue(parse("M3").adjacent(parse("O6")));
        assertTrue(parse("P6").adjacent(parse("P1")));
        assertTrue(parse("O1").oppositeBearing(parse("P4")));
        assertFalse(parse("I1").adjacent(parse("O1")));
        assertFalse(parse("P1").adjacent(parse("P3")));
        assertThrows(IllegalArgumentException.class, () -> parse("O25"));
    }

    @Test
    void capitalMovementValidatesOwnershipAdjacencyAndStartingZoneAllowanceBeforeMutating() {
        assertFalse(SBFSpaceCommand.deploy(manager, 2, fighter, parse("O1")));
        assertFalse(SBFSpaceCommand.deploy(manager, 1, fighter, parse("M1")));
        assertTrue(SBFSpaceCommand.deploy(manager, 1, fighter, parse("O1")));
        var opponent = squadron(21, 2);
        turn(2, GamePhase.DEPLOYMENT);
        assertFalse(SBFSpaceCommand.deploy(manager, 2, opponent, parse("P2")));
        assertTrue(SBFSpaceCommand.deploy(manager, 2, opponent, parse("P4")));

        turn(1, GamePhase.MOVEMENT);
        assertFalse(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("M1"), parse("P1")), false));
        assertEquals(parse("O1"), fighter.capitalSector());
        assertFalse(fighter.isDone());
        assertFalse(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("M1"), parse("I1"), parse("CENTRAL")), false));
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("M1"), parse("I1")), false));
        assertEquals(parse("I1"), fighter.capitalSector());
        assertNull(fighter.getPosition());
        assertFalse(fighter.isOnRadarMap());
        assertTrue(fighter.isDone());
        SBFSpaceCommand.endTurn(manager);
        assertEquals(4, fighter.spaceFuel());
    }

    @Test
    void returningToSameSectorCostsFuelOnceHoldingDoesNotAndDriftScoresForTheOpponent() {
        fighter.setDeployed(true);
        fighter.setCapitalSector(parse("O1"));
        turn(1, GamePhase.MOVEMENT);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(), false));
        SBFSpaceCommand.endTurn(manager);
        assertEquals(4, fighter.spaceFuel());
        game.setCurrentRound(1);
        fighter.setDone(false);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("O2"), parse("O1")), false));
        SBFSpaceCommand.endTurn(manager);
        SBFSpaceCommand.endTurn(manager);
        assertEquals(1, fighter.spaceFuel());
        game.setCurrentRound(2);
        fighter.setDone(false);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("P1")), false));
        SBFSpaceCommand.endTurn(manager);
        assertEquals(0, fighter.capitalThrust());
        assertTrue(fighter.isDrifting());
        assertTrue(fighter.isCrippled());
        assertEquals(60L, game.scenarioProgress().scores(game, game.getPlayersList()).get(2));
        assertTrue(game.scenarioProgress().losses().isEmpty());
        fighter.setDone(false);
        assertFalse(SBFSpaceCommand.move(manager, 1, fighter, List.of(), true));
    }

    @Test
    void bombLoadAndStationKeepingCanReduceThrustToZeroAndPeripheralExitRemovesTheSquadron() {
        fighter.setDeployed(true);
        fighter.setCapitalSector(parse("P1"));
        turn(1, GamePhase.MOVEMENT);
        fighter.setBombLoad(24, 0);
        assertEquals(0, fighter.capitalThrust());
        assertFalse(SBFSpaceCommand.move(manager, 1, fighter, List.of(), true));
        fighter.setBombLoad(0, 0);
        fighter.setMovementMode(SBFMovementMode.STATION_KEEPING);
        assertEquals(0, fighter.capitalThrust());
        fighter.setMovementMode(SBFMovementMode.AERODYNE);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(), true));
        assertFalse(game.getActiveFormations().contains(fighter));
    }

    @Test
    void opposingArrivalCreatesASectorLocalMapAndEntryWaitsUntilTheFollowingTurn() {
        var opponent = squadron(21, 2);
        opponent.setDeployed(true);
        opponent.setCapitalSector(parse("M1"));
        fighter.setDeployed(true);
        fighter.setCapitalSector(parse("I1"));
        turn(1, GamePhase.MOVEMENT);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("M1")), false));
        assertEquals(SBFRadarZone.CENTRAL, opponent.engagementZone());
        assertEquals(SBFRadarZone.OUTER_6, fighter.engagementZone());
        assertFalse(fighter.isOnRadarMap());
        assertTrue(fighter.waitingForSpaceCombat(0));
        fighter.setDone(false);
        var local = new SBFMovePath.RadarFlight(List.of(SBFRadarZone.OUTER_5), List.of(), false);
        assertFalse(new SBFAerospace(manager).move(1, fighter, local));
        game.setCurrentRound(1);
        assertTrue(new SBFAerospace(manager).move(1, fighter, local));
        SBFSpaceCommand.endTurn(manager);
        assertEquals(parse("M1"), fighter.capitalSector());
        assertEquals(SBFRadarZone.OUTER_5, fighter.engagementZone());
        assertEquals(3, fighter.spaceFuel());
        fighter.setDone(false);
        assertFalse(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("O2")), false));
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("I1")), false));
        assertNull(opponent.engagementZone());
        assertNull(fighter.engagementZone());
    }

    @Test
    void separateSectorsNeverShareCombatRangeEvenWhenLocalZonesMatch() {
        var opponent = squadron(21, 2);
        fighter.setCapitalSector(parse("M1"));
        opponent.setCapitalSector(parse("M2"));
        fighter.enterEngagementMap(SBFRadarZone.INNER, 0);
        opponent.enterEngagementMap(SBFRadarZone.INNER, 0);
        fighter.setEngagedWith(21, true);
        assertFalse(SBFCombatOrders.sameEngagementArea(game, fighter, opponent));
        assertTrue(SBFToHitData.standardAttackRange(game, fighter, opponent).isEmpty());
        fighter.assignPhaseCombatArea(1);
        candidateTurnDoesNotChangeWhenMapDissolves(fighter);
    }

    private void candidateTurnDoesNotChangeWhenMapDissolves(SBFFormation formation) {
        String scheduled = formation.assignPhaseCombatArea(1);
        formation.setEngagementZone(null);
        formation.setDeployed(true);
        game.setPhase(GamePhase.MOVEMENT);
        formation.setEngagedWith(21, false);
        assertTrue(new SBFFormationTurn(1, scheduled).isValidEntity(formation, game));
        assertFalse(new SBFFormationTurn(1, "1-capital").isValidEntity(formation, game));
    }

    @Test
    void gravityRemovesTheSquadronFromTheCapitalMapOnlyAtEndAndHonorsItsOwnersBearing() {
        fighter.setDeployed(true);
        fighter.setMovement(1);
        fighter.enterCapitalAtmosphere(SBFRadarZone.OUTER_12);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_GROUND_CENTER).setValue(true);
        turn(1, GamePhase.MOVEMENT);
        assertFalse(SBFSpaceCommand.setFallZone(game, 2, fighter, SBFRadarZone.OUTER_3));
        assertTrue(SBFSpaceCommand.setFallZone(game, 1, fighter, SBFRadarZone.OUTER_3));
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(), false));
        assertEquals(parse("CENTRAL"), fighter.capitalSector());
        SBFSpaceCommand.endTurn(manager);
        assertNull(fighter.capitalSector());
        assertEquals(SBFRadarZone.OUTER_3, fighter.getRadarZone());
        assertTrue(new SBFAerospace(manager).removeCrashedSquadrons().isEmpty());
    }

    @Test
    void stationFallsToOuterFirstThenBreaksUpAndDestroysCargoAtTheFollowingEnd() {
        fighter.setDeployed(true);
        fighter.setMovementMode(SBFMovementMode.STATION_KEEPING);
        fighter.getUnits().getFirst().setMovementMode(SBFMovementMode.STATION_KEEPING);
        fighter.enterCapitalAtmosphere(SBFRadarZone.OUTER_12);
        var cargo = squadron(12, 1);
        cargo.setDeployed(true);
        cargo.embark(fighter.getId(), false);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_GROUND_CENTER).setValue(true);
        SBFSpaceCommand.endTurn(manager);
        assertTrue(fighter.fallingFromOrbit());
        var radar = new SBFAerospace(manager);
        assertTrue(radar.removeCrashedSquadrons().isEmpty());
        game.setCurrentRound(1);
        assertEquals(2, radar.removeCrashedSquadrons().size());
        assertTrue(game.getFormation(11).isEmpty());
        assertTrue(game.getFormation(12).isEmpty());
        assertEquals(2, game.scenarioProgress().losses().size());
    }

    @Test
    void fallingWarShipCanClimbOnlyWithFourThrustAndSuccessfulControl() {
        fighter.setDeployed(true);
        fighter.setMovementMode(SBFMovementMode.WARSHIP);
        fighter.getUnits().getFirst().setMovementMode(SBFMovementMode.WARSHIP);
        fighter.setSkill(2);
        fighter.setCurrentMovement(1);
        fighter.enterCapitalAtmosphere(SBFRadarZone.OUTER_12);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_GROUND_CENTER).setValue(true);
        SBFSpaceCommand.endTurn(manager);
        fighter.setCurrentMovement(4);
        game.setCurrentRound(1);
        try (var dice = mockStatic(megamek.common.compute.Compute.class)) {
            dice.when(() -> megamek.common.compute.Compute.d6(2)).thenReturn(8);
            assertTrue(new SBFAerospace(manager).removeCrashedSquadrons().isEmpty());
        }
        assertEquals(parse("CENTRAL"), fighter.capitalSector());
        assertFalse(fighter.fallingFromOrbit());
    }

    @Test
    void lowFuelRecoveryChecksCapacityAndTwoCompleteTurnsBeforeRelaunchFromTheCarriersCurrentSector() {
        fighter.setDeployed(true);
        fighter.setSkill(2);
        fighter.setCapitalSector(parse("O1"));
        fighter.markSpaceManeuver(0);
        fighter.expendSpaceFuel(0);
        assertEquals(1, fighter.spaceFuel());
        var carrier = squadron(12, 1);
        carrier.setDeployed(true);
        carrier.setCapitalSector(parse("O2"));
        turn(1, GamePhase.MOVEMENT);
        carrier.getSpecialAbilities().replaceSUA(BattleForceSUA.AT, 1);
        carrier.getSpecialAbilities().replaceSUA(BattleForceSUA.ATxD, 1);
        assertFalse(SBFSpaceCommand.handleRecovery(manager, 1, fighter, carrier, false));
        carrier.setCapitalSector(parse("O1"));
        fighter.enterEngagementMap(SBFRadarZone.A, -1);
        carrier.enterEngagementMap(SBFRadarZone.B, -1);
        assertFalse(SBFSpaceCommand.handleRecovery(manager, 1, fighter, carrier, false));
        carrier.setEngagementZone(SBFRadarZone.A);
        assertTrue(SBFSpaceCommand.handleRecovery(manager, 1, fighter, carrier, false));
        assertTrue(fighter.isCarriedBy(12));
        assertTrue(carrier.recoveringFighters(0));
        assertNull(fighter.capitalSector());
        carrier.setCapitalSector(parse("I2"));
        game.setCurrentRound(2);
        assertFalse(SBFSpaceCommand.handleRecovery(manager, 1, fighter, carrier, true));
        game.setCurrentRound(3);
        fighter.setDone(false);
        assertTrue(game.getTurn().isValid(1, fighter, game));
        assertTrue(SBFSpaceCommand.handleRecovery(manager, 1, fighter, carrier, true));
        assertEquals(4, fighter.spaceFuel());
        assertEquals(parse("I2"), fighter.capitalSector());
        assertFalse(fighter.isEmbarked());
        assertTrue(fighter.isDone());
    }

    @Test
    void unstreamlinedSquadronStaysOnTheOuterRingWithDesignQuirks() {
        // Negative Design Quirks Table, IO:BF p. 229: Unstreamlined (Element; AS, LA) "If any Element in the Squadron
        // has this quirk, it may not enter the atmosphere"; like a WarShip it stays on the Outer Ring (p. 184).
        fighter.setCapitalSector(parse("I1"));
        fighter.setDeployed(true);
        turn(1, GamePhase.MOVEMENT);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_GROUND_CENTER).setValue(true);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("CENTRAL")), false));
        fighter.setDone(false);
        var dropShip = new AlphaStrikeElement();
        dropShip.setType(ASUnitType.DS);
        dropShip.getQuirks().getOption(OptionsConstants.QUIRK_NEG_UNSTREAMLINED).setValue(true);
        fighter.getUnits().getFirst().setElements(List.of(dropShip));
        var inward = new SBFMovePath.RadarFlight(List.of(SBFRadarZone.A), List.of(), false);
        game.getOptions().getOption(SBFRuleOptions.DESIGN_QUIRKS).setValue(true);
        assertFalse(new SBFAerospace(manager).move(1, fighter, inward));
        game.getOptions().getOption(SBFRuleOptions.DESIGN_QUIRKS).setValue(false);
        assertTrue(new SBFAerospace(manager).move(1, fighter, inward));
    }

    @Test
    void groundCenteredEntryOccupiesBothMapsAndAtmosphericDescentClearsCapitalSector() {
        fighter.setCapitalSector(parse("I1"));
        fighter.setDeployed(true);
        turn(1, GamePhase.MOVEMENT);
        game.getOptions().getOption(SBFRuleOptions.CAPITAL_GROUND_CENTER).setValue(true);
        assertTrue(SBFSpaceCommand.move(manager, 1, fighter, List.of(parse("CENTRAL")), false));
        assertEquals(SBFRadarZone.OUTER_12, fighter.getRadarZone());
        assertEquals(parse("CENTRAL"), fighter.capitalSector());
        fighter.setDone(false);
        var inward = new SBFMovePath.RadarFlight(List.of(SBFRadarZone.A), List.of(), false);
        fighter.getUnits().getFirst().setMovementMode(SBFMovementMode.WARSHIP);
        assertFalse(new SBFAerospace(manager).move(1, fighter, inward));
        fighter.getUnits().getFirst().setMovementMode(SBFMovementMode.AERODYNE);
        assertTrue(new SBFAerospace(manager).move(1, fighter, inward));
        assertEquals(SBFRadarZone.A, fighter.getRadarZone());
        assertNull(fighter.capitalSector());
    }
}
