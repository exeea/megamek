/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Two columns that fill the scroll pane, only the visible rows shown and created as they come into view, cards kept
 * (hidden) when they leave so scrolling back a little creates nothing, cards far away removed (releasing their
 * previews) and created again when they return, and clicks after rows enter and leave.
 */
@Tag("on-demand")
class UiCardGridSmokeTest {
    @Test void createsOnlyVisibleRowsAndKeepsCardsClickable() {
        UiTestStage.run(ui -> {
            var grid = new UiCardGrid(2, 116, 6);
            var scroll = ui.kit.scrollList(grid); scroll.setBounds(50, 50, 240, 400); ui.stage.addActor(scroll);
            AtomicInteger chosen = new AtomicInteger(-1), created = new AtomicInteger();
            java.util.function.IntFunction<Actor> card = index -> {
                created.incrementAndGet();
                var button = ui.kit.button("hud-plain", null, "Choice " + index, null); button.setName("choice-" + index);
                UiKit.onChange(button, () -> chosen.set(index)); return button;
            };
            grid.items(1000, card); ui.draw(); ui.draw();
            // 400 units show four rows of 122; one more row on each side is ready, so at most 6 rows of 2 exist.
            assertTrue(grid.getChildren().size <= 12, "Offscreen choices do not create a thousand preview actors");
            Actor first = grid.findActor("choice-0"), second = grid.findActor("choice-1");
            assertEquals(first.getWidth(), second.getWidth(), .01f);
            assertEquals(grid.getWidth(), second.getRight(), .5f, "Two columns fill the width");
            assertEquals(first.getY(), second.getY(), .01f);
            int before = created.get(); ui.draw(); ui.draw();
            assertEquals(before, created.get(), "An unchanged view creates nothing");
            scroll.setScrollY(5 * 122); scroll.updateVisualScroll(); ui.draw(); ui.draw();
            assertFalse(grid.findActor("choice-0").isVisible(), "Rows that left the view are hidden");
            int scrolled = created.get();
            scroll.setScrollY(0); scroll.updateVisualScroll(); ui.draw(); ui.draw();
            assertEquals(scrolled, created.get(), "Scrolling back a little shows the kept cards; their previews are not built again");
            assertTrue(grid.findActor("choice-0").isVisible());
            Actor first0 = grid.findActor("choice-0");
            scroll.setScrollY(scroll.getMaxY()); scroll.updateVisualScroll(); ui.draw(); ui.draw();
            assertNull(first0.getStage(), "Far rows are removed from the stage, which releases their previews");
            assertTrue(grid.getChildren().size <= (6 + 2 * UiCardGrid.KEEP_ROWS) * 2,
                  "A bounded window of cards stays, not every card scrolled past: " + grid.getChildren().size);
            Actor last = grid.findActor("choice-999"); assertNotNull(last); click(ui, last);
            assertEquals(999, chosen.get());
            scrolled = created.get();
            scroll.setScrollY(0); scroll.updateVisualScroll(); ui.draw(); ui.draw();
            assertTrue(created.get() > scrolled, "Returning far rows are created again");
            assertTrue(grid.findActor("choice-0").isVisible() && last.getStage() == null);
            grid.items(3, card); scroll.setScrollY(0); scroll.updateVisualScroll(); ui.draw(); ui.draw();
            assertEquals(3, grid.getChildren().size); assertEquals(0, scroll.getMaxY(), .01f);
            click(ui, grid.findActor("choice-2")); assertEquals(2, chosen.get());
            grid.items(0, card); ui.draw(); ui.draw();
            assertEquals(0, grid.getChildren().size);
        });
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = ui.stage.stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        ui.stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
