/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
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

    /** A deployment hex's marker: its zone colour's ring and the zone IDs, on the hex's annotation plane. */
    static BoardTactical capture(Hex hex, Coords coords) {
        if (!hex.containsTerrain(Terrains.DEPLOYMENT_ZONE)) { return BoardTactical.EMPTY; }
        int mask = hex.getTerrain(Terrains.DEPLOYMENT_ZONE).getExits();
        Color color = Color.getHSBColor(Math.floorMod(mask * 137, 360) / 360f, .42f, 1);
        var centre = BoardTacticalGeometry.centre(coords);
        var ring = BoardTacticalGeometry.fill(BoardTacticalGeometry.ring(1, 2), centre, color.getRGB(),
              BoardTactical.Playback.HIDE_DURING_MOVEMENT, new BoardTactical.HexBorder(centre, 1, 2, 1, true), null);
        // The IDs as the game reads them and the Edit panel shows them, centred under the hex's middle.
        String zones = megamek.client.ui.boardeditor.BoardEditorSession.zoneIds(mask);
        String label = zones.isEmpty() ? Messages.getString("BoardEditor.deploymentZoneMarker.none")
              : Messages.getString("BoardEditor.deploymentZoneMarker", zones);
        Font font = new Font(Font.DIALOG, Font.BOLD, 8);
        float width = (float) font.getStringBounds(label, new FontRenderContext(null, false, false)).getWidth();
        var text = new BoardTactical.Text(label, font, -width / 2, 25, Color.WHITE.getRGB());
        return new BoardTactical(List.of(ring), List.of(new BoardTactical.Label(centre, text)));
    }
}
