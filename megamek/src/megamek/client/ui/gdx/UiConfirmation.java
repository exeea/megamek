/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import megamek.client.ui.design.DesignTokens;

/**
 * A non-modal confirmation beside its trigger, with no backdrop. Add {@link #actor()} to an overlay layer and call
 * {@link #resize} after placing the underlying controls. A trigger calls {@link #toggle}; outside clicks dismiss
 * and continue to their original target, while re-clicking the trigger only closes. The owner supplies text and
 * the confirmed action, and cancels when that action becomes invalid. No game state or rendering resources live here.
 */
public final class UiConfirmation {
    private static final float WIDTH = 380;
    private final Table root = new Table();
    private final UiPopover popup;
    private final Label title;
    private final Label message;
    private final UiButton yes;
    private final UiButton no;
    private final Runnable dismissed;
    private final boolean token;
    private Runnable action;
    private int activationKey = -1;

    public UiConfirmation(UiKit ui, String name, Runnable dismissed) {
        this(ui, name, dismissed, null);
    }

    /**
     * A confirmation in hud-v3's look, or with {@code style} {@link UiTheme#TOKEN} in the Token v0 look: the token
     * popover holding a warning icon and a body.strong question over body detail in text.secondary, then a compact
     * danger button before a compact secondary one, in sentence case.
     */
    public UiConfirmation(UiKit ui, String name, Runnable dismissed, String style) {
        this.dismissed = dismissed;
        token = UiTheme.TOKEN.equals(style);
        root.setName(name + "-layer");
        popup = new UiPopover(ui, this::closed, style);
        popup.setName(name);
        if (token) {
            title = ui.typeLabel("", "body.strong", "text.primary");
            message = ui.typeLabel("", "body", "text.secondary");
            yes = ui.button("button-danger" + UiTheme.COMPACT, null, "", null);
            no = ui.button("button-secondary" + UiTheme.COMPACT, null, "", null);
        } else {
            popup.pad(0);
            title = ui.label("", "hud-title", 13, UiTheme.AMBER);
            message = ui.label("", "hud-body", 12, UiTheme.TEXT);
            yes = ui.button("hud-mini", null, "", null);
            no = ui.button("hud-mini", null, "", null);
        }
        title.setWrap(true);
        title.setName(name + "-title");
        message.setWrap(true);
        message.setName(name + "-text");
        yes.setName(name + "-yes");
        no.setName(name + "-no");
        UiKit.onChange(no, this::cancel);
        UiKit.onChange(yes, this::confirm);
        Table question = new Table();
        Table body = new Table();
        Table answers = new Table();
        answers.add().expandX();
        if (token) {
            DesignTokens tokens = DesignTokens.shipped();
            float sm = tokens.space().get("sm");
            float md = tokens.space().get("md");
            question.add(ui.icon("warn", tokens.size().get("icon.sm"), UiTheme.tokenColor("status.warning"))).top();
            question.add(title).growX().minWidth(0).padLeft(sm);
            body.pad(md);
            body.add(question).growX().row();
            body.add(message).growX().minWidth(0).padTop(tokens.space().get("xs")).row();
            answers.add(yes);
            answers.add(no).padLeft(md);
            body.add(answers).growX().padTop(tokens.space().get("lg"));
        } else {
            // Explicit padding is stable across normal, hover and focus backgrounds. Captions are never ellipsized.
            TextButton.TextButtonStyle buttonStyle = new TextButton.TextButtonStyle(no.getStyle());
            buttonStyle.focused = ui.skin.getDrawable("button-auto");
            no.setStyle(buttonStyle);
            buttonStyle = new TextButton.TextButtonStyle(buttonStyle);
            buttonStyle.up = ui.skin.newDrawable("button-auto", UiTheme.AMBER);
            buttonStyle.fontColor = UiTheme.AMBER;
            yes.setStyle(buttonStyle);
            no.pad(6, 12, 6, 12);
            yes.pad(6, 12, 6, 12);
            question.add(ui.icon("warn", 16, UiTheme.AMBER)).top();
            question.add(title).growX().minWidth(0).padLeft(8);
            body.pad(14);
            body.add(question).growX().row();
            body.add(message).growX().minWidth(0).padTop(8).row();
            answers.add(no).minHeight(36);
            answers.add(yes).minHeight(36).padLeft(8);
            body.add(answers).growX().padTop(14);
        }
        popup.content(body);
        popup.addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, int button) {
                // Clicking the card's non-interactive area keeps its keyboard choices available.
                if (popup.getStage().getKeyboardFocus() == null) {
                    popup.getStage().setKeyboardFocus(no);
                }
                return true;
            }

            @Override
            public boolean keyDown(InputEvent event, int key) {
                switch (key) {
                    case Input.Keys.ESCAPE -> cancel();
                    case Input.Keys.TAB, Input.Keys.LEFT, Input.Keys.RIGHT -> {
                        activationKey = -1;
                        popup.getStage().setKeyboardFocus(yes.hasKeyboardFocus() ? no : yes);
                    }
                    case Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER, Input.Keys.SPACE -> activationKey = key;
                    default -> { }
                }
                return true;
            }

            @Override
            public boolean keyUp(InputEvent event, int key) {
                if (key == activationKey) {
                    activationKey = -1;
                    if (yes.hasKeyboardFocus()) { confirm(); } else { cancel(); }
                }
                return true;
            }

            @Override
            public boolean keyTyped(InputEvent event, char character) {
                return true;
            }
        });
    }

    /** The transparent, children-only overlay; it never intercepts input outside the card. */
    public Table actor() {
        return root;
    }

    public boolean isOpen() {
        return popup.isVisible();
    }

    /** Updates the question and full action labels; the caller owns any counts or other live state in the text. */
    public void text(String heading, String detail, String affirmative, String negative) {
        title.setText(token ? heading : UiTheme.upper(heading));
        message.setText(detail);
        yes.setText(token ? affirmative : UiTheme.upper(affirmative));
        no.setText(token ? negative : UiTheme.upper(negative));
        popup.reposition();
    }

    /** Opens for this trigger, or closes on a second activation of the same trigger. Returns whether it opened. */
    public boolean toggle(Actor trigger, Runnable confirmed) {
        if (isOpen() && popup.anchor() == trigger) {
            cancel();
            return false;
        }
        cancel();
        action = confirmed;
        root.addActor(popup);
        popup.showAnchored(trigger, WIDTH);
        popup.getStage().setKeyboardFocus(no);
        return true;
    }

    /** Keeps the card attached on resize; a removed or hidden trigger invalidates the pending choice. */
    public void resize(float width, float height) {
        root.setSize(width, height);
        Actor anchor = popup.anchor();
        if (isOpen() && (anchor.getStage() != root.getStage() || !anchor.ascendantsVisible())) {
            cancel();
        } else {
            popup.reposition();
        }
    }

    /** Explicit cancellation returns keyboard focus to the trigger. Outside clicks instead keep their new focus. */
    public boolean cancel() {
        Actor trigger = popup.anchor();
        Stage stage = popup.getStage();
        if (!popup.cancel()) {
            return false;
        }
        if (stage != null && trigger != null && trigger.getStage() == stage && trigger.ascendantsVisible()) {
            stage.setKeyboardFocus(trigger);
        }
        return true;
    }

    private void confirm() {
        Runnable confirmed = action;
        cancel();
        if (confirmed != null) {
            confirmed.run();
        }
    }

    private void closed() {
        action = null;
        activationKey = -1;
        popup.remove();
        dismissed.run();
    }
}
