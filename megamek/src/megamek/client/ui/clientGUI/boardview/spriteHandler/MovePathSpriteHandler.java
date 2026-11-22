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

package megamek.client.ui.clientGUI.boardview.spriteHandler;

import java.awt.Color;

import jakarta.annotation.Nullable;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.SBFClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.clientGUI.boardview.sprite.sbf.SBFStepSprite;
import megamek.common.board.Coords;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFMoveStep;

public class MovePathSpriteHandler extends BoardViewSpriteHandler {

    public MovePathSpriteHandler(AbstractClientGUI clientGUI) {
        super(clientGUI);
    }

    /**
     * Clears the current sprites and creates new sprites for all formations.
     */
    public void update(@Nullable SBFMovePath movePath) {
        clear();
        if (SBFClientGUI.NATIVE_3D) {
            marks(movePath);
            return;
        }
        if (clientGUI.boardViews().isEmpty()) {
            return;
        }
        if (movePath == null) {
            return;
        }

        for (SBFMoveStep step : movePath.getSteps()) {
            currentSprites.add(new SBFStepSprite((BoardView) clientGUI.boardViews().getFirst(), step, movePath));
        }

        clientGUI.boardViews().getFirst().addSprites(currentSprites);
    }

    /**
     * The path as board marks, which the 3D board draws: a dot on each step, the destination a flag with the MP the
     * path uses; in the illegal-move colour where the step or the path is illegal.
     */
    private void marks(@Nullable SBFMovePath movePath) {
        if (movePath == null) {
            return;
        }
        for (SBFMoveStep step : movePath.getSteps()) {
            BoardClientState state = clientGUI.getBoardState(step.getDestination().boardId());
            if (state == null) {
                continue;
            }
            Color color = (step.isIllegal() || movePath.isIllegal()) ? GUIP.getMoveIllegalColor()
                  : GUIP.getMoveDefaultColor();
            Coords coords = step.getDestination().coords();
            addMark(state, movePath.isEndStep(step)
                  ? new TacticalMark.Flag(coords, color, movePath.getMpUpTo(step) + " MP", null,
                  TacticalMark.Flag.NO_FACING)
                  : new TacticalMark.RouteDot(coords, color, TacticalStyle.ROUTE_TRAIL_DIAMETER));
        }
    }

    @Override
    public void initialize() {

    }

    @Override
    public void dispose() {

    }
}
