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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.Set;
import javax.swing.JPanel;
import javax.swing.Scrollable;

import megamek.client.event.BoardViewListener;
import megamek.client.ui.IDisplayable;
import megamek.common.Player;
import megamek.common.board.Coords;

public interface IBoardView {

    /**
     * This method should be overridden to do the actual drawing of the board image into the provided Graphics.
     *
     * @param graphics The Graphics object to draw the board onto
     */
    void draw(Graphics graphics);

    /**
     * Zooms out the board (shows more of it at smaller size), if the minimum zoom has not been reached already.
     */
    void zoomOut();

    /**
     * Zooms in the board (shows less of it at bigger size), if the maximum zoom has not been reached already.
     */
    void zoomIn();

    /**
     * Resets the zoom level of the board
     */
    void zoomReset();

    /**
     * Toggles between two Zoom levels.
     */
    void zoomOverviewToggle();

    /**
     * Returns the pixel size of the entire board if drawn at the current zoom level. This should not include any
     * padding, just the board itself.
     *
     * @return The pixel size of the entire board at the current zoom level
     */
    Dimension getBoardSize();

    /**
     * @return a JScrollPane containing the board's panel.
     */
    Component getComponent();

    /**
     * Sets this BoardView to show or hide a warning in fields (hexes) that contain invalid information such as terrains
     * that cannot be used together in a single hex. Usually this warning is shown in the board editor but not in a
     * game.
     *
     * @param displayInvalidFields True when the invalid marker should be shown
     */
    void setDisplayInvalidFields(boolean displayInvalidFields);

    default boolean displayInvalidFields() {
        return false;
    }

    /**
     * Sets the {@link IBoardView} to use the given player ID as the player in whose client this {@link IBoardView} is
     * shown. This may affect what is shown and what is hidden in this {@link IBoardView}; it will also affect what is
     * considered an enemy and ally.
     *
     * @param playerId The local player's ID as stored in the game.
     */
    void setLocalPlayer(int playerId);

    Player getLocalPlayer();

    /**
     * Frees the resources this {@link IBoardView} uses and removes listeners. Call when this {@link IBoardView} is not
     * used anymore. The {@link IBoardView} will no longer be functional after calling this method. When overriding this
     * method, include a call to super.dispose().
     */
    void dispose();

    void setUseLosTool(boolean useLosTool);

    /**
     * @return The JPanel that contains this BoardView.
     */
    JPanel getPanel();

    /**
     * Adds the specified board listener to receive board events from this board.
     *
     * @param listener the board listener.
     */
    void addBoardViewListener(BoardViewListener listener);

    /**
     * Removes the specified board listener.
     *
     * @param listener the board listener.
     */
    void removeBoardViewListener(BoardViewListener listener);

    /**
     * Adds the given overlay to this {@link IBoardView}. Overlays are displayed above the actual board and fixed with
     * respect to the screen, like the chatbox or unit overview.
     *
     * @param overlay The overlay to add
     */
    void addOverlay(IDisplayable overlay);

    /**
     * Removes the given overlay from this {@link IBoardView}.
     *
     * @param overlay The overlay to add
     *
     * @see #addOverlay(IDisplayable)
     */
    void removeOverlay(IDisplayable overlay);

    default void refreshDisplayables() {
        getPanel().repaint();
    }

    /**
     * Notifies this BoardView to center itself on the given Coords. Override this to be safe for null Coords.
     *
     * @param coords The coordinates to center on
     */
    void centerOnHex(Coords coords);

    /**
     * @return A set of hashCodes of those image that are animated (such as animated tileset images) and therefore
     *       should prevent the hex image from being cached.
     */
    Set<Integer> getAnimatedImages();

    /**
     * Override this to provide a return value exactly as the Scrollable interface method of the same name.
     *
     * @see Scrollable#getScrollableUnitIncrement(Rectangle, int, int)
     */
    int getScrollableUnitIncrement(Rectangle arg0, int arg1, int arg2);

    /**
     * Override this to provide a return value exactly as the Scrollable interface method of the same name.
     *
     * @see Scrollable#getScrollableBlockIncrement(Rectangle, int, int)
     */
    int getScrollableBlockIncrement(Rectangle arg0, int arg1, int arg2);

    /**
     * @return the coords at the specified point in the BoardView's image area. The point may be given e.g. as part of a
     *       MouseEvent.
     */
    Coords getCoordsAt(Point point);

    /**
     * Schedules a repaint of the BoardView.
     *
     * @see JPanel#repaint()
     */
    void repaint();

    /**
     * Highlights the given coords, if they are on the board. When coords is null, remove the highlight. Note that a
     * BoardView implementation may choose to do nothing.
     *
     * @param coords the Coords to highlight
     */
    void highlight(Coords coords);

    /**
     * Selects the given coords, if they are on the board. When coords is null, remove the selection. Note that a
     * BoardView implementation may choose to do nothing.
     *
     * @param coords the Coords to select
     */
    void select(Coords coords);

    /**
     * Places a cursor on the given coords, if they are on the board. When coords is null, remove the cursor. Note that
     * a BoardView implementation may choose to do nothing.
     *
     * @param coords the Coords to cursor
     */
    void cursor(Coords coords);

    /**
     * Removes the markers set by the select(), cursor() and highlight() methods.
     */
    default void clearMarkedHexes() {
        select(null);
        highlight(null);
        cursor(null);
    }

    /**
     * @return This {@link IBoardView}'s board ID. Defaults to 0 for all implementations that don't support multiple
     *       boards.
     */
    default int getBoardId() {
        return 0;
    }

    /**
     * Returns true when this {@link IBoardView} is showing some animation and should not be centered on another hex or
     * be hidden right now. An example is showing a unit's move animation.
     *
     * @return True when this {@link IBoardView} is in the process of showing some animation
     */
    default boolean isShowingAnimation() {
        return false;
    }
}
