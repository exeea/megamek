/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import megamek.client.ui.design.DesignTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiScrollListSmokeTest {
    private static final DesignTokens TOKENS = DesignTokens.shipped();

    @Test
    void theTokenBarTakesAStripBesideTheRowsAndLightsUnderThePointer() {
        UiTestStage.run(ui -> {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                items.add("Row " + i);
            }
            float rowHeight = TOKENS.size().get("control.lg");
            UiVirtualList<String> list = new UiVirtualList<>(ui.kit, rowHeight, () -> {
                Table row = new Table();
                row.add(ui.kit.typeLabel("", "body", "text.primary")).expandX().right();
                return row;
            }, (row, item) -> ((Label) ((Table) row).getChildren().first()).setText(item));
            list.setItems(items);
            ScrollPane pane = ui.kit.scrollList(list, true, false);
            ui.window.addActor(pane);
            pane.setBounds(100, 300, 300, 200);
            pane.validate();
            ui.draw();

            float sm = TOKENS.space().get("sm");
            float xxs = TOKENS.space().get("xxs");
            float strip = sm + 2 * xxs + TOKENS.space().get("xs");
            assertEquals(strip, pane.getScrollBarWidth(), .01f, "the bar, sm + 2 xxs, and its xs gap to the rows");
            assertEquals(300 - strip, list.getWidth(), .01f, "the rows, and so their washes, end before the strip");
            for (Actor row : list.getChildren()) {
                if (row.isVisible()) {
                    Label text = (Label) ((Table) row).getChildren().first();
                    float right = text.localToActorCoordinates(list, new Vector2(text.getWidth(), 0)).x;
                    assertTrue(right <= list.getWidth(), "a row's trailing text ends left of the bar: " + right);
                }
            }
            assertEquals(sm + xxs, pane.getStyle().vScrollKnob.getMinWidth(), .01f,
                  "the thumb keeps xxs from the bar's trailing side");

            Vector2 bar = pane.localToStageCoordinates(new Vector2(pane.getWidth() - (sm + 2 * xxs) / 2, 100));
            ui.stage.stageToScreenCoordinates(bar);
            ui.stage.mouseMoved(Math.round(bar.x), Math.round(bar.y));
            ui.stage.act(0);
            assertSame(ui.kit.skin.getDrawable("scroll-rail"), pane.getStyle().vScroll, "the rail shows on hover");
            assertSame(ui.kit.skin.getDrawable("scroll-thumb-over"), pane.getStyle().vScrollKnob);

            Vector2 row = list.localToStageCoordinates(new Vector2(list.getWidth() - 1,
                  list.getHeight() - pane.getScrollY() - rowHeight * 1.5f));
            ui.stage.stageToScreenCoordinates(row);
            ui.stage.mouseMoved(Math.round(row.x), Math.round(row.y));
            ui.stage.act(0);
            assertSame(ui.kit.skin.getDrawable("scroll-thumb"), pane.getStyle().vScrollKnob,
                  "off the bar, the thumb rests");
            ui.draw();
        });
    }
}
