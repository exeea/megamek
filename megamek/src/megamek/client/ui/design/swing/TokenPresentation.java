/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.event.HierarchyEvent;
import java.awt.font.TextAttribute;
import java.beans.PropertyChangeEvent;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import javax.accessibility.AccessibleContext;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JTabbedPane;
import javax.swing.RootPaneContainer;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;

import megamek.MegaMek;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Presentation;
import megamek.client.ui.design.DesignTokens.TypeStyle;

/**
 * A screen's presentation mode for Swing (docs/design-system/tokens.md, Presentation mode). {@link #setMode} marks a
 * screen's root as an app or a game screen; every component below it follows the nearest marked ancestor, and app is
 * the default. The mode sets the text case of components with a text role and a short list of shapes (the slider's
 * thumb). The library's factories give their buttons, tabs and segmented controls a role; {@link #role} gives one to
 * another component.
 * <p>
 * A component keeps its sentence-case text and shows it upper-cased, in the role's caps tracking, when its role is
 * upper case in its screen's mode. Code may keep calling {@code setText}, {@code setTitleAt} and {@code setFont}: the
 * new value becomes the source and is shown in the screen's case. {@code getText} returns the shown text.
 */
public final class TokenPresentation {
    private static final String MODE = "MegaMek.presentation";
    private static final String ROLE = "MegaMek.textRole";
    private static final String SOURCE = "MegaMek.textSource";
    private static final String TAB_SOURCES = "MegaMek.textTabSources";
    private static final String BASE_FONT = "MegaMek.textBaseFont";
    private static final String CAPS_FONT = "MegaMek.textCapsFont";
    private static final String ACCESSIBLE_NAME = "MegaMek.textAccessibleName";
    private static final String APPLYING = "MegaMek.textApplying";

    private TokenPresentation() {
    }

    /** Marks {@code root}, a window's content or a screen's top panel, as an app or a game screen. */
    public static void setMode(JComponent root, Presentation mode) {
        root.putClientProperty(MODE, mode);
        refresh(root);
        // Sliders pick their thumb shape while painting
        root.repaint();
    }

    /**
     * The text mode of the nearest marked ancestor of {@code component}; a popup follows its invoker and a dialog its
     * owner window, which may be marked on its root pane.
     */
    public static Presentation mode(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JComponent jComponent && jComponent.getClientProperty(MODE) instanceof Presentation mode) {
                return mode;
            }
            if (current instanceof RootPaneContainer window && window.getRootPane() != null
                  && window.getRootPane().getClientProperty(MODE) instanceof Presentation mode) {
                return mode;
            }
            current = current instanceof JPopupMenu popup ? popup.getInvoker() : current.getParent();
        }
        return Presentation.APP;
    }

    /**
     * Gives {@code component}, a button, label or tab pane, the text role {@code role} ("button", "tab", "title"...), so
     * it follows its screen's text mode.
     */
    public static <T extends JComponent> T role(T component, String role) {
        if (!(component instanceof AbstractButton || component instanceof JLabel
              || component instanceof JTabbedPane)) {
            throw new IllegalArgumentException("Only buttons, labels and tab panes have a text role");
        }
        DesignTokens.shipped().roleStyle(role);
        boolean installed = component.getClientProperty(ROLE) != null;
        component.putClientProperty(ROLE, role);
        if (!installed) {
            install(component);
        }
        apply(component);
        return component;
    }

    /** Re-applies the text mode to every component with a text role in {@code root}. */
    public static void refresh(Component root) {
        if (root instanceof JComponent component) {
            apply(component);
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                refresh(child);
            }
        }
    }

    private static void install(JComponent component) {
        if (component instanceof JTabbedPane tabs) {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                capture(tabs, i);
            }
            tabs.addContainerListener(new ContainerAdapter() {
                @Override
                public void componentAdded(ContainerEvent event) {
                    if (!applying(tabs)) {
                        int index = tabs.indexOfComponent(event.getChild());
                        if (index >= 0) {
                            capture(tabs, index);
                            apply(tabs);
                        }
                    }
                }
            });
        } else {
            component.putClientProperty(SOURCE, text(component));
        }
        component.addPropertyChangeListener(event -> changed(component, event));
        component.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.PARENT_CHANGED) != 0) {
                apply(component);
            }
        });
    }

    private static void changed(JComponent component, PropertyChangeEvent event) {
        if (applying(component)) {
            return;
        }
        switch (event.getPropertyName()) {
            case "text" -> {
                if (!(component instanceof JTabbedPane)) {
                    component.putClientProperty(SOURCE, event.getNewValue());
                    apply(component);
                }
            }
            case "indexForTitle" -> {
                if (component instanceof JTabbedPane tabs && event.getNewValue() instanceof Integer index) {
                    capture(tabs, index);
                    apply(tabs);
                }
            }
            // A look and feel change or a view's own font replaces the caps font: track the new one
            case "UI", "font" -> apply(component);
            default -> {
            }
        }
    }

    private static void apply(JComponent component) {
        String role = (String) component.getClientProperty(ROLE);
        if (role == null || applying(component)) {
            return;
        }
        DesignTokens tokens = DesignTokens.shipped();
        Presentation mode = mode(component);
        boolean upper = tokens.upperCase(role, mode);
        Locale locale = MegaMek.getMMOptions().getLocale();
        component.putClientProperty(APPLYING, Boolean.TRUE);
        try {
            if (component instanceof JTabbedPane tabs) {
                Map<Component, String> sources = tabSources(tabs);
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    String source = sources.get(tabs.getComponentAt(i));
                    String shown = tokens.cased(role, mode, source, locale);
                    if (shown != null && !shown.equals(tabs.getTitleAt(i))) {
                        tabs.setTitleAt(i, shown);
                    }
                }
            } else {
                String source = (String) component.getClientProperty(SOURCE);
                String shown = tokens.cased(role, mode, source, locale);
                if (shown != null && !shown.equals(text(component))) {
                    setText(component, shown);
                }
                accessibleName(component, upper ? source : null);
            }
            font(component, upper ? tokens.roleStyle(role) : null);
        } finally {
            component.putClientProperty(APPLYING, null);
        }
    }

    /** Swaps the component's font for one tracked for upper case, or back to the font it replaced. */
    private static void font(JComponent component, TypeStyle caps) {
        Font current = component.getFont();
        Font capsFont = (Font) component.getClientProperty(CAPS_FONT);
        Font base = capsFont != null && current == capsFont ? (Font) component.getClientProperty(BASE_FONT) : current;
        if (caps == null) {
            if (capsFont != null && current == capsFont) {
                component.setFont(base);
            }
            component.putClientProperty(CAPS_FONT, null);
            component.putClientProperty(BASE_FONT, null);
            return;
        }
        if (base == null) {
            return;
        }
        Font derived = base.deriveFont(Map.of(TextAttribute.TRACKING, caps.capsTracking()));
        // A look and feel's font stays replaceable by the next look and feel
        if (base instanceof UIResource) {
            derived = new FontUIResource(derived);
        }
        component.putClientProperty(BASE_FONT, base);
        component.putClientProperty(CAPS_FONT, derived);
        component.setFont(derived);
    }

    /** Names the component by its sentence-case text while it shows upper case, unless a view named it itself. */
    private static void accessibleName(JComponent component, String name) {
        AccessibleContext context = component.getAccessibleContext();
        if (context == null) {
            return;
        }
        String ours = (String) component.getClientProperty(ACCESSIBLE_NAME);
        String current = context.getAccessibleName();
        boolean viewNamed = current != null && !current.equals(ours) && !current.equals(text(component));
        if (viewNamed) {
            return;
        }
        if (name != null) {
            context.setAccessibleName(name);
        } else if (ours != null) {
            context.setAccessibleName(null);
        }
        component.putClientProperty(ACCESSIBLE_NAME, name);
    }

    private static void capture(JTabbedPane tabs, int index) {
        Component page = tabs.getComponentAt(index);
        if (page != null) {
            tabSources(tabs).put(page, tabs.getTitleAt(index));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Component, String> tabSources(JTabbedPane tabs) {
        Object sources = tabs.getClientProperty(TAB_SOURCES);
        if (sources == null) {
            sources = new WeakHashMap<Component, String>();
            tabs.putClientProperty(TAB_SOURCES, sources);
        }
        return (Map<Component, String>) sources;
    }

    private static boolean applying(JComponent component) {
        return Boolean.TRUE.equals(component.getClientProperty(APPLYING));
    }

    private static String text(JComponent component) {
        if (component instanceof AbstractButton button) {
            return button.getText();
        }
        return component instanceof JLabel label ? label.getText() : null;
    }

    private static void setText(JComponent component, String text) {
        if (component instanceof AbstractButton button) {
            button.setText(text);
        } else if (component instanceof JLabel label) {
            label.setText(text);
        }
    }
}
