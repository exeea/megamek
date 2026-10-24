/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AWTEvent;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.ui.util.UIUtil;

/**
 * Shows toasts (docs/design-system/tokens.md, Toasts) over a view: wrap the view, then call {@link #show}. Toasts stack
 * at the top centre, {@code lg} from the edge and {@code md} apart, the newest on top; at most {@link #MAX_VISIBLE},
 * so a new one pushes the oldest out at once. Each fades in, stays for its level's time, fades out and leaves; the
 * pointer over a toast pauses it, and its ✕ removes it. Toasts have no pointer listeners of their own, so presses
 * beside the ✕ reach the view underneath.
 */
public final class TokenToasts extends JLayeredPane {
    /** The most toasts shown at once, as on the board today. */
    public static final int MAX_VISIBLE = 5;
    private static final int FRAME_MS = 15;
    /** The least time a toast stays after the pointer leaves it, so it can't vanish under a moving pointer. */
    private static final int MIN_AFTER_HOVER_MS = 1000;

    private final JComponent view;
    /** The toasts shown, newest first. */
    private final List<Entry> entries = new ArrayList<>();
    private final AWTEventListener pointer = event -> {
        if (event instanceof MouseEvent mouse) {
            pointerAt(mouse);
        }
    };

    /** One shown toast and its timing. */
    private final class Entry {
        final TokenToast toast;
        final Timer stay;
        final Timer fade = new Timer(FRAME_MS, null);
        int remaining;
        long stayStart;
        long fadeStart;
        int fadeLength;
        float fadeFrom;
        float fadeTarget;
        boolean hovered;

        Entry(TokenToast toast, int durationMs) {
            this.toast = toast;
            remaining = durationMs;
            stay = new Timer(durationMs, event -> fadeTo(this, 0));
            stay.setRepeats(false);
            fade.addActionListener(event -> step(this));
        }
    }

    /** Holds {@code view}, filling this pane, with toasts over it. */
    public TokenToasts(JComponent view) {
        this.view = view;
        add(view, DEFAULT_LAYER);
    }

    /** Shows {@code text} at {@code level} for the level's time; any thread. */
    public void show(TokenToast.Level level, String text) {
        show(level, text, null);
    }

    /** Shows {@code text} at {@code level} with the acting unit's sprite, which may be null; any thread. */
    public void show(TokenToast.Level level, String text, Image unit) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> show(level, text, unit));
            return;
        }
        show(new TokenToast(level, text, unit), level.durationMs());
    }

    /** Shows {@code toast} for {@code durationMs} once it has faded in. */
    public void show(TokenToast toast, int durationMs) {
        while (entries.size() >= MAX_VISIBLE) {
            drop(entries.get(entries.size() - 1));
        }
        Entry entry = new Entry(toast, durationMs);
        toast.onClose(() -> drop(entry));
        toast.applyComponentOrientation(getComponentOrientation());
        toast.setOpacity(0);
        entries.add(0, entry);
        add(toast, PALETTE_LAYER);
        revalidate();
        repaint();
        fadeTo(entry, 1);
        AccessibleContext context = toast.getAccessibleContext();
        context.firePropertyChange(AccessibleContext.ACCESSIBLE_NAME_PROPERTY, null, context.getAccessibleName());
    }

    /** The toasts shown, newest first. */
    public List<TokenToast> shown() {
        List<TokenToast> toasts = new ArrayList<>();
        for (Entry entry : entries) {
            toasts.add(entry.toast);
        }
        return toasts;
    }

    /** Removes every toast at once. */
    public void clear() {
        while (!entries.isEmpty()) {
            drop(entries.get(0));
        }
    }

    private void fadeTo(Entry entry, float target) {
        entry.stay.stop();
        entry.fadeFrom = entry.toast.opacity();
        entry.fadeTarget = target;
        entry.fadeStart = System.currentTimeMillis();
        entry.fadeLength = TokenMotion.isReduced() ? 0 : target > 0 ? TokenMotion.medium() : TokenMotion.slow();
        if (entry.fadeLength == 0) {
            finishFade(entry);
        } else {
            entry.fade.start();
        }
    }

    private void step(Entry entry) {
        float t = (System.currentTimeMillis() - entry.fadeStart) / (float) entry.fadeLength;
        if (t >= 1) {
            finishFade(entry);
            return;
        }
        float eased = TokenMotion.ease(t);
        entry.toast.setOpacity(entry.fadeFrom + (entry.fadeTarget - entry.fadeFrom) * eased);
    }

    private void finishFade(Entry entry) {
        entry.fade.stop();
        entry.toast.setOpacity(entry.fadeTarget);
        if (entry.fadeTarget <= 0) {
            drop(entry);
        } else if (!entry.hovered) {
            startStay(entry);
        }
    }

    private void startStay(Entry entry) {
        entry.stay.setInitialDelay(Math.max(1, entry.remaining));
        entry.stayStart = System.currentTimeMillis();
        entry.stay.restart();
    }

    private void drop(Entry entry) {
        entry.stay.stop();
        entry.fade.stop();
        if (entries.remove(entry)) {
            remove(entry.toast);
            revalidate();
            repaint();
        }
    }

    /** Pauses the toast under the pointer, bringing it back if it was fading out, and resumes the others. */
    private void pointerAt(MouseEvent event) {
        if (!isShowing() || entries.isEmpty()) {
            return;
        }
        boolean outside = event.getID() == MouseEvent.MOUSE_EXITED
              && event.getComponent() != null && SwingUtilities.getWindowAncestor(event.getComponent()) == null;
        Point screen = event.getLocationOnScreen();
        for (Entry entry : new ArrayList<>(entries)) {
            boolean over = false;
            if (!outside && entry.toast.isShowing()) {
                Point origin = entry.toast.getLocationOnScreen();
                over = new Rectangle(origin, entry.toast.getSize()).contains(screen);
            }
            if (over == entry.hovered) {
                continue;
            }
            entry.hovered = over;
            if (over) {
                if (entry.stay.isRunning()) {
                    entry.remaining -= (int) (System.currentTimeMillis() - entry.stayStart);
                    entry.stay.stop();
                }
                if (entry.fadeTarget <= 0) {
                    entry.remaining = 0;
                    fadeTo(entry, 1);
                }
            } else if (!entry.fade.isRunning()) {
                entry.remaining = Math.max(entry.remaining, MIN_AFTER_HOVER_MS);
                startStay(entry);
            } else {
                entry.remaining = Math.max(entry.remaining, MIN_AFTER_HOVER_MS);
            }
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        Toolkit.getDefaultToolkit().addAWTEventListener(pointer,
              AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
    }

    @Override
    public void removeNotify() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(pointer);
        for (Entry entry : entries) {
            entry.stay.stop();
            entry.fade.stop();
        }
        super.removeNotify();
    }

    @Override
    public void doLayout() {
        view.setBounds(0, 0, getWidth(), getHeight());
        int y = TokenDialogPane.space("lg");
        int room = getWidth() - 2 * TokenDialogPane.space("lg");
        for (Entry entry : entries) {
            Dimension size = entry.toast.getPreferredSize();
            int width = Math.min(size.width, Math.max(UIUtil.scaleForGUI(120), room));
            int height = width == size.width ? size.height : entry.toast.heightFor(width);
            entry.toast.setBounds((getWidth() - width) / 2, y, width, height);
            y += height + TokenDialogPane.space("md");
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return isPreferredSizeSet() ? super.getPreferredSize() : view.getPreferredSize();
    }

    @Override
    public Dimension getMinimumSize() {
        return isMinimumSizeSet() ? super.getMinimumSize() : view.getMinimumSize();
    }
}
