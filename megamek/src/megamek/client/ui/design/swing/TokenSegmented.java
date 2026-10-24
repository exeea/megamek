/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FocusTraversalPolicy;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ItemEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.ui.FlatUIUtils;
import megamek.client.ui.design.ColorMath;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.util.UIUtil;

/**
 * The segmented control (docs/design-system/tokens.md, Segmented control): a few mutually exclusive options as separate
 * raised segments, xs apart and each with its own edge, for switching a view or a mode in place. The selected segment
 * has a mint wash and edge, like a
 * selected toggle; the sections share the width equally. Tab reaches only the selected section; Left and Right move
 * the selection, and Space and Enter select the focused section. It paints itself from the tokens of the current mode,
 * easing between states ({@link TokenMotion}).
 * <p>
 * Listeners hear only selections made by the user; {@link #setSelectedIndex} is silent.
 */
public class TokenSegmented extends JPanel {
    private static final String FILL = "segmented.fill.";
    private static final String WASH = "segmented.wash.";
    private static final String EDGE = "segmented.edge.";
    private static final String TEXT = "segmented.text";
    private final List<Segment> segments = new ArrayList<>();
    private final Size size;
    private boolean silent;

    public TokenSegmented(Size size, String... labels) {
        super(null);
        if (labels.length < 2) {
            throw new IllegalArgumentException("A segmented control needs at least two options");
        }
        this.size = size;
        setOpaque(false);
        int line = line();
        // Each segment's box is its bounds grown by the edge; xs stays between two boxes
        int gap = UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("xs")));
        setLayout(new GridLayout(1, labels.length, gap + 2 * line, 0));
        setBorder(BorderFactory.createEmptyBorder(line, line, line, line));
        ButtonGroup group = new ButtonGroup();
        for (String label : labels) {
            Segment segment = new Segment(label);
            group.add(segment);
            segments.add(segment);
            add(segment);
            // A section reads as a tab: it follows the screen's text case mode
            TokenPresentation.role(segment, "tab");
        }
        for (int i = 0; i < segments.size(); i++) {
            bindKeys(i);
        }
        // One Tab stop, the selected section, as a radio group; Left and Right move within
        setFocusTraversalPolicyProvider(true);
        setFocusTraversalPolicy(new SelectedSegmentPolicy());
        setSelectedIndex(0);
    }

    /** The number of sections. */
    public int count() {
        return segments.size();
    }

    /** The section at {@code index}, a toggle button; the gallery freezes states through its model. */
    public JToggleButton segment(int index) {
        return segments.get(index);
    }

    public int getSelectedIndex() {
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).isSelected()) {
                return i;
            }
        }
        return -1;
    }

    /** Selects the section at {@code index} without notifying listeners. */
    public void setSelectedIndex(int index) {
        silent = true;
        try {
            segments.get(index).setSelected(true);
        } finally {
            silent = false;
        }
        repaint();
    }

    /** Listens for selections made by the user; the event's command is the selected section's label. */
    public void addActionListener(ActionListener listener) {
        listenerList.add(ActionListener.class, listener);
    }

    public void removeActionListener(ActionListener listener) {
        listenerList.remove(ActionListener.class, listener);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        for (Segment segment : segments) {
            segment.setEnabled(enabled);
        }
        repaint();
    }

    @Override
    public Dimension getMaximumSize() {
        return isMaximumSizeSet() ? super.getMaximumSize() : getPreferredSize();
    }

    /** The sections never squeeze below their labels and padding. */
    @Override
    public Dimension getMinimumSize() {
        return isMinimumSizeSet() ? super.getMinimumSize() : getPreferredSize();
    }

    private void bindKeys(int index) {
        Segment segment = segments.get(index);
        segment.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "previousSegment");
        segment.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "nextSegment");
        segment.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "selectSegment");
        segment.getActionMap().put("previousSegment", new MoveAction(index, -1));
        segment.getActionMap().put("nextSegment", new MoveAction(index, 1));
        segment.getActionMap().put("selectSegment", new MoveAction(index, 0));
    }

    private void selected(Segment segment) {
        repaint();
        if (silent) {
            return;
        }
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, segment.getText());
        for (ActionListener listener : listenerList.getListeners(ActionListener.class)) {
            listener.actionPerformed(event);
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = mode();
        boolean enabled = isEnabled();
        int raised = tokens.color("surface.raised", mode);
        int hover = tokens.color("surface.hover", mode);
        int selected = tokens.color("selected.background", mode);
        if (!enabled) {
            selected = ColorMath.withAlpha(selected, ColorMath.alpha(selected) / 2);
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float arc = arc();
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                ButtonModel model = segment.getModel();
                RoundRectangle2D box = round(outer(segment), arc);
                g.setColor(new Color(raised, true));
                g.fill(box);
                Color fill = TokenMotion.color(this, FILL + i, model.isSelected() ? new Color(selected, true) : null,
                      TokenMotion.fast());
                if (fill != null) {
                    g.setColor(fill);
                    g.fill(box);
                }
                Color wash = TokenMotion.color(this, WASH + i, hovered(segment) && !model.isSelected()
                      ? new Color(pressed(segment) ? ColorMath.withAlpha(hover,
                      Math.min(255, ColorMath.alpha(hover) * 2)) : hover, true) : null,
                      TokenMotion.buttonState(segment));
                if (wash != null) {
                    g.setColor(wash);
                    g.fill(box);
                }
            }
        } finally {
            g.dispose();
        }
    }

    /** The edges go over the sections: each segment's own edge, mint when selected, and the focus ring. */
    @Override
    protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        DesignTokens tokens = DesignTokens.shipped();
        Mode mode = mode();
        boolean enabled = isEnabled();
        int line = line();
        float arc = arc();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int selected = getSelectedIndex();
            for (int i = 0; i < segments.size(); i++) {
                Segment segment = segments.get(i);
                String role;
                if (!enabled) {
                    role = "border.subtle";
                } else if (i == selected) {
                    role = "selected.primary";
                } else {
                    role = hovered(segment) ? "border.strong" : "border.control";
                }
                Color edge = TokenMotion.color(this, EDGE + i, color(tokens, role, mode),
                      i == selected ? TokenMotion.fast() : TokenMotion.buttonState(segment));
                g.setColor(edge);
                g.fill(ring(outer(segment), line, arc));
            }
            for (Segment segment : segments) {
                if (enabled && FlatUIUtils.isPermanentFocusOwner(segment)) {
                    int ring = Math.max(1, UIUtil.scaleForGUI(Math.round(tokens.size().get("focus.width"))));
                    g.setColor(color(tokens, "focus.ring", mode));
                    g.fill(ring(outer(segment), ring, arc));
                }
            }
        } finally {
            g.dispose();
        }
    }

    private boolean hovered(Segment segment) {
        ButtonModel model = segment.getModel();
        return isEnabled() && (model.isRollover() || model.isPressed());
    }

    private static boolean pressed(Segment segment) {
        ButtonModel model = segment.getModel();
        return model.isArmed() && model.isPressed();
    }

    /** A segment's box: its bounds grown by the edge, which the layout leaves room for. */    private Rectangle outer(Segment segment) {
        Rectangle bounds = segment.getBounds();
        int line = line();
        bounds.grow(line, line);
        return bounds;
    }

    private static RoundRectangle2D round(Rectangle bounds, float arc) {
        return new RoundRectangle2D.Float(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
    }

    /** A {@code width} wide outline just inside {@code bounds}, with rounded corners. */
    private static Area ring(Rectangle bounds, float width, float arc) {
        Area area = new Area(round(bounds, arc));
        float inner = Math.max(0, arc - 2 * width);
        area.subtract(new Area(new RoundRectangle2D.Float(bounds.x + width, bounds.y + width,
              bounds.width - 2 * width, bounds.height - 2 * width, inner, inner)));
        return area;
    }

    private static Color color(DesignTokens tokens, String role, Mode mode) {
        return new Color(tokens.color(role, mode), true);
    }

    private static Mode mode() {
        return FlatLaf.isLafDark() ? Mode.DARK : Mode.LIGHT;
    }

    private static int line() {
        return Math.max(1, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get("border"))));
    }

    private static float arc() {
        return UIUtil.scaleForGUI(DesignTokens.shipped().radius().get("md")) * 2;
    }

    /** Tab stops only at the selected section; from there it leaves the control. */
    private final class SelectedSegmentPolicy extends FocusTraversalPolicy {
        @Override
        public Component getComponentAfter(Container root, Component component) {
            return null;
        }

        @Override
        public Component getComponentBefore(Container root, Component component) {
            return null;
        }

        @Override
        public Component getFirstComponent(Container root) {
            return getDefaultComponent(root);
        }

        @Override
        public Component getLastComponent(Container root) {
            return getDefaultComponent(root);
        }

        @Override
        public Component getDefaultComponent(Container root) {
            int index = getSelectedIndex();
            if (index < 0) {
                return null;
            }
            Segment segment = segments.get(index);
            return segment.isShowing() && segment.isEnabled() && segment.isFocusable() ? segment : null;
        }
    }

    /** Moves the selection {@code step} sections from {@code index}, or selects it for a step of 0. */
    private final class MoveAction extends AbstractAction {
        private final int index;
        private final int step;

        MoveAction(int index, int step) {
            this.index = index;
            this.step = step;
        }

        @Override
        public void actionPerformed(ActionEvent event) {
            int target = index + step;
            if (!TokenSegmented.this.isEnabled() || target < 0 || target >= segments.size()) {
                return;
            }
            Segment segment = segments.get(target);
            segment.setSelected(true);
            segment.requestFocusInWindow();
        }
    }

    /** A section: a toggle button that paints only its label, in the toggle button's text colors. */
    private final class Segment extends JToggleButton {
        Segment(String label) {
            super(label);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setRolloverEnabled(true);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder());
            addItemListener(event -> {
                if (event.getStateChange() == ItemEvent.SELECTED) {
                    selected(this);
                }
            });
            addChangeListener(event -> TokenSegmented.this.repaint());
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    TokenSegmented.this.repaint();
                }

                @Override
                public void focusLost(FocusEvent event) {
                    TokenSegmented.this.repaint();
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) {
                return super.getPreferredSize();
            }
            FontMetrics metrics = getFontMetrics(getFont());
            int side = UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space()
                  .get(size == Size.REGULAR ? "lg" : "md")));
            int height = UIUtil.scaleForGUI(size.height()) - 2 * line();
            return new Dimension(metrics.stringWidth(getText()) + 2 * side, Math.max(height, metrics.getHeight()));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            DesignTokens tokens = DesignTokens.shipped();
            ButtonModel model = getModel();
            boolean active = model.isSelected() || model.isRollover() || model.isPressed();
            String role = !isEnabled() ? "text.disabled" : active ? "text.primary" : "text.secondary";
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(TokenMotion.color(this, TEXT, color(tokens, role, mode()),
                      TokenMotion.buttonState(this)));
                g.setFont(getFont());
                FontMetrics metrics = g.getFontMetrics();
                String text = getText();
                int x = (getWidth() - metrics.stringWidth(text)) / 2;
                int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(text, x, y);
            } finally {
                g.dispose();
            }
        }

        @Override
        public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) {
                accessibleContext = new AccessibleJToggleButton() {
                    @Override
                    public AccessibleRole getAccessibleRole() {
                        return AccessibleRole.RADIO_BUTTON;
                    }
                };
            }
            return accessibleContext;
        }
    }
}
