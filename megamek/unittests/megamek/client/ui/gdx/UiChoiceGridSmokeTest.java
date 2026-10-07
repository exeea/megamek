/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Check real dialog bounds, scrolling and input after the same cards reflow across viewport sizes. */
@Tag("on-demand")
class UiChoiceGridSmokeTest {
    @Test void contentAndViewportDetermineTheGridWithoutRecreatingCards() {
        UiTestStage.run(ui -> {
            UiChoiceGrid grid = ui.kit.choiceGrid(148, 6);
            var scroll = ui.kit.scrollList(grid);
            var dialog = ui.kit.dialog("Choose a design", () -> { });
            dialog.add(scroll).row();
            dialog.add(ui.kit.caption("Choose a card"));
            ui.stage.addActor(dialog);
            List<Actor> cards = new ArrayList<>();
            AtomicInteger chosen = new AtomicInteger(-1);
            for (int count : new int[] { 3, 9, 12, 16, 20 }) {
                while (cards.size() < count) {
                    int id = cards.size();
                    var button = ui.kit.button("hud-plain", null, "Design " + (id + 1), null);
                    UiKit.onChange(button, () -> chosen.set(id));
                    var card = new Container<>(button).fill().prefHeight(112);
                    cards.add(card); grid.addActor(card);
                }
                ui.kit.fitChoices(dialog, scroll, grid, 1100, 850); dialog.pack();
                dialog.setPosition(50, 50); ui.draw();
                int columns = (int) cards.stream().filter(card -> card.getY() == cards.getFirst().getY()).count();
                assertEquals(count <= 3 ? count : count <= 9 ? 3 : count <= 16 ? 4 : 5, columns);
                assertEquals(0, scroll.getMaxY(), .1f, "This set fits without scrolling");
                assertTrue(dialog.getWidth() <= 1100 && dialog.getHeight() <= 850);
                if (count <= 16) { assertEquals(222, cards.getFirst().getWidth(), .1f, "Cards use 50% more width when the full set still fits"); }
                assertVisible(ui, scroll, cards);
                float fittedHeight = dialog.getHeight();
                for (int repeat = 0; repeat < 3; repeat++) {
                    ui.kit.fitChoices(dialog, scroll, grid, 1100, 850); dialog.pack(); ui.draw();
                    assertEquals(fittedHeight, dialog.getHeight(), .1f, "Repeated fitting must not collapse the gallery");
                    assertVisible(ui, scroll, cards);
                }
            }
            Actor selected = cards.get(7);
            ui.stage.setKeyboardFocus(selected);
            ui.kit.fitChoices(dialog, scroll, grid, 410, 520); dialog.pack(); ui.draw();
            assertTrue(scroll.getMaxY() > 0, "A small viewport scrolls without shrinking every card");
            assertTrue(dialog.getWidth() <= 410 && dialog.getHeight() <= 520);
            assertSame(selected, ui.stage.getKeyboardFocus());
            for (int i = 0; i < cards.size(); i++) { assertSame(cards.get(i), grid.getChildren().get(i)); }
            // A wide, short viewport uses more columns to make every card reachable without scrolling.
            ui.kit.fitChoices(dialog, scroll, grid, 1600, 390); dialog.pack(); ui.draw();
            assertEquals(0, scroll.getMaxY(), .1f);
            assertVisible(ui, scroll, cards);
            Actor last = cards.getLast();
            var point = ui.stage.stageToScreenCoordinates(last.localToStageCoordinates(new Vector2(last.getWidth() / 2, last.getHeight() / 2)));
            ui.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            ui.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
            assertEquals(19, chosen.get(), "The last reflowed card still invokes its original action");
            grid.clearChildren(); ui.kit.fitChoices(dialog, scroll, grid, 410, 520); dialog.pack(); ui.draw();
            assertEquals(0, grid.getPrefHeight());
        });
    }

    private static void assertVisible(UiTestStage ui, Actor viewport, List<Actor> cards) {
        var area = UiTestStage.bounds(viewport);
        for (Actor card : cards) {
            var bounds = UiTestStage.bounds(card);
            assertTrue(bounds.x >= area.x - .5f && bounds.y >= area.y - .5f && bounds.x + bounds.width <= area.x + area.width + .5f
                  && bounds.y + bounds.height <= area.y + area.height + .5f, "Card " + cards.indexOf(card) + " of " + cards.size()
                  + " at " + UiTestStage.bounds(card) + " must fit in " + area);
            var point = card.localToStageCoordinates(new Vector2(card.getWidth() / 2, card.getHeight() / 2));
            assertTrue(ui.stage.hit(point.x, point.y, true).isDescendantOf(card), "Every card can receive input");
        }
    }
}
