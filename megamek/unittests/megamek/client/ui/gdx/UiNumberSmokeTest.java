/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiNumberSmokeTest {
    @Test void captionClickDragAndBlurHaveDistinctCommitBoundaries() {
        UiTestStage.run(ui -> {
            record Change(String value, boolean finished) { }
            var events = new ArrayList<Change>();
            var enabled = new java.util.concurrent.atomic.AtomicBoolean();
            UiNumber number = new UiNumber(ui.kit, ui.stage, "Height", 2, -10, 30, .1,
                  (value, finished) -> events.add(new Change(value, finished))).withCheckbox(false, enabled::set);
            ui.stage.addActor(number); number.setBounds(100, 350, 320, 36); ui.draw();
            try {
                var checkbox = point(ui.stage, number.findActor("number-enabled"));
                ui.stage.touchDown((int) checkbox.x, (int) checkbox.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) checkbox.x, (int) checkbox.y, 0, Input.Buttons.LEFT);
                assertTrue(enabled.get());
                assertTrue(events.isEmpty(), "The application checkbox must not change the numeric value");
                assertFalse(number.editing(), "The checkbox must not open the slider");
                Actor caption = number.findActor("editor-scrub-Height");
                Vector2 at = point(ui.stage, caption);
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertTrue(number.editing());
                assertTrue(ui.stage.getRoot().findActor("editor-number-popup").isVisible());
                assertTrue(events.isEmpty(), "Opening the slider does not edit the value");
                ui.draw(); ui.capture("editor-numeric-slider").dispose();
                Actor popup = ui.stage.getRoot().findActor("editor-number-popup");
                Label readout = ui.stage.getRoot().findActor("editor-slider-value-Height");
                assertEquals("2", readout.getText().toString());
                assertTrue(UiTestStage.bounds(popup).contains(UiTestStage.bounds(readout)), "The value is inside the open slider popup");
                assertTrue(readout.getWidth() >= readout.getPrefWidth(), "The number must not be clipped");
                assertTrue(popup.getHeight() > 60 && popup.getY() > 300, "Popover must be visibly placed above the caption");
                // Outside click closes the slider; the next caption gesture scrubs immediately.
                ui.stage.touchDown(20, 20, 0, Input.Buttons.LEFT); ui.stage.touchUp(20, 20, 0, Input.Buttons.LEFT);
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) at.x + 40, (int) at.y, 0);
                assertEquals(new Change("3", false), events.getLast());
                assertEquals("3", readout.getText().toString(), "Caption scrubbing updates the popup before release");
                ui.draw(); ui.capture("editor-numeric-caption-drag").dispose();
                ui.stage.touchUp((int) at.x + 40, (int) at.y, 0, Input.Buttons.LEFT);
                assertEquals(new Change("3", true), events.getLast());
                assertEquals(1, events.stream().filter(Change::finished).count());
                assertFalse(number.editing(), "Releasing a caption drag closes its slider");
                TextField field = number.findActor("editor-Height");
                ui.stage.setKeyboardFocus(field); field.setText("4.5"); ui.stage.setKeyboardFocus(null);
                assertEquals(new Change("4.5", true), events.getLast());
                assertEquals("4.5", readout.getText().toString());
                ui.stage.setKeyboardFocus(field); field.setText("99"); ui.stage.keyDown(Input.Keys.ESCAPE);
                assertEquals("4.5", field.getText());
                assertEquals(2, events.stream().filter(Change::finished).count());
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) at.x + 20, (int) at.y, 0);
                ui.stage.cancelTouchFocus();
                assertEquals(new Change("5", true), events.getLast(), "Focus loss commits the last preview, not cancelled pointer coordinates");
                assertFalse(number.editing());
                ui.stage.setKeyboardFocus(field); field.setText("NaN"); ui.stage.setKeyboardFocus(null);
                assertEquals("5", field.getText());
                int beforeSync = events.size();
                number.value(-2.5);
                assertEquals(beforeSync, events.size(), "Snapshot refresh is silent");
                ui.stage.touchDown((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                ui.stage.touchUp((int) at.x, (int) at.y, 0, Input.Buttons.LEFT);
                assertEquals("-2.5", readout.getText().toString(), "Reopening shows refreshed negative and fractional values");
                ui.draw(); ui.capture("editor-numeric-negative-value").dispose();
                Slider slider = ui.stage.getRoot().findActor("editor-slider-Height");
                Vector2 track = point(ui.stage, slider);
                long committed = events.stream().filter(Change::finished).count();
                ui.stage.touchDown((int) track.x, (int) track.y, 0, Input.Buttons.LEFT);
                ui.stage.touchDragged((int) track.x + 35, (int) track.y, 0);
                assertTrue(popup.isVisible());
                assertTrue(Double.parseDouble(readout.getText().toString()) > 10, "Dragging the track changes the displayed value");
                assertEquals(field.getText(), readout.getText().toString());
                assertEquals(new Change(readout.getText().toString(), false), events.getLast());
                assertEquals(committed, events.stream().filter(Change::finished).count(), "A track drag only previews until release");
                ui.draw(); ui.capture("editor-numeric-slider-drag").dispose();
                ui.stage.touchUp((int) track.x + 35, (int) track.y, 0, Input.Buttons.LEFT);
                assertEquals(new Change(readout.getText().toString(), true), events.getLast());
                assertFalse(popup.isVisible());
            } finally { number.close(); }
        });
    }
    private static Vector2 point(Stage stage, Actor actor) {
        return stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
    }
}
