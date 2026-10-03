/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardTacticalGraphics;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;

/** Editor-only annotations for authored state with no visible artwork, on the existing hex marker plane. */
final class BoardEditorTerrain {
    private BoardEditorTerrain() { }

    static List<Integer> types(Hex hex, Set<Integer> blank) {
        List<Integer> result = new ArrayList<>();
        for (int type : hex.getTerrainTypes()) {
            boolean invisible = switch (type) {
                case Terrains.BLACK_ICE, Terrains.BLDG_BASE_COLLAPSED, Terrains.METAL_CONTENT -> true;
                case Terrains.GROUND_FLUFF -> blank.contains(type) && !cosmeticTransition(hex);
                case Terrains.BLDG_BASEMENT_TYPE, Terrains.FLUFF, Terrains.ROAD_FLUFF,
                      Terrains.WATER_FLUFF, Terrains.BLDG_FLUFF -> blank.contains(type);
                default -> false;
            };
            if (invisible) { result.add(type); }
        }
        return result.stream().sorted().toList();
    }

    /** Legacy theme blends use fluff levels 1-5 and store their 20-100% strength in the exits field. */
    private static boolean cosmeticTransition(Hex hex) {
        var ground = hex.getTerrain(Terrains.GROUND_FLUFF);
        return ground != null && ground.getLevel() >= 1 && ground.getLevel() <= 5
              && ground.hasExitsSpecified() && ground.getExits() >= 1 && ground.getExits() <= 5;
    }

    static BoardTactical capture(Hex hex, Coords coords, Set<Integer> blank) {
        List<Integer> types = types(hex, blank);
        if (types.isEmpty()) { return BoardTactical.EMPTY; }
        var graphics = new BoardTacticalGraphics();
        var local = BoardTacticalGraphics.onHexPlane(graphics, BoardArtwork.largeTileLocation(coords.getX(), coords.getY(), 1));
        try {
            local.setColor(new Color(0xDC162431, true));
            local.fillRoundRect(13, 16, 58, 38, 5, 5);
            local.setColor(new Color(hex.containsTerrain(Terrains.BLACK_ICE) ? 0xBBEEFF : 0xF2CD7A));
            local.setStroke(new BasicStroke(1.5f));
            if (hex.containsTerrain(Terrains.BLACK_ICE)) {
                for (int angle = 0; angle < 180; angle += 60) {
                    double radians = Math.toRadians(angle);
                    int x = (int) Math.round(8 * Math.cos(radians)), y = (int) Math.round(8 * Math.sin(radians));
                    local.drawLine(42 - x, 28 - y, 42 + x, 28 + y);
                }
            } else {
                local.drawLine(36, 20, 36, 35);
                local.drawPolyline(new int[] {36, 49, 45, 36}, new int[] {20, 20, 26, 26}, 4);
            }
            local.setFont(new Font(Font.DIALOG, Font.BOLD, 8));
            String label = Terrains.getEditorName(types.contains(Terrains.BLACK_ICE) ? Terrains.BLACK_ICE : types.getFirst());
            if (types.size() > 1) { label += " +" + (types.size() - 1); }
            float size = Math.min(8f, 54f * 8f / Math.max(1, local.getFontMetrics().stringWidth(label)));
            local.setFont(local.getFont().deriveFont(size));
            local.drawString(label, 42 - local.getFontMetrics().stringWidth(label) / 2f, 46);
        } finally { local.dispose(); }
        try { return graphics.snapshot(); }
        finally { graphics.dispose(); }
    }
}
