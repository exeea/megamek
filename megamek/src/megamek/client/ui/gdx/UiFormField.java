/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.gdx.UiTextField.Status;

/**
 * An input with its label above it and a helper or status message below it, the design system's form field
 * ({@link UiKit#formField} builds it; Swing's is TokenFormField). A status colors the input's edge and puts its icon and
 * text in the message, so it never relies on color alone. The message line is only there while it has text.
 */
public class UiFormField extends Table {
    private static final DesignTokens TOKENS = DesignTokens.shipped();

    private final UiKit kit;
    private final Actor input;
    private final Table message = new Table();
    private final Cell<Table> messageCell;
    private String helper;
    private Status status = Status.NONE;
    private String statusText;

    UiFormField(UiKit kit, String labelText, Actor input) {
        this.kit = kit;
        this.input = input;
        float gap = TOKENS.space().get("xs");
        top().left();
        defaults().left().growX();
        add(kit.label(labelText, "hud-medium", 12.5f, color("text.secondary"))).row();
        // The input fills the width the form gives it, rather than TextField's fixed 150 minimum
        add(input).minWidth(0).padTop(gap).row();
        messageCell = add(message).padTop(gap);
        refresh();
    }

    /** A hint under the input, shown while there is no status message. */
    public UiFormField helper(String text) {
        helper = text;
        refresh();
        return this;
    }

    /** An error under the input, replacing the helper; null clears it. */
    public UiFormField error(String text) {
        return status(text == null ? Status.NONE : Status.ERROR, text);
    }

    /** A warning under the input, replacing the helper; null clears it. */
    public UiFormField warning(String text) {
        return status(text == null ? Status.NONE : Status.WARNING, text);
    }

    public UiFormField status(Status value, String text) {
        status = value;
        statusText = value == Status.NONE ? null : text;
        refresh();
        return this;
    }

    public Actor input() {
        return input;
    }

    private void refresh() {
        if (input instanceof UiTextField field) {
            field.status(status);
        } else if (input instanceof UiKit.TokenPassword password) {
            password.field.status(status);
        } else if (input instanceof UiKit.TokenSearch search) {
            search.field.status(status);
        }
        message.clearChildren();
        message.left();
        String text = statusText != null ? statusText : helper;
        boolean shown = text != null && !text.isEmpty();
        if (shown) {
            Color tone = switch (status) {
                case ERROR -> color("status.danger");
                case WARNING -> color("status.warning");
                case NONE -> color("text.muted");
            };
            if (statusText != null) {
                message.add(kit.icon(status == Status.ERROR ? "error" : "warn", 14, tone))
                      .padRight(TOKENS.space().get("xs"));
            }
            Label line = kit.label(text, "hud-small", 11.5f, tone);
            line.setWrap(true);
            message.add(line).growX();
        }
        messageCell.padTop(shown ? TOKENS.space().get("xs") : 0);
        invalidateHierarchy();
    }

    private static Color color(String role) {
        Color color = new Color();
        Color.argb8888ToColor(color, DesignTokens.shipped().color(role, Mode.DARK));
        return color;
    }
}
