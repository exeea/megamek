/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.swing.JButton;
import javax.swing.JPanel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TokenMotionTest {
    private static final String KEY = "test";

    private final JPanel component = new JPanel();
    private long now;

    @BeforeEach
    void useFakeClock() {
        now = 1000;
        TokenMotion.setClock(() -> now);
        TokenMotion.setReduced(false);
    }

    @AfterEach
    void restore() {
        TokenMotion.reset();
        TokenMotion.setClock(() -> System.nanoTime() / 1_000_000L);
        TokenMotion.setReduced(false);
    }

    @Test
    void easingStartsAndEndsOnItsTargetsAndOnlyRises() {
        assertEquals(0, TokenMotion.ease(0));
        assertEquals(1, TokenMotion.ease(1));
        float previous = 0;
        for (int step = 1; step <= 100; step++) {
            float value = TokenMotion.ease(step / 100f);
            assertTrue(value >= previous, "easing falls at " + step);
            previous = value;
        }
        // The token curve decelerates: most of the way is covered early
        assertTrue(TokenMotion.ease(0.5f) > 0.5f);
    }

    @Test
    void theFirstValueShowsAtOnce() {
        assertEquals(Color.RED, TokenMotion.color(component, KEY, Color.RED, 200));
        assertEquals(1, TokenMotion.value(component, "flag", true, 200));
        assertFalse(TokenMotion.isMoving(component, KEY));
    }

    @Test
    void aNewTargetMovesThereOverItsDuration() {
        TokenMotion.value(component, KEY, 0, 200);
        assertEquals(0, TokenMotion.value(component, KEY, 1, 200));
        assertTrue(TokenMotion.isMoving(component, KEY));
        now += 100;
        float halfway = TokenMotion.value(component, KEY, 1, 200);
        assertEquals(TokenMotion.ease(0.5f), halfway, 0.001);
        now += 100;
        assertEquals(1, TokenMotion.value(component, KEY, 1, 200));
        assertFalse(TokenMotion.isMoving(component, KEY));
    }

    @Test
    void aChangedTargetLeavesFromWhatIsShowing() {
        TokenMotion.value(component, KEY, 0, 200);
        TokenMotion.value(component, KEY, 1, 200);
        now += 100;
        float shown = TokenMotion.value(component, KEY, 1, 200);
        assertEquals(shown, TokenMotion.value(component, KEY, 0, 200), 0.001);
        now += 200;
        assertEquals(0, TokenMotion.value(component, KEY, 0, 200));
    }

    @Test
    void colorsFadeInFromAndOutToNothing() {
        assertNull(TokenMotion.color(component, KEY, null, 200));
        assertEquals(0, TokenMotion.color(component, KEY, Color.RED, 200).getAlpha());
        now += 100;
        Color fading = TokenMotion.color(component, KEY, Color.RED, 200);
        assertEquals(Color.RED.getRGB() & 0xFFFFFF, fading.getRGB() & 0xFFFFFF);
        assertTrue(fading.getAlpha() > 0 && fading.getAlpha() < 255);
        now += 100;
        assertEquals(Color.RED, TokenMotion.color(component, KEY, Color.RED, 200));
        TokenMotion.color(component, KEY, null, 200);
        now += 200;
        assertNull(TokenMotion.color(component, KEY, null, 200));
    }

    @Test
    void reducedMotionJumpsAtOnce() {
        TokenMotion.setReduced(true);
        TokenMotion.color(component, KEY, Color.RED, 200);
        assertEquals(Color.BLUE, TokenMotion.color(component, KEY, Color.BLUE, 200));
        assertFalse(TokenMotion.isMoving(component, KEY));
    }

    @Test
    void forgottenValuesStartAfresh() {
        TokenMotion.value(component, KEY, 0, 200);
        TokenMotion.forget(component, KEY);
        assertEquals(1, TokenMotion.value(component, KEY, 1, 200));
    }

    @Test
    void pressesShowAtOnceAndReleasesFade() {
        JButton button = new JButton();
        assertEquals(TokenMotion.medium(), TokenMotion.buttonState(button));
        button.getModel().setRollover(true);
        assertEquals(TokenMotion.fast(), TokenMotion.buttonState(button));
        button.getModel().setArmed(true);
        button.getModel().setPressed(true);
        assertEquals(0, TokenMotion.buttonState(button));
        assertEquals(TokenMotion.fast(), TokenMotion.hover(true));
        assertEquals(TokenMotion.medium(), TokenMotion.hover(false));
    }

    @Test
    void mixingBlendsEveryChannel() {
        Color from = new Color(0, 0, 0, 0);
        Color to = new Color(200, 100, 50, 255);
        assertEquals(from, TokenMotion.mix(from, to, 0));
        assertEquals(to, TokenMotion.mix(from, to, 1));
        assertEquals(new Color(100, 50, 25, 128), TokenMotion.mix(from, to, 0.5f));
    }
}
