/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;

import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * A toast (docs/design-system/tokens.md, Toasts): a short notice on the popover surface with a bar and an icon in its
 * level's color, an optional unit sprite, its text wrapped at {@link #MAX_WIDTH} and a ✕. It never takes the focus.
 * {@link TokenToasts} shows, times and stacks toasts over a view; a toast on its own is static, as in the gallery.
 */
public final class TokenToast extends JComponent {
    /** The widest a toast grows before its text wraps, unscaled. */
    public static final int MAX_WIDTH = 480;
    private static final int BAR = 3;
    private static final int ICON_CLOSE = 0xE5CD;
    private static final int SPRITE_WIDTH = 40;
    private static final int SPRITE_HEIGHT = 34;

    /** How urgent a toast is: its color, icon, name and how much longer than the player's base time it stays. */
    public enum Level {
        INFO(0xE88E, "status.info", "TokenToast.level.info", 0),
        SUCCESS(0xE86C, "status.success", "TokenToast.level.success", 0),
        WARNING(0xE002, "status.warning", "TokenToast.level.warning", 1),
        DANGER(0xE000, "status.danger", "TokenToast.level.danger", 2),
        /** A Game Master's action: violet with its own icon, so it reads as the GM's hand, not a game outcome. */
        GAMEMASTER(0xF650, "selected.primary", "TokenToast.level.gamemaster", 1);

        private final int glyph;
        private final String role;
        private final String nameKey;
        private final int extraSeconds;

        Level(int glyph, String role, String nameKey, int extraSeconds) {
            this.glyph = glyph;
            this.role = role;
            this.nameKey = nameKey;
            this.extraSeconds = extraSeconds;
        }

        /** The level's color in the active theme. */
        Color color() {
            return TokenDialogPane.token(role);
        }

        /** The level's name for screen readers. */
        public String spokenName() {
            return Messages.getString(nameKey);
        }

        /** The player's toast time (at least a second) plus this level's extra seconds, in milliseconds. */
        public int durationMs() {
            int base = Math.max(1, GUIPreferences.getInstance().getToastDurationSeconds());
            return (base + extraSeconds) * 1000;
        }
    }

    private final Level level;
    private final String text;
    private final Image unit;
    private final AbstractButton close;
    private Runnable onClose;
    private float opacity = 1;

    /** A toast reporting {@code text} at {@code level}; {@code unit}, which may be null, is the acting unit's sprite. */
    public TokenToast(Level level, String text, Image unit) {
        this.level = level;
        this.text = text;
        this.unit = unit;
        setOpaque(false);
        setLayout(null);
        setFont(TokenFonts.font("body"));
        close = TokenButtons.icon(ICON_CLOSE, Messages.getString("TokenToast.close"), false,
              TokenButtons.Size.COMPACT);
        close.setFocusable(false);
        close.addActionListener(event -> {
            if (onClose != null) {
                onClose.run();
            }
        });
        add(close);
    }

    public Level level() {
        return level;
    }

    public String text() {
        return text;
    }

    /** Runs {@code action} when the user presses the ✕. */
    public void onClose(Runnable action) {
        onClose = action;
    }

    /** Fades the toast and its ✕ to {@code value} (0 to 1). */
    void setOpacity(float value) {
        opacity = Math.max(0, Math.min(1, value));
        repaint();
    }

    float opacity() {
        return opacity;
    }

    private static int space(String name) {
        return TokenDialogPane.space(name);
    }

    private static int scaled(int value) {
        return UIUtil.scaleForGUI(value);
    }

    private int closeSide() {
        return close.getPreferredSize().height;
    }

    private Dimension spriteSize() {
        if (unit == null || unit.getWidth(null) <= 0 || unit.getHeight(null) <= 0) {
            return new Dimension(0, 0);
        }
        double fit = Math.min(scaled(SPRITE_WIDTH) / (double) unit.getWidth(null),
              scaled(SPRITE_HEIGHT) / (double) unit.getHeight(null));
        return new Dimension((int) Math.round(unit.getWidth(null) * fit),
              (int) Math.round(unit.getHeight(null) * fit));
    }

    /** Everything left of the text: the bar, the padding, the icon and the sprite with their gaps. */
    private int leading() {
        int width = scaled(BAR) + space("lg") + TokenChips.scaledSize("icon.md") + space("md");
        Dimension sprite = spriteSize();
        return sprite.width > 0 ? width + sprite.width + space("md") : width;
    }

    /** Everything right of the text: a gap, the ✕ and the inset after it. */
    private int trailing() {
        return space("xs") + closeSide() + space("xs");
    }

    /** {@code text} broken at spaces into lines no wider than {@code width}; a longer word gets a line of its own. */
    private List<String> lines(int width) {
        FontMetrics metrics = getFontMetrics(getFont());
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && metrics.stringWidth(candidate) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        lines.add(line.toString());
        return lines;
    }

    private int textRoom(int width) {
        return Math.max(scaled(40), width - leading() - trailing());
    }

    /** The height of the first row: a line of text or the icon, whichever is taller. */
    private int firstRow() {
        return Math.max(getFontMetrics(getFont()).getHeight(), TokenChips.scaledSize("icon.md"));
    }

    /** The height the toast needs at {@code width}, its text wrapped to fit. */
    int heightFor(int width) {
        int textHeight = lines(textRoom(width)).size() * getFontMetrics(getFont()).getHeight();
        int content = Math.max(Math.max(textHeight, firstRow()), spriteSize().height);
        return Math.max(content + 2 * space("md"), closeSide() + 2 * space("xs"));
    }

    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        int natural = leading() + getFontMetrics(getFont()).stringWidth(text) + trailing();
        int width = Math.min(natural, scaled(MAX_WIDTH));
        return new Dimension(width, heightFor(width));
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /** Mirrors {@code x} for a part {@code width} wide in a right-to-left toast. */
    private int mirror(int x, int width) {
        return getComponentOrientation().isLeftToRight() ? x : getWidth() - x - width;
    }

    private int closeTop() {
        return Math.max(space("xs"), space("md") + (firstRow() - closeSide()) / 2);
    }

    @Override
    public void doLayout() {
        int side = closeSide();
        close.setBounds(mirror(getWidth() - space("xs") - side, side), closeTop(), side, side);
    }

    @Override
    public void paint(Graphics graphics) {
        if (opacity >= 1) {
            super.paint(graphics);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            super.paint(g);
        } finally {
            g.dispose();
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int w = getWidth();
            int h = getHeight();
            TokenChips.paintShape(g, w, h, TokenDialogPane.token("surface.popover"), null, 0);
            float arc = 2f * scaled(Math.round(DesignTokens.shipped().radius().get("md")));
            Shape card = new RoundRectangle2D.Float(0, 0, w, h, arc, arc);
            Graphics2D bar = (Graphics2D) g.create();
            bar.clip(card);
            bar.setColor(level.color());
            bar.fillRect(mirror(0, scaled(BAR)), 0, scaled(BAR), h);
            bar.dispose();
            TokenChips.paintShape(g, w, h, null, TokenDialogPane.token("border.subtle"), TokenFrame.line());

            int iconSize = TokenChips.scaledSize("icon.md");
            int top = space("md");
            int x = scaled(BAR) + space("lg");
            Icon icon = FontHandler.symbolIcon(level.glyph, iconSize, level.color());
            icon.paintIcon(this, g, mirror(x, iconSize), top + (firstRow() - iconSize) / 2);
            x += iconSize + space("md");
            Dimension sprite = spriteSize();
            if (sprite.width > 0) {
                g.drawImage(unit, mirror(x, sprite.width), top, sprite.width, sprite.height, null);
                x += sprite.width + space("md");
            }

            FlatUIUtils.setRenderingHints(g);
            g.setFont(getFont());
            g.setColor(TokenDialogPane.token("text.primary"));
            FontMetrics metrics = getFontMetrics(getFont());
            int lineTop = top + (firstRow() - metrics.getHeight()) / 2;
            for (String line : lines(textRoom(w))) {
                int width = metrics.stringWidth(line);
                FlatUIUtils.drawString(this, g, line, mirror(x, width), lineTop + metrics.getAscent());
                lineTop += metrics.getHeight();
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.ALERT;
                }
            };
            accessibleContext.setAccessibleName(level.spokenName() + ": " + text);
        }
        return accessibleContext;
    }
}
