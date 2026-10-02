/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import static megamek.client.ui.tileset.HexTileset.HEX_H;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/** Shared label content; offsets are artwork pixels, and fromTop anchors the glyph top instead of its baseline. */
public record BoardHexText(String text, int baseline, Font font, int argb, boolean fromTop, int elevation) {
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    private static final int HEX_TEXT_MARGIN = 6;

    public static void draw(Graphics2D graphics, Point origin, int width, List<BoardHexText> labels) {
        for (BoardHexText label : labels) {
            graphics.setFont(label.font());
            graphics.setColor(new Color(label.argb(), true));
            var metrics = graphics.getFontMetrics();
            int baseline = origin.y + label.baseline() + (label.fromTop() ? metrics.getAscent() : 0);
            graphics.drawString(label.text(), origin.x + (width - metrics.stringWidth(label.text())) / 2, baseline);
        }
    }
    public static List<BoardHexText> capture(Coords coords, Hex hex, Board board, float scale,
          Font font_hexNumber, Font font_elev) {
        List<BoardHexText> labels = new ArrayList<>();
        Color color = board.isSpace() ? GUIP.getBoardSpaceTextColor() : GUIP.getBoardTextColor();
        if (GUIP.getCoordsEnabled() && scale >= 0.5) {
            labels.add(new BoardHexText(coords.getBoardNum(), (int) (HEX_TEXT_MARGIN * scale), font_hexNumber,
                  color.getRGB(), true, 0));
        }
        if (scale > 0.5f) {
            int level = hex.getLevel();
            int depth = hex.depth(false);
            Terrain basement = hex.getTerrain(Terrains.BLDG_BASEMENT_TYPE);
            if (basement != null) {
                depth = 0;
            }
            int height = Math.max(hex.terrainLevel(Terrains.BLDG_ELEV), hex.terrainLevel(Terrains.BRIDGE_ELEV));
            height = Math.max(height, hex.terrainLevel(Terrains.INDUSTRIAL));
            int yPosition = HEX_H - HEX_TEXT_MARGIN;
            if (level != 0) {
                labels.add(new BoardHexText(Messages.getString("BoardView1.LEVEL") + level,
                      (int) (yPosition * scale), font_elev, color.getRGB(), false, 0));
                yPosition -= 10;
            }
            if (depth != 0) {
                labels.add(new BoardHexText(Messages.getString("BoardView1.DEPTH") + depth,
                      (int) (yPosition * scale), font_elev, color.getRGB(), false, 0));
                yPosition -= 10;
            }
            if (height > 0) {
                labels.add(new BoardHexText(Messages.getString("BoardView1.HEIGHT") + " " + height,
                      (int) (yPosition * scale), font_elev, GUIP.getBuildingTextColor().getRGB(), false, height));
                yPosition -= 10;
            }
            if (hex.terrainLevel(Terrains.FOLIAGE_ELEV) == 1) {
                labels.add(new BoardHexText(Messages.getString("BoardView1.LowFoliage"),
                      (int) (yPosition * scale), font_elev, GUIP.getLowFoliageColor().getRGB(), false, 0));
            }
        }
        return List.copyOf(labels);
    }

    public static void drawInvalid(java.awt.Graphics graphics, java.awt.Point origin, float scale) {
        new megamek.client.ui.util.StringDrawer(Messages.getString("BoardEditor.INVALID"))
              .color(GUIP.getWarningColor()).font(megamek.client.ui.util.FontHandler.notoFont().deriveFont(Font.BOLD))
              .center().at(origin.x + (int) (megamek.client.ui.tileset.HexTileset.HEX_W / 2.0f * scale),
                    origin.y + (int) (HEX_H / 2.0f * scale))
              .fontSize(14.0f * scale).outline(Color.WHITE, scale / 2).draw(graphics);
    }
}
