/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Clicks the start screen's graphics card question through the stage: it has no close button, Escape and the scrim
 * leave it open while the screen behind takes no click, the card in use is saved and closes it, and the other card is
 * saved and starts the program again.
 */
@Tag("on-demand")
class StartGraphicsCardChoiceSmokeTest {
    private static final List<String> NAMES = List.of("Intel(R) Iris(R) Xe Graphics",
          "NVIDIA GeForce RTX 4070 Laptop GPU");

    @AfterEach
    void unset() throws Exception {
        SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setBoardGraphicsCard(""));
    }

    @Test
    void theQuestionWantsACard() {
        UiTestStage.run(ui -> {
            int[] behind = { 0 };
            Actor screen = new Actor();
            screen.setBounds(0, 0, ui.stage.getWidth(), ui.stage.getHeight());
            screen.setTouchable(Touchable.enabled);
            screen.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    behind[0]++;
                }
            });
            ui.window.addActor(screen);
            List<GpuGraphicsCard> relaunched = new ArrayList<>();
            StartGraphicsCardChoice choice = choice(ui, relaunched);
            choice.show(ui.stage, false);
            ui.draw();
            assertEquals(choice.buttons().reversed(), choice.dialog().focusables(),
                  "the card in use, then the other, and no close button");
            assertTrue(texts(choice.buttons().get(1)).contains("(IN USE)"), "the card in use is marked");

            clickScreen(ui, 4, 4);
            ui.stage.keyDown(Input.Keys.ESCAPE);
            ui.stage.keyUp(Input.Keys.ESCAPE);
            ui.draw();
            assertTrue(choice.isOpen(), "neither the scrim nor Escape closes the question");
            assertEquals(0, behind[0], "the screen behind takes no click");
            flushSwing();
            assertEquals("", GUIPreferences.getInstance().getBoardGraphicsCard(), "nothing saved");
            assertEquals(List.of(), relaunched);
            ui.capture("start-graphics-card").dispose();
        });
    }

    @Test
    void theCardInUseIsSavedAndClosesTheQuestion() {
        UiTestStage.run(ui -> {
            List<GpuGraphicsCard> relaunched = new ArrayList<>();
            StartGraphicsCardChoice choice = choice(ui, relaunched);
            boolean[] next = { false };
            choice.then(() -> next[0] = true);
            choice.show(ui.stage, false);
            ui.draw();
            click(ui, choice.buttons().get(GpuGraphicsCard.DISCRETE.ordinal()));
            assertFalse(choice.isOpen());
            assertTrue(next[0], "what waits for the answer goes on");
            flushSwing();
            assertEquals("DISCRETE", GUIPreferences.getInstance().getBoardGraphicsCard());
            assertTrue(GpuGraphicsCard.chosen());
            assertEquals(List.of(), relaunched, "the card in use needs no new start");
        });
    }

    @Test
    void theOtherCardIsSavedAndStartsTheProgramAgain() {
        UiTestStage.run(ui -> {
            List<GpuGraphicsCard> relaunched = new ArrayList<>();
            StartGraphicsCardChoice choice = choice(ui, relaunched);
            boolean[] next = { false };
            choice.then(() -> next[0] = true);
            choice.show(ui.stage, false);
            ui.draw();
            click(ui, choice.buttons().get(GpuGraphicsCard.INTEGRATED.ordinal()));
            assertFalse(choice.isOpen());
            assertFalse(next[0], "nothing goes on in a run that ends");
            flushSwing();
            assertEquals("INTEGRATED", GUIPreferences.getInstance().getBoardGraphicsCard());
            assertEquals(List.of(GpuGraphicsCard.INTEGRATED), relaunched);
        });
    }

    /** The question on a laptop drawing with its NVIDIA card. */
    private static StartGraphicsCardChoice choice(UiTestStage ui, List<GpuGraphicsCard> relaunched) {
        return new StartGraphicsCardChoice(ui.kit, "MegaMek", NAMES, GpuGraphicsCard.DISCRETE,
              () -> ui.stage.getWidth() * 0.7, relaunched::add);
    }

    /** Waits for what the question handed to the Swing thread. */
    private static void flushSwing() {
        try {
            SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static String texts(Actor actor) {
        StringBuilder texts = new StringBuilder();
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.ui.Label label) {
            texts.append(label.getText()).append('\n');
        } else if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group group) {
            for (Actor child : group.getChildren()) {
                texts.append(texts(child));
            }
        }
        return texts.toString();
    }

    private static void click(UiTestStage ui, Actor actor) {
        Vector2 point = actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2));
        point = ui.stage.stageToScreenCoordinates(point);
        clickScreen(ui, Math.round(point.x), Math.round(point.y));
    }

    private static void clickScreen(UiTestStage ui, int x, int y) {
        ui.stage.mouseMoved(x, y);
        ui.stage.touchDown(x, y, 0, Input.Buttons.LEFT);
        ui.stage.touchUp(x, y, 0, Input.Buttons.LEFT);
        ui.draw();
    }
}
