/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiTextFieldSmokeTest {
    private static final DesignTokens TOKENS = DesignTokens.shipped();

    @Test
    void inputsMatchButtonHeightsAndEveryVariantIsATokenStyle() {
        UiTestStage.run(ui -> {
            for (boolean compact : new boolean[] { false, true }) {
                for (String variant : UiTheme.INPUT_VARIANTS) {
                    String name = "input" + variant + (compact ? UiTheme.COMPACT : "");
                    assertInstanceOf(UiTheme.TokenFieldStyle.class, ui.theme.skin.get(name, TextField.TextFieldStyle.class),
                          name);
                }
                float height = TOKENS.size().get(compact ? "control.sm" : "control.md");
                assertEquals(height, ui.kit.input("Atlas", compact).getPrefHeight(), .01f);
                assertEquals(height, ui.kit.password(compact).getPrefHeight(), .01f);
                assertEquals(height, ui.kit.tokenSearch("Search", compact).getPrefHeight(), .01f);
                assertEquals(height, ui.kit.tokenPassword(compact).getPrefHeight(), .01f);
            }
        });
    }

    @Test
    void revealShowsThePasswordAndSwapsTheEye() {
        UiTestStage.run(ui -> {
            UiKit.TokenPassword password = ui.kit.tokenPassword(false);
            ui.stage.addActor(password);
            password.setBounds(100, 300, 240, 36);
            password.field.setText("secret");
            assertTrue(password.field.isPasswordMode());
            password.reveal.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
            assertTrue(password.shown());
            assertFalse(password.field.isPasswordMode());
            assertSame(ui.theme.skin.getDrawable("icon-hide"),
                  ((com.badlogic.gdx.scenes.scene2d.ui.Image) password.reveal.icons.get(0)).getDrawable());
            password.shown(false);
            assertTrue(password.field.isPasswordMode());

            UiFormField form = ui.kit.formField("Password", password);
            form.error("Wrong password.");
            assertSame(UiTextField.Status.ERROR, password.field.status());
            assertSame(ui.theme.skin.get("input-reveal-error", TextField.TextFieldStyle.class),
                  password.field.getStyle());
            password.field.readOnly(true);
            password.act(0);
            assertFalse(password.reveal.isVisible(), "a read-only password has no reveal");
        });
    }

    @Test
    void pointerHoverShowsTheStrongEdgeUntilFocusOrAStatusWins() {
        UiTestStage.run(ui -> {
            UiTextField field = ui.kit.input("Atlas", false);
            ui.stage.addActor(field);
            field.setBounds(100, 300, 200, 36);
            ui.draw();
            UiTheme.TokenFieldStyle style = (UiTheme.TokenFieldStyle) field.getStyle();
            assertSame(style.background, field.getBackgroundDrawable());
            move(ui, field);
            assertSame(style.over, field.getBackgroundDrawable(), "hover");
            ui.stage.setKeyboardFocus(field);
            assertSame(style.focusedBackground, field.getBackgroundDrawable(), "focus wins over hover");
            ui.stage.setKeyboardFocus(null);
            field.status(UiTextField.Status.ERROR);
            UiTheme.TokenFieldStyle error = (UiTheme.TokenFieldStyle) field.getStyle();
            assertSame(ui.theme.skin.get("input-error", TextField.TextFieldStyle.class), error);
            assertSame(error.background, field.getBackgroundDrawable(), "the status edge stays under the pointer");
            ui.stage.mouseMoved(5, 5);
            ui.draw();
            field.status(UiTextField.Status.NONE);
            assertSame(style.background, field.getBackgroundDrawable(), "hover ends with the pointer");
        });
    }

    @Test
    void searchKeepsItsHoverOverTheClearButtonAndHidesItWhenReadOnly() {
        UiTestStage.run(ui -> {
            UiKit.TokenSearch search = ui.kit.tokenSearch("Search units", false);
            ui.stage.addActor(search);
            search.setBounds(100, 300, 240, 36);
            search.field.setText("Atlas");
            ui.stage.act(0);
            ui.draw();
            UiTheme.TokenFieldStyle style = (UiTheme.TokenFieldStyle) search.field.getStyle();
            assertTrue(search.clear.isVisible());
            move(ui, search.field);
            move(ui, search.clear);
            assertSame(style.over, search.field.getBackgroundDrawable(), "the clear button keeps the field's hover");
            ui.stage.mouseMoved(5, 5);
            ui.draw();
            assertSame(style.background, search.field.getBackgroundDrawable());

            search.field.readOnly(true);
            ui.stage.act(0);
            assertFalse(search.clear.isVisible(), "a read-only search can not be cleared");
            TextField.TextFieldStyle readOnly = search.field.getStyle();
            assertSame(ui.theme.skin.get("input-search-readonly", TextField.TextFieldStyle.class), readOnly);
            Color primary = new Color();
            Color.argb8888ToColor(primary, TOKENS.color("text.primary", Mode.DARK));
            assertEquals(primary, readOnly.disabledFontColor, "read-only text stays primary");
        });
    }

    @Test
    void formFieldStatusColorsTheInput() {
        UiTestStage.run(ui -> {
            UiTextField field = ui.kit.input("9", false);
            UiFormField form = ui.kit.formField("Gunnery", field).helper("0 to 8");
            assertSame(UiTextField.Status.NONE, field.status());
            form.error("Gunnery must be between 0 and 8.");
            assertSame(UiTextField.Status.ERROR, field.status());
            form.error(null);
            assertSame(UiTextField.Status.NONE, field.status());
        });
    }

    /** Moves the mouse to the actor's center and draws a frame, which fires its enter and exit. */
    private static void move(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        ui.stage.stageToScreenCoordinates(point);
        ui.stage.mouseMoved(Math.round(point.x), Math.round(point.y));
        ui.draw();
    }
}
