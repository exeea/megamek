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
import static megamek.common.alphaStrike.BattleForceSUA.IT;
import static megamek.common.alphaStrike.BattleForceSUA.MEC;
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
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePath.TransportOrder;
import megamek.common.strategicBattleSystems.SBFMovementMode;
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
