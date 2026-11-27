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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SBFUnit;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.server.GameManagerSaveHelper;
import megamek.server.Server;
import megamek.server.scriptedEvents.GameEndTriggeredEvent;
import megamek.server.trigger.SpecificRoundEndTrigger;
import org.junit.jupiter.api.Test;

class SBFGroundGameTest {

    @Test
    void twoPlayersDeployMoveDeclareFireAndReachTheScenarioEndThroughNormalInputs() {
        SBFGame game = new SBFGame();
        game.setBoard(0, Board.createEmptyBoard(8, 8));
        game.addScriptedEvent(new GameEndTriggeredEvent(new SpecificRoundEndTrigger(1)));
        for (int owner = 0; owner <= 1; owner++) {
            game.addPlayer(owner, new Player(owner, "Player " + owner));
            SBFFormation formation = new SBFFormation();
            formation.setId(owner + 10);
            formation.setOwnerId(owner);
            formation.setType(SBFElementType.BM);
            formation.setMovementMode(SBFMovementMode.MEK_WALK);
            formation.setMovement(4);
            formation.setSkill(0);
            SBFUnit unit = new SBFUnit();
            unit.setType(SBFElementType.BM);
            unit.setMovementMode(SBFMovementMode.MEK_WALK);
            unit.setMovement(4);
            unit.setArmor(10);
            unit.setDamage(ASDamageVector.createNormRndDmg(2, 2, 2));
            formation.addUnit(unit);
            game.addUnit(formation);
        }

        Server server = mock(Server.class);
        when(server.getGame()).thenReturn(game);
        try (var saves = mockConstruction(GameManagerSaveHelper.class); var servers = mockStatic(Server.class)) {
            SBFGameManager manager = new SBFGameManager();
            manager.setGame(game);
            servers.when(Server::getServerInstance).thenReturn(server);
            game.setPhase(GamePhase.EXCHANGE);
            manager.endCurrentPhase();
            assertEquals(GamePhase.DEPLOYMENT, game.getPhase());
            var deploy = manager.getCommandList(server).stream().filter(cmd -> cmd.getName().equals("sbfdeploy"))
                  .findFirst().orElseThrow();
            int winner = game.getTurn().playerId();
            deploy.run(winner, new String[]{"sbfdeploy", "second"});
            assertEquals(1 - winner, game.getTurn().playerId());
            for (int i = 0; i < 2; i++) {
                int owner = game.getTurn().playerId();
                deploy.run(owner, new String[]{"sbfdeploy", Integer.toString(owner + 10), owner == 0 ? "2" : "5", "4"});
            }
            assertEquals(1, game.getCurrentRound());
            assertEquals(GamePhase.INITIATIVE_REPORT, game.getPhase());
            acknowledge(manager);
            assertEquals(GamePhase.MOVEMENT, game.getPhase());
            for (int i = 0; i < 2; i++) {
                int owner = game.getTurn().playerId();
                SBFFormation formation = game.getFormation(owner + 10).orElseThrow();
                assertNotNull(formation.getPosition());
                BoardLocation start = formation.getPosition();
                BoardLocation destination = BoardLocation.of(new Coords(owner == 0 ? 2 : 3, 3), 0);
                SBFMovePath route = new SBFMovePath(formation.getId(), start, game);
                route.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), start, destination));
                manager.handlePacket(owner, new Packet(PacketCommand.ENTITY_MOVE, route));
                assertEquals(destination, formation.getPosition());
            }
            if (game.getPhase().isMovementReport()) {
                acknowledge(manager);
            }
            assertEquals(GamePhase.FIRING, game.getPhase());
            for (int i = 0; i < 2; i++) {
                int owner = game.getTurn().playerId();
                var shot = new SBFStandardUnitAttack(owner + 10, 0, 11 - owner, ASRange.LONG);
                manager.handlePacket(owner, new Packet(PacketCommand.ENTITY_ATTACK, owner + 10, new ArrayList<>(List.of(shot))));
            }
            if (game.getPhase().isFiringReport()) {
                acknowledge(manager);
            }
            assertEquals(GamePhase.VICTORY, game.getPhase());
            for (SBFFormation formation : game.getActiveFormations()) {
                assertEquals(8, formation.getUnits().getFirst().getCurrentArmor());
            }
            assertEquals(1, game.getCurrentRound());
        }
    }

    private static void acknowledge(SBFGameManager manager) {
        for (int owner = 0; owner <= 1; owner++) {
            manager.handlePacket(owner, new Packet(PacketCommand.PLAYER_READY, true));
        }
    }
}
