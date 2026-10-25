/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import megamek.client.ui.design.DesignTokens;

/**
 * A section that opens and closes (docs/design-system/tokens.md, Sections): a section header with a chevron, the
 * tree's, at its leading edge. The header row takes the hover wash and the keyboard; a click, Enter or Space opens or
 * closes the content below it. Made by {@link TokenSections#collapsible}; listen to {@link #EXPANDED} for changes.
 */
public class TokenCollapsibleSection extends JPanel {
    /** The bound property fired when the section opens or closes. */
    public static final String EXPANDED = "expanded";
    /** Client property freezing the header row in its hover look, for the design gallery. */
    static final String FROZEN_HOVER = "MegaMek.section.frozenHover";
    private static final String TOGGLE = "toggle";
    private static final String REVEAL = "section.reveal";
    private static final String WASH = "section.wash";
    private static final String TITLE = "section.title";

    private final HeaderRow header;
    private final JComponent content;
    private boolean expanded;
    private Timer revealTimer;

    public TokenCollapsibleSection(String title, JComponent content, boolean expanded) {
        super(new BorderLayout());
        setLayout(new RevealLayout());
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        this.content = content;
        this.expanded = expanded;
        header = new HeaderRow(title);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        content.setVisible(expanded);
        TokenMotion.value(this, REVEAL, expanded, 0);
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** Opens or closes the section, sliding the content in or out over {@code medium} while the chevron turns. */
    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return;
        }
        this.expanded = expanded;
        reveal();
        // The content stays visible while it slides, clipped by the section, and hides once closed
        content.setVisible(true);
        if (TokenMotion.isMoving(this, REVEAL)) {
            if (revealTimer == null) {
                revealTimer = new Timer(16, event -> {
                    revalidate();
                    repaint();
                    if (!TokenMotion.isMoving(this, REVEAL)) {
                        settle();
                    }
                });
            }
            revealTimer.start();
        } else {
            settle();
        }
        header.repaint();
        revalidate();
        repaint();
        firePropertyChange(EXPANDED, !expanded, expanded);
        header.getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
              expanded ? AccessibleState.COLLAPSED : AccessibleState.EXPANDED,
              expanded ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED);
    }

    @Override
    public Dimension getPreferredSize() {
        return revealed(super.getPreferredSize(), isPreferredSizeSet());
    }

    @Override
    public Dimension getMinimumSize() {
        return revealed(super.getMinimumSize(), isMinimumSizeSet());
    }

    @Override
    public Dimension getMaximumSize() {
        return revealed(super.getMaximumSize(), isMaximumSizeSet());
    }

    /** While the content slides, every size is the header and the shown part of the content's preferred height. */
    private Dimension revealed(Dimension size, boolean set) {
        if (set || !isRevealing()) {
            return size;
        }
        Dimension preferred = super.getPreferredSize();
        int headerHeight = header.getPreferredSize().height;
        size.height = headerHeight + Math.round((preferred.height - headerHeight) * reveal());
        return size;
    }

    /** How open the section is, 0 closed to 1 open, eased between. */
    private float reveal() {
        return TokenMotion.value(this, REVEAL, expanded, TokenMotion.medium());
    }

    private boolean isRevealing() {
        return revealTimer != null && revealTimer.isRunning();
    }

    private void settle() {
        if (revealTimer != null) {
            revealTimer.stop();
        }
        content.setVisible(expanded);
        revalidate();
        repaint();
    }

    public String getTitle() {
        return header.title;
    }

    public JComponent getContent() {
        return content;
    }

    /** The header row, which takes the keyboard focus. */
    public JComponent getHeader() {
        return header;
    }

    /**
     * The border layout, except that while the content slides it keeps its full preferred height, so the section
     * clips it instead of squeezing it.
     */
    private final class RevealLayout extends BorderLayout {
        @Override
        public void layoutContainer(Container target) {
            super.layoutContainer(target);
            if (isRevealing()) {
                content.setSize(content.getWidth(), Math.max(content.getHeight(), content.getPreferredSize().height));
            }
        }
    }

    /** The header row: chevron, title and line, with the hover wash and the focus ring. */
    private final class HeaderRow extends JComponent {
        private final String title;
        private boolean hover;

        HeaderRow(String title) {
            this.title = title;
            setFocusable(true);
            setFont(TokenSections.titleFont());
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), TOGGLE);
            getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), TOGGLE);
            getActionMap().put(TOGGLE, new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    toggle();
                }
            });
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    setHover(true);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setHover(false);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled() && e.getButton() == MouseEvent.BUTTON1) {
                        requestFocusInWindow();
                        toggle();
                    }
                }
            };
            addMouseListener(mouse);
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    repaint();
                }
            });
        }

        private void toggle() {
            if (isEnabled()) {
                setExpanded(!expanded);
            }
        }

        private void setHover(boolean hover) {
            this.hover = hover;
            repaint();
        }

        private boolean showsHover() {
            return isEnabled() && (hover || Boolean.TRUE.equals(
                  TokenCollapsibleSection.this.getClientProperty(FROZEN_HOVER)));
        }

        @Override
        public boolean isEnabled() {
            return super.isEnabled() && TokenCollapsibleSection.this.isEnabled();
        }

        /** The closed chevron, which turns a quarter as the section opens; the tree's open one is the same turned. */
        private Icon chevron() {
            return UIManager.getIcon("Tree.collapsedIcon");
        }

        /** The space between the row's edge and the chevron: a list row's text inset. */
        private int padding() {
            return UIScale.scale(Math.round(DesignTokens.shipped().space().get("sm")));
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) {
                return super.getPreferredSize();
            }
            Icon icon = chevron();
            int iconWidth = icon == null ? 0 : icon.getIconWidth() + TokenSections.titleGap();
            return new Dimension(2 * padding() + iconWidth + TokenSections.minimumWidth(title, this),
                  TokenSections.height());
        }

        @Override
        public Dimension getMinimumSize() {
            return isMinimumSizeSet() ? super.getMinimumSize() : getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int width = getWidth();
            int height = getHeight();
            int arc = UIManager.getInt(TokenSections.ROW_ARC);
            boolean hovered = showsHover();
            TokenLists.paintWash(g, TokenMotion.color(this, WASH,
                        hovered ? UIManager.getColor(TokenSections.HOVER_BACKGROUND) : null, TokenMotion.hover(hovered)),
                  0, 0, width, height, new Insets(0, 0, 0, 0), arc);
            boolean leftToRight = getComponentOrientation().isLeftToRight();
            int padding = padding();
            int x = padding;
            Icon icon = chevron();
            if (icon != null) {
                int iconX = leftToRight ? x : width - x - icon.getIconWidth();
                int iconY = (height - icon.getIconHeight()) / 2;
                // The closed chevron turns a quarter towards the content as the section opens
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.rotate((leftToRight ? 1 : -1) * Math.PI / 2 * reveal(), iconX + icon.getIconWidth() / 2.0,
                          iconY + icon.getIconHeight() / 2.0);
                    icon.paintIcon(this, g2, iconX, iconY);
                } finally {
                    g2.dispose();
                }
                x += icon.getIconWidth() + TokenSections.titleGap();
            }
            int textX = leftToRight ? x : padding;
            Color titleColor = TokenMotion.color(this, TITLE, TokenSections.titleColor(this, hovered),
                  TokenMotion.hover(hovered));
            TokenSections.paintHeader(g, this, title, TokenSections.titleFont(this, getFont()), titleColor, textX, 0,
                  width - x - padding, height);
            if (isEnabled() && FocusVisibility.shows(this)) {
                paintFocus(g, width, height, arc);
            }
        }

        /** A {@code focus.width} ring inside the row, rounded like its wash. */
        private void paintFocus(Graphics g, int width, int height, int arc) {
            Color color = UIManager.getColor("Component.focusColor");
            if (color == null) {
                return;
            }
            float ring = UIScale.scale(DesignTokens.shipped().size().get("focus.width"));
            float outer = UIScale.scale((float) arc);
            Area area = new Area(new RoundRectangle2D.Float(0, 0, width, height, outer, outer));
            float inner = Math.max(0, outer - 2 * ring);
            area.subtract(new Area(new RoundRectangle2D.Float(ring, ring, width - 2 * ring, height - 2 * ring, inner,
                  inner)));
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                FlatUIUtils.setRenderingHints(g2);
                g2.setColor(color);
                g2.fill(area);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleHeader();
            }
            return accessibleContext;
        }

        /** A toggle button named by the title, expanded or collapsed with the section. */
        private final class AccessibleHeader extends AccessibleJComponent {
            @Override
            public String getAccessibleName() {
                String name = super.getAccessibleName();
                return name != null ? name : title;
            }

            @Override
            public AccessibleRole getAccessibleRole() {
                return AccessibleRole.TOGGLE_BUTTON;
            }

            @Override
            public AccessibleStateSet getAccessibleStateSet() {
                AccessibleStateSet states = super.getAccessibleStateSet();
                states.add(AccessibleState.EXPANDABLE);
                states.add(expanded ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED);
                return states;
            }
        }
    }
}
