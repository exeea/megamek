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

import static megamek.common.alphaStrike.BattleForceSUA.CAR;
import static megamek.common.alphaStrike.BattleForceSUA.CK;
import static megamek.common.alphaStrike.BattleForceSUA.CT;
import static megamek.common.alphaStrike.BattleForceSUA.IT;
import static megamek.common.alphaStrike.BattleForceSUA.MEC;
import static megamek.common.alphaStrike.BattleForceSUA.MT;
import static megamek.common.alphaStrike.BattleForceSUA.MTxD;
import static megamek.common.alphaStrike.BattleForceSUA.SBF_OMNI;
import static megamek.common.alphaStrike.BattleForceSUA.XMEC;
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
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.ContactHit;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Exchange;
import megamek.common.strategicBattleSystems.SBFEngagementOrders.Order;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.TransportOrder;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFRadarZone;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import megamek.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class SBFTransportTest {
    private final SBFGame game = new SBFGame();
    private final SBFGameManager manager = mock(SBFGameManager.class);
    private final BoardLocation start = at(2, 3);
    private SBFFormation carrier;
    private SBFFormation infantry;

    @BeforeEach
    void setUp() {
        when(manager.getGame()).thenReturn(game);
        game.setPhase(GamePhase.MOVEMENT);
        game.setCurrentRound(3);
        game.addPlayer(1, new Player(1, "Blue"));
        game.addPlayer(2, new Player(2, "Red"));
        Board board = new Board(8, 8);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }
        game.setBoard(0, board);
        carrier = formation(10, 1, SBFElementType.V, start);
        carrier.getSpecialAbilities().replaceSUA(IT, 4);
        infantry = formation(11, 1, SBFElementType.CI, start);
        infantry.getSpecialAbilities().replaceSUA(CAR, 4);
        formation(12, 2, SBFElementType.V, at(7, 7));
        game.setTurns(List.of(new SBFFormationTurn(1), new SBFFormationTurn(2)));
        game.setTurnIndex(0, Player.PLAYER_NONE);
    }

    @Test
    void commandPicksUpAtStartDropsMidMoveAndRestoresPassengerTurn() {
        SBFGameManager live = new SBFGameManager();
        live.setGame(game);
        Server server = mock(Server.class);
        try (MockedStatic<Server> serverStatic = mockStatic(Server.class)) {
            serverStatic.when(Server::getServerInstance).thenReturn(server);
            SBFTransportCommand command = new SBFTransportCommand(server, live);
            String[] orders = { "sbftransport", "10", "mount:11", "3,3", "drop:11", "3,2" };
            command.run(2, orders);
            assertEquals(start, carrier.getPosition());
            command.run(1, orders);
        }
        assertEquals(at(2, 1), carrier.getPosition());
        assertEquals(at(2, 2), infantry.getPosition());
        assertFalse(infantry.isEmbarked());
        assertTrue(infantry.isEligibleForPhase(GamePhase.MOVEMENT));
        assertTrue(infantry.isEligibleForPhase(GamePhase.FIRING));
        assertEquals(3, SBFMovePath.normalMovementPoints(infantry, game));
        assertEquals(List.of(1, 2, 1), game.getTurnsList().stream().map(t -> t.playerId()).toList());
        game.setCurrentRound(4);
        assertEquals(4, SBFMovePath.normalMovementPoints(infantry, game));
    }

    @Test
    void nonInfantryBoardingUsesPassengerTurnAllElementsAndDoorsThenChargesFourMpToUnload() {
        SBFFormation passenger = formation(24, 1, SBFElementType.BM, at(2, 2));
        AlphaStrikeElement mek = new AlphaStrikeElement();
        mek.setType(ASUnitType.BM);
        passenger.getUnits().getFirst().setElements(List.of(mek, mek));
        passenger.setMovement(3);
        carrier.getSpecialAbilities().replaceSUA(MT, 1);
        carrier.getSpecialAbilities().replaceSUA(MTxD, 1);
        SBFMovePath route = new SBFMovePath(24, at(2, 2), game);
        route.boardCarrierAtEnd(10);
        var movement = new SBFMovementProcessor(manager);
        assertFalse(movement.processMovement(route, passenger));
        carrier.getSpecialAbilities().replaceSUA(MT, 3);
        assertTrue(movement.processMovement(route, passenger));
        assertTrue(passenger.isCarriedBy(10));
        assertEquals(3, passenger.getMpSpentThisTurn());
        assertEquals(1, carrier.bayDoorsUsed(3));
        assertFalse(SBFBayTransport.unload(manager, 1, passenger, carrier, at(2, 2)));
        game.setCurrentRound(4);
        passenger.setDone(false);
        passenger.setMovement(6);
        assertFalse(SBFBayTransport.unload(manager, 1, passenger, carrier, at(5, 5)));
        assertTrue(SBFBayTransport.unload(manager, 1, passenger, carrier, at(2, 2)));
        assertEquals(2, SBFMovePath.normalMovementPoints(passenger, game));
        assertEquals(0, carrier.bayDoorsUsed(4));
    }

    @Test
    void landedCarrierFitsMixedInfantryAndMeksAndChargesBayDismountRate() {
        SBFFormation mixed = formation(24, 1, SBFElementType.CI, at(2, 2));
        AlphaStrikeElement mek = new AlphaStrikeElement();
        mek.setType(ASUnitType.BM);
        AlphaStrikeElement foot = new AlphaStrikeElement();
        foot.setType(ASUnitType.CI);
        foot.getSpecialAbilities().replaceSUA(CAR, 2);
        mixed.getUnits().getFirst().setElements(List.of(foot, mek));
        carrier.setMovementMode(SBFMovementMode.VTOL);
        carrier.getSpecialAbilities().replaceSUA(MT, 1);
        carrier.getSpecialAbilities().replaceSUA(MTxD, 1);
        var route = new SBFMovePath(24, at(2, 2), game);
        route.boardCarrierAtEnd(10);
        carrier.getSpecialAbilities().replaceSUA(IT, 1);
        carrier.setAirVehicleLanded(true);
        assertFalse(SBFBayTransport.canBoard(game, mixed, route));
        carrier.getSpecialAbilities().replaceSUA(IT, 2);
        carrier.setAirVehicleLanded(false);
        assertFalse(SBFBayTransport.canBoard(game, mixed, route));
        carrier.setAirVehicleLanded(true);
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, mixed));
        game.setCurrentRound(4);
        mixed.setDone(false);
        carrier.setAirVehicleLanded(false);
        assertFalse(SBFBayTransport.unload(manager, 1, mixed, carrier, at(2, 2)));
        carrier.setAirVehicleLanded(true);
        assertTrue(SBFBayTransport.unload(manager, 1, mixed, carrier, at(2, 2)));
        assertEquals(4, mixed.dismountCost(4));
    }

    @Test
    void civilianCargoUsesActualWeightOneUnitPerTurnAndThreeTurnsToUnpack() {
        carrier.setType(SBFElementType.LA);
        carrier.setMovementMode(SBFMovementMode.SPHEROID);
        var passenger = formation(24, 1, SBFElementType.BM, at(2, 2));
        var element = new AlphaStrikeElement();
        element.setType(ASUnitType.BM);
        element.setTransportTonnage(10);
        passenger.getUnits().getFirst().setElements(List.of(element));
        carrier.getSpecialAbilities().replaceSUA(CT, 10);
        carrier.getSpecialAbilities().replaceSUA(CK, 999);
        assertFalse(SBFCargoTransport.load(manager, 1, passenger, 0, carrier));
        carrier.getSpecialAbilities().replaceSUA(CK, 1000);
        assertTrue(SBFCargoTransport.load(manager, 1, passenger, 0, carrier));
        assertEquals(11.0, passenger.packedCargoTons());
        assertFalse(SBFAirDrop.mayDrop(passenger));
        assertFalse(SBFCargoTransport.unload(manager, 1, passenger, carrier, at(2, 2)));
        game.setCurrentRound(4);
        passenger.setDone(false);
        assertFalse(SBFBayTransport.unload(manager, 1, passenger, carrier, at(2, 2)));
        assertTrue(SBFCargoTransport.unload(manager, 1, passenger, carrier, at(2, 2)));
        passenger.setDone(false);
        assertFalse(SBFFormationTurn.isEligible(passenger, GamePhase.MOVEMENT, 6));
        assertTrue(SBFFormationTurn.isEligible(passenger, GamePhase.MOVEMENT, 7));
        assertFalse(SBFFormationTurn.isEligible(passenger, GamePhase.FIRING, 6));
        assertTrue(SBFFormationTurn.isEligible(passenger, GamePhase.FIRING, 7));
    }

    @Test
    void airborneVtolDropsJumpInfantryAtEndAndFailedLandingDamagesEachUnit() {
        carrier.setMovementMode(SBFMovementMode.VTOL);
        infantry.embark(10, false);
        var route = path();
        route.addTransportOrder(new TransportOrder(0, 11, false));
        assertTrue(SBFTransport.plan(game, carrier, route).isEmpty());
        infantry.setJumpMove(1);
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, carrier));
        assertEquals(start, infantry.getDropDestination());
        assertNull(infantry.getPosition());
        assertEquals(List.of(1), new SBFAirDrop(manager).landAll(() -> 4, () -> 0));
        assertEquals(start, infantry.getPosition());
        assertEquals(6, infantry.getUnits().getFirst().getCurrentArmor());
        assertEquals(2, SBFMovePath.normalMovementPoints(infantry, game));
    }

    @Test
    void previouslyMovedEnemyInCentralCanEngageAnImmediatelyReleasedHighDrop() {
        carrier.setType(SBFElementType.AS);
        carrier.setRadarPosition(SBFRadarZone.INNER, List.of());
        var enemy = game.getFormation(12).orElseThrow();
        enemy.setType(SBFElementType.AS);
        enemy.setRadarPosition(SBFRadarZone.CENTRAL, List.of(start));
        enemy.setDone(true);
        infantry.setJumpMove(1);
        infantry.embark(10, false);
        game.engagementOrders().order(enemy.getId(), infantry.getId(), true);
        try (var dice = mockStatic(Compute.class)) {
            dice.when(() -> Compute.d6(2)).thenReturn(2, 12);
            assertTrue(new SBFAirDrop(manager).fromRadar(1, infantry, carrier, null, true));
        }
        assertTrue(enemy.isEngagedWith(infantry.getId()));
        assertFalse(infantry.mayAttackAirOpponent(enemy.getId()));
        assertFalse(carrier.isDone());
    }

    @Test
    void highDropRemainsInTheAirForOneRoundThenCannotWalkAndFire() {
        carrier.setType(SBFElementType.AS);
        carrier.setRadarPosition(SBFRadarZone.INNER, List.of());
        infantry.setJumpMove(1);
        infantry.embark(10, false);
        var drop = new SBFAirDrop(manager);
        assertTrue(drop.fromRadar(1, infantry, carrier, null, true));
        assertTrue(infantry.inHighDrop());
        assertFalse(infantry.isEligibleForPhase(GamePhase.FIRING));
        assertFalse(drop.aimHigh(1, infantry, at(3, 3)));
        game.setCurrentRound(4);
        infantry.setDone(false);
        assertFalse(drop.aimHigh(1, infantry, at(7, 7)));
        assertTrue(drop.aimHigh(1, infantry, at(3, 3)));
        assertEquals(List.of(1), drop.landAll(() -> 5, () -> 0));
        assertFalse(infantry.inHighDrop());
        assertEquals(1, SBFMovePath.normalMovementPoints(infantry, game));
        assertTrue(infantry.isEligibleForPhase(GamePhase.FIRING));
        infantry.setMpSpentThisTurn(1);
        assertFalse(infantry.isEligibleForPhase(GamePhase.FIRING));
    }

    @Test
    void exceededCapacityLateMountAndOverBudgetRouteNeverPartiallyLoad() {
        carrier.getSpecialAbilities().replaceSUA(IT, 3);
        SBFMovePath route = path(at(2, 2));
        route.addTransportOrder(new TransportOrder(0, 11, true));
        assertFalse(new SBFMovementProcessor(manager).processMovement(route, carrier));
        carrier.getSpecialAbilities().replaceSUA(IT, 4);
        SBFMovePath late = path(at(2, 2));
        late.addTransportOrder(new TransportOrder(1, 11, true));
        assertFalse(new SBFMovementProcessor(manager).processMovement(late, carrier));
        carrier.setMovement(1);
        assertFalse(new SBFMovementProcessor(manager).processMovement(route, carrier));
        assertFalse(infantry.isEmbarked());
        assertEquals(start, carrier.getPosition());
        assertFalse(carrier.isDone());
    }

    @Test
    void mountedFormationHasNoBoardMarkerMovementFireOrDirectTarget() {
        SBFMovePath route = path();
        route.addTransportOrder(new TransportOrder(0, 11, true));
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, carrier));
        assertNull(infantry.getPosition());
        assertFalse(infantry.isEligibleForPhase(GamePhase.MOVEMENT));
        assertFalse(infantry.isEligibleForPhase(GamePhase.FIRING));
        assertFalse(new SBFStandardUnitAttack(11, 0, 12, ASRange.LONG).isDataValid(game));
        assertFalse(new SBFStandardUnitAttack(12, 0, 11, ASRange.LONG).isDataValid(game));
        assertEquals(List.of(infantry), game.passengersOf(carrier));
    }

    @Test
    void externalBattleArmorRequiresEnoughOmniSlotsOrXmecAndReducesXmecCarrierMove() {
        carrier.getSpecialAbilities().removeSUA(IT);
        infantry.setType(SBFElementType.BA);
        infantry.getSpecialAbilities().setSUA(MEC);
        infantry.getUnits().getFirst().setElements(List.of(new AlphaStrikeElement(), new AlphaStrikeElement()));
        carrier.getSpecialAbilities().replaceSUA(SBF_OMNI, 1);
        SBFMovePath route = path();
        route.addTransportOrder(new TransportOrder(0, 11, true));
        assertTrue(SBFTransport.plan(game, carrier, route).isEmpty());
        carrier.getSpecialAbilities().replaceSUA(SBF_OMNI, 2);
        assertTrue(SBFTransport.plan(game, carrier, route).isPresent());
        carrier.getSpecialAbilities().removeSUA(SBF_OMNI);
        carrier.getUnits().getFirst().setElements(List.of(new AlphaStrikeElement(), new AlphaStrikeElement()));
        infantry.getSpecialAbilities().setSUA(XMEC);
        assertTrue(new SBFMovementProcessor(manager).processMovement(route, carrier));
        assertTrue(infantry.isExternallyMounted());
        assertEquals(3, SBFMovePath.normalMovementPoints(carrier, game));
    }

    @Test
    void losingOneCarrierUnitForcesDismountInNearestAvailableStack() {
        infantry.embark(carrier.getId(), false);
        formation(19, 1, SBFElementType.CI, start);
        SBFUnit casualty = new SBFUnit();
        casualty.setArmor(4);
        casualty.setCurrentArmor(0);
        carrier.addUnit(casualty);
        SBFCombatDamage.removeDestroyedUnits(manager, carrier);
        assertFalse(infantry.isEmbarked());
        assertEquals(1, start.coords().distance(infantry.getPosition().coords()));
        assertEquals(1, carrier.getUnits().size());
        assertTrue(infantry.dismountedIn(3));
    }

    @Test
    void overrunDestroysTheNewCarrierAndReturnsItsPassengerAtContactWithAMovementTurn() {
        BoardLocation contact = at(2, 2);
        SBFFormation enemy = game.getFormation(12).orElseThrow();
        enemy.setPosition(contact);
        SBFMovePath route = path(contact, at(2, 1));
        route.addTransportOrder(new TransportOrder(0, infantry.getId(), true));
        route.addTransportOrder(new TransportOrder(2, infantry.getId(), false));
        var hit = new ContactHit(enemy.getId(), carrier.getId(), 8, Player.PLAYER_NONE, 0);
        game.engagementOrders().setExchange(new Exchange(carrier.getId(), enemy.getId(), Order.OVERRUN, true, -1,
              List.of(hit)));
        assertTrue(new SBFContactDamage(manager, () -> 7).resolve(carrier, enemy, contact, route, 1));
        assertTrue(game.getGraveyard().contains(carrier));
        assertEquals(contact, infantry.getPosition());
        assertTrue(infantry.dismountedIn(3));
        assertEquals(1, route.processedTransportOrders());
        assertEquals(1, route.spentTransportMP());
        assertEquals(List.of(1, 2, 1), game.getTurnsList().stream().map(t -> t.playerId()).toList());
    }

    @Test
    void emergencyDismountIntoProhibitedWaterDestroysTheCarriedInfantry() {
        infantry.embark(carrier.getId(), false);
        game.getHex(start).addTerrain(new Terrain(Terrains.WATER, 2));
        carrier.getUnits().getFirst().setCurrentArmor(0);
        SBFCombatDamage.removeDestroyedUnits(manager, carrier);
        assertTrue(game.getGraveyard().containsAll(List.of(carrier, infantry)));
        assertEquals(0, infantry.getUnits().getFirst().getCurrentArmor());
    }

    private SBFMovePath path(BoardLocation... destinations) {
        SBFMovePath result = new SBFMovePath(carrier.getId(), start, game);
        BoardLocation previous = start;
        for (BoardLocation end : destinations) {
            result.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, carrier.getId(), previous, end));
            previous = end;
        }
        return result;
    }

    private SBFFormation formation(int id, int owner, SBFElementType type, BoardLocation location) {
        SBFFormation f = new SBFFormation();
        f.setId(id);
        f.setName("Formation " + id);
        f.setOwnerId(owner);
        f.setType(type);
        SBFMovementMode mode = type == SBFElementType.V ? SBFMovementMode.TRACKED : SBFMovementMode.INFANTRY_FOOT;
        f.setMovementMode(mode);
        f.setMovement(4);
        f.setDeployed(true);
        f.setPosition(location);
        SBFUnit unit = new SBFUnit();
        unit.setType(type);
        unit.setMovement(4);
        unit.setMovementMode(mode);
        unit.setArmor(8);
        f.addUnit(unit);
        game.addUnit(f);
        return f;
    }

    private static BoardLocation at(int x, int y) {
        return BoardLocation.of(new Coords(x, y), 0);
    }
}
