/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;

/** Floating hex markings for the captured restriction flag; never physical obstacles or movement rules. */
final class BoardImpassable {
    private static final int RED = 0xF04444;
    private static final float BORDER_WIDTH = 1.8f;
    private static final float INSET = 1;
    private static final float HATCH_SPACING = 24;
    private static final float HATCH_WIDTH = 12;
    private static final List<BoardTactical.Fill> TEMPLATE = template();

    private BoardImpassable() { }

    static List<BoardTactical.Fill> fills(BoardScene scene, Coords hover, boolean planning) {
        List<BoardTactical.Fill> result = new ArrayList<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            if (!tile.impassable()) { continue; }
            Coords coords = tile.coords();
            var anchor = BoardTacticalGeometry.centre(coords);
            boolean emphasized = planning || coords.equals(hover);
            for (BoardTactical.Fill fill : TEMPLATE) {
                var contours = fill.contours().stream().map(contour -> new BoardTactical.Contour(contour.points().stream()
                      .map(point -> new BoardTactical.Point(anchor.x() + point.x(), anchor.y() + point.y())).toList()))
                      .toList();
                int color = emphasized ? ink(fill.border() == null ? 112 : 240) : fill.argb();
                // Both the border and stripes use the existing floating-marker plane for this owner hex.
                var border = fill.border() == null ? null : new BoardTactical.HexBorder(anchor, INSET, BORDER_WIDTH, 1);
                result.add(new BoardTactical.Fill(contours, fill.winding(), color,
                      BoardTactical.Playback.HOLD_DURING_PLAYBACK, border, anchor));
            }
        }
        return List.copyOf(result);
    }

    /** Build the two shapes once, about the hex centre; each hex only translates and recolors their contours. */
    private static List<BoardTactical.Fill> template() {
        float half = BoardGeometry.TILE_WIDTH / 2 + BoardGeometry.TILE_HEIGHT;
        Path2D stripes = new Path2D.Float();
        for (float offset = -half; offset < half; offset += HATCH_SPACING) {
            stripes.moveTo(offset, -BoardGeometry.TILE_HEIGHT / 2);
            stripes.lineTo(offset + HATCH_WIDTH, -BoardGeometry.TILE_HEIGHT / 2);
            stripes.lineTo(offset + HATCH_WIDTH - BoardGeometry.TILE_HEIGHT, BoardGeometry.TILE_HEIGHT / 2);
            stripes.lineTo(offset - BoardGeometry.TILE_HEIGHT, BoardGeometry.TILE_HEIGHT / 2);
            stripes.closePath();
        }
        Area hatch = new Area(stripes);
        hatch.intersect(new Area(BoardTacticalGeometry.insetHexagon(INSET + BORDER_WIDTH)));
        var origin = new BoardTactical.Point(0, 0);
        var border = new BoardTactical.HexBorder(origin, INSET, BORDER_WIDTH, 1);
        return List.of(BoardTacticalGeometry.fill(hatch, origin, ink(48), BoardTactical.Playback.LIVE, null, null),
              BoardTacticalGeometry.fill(BoardTacticalGeometry.ring(INSET, BORDER_WIDTH), origin, ink(180),
                    BoardTactical.Playback.LIVE, border, null));
    }

    private static int ink(int alpha) { return alpha << 24 | RED; }
}
