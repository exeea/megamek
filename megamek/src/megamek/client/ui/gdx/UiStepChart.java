/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.WidgetGroup;
import com.badlogic.gdx.utils.Align;
import megamek.client.ui.design.DesignTokens;

/**
 * A step chart in the Token v0 look (docs/design-system/tokens.md, Charts): values by whole step, such as damage by
 * range. A series is a line that holds its value across its step and turns square to the next ({@link Style#LINE}),
 * the same line over a fill in its colour at {@code tint.pressed} ({@link Style#AREA}), or a dashed ceiling
 * ({@link Style#CEILING}). Under them three guide lines in border.subtle at nothing, half and the ceiling, labelled in
 * caption type and text.muted. The ceiling rounds up to 1, 2 or 5 times a power of ten over the highest value. Series
 * draw in the order given, so the one that matters most comes last and draws on top.
 * <p>
 * With {@link #bands} the steps fall into named bands, such as the short, medium and long ranges: a divider at each
 * band's start, every other band washed in border.subtle at {@code tint.hover}, the band's name in overline over it and
 * the step numbers of the band starts and the last step under the plot. Without bands the first, middle and last step
 * are numbered. {@link #sweep} draws the series in from the left, on the token easing over {@code motion.slow}, behind
 * an accent scan line.
 */
public final class UiStepChart extends WidgetGroup {
    private static final int GUIDES = 3;

    /** How a series draws. */
    public enum Style {
        /** A square-stepped line. */
        LINE,
        /** The line over a fill down to nothing, in its colour at {@code tint.pressed}. */
        AREA,
        /** A dashed line across each step, without the turns: a ceiling the other series stay under. */
        CEILING
    }

    /** One series: the values of steps 1 to n, its colour, its thickness and how it draws. */
    public record Series(float[] values, Color color, float thickness, Style style) {
        /** A plain line. */
        public Series(float[] values, Color color, float thickness) {
            this(values, color, thickness, Style.LINE);
        }
    }

    private final UiKit kit;
    private final Texture white;
    private final Color guide = new Color(UiTheme.tokenColor("border.subtle"));
    private final Color scan = new Color(UiTheme.tokenColor("accent.primary"));
    private final Label[] valueLabels = new Label[GUIDES];
    private final List<Label> stepLabels = new ArrayList<>();
    private final List<Label> bandLabels = new ArrayList<>();
    private final float gap = space("xs");
    private final float fillAlpha;
    private final float washAlpha;
    private final float duration;
    private final float[] easing;
    private List<Series> series = List.of();
    private int[] bandStarts = new int[0];
    private int[] labelled = new int[0];
    private int steps;
    private float ceiling = 1;
    private float plotX;
    private float plotY;
    private float plotWidth;
    private float plotHeight;
    private boolean sweeping;
    private float elapsed;

    public UiStepChart(UiKit kit) {
        this.kit = kit;
        DesignTokens tokens = DesignTokens.shipped();
        white = kit.skin.get("white", Texture.class);
        fillAlpha = tokens.opacity().get("tint.pressed");
        washAlpha = tokens.opacity().get("tint.hover");
        duration = tokens.duration("slow") / 1000f;
        easing = tokens.easing();
        setTouchable(Touchable.disabled);
        for (int i = 0; i < GUIDES; i++) {
            valueLabels[i] = kit.typeLabel("", "caption", "text.muted");
            valueLabels[i].setAlignment(Align.right);
            addActor(valueLabels[i]);
        }
    }

    private static float space(String name) {
        return DesignTokens.shipped().space().get(name);
    }

    /** Shows {@code next}, each series' values for steps 1 to n, the guides labelled by {@code format}. */
    public void show(List<Series> next, DoubleFunction<String> format) {
        series = new ArrayList<>(next);
        steps = 0;
        float peak = 0;
        for (Series each : series) {
            steps = Math.max(steps, each.values().length);
            for (float value : each.values()) {
                peak = Math.max(peak, value);
            }
        }
        ceiling = ceiling(peak);
        for (int i = 0; i < GUIDES; i++) {
            valueLabels[i].setText(format.apply(ceiling * i / (GUIDES - 1)));
        }
        refreshSteps();
    }

    /**
     * Splits the steps into bands that start at {@code starts} (ascending, the first normally 1), named {@code names};
     * a band that starts past the last step does not show.
     */
    public void bands(int[] starts, String[] names) {
        bandStarts = starts.clone();
        bandLabels.forEach(Label::remove);
        bandLabels.clear();
        for (String name : names) {
            Label label = kit.roleTypeLabel("overline", name, "text.secondary");
            label.setAlignment(Align.center);
            bandLabels.add(label);
            addActor(label);
        }
        refreshSteps();
    }

    /** Draws the series in from the left once, from the next frame, or at once without {@code motion}. */
    public void sweep(boolean motion) {
        sweep(motion, 0);
    }

    /** Draws the series in from the left once, {@code delay} seconds on, or at once without {@code motion}. */
    public void sweep(boolean motion, float delay) {
        sweeping = motion;
        elapsed = -delay;
    }

    /** How far the series have drawn in, 0 to 1 on the token easing. */
    public float progress() {
        return sweeping ? UiReveal.ease(Math.max(0, Math.min(1, elapsed / duration)), easing) : 1;
    }

    /**
     * The steps numbered under the plot: without bands the first, middle and last of {@code steps}; with them each
     * band's start within the steps and the last step. None for no steps.
     */
    public static int[] labelledSteps(int steps, int[] starts) {
        if (steps <= 0) {
            return new int[0];
        }
        List<Integer> shown = new ArrayList<>();
        if (starts.length == 0) {
            shown.add(1);
            shown.add(Math.max(1, (steps + 1) / 2));
        } else {
            for (int start : starts) {
                if (start >= 1 && start <= steps) {
                    shown.add(start);
                }
            }
        }
        shown.add(steps);
        return shown.stream().distinct().sorted().mapToInt(Integer::intValue).toArray();
    }

    /** The guides' top: 1, 2 or 5 times a power of ten, at or over {@code peak}; 1 for nothing. */
    public static float ceiling(float peak) {
        if (peak <= 0) {
            return 1;
        }
        double power = Math.pow(10, Math.floor(Math.log10(peak)));
        for (double step : new double[] { 1, 2, 5, 10 }) {
            if (step * power >= peak - 1e-6) {
                return (float) (step * power);
            }
        }
        return (float) (10 * power);
    }

    /** The ceiling of the guides now shown. */
    public float ceiling() {
        return ceiling;
    }

    private void refreshSteps() {
        stepLabels.forEach(Label::remove);
        stepLabels.clear();
        labelled = labelledSteps(steps, bandStarts);
        for (int step : labelled) {
            Label label = kit.typeLabel(Integer.toString(step), "caption", "text.muted");
            label.setAlignment(Align.center);
            stepLabels.add(label);
            addActor(label);
        }
        invalidateHierarchy();
    }

    /** How many bands show: those that start within the steps. */
    private int shownBands() {
        int shown = 0;
        for (int i = 0; i < Math.min(bandStarts.length, bandLabels.size()); i++) {
            if (bandStarts[i] <= Math.max(1, steps)) {
                shown = i + 1;
            }
        }
        return shown;
    }

    private int bandEnd(int band) {
        return (band + 1 < shownBands()) ? bandStarts[band + 1] - 1 : steps;
    }

    @Override
    public float getPrefWidth() {
        return space("xxxl") * 10;
    }

    @Override
    public float getPrefHeight() {
        return space("xxxl") * 5;
    }

    @Override
    public float getMinWidth() {
        return space("xxxl") * 4;
    }

    @Override
    public void layout() {
        float labelWidth = 0;
        for (Label label : valueLabels) {
            labelWidth = Math.max(labelWidth, label.getPrefWidth());
        }
        float labelHeight = valueLabels[0].getPrefHeight();
        float header = bandLabels.isEmpty() ? labelHeight / 2 : bandLabels.get(0).getPrefHeight() + gap;
        plotX = labelWidth + gap * 2;
        plotY = labelHeight + gap;
        plotWidth = Math.max(1, getWidth() - plotX - gap);
        plotHeight = Math.max(1, getHeight() - plotY - header);
        for (int i = 0; i < GUIDES; i++) {
            float y = plotY + plotHeight * i / (GUIDES - 1);
            valueLabels[i].setBounds(0, y - labelHeight / 2, labelWidth, labelHeight);
        }
        for (int i = 0; i < stepLabels.size(); i++) {
            Label label = stepLabels.get(i);
            float centre = stepCentre(labelled[i]);
            float width = label.getPrefWidth() + gap * 2;
            label.setBounds(centre - width / 2, 0, width, labelHeight);
        }
        int shown = shownBands();
        for (int i = 0; i < bandLabels.size(); i++) {
            Label label = bandLabels.get(i);
            label.setVisible(i < shown && steps > 0);
            if (!label.isVisible()) {
                continue;
            }
            float left = stepLeft(bandStarts[i]);
            float right = stepLeft(bandEnd(i) + 1);
            label.setBounds(left, plotY + plotHeight + gap, right - left, label.getPrefHeight());
        }
    }

    private float stepLeft(int step) {
        return plotX + plotWidth * (step - 1) / Math.max(1, steps);
    }

    private float stepCentre(int step) {
        return plotX + plotWidth * (step - .5f) / Math.max(1, steps);
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (sweeping && getStage() != null) {
            elapsed += delta;
            if (elapsed >= duration) {
                sweeping = false;
            }
            if (Gdx.graphics != null) {
                Gdx.graphics.requestRendering();
            }
        }
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        float alpha = parentAlpha * getColor().a;
        float previous = batch.getPackedColor();
        float x0 = getX() + plotX;
        float y0 = getY() + plotY;
        int bands = shownBands();
        if (steps > 0) {
            for (int i = 0; i < bands; i++) {
                float left = getX() + stepLeft(bandStarts[i]);
                if (i % 2 == 1) {
                    batch.setColor(guide.r, guide.g, guide.b, washAlpha * alpha);
                    batch.draw(white, left, y0, getX() + stepLeft(bandEnd(i) + 1) - left, plotHeight);
                }
                if (i > 0) {
                    batch.setColor(guide.r, guide.g, guide.b, guide.a * alpha);
                    batch.draw(white, left, y0, 1, plotHeight + gap + bandLabels.get(i).getHeight());
                }
            }
        }
        batch.setColor(guide.r, guide.g, guide.b, guide.a * alpha);
        for (int i = 0; i < GUIDES; i++) {
            batch.draw(white, x0, y0 + plotHeight * i / (GUIDES - 1), plotWidth, 1);
        }
        float shown = plotWidth * progress();
        if (steps > 0) {
            float stepWidth = plotWidth / steps;
            for (Series each : series) {
                switch (each.style()) {
                    case AREA -> {
                        fill(batch, each, x0, y0, stepWidth, shown, alpha);
                        line(batch, each, x0, y0, stepWidth, shown, alpha);
                    }
                    case LINE -> line(batch, each, x0, y0, stepWidth, shown, alpha);
                    case CEILING -> dashes(batch, each, x0, y0, stepWidth, shown, alpha);
                }
            }
            if (shown < plotWidth) {
                batch.setColor(scan.r, scan.g, scan.b, scan.a * alpha);
                batch.draw(white, x0 + shown - 1, y0, 2, plotHeight);
            }
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }

    private float valueY(float y0, float value) {
        return y0 + plotHeight * Math.min(1, Math.max(0, value) / ceiling);
    }

    private void fill(Batch batch, Series each, float x0, float y0, float stepWidth, float shown, float alpha) {
        Color color = each.color();
        batch.setColor(color.r, color.g, color.b, color.a * fillAlpha * alpha);
        float[] values = each.values();
        for (int i = 0; i < values.length; i++) {
            float width = Math.min(stepWidth, shown - i * stepWidth);
            if (width <= 0) {
                break;
            }
            batch.draw(white, x0 + i * stepWidth, y0, width, valueY(y0, values[i]) - y0);
        }
    }

    private void line(Batch batch, Series each, float x0, float y0, float stepWidth, float shown, float alpha) {
        Color color = each.color();
        batch.setColor(color.r, color.g, color.b, color.a * alpha);
        float thickness = each.thickness();
        float[] values = each.values();
        for (int i = 0; i < values.length; i++) {
            float width = Math.min(stepWidth, shown - i * stepWidth);
            if (width <= 0) {
                break;
            }
            float y = valueY(y0, values[i]) - thickness / 2;
            batch.draw(white, x0 + i * stepWidth, y, width, thickness);
            if (i > 0) {
                float before = valueY(y0, values[i - 1]) - thickness / 2;
                batch.draw(white, x0 + i * stepWidth - thickness / 2, Math.min(before, y), thickness,
                      Math.abs(before - y) + thickness);
            }
        }
    }

    private void dashes(Batch batch, Series each, float x0, float y0, float stepWidth, float shown, float alpha) {
        Color color = each.color();
        batch.setColor(color.r, color.g, color.b, color.a * alpha);
        float thickness = each.thickness();
        float[] values = each.values();
        float dash = gap * 2;
        for (float x = 0; x < shown; x += dash + gap) {
            int step = Math.min(values.length - 1, (int) ((x + dash / 2) / stepWidth));
            if (step < 0) {
                break;
            }
            float width = Math.min(dash, shown - x);
            batch.draw(white, x0 + x, valueY(y0, values[step]) - thickness / 2, width, thickness);
        }
    }
}
