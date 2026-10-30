/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

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
 * range, each series a line that holds its value across its step and turns square to the next; under them three
 * guide lines in border.subtle at nothing, half and the ceiling, labelled in caption type and text.muted, with the
 * first, middle and last step under the plot. The ceiling rounds up to 1, 2 or 5 times a power of ten over the highest
 * value. Series draw in the order given, so the one that matters most comes last and draws on top.
 */
public final class UiStepChart extends WidgetGroup {
    private static final int GUIDES = 3;
    private static final int STEP_LABELS = 3;

    /** One line: the values of steps 1 to n, its colour and its thickness. */
    public record Series(float[] values, Color color, float thickness) { }

    private final Texture white;
    private final Color guide = new Color(UiTheme.tokenColor("border.subtle"));
    private final Label[] valueLabels = new Label[GUIDES];
    private final Label[] stepLabels = new Label[STEP_LABELS];
    private final float gap = space("xs");
    private List<Series> series = List.of();
    private int steps;
    private float ceiling = 1;
    private float plotX;
    private float plotY;
    private float plotWidth;
    private float plotHeight;

    public UiStepChart(UiKit kit) {
        white = kit.skin.get("white", Texture.class);
        setTouchable(Touchable.disabled);
        for (int i = 0; i < GUIDES; i++) {
            valueLabels[i] = kit.typeLabel("", "caption", "text.muted");
            valueLabels[i].setAlignment(Align.right);
            addActor(valueLabels[i]);
        }
        for (int i = 0; i < STEP_LABELS; i++) {
            stepLabels[i] = kit.typeLabel("", "caption", "text.muted");
            stepLabels[i].setAlignment(Align.center);
            addActor(stepLabels[i]);
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
        int[] shown = { 1, Math.max(1, (steps + 1) / 2), Math.max(1, steps) };
        for (int i = 0; i < STEP_LABELS; i++) {
            stepLabels[i].setText(steps == 0 || (i > 0 && shown[i] == shown[i - 1]) ? "" : Integer.toString(shown[i]));
        }
        invalidateHierarchy();
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
        plotX = labelWidth + gap * 2;
        plotY = labelHeight + gap;
        plotWidth = Math.max(1, getWidth() - plotX - gap);
        plotHeight = Math.max(1, getHeight() - plotY - labelHeight / 2);
        for (int i = 0; i < GUIDES; i++) {
            float y = plotY + plotHeight * i / (GUIDES - 1);
            valueLabels[i].setBounds(0, y - labelHeight / 2, labelWidth, labelHeight);
        }
        int[] shown = { 1, Math.max(1, (steps + 1) / 2), Math.max(1, steps) };
        for (int i = 0; i < STEP_LABELS; i++) {
            float centre = stepCentre(shown[i]);
            float width = stepLabels[i].getPrefWidth() + gap * 2;
            stepLabels[i].setBounds(centre - width / 2, 0, width, labelHeight);
        }
    }

    private float stepCentre(int step) {
        return plotX + plotWidth * (step - .5f) / Math.max(1, steps);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        validate();
        float alpha = parentAlpha * getColor().a;
        float previous = batch.getPackedColor();
        float x0 = getX() + plotX;
        float y0 = getY() + plotY;
        batch.setColor(guide.r, guide.g, guide.b, guide.a * alpha);
        for (int i = 0; i < GUIDES; i++) {
            batch.draw(white, x0, y0 + plotHeight * i / (GUIDES - 1), plotWidth, 1);
        }
        if (steps > 0) {
            float stepWidth = plotWidth / steps;
            for (Series each : series) {
                batch.setColor(each.color().r, each.color().g, each.color().b, each.color().a * alpha);
                float thickness = each.thickness();
                float[] values = each.values();
                for (int i = 0; i < values.length; i++) {
                    float y = y0 + plotHeight * Math.min(1, values[i] / ceiling) - thickness / 2;
                    batch.draw(white, x0 + i * stepWidth, y, stepWidth, thickness);
                    if (i > 0) {
                        float before = y0 + plotHeight * Math.min(1, values[i - 1] / ceiling) - thickness / 2;
                        float low = Math.min(before, y);
                        batch.draw(white, x0 + i * stepWidth - thickness / 2, low, thickness,
                              Math.abs(before - y) + thickness);
                    }
                }
            }
        }
        batch.setPackedColor(previous);
        super.draw(batch, parentAlpha);
    }
}
