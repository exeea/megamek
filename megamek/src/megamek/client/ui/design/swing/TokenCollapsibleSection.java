/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BorderLayout;
import java.awt.Color;
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

    private final HeaderRow header;
    private final JComponent content;
    private boolean expanded;

    public TokenCollapsibleSection(String title, JComponent content, boolean expanded) {
        super(new BorderLayout());
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        this.content = content;
        this.expanded = expanded;
        header = new HeaderRow(title);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        content.setVisible(expanded);
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return;
        }
        this.expanded = expanded;
        content.setVisible(expanded);
        header.repaint();
        revalidate();
        repaint();
        firePropertyChange(EXPANDED, !expanded, expanded);
        header.getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
              expanded ? AccessibleState.COLLAPSED : AccessibleState.EXPANDED,
              expanded ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED);
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

        private Icon chevron() {
            return UIManager.getIcon(expanded ? "Tree.expandedIcon" : "Tree.collapsedIcon");
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
            if (showsHover()) {
                TokenLists.paintWash(g, UIManager.getColor(TokenSections.HOVER_BACKGROUND), 0, 0, width, height,
                      new Insets(0, 0, 0, 0), arc);
            }
            boolean leftToRight = getComponentOrientation().isLeftToRight();
            int padding = padding();
            int x = padding;
            Icon icon = chevron();
            if (icon != null) {
                int iconX = leftToRight ? x : width - x - icon.getIconWidth();
                icon.paintIcon(this, g, iconX, (height - icon.getIconHeight()) / 2);
                x += icon.getIconWidth() + TokenSections.titleGap();
            }
            int textX = leftToRight ? x : padding;
            TokenSections.paintHeader(g, this, title, getFont(), TokenSections.titleColor(this, showsHover()), textX,
                  0, width - x - padding, height);
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
