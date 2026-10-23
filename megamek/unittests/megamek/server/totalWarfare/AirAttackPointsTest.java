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
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * The attack event places an aircraft over a ground map where the rules resolve the attack, not at its end-of-move
 * hex: an air-to-ground shot fires from the approach hex of the target, and a ground unit shooting at an aircraft
 * fires at the aircraft's closest flight path hex. A Shilone-like fighter flies east along row 5, from 0206 to 0706.
 */
class AirAttackPointsTest {

    private static final int FACING_EAST = 2;

    private TWGameManager gameManager;
    private Game game;
    private Player attackerOwner;
    private Player defenderOwner;
    private AeroSpaceFighter fighter;

    @BeforeAll
    static void beforeAll() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void beforeEach() {
        gameManager = Mockito.spy(new TWGameManager());
        Mockito.doNothing().when(gameManager).send(any(Packet.class));
        Mockito.doNothing().when(gameManager).entityUpdate(anyInt());
        game = gameManager.getGame();
        int width = 16;
        int height = 17;
        game.setBoard(0, new Board(width, height,
              Stream.generate(Hex::new).limit((long) width * height).toArray(Hex[]::new)));

        attackerOwner = new Player(0, "Attacker");
        defenderOwner = new Player(1, "Defender");
        attackerOwner.setTeam(1);
        defenderOwner.setTeam(2);
        game.addPlayer(0, attackerOwner);
        game.addPlayer(1, defenderOwner);

        fighter = fighter(attackerOwner, "Striker");
        Vector<Coords> path = new Vector<>();
        List<Integer> facings = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            path.add(new Coords(x, 5));
            facings.add(FACING_EAST);
        }
        fighter.setPassedThrough(path);
        fighter.setPassedThroughFacing(facings);
        fighter.setPassedThroughBoardId(0);
        fighter.setPosition(new Coords(6, 5));
        fighter.setFacing(FACING_EAST);
    }

    private AeroSpaceFighter fighter(Player owner, String model) {
        AeroSpaceFighter aircraft = new AeroSpaceFighter();
        aircraft.setGame(game);
        aircraft.setId(game.getNextEntityId());
        aircraft.setChassis("Test Fighter");
        aircraft.setModel(model);
        aircraft.setCrew(new Crew(CrewType.SINGLE));
        aircraft.setOwner(owner);
        aircraft.setWeight(50);
        game.addEntity(aircraft);
        aircraft.setBoardId(0);
        aircraft.setAltitude(3);
        aircraft.setDeployed(true);
        return aircraft;
    }

    private BipedMek mek(Coords position) {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setId(game.getNextEntityId());
        mek.setChassis("Test Mek");
        mek.setModel("Hunchback");
        mek.setCrew(new Crew(CrewType.SINGLE));
        mek.setOwner(defenderOwner);
        mek.setWeight(50.0);
        game.addEntity(mek);
        mek.setPosition(position);
        mek.setBoardId(0);
        mek.setDeployed(true);
        return mek;
    }

    private static UnitLocation at(Entity entity) {
        return new UnitLocation(entity.getId(), entity.getPosition(), entity.getFacing(), entity.getElevation(),
              entity.getBoardId());
    }

    @Test
    void theFighterIsOverAGroundMap() {
        assertTrue(fighter.isAirborneAeroOnGroundMap(), "test setup: an airborne fighter on a ground board");
    }

    /** The fighter crossed 0406 from 0306: it fires from 0306, heading east, not from its end hex 0706. */
    @Test
    void airToGroundFiresFromTheApproachHex() {
        BipedMek target = mek(new Coords(3, 5));

        UnitLocation firing = AirAttackPoints.firingLocation(fighter, target, ResolvedAttack.Kind.SHOT, at(fighter));

        assertEquals(new Coords(2, 5), firing.coords(), "the hex before the target on the flight path");
        assertEquals(FACING_EAST, firing.facing(), "the facing it entered the target hex with");
        assertEquals(fighter.getElevation(), firing.elevation(), "the fighter keeps its own elevation");
    }

    /** A target it did not fly over is engaged from the flight path hex closest to it. */
    @Test
    void airToGroundOffThePathFiresFromTheClosestFlightPathHex() {
        BipedMek target = mek(new Coords(4, 8));

        UnitLocation firing = AirAttackPoints.firingLocation(fighter, target, ResolvedAttack.Kind.SHOT, at(fighter));

        assertEquals(Compute.getClosestFlightPath(Entity.NONE, target.getPosition(), fighter), firing.coords());
        assertNotEquals(fighter.getPosition(), firing.coords(), "not the end hex");
    }

    /** A Mek shooting at the fighter aims at the fighter's closest flight path hex, not at 0706. */
    @Test
    void groundToAirAimsAtTheClosestFlightPathHex() {
        BipedMek shooter = mek(new Coords(3, 8));

        UnitLocation targetAt = AirAttackPoints.targetLocation(shooter, fighter, ResolvedAttack.Kind.SHOT, at(fighter));

        assertEquals(Compute.getClosestFlightPath(shooter.getId(), shooter.getPosition(), fighter), targetAt.coords());
        assertNotEquals(fighter.getPosition(), targetAt.coords(), "not the end hex");
        assertEquals(fighter.getElevation(), targetAt.elevation(), "the fighter keeps its own elevation");
    }

    @Test
    void airToAirKeepsEndOfMoveLocations() {
        AeroSpaceFighter enemy = fighter(defenderOwner, "Interceptor");
        enemy.setPosition(new Coords(4, 5));
        UnitLocation fighterEnd = at(fighter);
        UnitLocation enemyEnd = at(enemy);

        assertEquals(fighterEnd, AirAttackPoints.firingLocation(fighter, enemy, ResolvedAttack.Kind.SHOT, fighterEnd));
        assertEquals(enemyEnd, AirAttackPoints.targetLocation(fighter, enemy, ResolvedAttack.Kind.SHOT, enemyEnd));
    }

    @Test
    void anEventThatIsNotAShotKeepsTheEndOfMoveLocation() {
        BipedMek target = mek(new Coords(3, 5));
        UnitLocation fighterEnd = at(fighter);

        assertEquals(fighterEnd, AirAttackPoints.firingLocation(fighter, target, ResolvedAttack.Kind.DEATH, fighterEnd));
    }

    @Test
    void aFighterWithNoFlightPathKeepsItsEndOfMoveLocation() {
        fighter.setPassedThrough(new Vector<>());
        fighter.setPassedThroughFacing(new ArrayList<>());
        BipedMek target = mek(new Coords(3, 5));
        UnitLocation fighterEnd = at(fighter);

        assertEquals(fighterEnd, AirAttackPoints.firingLocation(fighter, target, ResolvedAttack.Kind.SHOT, fighterEnd));
    }

    /** End to end: the event the server sends places the firing fighter on its approach hex. */
    @Test
    void theSentAttackEventCarriesTheApproachHex() {
        BipedMek target = mek(new Coords(3, 5));
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        Mockito.doNothing().when(gameManager).send(anyInt(), sent.capture());

        gameManager.sendAttackAnimation(fighter, target, ResolvedAttack.Kind.SHOT, -1, -1, true);

        ResolvedAttack event = null;
        for (Packet packet : sent.getAllValues()) {
            if (packet.command() == PacketCommand.ENTITY_ATTACK_RESOLVED) {
                event = (ResolvedAttack) packet.getObject(0);
            }
        }
        assertTrue(event != null, "an attack event was sent");
        assertEquals(new Coords(2, 5), event.attacker().coords(), "fired from the approach hex");
        assertEquals(target.getPosition(), event.target().coords(), "the ground target stays where it is");
    }
}
