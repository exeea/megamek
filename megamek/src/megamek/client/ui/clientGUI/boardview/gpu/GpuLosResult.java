/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.event.InputEvent;

import com.badlogic.gdx.math.MathUtils;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/**
 * EDT service for line of sight, which stays MegaMek's Swing ruler over the native window (the user's decision of
 * 2026-10-03): the measurement waiting for its second point, and the menus' line of sight from a unit.
 */
final class GpuLosResult {
    /**
     * The modifier of a measurement waiting for its second point: Ctrl for a line of sight, Alt for a distance, 0 for
     * none. A plain left click on the board ends that measurement.
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
     * The modifier of a measurement waiting for its second point: Ctrl
     * while a line of sight has its first point, Alt while the ruler has its start alone, else 0.
     */
    static int pending(BoardClientState view) {
        if (view.getFirstLOS() != null) {
            return InputEvent.CTRL_DOWN_MASK;
        }
        return (view.getRulerStart() != null) != (view.getRulerEnd() != null) ? InputEvent.ALT_DOWN_MASK : 0;
    }

    /**
     * The height above its hex that the board's pointer shows at a terrain hit of world height {@code z}, in levels:
     * the floor under the hit, a building's floor or roof or else the ground, as the hover ring marks it (the user's
     * decision of 2026-10-03: the ruler measures from and to that height).
     */
    static int pointedHeight(Hex hex, float z) {
        return Math.max(0, MathUtils.floor(z / BoardGeometry.level() + .0001f) - hex.getLevel());
    }

    /**
     * GL-safe: the menu's line of sight from an own unit to a hex, which MegaMek's ruler measures and shows, its target
     * at the height the pointer showed ({@code pointedZ}, the terrain hit's world height; NaN keeps the ruler's own);
     * never from a unit the player cannot see or another board's, nor into the unit's own hexes.
     */
    void lineOfSight(int fromEntityId, Coords to, float pointedZ) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            Entity from = view.game.getEntity(fromEntityId);
            if (view.getClientgui() != null && from != null && source.owned(from) && source.visible(from)
                  && from.isOnBoard(view.getBoardId()) && view.getBoard().contains(to)
                  && !from.getOccupiedCoords().contains(to)) {
                view.getClientgui().measureLineOfSight(view.getBoardId(), from.getPosition(), to);
                if (!Float.isNaN(pointedZ)) {
                    view.getClientgui().setRulerHeight(view.getBoardId(), to,
                          pointedHeight(view.getBoard().getHex(to), pointedZ));
                }
            }
        });
    }
}
