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
package megamek.client.ui.clientGUI.boardview;

import java.util.Collection;
import java.util.Set;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListener;
import megamek.client.ui.IDisplayable;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.common.Player;

public abstract class AbstractBoardView implements IBoardView {

    protected final int boardId;
    public abstract BoardClientState getClientState();

    AbstractBoardView(int boardId) {
        this.boardId = boardId;
    }

    /**
     * Notifies attached BoardViewListeners of the event.
     *
     * @param event the board event.
     */
    public void processBoardViewEvent(BoardViewEvent event) {
        getClientState().processBoardViewEvent(event);
    }

    @Override
    public void addBoardViewListener(BoardViewListener listener) {
        getClientState().addBoardViewListener(listener);
    }

    @Override
    public void removeBoardViewListener(BoardViewListener listener) {
        getClientState().removeBoardViewListener(listener);
    }



    @Override
    public void addOverlay(IDisplayable overlay) {
        getClientState().addOverlay(overlay);
    }

    @Override
    public void removeOverlay(IDisplayable overlay) {
        getClientState().removeOverlay(overlay);
    }

    @Override
    public void addSprites(Collection<? extends Sprite> sprites) {
        getClientState().addSprites(sprites);
    }

    @Override
    public void removeSprites(Collection<? extends Sprite> sprites) {
        getClientState().removeSprites(sprites);
    }

    /**
     * Removes all sprites from this BoardView. This includes (possibly) sprites for units, attacks etc. Note that this
     * is not communicated to the SpriteHandlers.
     */
    public void clearSprites() {
        getClientState().clearSprites();
    }

    /**
     * Returns an unmodifiable view of this BoardView's sprites.
     */
    public Set<Sprite> getAllSprites() {
        return getClientState().getAllSprites();
    }

    @Override
    public Player getLocalPlayer() {
        return getClientState().getLocalPlayer();
    }

    public void setLocalPlayer(Player p) {
        getClientState().setLocalPlayer(p);
    }

    @Override
    public int getBoardId() {
        return boardId;
    }
}
