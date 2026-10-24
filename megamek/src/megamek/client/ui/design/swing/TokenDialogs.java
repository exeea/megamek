/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import megamek.client.ui.Messages;
import megamek.client.ui.design.swing.TokenDialogPane.Status;

/**
 * Shows design system dialogs (docs/design-system/tokens.md, Dialogs) in their two tiers:
 * <ul>
 *     <li>{@link #show}: a modal card over the owner's window, for messages, confirmations and short forms. When the
 *     owner has no window to show it in, or the card would not fit, it opens in a window instead.</li>
 *     <li>{@link #window}: a modal window with the theme's title bar, for tools and longer forms.</li>
 * </ul>
 * Both block until the dialog closes, like {@code JOptionPane}, and give the pressed button, or null when the dialog was
 * cancelled. Call them on the event dispatch thread.
 */
public final class TokenDialogs {
    private static int openCards;

    private TokenDialogs() {
    }

    /** Shows {@code pane} as a modal card over {@code owner}'s window and gives the pressed button, or null. */
    public static JButton show(Component owner, TokenDialogPane pane) {
        JRootPane root = rootPane(owner);
        if (root == null || !TokenModalCard.fits(root, pane)) {
            return window(owner, pane);
        }
        return new TokenModalCard(root, pane).showAndWait();
    }

    /** Shows {@code pane} in a modal window titled with its title and gives the pressed button, or null. */
    public static JButton window(Component owner, TokenDialogPane pane) {
        Window parent = owner == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
              : owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(parent, pane.title(), JDialog.ModalityType.APPLICATION_MODAL);
        JButton[] result = new JButton[1];
        pane.assemble(false, button -> {
            result[0] = button;
            dialog.dispose();
        });
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(pane);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                pane.initialFocus().requestFocusInWindow();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return result[0];
    }

    /** Shows a message with an Okay button. */
    public static void message(Component owner, Status status, String title, String text) {
        TokenDialogPane pane = new TokenDialogPane(title).status(status).message(text);
        pane.primary(Messages.getString("Okay"));
        show(owner, pane);
    }

    /** Asks to confirm {@code action}, the label of its button, and gives whether it was confirmed. */
    public static boolean confirm(Component owner, String title, String text, String action) {
        TokenDialogPane pane = new TokenDialogPane(title).status(Status.QUESTION).message(text);
        pane.cancel(Messages.getString("Cancel"));
        JButton confirm = pane.primary(action);
        return show(owner, pane) == confirm;
    }

    /**
     * Asks to confirm a destructive {@code action}, the label of its danger button, and gives whether it was confirmed.
     * The focus starts on Cancel and Enter does not press the action unless it has the focus.
     */
    public static boolean confirmDanger(Component owner, String title, String text, String action) {
        TokenDialogPane pane = new TokenDialogPane(title).status(Status.WARNING).message(text);
        pane.cancel(Messages.getString("Cancel"));
        JButton confirm = pane.danger(action);
        return show(owner, pane) == confirm;
    }

    /** Whether a modal card is showing in any window, so global shortcuts can stand down like for a modal dialog. */
    public static boolean isCardShown() {
        return openCards > 0;
    }

    static void cardOpened() {
        openCards++;
    }

    static void cardClosed() {
        openCards = Math.max(0, openCards - 1);
    }

    private static JRootPane rootPane(Component owner) {
        Component anchor = owner;
        if (anchor == null) {
            anchor = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        }
        if (anchor instanceof RootPaneContainer container) {
            return container.getRootPane();
        }
        return anchor == null ? null : SwingUtilities.getRootPane(anchor);
    }
}
