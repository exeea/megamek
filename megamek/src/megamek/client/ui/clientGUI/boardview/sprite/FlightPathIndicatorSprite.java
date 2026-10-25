/*
 * Copyright (C) 2023-2025 The MegaMek Team. All Rights Reserved.
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

import java.awt.Graphics2D;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.TacticalGlyphs;
import megamek.client.ui.clientGUI.boardview.TacticalMark;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * The Flight Path Indicator Sprite paints a path in front of an aerospace movement path and indicates the turn status
 * and remaining velocity by showing the length of path the flight must follow and when the unit can turn.
 * <p>
 * A green solid circle indicates the craft can make a free turn on that hex. A yellow solid circle indicates the craft
 * can make a turn with cost to thrust. A red empty circle indicates the craft cannot turn on that hex. A green flag
 * indicates the craft has used all its remaining velocity on that hex. A yellow two-way diamond indicates the craft
 * will fly off the map with remaining velocity.
 */
public class FlightPathIndicatorSprite extends HexSprite {

    private final TacticalMark.FlightPath indicator;

    public FlightPathIndicatorSprite(BoardGlyphContext boardView, TacticalMark.FlightPath indicator) {
        super(boardView, indicator.steps().get(indicator.index()).getPosition());
        this.indicator = indicator;
    }

    @Override
    public void prepare() {
        updateBounds();
        image = createNewHexImage();
        Graphics2D graph = (Graphics2D) image.getGraphics();
        UIUtil.setHighQualityRendering(graph);
        graph.scale(bv.getScale(), bv.getScale());
        graph.setFont(FontHandler.symbolFont());
        TacticalGlyphs.paintFlightPath(graph, bv, indicator, false);
        graph.dispose();
    }
}
