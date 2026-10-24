/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import megamek.client.ui.Messages;
import megamek.client.ui.design.swing.TokenButtons.Kind;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.design.swing.TokenDialogPane.MessageText;
import megamek.client.ui.design.swing.TokenDialogPane.Width;
import megamek.client.ui.util.UIUtil;

/**
 * A status card (docs/design-system/tokens.md, Status cards): the progress of a long task in the dialog card's frame,
 * with the title, a Cancel in the header and the running step and its percentage over a progress bar. It asks nothing,
 * so it has no footer: its buttons stop or dismiss the task, and those sit in the header.
 * <p>
 * By default it is an ordinary component that the view places where it covers nothing the user works on; it takes no
 * focus. {@link TokenDialogs#progress} shows it as a modal card instead, for work the user must wait for.
 * <p>
 * When the task fails, {@link #fail} turns the bar to the danger color, shows the error in place of the step and
 * replaces Cancel with Close, and Retry when the task can run again; the error stays until the user closes it.
 */
public class TokenStatusCard extends JPanel {
    private final String title;
    private final JLabel heading = new JLabel();
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.TRAILING, 0, 0));
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final MessageText error = new MessageText("", "small");
    private final JButton cancel = TokenButtons.button(Kind.SECONDARY, Messages.getString("Cancel"), Size.COMPACT);
    private final JButton close = TokenButtons.button(Kind.SECONDARY, Messages.getString("Close"), Size.COMPACT);
    private final JButton retry = TokenButtons.button(Kind.SECONDARY, Messages.getString("TokenStatusCard.retry"),
          Size.COMPACT);
    private Width width = Width.SMALL;
    private String step = "";
    private Runnable onCancel;
    private Runnable onClose = () -> { };
    private Runnable onRetry;
    private boolean failed;
    private boolean finished;
    private Consumer<JButton> closer = button -> { };

    public TokenStatusCard(String title) {
        super(new GridBagLayout());
        this.title = title;
        setOpaque(false);
        getAccessibleContext().setAccessibleName(title);
        heading.setText(title);
        tools.setOpaque(false);
        bar.setStringPainted(true);
        bar.putClientProperty(TokenProgressBarUI.SHOW_PERCENT, Boolean.TRUE);
        bar.getAccessibleContext().setAccessibleName(title);
        error.setVisible(false);
        cancel.addActionListener(event -> {
            if (onCancel != null) {
                onCancel.run();
            }
            closer.accept(cancel);
        });
        close.addActionListener(event -> {
            onClose.run();
            closer.accept(close);
        });
        retry.addActionListener(event -> {
            Runnable again = onRetry;
            resume();
            if (again != null) {
                again.run();
            }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop");
        getActionMap().put("stop", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                JButton stop = stopButton();
                if (stop != null) {
                    stop.doClick();
                }
            }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "press");
        getActionMap().put("press", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused instanceof JButton button && button.isEnabled()
                      && SwingUtilities.isDescendingFrom(button, TokenStatusCard.this)) {
                    button.doClick();
                }
            }
        });
        colors();
        layOut();
        tools();
    }

    public String title() {
        return title;
    }

    public TokenStatusCard width(Width width) {
        this.width = width;
        layOut();
        return this;
    }

    /** Shows Cancel in the header; pressing it, or Escape inside the card, runs {@code onCancel}. */
    public TokenStatusCard onCancel(Runnable onCancel) {
        this.onCancel = onCancel;
        tools();
        return this;
    }

    /** Runs when the user closes the card after a failure, so the view can remove it. */
    public TokenStatusCard onClose(Runnable onClose) {
        this.onClose = onClose == null ? () -> { } : onClose;
        return this;
    }

    /** The step that runs now, such as "Preparing terrain", shown above the bar. */
    public TokenStatusCard step(String step) {
        this.step = step == null ? "" : step;
        if (!failed) {
            bar.setString(this.step);
        }
        return this;
    }

    /** The share done, from 0 to 1, with its percentage; makes the bar determinate. */
    public TokenStatusCard progress(double done) {
        bar.setIndeterminate(false);
        bar.setValue((int) Math.round(Math.max(0, Math.min(1, done)) * bar.getMaximum()));
        return this;
    }

    /** For work of unknown length: the bar's moving segment, without a percentage. */
    public TokenStatusCard indeterminate() {
        bar.setIndeterminate(true);
        return this;
    }

    /**
     * Shows that the task failed with {@code message}: a danger bar, the message in place of the step and Close in place
     * of Cancel, with Retry before it when {@code retry} is not null. Retry puts the card back to running and runs it.
     */
    public TokenStatusCard fail(String message, Runnable retry) {
        failed = true;
        onRetry = retry;
        bar.setIndeterminate(false);
        bar.setStringPainted(false);
        bar.putClientProperty(TokenProgressBarUI.SHOW_PERCENT, Boolean.FALSE);
        error.setText(message);
        error.setVisible(true);
        bar.getAccessibleContext().setAccessibleDescription(message);
        colors();
        swapTools();
        return this;
    }

    public boolean isFailed() {
        return failed;
    }

    /** Ends the card when the task is done: closes it when it shows as a modal card; a view removes it itself. */
    public void finish() {
        finished = true;
        closer.accept(null);
    }

    /** Whether {@link #finish} was called. */
    public boolean isFinished() {
        return finished;
    }

    /** The bar, for a view that reads or describes it. */
    public JProgressBar progressBar() {
        return bar;
    }

    /** The button that stops or dismisses the card now: Close after a failure, Cancel when there is one, or null. */
    JButton stopButton() {
        return failed ? close : onCancel != null ? cancel : null;
    }

    JButton cancelButton() {
        return cancel;
    }

    JButton closeButton() {
        return close;
    }

    JButton retryButton() {
        return retry;
    }

    /** Lays the card out to show in a modal card; {@code closer} ends it, with the pressed button or null when done. */
    void hostIn(Consumer<JButton> closer) {
        this.closer = closer == null ? button -> { } : closer;
    }

    /** The modal card starts the focus on Cancel or Close, so Escape and Enter work at once; else on the card. */
    Component initialFocus() {
        if (failed) {
            return close;
        }
        return onCancel != null ? cancel : this;
    }

    private void resume() {
        failed = false;
        onRetry = null;
        error.setVisible(false);
        bar.setStringPainted(true);
        bar.setString(step);
        bar.putClientProperty(TokenProgressBarUI.SHOW_PERCENT, Boolean.TRUE);
        bar.getAccessibleContext().setAccessibleDescription(null);
        colors();
        swapTools();
    }

    /** Rebuilds the header's buttons; when the focus was in the card, it moves to the button that now stops it. */
    private void swapTools() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean inside = focused != null && SwingUtilities.isDescendingFrom(focused, this);
        tools();
        if (SwingUtilities.getWindowAncestor(this) instanceof JDialog dialog && dialog.getContentPane() == this) {
            // Shown in a window, the card grows with the error
            dialog.pack();
        }
        if (inside) {
            initialFocus().requestFocusInWindow();
        }
    }

    private void layOut() {
        removeAll();
        int xl = TokenDialogPane.space("xl");
        int xxl = TokenDialogPane.space("xxl");
        setBorder(BorderFactory.createEmptyBorder(xl, xxl, xxl, xxl));
        int contentWidth = UIUtil.scaleForGUI(width.pixels()) - 2 * xxl;
        error.wrapAt(contentWidth);

        JPanel header = new JPanel(new BorderLayout(TokenDialogPane.space("md"), 0));
        header.setOpaque(false);
        header.add(heading, BorderLayout.CENTER);
        header.add(tools, BorderLayout.EAST);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        add(header, c);
        c.gridy++;
        c.insets = new Insets(TokenDialogPane.space("lg"), 0, 0, 0);
        add(error, c);
        c.gridy++;
        // Below the error, the bar keeps the gap its own text line has
        c.insets = new Insets(TokenDialogPane.space(error.isVisible() ? "xs" : "lg"), 0, 0, 0);
        add(bar, c);
        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        add(Box.createHorizontalStrut(contentWidth), c);
    }

    /** The header's buttons: Cancel while running, or Retry and Close after a failure. */
    private void tools() {
        tools.removeAll();
        if (failed) {
            if (onRetry != null) {
                tools.add(retry);
                tools.add(Box.createHorizontalStrut(TokenDialogPane.space("md")));
            }
            tools.add(close);
        } else if (onCancel != null) {
            tools.add(cancel);
        }
        layOut();
        revalidate();
        repaint();
    }

    private void colors() {
        heading.setFont(TokenFonts.font("headline"));
        heading.setForeground(TokenDialogPane.token("text.primary"));
        error.setForeground(TokenDialogPane.token("text.primary"));
        bar.setForeground(failed ? TokenDialogPane.token("status.danger")
              : UIManager.getColor("ProgressBar.foreground"));
    }

    /** Follows a theme change: the colors come from the tokens of the theme that shows it. */
    @Override
    public void updateUI() {
        super.updateUI();
        if (bar != null) {
            colors();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            TokenFrame.paintCard(g, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }
}
