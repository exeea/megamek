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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMovePathFinder;
import megamek.common.strategicBattleSystems.SBFMovementMode;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests validation and authoritative mutation of SBF movement submissions. */
class SBFMovementProcessorTest {

    private final BoardLocation start = BoardLocation.of(new Coords(1, 1), 0);
    private SBFGame game;
    private SBFGameManager gameManager;
    private SBFMovementProcessor processor;
    private SBFFormation formation;

    @BeforeEach
    void setUp() {
        game = new SBFGame();
        game.setPhase(GamePhase.MOVEMENT);
        gameManager = mock(SBFGameManager.class);
        when(gameManager.getGame()).thenReturn(game);
        processor = new SBFMovementProcessor(gameManager);
        Board board = new Board(10, 10);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(x, y, new Hex());
            }
        }
        game.setBoard(0, board);
        game.addPlayer(1, new Player(1, "Owner"));
        formation = new SBFFormation();
        formation.setId(17);
        formation.setOwnerId(1);
        formation.setPosition(start);
        formation.setMovement(3);
        formation.setJumpMove(2);
        formation.setType(SBFElementType.BM);
        formation.setMovementMode(SBFMovementMode.MEK_WALK);
        formation.setDeployed(true);
        game.addUnit(formation);
    }

    @Test
    void validMovementUpdatesFormationAndEndsTurn() {
        BoardLocation destination = BoardLocation.of(new Coords(2, 1), 0);
        SBFMovePath movePath = new SBFMovePath(formation.getId(), start, game);
        movePath.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), start, destination));
        movePath.setJumpUsed(2);

        assertTrue(processor.processMovement(movePath, formation));
        assertEquals(destination, formation.getPosition());
        assertEquals(2, formation.getJumpUsedThisTurn());
        assertTrue(formation.isDone());
        verify(gameManager).sendUnitUpdate(formation);
        verify(gameManager).endCurrentTurn(formation);
    }

    @Test
    void wrongPhaseRejectsWithoutMutation() {
        game.setPhase(GamePhase.FIRING);

        assertRejected(new SBFMovePath(formation.getId(), start, game));
    }

    @Test
    void forgedStartCannotTeleportFormation() {
        BoardLocation forgedStart = BoardLocation.of(new Coords(7, 7), 0);
        BoardLocation destination = BoardLocation.of(new Coords(8, 7), 0);
        SBFMovePath movePath = new SBFMovePath(formation.getId(), forgedStart, game);
        movePath.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), forgedStart, destination));

        assertRejected(movePath);
    }

    @Test
    void discontinuousRouteRejectsWithoutMutation() {
        BoardLocation first = BoardLocation.of(new Coords(2, 1), 0);
        BoardLocation forgedSecondStart = BoardLocation.of(new Coords(7, 7), 0);
        BoardLocation second = BoardLocation.of(new Coords(8, 7), 0);
        SBFMovePath movePath = new SBFMovePath(formation.getId(), start, game);
        movePath.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), start, first));
        movePath.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), forgedSecondStart, second));

        assertRejected(movePath);
    }

    @Test
    void excessiveJumpUseRejectsWithoutMutation() {
        SBFMovePath movePath = new SBFMovePath(formation.getId(), start, game);
        movePath.setJumpUsed(formation.getJumpMove() + 1);

        assertRejected(movePath);
    }

    @Test
    void negativeJumpUseRejectsWithoutMutation() {
        SBFMovePath movePath = new SBFMovePath(formation.getId(), start, game);
        movePath.setJumpUsed(-1);

        assertRejected(movePath);
    }

    @Test
    void completedFormationRejectsWithoutMutation() {
        formation.setDone(true);

        assertFalse(processor.processMovement(new SBFMovePath(formation.getId(), start, game), formation));
        assertTrue(formation.isDone());
        assertEquals(start, formation.getPosition());
        verify(gameManager, never()).sendUnitUpdate(any());
        verify(gameManager, never()).endCurrentTurn(any());
    }

    @Test
    void movementBeyondNormalLimitRejects() {
        assertRejected(pathWithSteps(formation.getMovement() + 1));
    }

    @Test
    void movementAtNormalLimitDoesNotCountAsSprinting() {
        assertTrue(processor.processMovement(pathWithSteps(formation.getMovement()), formation));
        assertFalse(formation.hasSprintedThisTurn());
    }

    @Test
    void sprintingRoundsOneAndAHalfMovementUp() {
        game.getOptions().getOption(SBFRuleOptions.MOVE_SPRINT).setValue(true);

        assertTrue(processor.processMovement(pathWithSteps(5), formation));
        assertTrue(formation.isDone());
        assertTrue(formation.hasSprintedThisTurn());
        formation.setDone(false);
        assertFalse(formation.isEligibleForPhase(GamePhase.FIRING));
    }

    @Test
    void movementBeyondSprintingLimitRejects() {
        game.getOptions().getOption(SBFRuleOptions.MOVE_SPRINT).setValue(true);

        assertRejected(pathWithSteps(6));
    }

    @Test
    void minimumMovementUsesNormalMoveEvenWhenSprintingIsEnabled() {
        formation.setMovement(1);
        game.getOptions().getOption(SBFRuleOptions.MOVE_SPRINT).setValue(true);
        BoardLocation adjacent = BoardLocation.of(new Coords(2, 1), 0);
        game.getBoard(0).getHex(adjacent.coords()).addTerrain(new Terrain(Terrains.ROUGH, 1));

        SBFMovePathFinder finder = SBFMovePathFinder.moveEnvelopeFinder(1, game);
        finder.run(new SBFMovePath(formation.getId(), start, game));
        SBFMovePath path = finder.getAllComputedPaths().get(adjacent);
        assertEquals(1, path.getMpUsed());
        assertTrue(processor.processMovement(path, formation));
        assertFalse(formation.hasSprintedThisTurn());
    }

    @Test
    void minimumMovementCannotBeUsedOnALongerRouteOrByAnImmobileFormation() {
        formation.setMovement(1);
        game.getBoard(0).getHex(new Coords(2, 1)).addTerrain(new Terrain(Terrains.ROUGH, 1));
        assertRejected(pathWithSteps(2));
        formation.setMovement(0);
        assertRejected(pathWithSteps(1));
    }

    @Test
    void minimumMovementCannotEnterProhibitedTerrain() {
        formation.setMovement(1);
        game.getBoard(0).getHex(new Coords(2, 1)).setLevel(4);
        assertRejected(pathWithSteps(1));
    }

    @Test
    void infantryCanJoinAndHoldWithAnotherFormation() {
        BoardLocation adjacent = BoardLocation.of(new Coords(2, 1), 0);
        addFriendly(18, adjacent, SBFElementType.BM);
        assertRejected(pathWithSteps(1));
        formation.setType(SBFElementType.BA);
        assertTrue(processor.processMovement(pathWithSteps(1), formation));
        formation.setDone(false);
        assertTrue(processor.processMovement(new SBFMovePath(formation.getId(), adjacent, game), formation));
    }

    @Test
    void infantryCannotJoinAFullFriendlyStack() {
        BoardLocation adjacent = BoardLocation.of(new Coords(2, 1), 0);
        formation.setType(SBFElementType.BA);
        addFriendly(18, adjacent, SBFElementType.BM);
        addFriendly(19, adjacent, SBFElementType.CI);
        assertRejected(pathWithSteps(1));
    }

    @Test
    void pathfindersAndServerAllowTransitThroughAFullFriendlyStack() {
        Board corridor = new Board(1, 3);
        for (int y = 0; y < 3; y++) {
            corridor.setHex(0, y, new Hex());
        }
        game.setBoard(0, corridor);
        BoardLocation entrance = BoardLocation.of(new Coords(0, 0), 0);
        BoardLocation middle = BoardLocation.of(new Coords(0, 1), 0);
        BoardLocation exit = BoardLocation.of(new Coords(0, 2), 0);
        formation.setPosition(entrance);
        addFriendly(18, middle, SBFElementType.CI);
        addFriendly(19, middle, SBFElementType.BM);
        SBFMovePath startPath = new SBFMovePath(formation.getId(), entrance, game);

        SBFMovePathFinder envelope = SBFMovePathFinder.moveEnvelopeFinder(2, game);
        envelope.run(startPath);
        assertFalse(envelope.getAllComputedPaths().containsKey(middle));
        assertTrue(envelope.getAllComputedPaths().containsKey(exit));
        SBFMovePathFinder finder = SBFMovePathFinder.aStarFinder(exit, game);
        finder.run(startPath);
        assertTrue(processor.processMovement(finder.getComputedPath(exit), formation));
        assertEquals(exit, formation.getPosition());
    }

    @Test
    void extraRoadHexRequiresTheEntireRouteToQualify() {
        formation.setMovementMode(SBFMovementMode.TRACKED);
        formation.setType(SBFElementType.V);
        for (int x = 1; x <= 5; x++) {
            game.getBoard(0).getHex(new Coords(x, 1)).addTerrain(new Terrain(Terrains.ROAD, 1));
        }
        Hex middle = game.getBoard(0).getHex(new Coords(3, 1));
        middle.removeTerrain(Terrains.ROAD);
        assertRejected(pathWithSteps(4));
        middle.addTerrain(new Terrain(Terrains.ROAD, 1));

        SBFMovePathFinder envelope = SBFMovePathFinder.moveEnvelopeFinder(formation.getMovement(), game);
        envelope.run(new SBFMovePath(formation.getId(), start, game));
        BoardLocation end = BoardLocation.of(new Coords(5, 1), 0);
        assertEquals(3, envelope.getComputedPath(end).getMpUsed());
        assertTrue(processor.processMovement(pathWithSteps(4), formation));
        assertFalse(formation.hasSprintedThisTurn());
    }

    private void addFriendly(int id, BoardLocation location, SBFElementType type) {
        SBFFormation friendly = new SBFFormation();
        friendly.setId(id);
        friendly.setOwnerId(formation.getOwnerId());
        friendly.setPosition(location);
        friendly.setType(type);
        friendly.setDeployed(true);
        game.addUnit(friendly);
    }

    private SBFMovePath pathWithSteps(int count) {
        SBFMovePath path = new SBFMovePath(formation.getId(), start, game);
        BoardLocation current = start;
        for (int i = 0; i < count; i++) {
            BoardLocation destination = BoardLocation.of(new Coords(current.coords().getX() + 1,
                  current.coords().getY()), 0);
            path.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game, formation.getId(), current, destination));
            current = destination;
        }
        return path;
    }

    private void assertRejected(SBFMovePath movePath) {
        assertFalse(processor.processMovement(movePath, formation));
        assertFalse(formation.isDone());
        assertEquals(start, formation.getPosition());
        verify(gameManager, never()).sendUnitUpdate(any());
        verify(gameManager, never()).endCurrentTurn(any());
    }
}
