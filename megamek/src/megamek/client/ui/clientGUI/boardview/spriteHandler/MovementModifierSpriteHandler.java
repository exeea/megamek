/*
 * Copyright (C) 2002-2025 The MegaMek Team. All Rights Reserved.
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
import java.util.Collection;

import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.common.Facing;
import megamek.common.compute.DefensiveMovementModifier;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.Game;
import megamek.common.moves.MovePath;

public class MovementModifierSpriteHandler extends BoardViewSpriteHandler {

    private final Game game;

    public MovementModifierSpriteHandler(AbstractClientGUI clientGUI, Game game) {
        super(clientGUI);
        this.game = game;
    }

    @Override
    public void initialize() {
        game.addGameListener(this);
    }

    @Override
    public void dispose() {
        clear();
        game.removeGameListener(this);
    }

    public void renewSprites(Collection<MovePath> movePaths) {
        clear();
        if (clientGUI.boardStates().isEmpty()) {
            return;
        }
        BoardClientState boardView = clientGUI.boardStates().getFirst();
        addMarks(boardView, movePaths.stream().map(this::modifier).toList());
        addMarks(boardView, movePaths.stream().filter(MovePath::isMoveLegal).map(this::modifier).toList());
    }

    /**
     * The defensive modifier at the end of {@code movePath}, from one shared computation with the bot's position
     * evaluation, so the overlay and the bot can never value destinations differently.
     */
    private TacticalMark.FacingModifier modifier(MovePath movePath) {
        int movementModifier = DefensiveMovementModifier.forPath(movePath, game);
        float hue = 0.7f - 0.15f * movementModifier;
        return new TacticalMark.FacingModifier(movePath.getFinalCoords(), Facing.valueOfInt(movePath.getFinalFacing()),
              String.format("%+d", movementModifier), new Color(Color.HSBtoRGB(hue, 1, 1)));
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent e) {
        clear();
    }
}
