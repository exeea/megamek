/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual framebuffer checks: nested dialogs dim the previous dialog but never compound the background opacity. */
@Tag("on-demand")
class UiModalSmokeTest {
    @Test
    void oneBackdropFollowsTheHighestVisibleModal() {
        UiTestStage.run(ui -> {
            UiModal first = modal(ui, "FIRST DIALOG", Color.WHITE, 100, 100);
            ui.window.addActor(first);
            Group branch = new Group();
            branch.setTransform(false);
            ui.window.addActor(branch);
            UiModal second = modal(ui, "SECOND DIALOG", UiTheme.CORAL, 300, 240);
            branch.addActor(second);
            UiModal third = modal(ui, "THIRD DIALOG", UiTheme.MINT, 500, 380);
            second.addActor(third);

            first.open(true);
            first.focus();
            ui.draw();
            int background = pixel(20, 20);
            int firstUndimmed = pixel(120, 120);
            ui.capture("modal-stack-one").dispose();

            second.open(true);
            second.focus();
            first.focus();
            assertSame(second, ui.stage.getKeyboardFocus(), "a lower modal cannot steal focus");
            ui.draw();
            assertEquals(background, pixel(20, 20), "opening another modal must not darken the background again");
            int firstDimmed = pixel(120, 120);
            assertNotEquals(firstUndimmed, firstDimmed, "the former top dialog is now dimmed");
            int secondUndimmed = pixel(320, 260);
            ui.capture("modal-stack-two").dispose();

            third.open(true);
            third.focus();
            second.focus();
            assertSame(third, ui.stage.getKeyboardFocus());
            ui.draw();
            assertEquals(background, pixel(20, 20), "a genuinely nested third modal also uses one dimming pass");
            assertEquals(firstDimmed, pixel(120, 120));
            assertNotEquals(secondUndimmed, pixel(320, 260));
            ui.capture("modal-stack-three").dispose();

            Vector2 outside = ui.stage.stageToScreenCoordinates(new Vector2(20, 20));
            assertTrue(ui.stage.touchDown((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT));
            ui.stage.touchUp((int) outside.x, (int) outside.y, 0, Input.Buttons.LEFT);
            assertTrue(!third.isVisible() && second.isVisible() && first.isVisible(), "only the top dialog closes");
            second.focus();
            ui.draw();
            assertEquals(background, pixel(20, 20));
            assertEquals(secondUndimmed, pixel(320, 260), "the backdrop moves down when the top dialog closes");

            branch.setVisible(false);
            first.focus();
            ui.draw();
            assertEquals(firstUndimmed, pixel(120, 120), "hidden ancestors do not retain the backdrop");
            assertEquals(background, pixel(20, 20));
            branch.setVisible(true);
            branch.toBack();
            ui.draw();
            assertEquals(firstUndimmed, pixel(120, 120), "reordering dialogs moves the backdrop too");
            assertEquals(background, pixel(20, 20));
            first.remove();
            ui.draw();
            assertEquals(secondUndimmed, pixel(320, 260), "removing the top modal reveals the next one");
            assertEquals(background, pixel(20, 20));
            second.open(false);
            ui.draw();
            assertNotEquals(background, pixel(20, 20), "no modal means no dimming");
        });
    }

    private static UiModal modal(UiTestStage ui, String title, Color color, float x, float y) {
        UiModal[] self = new UiModal[1];
        UiModal modal = new UiModal(ui.kit, () -> self[0].open(false));
        self[0] = modal;
        modal.setSize(ui.stage.getWidth(), ui.stage.getHeight());
        Table dialog = new Table();
        dialog.setBackground(ui.kit.skin.newDrawable("white", color));
        dialog.add(ui.kit.label(title, "hud-title", 16, UiTheme.FILL_INK));
        dialog.setBounds(x, y, 440, 300);
        modal.addActor(dialog);
        return modal;
    }

    private static int pixel(int x, int y) {
        Pixmap sample = Pixmap.createFromFrameBuffer(x, y, 1, 1);
        try {
            return sample.getPixel(0, 0);
        } finally {
            sample.dispose();
        }
    }
}
