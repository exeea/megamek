/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import megamek.client.ui.Messages;
import megamek.client.ui.design.swing.TokenDialogPane.Status;
import megamek.client.ui.util.UIUtil;

/**
 * Shows design system dialogs (docs/design-system/tokens.md, Dialogs) in their two tiers:
 * <ul>
 *     <li>{@link #show}: a modal card over the owner's window, for messages, confirmations and short forms. When the
 *     owner has no window to show it in, or the card would not fit, it opens in a window instead.</li>
 *     <li>{@link #window}: a modal window with the theme's title bar, for tools and longer forms.</li>
 * </ul>
 * Both block until the dialog closes, like {@code JOptionPane}, and give the pressed button, or null when the dialog was
 * cancelled. {@link #progress} shows a {@link TokenStatusCard} the same way, for work the user must wait for. Call them
 * on the event dispatch thread.
 */
public final class TokenDialogs {
    private static int openCards;

    private TokenDialogs() {
    }

    /**
     * A dialog footer for windows that are not yet a {@link TokenDialogPane}: {@code buttons} centred, {@code md}
     * apart, all as wide as the widest, at least 88 wide and the regular control height, padded {@code xxl} on every
     * side but the top, which has {@code xl}. Give the main action first; the row reverses for right-to-left.
     */
    public static JPanel footer(AbstractButton... buttons) {
        JPanel footer = new JPanel(new TokenDialogPane.EqualRow(TokenDialogPane.space("md"),
              UIUtil.scaleForGUI(TokenDialogPane.BUTTON_MIN_WIDTH),
              UIUtil.scaleForGUI(TokenButtons.Size.REGULAR.height())));
        int xxl = TokenDialogPane.space("xxl");
        footer.setBorder(BorderFactory.createEmptyBorder(TokenDialogPane.space("xl"), xxl, xxl, xxl));
        for (AbstractButton button : buttons) {
            footer.add(button);
        }
        return footer;
    }

    /** Shows {@code pane} as a modal card over {@code owner}'s window and gives the pressed button, or null. */
    public static JButton show(Component owner, TokenDialogPane pane) {
        JRootPane root = rootPane(owner);
        TokenModalCard.Content content = TokenModalCard.Content.of(pane);
        if (root == null || !TokenModalCard.fits(root, content)) {
            return window(owner, pane);
        }
        return new TokenModalCard(root, content).showAndWait();
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

    /**
     * Shows {@code card} as a modal card over {@code owner}'s window, or in a window when it does not fit, until the
     * user cancels or closes it or the task calls {@link TokenStatusCard#finish}. Start the task first: this waits,
     * still handling events, like a modal dialog.
     */
    public static void progress(Component owner, TokenStatusCard card) {
        if (card.isFinished()) {
            return;
        }
        JRootPane root = rootPane(owner);
        TokenModalCard.Content content = new TokenModalCard.Content(card, card::hostIn, card::initialFocus);
        if (root != null && TokenModalCard.fits(root, content)) {
            new TokenModalCard(root, content).showAndWait();
            return;
        }
        Window parent = owner == null ? KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()
              : owner instanceof Window window ? window : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(parent, card.title(), JDialog.ModalityType.APPLICATION_MODAL);
        card.hostIn(button -> dialog.dispose());
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setContentPane(card);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                card.initialFocus().requestFocusInWindow();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                // The title bar's close stops the task like the card's own button, and does nothing when it can't
                JButton stop = card.stopButton();
                if (stop != null) {
                    stop.doClick();
                }
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
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
