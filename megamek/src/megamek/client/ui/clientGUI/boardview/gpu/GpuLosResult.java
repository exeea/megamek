/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.InputEvent;

import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * EDT service for line of sight, which stays MegaMek's Swing ruler over the native window (the user's decision of
 * 2026-10-03): the measurement waiting for its second point, and the menus' line of sight from a unit.
 */
final class GpuLosResult {
    /**
     * The modifier of a measurement waiting for its second point: Ctrl for a line of sight, Alt for a distance, 0 for
     * none. A plain left click on the board ends that measurement, as on rimshaderv1's board.
     */
    record Snapshot(int pending) {
        static final Snapshot NONE = new Snapshot(0);
    }

    private final GpuBoardSource source;
    private Snapshot snapshot = Snapshot.NONE;

    GpuLosResult(GpuBoardSource source) {
        this.source = source;
    }

    /** EDT: the measurement waiting for its second point on the shown board. */
    Snapshot capture() {
        GpuBoardSource.requireSwingThread();
        int pending = pending(source.currentView());
        if (pending != snapshot.pending()) {
            snapshot = new Snapshot(pending);
        }
        return snapshot;
    }

    /**
     * The modifier of a measurement waiting for its second point (rimshaderv1's pendingMeasurementModifiers): Ctrl
     * while a line of sight has its first point, Alt while the ruler has its start alone, else 0.
     */
    private static int pending(BoardClientState view) {
        if (view.getFirstLOS() != null) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        return (view.getRulerStart() != null) != (view.getRulerEnd() != null) ? InputEvent.ALT_DOWN_MASK : 0;
    }

    /**
     * GL-safe: the menu's line of sight from an own unit to a hex, which MegaMek's ruler measures and shows; never from
     * a unit the player cannot see or another board's, nor into the unit's own hexes.
     */
    void lineOfSight(int fromEntityId, Coords to) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            Entity from = view.game.getEntity(fromEntityId);
            if (view.getClientgui() != null && from != null && source.owned(from) && source.visible(from)
                  && from.isOnBoard(view.getBoardId()) && view.getBoard().contains(to)
                  && !from.getOccupiedCoords().contains(to)) {
                view.getClientgui().measureLineOfSight(view.getBoardId(), from.getPosition(), to);
            }
        });
    }
}
