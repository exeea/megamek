/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.util.function.Predicate;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import com.formdev.flatlaf.FlatClientProperties;

/**
 * Shows the focus ring only after keyboard use, as browsers do with {@code :focus-visible}: a click focuses a button
 * without ringing it, and the next key press brings the ring back. Editable text always shows it, because typing goes
 * there. Installed while a MegaMek look and feel is active.
 * <p>
 * FlatLaf asks {@link FlatClientProperties#COMPONENT_FOCUS_OWNER} whether a component paints as focused, so each
 * component gets this predicate when it first takes focus. Components that already have their own predicate, such as
 * the gallery's frozen samples, keep it.
 */
public final class FocusVisibility {
    private static final Predicate<JComponent> PREDICATE = FocusVisibility::shows;
    private static final String FOCUS_OWNER = "permanentFocusOwner";

    private static int installs;
    private static volatile boolean installed;
    private static volatile boolean keyboard;
    private static KeyboardFocusManager focusManager;
    private static final AWTEventListener INPUT = FocusVisibility::input;
    private static final PropertyChangeListener FOCUS = event -> {
        if (event.getNewValue() instanceof JComponent component
              && component.getClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER) == null) {
            component.putClientProperty(FlatClientProperties.COMPONENT_FOCUS_OWNER, PREDICATE);
        }
    };

    private FocusVisibility() {
    }

    static synchronized void install() {
        if (installs++ > 0) {
            return;
        }
        keyboard = false;
        Toolkit.getDefaultToolkit().addAWTEventListener(INPUT, AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
        focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        focusManager.addPropertyChangeListener(FOCUS_OWNER, FOCUS);
        installed = true;
    }

    static synchronized void uninstall() {
        if (installs == 0 || --installs > 0) {
            return;
        }
        installed = false;
        Toolkit.getDefaultToolkit().removeAWTEventListener(INPUT);
        focusManager.removePropertyChangeListener(FOCUS_OWNER, FOCUS);
        focusManager = null;
    }

    /**
     * Whether {@code component} paints its focus ring: it owns the focus, and the keyboard was used last or it is
     * editable text. Without a MegaMek look and feel this is plain focus ownership, as in stock FlatLaf.
     */
    public static boolean shows(JComponent component) {
        boolean owner = component.hasFocus()
              || KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner() == component;
        return owner && (!installed || keyboard || alwaysShows(component));
    }

    /** Whether the last input came from the keyboard, so focus rings are showing. */
    static boolean keyboardActive() {
        return keyboard;
    }

    static boolean alwaysShows(Component component) {
        return component instanceof JTextComponent text && text.isEditable();
    }

    static void input(AWTEvent event) {
        boolean wasKeyboard = keyboard;
        if (event.getID() == KeyEvent.KEY_PRESSED && !isModifier(((KeyEvent) event).getKeyCode())) {
            keyboard = true;
        } else if (event.getID() == MouseEvent.MOUSE_PRESSED) {
            keyboard = false;
        }
        if (keyboard != wasKeyboard) {
            repaintFocusOwner();
        }
    }

    private static boolean isModifier(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
                 KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_WINDOWS, KeyEvent.VK_CONTEXT_MENU -> true;
            default -> false;
        };
    }

    /** Repaints the focus owner and the spinner, combo box or scroll pane whose edge shows its focus. */
    private static void repaintFocusOwner() {
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        if (owner == null || alwaysShows(owner)) {
            return;
        }
        owner.repaint();
        Component edge = SwingUtilities.getAncestorOfClass(JSpinner.class, owner);
        if (edge == null) {
            edge = SwingUtilities.getAncestorOfClass(JComboBox.class, owner);
        }
        if (edge == null) {
            edge = SwingUtilities.getAncestorOfClass(JScrollPane.class, owner);
        }
        if (edge != null) {
            edge.repaint();
        }
    }
}
