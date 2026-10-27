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
package megamek.client.ui.clientGUI;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Locale;

import jakarta.annotation.Nullable;
import megamek.Version;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;
import megamek.logging.MMLogger;

/**
 * The start screen's build-specific art: the tag sprayed across the MegaMek logo and the build note's colour and
 * size, all read from {@code Version.properties}. Most builds carry neither.
 */
public final class SplashArt {
    private static final MMLogger LOGGER = MMLogger.create(SplashArt.class);

    /** Family name of the spray-paint font the splash tag is drawn in. Ships in mm-data under data/fonts. */
    private static final String SPRAY_PAINT_FONT_FAMILY = "Rubik Spray Paint";

    /** Colour the splash tag is sprayed in when it does not name one: a strong red. */
    private static final Color SPLASH_TAG_DEFAULT_COLOR = new Color(198, 26, 26);

    /** How solid the sprayed tag is, out of 255. Short of opaque, so the logo shows through the paint. */
    private static final int SPLASH_TAG_ALPHA = 232;

    /** Colour outlining the sprayed tag, which lifts the red clear of the light wordmark underneath it. */
    private static final Color SPLASH_TAG_OUTLINE_COLOR = new Color(0, 0, 0, 225);

    /** Thickness of that outline, as a fraction of the tag's font size, so it stays in proportion at every size. */
    private static final double SPLASH_TAG_OUTLINE_WIDTH_FRACTION = 0.085;

    /** Fraction of the logo's width the sprayed tag spans, leaving it a little narrower than the logo itself. */
    private static final double SPLASH_TAG_WIDTH_FRACTION_OF_LOGO = 0.92;

    /**
     * How far down the logo image the tag is centred, as a fraction of the image's height. The logo is a 'Mek standing
     * above the wordmark plate, which fills about the bottom quarter; the tag sits low on that plate, overlapping the
     * bottom of the lettering, where a tag sprayed across a sign tends to land.
     */
    private static final double LOGO_WORDMARK_CENTRE_FRACTION = 0.93;

    /** The tallest the tag's lettering may be, as a fraction of the logo's height, so it stays on the plate. */
    private static final double SPLASH_TAG_MAX_HEIGHT_FRACTION_OF_LOGO = 0.17;

    /** Size the tag is measured at before being scaled to fit; large enough that the measurement is accurate. */
    private static final float SPLASH_TAG_REFERENCE_FONT_SIZE = 100f;

    private SplashArt() {
    }

    /**
     * The logo with this build's tag sprayed across its wordmark, or the logo itself when the build has no tag. The
     * tag is red outlined in black, tilted slightly and left a little see-through, so it reads as paint on the picture
     * rather than as a caption.
     */
    public static BufferedImage logoWithTag(BufferedImage logo) {
        Font tagFont = loadSplashTagFont();
        if (tagFont == null) {
            return logo;
        }
        BufferedImage tagged = new BufferedImage(logo.getWidth(), logo.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = tagged.createGraphics();
        try {
            graphics.drawImage(logo, 0, 0, null);
            sprayTag(graphics, tagFont, new Rectangle(0, 0, logo.getWidth(), logo.getHeight()));
        } finally {
            graphics.dispose();
        }
        return tagged;
    }

    /**
     * The colour to draw the build note in: the plain colour name {@code Version.properties} gives, or the warning
     * colour when it names none or one that is not recognised.
     */
    public static Color buildNotesColor() {
        Color warningColor = GUIPreferences.getInstance().getWarningColor();
        String configuredColorName = Version.getBuildNotesColorName();
        if (configuredColorName == null) {
            return warningColor;
        }
        Color namedColor = colorForName(configuredColorName);
        if (namedColor == null) {
            LOGGER.warn("[BuildNotes] Version.properties sets notesColor to '{}', which is not a colour name "
                  + "MegaMek knows. The build note will use the warning colour instead.", configuredColorName);
            return warningColor;
        }
        return namedColor;
    }

    /** The build note's point size from {@code Version.properties}, unscaled, or {@code fallback} when it sets none. */
    public static float buildNotesFontSize(float fallback) {
        return Version.hasBuildNotesFontSize() ? Version.getBuildNotesFontSize() : fallback;
    }

    private static void sprayTag(Graphics2D graphics, Font splashTagFont, Rectangle logoBounds) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font sized = deriveSplashTagFont(graphics, splashTagFont, logoBounds);
        if (sized == null) {
            return;
        }
        String splashTag = Version.getSplashTag();
        // The painted lettering is measured, so centring uses where the paint lands, not the font's full extent
        GlyphVector tagGlyphs = sized.createGlyphVector(graphics.getFontRenderContext(), splashTag);
        Rectangle2D tagInk = tagGlyphs.getVisualBounds();
        int tagX = logoBounds.x + (int) ((logoBounds.width - tagInk.getWidth()) / 2 - tagInk.getX());
        int wordmarkCentreY = logoBounds.y + (int) (logoBounds.height * LOGO_WORDMARK_CENTRE_FRACTION);
        int tagY = wordmarkCentreY - (int) (tagInk.getY() + (tagInk.getHeight() / 2));
        graphics.rotate(Math.toRadians(Version.getSplashTagRotation()), tagX + tagInk.getCenterX(), wordmarkCentreY);
        Shape tagOutline = tagGlyphs.getOutline(tagX, tagY);
        float outlineWidth = (float) Math.max(1.0, sized.getSize2D() * SPLASH_TAG_OUTLINE_WIDTH_FRACTION);
        graphics.setStroke(new BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(SPLASH_TAG_OUTLINE_COLOR);
        graphics.draw(tagOutline);
        graphics.setColor(resolveSplashTagColor());
        graphics.fill(tagOutline);
    }

    /**
     * The tag's font at the size that spans the logo, pulled back if that would make it taller than the wordmark
     * plate, as a very short tag would be. Measured once at a reference size and scaled, rather than searched.
     */
    private static @Nullable Font deriveSplashTagFont(Graphics2D graphics, Font splashTagFont, Rectangle logoBounds) {
        Font referenceFont = splashTagFont.deriveFont(SPLASH_TAG_REFERENCE_FONT_SIZE);
        Rectangle2D referenceInk = referenceFont.createGlyphVector(graphics.getFontRenderContext(),
              Version.getSplashTag()).getVisualBounds();
        if ((referenceInk.getWidth() <= 0) || (referenceInk.getHeight() <= 0)) {
            return null;
        }
        double sizeThatFitsWidth = SPLASH_TAG_REFERENCE_FONT_SIZE * logoBounds.width
              * SPLASH_TAG_WIDTH_FRACTION_OF_LOGO / referenceInk.getWidth();
        double sizeThatFitsHeight = SPLASH_TAG_REFERENCE_FONT_SIZE * logoBounds.height
              * SPLASH_TAG_MAX_HEIGHT_FRACTION_OF_LOGO / referenceInk.getHeight();
        float size = (float) Math.min(sizeThatFitsWidth, sizeThatFitsHeight);
        return size < 1f ? null : splashTagFont.deriveFont(size);
    }

    private static Color resolveSplashTagColor() {
        String configuredColorName = Version.getSplashTagColorName();
        Color namedColor = configuredColorName == null ? null : colorForName(configuredColorName);
        if (configuredColorName != null && namedColor == null) {
            LOGGER.warn("[SplashTag] Version.properties sets splashTagColor to '{}', which is not a colour name "
                  + "MegaMek knows. The tag will be sprayed in red instead.", configuredColorName);
        }
        return UIUtil.addAlpha(namedColor == null ? SPLASH_TAG_DEFAULT_COLOR : namedColor, SPLASH_TAG_ALPHA);
    }

    /** The spray-paint font, a plain fallback when it is not installed, or {@code null} when there is no tag. */
    private static @Nullable Font loadSplashTagFont() {
        if (!Version.hasSplashTag()) {
            return null;
        }
        if (!FontHandler.getAvailableFonts().contains(SPRAY_PAINT_FONT_FAMILY)) {
            LOGGER.warn("[SplashTag] The '{}' font is not installed, so '{}' is painted in the default font instead. "
                  + "Check that mm-data has been staged.", SPRAY_PAINT_FONT_FAMILY, Version.getSplashTag());
            return new Font(Font.SANS_SERIF, Font.BOLD, 12);
        }
        return new Font(SPRAY_PAINT_FONT_FAMILY, Font.PLAIN, 12);
    }

    /** A colour name from {@code Version.properties}, in any case, or {@code null} when it is not recognised. */
    static @Nullable Color colorForName(String colorName) {
        return switch (colorName.toLowerCase(Locale.ROOT)) {
            case "red" -> Color.RED;
            case "orange" -> Color.ORANGE;
            case "yellow" -> Color.YELLOW;
            case "green" -> Color.GREEN;
            case "blue" -> Color.BLUE;
            case "cyan" -> Color.CYAN;
            case "magenta" -> Color.MAGENTA;
            case "pink" -> Color.PINK;
            case "white" -> Color.WHITE;
            case "black" -> Color.BLACK;
            case "gray", "grey" -> Color.GRAY;
            case "lightgray", "lightgrey" -> Color.LIGHT_GRAY;
            case "darkgray", "darkgrey" -> Color.DARK_GRAY;
            default -> null;
        };
    }
}
