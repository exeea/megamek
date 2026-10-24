/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;

/**
 * The edge of an input: text fields, spinners, combo boxes and scroll panes around text. FlatLaf colors it at rest,
 * focused, disabled and with a status; this adds a strong edge while the pointer is over an editable input, and a
 * subtle edge on a read-only one, which has no input fill either. The hover tracker runs while a MegaMek look and feel
 * is active.
 */
final class FieldEdge {
    /** Client property that keeps a gallery sample in its hovered look whatever the pointer does. */
    static final String FROZEN_HOVER = "MegaMek.field.frozenHover";
    static final String HOVER_COLOR = "MegaMek.field.hoverBorderColor";
    static final String READ_ONLY_COLOR = "MegaMek.field.readOnlyBorderColor";
    /** The input height when the component has no {@code JComponent.minimumHeight} of its own. */
    static final String HEIGHT = "MegaMek.field.height";
    private static final String EDGE = "field.edge";

    private static final AWTEventListener MOUSE = FieldEdge::mouse;
    private static int installs;
    private static Component hovered;

    private FieldEdge() {
    }

    static synchronized void install() {
        if (installs++ == 0) {
            Toolkit.getDefaultToolkit().addAWTEventListener(MOUSE, AWTEvent.MOUSE_EVENT_MASK);
        }
    }

    static synchronized void uninstall() {
        if (installs == 0 || --installs > 0) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(MOUSE);
        setHovered(null);
    }

    /**
     * The edge color for {@code component}: FlatLaf's {@code normal} color, unless the input is enabled and not
     * focused, in which case a read-only input gets the subtle edge and a hovered one the strong edge. The edge eases
     * between them ({@link TokenMotion}): focus shows at once, hover comes in quickly and lingers on the way out.
     */
    static Paint color(Component component, Paint normal, boolean enabled, boolean focused) {
        Paint target = target(component, normal, enabled, focused);
        if (!(component instanceof JComponent c) || !(target instanceof Color || target == null)) {
            return target;
        }
        int duration = focused ? 0 : TokenMotion.hover(target == UIManager.getColor(HOVER_COLOR));
        return TokenMotion.color(c, EDGE, (Color) target, duration);
    }

    private static Paint target(Component component, Paint normal, boolean enabled, boolean focused) {
        if (!enabled || focused || !isInput(component)) {
            return normal;
        }
        Color readOnly = UIManager.getColor(READ_ONLY_COLOR);
        if (readOnly != null && isReadOnly(component)) {
            return readOnly;
        }
        Color hover = UIManager.getColor(HOVER_COLOR);
        if (hover != null && isHovered(component)) {
            return hover;
        }
        return normal;
    }

    static boolean isHovered(Component component) {
        return component == hovered
              || (component instanceof JComponent c && Boolean.TRUE.equals(c.getClientProperty(FROZEN_HOVER)));
    }

    /** Whether {@code component} is an input whose border is its edge: a scroll pane only when it holds text. */
    static boolean isInput(Component component) {
        if (component instanceof JScrollPane scroll) {
            return scroll.getViewport() != null && scroll.getViewport().getView() instanceof JTextComponent;
        }
        return component instanceof JTextComponent || component instanceof JSpinner
              || component instanceof JComboBox;
    }

    static boolean isReadOnly(Component component) {
        if (component instanceof JTextComponent text) {
            return !text.isEditable();
        } else if (component instanceof JScrollPane scroll) {
            return scroll.getViewport().getView() instanceof JTextComponent text && !text.isEditable();
        } else if (component instanceof JSpinner spinner
              && spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            return !editor.getTextField().isEditable();
        }
        return false;
    }

    /** The outermost input around {@code component}, whose edge shows the hover: a field's spinner, for example. */
    static Component edgeOf(Component component) {
        Component edge = null;
        for (Component c = component; c != null && !(c instanceof Window); c = c.getParent()) {
            if (isInput(c)) {
                edge = c;
            }
        }
        return edge;
    }

    private static void mouse(AWTEvent event) {
        if (!(event instanceof MouseEvent mouse)) {
            return;
        }
        if (mouse.getID() == MouseEvent.MOUSE_ENTERED) {
            setHovered(edgeOf(mouse.getComponent()));
        } else if (mouse.getID() == MouseEvent.MOUSE_EXITED && hovered != null) {
            // Moving onto a spinner's arrow or a field's clear button leaves the field but not its edge
            Point point = hovered.isShowing()
                  ? SwingUtilities.convertPoint(mouse.getComponent(), mouse.getPoint(), hovered) : null;
            if (point == null || !hovered.contains(point)) {
                setHovered(null);
            }
        }
    }

    private static void setHovered(Component edge) {
        if (edge == hovered) {
            return;
        }
        if (hovered != null) {
            hovered.repaint();
        }
        hovered = edge;
        if (edge != null) {
            edge.repaint();
        }
    }
}
