/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.math.MathUtils;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Entity;

/** EDT adapter for the native ruler; its immutable snapshot supplies both the panel and the board ray. */
final class GpuLosResult {
    private final GpuBoardSource source;
    private BoardClientState owner;
    private megamek.common.board.Board board;
    private megamek.common.Player player;
    private RulerModel model;

    GpuLosResult(GpuBoardSource source) { this.source = source; }

    RulerModel model() {
        GpuBoardSource.requireSwingThread();
        BoardClientState view = source.currentView();
        if (model == null || owner != view || board != view.getBoard() || player != view.getLocalPlayer()) {
            owner = view; board = view.getBoard(); player = view.getLocalPlayer();
            model = new RulerModel(view.game, view.getBoardId(), player);
        }
        return model;
    }

    RulerModel.Snapshot capture() { return model().capture(); }

    /**
     * The height above its hex that the board's pointer shows at a terrain hit of world height {@code z}, in levels:
     * the floor under the hit, a building's floor or roof or else the ground, as the hover ring marks it (the user's
     * decision of 2026-10-03: the ruler measures from and to that height).
     */
    static int pointedHeight(Hex hex, float z) {
        return pointedHeight(hex.getLevel(), z);
    }

    static int pointedHeight(int ground, float z) {
        return Math.max(0, MathUtils.floor(z / BoardGeometry.level() + .0001f) - ground);
    }

    /**
     * GL-safe: the menu's native line of sight from an own unit to a hex, its target
     * at the height the pointer showed ({@code pointedZ}, the terrain hit's world height; NaN keeps the ruler's own);
     * never from a unit the player cannot see or another board's, nor into the unit's own hexes.
     */
    void lineOfSight(int fromEntityId, Coords to, float pointedZ) {
        source.command(() -> {
            BoardClientState view = source.currentView();
            Entity from = view.game.getEntity(fromEntityId);
            if (from != null && source.owned(from) && source.visible(from)
                  && from.isOnBoard(view.getBoardId()) && view.getBoard().contains(to)
                  && !from.getOccupiedCoords().contains(to)) {
                model().measure(from.getPosition(), to, fromEntityId, Float.isNaN(pointedZ) ? null
                      : pointedHeight(view.getBoard().getHex(to), pointedZ));
            }
        });
    }
}
