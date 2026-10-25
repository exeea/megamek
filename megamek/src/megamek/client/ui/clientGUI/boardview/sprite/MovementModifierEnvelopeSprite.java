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

import static megamek.client.ui.clientGUI.boardview.HexDrawUtilities.CUT_INSIDE;
import static megamek.client.ui.clientGUI.boardview.HexDrawUtilities.getHexBorderArea;
import static megamek.client.ui.clientGUI.boardview.HexDrawUtilities.getHexBorderAreaMid;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Point2D;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.util.StringDrawer;
import megamek.client.ui.util.UIUtil;
import megamek.common.Facing;

/**
 * Sprite for displaying information about movement modifier that can be achieved by provided MovePath. Multiple
 * MovementModifierEnvelopeSprite can be drawn on a single hex, one for each final facing.
 *
 * @author Saginatio
 */
public class MovementModifierEnvelopeSprite extends HexSprite {

    private final Color color;
    private final Facing facing;
    private final String modifier;

    public MovementModifierEnvelopeSprite(BoardGlyphContext boardView, TacticalMark.FacingModifier mark) {
        super(boardView, mark.coords());
        facing = mark.facing();
        color = mark.color();
        modifier = mark.modifier();
    }

    @Override
    public void prepare() {
        // adjust bounds (image size) to board zoom
        updateBounds();

        // create image for buffer
        image = createNewHexImage();
        Graphics2D graph = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(graph);

        // scale the following draws according to board zoom
        graph.scale(bv.getScale(), bv.getScale());

        paintTactical(graph);

        graph.dispose();
    }

    protected void paintTactical(Graphics2D graph) {
        // colored polygon at the hex border
        graph.setColor(color);
        graph.fill(getHexBorderArea(facing.getIntValue(), CUT_INSIDE, TacticalStyle.MODIFIER_BAND_WIDTH, TacticalStyle.MODIFIER_INSET));

        // draw the movement modifier if it's readable
        if (TacticalStyle.MODIFIER_FONT_SIZE * bv.getScale() > 4) {
            graph.setFont(graph.getFont().deriveFont(TacticalStyle.MODIFIER_FONT_SIZE));
            Point2D.Double pos = getHexBorderAreaMid(facing.getIntValue(), TacticalStyle.MODIFIER_BAND_WIDTH, TacticalStyle.MODIFIER_INSET);
            new StringDrawer(modifier).center().color(TacticalStyle.MODIFIER_FONT_COLOR).at((int) pos.x, (int) pos.y).draw(graph);
        }

    }

}
