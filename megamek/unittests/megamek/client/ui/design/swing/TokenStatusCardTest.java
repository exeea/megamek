/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TokenStatusCardTest {
    private static String previous;

    @BeforeAll
    static void installTheme() throws Exception {
        previous = UIManager.getLookAndFeel() == null ? null : UIManager.getLookAndFeel().getClass().getName();
        UIManager.setLookAndFeel(new MegaMekDarkLaf());
    }

    @AfterAll
    static void restoreTheme() throws Exception {
        if (previous != null) {
            UIManager.setLookAndFeel(previous);
        }
    }

    @Test
    void cancelShowsOnlyWhenTheTaskCanStop() {
        TokenStatusCard fixed = new TokenStatusCard("Save game").step("Writing").progress(0.7);
        assertTrue(buttonsIn(fixed).isEmpty());
        assertNull(fixed.stopButton());
        assertSame(fixed, fixed.initialFocus());

        List<String> calls = new ArrayList<>();
        TokenStatusCard card = new TokenStatusCard("Print").onCancel(() -> calls.add("cancel"));
        assertEquals(List.of(card.cancelButton()), buttonsIn(card));
        assertSame(card.cancelButton(), card.stopButton());
        assertSame(card.cancelButton(), card.initialFocus());

        List<JButton> closed = new ArrayList<>();
        card.hostIn(closed::add);
        card.cancelButton().doClick();
        assertEquals(List.of("cancel"), calls);
        assertEquals(List.of(card.cancelButton()), closed);
    }

    @Test
    void progressShowsTheStepAndPercentage() {
        TokenStatusCard card = new TokenStatusCard("Print").step("Preparing terrain").progress(0.259);
        TokenProgressBarUI ui = (TokenProgressBarUI) card.progressBar().getUI();
        assertEquals("Preparing terrain", card.progressBar().getString());
        assertEquals("25%", ui.percentText());
        card.progress(2);
        assertEquals("100%", ui.percentText());
        card.indeterminate();
        assertNull(ui.percentText());
    }

    @Test
    void failureSwapsCancelForRetryAndClose() {
        List<String> calls = new ArrayList<>();
        TokenStatusCard card = new TokenStatusCard("Print").onCancel(() -> calls.add("cancel"))
              .onClose(() -> calls.add("close")).step("Rendering").progress(0.6);
        card.fail("Out of memory", () -> calls.add("retry"));

        assertTrue(card.isFailed());
        assertEquals(List.of(card.retryButton(), card.closeButton()), buttonsIn(card));
        assertSame(card.closeButton(), card.stopButton());
        assertSame(card.closeButton(), card.initialFocus());
        assertEquals(TokenDialogPane.token("status.danger"), card.progressBar().getForeground());
        assertFalse(card.progressBar().isStringPainted());
        assertNull(((TokenProgressBarUI) card.progressBar().getUI()).percentText());

        card.retryButton().doClick();
        assertFalse(card.isFailed());
        assertEquals(List.of("retry"), calls);
        assertEquals(List.of(card.cancelButton()), buttonsIn(card));
        assertEquals("Rendering", card.progressBar().getString());
        assertEquals(UIManager.getColor("ProgressBar.foreground"), card.progressBar().getForeground());
        assertEquals("60%", ((TokenProgressBarUI) card.progressBar().getUI()).percentText());

        card.fail("Out of memory", null);
        assertEquals(List.of(card.closeButton()), buttonsIn(card), "no Retry when the task can't run again");
        List<JButton> closed = new ArrayList<>();
        card.hostIn(closed::add);
        card.closeButton().doClick();
        assertEquals(List.of("retry", "close"), calls);
        assertEquals(List.of(card.closeButton()), closed);
    }

    @Test
    void finishClosesTheHostWithoutAButton() {
        TokenStatusCard card = new TokenStatusCard("Print").onCancel(() -> { });
        List<JButton> closed = new ArrayList<>();
        card.hostIn(closed::add);
        assertFalse(card.isFinished());
        card.finish();
        assertTrue(card.isFinished());
        assertEquals(1, closed.size());
        assertNull(closed.get(0));
    }

    private static List<JButton> buttonsIn(Container container) {
        List<JButton> buttons = new ArrayList<>();
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button) {
                buttons.add(button);
            } else if (component instanceof Container child) {
                buttons.addAll(buttonsIn(child));
            }
        }
        return buttons;
    }
}
