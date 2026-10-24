/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;

import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * A badge (docs/design-system/tokens.md, Chips and badges): a count, or a dot, on the top-right corner of a button or
 * tab. A count is an {@code icon.sm} (16) high pill, at least as wide as it is high, with {@code caption} digits at
 * weight 500 in {@code text.onAccent}; above 99 it shows "99+". A dot is {@code md} (8) across. Both stand inside an
 * {@code xxs} (2) ring of the surface they sit on, so they read apart from the button.
 * <p>
 * {@link #attach} seats the badge on a component; a count of 0 hides it. Tell screen readers what the count means with
 * the target's accessible description, such as "3 unread messages".
 */
public class TokenBadge extends JComponent {
    /** The most a count shows before it reads "99+". */
    public static final int MAX_SHOWN = 99;

    /** The fill: accent by default, a status when the count means one. */
    public enum Tone {
        ACCENT("accent.primary"),
        DANGER("status.danger"),
        WARNING("status.warning"),
        SUCCESS("status.success");

        private final String role;

        Tone(String role) {
            this.role = role;
        }

        Color color() {
            return TokenDialogPane.token(role);
        }
    }

    private final boolean dot;
    private Tone tone = Tone.ACCENT;
    private int count;

    private TokenBadge(boolean dot, int count) {
        this.dot = dot;
        setOpaque(false);
        setCount(count);
    }

    /** A count badge showing {@code count}; 0 or less hides it. */
    public static TokenBadge count(int count) {
        return new TokenBadge(false, count);
    }

    /** A dot badge, for "something new" without a number. */
    public static TokenBadge dot() {
        return new TokenBadge(true, 1);
    }

    public TokenBadge tone(Tone tone) {
        this.tone = tone;
        repaint();
        return this;
    }

    public Tone tone() {
        return tone;
    }

    public int getCount() {
        return count;
    }

    /** Shows {@code count}, or hides the badge at 0 or less; a dot shows for any count above 0. */
    public void setCount(int count) {
        this.count = count;
        setVisible(count > 0);
        Container parent = getParent();
        if (parent != null) {
            parent.revalidate();
        }
        repaint();
    }

    /** The text a count shows. */
    String shown() {
        return count > MAX_SHOWN ? MAX_SHOWN + "+" : Integer.toString(count);
    }

    private static Font font() {
        // caption's size at label's weight: TokenFonts has no medium caption, and label shares its tracking.
        return TokenFonts.font("label").deriveFont(TokenFonts.font("caption").getSize2D());
    }

    private static int ring() {
        return TokenDialogPane.space("xxs");
    }

    /** The badge's size with its ring. */
    @Override
    public Dimension getPreferredSize() {
        int ring = ring();
        if (dot) {
            int side = TokenDialogPane.space("md") + 2 * ring;
            return new Dimension(side, side);
        }
        int height = TokenChips.scaledSize("icon.sm");
        int width = Math.max(height, getFontMetrics(font()).stringWidth(shown()) + 2 * TokenDialogPane.space("xs"));
        return new Dimension(width + 2 * ring, height + 2 * ring);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            int ring = ring();
            g.setColor(surface(this));
            g.fill(new RoundRectangle2D.Float(0, 0, width, height, height, height));
            g.setColor(tone.color());
            g.fill(new RoundRectangle2D.Float(ring, ring, width - 2f * ring, height - 2f * ring, height - 2f * ring,
                  height - 2f * ring));
            if (!dot) {
                FlatUIUtils.setRenderingHints(g);
                Font font = font();
                FontMetrics metrics = getFontMetrics(font);
                String text = shown();
                g.setFont(font);
                g.setColor(TokenDialogPane.token("text.onAccent"));
                FlatUIUtils.drawString(this, g, text, (width - metrics.stringWidth(text)) / 2,
                      (height - metrics.getHeight()) / 2 + metrics.getAscent());
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * The surface under the badge, for its ring: a framed {@link TokenPanel}'s {@code surface.panel}, else the first
     * opaque ancestor's background, else {@code surface.base}.
     */
    static Color surface(Component component) {
        for (Component c = component.getParent(); c != null; c = c.getParent()) {
            if (c instanceof TokenPanel panel && !panel.isPlain()) {
                return TokenDialogPane.token("surface.panel");
            }
            if (c.isOpaque() && c.getBackground() != null) {
                return c.getBackground();
            }
        }
        return TokenDialogPane.token("surface.base");
    }

    /**
     * Seats {@code badge} on {@code target}'s top-right corner (top-left in right-to-left), its centre {@code xs} (4)
     * inside the corner, or on the glyph's corner for an icon-only button, and returns the holder to add in the
     * target's place. The holder leaves room for the part of the badge that stands out of the target, so nothing is
     * clipped.
     */
    public static JComponent attach(JComponent target, TokenBadge badge) {
        return new Anchor(target, badge);
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.LABEL;
                }

                @Override
                public String getAccessibleName() {
                    String name = super.getAccessibleName();
                    return name != null || dot ? name : shown();
                }
            };
        }
        return accessibleContext;
    }

    /** Holds a target and the badge over its corner; the badge is painted last and takes no mouse events. */
    private static final class Anchor extends JComponent {
        private final JComponent target;
        private final TokenBadge badge;

        Anchor(JComponent target, TokenBadge badge) {
            this.target = target;
            this.badge = badge;
            setOpaque(false);
            // The first child paints on top.
            add(badge);
            add(target);
        }

        @Override
        public boolean isOptimizedDrawingEnabled() {
            return false;
        }

        /** The badge's bounds for a target at {@code 0, 0} of {@code width} by {@code height}. */
        private Rectangle badgeBounds(int width, int height) {
            Dimension size = badge.getPreferredSize();
            boolean leftToRight = getComponentOrientation().isLeftToRight();
            int centreX;
            int centreY;
            Icon icon = target instanceof AbstractButton button && (button.getText() == null
                  || button.getText().isEmpty()) ? button.getIcon() : null;
            if (icon != null) {
                // An icon-only button shows no box of its own, so the badge sits on the glyph's corner.
                int left = (width - icon.getIconWidth()) / 2;
                centreX = leftToRight ? left + icon.getIconWidth() : left;
                centreY = (height - icon.getIconHeight()) / 2;
            } else {
                int inset = TokenDialogPane.space("xs");
                centreX = leftToRight ? width - inset : inset;
                centreY = inset;
            }
            return new Rectangle(centreX - size.width / 2, centreY - size.height / 2, size.width,
                  size.height);
        }

        /** How far the badge stands above and beside the target. */
        private Insets overhang(int width, int height) {
            Rectangle bounds = badgeBounds(width, height);
            return new Insets(Math.max(0, -bounds.y), Math.max(0, -bounds.x), 0,
                  Math.max(0, bounds.x + bounds.width - width));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = target.getPreferredSize();
            Insets out = overhang(size.width, size.height);
            return new Dimension(size.width + out.left + out.right, size.height + out.top);
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public void doLayout() {
            Dimension size = target.getPreferredSize();
            Insets out = overhang(size.width, size.height);
            int width = getWidth() - out.left - out.right;
            int height = getHeight() - out.top;
            target.setBounds(out.left, out.top, width, height);
            Rectangle bounds = badgeBounds(width, height);
            bounds.translate(out.left, out.top);
            badge.setBounds(bounds);
        }
    }
}
