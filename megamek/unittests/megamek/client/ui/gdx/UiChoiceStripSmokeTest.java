/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Paging, real scrollbar geometry and input after cards enter/leave the virtualized viewport. */
@Tag("on-demand")
class UiChoiceStripSmokeTest {
    @Test void showsOnlyUsefulNavigationAndKeepsCardsClickable() {
        UiTestStage.run(ui -> {
            var strip = new UiChoiceStrip(124, 116, 6);
            var viewport = strip.viewport(ui.kit); viewport.setBounds(50, 50, 600, 126); ui.stage.addActor(viewport);
            AtomicInteger chosen = new AtomicInteger(-1);
            java.util.function.IntFunction<Actor> card = index -> {
                var button = ui.kit.button("hud-plain", null, "Choice " + index, null); button.setName("choice-" + index);
                UiKit.onChange(button, () -> chosen.set(index)); return button;
            };
            strip.items(30, card); ui.draw(); ui.draw();
            UiButton previous = viewport.findActor("choices-previous"), next = viewport.findActor("choices-next");
            assertTrue(previous.isVisible() && next.isVisible());
            assertTrue(previous.isDisabled()); assertFalse(next.isDisabled());
            assertTrue(strip.scroll().isScrollX());
            assertTrue(strip.scroll().getScrollBarHeight() > 0, "Overflow has a visible horizontal scrollbar");
            assertTrue(strip.getChildren().size <= 7, "Offscreen choices do not create thousands of preview actors");
            click(ui, next); ui.draw(); ui.draw();
            assertTrue(strip.scroll().getScrollX() > 0); assertFalse(previous.isDisabled());
            for (int page = 0; page < 12 && !next.isDisabled(); page++) { click(ui, next); ui.draw(); ui.draw(); }
            assertTrue(next.isDisabled()); assertFalse(previous.isDisabled());
            assertEquals(strip.scroll().getMaxX(), strip.scroll().getScrollX(), .1f);
            Actor last = strip.findActor("choice-29"); assertNotNull(last); click(ui, last);
            assertEquals(29, chosen.get());
            strip.items(3, card); ui.draw(); ui.draw();
            assertFalse(previous.isVisible()); assertFalse(next.isVisible());
            assertFalse(strip.scroll().isScrollX()); assertEquals(0, strip.scroll().getScrollX());
            click(ui, strip.findActor("choice-2")); assertEquals(2, chosen.get());
            viewport.setWidth(250); ui.draw(); ui.draw();
            assertTrue(next.isVisible()); assertTrue(previous.isDisabled());
            strip.items(0, card); ui.draw(); ui.draw();
            assertFalse(next.isVisible()); assertFalse(previous.isVisible()); assertEquals(0, strip.getChildren().size);
        });
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        ui.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
