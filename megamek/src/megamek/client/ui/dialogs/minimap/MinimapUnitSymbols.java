/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.minimap;

import static java.lang.Math.PI;
import static java.lang.Math.cos;
import static java.lang.Math.sin;
import static java.lang.Math.toRadians;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.units.Aero;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Mek;
import megamek.common.units.MekWarrior;
import megamek.common.units.ProtoMek;
import megamek.common.units.Tank;
import megamek.common.units.VTOL;

/** Contains geometric paths for drawing the minimap unit symbols. */
public class MinimapUnitSymbols {

    private static final GUIPreferences GUIP = GUIPreferences.getInstance();
    public static final Path2D STRAT_BASE_RECT;
    public static final Path2D STRAT_INFANTRY;
    public static final Path2D STRAT_MEK;
    public static final Path2D STRAT_VTOL;
    public static final Path2D STRAT_TANK_TRACKED;
    public static final Path2D STRAT_AERO;
    public static final Path2D STRAT_SPHEROID;
    public static final Path2D STRAT_HOVER;
    public static final Path2D STRAT_WHEELED;
    public static final Path2D STRAT_NAVAL;
    public static final Path2D STRAT_DESTROYED;
    public static final Path2D FACING_ARROW;
    public static final Path2D STD_MEK;
    public static final Path2D STD_TANK;
    public static final Path2D STD_VTOL;
    public static final Path2D STD_AERO;
    public static final Path2D STD_INFANTRY;
    public static final Path2D STD_DESTROYED;
    public static final Path2D STD_MEKWARRIOR;
    public static final Path2D STD_NAVAL;
    public static final Path2D STD_SPHEROID;
    public static final Dimension STRAT_SYMBOL_SIZE = new Dimension(167, 103);
    public static final Dimension STD_SYMBOL_SIZE = new Dimension(100, 100);
    public static final double STRAT_CX = STRAT_SYMBOL_SIZE.getWidth() / 5; // X center for two symbols

    private static final double PI_HALF = PI / 2;
    private static final String[] STRAT_WEIGHTS = { "L", "L", "M", "H", "A", "A" };
    /** A destroyed unit's colours are this opaque. */
    private static final int DESTROYED_ALPHA = 64;
    /** A unit's place in a hex several units share: each next one this far up and right, in symbol units. */
    private static final int STACK_SHIFT = 20;
    private static final float OUTER_BORDER = 30f;
    private static final float INNER_BORDER = 10f;
    private static final float FORM_STROKE = 20f;

    static {
        FACING_ARROW = new Path2D.Double();
        FACING_ARROW.moveTo(0, -130);
        FACING_ARROW.lineTo(25, -130);
        FACING_ARROW.lineTo(0, -180);
        FACING_ARROW.lineTo(-25, -130);
        FACING_ARROW.closePath();

        STD_MEK = new Path2D.Double();
        STD_MEK.moveTo(-25, 45);
        STD_MEK.lineTo(25, 45);
        STD_MEK.lineTo(45, -45);
        STD_MEK.lineTo(-45, -45);
        STD_MEK.closePath();

        STD_TANK = new Path2D.Double();
        STD_TANK.moveTo(-25, -50);
        STD_TANK.lineTo(25, -50);
        STD_TANK.lineTo(25, 50);
        STD_TANK.lineTo(-25, 50);
        STD_TANK.closePath();

        STD_NAVAL = new Path2D.Double();
        STD_NAVAL.moveTo(-15, -50);
        STD_NAVAL.lineTo(15, -50);
        STD_NAVAL.curveTo(25, 0, 25, 0, 15, 50);
        STD_NAVAL.lineTo(-15, 50);
        STD_NAVAL.curveTo(-25, 0, -25, 0, -15, -50);
        STD_NAVAL.closePath();

        STD_VTOL = new Path2D.Double();
        int ofs = 10;
        STD_VTOL.moveTo(-50, 0);
        STD_VTOL.curveTo(-ofs, ofs, -ofs, ofs, 0, 50);
        STD_VTOL.curveTo(ofs, ofs, ofs, ofs, 50, 0);
        STD_VTOL.curveTo(ofs, -ofs, ofs, -ofs, 0, -50);
        STD_VTOL.curveTo(-ofs, -ofs, -ofs, -ofs, -50, 0);
        STD_VTOL.closePath();

        STD_AERO = new Path2D.Double();
        STD_AERO.moveTo(-30, 40);
        STD_AERO.lineTo(0, -50);
        STD_AERO.lineTo(30, 40);
        STD_AERO.lineTo(30, 50);
        STD_AERO.lineTo(-30, 50);
        STD_AERO.closePath();

        STD_SPHEROID = new Path2D.Double();
        double rad = 50;
        double r72 = toRadians(72);
        STD_SPHEROID.moveTo(rad * cos(PI_HALF), -rad * sin(PI_HALF));
        STD_SPHEROID.lineTo(rad * cos(PI_HALF + r72), -rad * sin(PI_HALF + r72));
        STD_SPHEROID.lineTo(rad * cos(PI_HALF + 2 * r72), -rad * sin(PI_HALF + 2 * r72));
        STD_SPHEROID.lineTo(rad * cos(PI_HALF + 3 * r72), -rad * sin(PI_HALF + 3 * r72));
        STD_SPHEROID.lineTo(rad * cos(PI_HALF + 4 * r72), -rad * sin(PI_HALF + 4 * r72));
        STD_SPHEROID.closePath();

        STD_INFANTRY = new Path2D.Double();
        STD_INFANTRY.moveTo(-50, 0);
        STD_INFANTRY.curveTo(0, 20, 0, 20, 50, 0);
        STD_INFANTRY.curveTo(0, -20, 0, -20, -50, 0);
        STD_INFANTRY.closePath();

        STD_DESTROYED = new Path2D.Double();
        STD_DESTROYED.moveTo(-70, 0);
        STD_DESTROYED.lineTo(70, 0);

        STD_MEKWARRIOR = new Path2D.Double();
        STD_MEKWARRIOR.moveTo(-30, 0);
        STD_MEKWARRIOR.curveTo(0, 15, 0, 15, 30, 0);
        STD_MEKWARRIOR.curveTo(0, -15, 0, -15, -30, 0);
        STD_MEKWARRIOR.closePath();

        // Base rectangle for all units (StratOps)
        STRAT_BASE_RECT = new Path2D.Double();
        STRAT_BASE_RECT.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 2, -STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_BASE_RECT.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 2, -STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_BASE_RECT.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 2, STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_BASE_RECT.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 2, STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_BASE_RECT.closePath();

        // Infantry Symbol
        STRAT_INFANTRY = new Path2D.Double();
        STRAT_INFANTRY.append(STRAT_BASE_RECT, false);
        STRAT_INFANTRY.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 2, -STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_INFANTRY.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 2, STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_INFANTRY.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 2, STRAT_SYMBOL_SIZE.getHeight() / 2);
        STRAT_INFANTRY.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 2, -STRAT_SYMBOL_SIZE.getHeight() / 2);

        STRAT_DESTROYED = new Path2D.Double();
        STRAT_DESTROYED.append(STRAT_BASE_RECT, false);
        STRAT_DESTROYED.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 2, 0);
        STRAT_DESTROYED.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 2, 0);


        STRAT_VTOL = new Path2D.Double();
        STRAT_VTOL.append(STRAT_BASE_RECT, false);
        STRAT_VTOL.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 4, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_VTOL.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 4, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_VTOL.lineTo(0, 0);
        STRAT_VTOL.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 4, -STRAT_SYMBOL_SIZE.getHeight() / 4);

        STRAT_VTOL.moveTo(STRAT_SYMBOL_SIZE.getWidth() / 4, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_VTOL.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 4, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_VTOL.lineTo(0, 0);
        STRAT_VTOL.closePath();

        STRAT_TANK_TRACKED = new Path2D.Double();
        STRAT_TANK_TRACKED.append(STRAT_BASE_RECT, false);
        double small = STRAT_SYMBOL_SIZE.getWidth() / 20;
        STRAT_TANK_TRACKED.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 3 + small, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_TANK_TRACKED.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3 - small, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_TANK_TRACKED.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3, -STRAT_SYMBOL_SIZE.getHeight() / 4 + small);
        STRAT_TANK_TRACKED.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3, STRAT_SYMBOL_SIZE.getHeight() / 4 - small);
        STRAT_TANK_TRACKED.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3 - small, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_TANK_TRACKED.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 3 + small, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_TANK_TRACKED.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 3, STRAT_SYMBOL_SIZE.getHeight() / 4 - small);
        STRAT_TANK_TRACKED.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 3, -STRAT_SYMBOL_SIZE.getHeight() / 4 + small);
        STRAT_TANK_TRACKED.closePath();

        STRAT_MEK = new Path2D.Double();
        STRAT_MEK.append(STRAT_BASE_RECT, false);

        STRAT_MEK.moveTo(-STRAT_CX - 1.5 * small, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_MEK.lineTo(-STRAT_CX - 3.0 * small, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_MEK.lineTo(-STRAT_CX + 3.0 * small, STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_MEK.lineTo(-STRAT_CX + 1.5 * small, -STRAT_SYMBOL_SIZE.getHeight() / 4);
        STRAT_MEK.closePath();

        STRAT_AERO = new Path2D.Double();
        STRAT_AERO.append(STRAT_BASE_RECT, false);
        rad = STRAT_SYMBOL_SIZE.getWidth() / 5;

        STRAT_AERO.moveTo(-STRAT_CX + rad / 3 * cos(PI_HALF + 2 * r72), rad / 3 * sin(PI_HALF + 2 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad * cos(PI_HALF + 1 * r72), -rad * sin(PI_HALF + 1 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad / 3 * cos(PI_HALF + 1 * r72), rad / 3 * sin(PI_HALF + 1 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad * cos(PI_HALF + 2 * r72), -rad * sin(PI_HALF + 2 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad / 3 * cos(PI_HALF), rad / 3 * sin(PI_HALF));
        STRAT_AERO.lineTo(-STRAT_CX + rad * cos(PI_HALF + 3 * r72), -rad * sin(PI_HALF + 3 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad / 3 * cos(PI_HALF + 4 * r72), rad / 3 * sin(PI_HALF + 4 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad * cos(PI_HALF + 4 * r72), -rad * sin(PI_HALF + 4 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad / 3 * cos(PI_HALF + 3 * r72), rad / 3 * sin(PI_HALF + 3 * r72));
        STRAT_AERO.lineTo(-STRAT_CX + rad * cos(PI_HALF), -rad * sin(PI_HALF));
        STRAT_AERO.closePath();

        STRAT_SPHEROID = new Path2D.Double();
        STRAT_SPHEROID.append(STRAT_BASE_RECT, false);
        STRAT_SPHEROID.moveTo(rad * cos(PI_HALF), -rad * sin(PI_HALF));
        STRAT_SPHEROID.lineTo(rad * cos(PI_HALF + r72), -rad * sin(PI_HALF + r72));
        STRAT_SPHEROID.lineTo(rad * cos(PI_HALF + 2 * r72), -rad * sin(PI_HALF + 2 * r72));
        STRAT_SPHEROID.lineTo(rad * cos(PI_HALF + 3 * r72), -rad * sin(PI_HALF + 3 * r72));
        STRAT_SPHEROID.lineTo(rad * cos(PI_HALF + 4 * r72), -rad * sin(PI_HALF + 4 * r72));
        STRAT_SPHEROID.closePath();

        STRAT_HOVER = new Path2D.Double();
        STRAT_HOVER.append(STRAT_BASE_RECT, false);
        STRAT_HOVER.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 3, small);
        STRAT_HOVER.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 3, -small);
        STRAT_HOVER.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3, -small);
        STRAT_HOVER.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 3, small);

        STRAT_HOVER.moveTo(-STRAT_SYMBOL_SIZE.getWidth() / 6, -small);
        STRAT_HOVER.lineTo(-STRAT_SYMBOL_SIZE.getWidth() / 6, +small);
        STRAT_HOVER.moveTo(0, -small);
        STRAT_HOVER.lineTo(0, +small);
        STRAT_HOVER.moveTo(STRAT_SYMBOL_SIZE.getWidth() / 6, -small);
        STRAT_HOVER.lineTo(STRAT_SYMBOL_SIZE.getWidth() / 6, +small);

        STRAT_WHEELED = new Path2D.Double();
        STRAT_WHEELED.append(STRAT_BASE_RECT, false);
        double smaller = STRAT_SYMBOL_SIZE.getWidth() / 17;
        STRAT_WHEELED.moveTo(-STRAT_CX - smaller * 2, -smaller);
        STRAT_WHEELED.lineTo(STRAT_CX + smaller * 2, -smaller);
        STRAT_WHEELED.moveTo(-STRAT_CX, -smaller);
        STRAT_WHEELED.lineTo(-STRAT_CX - smaller, 0);
        STRAT_WHEELED.lineTo(-STRAT_CX, +smaller);
        STRAT_WHEELED.lineTo(-STRAT_CX + smaller, 0);
        STRAT_WHEELED.closePath();
        STRAT_WHEELED.moveTo(STRAT_CX, -smaller);
        STRAT_WHEELED.lineTo(STRAT_CX - smaller, 0);
        STRAT_WHEELED.lineTo(STRAT_CX, +smaller);
        STRAT_WHEELED.lineTo(STRAT_CX + smaller, 0);
        STRAT_WHEELED.closePath();
        STRAT_WHEELED.moveTo(0, -smaller);
        STRAT_WHEELED.lineTo(-smaller, 0);
        STRAT_WHEELED.lineTo(0, +smaller);
        STRAT_WHEELED.lineTo(smaller, 0);
        STRAT_WHEELED.closePath();

        STRAT_NAVAL = new Path2D.Double();
        STRAT_NAVAL.append(STRAT_BASE_RECT, false);
        STRAT_NAVAL.moveTo(0, -STRAT_SYMBOL_SIZE.getHeight() / 3);
        STRAT_NAVAL.lineTo(0, STRAT_SYMBOL_SIZE.getHeight() / 3);
        STRAT_NAVAL.moveTo(-STRAT_CX / 2, -STRAT_SYMBOL_SIZE.getHeight() / 5);
        STRAT_NAVAL.lineTo(STRAT_CX / 2, -STRAT_SYMBOL_SIZE.getHeight() / 5);

        STRAT_NAVAL.moveTo(rad, 0);
        STRAT_NAVAL.curveTo(
              rad * 0.8, STRAT_SYMBOL_SIZE.getHeight() / 3 * 0.8,
              rad * 0.8, STRAT_SYMBOL_SIZE.getHeight() / 3 * 0.8,
              0, STRAT_SYMBOL_SIZE.getHeight() / 3);
        STRAT_NAVAL.curveTo(
              -rad * 0.8, STRAT_SYMBOL_SIZE.getHeight() / 3 * 0.8,
              -rad * 0.8, STRAT_SYMBOL_SIZE.getHeight() / 3 * 0.8,
              -rad, 0);
    }

    /** Returns the Path2D minimap symbol shape for the given entity. */
    public static Path2D getForm(Entity entity) {
        boolean stratOps = GUIP.getMmSymbol();
        if ((entity instanceof Mek) || (entity instanceof ProtoMek)) {
            return stratOps ? STRAT_MEK : STD_MEK;
        } else if (entity instanceof VTOL) {
            return stratOps ? STRAT_VTOL : STD_VTOL;
        } else if (entity instanceof MekWarrior) {
            return stratOps ? STRAT_INFANTRY : STD_MEKWARRIOR;
        } else if (entity instanceof Tank) {
            if (entity.getMovementMode() == EntityMovementMode.HOVER) {
                return stratOps ? STRAT_HOVER : STD_TANK;
            } else if (entity.getMovementMode() == EntityMovementMode.WHEELED) {
                return stratOps ? STRAT_WHEELED : STD_TANK;
            } else if ((entity.getMovementMode() == EntityMovementMode.HYDROFOIL) ||
                  (entity.getMovementMode() == EntityMovementMode.NAVAL)) {
                return stratOps ? STRAT_NAVAL : STD_NAVAL;
            } else {
                return stratOps ? STRAT_TANK_TRACKED : STD_TANK;
            }
        } else if (entity.isAero()) {
            if (entity.isFighter()) {
                return stratOps ? STRAT_AERO : STD_AERO;
            } else {
                return stratOps ? STRAT_SPHEROID : STD_SPHEROID;
            }
        } else {
            return stratOps ? STRAT_INFANTRY : STD_INFANTRY;
        }
    }

    /**
     * What a unit's symbol shows, taken from the unit once: its {@link #getForm form} and whether that is a StratOps
     * symbol (the preference when it was taken), the weight letter of a StratOps Mek, ProtoMek or aerospace unit ("P",
     * "I", "L" to "A"; empty for none), whether its form is filled with a thin brush, whether it is a MekWarrior (a dot
     * on a StratOps symbol), and whether it shows a facing (any unit but infantry).
     */
    public record Symbol(Path2D form, boolean strat, String letter, boolean thin, boolean pilot, boolean facing) { }

    /** The symbol of {@code entity}. */
    public static Symbol symbol(Entity entity) {
        String letter = "";
        if (entity instanceof ProtoMek) {
            letter = "P";
        } else if ((entity instanceof Mek mek) && mek.isIndustrial()) {
            letter = "I";
        } else if (((entity instanceof Mek) || (entity instanceof Aero)) && (entity.getWeightClass() < 6)) {
            letter = STRAT_WEIGHTS[entity.getWeightClass()];
        }
        boolean thin = (entity instanceof Mek) || (entity instanceof ProtoMek) || (entity instanceof VTOL)
              || entity.isAero();
        return new Symbol(getForm(entity), GUIP.getMmSymbol(), letter, thin, entity instanceof MekWarrior,
              !entity.isInfantry());
    }

    /** The colour of a symbol's outlines and letters: black, faded for a destroyed unit. */
    public static Color ink(boolean destroyed) {
        return destroyed ? faded(Color.BLACK) : Color.BLACK;
    }

    /** A destroyed unit's form of {@code colour}: brighter and mostly transparent. */
    private static Color faded(Color colour) {
        Color brighter = colour.brighter().brighter();
        return new Color(brighter.getRed(), brighter.getGreen(), brighter.getBlue(), DESTROYED_ALPHA);
    }

    /** Moves {@code g} to the place of the {@code stacked}th unit (1 for the first) in a hex several units share. */
    public static void stack(Graphics2D g, int stacked) {
        g.translate(STACK_SHIFT * (stacked - 1), -STACK_SHIFT * (stacked - 1));
    }

    /**
     * Draws {@code symbol} at the origin of {@code g}, in the symbols' own units (a standard symbol is 100 wide): its
     * form filled in {@code colour}, the unit's owner's or team's, set off by a black border, with an inner border
     * that is black when the unit has {@code moved} and white when not; a {@code destroyed} unit's symbol is faded
     * and struck through.
     */
    public static void paint(Graphics2D g, Symbol symbol, Color colour, boolean moved, boolean destroyed) {
        Color fill = destroyed ? faded(colour) : colour;
        Color border = moved && !destroyed ? Color.BLACK : Color.WHITE;
        if (destroyed) {
            border = faded(border);
        }
        Color ink = ink(destroyed);
        Path2D form = symbol.form();
        if (symbol.strat()) {
            // A black ground to set off the icon from the background
            g.setStroke(new BasicStroke(OUTER_BORDER, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_BEVEL));
            g.setColor(ink);
            g.draw(STRAT_BASE_RECT);
            g.fill(STRAT_BASE_RECT);
            // A thin brush for filled areas, a thick one for line symbols
            g.setStroke(symbol.thin() ? new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
                  : new BasicStroke(FORM_STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            g.setColor(fill);
            g.fill(form);
            g.setColor(ink);
            if (!symbol.letter().isEmpty()) {
                FontRenderContext context = new FontRenderContext(null, true, true);
                Font font = new Font(MMConstants.FONT_SANS_SERIF, destroyed ? Font.PLAIN : Font.BOLD, 100);
                double width = font.getStringBounds(symbol.letter(), context).getWidth();
                g.fill(font.createGlyphVector(context, symbol.letter()).getOutline(
                      (float) (STRAT_CX - width / 2), (float) STRAT_SYMBOL_SIZE.getHeight() / 3.0f));
            } else if (symbol.pilot()) {
                g.fillOval(-25, -25, 50, 50);
            }
            g.draw(form);
            g.setColor(border);
            g.setStroke(new BasicStroke(INNER_BORDER, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            g.draw(STRAT_BASE_RECT);
        } else {
            // A black border to set off the icon from the background
            g.setStroke(new BasicStroke(OUTER_BORDER, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_ROUND));
            g.setColor(ink);
            g.draw(form);
            g.setColor(fill);
            g.fill(form);
            g.setColor(border);
            g.setStroke(new BasicStroke(INNER_BORDER / 2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.draw(form);
            if (destroyed) {
                g.draw(STRAT_DESTROYED);
                g.fill(STRAT_DESTROYED);
            }
        }
        if (destroyed) {
            g.setColor(ink);
            g.setStroke(new BasicStroke(FORM_STROKE, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
            g.draw(symbol.strat() ? STRAT_DESTROYED : STD_DESTROYED);
        }
    }

    /** Draws the arrow of a unit that shows its {@code facing} (0 to 5; none below 0), filled in {@code colour}. */
    public static void paintFacing(Graphics2D g, Symbol symbol, Color colour, int facing) {
        if (!symbol.facing() || facing < 0) {
            return;
        }
        AffineTransform transform = g.getTransform();
        g.setStroke(new BasicStroke(INNER_BORDER, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
        g.rotate(Math.toRadians(facing * 60));
        g.setColor(Color.BLACK);
        g.draw(FACING_ARROW);
        g.setColor(colour);
        g.fill(FACING_ARROW);
        g.setTransform(transform);
    }
}
