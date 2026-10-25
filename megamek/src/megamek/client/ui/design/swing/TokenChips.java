/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import javax.accessibility.AccessibleAction;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.Messages;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * Chips (docs/design-system/tokens.md, Chips and badges): small rounded labels in the {@code small} text style.
 * <ul>
 *     <li>A {@link StatusChip} reports a state, such as "Engine hit": the status icon and edge in the status color.</li>
 *     <li>A {@link FilterChip} is a toggle that filters a view; {@link #selectOne} makes a group with one on.</li>
 *     <li>A {@link TagChip} is a value the user added and can remove with its ✕, Delete or Backspace.</li>
 * </ul>
 * Badges, the counts and dots on buttons, are {@link TokenBadge}.
 */
public final class TokenChips {
    /** Client property freezing a filter or tag chip in {@link #FROZEN_HOVER} or {@link #FROZEN_FOCUS}. */
    static final String FROZEN_STATE = "MegaMek.chip.frozenState";
    static final String FROZEN_HOVER = "hover";
    static final String FROZEN_FOCUS = "focus";
    private static final int ICON_CHECK = 0xE5CA;
    private static final int ICON_CLOSE = 0xE5CD;
    private static final String EDGE = "chip.edge";
    private static final String FILL = "chip.fill";
    private static final String REMOVE = "chip.remove";

    private TokenChips() {
    }

    /** What a status chip reports; every tone but {@link #NEUTRAL} shows its status icon, so color is never alone. */
    public enum Tone {
        NEUTRAL(0, null, null),
        INFO(0xE88E, "status.info", "TokenChips.tone.info"),
        SUCCESS(0xE86C, "status.success", "TokenChips.tone.success"),
        WARNING(0xE002, "status.warning", "TokenChips.tone.warning"),
        DANGER(0xE000, "status.danger", "TokenChips.tone.danger");

        private final int glyph;
        private final String role;
        private final String nameKey;

        Tone(int glyph, String role, String nameKey) {
            this.glyph = glyph;
            this.role = role;
            this.nameKey = nameKey;
        }

        /** The edge color of the active theme. */
        Color edge() {
            return TokenDialogPane.token(role == null ? "border.control" : role);
        }

        Icon icon() {
            return glyph == 0 ? null : FontHandler.symbolIcon(glyph, scaledSize("icon.sm"), edge());
        }

        /** The tone's name for screen readers, or null for NEUTRAL. */
        String spokenName() {
            return nameKey == null ? null : Messages.getString(nameKey);
        }
    }

    /** A read-only chip reporting {@code text} in {@code tone}. */
    public static StatusChip status(String text, Tone tone) {
        return new StatusChip(text, tone);
    }

    /** A filter chip, off. */
    public static FilterChip filter(String text) {
        return new FilterChip(text);
    }

    /** A removable tag chip; {@code onRemove}, which may be null, runs after the chip has left its container. */
    public static TagChip tag(String text, Runnable onRemove) {
        return new TagChip(text, onRemove);
    }

    /** Groups {@code chips} so that exactly one is on, like radio buttons; the first is turned on if none is. */
    public static void selectOne(FilterChip... chips) {
        ButtonGroup group = new ButtonGroup();
        boolean any = false;
        for (FilterChip chip : chips) {
            group.add(chip);
            chip.grouped = true;
            any |= chip.isSelected();
        }
        if (!any && chips.length > 0) {
            chips[0].setSelected(true);
        }
    }

    static int scaledSize(String token) {
        return Math.max(1, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get(token))));
    }

    private static int radius() {
        return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().radius().get("md")));
    }

    private static boolean frozen(JComponent component, String state) {
        return state.equals(component.getClientProperty(FROZEN_STATE));
    }

    private static boolean showsFocus(JComponent component) {
        return component.isEnabled() && (frozen(component, FROZEN_FOCUS) || FocusVisibility.shows(component));
    }

    /**
     * Paints a chip's shape over {@code 0, 0, w, h}: {@code fill}, then an {@code edgeWidth} wide edge inside the
     * bounds, both with the {@code radius.md} corner. A null color skips its part.
     */
    static void paintShape(Graphics2D g, int w, int h, Color fill, Color edge, int edgeWidth) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float arc = radius() * 2f;
        RoundRectangle2D outer = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
        if (fill != null) {
            g.setColor(fill);
            g.fill(outer);
        }
        if (edge != null && edgeWidth > 0) {
            Area ring = new Area(outer);
            float innerArc = Math.max(0, arc - 2 * edgeWidth);
            ring.subtract(new Area(new RoundRectangle2D.Float(edgeWidth, edgeWidth, w - 2f * edgeWidth,
                  h - 2f * edgeWidth, innerArc, innerArc)));
            g.setColor(edge);
            g.fill(ring);
        }
    }

    private static void drawText(JComponent component, Graphics2D g, String text, int x, int height, Color color) {
        FlatUIUtils.setRenderingHints(g);
        FontMetrics metrics = component.getFontMetrics(component.getFont());
        g.setFont(component.getFont());
        g.setColor(color);
        FlatUIUtils.drawString(component, g, text, x, (height - metrics.getHeight()) / 2 + metrics.getAscent());
    }

    private static void repaintOnFocus(JComponent component) {
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                component.repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                component.repaint();
            }
        });
    }

    /**
     * A read-only chip, {@code icon.sm} plus {@code xxs} above and below high (20): the tone's icon and a 1px edge in
     * the status color, the text in {@code small} and {@code text.primary}. A neutral chip has a {@code border.control}
     * edge, {@code text.secondary} text and no icon.
     */
    public static final class StatusChip extends JLabel {
        private final Tone tone;

        private StatusChip(String text, Tone tone) {
            super(text, SwingConstants.LEADING);
            this.tone = tone;
            setOpaque(false);
            applyStyle();
            String spoken = tone.spokenName();
            if (spoken != null) {
                getAccessibleContext().setAccessibleDescription(spoken);
            }
        }

        public Tone tone() {
            return tone;
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (tone != null) {
                applyStyle();
            }
        }

        private void applyStyle() {
            setFont(TokenFonts.font("small"));
            setForeground(TokenDialogPane.token(tone == Tone.NEUTRAL ? "text.secondary" : "text.primary"));
            setIcon(tone.icon());
            setIconTextGap(TokenDialogPane.space("xs"));
            int leading = TokenDialogPane.space(tone == Tone.NEUTRAL ? "md" : "xs");
            int trailing = TokenDialogPane.space("md");
            boolean leftToRight = getComponentOrientation().isLeftToRight();
            setBorder(BorderFactory.createEmptyBorder(0, leftToRight ? leading : trailing, 0,
                  leftToRight ? trailing : leading));
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width, Math.max(size.height, height()));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        private static int height() {
            return scaledSize("icon.sm") + 2 * TokenDialogPane.space("xxs");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                paintShape(g, getWidth(), getHeight(), null, tone.edge(), TokenFrame.line());
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /**
     * A toggle that filters a view, {@code control.sm} (28) high. At rest a {@code border.control} edge and
     * {@code text.secondary}; hover a {@code surface.hover} wash, a {@code border.strong} edge and {@code text.primary};
     * on, the inverse face and edge with a leading check, all in {@code text.onInverse}, hover and press leaning toward
     * that color like the primary button; disabled and on, a faint inverse face. Keyboard focus draws the
     * {@code focus.ring} in place of the edge, or around an on chip a gap away ({@link FocusHalo}) when that fits, else
     * inside it as two lines in the text colors.
     */
    public static final class FilterChip extends JToggleButton {
        private boolean grouped;

        private FilterChip(String text) {
            super(text);
            setRolloverEnabled(true);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            addItemListener(event -> {
                revalidate();
                repaint();
            });
            repaintOnFocus(this);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setFont(TokenFonts.font("small"));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
        }

        private int checkRoom() {
            return isSelected() ? scaledSize("icon.sm") + TokenDialogPane.space("xs") : 0;
        }

        private int leading() {
            return TokenDialogPane.space(isSelected() ? "md" : "lg");
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics metrics = getFontMetrics(getFont());
            int width = leading() + checkRoom() + metrics.stringWidth(getText()) + TokenDialogPane.space("lg");
            return new Dimension(width, scaledSize("control.sm"));
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        private boolean hovered() {
            return isEnabled() && (getModel().isRollover() || frozen(this, FROZEN_HOVER));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                int width = getWidth();
                int height = getHeight();
                boolean enabled = isEnabled();
                boolean on = isSelected();
                boolean hovered = hovered();
                int duration = TokenMotion.buttonState(this);
                Color fill = on ? onFace(enabled, hovered, enabled && getModel().isPressed())
                      : hovered ? TokenDialogPane.token("surface.hover") : null;
                paintShape(g, width, height, TokenMotion.color(this, FILL, fill, duration), null, 0);
                boolean focused = showsFocus(this);
                float arc = radius() * 2f;
                if (focused && on && FocusHalo.around(this, new RoundRectangle2D.Float(0, 0, width, height, arc, arc))) {
                    paintShape(g, width, height, null, TokenMotion.color(this, EDGE, edge(enabled, on, hovered),
                          duration), TokenFrame.line());
                } else if (focused && on) {
                    // No room outside: the label color inside a primary-text edge, as on the primary button
                    int line = TokenFrame.line();
                    paintShape(g, width, height, null, TokenDialogPane.token("text.primary"), line);
                    Graphics2D inner = (Graphics2D) g.create(line, line, width - 2 * line, height - 2 * line);
                    try {
                        paintShape(inner, width - 2 * line, height - 2 * line, null,
                              TokenDialogPane.token("text.onInverse"), line);
                    } finally {
                        inner.dispose();
                    }
                } else if (focused) {
                    FocusHalo.clear(this);
                    paintShape(g, width, height, null, TokenDialogPane.token("focus.ring"), scaledSize("focus.width"));
                } else {
                    FocusHalo.clear(this);
                    paintShape(g, width, height, null, TokenMotion.color(this, EDGE, edge(enabled, on, hovered),
                          duration), TokenFrame.line());
                }
                Color ink = TokenDialogPane.token(on ? "text.onInverse" : !enabled ? "text.disabled"
                      : hovered ? "text.primary" : "text.secondary");
                boolean leftToRight = getComponentOrientation().isLeftToRight();
                int textWidth = getFontMetrics(getFont()).stringWidth(getText());
                int start = leading();
                if (on) {
                    Icon check = FontHandler.symbolIcon(ICON_CHECK, scaledSize("icon.sm"), ink);
                    int x = leftToRight ? start : width - start - check.getIconWidth();
                    check.paintIcon(this, g, x, (height - check.getIconHeight()) / 2);
                }
                int textX = leftToRight ? start + checkRoom() : width - start - checkRoom() - textWidth;
                drawText(this, g, getText(), textX, height, ink);
            } finally {
                g.dispose();
            }
        }

        private static Color edge(boolean enabled, boolean on, boolean hovered) {
            if (on && !enabled) {
                return null;
            }
            if (!enabled) {
                return TokenDialogPane.token("border.subtle");
            }
            if (on) {
                return TokenDialogPane.token(hovered ? "border.strong" : "surface.inverse");
            }
            return TokenDialogPane.token(hovered ? "border.strong" : "border.control");
        }

        /** An on chip's face: the inverse color, leaning toward its label under the pointer; faint when disabled. */
        private static Color onFace(boolean enabled, boolean hovered, boolean pressed) {
            if (!enabled) {
                return TokenDialogPane.token("surface.inverseDisabled");
            }
            Color inverse = TokenDialogPane.token("surface.inverse");
            if (!hovered && !pressed) {
                return inverse;
            }
            DesignTokens tokens = DesignTokens.shipped();
            int lean = ColorMath.withAlpha(TokenDialogPane.token("text.onInverse").getRGB(),
                  tokens.alpha(pressed ? "tint.pressed" : "tint.hover"));
            return new Color(ColorMath.over(lean, inverse.getRGB()), true);
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJToggleButton() {
                    @Override
                    public AccessibleRole getAccessibleRole() {
                        return grouped ? AccessibleRole.RADIO_BUTTON : AccessibleRole.TOGGLE_BUTTON;
                    }
                };
            }
            return accessibleContext;
        }
    }

    /**
     * A value the user added, {@code control.sm} (28) high: {@code text.primary} in a {@code border.control} edge, then
     * a ✕ that removes it. The ✕ takes a {@code surface.hover} wash under the pointer; with the focus on the chip,
     * Delete or Backspace removes it too.
     */
    public static final class TagChip extends JComponent {
        private final String text;
        private final Runnable onRemove;
        private boolean hoverRemove;

        private TagChip(String text, Runnable onRemove) {
            this.text = text;
            this.onRemove = onRemove;
            setFocusable(true);
            setOpaque(false);
            setFont(TokenFonts.font("small"));
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), REMOVE);
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), REMOVE);
            getActionMap().put(REMOVE, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent event) {
                    removeTag();
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    setHoverRemove(removeBounds().contains(event.getPoint()));
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    setHoverRemove(false);
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    if (isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
                        requestFocusInWindow();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event) && removeBounds().contains(event.getPoint())) {
                        removeTag();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            repaintOnFocus(this);
            // Registers the chip so that the ✕ alone shows its tooltip.
            setToolTipText(Messages.getString("TokenChips.remove", text));
        }

        public String text() {
            return text;
        }

        /** Removes the chip from its container, moving the focus on first when it has it, then runs the listener. */
        public void removeTag() {
            if (!isEnabled()) {
                return;
            }
            if (isFocusOwner()) {
                transferFocus();
            }
            Container parent = getParent();
            if (parent != null) {
                parent.remove(this);
                parent.revalidate();
                parent.repaint();
            }
            if (onRemove != null) {
                onRemove.run();
            }
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            return isEnabled() && removeBounds().contains(event.getPoint()) ? super.getToolTipText(event) : null;
        }

        private void setHoverRemove(boolean hover) {
            if (hoverRemove != hover) {
                hoverRemove = hover;
                repaint();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setFocusable(enabled);
            repaint();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setFont(TokenFonts.font("small"));
        }

        /** The ✕'s square: the chip's height less {@code xs} above and below, {@code xs} from the trailing edge. */
        private Rectangle removeBounds() {
            int inset = TokenDialogPane.space("xs");
            int side = getHeight() - 2 * inset;
            int x = getComponentOrientation().isLeftToRight() ? getWidth() - inset - side : inset;
            return new Rectangle(x, inset, side, side);
        }

        @Override
        public Dimension getPreferredSize() {
            int height = scaledSize("control.sm");
            int inset = TokenDialogPane.space("xs");
            int width = TokenDialogPane.space("lg") + getFontMetrics(getFont()).stringWidth(text)
                  + TokenDialogPane.space("xxs") + (height - 2 * inset) + inset;
            return new Dimension(width, height);
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
                int width = getWidth();
                int height = getHeight();
                boolean enabled = isEnabled();
                if (showsFocus(this)) {
                    paintShape(g, width, height, null, TokenDialogPane.token("focus.ring"), scaledSize("focus.width"));
                } else {
                    paintShape(g, width, height, null,
                          TokenDialogPane.token(enabled ? "border.control" : "border.subtle"), TokenFrame.line());
                }
                Rectangle remove = removeBounds();
                boolean hovered = enabled && (hoverRemove || frozen(this, FROZEN_HOVER));
                Color wash = TokenMotion.color(this, FILL, hovered ? TokenDialogPane.token("surface.hover") : null,
                      TokenMotion.hover(hovered));
                if (wash != null) {
                    Graphics2D w = (Graphics2D) g.create(remove.x, remove.y, remove.width, remove.height);
                    try {
                        w.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        w.setColor(wash);
                        float arc = UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().radius().get("sm"))) * 2f;
                        w.fill(new RoundRectangle2D.Float(0, 0, remove.width, remove.height, arc, arc));
                    } finally {
                        w.dispose();
                    }
                }
                Color cross = TokenDialogPane.token(!enabled ? "text.disabled"
                      : hovered ? "text.primary" : "text.muted");
                Icon close = FontHandler.symbolIcon(ICON_CLOSE, scaledSize("icon.sm"), cross);
                close.paintIcon(this, g, remove.x + (remove.width - close.getIconWidth()) / 2,
                      remove.y + (remove.height - close.getIconHeight()) / 2);
                int textWidth = getFontMetrics(getFont()).stringWidth(text);
                int lead = TokenDialogPane.space("lg");
                int textX = getComponentOrientation().isLeftToRight() ? lead : width - lead - textWidth;
                drawText(this, g, text, textX, height, TokenDialogPane.token(enabled ? "text.primary"
                      : "text.disabled"));
            } finally {
                g.dispose();
            }
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleTagChip();
            }
            return accessibleContext;
        }

        /** A push button named "Remove {tag}" whose one action removes the chip. */
        private final class AccessibleTagChip extends AccessibleJComponent implements AccessibleAction {
            @Override
            public String getAccessibleName() {
                String name = super.getAccessibleName();
                return name != null ? name : Messages.getString("TokenChips.remove", text);
            }

            @Override
            public AccessibleRole getAccessibleRole() {
                return AccessibleRole.PUSH_BUTTON;
            }

            @Override
            public AccessibleAction getAccessibleAction() {
                return this;
            }

            @Override
            public int getAccessibleActionCount() {
                return 1;
            }

            @Override
            public String getAccessibleActionDescription(int index) {
                return index == 0 ? Messages.getString("TokenChips.remove", text) : null;
            }

            @Override
            public boolean doAccessibleAction(int index) {
                if (index != 0 || !isEnabled()) {
                    return false;
                }
                removeTag();
                return true;
            }
        }
    }
}
