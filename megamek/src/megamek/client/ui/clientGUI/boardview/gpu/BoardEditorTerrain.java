/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.BoardTacticalGraphics;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.enums.BasementType;
import megamek.common.units.Terrains;

/** EDT capture of editor badges and deployment annotations; no render thread reads live terrain. */
final class BoardEditorTerrain {
    private BoardEditorTerrain() { }

    enum Kind { BASEMENT, UNKNOWN_BASEMENT, COLLAPSED_BASEMENT, BLACK_ICE, METAL_DEPOSITS }
    record Badge(Kind kind, String text) { }

    /** Stable top-to-bottom order, with the full list frozen before it crosses to the render thread. */
    static List<Badge> badges(Hex hex) {
        List<Badge> result = new ArrayList<>();
        if (hex.containsTerrain(Terrains.BUILDING)) {
            var basement = BasementType.getType(hex.terrainLevel(Terrains.BLDG_BASEMENT_TYPE));
            if (!basement.isNone()) {
                result.add(new Badge(basement.isUnknown() ? Kind.UNKNOWN_BASEMENT : Kind.BASEMENT,
                      basement.isUnknown() ? "B?" : "B" + basement.getDepth()));
            }
        }
        if (hex.terrainLevel(Terrains.BLDG_BASE_COLLAPSED) == 1) {
            result.add(new Badge(Kind.COLLAPSED_BASEMENT, Messages.getString("BoardEditor.badge.collapsed")));
        }
        if (hex.containsTerrain(Terrains.BLACK_ICE)) {
            result.add(new Badge(Kind.BLACK_ICE, Terrains.getEditorName(Terrains.BLACK_ICE)));
        }
        if (hex.containsTerrain(Terrains.METAL_CONTENT)) {
            result.add(new Badge(Kind.METAL_DEPOSITS,
                  Messages.getString("BoardEditor.badge.metal", hex.terrainLevel(Terrains.METAL_CONTENT))));
        }
        return List.copyOf(result);
    }

    static BoardTactical capture(Hex hex, Coords coords) {
        boolean deployment = hex.containsTerrain(Terrains.DEPLOYMENT_ZONE);
        if (!deployment) { return BoardTactical.EMPTY; }
        var graphics = new BoardTacticalGraphics();
        var local = BoardTacticalGraphics.onHexPlane(graphics, BoardArtwork.largeTileLocation(coords.getX(), coords.getY(), 1));
        try {
            if (deployment) {
                int mask = hex.getTerrain(Terrains.DEPLOYMENT_ZONE).getExits();
                Color color = Color.getHSBColor(Math.floorMod(mask * 137, 360) / 360f, .42f, 1);
                BoardTacticalGraphics.drawDeployment(graphics, g -> {
                    g.setColor(color);
                    ((BoardTacticalGraphics) g).fillHexBorder(BoardArtwork.largeTileLocation(coords.getX(), coords.getY(), 1), 1, 1, 2);
                });
                local.setColor(Color.WHITE);
                local.setFont(new Font(Font.DIALOG, Font.BOLD, 8));
                // The IDs as the game reads them and the Edit panel shows them.
                String zones = megamek.client.ui.boardeditor.BoardEditorSession.zoneIds(mask);
                String label = zones.isEmpty() ? Messages.getString("BoardEditor.deploymentZoneMarker.none")
                      : Messages.getString("BoardEditor.deploymentZoneMarker", zones);
                local.drawString(label, 42 - local.getFontMetrics().stringWidth(label) / 2f, 61);
            }
        } finally { local.dispose(); }
        try { return graphics.snapshot(); }
        finally { graphics.dispose(); }
    }
}
