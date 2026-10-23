/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import megamek.client.ui.design.DesignTokens;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class UiOptionBarSmokeTest {
    private static final DesignTokens TOKENS = DesignTokens.shipped();

    @Test
    void barsAreControlHeightAndSegmentsShareTheWidth() {
        UiTestStage.run(ui -> {
            assertEquals(TOKENS.size().get("control.md"), ui.kit.tokenTabs(false, "A", "B").getPrefHeight(), .01f);
            assertEquals(TOKENS.size().get("control.sm"), ui.kit.tokenTabs(true, "A", "B").getPrefHeight(), .01f);
            UiSegmented regular = ui.kit.tokenSegmented(false, "Map", "List", "Cards");
            UiSegmented compact = ui.kit.tokenSegmented(true, "Map", "List", "Cards");
            assertEquals(TOKENS.size().get("control.md"), regular.getPrefHeight(), .01f);
            assertEquals(TOKENS.size().get("control.sm"), compact.getPrefHeight(), .01f);
            place(ui, regular);
            float width = regular.option(0).getWidth();
            for (int i = 1; i < regular.count(); i++) {
                assertEquals(width, regular.option(i).getWidth(), .01f, "section " + i);
            }
            assertTrue(compact.getPrefWidth() < regular.getPrefWidth(), "compact sections have narrower padding");
            assertThrows(IllegalArgumentException.class, () -> ui.kit.tokenTabs(false));
        });
    }

    @Test
    void aClickSelectsAnOptionAndFiresOneChange() {
        UiTestStage.run(ui -> {
            for (UiOptionBar bar : new UiOptionBar[] { ui.kit.tokenTabs(false, "Unit", "Pilot", "Equipment"),
                  ui.kit.tokenSegmented(false, "Map", "List", "Cards") }) {
                place(ui, bar);
                AtomicInteger changes = count(bar);
                assertEquals(0, bar.getSelectedIndex(), "the first option starts selected");
                click(ui, bar.option(2));
                assertEquals(2, bar.getSelectedIndex());
                assertTrue(bar.option(2).isSelected());
                assertEquals(1, changes.get());
                click(ui, bar.option(2));
                assertEquals(1, changes.get(), "a click on the selected option fires nothing");
                bar.select(1);
                assertEquals(1, bar.getSelectedIndex());
                assertEquals(1, changes.get(), "a view's own change fires nothing");
                assertThrows(IndexOutOfBoundsException.class, () -> bar.select(3));
                bar.remove();
            }
        });
    }

    @Test
    void leftAndRightMoveTheSelectionOfAFocusedBar() {
        UiTestStage.run(ui -> {
            UiSegmented bar = place(ui, ui.kit.tokenSegmented(false, "Map", "List", "Cards"));
            AtomicInteger changes = count(bar);
            assertFalse(bar.ringed(0), "no ring without focus");
            ui.stage.setKeyboardFocus(bar);
            assertTrue(bar.ringed(0), "the focused bar rings its selection");
            ui.stage.keyDown(Input.Keys.RIGHT);
            ui.stage.keyDown(Input.Keys.RIGHT);
            assertEquals(2, bar.getSelectedIndex());
            assertTrue(bar.ringed(2));
            ui.stage.keyDown(Input.Keys.RIGHT);
            assertEquals(2, bar.getSelectedIndex(), "the last option stays selected");
            ui.stage.keyDown(Input.Keys.LEFT);
            assertEquals(1, bar.getSelectedIndex());
            assertEquals(3, changes.get());
        });
    }

    @Test
    void aDisabledBarIgnoresInputAndHoverShowsOnTheOption() {
        UiTestStage.run(ui -> {
            UiTabs bar = place(ui, ui.kit.tokenTabs(true, "Unit", "Pilot", "Equipment"));
            move(ui, bar.option(1));
            assertTrue(bar.option(1).isOver());
            ui.stage.mouseMoved(5, 5);
            ui.draw();
            assertFalse(bar.option(1).isOver(), "hover ends with the pointer");
            AtomicInteger changes = count(bar);
            bar.setDisabled(true);
            assertTrue(bar.option(1).isDisabled());
            click(ui, bar.option(1));
            ui.stage.setKeyboardFocus(bar);
            ui.stage.keyDown(Input.Keys.RIGHT);
            assertEquals(0, bar.getSelectedIndex());
            assertEquals(0, changes.get());
            assertFalse(bar.ringed(0), "a disabled bar shows no ring");
        });
    }

    private static <T extends UiOptionBar> T place(UiTestStage ui, T bar) {
        ui.stage.addActor(bar);
        bar.setBounds(100, 300, bar.getPrefWidth(), bar.getPrefHeight());
        bar.validate();
        ui.draw();
        return bar;
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
