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
package megamek.client.ui.dialogs.BotCommands;

import java.awt.Color;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardHexPick;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.clientGUI.boardview.sprite.TextMarkerSprite;
import megamek.common.RangeType;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.util.Distractable;
import megamek.logging.MMLogger;

/**
 * The board side of the Move Order editor: takes the hexes the player clicks while picking, lets a waypoint be dragged
 * to another hex while the editor is open, holds the display under the board still meanwhile so clicks select and move
 * nothing there, and draws the draft route. Split out of {@link BotMoveOrderDialog}, which decides what a picked hex
 * does. While it picks it is the board's hex pick, which the GPU battle window's HUD shows and ends; that window takes
 * no waypoint drags (see {@link #dragWaypoint}).
 */
final class BoardRouteEditor implements BoardHexPick {

    private static final MMLogger LOGGER = MMLogger.create(BoardRouteEditor.class);
    private static final int ALL_HEX_BORDERS = 63;

    private final ClientGUI clientGUI;
    private final BoardClientState boardView;
    private final WaypointTableModel waypoints;
    private final Consumer<Coords> onHexPicked;
    private final IntConsumer onRowChanged;
    private final Runnable onPickEnded;
    private String instructions = "";
    private final List<Sprite> routeSprites = new ArrayList<>();
    // dragging a waypoint on the board: its row, or -1; whether it has moved; the display held still while it drags,
    // when picking was not already holding it; and the release that ended the last drag, which is no click
    private final BoardViewListenerAdapter dragListener = new BoardViewListenerAdapter() {
        @Override
        public void hexMoused(BoardViewEvent event) {
            dragWaypoint(event);
        }
    };
    private int draggedRow = -1;
    private boolean hasDragMoved;
    private Distractable dragHeldDisplay;
    private BoardViewEvent dragEndingClick;
    private BoardViewListenerAdapter hexClickListener;
    // a new phase has a new display under the board, which picking would not hold still: picking ends, as a bot
    // order's pick does
    private final GameListenerAdapter phaseChangeListener = new GameListenerAdapter() {
        @Override
        public void gamePhaseChange(GamePhaseChangeEvent event) {
            if (event.getNewPhase() != GamePhase.UNKNOWN) {
                onPickEnded.run();
            }
        }
    };
    private Distractable suppressedDisplay;

    /**
     * Starts listening for waypoints dragged on the board.
     *
     * @param clientGUI    the client GUI, whose display is held still while hexes are picked or dragged
     * @param boardView    the board
     * @param waypoints    the draft route
     * @param onHexPicked  what a hex clicked while picking does
     * @param onRowChanged told the row of a waypoint dragged to another hex
     * @param onPickEnded  told that the HUD's Done or Cancel, or another pick, ended the picking
     */
    BoardRouteEditor(ClientGUI clientGUI, BoardClientState boardView, WaypointTableModel waypoints,
          Consumer<Coords> onHexPicked, IntConsumer onRowChanged, Runnable onPickEnded) {
        this.clientGUI = clientGUI;
        this.boardView = boardView;
        this.waypoints = waypoints;
        this.onHexPicked = onHexPicked;
        this.onRowChanged = onRowChanged;
        this.onPickEnded = onPickEnded;
        boardView.addBoardViewListener(dragListener);
    }

    /**
     * Starts taking each hex the player clicks on the board, holding the display under the board still.
     *
     * @param instructions what the clicked hexes do, which the GPU battle window's HUD shows
     *
     * @return {@code true} if picking started; {@code false} if it was already on
     */
    boolean startPicking(String instructions) {
        this.instructions = instructions;
        if (hexClickListener != null) {
            return false;
        }
        // first, as it ends a running pick, which lets the display under the board go
        boardView.startHexPick(this);
        if (clientGUI.getCurrentPanel() instanceof Distractable distractable) {
            suppressedDisplay = distractable;
            suppressedDisplay.setIgnoringEvents(true);
        }
        hexClickListener = new BoardViewListenerAdapter() {
            @Override
            public void hexMoused(BoardViewEvent event) {
                if ((event.getType() != BoardViewEvent.BOARD_HEX_CLICKED) || (event.getButton() != MouseEvent.BUTTON1)
                      || (event.getCoords() == null)) {
                    return;
                }
                if ((event == dragEndingClick) || ((draggedRow >= 0) && hasDragMoved)) {
                    // the release at the end of a drag moved a waypoint: it adds none
                    return;
                }
                onHexPicked.accept(event.getCoords());
            }
        };
        boardView.addBoardViewListener(hexClickListener);
        clientGUI.getClient().getGame().addGameListener(phaseChangeListener);
        return true;
    }

    /** Stops taking clicked hexes and lets the display under the board go. */
    void stopPicking() {
        boardView.endHexPick(this);
        if (hexClickListener != null) {
            boardView.removeBoardViewListener(hexClickListener);
            hexClickListener = null;
            clientGUI.getClient().getGame().removeGameListener(phaseChangeListener);
        }
        if (suppressedDisplay != null) {
            suppressedDisplay.setIgnoringEvents(false);
            suppressedDisplay = null;
        }
    }

    /** Draws the draft route on the board: each waypoint outlined and numbered in route order. */
    void refreshSprites() {
        boardView.removeSprites(routeSprites);
        routeSprites.clear();
        for (int row = 0; row < waypoints.getRowCount(); row++) {
            Coords hex = waypoints.getHex(row);
            routeSprites.add(new FieldOfFireSprite(boardView, RangeType.RANGE_SHORT, hex, ALL_HEX_BORDERS));
            routeSprites.add(new TextMarkerSprite(boardView, hex, String.valueOf(row + 1), Color.WHITE));
        }
        boardView.addSprites(routeSprites);
    }

    /**
     * Drags a waypoint: pressed on one of the route's hexes, it follows the mouse hex by hex, and the release leaves
     * it there. The display under the board is held still while it drags, so the drag selects and moves nothing. The
     * GPU battle window reports its hover as a drag (GpuBoardSource#hover) and no drags of its own, so there a waypoint
     * is moved by removing it and picking its new hex.
     */
    private void dragWaypoint(BoardViewEvent event) {
        Coords hex = event.getCoords();
        if ((hex == null) || GpuBoardWindow.isActiveFor(clientGUI)) {
            return;
        }
        if (event.getType() == BoardViewEvent.BOARD_HEX_DRAGGED) {
            if (draggedRow < 0) {
                // the press: a drag starts only on one of the route's hexes, with the left button
                if (event.getButton() != MouseEvent.BUTTON1) {
                    // the right button pans the board; only the left drags a waypoint
                    return;
                }
                int row = waypoints.rowAt(hex);
                LOGGER.info("[BotOrders] Move Order editor: press at {} - {}", hex.getBoardNum(), (row >= 0)
                      ? "picked up waypoint " + (row + 1) + ", drag it and release"
                      : "not one of the route's " + waypoints.getRowCount() + " waypoint(s), nothing to drag");
                if (row >= 0) {
                    draggedRow = row;
                    hasDragMoved = false;
                    if ((suppressedDisplay == null) && (clientGUI.getCurrentPanel() instanceof Distractable display)) {
                        dragHeldDisplay = display;
                        dragHeldDisplay.setIgnoringEvents(true);
                    }
                }
                return;
            }
            if (!hex.equals(waypoints.getHex(draggedRow))) {
                waypoints.moveWaypoint(draggedRow, hex);
                hasDragMoved = true;
                onRowChanged.accept(draggedRow);
            }
        } else if ((event.getType() == BoardViewEvent.BOARD_HEX_CLICKED) && (draggedRow >= 0)) {
            if (hasDragMoved) {
                dragEndingClick = event;
                LOGGER.info("[BotOrders] Move Order editor: waypoint {} dragged to {}", draggedRow + 1,
                      hex.getBoardNum());
            }
            endDrag();
        }
    }

    private void endDrag() {
        draggedRow = -1;
        hasDragMoved = false;
        if (dragHeldDisplay != null) {
            dragHeldDisplay.setIgnoringEvents(false);
            dragHeldDisplay = null;
        }
    }

    @Override
    public String instructions() {
        return instructions;
    }

    /** @return the waypoints in route order, as the bot order's pick lists its hexes */
    @Override
    public String status() {
        return Messages.getString("BotCommandPanel.HexPicker.status", waypoints.getRowCount(),
              IntStream.range(0, waypoints.getRowCount()).mapToObj(row -> waypoints.getHex(row).getBoardNum())
                    .collect(Collectors.joining(", ")));
    }

    @Override
    public boolean hasHexes() {
        return waypoints.getRowCount() > 0;
    }

    /** The HUD's Done: picking stops; the route stays in the editor, whose Send sends it. */
    @Override
    public void done() {
        onPickEnded.run();
    }

    /** The HUD's Esc, or another pick: picking stops too, as the editor's pick button does; the waypoints stay. */
    @Override
    public void cancel() {
        onPickEnded.run();
    }

    /** Stops all board editing and takes the draft route off the board, as the editor closes. */
    void close() {
        boardView.removeBoardViewListener(dragListener);
        endDrag();
        stopPicking();
        boardView.removeSprites(routeSprites);
        routeSprites.clear();
    }
}
