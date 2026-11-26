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

import java.awt.Component;
import java.awt.GridLayout;
import java.util.Objects;
import java.util.Optional;
import javax.swing.JPanel;

import megamek.common.board.Board;
import megamek.common.game.IGame;

/**
 * Keeps which of a client's boards is selected ({@link #getCurrentBoardId()}) and lets only that board's state take
 * keys. Call {@link #updateMapTabs()} when the client's boards change.
 */
public class BoardViewsContainer {

    /** The panel the Swing game frame lays out for the board; it holds no board view. */
    private final JPanel boardViewsContainer = new JPanel(new GridLayout(1, 1));

    private final AbstractClientGUI clientGUI;
    private Integer selectedBoardId;

    /**
     * Returns a new BoardViewsContainer, which keeps the selected board of the client's board states. Requires a
     * non-null AbstractClientGUI as parent.
     *
     * @param clientGUI The AbstractClientGUI parent
     */
    public BoardViewsContainer(AbstractClientGUI clientGUI) {
        this.clientGUI = Objects.requireNonNull(clientGUI);
    }

    /**
     * @return The panel the Swing game frame lays out for the board
     */
    public Component getPanel() {
        return boardViewsContainer;
    }

    /**
     * Follows the client's board states: keeps the selection on a board that exists and lets only its state take keys.
     */
    public void updateMapTabs() {
        updateSelection();
        updateBoardViewKeyStatus();
    }

    /**
     * A board's name and number and, for a board embedded in another, where it lies there: the 3D board's Maps menu
     * detail.
     */
    public static String boardDetail(IGame game, int boardId) {
        String detail = String.format("%s (Board #%d)", game.getBoard(boardId).getBoardName(), boardId);
        Optional<Board> enclosingBoard = game.getEnclosingBoard(boardId);
        if (enclosingBoard.isPresent()) {
            detail += "\nLocated at %s in %s".formatted(enclosingBoard.get()
                  .embeddedBoardPosition(boardId)
                  .getBoardNum(), enclosingBoard.get().getBoardName());
        }
        return detail;
    }

    /**
     * Returns the selected board independently of the visualization; the 3D board's startup and navigation do not
     * depend on a Swing component being constructed.
     *
     * @return The selected board's id, if any board exists
     */
    public Optional<Integer> getCurrentBoardId() {
        updateSelection();
        return Optional.ofNullable(selectedBoardId);
    }

    public void setName(String name) {
        boardViewsContainer.setName(name);
    }

    public void showBoardView(int boardId) {
        if (!clientGUI.boardStates.containsKey(boardId)) {
            return;
        }
        selectedBoardId = boardId;
        updateBoardViewKeyStatus();
    }

    private void updateSelection() {
        if (!clientGUI.boardStates.containsKey(selectedBoardId)) {
            selectedBoardId = clientGUI.boardStates.keySet().stream().min(Integer::compareTo).orElse(null);
        }
    }

    private void updateBoardViewKeyStatus() {
        clientGUI.boardStates.forEach((id, state) -> state.setShouldIgnoreKeys(!id.equals(selectedBoardId)));
    }
}
