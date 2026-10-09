/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiConfirmationSmokeTest {
    @Test
    void sharedPromptTogglesPassesOutsideClicksAndNeverDimsTheBackground() {
        UiTestStage.run(ui -> {
            List<String> actions = new ArrayList<>();
            UiConfirmation prompt = new UiConfirmation(ui.kit, "confirm", () -> { });
            UiButton first = ui.kit.button("hud-mini", null, "FIRST", null);
            UiButton second = ui.kit.button("hud-mini", null, "SECOND", null);
            UiButton other = ui.kit.button("hud-mini", null, "OTHER", null);
            first.setBounds(450, 180, 120, 40);
            second.setBounds(850, 180, 120, 40);
            other.setBounds(100, 180, 120, 40);
            ui.window.addActor(first);
            ui.window.addActor(second);
            ui.window.addActor(other);
            ui.window.addActor(prompt.actor());
            prompt.resize(ui.window.getWidth(), ui.window.getHeight());
            prompt.text("Continue?", "This operation affects the remaining units.", "Continue", "Cancel");
            UiKit.onChange(first, () -> prompt.toggle(first, () -> actions.add("first")));
            UiKit.onChange(second, () -> prompt.toggle(second, () -> actions.add("second")));
            UiKit.onChange(other, () -> actions.add("other"));
            ui.draw();
            Pixmap before = ui.capture("confirmation-closed");
            try {
                click(ui.stage, first);
                ui.draw();
                Pixmap after = ui.capture("confirmation-open");
                try {
                    assertEquals(before.getPixel(20, 20), after.getPixel(20, 20), "No background dimming");
                } finally {
                    after.dispose();
                }
                UiButton cancel = prompt.actor().findActor("confirm-no");
                assertSame(cancel, ui.stage.getKeyboardFocus());
                Label caption = cancel.getLabel();
                assertTrue(caption.getWidth() >= caption.getPrefWidth(), "The focused Cancel caption fits");
                click(ui.stage, first);
                assertFalse(prompt.isOpen(), "The same trigger closes without reopening");
                assertTrue(actions.isEmpty());

                click(ui.stage, first);
                click(ui.stage, other);
                assertFalse(prompt.isOpen());
                assertEquals(List.of("other"), actions, "Outside input reaches its target on the same click");
                click(ui.stage, first);
                click(ui.stage, second);
                assertTrue(prompt.isOpen(), "A different trigger opens its own question on the same click");
                ui.stage.keyDown(Input.Keys.TAB);
                ui.stage.keyUp(Input.Keys.TAB);
                ui.stage.keyDown(Input.Keys.SPACE);
                assertEquals(List.of("other"), actions, "Keyboard activation waits for release");
                ui.stage.keyUp(Input.Keys.SPACE);
                assertFalse(prompt.isOpen());
                assertEquals(List.of("other", "second"), actions, "Only the current trigger's action runs");

                click(ui.stage, first);
                // Detaching and reattaching a visible overlay must keep outside dismissal working.
                prompt.actor().remove();
                ui.window.addActor(prompt.actor());
                click(ui.stage, other);
                assertFalse(prompt.isOpen());
                assertEquals(List.of("other", "second", "other"), actions);
            } finally {
                before.dispose();
            }
        });
    }

    private static void click(Stage stage, Actor actor) {
        Vector2 at = stage.stageToScreenCoordinates(actor.localToStageCoordinates(
              new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
        stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
    }
}
