/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice.WindowTranslucency;
import java.awt.Window;
import javax.swing.JWindow;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Fades popups, menus and tooltips in ({@code fast}, docs/design-system/tokens.md, Motion). It wraps the popup factory
 * FlatLaf installs and eases the opacity of popup windows as they open. A popup that fits inside its window is
 * lightweight and has no window of its own, so it appears at once; so does one whose screen cannot draw translucent
 * windows, and every popup while motion is reduced. A popup window that is already showing, such as a tooltip following
 * the pointer, is reused without fading again.
 */
final class TokenPopupFactory extends PopupFactory {
    private static PopupFactory previous;

    private final PopupFactory delegate;

    private TokenPopupFactory(PopupFactory delegate) {
        this.delegate = delegate;
    }

    /** Wraps the current popup factory; call after FlatLaf has installed its own. */
    static void install() {
        PopupFactory current = PopupFactory.getSharedInstance();
        if (!(current instanceof TokenPopupFactory)) {
            previous = current;
            PopupFactory.setSharedInstance(new TokenPopupFactory(current));
        }
    }

    /** Restores the wrapped factory, before FlatLaf restores the one it replaced. */
    static void uninstall() {
        if (previous != null && PopupFactory.getSharedInstance() instanceof TokenPopupFactory) {
            PopupFactory.setSharedInstance(previous);
        }
        previous = null;
    }

    @Override
    public Popup getPopup(Component owner, Component contents, int x, int y) {
        return new Fading(delegate.getPopup(owner, contents, x, y), contents);
    }

    private static final class Fading extends Popup {
        private final Popup popup;
        private final Component contents;
        private Timer timer;

        private Fading(Popup popup, Component contents) {
            this.popup = popup;
            this.contents = contents;
        }

        @Override
        public void show() {
            Window window = SwingUtilities.getWindowAncestor(contents);
            boolean fade = !TokenMotion.isReduced() && window != null && !window.isVisible() && isTranslucent(window);
            if (fade) {
                window.setOpacity(0);
            }
            popup.show();
            if (fade) {
                fadeIn(window);
            }
        }

        @Override
        public void hide() {
            Window window = SwingUtilities.getWindowAncestor(contents);
            stop(window);
            popup.hide();
        }

        private void fadeIn(Window window) {
            int duration = TokenMotion.fast();
            long start = System.nanoTime();
            timer = new Timer(16, event -> {
                float t = Math.min(1, (System.nanoTime() - start) / 1_000_000f / duration);
                if (t >= 1 || !window.isShowing()) {
                    stop(window);
                } else {
                    window.setOpacity(TokenMotion.ease(t));
                }
            });
            timer.start();
        }

        /** Stops fading and leaves the window opaque, since popup windows are cached and reused. */
        private void stop(Window window) {
            if (timer != null) {
                timer.stop();
                timer = null;
                if (window != null && isTranslucent(window)) {
                    window.setOpacity(1);
                }
            }
        }

        private static boolean isTranslucent(Window window) {
            GraphicsConfiguration configuration = window.getGraphicsConfiguration();
            return window instanceof JWindow && configuration != null
                  && configuration.getDevice().isWindowTranslucencySupported(WindowTranslucency.TRANSLUCENT);
        }
    }
}
