/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.stream.Stream;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.units.AeroSpaceFighter;
import megamek.common.units.BipedMek;
import megamek.common.units.Crew;
import megamek.common.units.CrewType;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An aircraft over a ground map has its move held back from the Movement phase until the firing results begin, then
 * plays it ahead of the attacks; anything else plays its move at once. A Shilone-like fighter flies east along row 5.
 */
class HeldFlightsTest {

    private static final int ROUND = 3;

    private Game game;
    private HeldFlights heldFlights;
    private AeroSpaceFighter fighter;

    @BeforeAll
    static void beforeAll() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void beforeEach() {
        game = new Game();
        int width = 16;
        int height = 17;
        game.setBoard(0, new Board(width, height,
              Stream.generate(Hex::new).limit((long) width * height).toArray(Hex[]::new)));
        Player owner = new Player(0, "Flyer");
        game.addPlayer(0, owner);
        game.setRoundCount(ROUND);
        game.setPhase(GamePhase.MOVEMENT);
        heldFlights = new HeldFlights();

        fighter = new AeroSpaceFighter();
        fighter.setGame(game);
        fighter.setId(game.getNextEntityId());
        fighter.setChassis("Test Fighter");
        fighter.setModel("Striker");
        fighter.setCrew(new Crew(CrewType.SINGLE));
        fighter.setOwner(owner);
        fighter.setWeight(50);
        game.addEntity(fighter);
        fighter.setBoardId(0);
        fighter.setPosition(new Coords(6, 5));
        fighter.setAltitude(3);
        fighter.setDeployed(true);
    }

    private static HeldFlight flight(Entity entity, int round) {
        List<BoardScene.Waypoint> path = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            path.add(new BoardScene.Waypoint(new Coords(x, 5), 3, 2));
        }
        return new HeldFlight(entity.getId(), round, path, true, EntityMovementType.MOVE_FLYING, 0, 6);
    }

    private boolean holdNow() {
        return heldFlights.hold(fighter, game.getPhase(), flight(fighter, ROUND), "test");
    }

    @Test
    void anAircraftOverAGroundMapIsHeldInTheMovementPhase() {
        assertTrue(holdNow(), "held");
        assertTrue(heldFlights.snapshot().isHeld(fighter.getId()), "the board draws it held");
        assertEquals(new Coords(1, 5), heldFlights.snapshot().flight(fighter.getId()).start().coords(),
              "its model waits at the start of its move");
    }

    @Test
    void aGroundUnitPlaysItsMoveAtOnce() {
        BipedMek mek = new BipedMek();
        mek.setGame(game);
        mek.setId(game.getNextEntityId());
        mek.setOwner(game.getPlayer(0));
        game.addEntity(mek);
        mek.setPosition(new Coords(3, 3));

        assertFalse(heldFlights.hold(mek, GamePhase.MOVEMENT, flight(mek, ROUND), "test"), "plays now");
        assertTrue(heldFlights.snapshot().flights().isEmpty());
    }

    @Test
    void aMoveOutsideTheWaitingPhasesPlaysAtOnce() {
        assertFalse(heldFlights.hold(fighter, GamePhase.PHYSICAL, flight(fighter, ROUND), "test"));
    }

    @Test
    void theFiringResultsReleaseEveryHeldMoveOnce() {
        holdNow();

        List<HeldFlight> released = heldFlights.releaseForFiringResults();

        assertEquals(1, released.size(), "its move plays now");
        assertTrue(heldFlights.snapshot().flights().isEmpty(), "nothing held after the release");
        assertTrue(heldFlights.releaseForFiringResults().isEmpty(), "a later result releases nothing more");
        assertFalse(holdNow(), "the same round never holds it again");
    }

    @Test
    void theOffBoardPhaseKeepsTheHold() {
        holdNow();

        assertTrue(heldFlights.releaseFinished(GamePhase.OFFBOARD, ROUND).isEmpty());
        assertTrue(heldFlights.snapshot().isHeld(fighter.getId()), "still waiting for the firing results");
    }

    @Test
    void aPhasePastFiringWithNoResultsPlaysTheHeldMove() {
        holdNow();

        assertEquals(1, heldFlights.releaseFinished(GamePhase.PHYSICAL, ROUND).size(), "it still flies");
        assertTrue(heldFlights.snapshot().flights().isEmpty());
    }

    @Test
    void aNewRoundDropsTheHeldMoveUnplayed() {
        holdNow();

        assertTrue(heldFlights.releaseFinished(GamePhase.MOVEMENT, ROUND + 1).isEmpty(), "too late to play it");
        assertTrue(heldFlights.snapshot().flights().isEmpty());
    }

    @Test
    void aRemovedUnitIsDropped() {
        holdNow();
        game.removeEntity(fighter.getId(), 0);

        List<HeldFlight> toPlay = heldFlights.update(game, 0, entity -> true, entity -> flight(entity, ROUND));

        assertTrue(toPlay.isEmpty(), "nothing to play");
        assertTrue(heldFlights.snapshot().flights().isEmpty(), "dropped");
    }

    @Test
    void aHiddenUnitIsDropped() {
        holdNow();

        heldFlights.update(game, 0, entity -> false, entity -> flight(entity, ROUND));

        assertTrue(heldFlights.snapshot().flights().isEmpty(), "dropped");
    }

    /** A game loaded after the fighter moved: its flight path holds it again. */
    @Test
    void aMoveSeenOnlyAsAFlightPathIsHeldAgain() {
        Vector<Coords> path = new Vector<>();
        List<Integer> facings = new ArrayList<>();
        for (int x = 1; x <= 6; x++) {
            path.add(new Coords(x, 5));
            facings.add(2);
        }
        fighter.setPassedThrough(path);
        fighter.setPassedThroughFacing(facings);
        fighter.setPassedThroughBoardId(0);
        fighter.moved = EntityMovementType.MOVE_FLYING;

        heldFlights.update(game, 0, entity -> true, entity -> flight(entity, ROUND));

        assertTrue(heldFlights.snapshot().isHeld(fighter.getId()), "held again from its flight path");
    }

    @Test
    void anAircraftThatHasNotMovedIsNotRebuilt() {
        heldFlights.update(game, 0, entity -> true, entity -> flight(entity, ROUND));

        assertTrue(heldFlights.snapshot().flights().isEmpty());
    }
}
