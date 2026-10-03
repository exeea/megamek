/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.battleArmor.BattleArmorHandles;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExteriorMovementUpdatesTest {
    private record Update(int id, Coords position, List<UnitLocation> path, List<Integer> riders, EntityMovementType type) { }

    @ParameterizedTest
    @CsvSource({ "true, 2", "true, 0", "false, 0" })
    void carrierPublishesItsUnloadWithTheRouteOnlyDuringMovement(boolean moving, int hexes) throws Exception {
        var previousRules = Game.rulesManager;
        try {
            var manager = spy(new TWGameManager());
            var updates = new ArrayList<Update>();
            doAnswer(call -> {
                Packet packet = call.getArgument(0);
                if (packet.command() == PacketCommand.ENTITY_UPDATE) {
                    var entity = packet.getEntity(1);
                    var path = packet.getUnitLocationVector(2);
                    updates.add(new Update(entity.getId(), entity.getPosition(), path == null ? List.of() : List.copyOf(path),
                          entity.getExternalUnits().stream().map(Entity::getId).toList(), entity.moved));
                }
                return null;
            }).when(manager).send(any(Packet.class));
            doNothing().when(manager).sendServerChat(anyString());
            var game = manager.getGame();
            game.initializeRulesManager(OptionsConstants.RULES_CORE);
            game.addPlayer(0, new Player(0, "Attachment review"));
            game.setBoard(new Board(7, 7, Stream.generate(Hex::new).limit(49).toArray(Hex[]::new)));
            game.setPhase(GamePhase.MOVEMENT);
            var carrier = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            carrier.setId(1);
            carrier.setOwner(game.getPlayer(0));
            carrier.setPosition(new Coords(3, 5));
            carrier.setDeployed(true);
            game.addEntity(carrier);
            var armor = new BattleArmor();
            armor.setId(2);
            armor.setOwner(game.getPlayer(0));
            armor.setSquadSize(4);
            for (int member = 1; member <= 4; member++) { armor.initializeInternal(1, member); }
            armor.setOriginalWalkMP(1);
            armor.setDeployed(true);
            game.addEntity(armor);
            var handles = spy(new BattleArmorHandles());
            doReturn(true).when(handles).canLoad(armor);
            carrier.addTransporter(handles);
            handles.load(armor);
            armor.setTransportId(carrier.getId());
            armor.setPosition(null);

            var path = new MovePath(game, carrier);
            for (int step = 0; step < hexes; step++) { path.addStep(MoveStepType.FORWARDS); }
            var arrival = path.getFinalCoords();
            var destination = arrival.translated(1);
            if (moving) {
                path.addStep(MoveStepType.UNLOAD, armor, destination);
                assertTrue(path.isMoveLegal());
                new MovePathHandler(manager, carrier, path, null).processMovement();
            } else {
                assertTrue(manager.unloadUnit(carrier, armor, destination, 1, 0));
            }

            assertEquals(arrival, carrier.getPosition());
            assertEquals(destination, armor.getPosition());
            assertEquals(Entity.NONE, armor.getTransportId());
            var release = updates.stream().filter(update -> update.id() == carrier.getId() && update.riders().isEmpty())
                  .findFirst().orElseThrow();
            assertEquals(!moving, release.path().isEmpty(), "Movement unloads must publish the carrier with its route");
            assertEquals(arrival, release.position());
            if (moving) { assertEquals(arrival, release.path().getLast().coords()); }
            if (hexes > 0) { assertEquals(EntityMovementType.MOVE_WALK, release.type()); }
        } finally { Game.rulesManager = previousRules; }
    }
}
