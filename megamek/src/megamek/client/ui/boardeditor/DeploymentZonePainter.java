/*
 * Copyright (C) 2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.boardeditor;

import static megamek.common.units.Terrains.DEPLOYMENT_ZONE;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.util.List;

import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.StringDrawer;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.units.Terrain;

/**
 * Shared deployment-zone artwork for both editor viewports
 */
class DeploymentZonePainter {

    private static final Color SELECTED_ZONE = Color.GREEN;
    private static final Color OTHER_ZONE = new Color(215, 215, 80);
    private static final Color OTHER_PAINT_MODE = new Color(255, 255, 0, 80);
    private static final Color ZONE_TEXT_COLOR = Color.WHITE;
    private static final Color ZONE_TEXT_OUTLINE = Color.DARK_GRAY;

    private int selectedDeploymentZone = 1;
    private boolean deploymentZoneMode;

    void setSelectedDeploymentZone(int selectedDeploymentZone) {
        this.selectedDeploymentZone = selectedDeploymentZone;
    }

    void setDeploymentZoneMode(boolean deploymentZoneMode) {
        this.deploymentZoneMode = deploymentZoneMode;
    }

    void draw(Graphics2D graphics2D, Hex hex, float scale) {
        if (hex.containsTerrain(DEPLOYMENT_ZONE)) {
            if (deploymentZoneMode) {
                Terrain deploymentTerrain = hex.getTerrain(DEPLOYMENT_ZONE);
                if (containsSelectedZone(deploymentTerrain) && !containsOtherZone(deploymentTerrain)) {
                    drawBorder(graphics2D, scale, SELECTED_ZONE, 5, 5);
                } else if (containsSelectedZone(deploymentTerrain)) {
                    drawBorder(graphics2D, scale, OTHER_ZONE, 8, 5);
                    drawBorder(graphics2D, scale, SELECTED_ZONE, 5, 5);
                } else {
                    drawBorder(graphics2D, scale, OTHER_ZONE, 5, 5);
                }

                drawZoneText(graphics2D, scale, deploymentTerrain);

            } else {
                drawBorder(graphics2D, scale, OTHER_PAINT_MODE, 5, 2);
            }
        }
    }

    private static void drawBorder(Graphics2D graphics, float scale, Color color, double padding, double width) {
        graphics.setColor(color);
        graphics.fill(AffineTransform.getScaleInstance(scale, scale)
              .createTransformedShape(HexDrawUtilities.getHexFullBorderArea(width, padding)));
    }

    /**
     * Draws zone text in the form "2, 5, 8" to the center of the hex.
     */
    private static void drawZoneText(Graphics2D graphics2D, float scale, Terrain deploymentTerrain) {
        List<Integer> zones = Board.exitsAsIntList(deploymentTerrain.getExits());
        List<String> zonesAsString = zones.stream().map(String::valueOf).toList();
        int x = (int) (scale * HexTileset.HEX_W / 2);
        int y = (int) (scale * HexTileset.HEX_H / 2);
        var zoneText = new StringDrawer(String.join(", ", zonesAsString))
              .at(x, y)
              .center()
              .outline(ZONE_TEXT_OUTLINE, 1f)
              .fontSize(HexTileset.HEX_W * scale / 4)
              .maxWidth(x)
              .color(ZONE_TEXT_COLOR);
        zoneText.draw(graphics2D);
    }

    private boolean containsSelectedZone(Terrain deploymentZone) {
        return (deploymentZone.getExits() & (1 << (selectedDeploymentZone - 1))) != 0;
    }

    private boolean containsOtherZone(Terrain deploymentZone) {
        return (deploymentZone.getExits() & ~(1 << (selectedDeploymentZone - 1))) != 0;
    }
}
