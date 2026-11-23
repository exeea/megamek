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

package megamek.common.strategicBattleSystems;

import java.util.List;

import megamek.client.commands.ClientCommand;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.BoardLocation;

/**
 * This is an SBF move step that happens on the surface of a hex, i.e. on the ground or, if the destination has water,
 * on the water surface. This means that the elevation at start and end is considered to be 0.
 */
public class SurfaceSBFMoveStep extends SBFMoveStep {

    private boolean roadBonusEligible;

    boolean earnsRoadBonus() {
        return roadBonusEligible;
    }

    protected SurfaceSBFMoveStep(int formationId) {
        super(formationId);
    }

    public static SBFMoveStep createSurfaceMoveStep(SBFGame game, int formationId,
          BoardLocation startingPoint, BoardLocation destination) {
        SBFMoveStep step = new SurfaceSBFMoveStep(formationId);
        step.startingPoint = startingPoint;
        step.destination = destination;
        step.compile(game);
        return step;
    }

    @Deprecated(since = "0.51.0", forRemoval = true)
    public static SBFMoveStep createSurfaceMoveStep(SBFGame game, int formationId,
          BoardLocation startingPoint, int direction) {
        return createSurfaceMoveStep(game, formationId, startingPoint, startingPoint.translated(direction));
    }

    @Override
    protected void compile(SBFGame game) {
        super.compile(game);
        if (isIllegal) {
            // A step that is already illegal from basic tests does not need any further treatment
            return;
        }

        SBFFormation formation = game.getFormation(formationId).orElseThrow();
        Hex from = game.getBoard(startingPoint.boardId()).getHex(startingPoint.coords());
        Hex to = game.getBoard(destination.boardId()).getHex(destination.coords());
        List<SBFTerrainMovement> movers = SBFTerrainMovement.of(formation).toList();
        roadBonusEligible = SBFTerrainMovement.isPaved(from) && SBFTerrainMovement.isPaved(to);
        int direction = startingPoint.coords().direction(destination.coords());
        for (SBFTerrainMovement mover : movers) {
            int cost = mover.cost(from, to, direction, game.usesExpandedWoods());
            if (cost == SBFTerrainMovement.PROHIBITED) {
                isIllegal = true;
                return;
            }
            mpUsed = Math.max(mpUsed, cost);
            roadBonusEligible &= mover.earnsRoadBonus();
        }
        isIllegal |= movers.isEmpty();
        final Player owner = game.getPlayer(formation.getOwnerId());
        if (game.getActiveFormationsAt(startingPoint).stream().anyMatch(f -> game.areHostile(f, owner))) {
            mpUsed++;
        }
    }

    @Override
    public SBFMoveStep copy() {
        SurfaceSBFMoveStep step = new SurfaceSBFMoveStep(formationId);
        step.roadBonusEligible = roadBonusEligible;
        step.startingPoint = startingPoint;
        step.destination = destination;
        step.mpUsed = mpUsed;
        step.isIllegal = isIllegal;
        return step;
    }

    @Override
    public String toString() {
        return ClientCommand.getDirection(startingPoint.coords().direction(destination.coords()));
    }

    @Override
    public int getMovementDirection() {
        return startingPoint.coords().direction(destination.coords());
    }
}
