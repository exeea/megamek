/*
 * Copyright (C) 2014-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.clientGUI.boardview.sprite;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.HexDrawUtilities;
import megamek.client.ui.clientGUI.boardview.TacticalGlyphs;
import megamek.client.ui.tileset.HexTileset;
import megamek.client.ui.util.UIUtil;
import megamek.common.moves.MoveStep;

/**
 * Sprite for a step in a movement path. Only one sprite should exist for any hex in a path. Contains a colored number,
 * and arrows indicating entering, exiting or turning.
 */
public class StepSprite extends Sprite {

    private final MoveStep step;
    private final boolean isLastStep;
    private Image baseScaleImage;

    public StepSprite(BoardGlyphContext boardView1, final MoveStep step,
          boolean isLastStep) {
        super(boardView1);
        this.step = step;
        this.isLastStep = isLastStep;

        // step is the size of the hex that this step is in
        bounds = new Rectangle(bv.getHexLocation(step.getPosition()), bv.getHexSize());
        image = null;
        baseScaleImage = null;
    }

    /**
     * Refreshes this StepSprite's image to handle changes in the zoom level.
     */
    public void refreshZoomLevel() {
        if (baseScaleImage == null) {
            return;
        }

        image = bv.getScaledImage(baseScaleImage, false);
    }

    @Override
    public void prepare() {
        // create image for buffer
        Image tempImage = new BufferedImage(HexTileset.HEX_W, HexTileset.HEX_H,
              BufferedImage.TYPE_INT_ARGB);
        Graphics graph = tempImage.getGraphics();
        Graphics2D g2D = (Graphics2D) graph;

        UIUtil.setHighQualityRendering(graph);

        // fill with key color
        graph.setColor(new Color(0, 0, 0, 0));
        graph.fillRect(0, 0, HexTileset.HEX_W, HexTileset.HEX_H);

        TacticalGlyphs.paintStep(g2D, bv, step, isLastStep);
        if (TacticalGlyphs.hasBorder(step)) {
            graph.setColor(TacticalGlyphs.stepColor(step, isLastStep));
            g2D.fill(HexDrawUtilities.getHexFullBorderArea(3, 0));
        }

        baseScaleImage = tempImage;
        image = bv.getScaledImage(tempImage, false);

        graph.dispose();
    }

    @Override
    public Rectangle getBounds() {
        bounds = new Rectangle(bv.getHexLocation(step.getPosition()), bv.getHexSize());
        return bounds;
    }

    public MoveStep getStep() {
        return step;
    }
}
