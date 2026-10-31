/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Clicks the pick list, the range input and the list's type-ahead through the stage. */
@Tag("on-demand")
class UiPickListSmokeTest {
    private static final List<String> ITEMS = List.of("Battle Fists", "Command Mek", "Compact Heat Sinks", "Rugged");

    @Test
    void aChoiceBecomesAChipThatTogglesAndCloses() {
        UiTestStage.run(ui -> {
            UiPickList picks = ui.kit.pickList("Add…", ITEMS, true);
            place(ui, picks);
            AtomicInteger changes = count(picks);
            click(ui, picks.select());
            click(ui, picks.select().popup().label(2));
            assertEquals(Map.of(2, true), picks.picks(), "the choice is a must-have");
            assertEquals(1, changes.get(), "one change for the choice");
            assertEquals(-1, picks.select().getSelectedIndex(), "the select goes back to its placeholder");
            layout(ui, picks);
            assertEquals(1, picks.chips().size());

            click(ui, picks.chips().get(0));
            assertEquals(Map.of(2, false), picks.picks(), "a click on the chip turns it to must-not-have");
            assertEquals(2, changes.get());
            layout(ui, picks);

            Actor close = ((Table) picks.chips().get(0)).getChildren().peek();
            click(ui, close);
            assertTrue(picks.picks().isEmpty(), "its close glyph removes it, without switching it first");
            assertEquals(3, changes.get());

            picks.set(Map.of(0, true, 3, true));
            assertEquals(3, changes.get(), "set fires nothing");
            assertEquals(2, picks.chips().size());
        });
    }

    @Test
    void withoutExcludesAChipClickRemovesIt() {
        UiTestStage.run(ui -> {
            UiPickList picks = ui.kit.pickList("Add…", ITEMS, false);
            place(ui, picks);
            picks.set(Map.of(1, true));
            layout(ui, picks);
            click(ui, picks.chips().get(0));
            assertTrue(picks.picks().isEmpty());
        });
    }

    @Test
    void theRangeTakesDigitsAndFiresOneChangePerKey() {
        UiTestStage.run(ui -> {
            UiRangeInput range = ui.kit.rangeInput("Min", "Max");
            place(ui, range);
            AtomicInteger changes = count(range);
            click(ui, range.minField());
            ui.stage.keyTyped('4');
            ui.stage.keyTyped('x');
            ui.stage.keyTyped('2');
            assertEquals(42, range.min(), "only digits are taken");
            assertNull(range.max());
            assertEquals(2, changes.get(), "one change from the range per digit, none from the field itself");
            range.set(null, 7);
            assertNull(range.min());
            assertEquals(7, range.max());
            assertEquals(2, changes.get(), "set fires nothing");
            assertNull(UiRangeInput.parse(""));
            assertEquals(12, UiRangeInput.parse(" 12 "));
        });
    }

    @Test
    void typingInAnOpenListJumpsToTheFirstMatchingRow() {
        UiTestStage.run(ui -> {
            UiListPopup list = new UiListPopup(ui.kit, ITEMS.toArray(String[]::new));
            assertTrue(list.typeAhead('c', 0));
            assertEquals(1, list.active());
            assertTrue(list.typeAhead('O', 100));
            assertTrue(list.typeAhead('m', 200));
            assertTrue(list.typeAhead('p', 300));
            assertEquals(2, list.active(), "the prefix grows while keys come quickly");
            assertTrue(list.typeAhead('r', 300 + UiListPopup.TYPE_AHEAD_MS + 1));
            assertEquals(3, list.active(), "a pause starts a new prefix");
            assertFalse(list.typeAhead(' ', 5000), "a space alone is not a prefix");
            assertFalse(list.typeAhead('\n', 5000));
        });
    }

    private static void place(UiTestStage ui, Table table) {
        ui.stage.addActor(table);
        layout(ui, table);
    }

    private static void layout(UiTestStage ui, Table table) {
        table.setBounds(100, 200, Math.max(400, table.getPrefWidth()), table.getPrefHeight());
        table.validate();
        ui.draw();
    }

    private static AtomicInteger count(Actor actor) {
        AtomicInteger changes = new AtomicInteger();
        actor.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor target) {
                changes.incrementAndGet();
            }
        });
        return changes;
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        ui.stage.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.stage.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
