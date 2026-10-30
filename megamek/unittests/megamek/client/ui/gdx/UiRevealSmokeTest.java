/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.scenes.scene2d.Actor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The staggered reveal on a stage: bands show one after another, once, or all at once without motion. */
@Tag("on-demand")
class UiRevealSmokeTest {

    @Test
    void theBandsShowOneAfterAnotherThenTheRevealIsDone() {
        UiTestStage.run(ui -> {
            UiReveal reveal = new UiReveal(true);
            for (int i = 0; i < 3; i++) {
                reveal.band(ui.kit.typeLabel("Band " + i, "body", "text.primary"));
            }
            reveal.setSize(200, 200);
            ui.stage.addActor(reveal);
            assertEquals(0, reveal.shown(0));
            reveal.act(0.1f);
            assertTrue(reveal.shown(0) > 0, "the first band has begun");
            assertEquals(0, reveal.shown(2), "the third waits its turn");
            assertFalse(reveal.done());
            ui.stage.draw();
            reveal.act(1);
            assertTrue(reveal.done());
            assertEquals(1, reveal.shown(2));
            float shown = reveal.shown(0);
            reveal.act(1);
            assertEquals(shown, reveal.shown(0), "the reveal runs once");
        });
    }

    @Test
    void withoutMotionEverythingShowsAtOnce() {
        UiTestStage.run(ui -> {
            UiReveal reveal = new UiReveal(false);
            reveal.band(new Actor());
            reveal.band(new Actor());
            assertTrue(reveal.done());
            assertEquals(1, reveal.shown(1));
        });
    }
}
