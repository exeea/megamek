/*
 * Copyright (C) 2015-2025 The MegaMek Team. All Rights Reserved.
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

import static megamek.client.ui.clientGUI.boardview.HexDrawUtilities.*;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Shape;
import java.awt.image.ImageObserver;

import megamek.client.ui.clientGUI.boardview.BoardGlyphContext;
import megamek.client.ui.clientGUI.boardview.TacticalStyle;
import megamek.client.ui.util.UIUtil;
import megamek.common.RangeType;
import megamek.common.board.Coords;

/**
 * This sprite is used to paint the field of fire for weapons.
 *
 * <BR>
 * <BR>
 * Extends {@link MovementEnvelopeSprite}
 *
 * @author Simon
 */
public class FieldOfFireSprite extends MovementEnvelopeSprite {
    private static final int COLORS_MAX = 5;

    // in this sprite type, the images are very repetitive
    // therefore they get saved in a static array
    // they will be painted only once for each border
    // arrangement and color and repainted only when
    // the board is zoomed
    private static Image[][] images = new Image[64][COLORS_MAX];
    private float oldZoom;

    // individual sprite values
    private Color fillColor;
    private final int rangeBracket;
    private final int borderWidth;

    /**
     * Set for a zone drawn in a caller-chosen colour rather than a range bracket's. Those cannot use the
     * shared image cache: it is keyed by border pattern and range bracket alone, so every control zone
     * would land in one slot and reuse whichever colour happened to fill it first - which is exactly what
     * happened, and the zone never changed colour with its controller.
     */
    private Image ownImage;

    /** {@code true} when this sprite draws its own colour and must not share the cached images. */
    private boolean usesOwnColor;

    public FieldOfFireSprite(BoardGlyphContext boardView1, int rangeBracket, Coords l,
          int borders) {
        // the color of the super doesn't matter
        super(boardView1, Color.BLACK, l, borders);
        fillColor = TacticalStyle.withAlpha(TacticalStyle.rangeColor(rangeBracket), TacticalStyle.RANGE_BAND_OPACITY);
        this.rangeBracket = rangeBracket;
        this.borderWidth = TacticalStyle.RANGE_BAND_WIDTH;
    }

    /**
     * Draws the same bordered zone in a caller-chosen colour rather than a weapon range bracket's. Used
     * for objective control zones, which are tinted by whoever currently holds the point.
     *
     * @param boardView1 The board view to draw on
     * @param zoneColor  The colour to fill the zone with
     * @param l          The hex this piece of the zone covers
     * @param borders    Bit field of which of the six edges lie on the zone's outside
     */
    public FieldOfFireSprite(BoardGlyphContext boardView1, Color zoneColor, Coords l, int borders) {
        super(boardView1, Color.BLACK, l, borders);
        this.usesOwnColor = true;
        fillColor = TacticalStyle.withAlpha(zoneColor, TacticalStyle.CONTROL_ZONE_OPACITY);
        this.rangeBracket = RangeType.RANGE_MEDIUM;
        this.borderWidth = TacticalStyle.CONTROL_ZONE_BAND_WIDTH;
    }

    protected void setFillColor(Color c) {
        fillColor = c;
    }

    protected float getOldZoom() {
        return oldZoom;
    }

    protected void setOldZoom(float f) {
        oldZoom = f;
    }

    public int getRangeBracket() {
        return rangeBracket;
    }

    /** Sensor ranges and objective zones keep their own planar presentation. */
    public boolean isWeaponRange() {
        return getClass() == FieldOfFireSprite.class && !usesOwnColor;
    }

    @Override
    public void prepare() {
        // adjust bounds (image size) to board zoom
        updateBounds();

        // when the zoom hasn't changed and there is already
        // a prepared image for these borders, then do nothing more
        if ((bv.getScale() == oldZoom) && isReady()) {
            return;
        }

        // when the board is re-zoomed, ditch all images
        if (bv.getScale() != oldZoom) {
            oldZoom = bv.getScale();
            images = new Image[64][COLORS_MAX];
            ownImage = null;
        }

        // create image for buffer
        Image buffer = createNewHexImage();
        if (usesOwnColor) {
            ownImage = buffer;
        } else {
            images[borders][rangeBracket] = buffer;
        }
        Graphics2D graph = (Graphics2D) buffer.getGraphics();
        UIUtil.setHighQualityRendering(graph);

        // scale the following draws according to board zoom
        graph.scale(bv.getScale(), bv.getScale());

        paintTactical(graph);

        graph.dispose();
    }

    @Override
    protected void paintTactical(Graphics2D graph) {
        graph.setStroke(TacticalStyle.ZONE_LINE_STROKE);

        // this will take the right way to paint the borders
        // from the static arrays; depends on the exact
        // borders that are present
        int turn = TacticalStyle.borderLayoutDirection(borders);
        switch (TacticalStyle.borderLayout(borders)) {
            case 1: // 2 adjacent borders
                drawBorderXC(graph, getHexCrossArea01(turn, borderWidth),
                      getHexCrossLine01(turn, borderWidth));
                break;
            case 2: // 3 adjacent borders
                drawBorderXC(graph, getHexCrossArea012(turn, borderWidth),
                      getHexCrossLine012(turn, borderWidth));
                break;
            case 3: // 4 adjacent borders
                drawBorderXC(graph, getHexCrossArea0123(turn, borderWidth),
                      getHexCrossLine0123(turn, borderWidth));
                break;
            case 4: // twice two adjacent borders
                drawBorderXC(graph, getHexCrossArea01(turn, borderWidth),
                      getHexCrossLine01(turn, borderWidth));
                drawBorderXC(graph, getHexCrossArea01(turn + 3, borderWidth),
                      getHexCrossLine01(turn + 3, borderWidth));
                break;
            case 5: // three adjacent borders and one lone
                drawBorderXC(graph, getHexCrossArea012(turn, borderWidth),
                      getHexCrossLine012(turn, borderWidth));
                drawLoneBorder(graph, turn + 4);
                break;
            case 6: // two adjacent borders and one lone
                drawBorderXC(graph, getHexCrossArea01(turn, borderWidth),
                      getHexCrossLine01(turn, borderWidth));
                drawLoneBorder(graph, turn + 3);
                break;
            case 7: // two adjacent borders and one lone (other hex face)
                drawBorderXC(graph, getHexCrossArea01(turn, borderWidth),
                      getHexCrossLine01(turn, borderWidth));
                drawLoneBorder(graph, turn + 4);
                break;
            case 8:
                drawBorderXC(graph, getHexCrossArea01234(turn, borderWidth),
                      getHexCrossLine01234(turn, borderWidth));
                break;
            default:
                drawNormalBorders(graph, borders);
        }

    }

    protected void drawBorderXC(Graphics2D graph, Shape fillShape, Shape lineShape) {
        // 1) thick transparent border
        graph.setColor(fillColor);
        graph.fill(fillShape);

        // 2) thin dashed line border
        graph.setColor(TacticalStyle.ZONE_LINE_COLOR);
        graph.draw(lineShape);
    }

    protected void drawLoneBorder(Graphics2D graph, int dir) {
        drawBorderXC(graph, getHexBorderArea(dir, CUT_BORDER, borderWidth), getHexBorderLine(dir));
    }

    private void drawNormalBorders(Graphics2D graph, int borderMask) {
        // cycle through directions
        for (int i = 0; i < 6; i++) {
            if ((borderMask & (1 << i)) != 0) {
                // 1) thick transparent border
                int cut = ((borderMask & (1 << ((i + 1) % 6))) == 0) ? CUT_RIGHT_BORDER : CUT_RIGHT_INSIDE;
                cut |= ((borderMask & (1 << ((i + 5) % 6))) == 0) ? CUT_LEFT_BORDER : CUT_LEFT_INSIDE;

                drawBorderXC(graph, getHexBorderArea(i, cut, borderWidth),
                      getHexBorderLine(i, cut, TacticalStyle.ZONE_LINE_THICKNESS / 2));
            }
        }
    }

    @Override
    public boolean isReady() {
        Image prepared = usesOwnColor ? ownImage : images[borders][rangeBracket];
        return (bv.getScale() == oldZoom) && (prepared != null);
    }

    @Override
    public void drawOnto(Graphics g, int x, int y, ImageObserver observer, boolean makeTranslucent) {
        if (isReady()) {
            Image prepared = usesOwnColor ? ownImage : images[borders][rangeBracket];
            if (makeTranslucent) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
                g2.drawImage(prepared, x, y, observer);
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
            } else {
                g.drawImage(prepared, x, y, observer);
            }
        } else {
            prepare();
        }
    }
}
