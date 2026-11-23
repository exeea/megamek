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

package megamek.client.ui.clientGUI;

import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.board.GameBoardNewEvent;

/**
 * The SBF client GUI's listener on its {@code SBFGame}, which is the source of truth. It is the one writer of the
 * board's game ({@code SBFClientGUI.bvGame}): a snapshot of the board, the phase and the round that the board's state
 * and the 3D board read, never read for rules.
 */
public class SBFClientGUIGameListener extends GameListenerAdapter {

    private final SBFClientGUI clientGUI;

    public SBFClientGUIGameListener(SBFClientGUI clientGUI) {
        this.clientGUI = clientGUI;
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent e) {
        // The board's snapshot first (never the lounge, whose Game.setPhase resets that game), then the panel
        if (!e.getNewPhase().isLounge()) {
            clientGUI.bvGame.setCurrentRound(clientGUI.getClient().getGame().getCurrentRound());
            clientGUI.bvGame.setPhase(e.getNewPhase());
        }
        // Swap to this phase's panel.
        clientGUI.switchPanel(e.getNewPhase());
        clientGUI.menuBar.setPhase(e.getNewPhase());
    }

    @Override
    public void gameSettingsChange(GameSettingsChangeEvent event) {
        clientGUI.bvGame.setPlanetaryConditions(clientGUI.getClient().getGame().getPlanetaryConditions());
    }

    @Override
    public void gameBoardNew(GameBoardNewEvent e) {
        clientGUI.bvGame.setBoard(e.getNewBoard());
        clientGUI.openBoardWindow();
    }

}
