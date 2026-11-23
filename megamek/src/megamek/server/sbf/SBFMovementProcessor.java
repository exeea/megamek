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

import java.util.List;
import java.util.Optional;

import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMoveStep;
import megamek.common.strategicBattleSystems.SurfaceSBFMoveStep;
import megamek.logging.MMLogger;

record SBFMovementProcessor(SBFGameManager gameManager) implements SBFGameManagerHelper {
    private static final MMLogger logger = MMLogger.create(SBFMovementProcessor.class);

    /**
     * Executes a submitted move. The path is rebuilt on the server from the formation's own position, so only the
     * route's hexes and the JUMP points used are taken from the Client.
     *
     * @return true when the move was legal and executed, false when it was refused (nothing changed)
     */
    boolean processMovement(SBFMovePath submittedPath, SBFFormation formation) {
        if (!validatePermitted(formation)) {
            return false;
        }

        Optional<SBFMovePath> rebuiltPath = rebuildPath(submittedPath, formation);
        if (rebuiltPath.isEmpty() || rebuiltPath.get().isIllegal()) {
            logger.error("Illegal move path!");
            return false;
        }

        return executeMovement(rebuiltPath.get(), formation, false);
    }

    /** Only the persisted server-rebuilt route can resume. An earlier immediate critical may have reduced Move. */
    void resumeMovement(SBFMovePath route, SBFFormation formation) {
        route.restore(game());
        executeMovement(route, formation, true);
    }

    private boolean executeMovement(SBFMovePath movePath, SBFFormation formation, boolean resuming) {
        var transport = SBFTransport.plan(game(), formation, movePath, resuming);
        if (transport.isEmpty() || (!resuming && !SBFWithdrawal.permitsMove(game(), formation, movePath))
              || (game().engagementOrders().pendingMove() != null)) {
            return false;
        }
        movePath = new SBFEngagementControl(gameManager).resolveMovement(formation, movePath);
        if (movePath == null) {
            return true; // The authoritative move resumes when both owners supply their orders.
        }
        game().engagementOrders().finishMovement();
        if (game().getFormation(formation.getId()).isEmpty()) {
            gameManager.endCurrentTurn(formation);
            return true;
        }
        int transportCost = SBFTransport.apply(gameManager, formation, transport.get(), movePath.getSteps().size());
        if (!movePath.getSteps().isEmpty()) {
            game().scenarioProgress().interruptOccupation(formation);
        }
        SBFWithdrawal.recordHostileEntries(game(), formation, movePath);
        formation.setPosition(movePath.getLastPosition());
        formation.setJumpUsedThisTurn(movePath.getJumpUsed());
        formation.setMpSpentThisTurn(movePath.getMpUsed() + transportCost);
        formation.setSprintedThisTurn(movePath.getMpUsed() + transportCost > SBFMovePath.normalMovementPoints(formation, game()));
        formation.setDone(true);
        if (!SBFWithdrawal.withdrawIfAtHomeEdge(gameManager, formation)) {
            gameManager.sendUnitUpdate(formation);
        }
        if (transportCost > 0) {
            SBFTransport.restoreDismountTurns(gameManager, formation.getOwnerId());
        }
        gameManager.endCurrentTurn(formation);
        return true;
    }

    private boolean validatePermitted(SBFFormation formation) {
        if (!game().getPhase().isMovement()) {
            logger.error("Server got movement packet in wrong phase!");
            return false;
        } else if (formation.isDone() || formation.isEmbarked()) {
            logger.error("Formation already done!");
            return false;
        }
        return true;
    }

    /**
     * Rebuilds the submitted route from the formation's position as a chain of adjacent ground steps on its board.
     * Surface steps are the only SBF move steps today; another step type needs its own rebuilding here.
     */
    private Optional<SBFMovePath> rebuildPath(SBFMovePath submittedPath, SBFFormation formation) {
        try {
            BoardLocation currentLocation = formation.getPosition();
            int jumpUsed = submittedPath.getJumpUsed();
            if (!game().hasBoardLocation(currentLocation) || (jumpUsed < 0) || (jumpUsed > formation.getJumpMove())) {
                return Optional.empty();
            }

            List<SBFMoveStep> submittedSteps = submittedPath.getSteps();
            // One additional wholly paved hex is possible. Rebuilt terrain costs verify actual eligibility.
            if (submittedSteps.size() > (long) SBFMovePath.maximumMovementPoints(formation, game()) + 1) {
                return Optional.empty();
            }

            SBFMovePath rebuiltPath = new SBFMovePath(formation.getId(), currentLocation, game());
            for (SBFMoveStep submittedStep : submittedSteps) {
                BoardLocation destination = submittedStep.getDestination();
                if (!game().hasBoardLocation(destination)
                      || (destination.boardId() != currentLocation.boardId())
                      || (currentLocation.coords().distance(destination.coords()) != 1)) {
                    return Optional.empty();
                }
                rebuiltPath.addStep(SurfaceSBFMoveStep.createSurfaceMoveStep(game(), formation.getId(),
                      currentLocation, destination));
                currentLocation = destination;
            }
            if (submittedPath.getTransportOrders().size() > SBFMovePath.maximumMovementPoints(formation, game())) {
                return Optional.empty();
            }
            submittedPath.getTransportOrders().forEach(rebuiltPath::addTransportOrder);
            rebuiltPath.setJumpUsed(jumpUsed);
            return Optional.of(rebuiltPath);
        } catch (RuntimeException exception) {
            logger.warn("Rejected malformed SBF movement path", exception);
            return Optional.empty();
        }
    }
}
