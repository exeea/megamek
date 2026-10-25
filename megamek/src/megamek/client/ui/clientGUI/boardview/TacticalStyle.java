/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview;

import java.awt.BasicStroke;
import java.awt.Color;

import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.RangeType;

/** The look both boards give the tactical marks, in hex units: bands, lines, colours and the border layout. */
public final class TacticalStyle {
    private static final GUIPreferences GUIP = GUIPreferences.getInstance();

    /** Band width and opacity of a weapon, sensor or visual range. */
    public static final int RANGE_BAND_WIDTH = 10;
    public static final int RANGE_BAND_OPACITY = 120;

    /**
     * Band width of an objective control zone: half a range band. A range band is a broad wash the eye reads past; a
     * control zone is a boundary, and at full width it swallowed the hex edge it marks.
     */
    public static final int CONTROL_ZONE_BAND_WIDTH = RANGE_BAND_WIDTH / 2;

    /**
     * Objective control zones are drawn solid, where a range band is translucent: the default player palette is pale,
     * and blended over terrain it reads as grey, so the holder could not be told from nobody. The band is only the
     * hex edge, so painting it solid hides almost no terrain.
     */
    public static final int CONTROL_ZONE_OPACITY = 255;

    /** The thin dashed line along a range or zone boundary. */
    public static final float ZONE_LINE_THICKNESS = 1.4f;
    public static final Color ZONE_LINE_COLOR = Color.WHITE;
    public static final BasicStroke ZONE_LINE_STROKE = new BasicStroke(ZONE_LINE_THICKNESS, BasicStroke.CAP_BUTT,
          BasicStroke.JOIN_MITER, 10f, new float[] { 2f, 2f }, 0f);

    /** The movement envelope's band, and the dashed line along its outside. */
    public static final int ENVELOPE_BAND_WIDTH = 10;
    public static final int ENVELOPE_BAND_OPACITY = 60;
    public static final float ENVELOPE_LINE_THICKNESS = 2;
    public static final BasicStroke ENVELOPE_LINE_STROKE = new BasicStroke(ENVELOPE_LINE_THICKNESS,
          BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 5f, 3f }, 0f);

    /** The defensive facing modifier's band inside its side, and its label. */
    public static final double MODIFIER_BAND_WIDTH = 15;
    public static final double MODIFIER_INSET = 1;
    public static final float MODIFIER_FONT_SIZE = 9;
    public static final Color MODIFIER_FONT_COLOR = Color.BLACK;

    /** Route dots: a small one on each hex between waypoints, a larger one on a turning point the bot planned. */
    public static final double ROUTE_TRAIL_DIAMETER = 7;
    public static final double ROUTE_TURN_DIAMETER = 15;
    public static final Color ROUTE_OUTLINE_COLOR = new Color(40, 40, 40, 220);
    public static final BasicStroke ROUTE_OUTLINE_STROKE = new BasicStroke(1.5f);

    /** Half the width of a C3 link's and a fly-over route's band. */
    public static final int C3_LINE_WIDTH = 1;
    public static final int FLY_OVER_LINE_WIDTH = 3;

    /** The sensor range brackets of {@link TacticalMark.SensorRange}. */
    public static final int SENSORS = 0;
    public static final int SENSORS_AIR = 1;
    public static final int VISUAL = 2;
    public static final int VISUAL_DARK = 3;
    /** The reach of the unit's active probe, in the sensor range colour. */
    public static final int ACTIVE_PROBE = 4;

    /** A hex a crane may unload into: a translucent fill and a stronger line around it, in the move colour. */
    public static final int CRANE_TARGET_FILL_OPACITY = 90;
    public static final int CRANE_TARGET_OUTLINE_OPACITY = 230;
    public static final float CRANE_TARGET_OUTLINE_WIDTH = 2.5f;

    /**
     * A bridge target's hazard tape: black under caution-coloured dashes, high-contrast on any terrain and for
     * colour-blind players, who flagged a plain ghost bridge as hard to see.
     */
    public static final float HAZARD_OUTLINE_WIDTH = 4;
    public static final float BRIDGE_HAZARD_DASH = 8;
    public static final Color HAZARD_BACKING_COLOR = Color.BLACK;

    /** The symbol font's warning sign, for a building that may collapse. */
    public static final String WARNING_SIGN = "";

    // A zone's outside sides (the index, bits by direction) drawn as one run across the hex where two to five
    // adjacent sides are outside: the run's layout and the direction it is turned to.
    private static final int[] BORDER_DIRECTION = {
          0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 2, 2, 1, 0, 0, 0, 0, 0,
          0, 0, 1, 0, 3, 3, 3, 0, 2, 2, 1, 0, 2, 5, 4, 5, 6, 5, 1, 5,
          0, 5, 2, 5, 2, 2, 1, 5, 4, 4, 4, 4, 4, 4, 1, 4, 3, 3, 3, 3, 2, 2, 1, 0
    };
    private static final int[] BORDER_LAYOUT = {
          0, 0, 0, 1, 0, 0, 1, 2, 0, 0, 0, 6, 1, 7, 2, 3, 0, 0, 0, 7,
          0, 1, 6, 5, 1, 6, 7, 4, 2, 5, 3, 8, 0, 1, 4, 2, 6, 6, 7, 3,
          0, 7, 2, 5, 6, 4, 5, 8, 1, 2, 6, 3, 7, 5, 4, 8, 2, 3, 5, 8, 3, 8, 8, 0
    };

    private TacticalStyle() { }

    /**
     * The border layout for the outside sides {@code borders}: 0 draws a band along each side; 1 two, 2 three, 3 four
     * and 8 five adjacent sides as one run across the hex; 4 twice two (the second turned three sixths), 5 three and a
     * lone side four sixths on, 6 two and a lone side three sixths on, 7 two and a lone side four sixths on; each
     * turned {@link #borderLayoutDirection} sixths.
     */
    public static int borderLayout(int borders) {
        return BORDER_LAYOUT[borders];
    }

    public static int borderLayoutDirection(int borders) {
        return BORDER_DIRECTION[borders];
    }

    /** A weapon range bracket's colour: minimum, short, medium, long or extreme. */
    public static Color rangeColor(int rangeBracket) {
        return switch (rangeBracket) {
            case RangeType.RANGE_MINIMUM -> GUIP.getFieldOfFireMinColor();
            case RangeType.RANGE_SHORT -> GUIP.getFieldOfFireShortColor();
            case RangeType.RANGE_MEDIUM -> GUIP.getFieldOfFireMediumColor();
            case RangeType.RANGE_LONG -> GUIP.getFieldOfFireLongColor();
            case RangeType.RANGE_EXTREME -> GUIP.getFieldOfFireExtremeColor();
            default -> new Color(0, 0, 0);
        };
    }

    /** A weapon range bracket's letter. */
    public static String rangeText(int rangeBracket) {
        return switch (rangeBracket) {
            case RangeType.RANGE_MINIMUM -> "min";
            case RangeType.RANGE_SHORT -> "S";
            case RangeType.RANGE_MEDIUM -> "M";
            case RangeType.RANGE_LONG -> "L";
            case RangeType.RANGE_EXTREME -> "E";
            default -> "";
        };
    }

    /** A sensor range bracket's colour. */
    public static Color sensorColor(int bracket) {
        return switch (bracket) {
            case SENSORS, SENSORS_AIR, ACTIVE_PROBE -> GUIP.getSensorRangeColor();
            case VISUAL, VISUAL_DARK -> GUIP.getVisualRangeColor();
            default -> new Color(0, 0, 0);
        };
    }

    /** {@code color} at {@code alpha} (0 to 255). */
    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
