/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import megamek.client.ui.design.DesignTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiChoiceSmokeTest {
    private static final DesignTokens TOKENS = DesignTokens.shipped();

    @Test
    void indicatorsAreIconSizedWithRoomForTheFocusRing() {
        UiTestStage.run(ui -> {
            float size = TOKENS.size().get("icon.sm");
            float ring = TOKENS.size().get("focus.width") + TOKENS.size().get("focus.gap");
            for (UiChoice choice : new UiChoice[] { ui.kit.check("A", false), ui.kit.radio("A", false),
                  ui.kit.toggleSwitch("A", false) }) {
                assertEquals(size + 2 * ring, choice.getPrefHeight(), .01f, choice.kind().name());
                for (String state : UiTheme.CHOICE_STATES) {
                    String shape = switch (choice.kind()) {
                        case CHECK -> "box";
                        case RADIO -> "radio";
                        case SWITCH -> "track";
                    };
                    assertTrue(ui.theme.skin.has("choice-" + shape + state, Drawable.class), shape + state);
                }
            }
            float switchWidth = ui.kit.toggleSwitch("A", false).getPrefWidth();
            float checkWidth = ui.kit.check("A", false).getPrefWidth();
            assertEquals(Math.round(size * UiTheme.SWITCH_LENGTH) - size, switchWidth - checkWidth, .01f);
        });
    }

    @Test
    void aClickOnTheBoxOrLabelFlipsItAndFiresOneChange() {
        UiTestStage.run(ui -> {
            UiChoice check = place(ui, ui.kit.check("Double blind", false));
            AtomicInteger changes = count(check);
            click(ui, check);
            assertTrue(check.isChecked());
            click(ui, check.label());
            assertFalse(check.isChecked(), "the label is part of the target");
            assertEquals(2, changes.get());
            check.setChecked(true);
            assertEquals(2, changes.get(), "a view's own change fires nothing");
        });
    }

    @Test
    void aClickChecksAMixedBoxAndDropsTheDash() {
        UiTestStage.run(ui -> {
            UiChoice check = place(ui, ui.kit.check("All units", true).mixed(true));
            assertTrue(check.isMixed());
            assertFalse(check.isChecked(), "mixed reads as unchecked");
            assertEquals(4, check.state(), "mixed shows the on face");
            click(ui, check);
            assertTrue(check.isChecked());
            assertFalse(check.isMixed());
            check.mixed(true);
            check.setChecked(false);
            assertFalse(check.isMixed(), "setting the state drops the dash");
            assertFalse(ui.kit.radio("A", false).mixed(true).isMixed(), "only a check box can be mixed");
        });
    }

    @Test
    void groupedRadioButtonsAreExclusiveAndStaySelected() {
        UiTestStage.run(ui -> {
            UiChoice standard = ui.kit.radio("Standard", true);
            UiChoice total = ui.kit.radio("Total warfare", false);
            ui.kit.radioGroup(standard, total);
            place(ui, standard);
            ui.stage.addActor(total);
            total.setBounds(100, 200, total.getPrefWidth(), total.getPrefHeight());
            ui.draw();
            click(ui, total);
            assertTrue(total.isChecked());
            assertFalse(standard.isChecked());
            click(ui, total);
            assertTrue(total.isChecked(), "a click leaves a selected radio button selected");
            ui.stage.setKeyboardFocus(total);
            ui.stage.keyDown(Input.Keys.SPACE);
            assertTrue(total.isChecked(), "so does Space");
        });
    }

    @Test
    void hoverAndDisabledPickTheirFaces() {
        UiTestStage.run(ui -> {
            UiChoice check = place(ui, ui.kit.toggleSwitch("Autosave", true));
            assertEquals(4, check.state());
            move(ui, check);
            assertEquals(5, check.state(), "hover, on");
            ui.stage.mouseMoved(5, 5);
            ui.draw();
            assertEquals(4, check.state(), "hover ends with the pointer");
            check.setDisabled(true);
            assertEquals(7, check.state());
            AtomicInteger changes = count(check);
            click(ui, check);
            assertTrue(check.isChecked(), "a disabled choice ignores clicks");
            assertEquals(0, changes.get());
        });
    }

    @Test
    void spaceAndEnterFlipTheFocusedChoice() {
        UiTestStage.run(ui -> {
            UiChoice check = place(ui, ui.kit.check("Double blind", false));
            AtomicInteger changes = count(check);
            ui.stage.setKeyboardFocus(check);
            ui.stage.keyDown(Input.Keys.SPACE);
            assertTrue(check.isChecked());
            ui.stage.keyDown(Input.Keys.ENTER);
            assertFalse(check.isChecked());
            assertEquals(2, changes.get());
            ui.stage.keyDown(Input.Keys.A);
            assertFalse(check.isChecked(), "other keys pass through");
        });
    }

    private static UiChoice place(UiTestStage ui, UiChoice choice) {
        ui.stage.addActor(choice);
        choice.setBounds(100, 300, choice.getPrefWidth(), choice.getPrefHeight());
        ui.draw();
        return choice;
    }

    private static AtomicInteger count(UiChoice choice) {
        AtomicInteger changes = new AtomicInteger();
        choice.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                changes.incrementAndGet();
            }
        });
        return changes;
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = screen(ui, actor);
        ui.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }

    /** Moves the mouse to the actor's center and draws a frame, which fires its enter and exit. */
    private static void move(UiTestStage ui, Actor actor) {
        Vector2 point = screen(ui, actor);
        ui.stage.mouseMoved(Math.round(point.x), Math.round(point.y));
        ui.draw();
    }

    private static Vector2 screen(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        return ui.stage.stageToScreenCoordinates(point);
    }
}
