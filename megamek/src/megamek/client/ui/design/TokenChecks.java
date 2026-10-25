/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design;

import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.design.DesignTokens.ContrastCheck;
import megamek.client.ui.design.DesignTokens.DistinctCheck;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.DesignTokens.Palette;

/**
 * Runs the accessibility checks that the token file declares, for both modes of one palette: WCAG contrast of
 * foreground and background roles, and the CIEDE2000 separation of color roles under the palette's simulated
 * {@link Vision} types ({@link #visions}). The unit test fails on any failing result in either palette; the Swing
 * gallery lists them all for the active palette.
 */
public final class TokenChecks {
    private TokenChecks() {
    }

    /** One measured pair. {@code vision} is the worst vision type of a distinctness result, else null. */
    public record Result(Mode mode, String subject, double value, double min, Vision vision, String why) {
        public boolean passes() {
            return value >= min;
        }
    }

    /** Every result of every check, in both modes. */
    public static List<Result> run(DesignTokens tokens) {
        List<Result> results = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            for (ContrastCheck check : tokens.contrastChecks()) {
                for (String foreground : check.foregrounds()) {
                    for (String background : check.backgrounds()) {
                        results.add(contrast(tokens, mode, check, foreground, background));
                    }
                }
            }
            for (DistinctCheck check : tokens.distinctChecks()) {
                List<String> names = check.colors();
                for (int first = 0; first < names.size(); first++) {
                    for (int second = first + 1; second < names.size(); second++) {
                        results.add(distinct(tokens, mode, check, names.get(first), names.get(second)));
                    }
                }
            }
        }
        return results;
    }

    private static Result contrast(DesignTokens tokens, Mode mode, ContrastCheck check, String foreground,
          String background) {
        int backdrop = check.over() == null ? 0xFF000000 : tokens.color(check.over(), mode);
        int back = opaque(tokens.color(background, mode), backdrop);
        int front = opaque(tokens.color(foreground, mode), back);
        String subject = foreground + " on " + background + (check.over() == null ? "" : " over " + check.over());
        return new Result(mode, subject, ColorMath.contrast(front, back), check.min(), null, check.why());
    }

    private static Result distinct(DesignTokens tokens, Mode mode, DistinctCheck check, String first, String second) {
        int a = tokens.color(first, mode);
        int b = tokens.color(second, mode);
        double worst = Double.MAX_VALUE;
        Vision worstVision = Vision.NORMAL;
        for (Vision vision : visions(tokens.tokenPalette())) {
            double difference = ColorMath.deltaE(a, b, vision);
            if (difference < worst) {
                worst = difference;
                worstVision = vision;
            }
        }
        return new Result(mode, first + " vs " + second, worst, check.minDeltaE(), worstVision, check.why());
    }

    /** The vision types a palette is measured for: normal vision for the standard one, every type for color-blind. */
    public static List<Vision> visions(Palette palette) {
        return palette == Palette.STANDARD ? List.of(Vision.NORMAL) : List.of(Vision.values());
    }

    private static int opaque(int color, int backdrop) {
        return ColorMath.alpha(color) == 0xFF ? color : ColorMath.over(color, backdrop);
    }
}
