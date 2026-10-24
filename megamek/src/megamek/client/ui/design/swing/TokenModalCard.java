/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AlphaComposite;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.KeyboardFocusManager;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.LayoutFocusTraversalPolicy;
import javax.swing.SwingUtilities;

/**
 * Shows a {@link TokenDialogPane} or a {@link TokenStatusCard} as a modal card in a window: a {@code surface.scrim} over the whole window, menu bar
 * included, with the card centred on it. The scrim takes every click and the card every key, so nothing behind it
 * reacts, including the window's shortcuts; Tab stays inside the card. It fades in over {@code motion.fast} and closes
 * at once, giving the focus back to where it was.
 */
final class TokenModalCard extends JComponent {
    private static final String FADE = "MegaMek.dialog.fade";

    private final JRootPane root;
    private final Content content;
    private final JComponent pane;
    private final ComponentAdapter follower = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            fit();
        }
    };
    private SecondaryLoop loop;
    private Component previousFocus;
    private JButton result;
    private boolean open;

    /**
     * What a card shows: the view, how to lay it out as a card with the action that closes it, and where the focus
     * starts.
     */
    record Content(JComponent view, Consumer<Consumer<JButton>> assembler, Supplier<Component> initialFocus) {
        static Content of(TokenDialogPane pane) {
            return new Content(pane, closer -> pane.assemble(true, closer), pane::initialFocus);
        }
    }

    TokenModalCard(JRootPane root, Content content) {
        this.root = root;
        this.content = content;
        this.pane = content.view();
        setLayout(null);
        setOpaque(false);
        setFocusCycleRoot(true);
        setFocusTraversalPolicy(new LayoutFocusTraversalPolicy());
        setCursor(Cursor.getDefaultCursor());
        MouseAdapter swallow = new MouseAdapter() { };
        addMouseListener(swallow);
        addMouseMotionListener(swallow);
        addMouseWheelListener(swallow);
        content.assembler().accept(this::close);
        add(pane);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing() && open) {
                // The window went away under the card: end the dialog as cancelled
                close(null);
            }
        });
    }

    /** Whether the card fits in {@code root} with the xxl margin all round. */
    static boolean fits(JRootPane root, Content content) {
        content.assembler().accept(button -> { });
        Dimension size = content.view().getPreferredSize();
        int margin = TokenDialogPane.space("xxl");
        return root.isShowing() && size.width + 2 * margin <= root.getWidth()
              && size.height + 2 * margin <= root.getHeight();
    }

    /** Shows the card and waits, still handling events, until it closes; then gives the pressed button or null. */
    JButton showAndWait() {
        JLayeredPane layers = root.getLayeredPane();
        int layer = JLayeredPane.MODAL_LAYER;
        for (Component component : layers.getComponents()) {
            if (component instanceof TokenModalCard) {
                layer = Math.max(layer, layers.getLayer(component) + 1);
            }
        }
        previousFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        TokenMotion.value(this, FADE, 0f, 0);
        layers.add(this, Integer.valueOf(layer));
        layers.addComponentListener(follower);
        fit();
        open = true;
        TokenDialogs.cardOpened();
        Component focus = content.initialFocus().get();
        SwingUtilities.invokeLater(() -> {
            if (open) {
                focus.requestFocusInWindow();
            }
        });
        loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        loop.enter();
        return result;
    }

    private void fit() {
        JLayeredPane layers = root.getLayeredPane();
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
        revalidate();
        repaint();
    }

    /** Centres the content at its preferred size, again whenever it changes, such as a status card that fails. */
    @Override
    public void doLayout() {
        Dimension size = pane.getPreferredSize();
        int width = Math.min(size.width, getWidth());
        int height = Math.min(size.height, getHeight());
        pane.setBounds((getWidth() - width) / 2, (getHeight() - height) / 2, width, height);
    }

    private void close(JButton button) {
        if (!open) {
            return;
        }
        open = false;
        result = button;
        TokenDialogs.cardClosed();
        JLayeredPane layers = root.getLayeredPane();
        layers.removeComponentListener(follower);
        layers.remove(this);
        layers.repaint();
        TokenMotion.forget(this, FADE);
        if (previousFocus != null && previousFocus.isShowing()) {
            previousFocus.requestFocusInWindow();
        }
        if (loop != null) {
            loop.exit();
        }
    }

    /** Takes every key the card's controls leave, so the window's shortcuts and menus do not act behind the card. */
    @Override
    protected boolean processKeyBinding(KeyStroke stroke, KeyEvent event, int condition, boolean pressed) {
        return super.processKeyBinding(stroke, event, condition, pressed) || condition == WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
    }

    @Override
    public void paint(Graphics graphics) {
        float alpha = TokenMotion.value(this, FADE, 1f, TokenMotion.fast());
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            if (alpha < 1) {
                g.setComposite(AlphaComposite.SrcOver.derive(Math.max(0, alpha)));
            }
            g.setColor(TokenDialogPane.token("surface.scrim"));
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paint(g);
        } finally {
            g.dispose();
        }
    }
}
