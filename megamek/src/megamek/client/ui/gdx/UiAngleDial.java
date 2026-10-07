/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import java.util.function.DoubleConsumer;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.ui.Widget;

/** A reusable angle input with a continuous drag across the turn boundary; values are degrees. */
public final class UiAngleDial extends Widget {
    private final Texture white;
    private final DoubleConsumer preview;
    private double value;
    private double previousAngle;
    private double draggedValue;

    public UiAngleDial(UiKit ui, DoubleConsumer preview, Runnable commit) {
        white = ui.skin.get("white", Texture.class);
        this.preview = preview;
        addListener(new InputListener() {
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                if (button != Input.Buttons.LEFT) { return false; }
                if (Math.hypot(x - getWidth() / 2, y - getHeight() / 2) < 10) { return false; }
                preview.accept(Math.toDegrees(Math.atan2(y - getHeight() / 2, x - getWidth() / 2)));
                begin(event.getStageX(), event.getStageY(), value);
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer) {
                drag(event.getStageX(), event.getStageY());
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) { commit.run(); }
        });
    }

    public void value(double next) { value = next; }

    /** Caption scrubbing can begin outside the dial, without jumping to the pointer's initial angle. */
    public void begin(float stageX, float stageY, double initial) {
        draggedValue = initial;
        previousAngle = angle(stageX, stageY);
    }

    public void drag(float stageX, float stageY) {
        double next = angle(stageX, stageY);
        if (!Double.isFinite(next)) { return; }
        if (Double.isFinite(previousAngle)) { draggedValue += Math.IEEEremainder(next - previousAngle, 360); }
        previousAngle = next;
        preview.accept(draggedValue);
    }

    private double angle(float x, float y) {
        Vector2 center = localToStageCoordinates(new Vector2(getWidth() / 2, getHeight() / 2));
        return Math.hypot(x - center.x, y - center.y) < 10 ? Double.NaN
              : Math.toDegrees(Math.atan2(y - center.y, x - center.x));
    }

    @Override public float getPrefWidth() { return 132; }
    @Override public float getPrefHeight() { return 132; }

    @Override public void draw(Batch batch, float parentAlpha) {
        float saved = batch.getPackedColor();
        float cx = getX() + getWidth() / 2, cy = getY() + getHeight() / 2;
        float radius = Math.min(getWidth(), getHeight()) / 2 - 12;
        for (int i = 0; i < 72; i++) {
            double a = Math.toRadians(i * 5), b = Math.toRadians((i + 1) * 5);
            line(batch, UiTheme.MUTED, parentAlpha, cx + radius * (float) Math.cos(a), cy + radius * (float) Math.sin(a),
                  cx + radius * (float) Math.cos(b), cy + radius * (float) Math.sin(b), 2);
        }
        for (int i = 0; i < 12; i++) {
            float x = (float) Math.cos(i * Math.PI / 6), y = (float) Math.sin(i * Math.PI / 6);
            line(batch, UiTheme.TEXT, parentAlpha, cx + (radius - 5) * x, cy + (radius - 5) * y,
                  cx + (radius + 3) * x, cy + (radius + 3) * y, 1);
        }
        double angle = Math.toRadians(value);
        line(batch, UiTheme.MINT, parentAlpha, cx, cy,
              cx + radius * (float) Math.cos(angle), cy + radius * (float) Math.sin(angle), 3);
        batch.setPackedColor(saved);
    }

    private void line(Batch batch, Color color, float alpha, float ax, float ay, float bx, float by, float width) {
        batch.setColor(color.r, color.g, color.b, color.a * alpha);
        batch.draw(white, ax, ay - width / 2, 0, width / 2, (float) Math.hypot(bx - ax, by - ay), width,
              1, 1, (float) Math.toDegrees(Math.atan2(by - ay, bx - ax)), 0, 0, 1, 1, false, false);
    }
}
