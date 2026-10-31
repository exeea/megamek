/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.client.ui.design.DesignTokens;
import org.junit.jupiter.api.Test;

/** The staggered reveal's timing: its easing, and its steps (UiRevealSmokeTest shows the bands). */
class UiRevealTest {
    private static final float[] EASING = DesignTokens.shipped().easing();

    @Test
    void theEasingRunsFromNothingToAllAndNeverBack() {
        assertEquals(0, UiReveal.ease(0, EASING));
        assertEquals(1, UiReveal.ease(1, EASING));
        float last = 0;
        for (int i = 1; i <= 20; i++) {
            float eased = UiReveal.ease(i / 20f, EASING);
            assertTrue(eased >= last, "the easing never goes back");
            last = eased;
        }
        assertTrue(UiReveal.ease(0.5f, EASING) > 0.5f, "the token easing decelerates");
        assertEquals(0.5f, UiReveal.ease(0.5f, new float[] { 0, 0, 1, 1 }), 0.01f);
    }

    @Test
    void eachBandWaitsAStepMoreUpToTheCap() {
        assertEquals(0, UiReveal.delay(0, 0.12f));
        assertEquals(0.24f, UiReveal.delay(2, 0.12f), 1e-6f);
        assertEquals(UiReveal.delay(UiReveal.STAGGER_CAP, 0.12f), UiReveal.delay(40, 0.12f));
    }
}
