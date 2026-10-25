/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.ImageObserver;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.TacticalGlyphs;
import megamek.client.ui.tileset.HexTileset;
import megamek.common.board.Coords;

/**
 * The sensor sweep a unit has ordered for the End Phase, drawn from the scanning unit to whatever it was pointed at.
 *
 * <p>An attack is a single arrow because something travels the line. A scan is a sweep, so this draws a run of
 * widening arcs instead, like the ripples on a radar plot, spaced along the line and facing the target. That reads as
 * "this unit is listening to that" rather than "this unit is shooting that", which matters because the two orders can
 * stand at the same time on the same board.</p>
 *
 * @author Claude Code (Opus 5)
 */
public class ScanSprite extends Sprite {

    private final Coords scannerPosition;
    private final Coords targetPosition;
    private final Color sweepColor;

    private Point scannerPoint;
    private Point targetPoint;

    /**
     * @param boardView       the board view to draw on
     * @param scannerPosition where the scanning unit stands
     * @param targetPosition  the hex being read, which is the target unit's hex when a unit was picked
     * @param sweepColor      the colour to draw in, which is the same amber as the SCANNING label
     */
    public ScanSprite(BoardGlyphContext boardView, Coords scannerPosition, Coords targetPosition, Color sweepColor) {
        super(boardView);
        this.scannerPosition = scannerPosition;
        this.targetPosition = targetPosition;
        this.sweepColor = sweepColor;
        image = null;
    }

    /** Works out the two hex centres the sweep runs between, in board pixels. */
    private void locate() {
        scannerPoint = hexCentre(scannerPosition);
        targetPoint = hexCentre(targetPosition);
    }

    /**
     * The board's zoom factor. Named rather than read inline, because the field it comes from is called
     * {@code bv} on {@link Sprite} and reads as battle value in a BattleTech codebase, which it is not.
     *
     * @return how many screen pixels one unscaled pixel is drawn as
     */
    private double boardScale() {
        return bv.getScale();
    }

    /**
     * @param coords the hex to locate
     *
     * @return the centre of that hex in board pixels
     */
    private Point hexCentre(Coords coords) {
        Point corner = bv.getHexLocation(coords);
        corner.translate((int) (HexTileset.HEX_W / 2.0 * boardScale()),
              (int) (HexTileset.HEX_H / 2.0 * boardScale()));
        return corner;
    }

    @Override
    public Rectangle getBounds() {
        locate();
        int pad = (int) (TacticalGlyphs.SCAN_REACH * boardScale());
        bounds = new Rectangle(Math.min(scannerPoint.x, targetPoint.x) - pad,
              Math.min(scannerPoint.y, targetPoint.y) - pad,
              Math.abs(targetPoint.x - scannerPoint.x) + (pad * 2),
              Math.abs(targetPoint.y - scannerPoint.y) + (pad * 2));
        return bounds;
    }

    /**
     * The board hands {@code drawOnto} the sprite's own bounds as the origin to draw from, the way it does for the
     * attack arrows, so everything drawn has to be measured from the top left of those bounds rather than from the
     * board. Adding the board position and the origin together put the whole sweep off the visible map.
     *
     * @return the top left of this sprite's bounds, in board pixels
     */
    private Point boundsOrigin() {
        int pad = (int) (TacticalGlyphs.SCAN_REACH * boardScale());
        return new Point(Math.min(scannerPoint.x, targetPoint.x) - pad,
              Math.min(scannerPoint.y, targetPoint.y) - pad);
    }

    @Override
    public void prepare() {
        getBounds();
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void drawOnto(Graphics graphics, int x, int y, ImageObserver observer) {
        locate();
        Graphics2D graphics2D = (Graphics2D) graphics.create();
        Point origin = boundsOrigin();
        graphics2D.translate(x - origin.x, y - origin.y);
        TacticalGlyphs.paintScan(graphics2D, scannerPoint, targetPoint, sweepColor, boardScale());
        graphics2D.dispose();
    }

    @Override
    public boolean isInside(Point point) {
        return false;
    }
}
