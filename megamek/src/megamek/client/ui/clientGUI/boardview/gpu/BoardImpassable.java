/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.awt.Point;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardTacticalGraphics;
import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
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
            float x = coords.getX() * BoardGeometry.TILE_WIDTH * .75f;
            float y = (coords.getY() + (coords.getX() & 1) * .5f) * BoardGeometry.TILE_HEIGHT;
            var anchor = new BoardTactical.Point(x + BoardGeometry.TILE_WIDTH / 2, y + BoardGeometry.TILE_HEIGHT / 2);
            boolean emphasized = planning || coords.equals(hover);
            for (BoardTactical.Fill fill : TEMPLATE) {
                var contours = fill.contours().stream().map(contour -> new BoardTactical.Contour(contour.points().stream()
                      .map(point -> new BoardTactical.Point(x + point.x(), y + point.y())).toList())).toList();
                int color = emphasized ? ink(fill.border() == null ? 112 : 240) : fill.argb();
                // Both the border and stripes use the existing floating-marker plane for this owner hex.
                var border = fill.border() == null ? null : new BoardTactical.HexBorder(anchor, INSET, BORDER_WIDTH, 1);
                result.add(new BoardTactical.Fill(contours, fill.winding(), color,
                      BoardTactical.Playback.HOLD_DURING_PLAYBACK, border, anchor));
            }
        }
        return List.copyOf(result);
    }

    /** Build the two local shapes once; each hex only translates and recolors their immutable contours. */
    private static List<BoardTactical.Fill> template() {
        float width = BoardGeometry.TILE_WIDTH, height = BoardGeometry.TILE_HEIGHT;
        Path2D stripes = new Path2D.Float();
        for (float offset = 0; offset < width + height; offset += HATCH_SPACING) {
            stripes.moveTo(offset, 0);
            stripes.lineTo(offset + HATCH_WIDTH, 0);
            stripes.lineTo(offset + HATCH_WIDTH - height, height);
            stripes.lineTo(offset - height, height);
            stripes.closePath();
        }
        Area hatch = new Area(stripes);
        hatch.intersect(new Area(HexDrawUtilities.getHexFullBorderLine(INSET + BORDER_WIDTH)));
        var graphics = new BoardTacticalGraphics();
        try {
            graphics.setColor(new Color(ink(48), true));
            graphics.fill(hatch);
            graphics.setColor(new Color(ink(180), true));
            graphics.fillHexBorder(new Point(), 1, INSET, BORDER_WIDTH, true);
            return graphics.snapshot().fills();
        } finally {
            graphics.dispose();
        }
    }

    private static int ink(int alpha) { return alpha << 24 | RED; }
}
