/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.swing.TokenDialogPane.Status;
import megamek.client.ui.util.UIUtil;

/**
 * A corner-ticked panel for anything that isn't a dialog, such as sidebars, unit cards and grouped areas
 * (docs/design-system/tokens.md, Panels): the dialog card's frame with an optional header of a status icon, a title
 * and trailing actions above the body.
 * <p>
 * A panel can be selectable, so that the whole card is the control: a click, Space or Enter selects it, and
 * {@link #SELECTED} fires. {@link #selectOne} makes a group of which one is selected. A {@link #plain()} panel keeps only
 * the edge, for a group inside another panel; ticks belong to the outermost frame.
 * <p>
 * The body is made transparent so the panel's surface shows through; its own panels must be transparent too.
 */
public class TokenPanel extends JPanel {
    /** The bound property fired when a selectable panel is selected or deselected. */
    public static final String SELECTED = "selected";
    /** Client property freezing the panel in {@link #FROZEN_HOVER} or {@link #FROZEN_FOCUS}, for the design gallery. */
    static final String FROZEN_STATE = "MegaMek.panel.frozenState";
    static final String FROZEN_HOVER = "hover";
    static final String FROZEN_FOCUS = "focus";
    private static final String SELECT = "select";
    private static final String EDGE = "panel.edge";
    private static final String TICKS = "panel.ticks";
    private static final String WASH = "panel.wash";

    /** The space between the frame and the content. */
    public enum Padding {
        /** {@code lg} (12), for dense sidebars and unit cards. */
        REGULAR("lg"),
        /** {@code xl} (16), for roomy grouped areas. */
        ROOMY("xl");

        private final String token;

        Padding(String token) {
            this.token = token;
        }

        /** The padding in scaled pixels. */
        public int pixels() {
            return TokenDialogPane.space(token);
        }
    }

    private final String title;
    private final JLabel heading;
    private final JPanel tools;
    private final JPanel header;
    private JComponent body;
    private Status status = Status.NONE;
    private Padding padding = Padding.REGULAR;
    private boolean plain;
    private boolean selectable;
    private boolean selected;
    private boolean hover;
    private List<TokenPanel> group;
    private final AWTEventListener pointer = this::pointer;
    private boolean tracking;

    /** A panel without a header. */
    public TokenPanel() {
        this(null);
    }

    /** A panel with {@code title} in its header, or none when it is null. */
    public TokenPanel(String title) {
        super(new BorderLayout());
        this.title = title;
        if (title == null) {
            heading = null;
            tools = null;
            header = null;
        } else {
            heading = TokenTextCase.role(new JLabel(title, JLabel.LEADING), "title");
            tools = new JPanel(new FlowLayout(FlowLayout.TRAILING, TokenDialogPane.space("xs"), 0));
            tools.setOpaque(false);
            header = new JPanel(new BorderLayout(TokenDialogPane.space("md"), 0));
            header.setOpaque(false);
            header.add(heading, BorderLayout.CENTER);
            header.add(tools, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            getAccessibleContext().setAccessibleName(title);
        }
        applyStyle();
    }

    public String title() {
        return title;
    }

    public Status status() {
        return status;
    }

    /**
     * Ticks the corners in the status color, with the status icon before the title, so color is never the only cue.
     *
     * @throws IllegalStateException when the panel has no title for the icon to sit beside
     */
    public TokenPanel status(Status status) {
        Status next = status == null ? Status.NONE : status;
        if (next != Status.NONE && title == null) {
            throw new IllegalStateException("A status panel needs a title for its status icon");
        }
        this.status = next;
        applyStyle();
        repaint();
        return this;
    }

    public TokenPanel padding(Padding padding) {
        this.padding = padding == null ? Padding.REGULAR : padding;
        applyStyle();
        return this;
    }

    /** Drops the surface and the ticks, keeping the edge: a group inside another panel. */
    public TokenPanel plain() {
        plain = true;
        applyStyle();
        repaint();
        return this;
    }

    public boolean isPlain() {
        return plain;
    }

    /**
     * Adds a control to the trailing end of the header, such as a compact quiet or icon button.
     *
     * @throws IllegalStateException when the panel has no header
     */
    public TokenPanel action(AbstractButton action) {
        if (tools == null) {
            throw new IllegalStateException("Header actions need a panel with a title");
        }
        tools.add(action);
        return this;
    }

    /** The content below the header; a {@link JPanel} is made transparent so the surface shows. */
    public TokenPanel body(JComponent body) {
        if (this.body != null) {
            remove(this.body);
        }
        this.body = body;
        if (body instanceof JPanel) {
            body.setOpaque(false);
        }
        add(body, BorderLayout.CENTER);
        applyStyle();
        revalidate();
        repaint();
        return this;
    }

    public JComponent body() {
        return body;
    }

    /** Makes the whole panel a control that a click, Space or Enter selects. */
    public TokenPanel selectable(boolean selectable) {
        if (this.selectable == selectable) {
            return this;
        }
        this.selectable = selectable;
        setFocusable(selectable);
        if (selectable) {
            installControl();
        }
        track(selectable && isDisplayable());
        repaint();
        return this;
    }

    public boolean isSelectable() {
        return selectable;
    }

    public boolean isSelected() {
        return selected;
    }

    /** Selects or deselects the panel; selecting one panel of a {@link #selectOne} group deselects the others. */
    public void setSelected(boolean selected) {
        if (this.selected == selected) {
            return;
        }
        this.selected = selected;
        if (selected && group != null) {
            for (TokenPanel other : group) {
                if (other != this) {
                    other.setSelected(false);
                }
            }
        }
        repaint();
        firePropertyChange(SELECTED, !selected, selected);
        getAccessibleContext().firePropertyChange(AccessibleContext.ACCESSIBLE_STATE_PROPERTY,
              selected ? null : AccessibleState.CHECKED, selected ? AccessibleState.CHECKED : null);
    }

    /**
     * Makes {@code panels} selectable as a group of which at most one is selected, like radio buttons: selecting one
     * deselects the others, and pressing the selected one keeps it.
     */
    public static void selectOne(TokenPanel... panels) {
        List<TokenPanel> members = List.of(panels);
        for (TokenPanel panel : members) {
            panel.group = members;
            panel.selectable(true);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyStyle();
        repaint();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Runs from JPanel's constructor too, before the fields are set
        if (padding != null) {
            applyStyle();
        }
    }

    @Override
    public void addNotify() {
        super.addNotify();
        track(selectable);
    }

    @Override
    public void removeNotify() {
        track(false);
        super.removeNotify();
    }

    /** Fonts, colors and spacing from the active theme. */
    private void applyStyle() {
        int space = padding.pixels();
        setBorder(BorderFactory.createEmptyBorder(space, space, space, space));
        setOpaque(!plain);
        if (heading == null) {
            return;
        }
        heading.setFont(TokenFonts.font("title"));
        heading.setForeground(TokenDialogPane.token(isEnabled() ? "text.primary" : "text.disabled"));
        heading.setIcon(status.icon(iconSize(), isEnabled() ? status.color() : TokenDialogPane.token("text.disabled")));
        heading.setIconTextGap(TokenDialogPane.space("md"));
        header.setBorder(BorderFactory.createEmptyBorder(0, 0, body == null ? 0 : TokenDialogPane.space("md"), 0));
    }

    private static int iconSize() {
        return Math.round(DesignTokens.shipped().size().get("icon.md"));
    }

    private void installControl() {
        if (getActionMap().get(SELECT) != null) {
            return;
        }
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), SELECT);
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SELECT);
        getActionMap().put(SELECT, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                activate();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (selectable && isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
                    requestFocusInWindow();
                    activate();
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                repaint();
            }
        });
    }

    private void activate() {
        if (!selectable || !isEnabled()) {
            return;
        }
        setSelected(group != null || !selected);
    }

    /**
     * Follows the pointer over the panel and everything in it while the panel is selectable and shown. Children that
     * take mouse events, such as header buttons, hide the pointer from the panel's own listeners, so the panel listens
     * to the whole toolkit instead.
     */
    private void track(boolean on) {
        if (on == tracking) {
            return;
        }
        tracking = on;
        if (on) {
            Toolkit.getDefaultToolkit().addAWTEventListener(pointer,
                  AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        } else {
            Toolkit.getDefaultToolkit().removeAWTEventListener(pointer);
            setHover(false);
        }
    }

    private void pointer(AWTEvent event) {
        if (!(event instanceof MouseEvent mouse) || !(mouse.getSource() instanceof Component source)) {
            return;
        }
        boolean inside;
        if (mouse.getID() == MouseEvent.MOUSE_EXITED) {
            inside = isShowing() && getMousePosition(true) != null;
        } else if (SwingUtilities.getWindowAncestor(source) != SwingUtilities.getWindowAncestor(this)
              && source != SwingUtilities.getWindowAncestor(this)) {
            inside = false;
        } else {
            Point point = SwingUtilities.convertPoint(source, mouse.getPoint(), this);
            inside = isShowing() && contains(point);
        }
        setHover(inside);
    }

    private void setHover(boolean hover) {
        if (this.hover != hover) {
            this.hover = hover;
            repaint();
        }
    }

    private boolean showsHover() {
        return hover || FROZEN_HOVER.equals(getClientProperty(FROZEN_STATE));
    }

    private boolean showsFocus() {
        return FROZEN_FOCUS.equals(getClientProperty(FROZEN_STATE)) || FocusVisibility.shows(this);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            int width = getWidth();
            int height = getHeight();
            boolean enabled = isEnabled();
            boolean on = enabled && selectable && selected;
            boolean hovered = enabled && selectable && showsHover();
            TokenFrame.paint(g, width, height, plain ? null : TokenDialogPane.token("surface.panel"), null, 0, null);
            Color wash = TokenMotion.color(this, WASH, on ? TokenDialogPane.token("selected.background") : null,
                  TokenMotion.fast());
            TokenFrame.paint(g, width, height, wash, null, 0, null);
            if (selectable && enabled && showsFocus()) {
                TokenFrame.paint(g, width, height, null, TokenDialogPane.token("focus.ring"), ringWidth(), null);
            } else {
                Color edge = TokenMotion.color(this, EDGE, edgeColor(enabled, on, hovered), TokenMotion.hover(hovered));
                TokenFrame.paint(g, width, height, null, edge, TokenFrame.line(), null);
            }
            if (!plain) {
                TokenFrame.paint(g, width, height, null, null, 0,
                      TokenMotion.color(this, TICKS, tickColor(enabled, on), TokenMotion.fast()));
            }
        } finally {
            g.dispose();
        }
    }

    private static Color edgeColor(boolean enabled, boolean on, boolean hovered) {
        if (enabled && on) {
            return TokenDialogPane.token("selected.primary");
        }
        return TokenDialogPane.token(enabled && hovered ? "border.control" : "border.subtle");
    }

    private Color tickColor(boolean enabled, boolean on) {
        if (!enabled) {
            return TokenDialogPane.token("border.subtle");
        }
        if (on) {
            return TokenDialogPane.token("selected.primary");
        }
        return status == Status.NONE ? TokenDialogPane.token("border.strong") : status.color();
    }

    private static int ringWidth() {
        return Math.max(1, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get("focus.width"))));
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleTokenPanel();
        }
        return accessibleContext;
    }

    /** A plain panel, or a toggle (a radio button in a group) checked while selected when the panel is selectable. */
    private final class AccessibleTokenPanel extends AccessibleJPanel {
        @Override
        public AccessibleRole getAccessibleRole() {
            if (!selectable) {
                return super.getAccessibleRole();
            }
            return group == null ? AccessibleRole.TOGGLE_BUTTON : AccessibleRole.RADIO_BUTTON;
        }

        @Override
        public AccessibleStateSet getAccessibleStateSet() {
            AccessibleStateSet states = super.getAccessibleStateSet();
            if (selectable && selected) {
                states.add(AccessibleState.CHECKED);
            }
            return states;
        }
    }
}
