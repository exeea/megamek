/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;

import com.formdev.flatlaf.FlatLaf;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.DesignTokens.Mode;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;

/**
 * The content of a design system dialog (docs/design-system/tokens.md, Dialogs): a header with an optional status icon
 * and the title, the body, an optional extras row (such as "don't ask again") and a footer of buttons centred under it,
 * the main action first, as in Windows. {@link TokenDialogs} shows it as a modal card over the window or in a window of its own;
 * a window shows the title in its title bar instead of the header.
 * <p>
 * Add the other buttons in reading order; the main action, {@link #primary} or {@link #danger}, always goes first. Enter
 * presses the focused button, or the primary one when the focus is elsewhere; Escape and the close button cancel. A
 * danger action is never pressed by Enter from elsewhere, and the focus starts on Cancel.
 */
public class TokenDialogPane extends JPanel {
    /** Material Symbols code points of the header glyphs. */
    static final int ICON_CLOSE = 0xE5CD;
    static final int ICON_HELP = 0xE887;
    /** The narrowest footer button, as wide as hud-v3's dialog buttons. */
    static final int BUTTON_MIN_WIDTH = 88;

    /** The status a dialog reports, shown by an icon before its title; color is never the only cue. */
    public enum Status {
        NONE(0, null),
        INFO(0xE88E, "status.info"),
        SUCCESS(0xE86C, "status.success"),
        WARNING(0xE002, "status.warning"),
        ERROR(0xE000, "status.danger"),
        QUESTION(ICON_HELP, "accent.primary");

        private final int glyph;
        private final String role;

        Status(int glyph, String role) {
            this.glyph = glyph;
            this.role = role;
        }

        /** The status color of the active theme, or null for NONE. */
        public Color color() {
            return role == null ? null : token(role);
        }

        /** The status glyph at the large icon size, or null for NONE. */
        public Icon icon() {
            return icon(iconSize());
        }

        /** The status glyph {@code size} unscaled pixels high, or null for NONE. */
        Icon icon(int size) {
            return icon(size, color());
        }

        /** The status glyph {@code size} unscaled pixels high in {@code color}, or null for NONE. */
        Icon icon(int size, Color color) {
            return glyph == 0 ? null : FontHandler.symbolIcon(glyph, UIUtil.scaleForGUI(size), color);
        }

        static int iconSize() {
            return Math.round(DesignTokens.shipped().size().get("icon.lg"));
        }
    }

    /** The widths from the {@code dialog.*} size tokens; a body that needs more space makes the dialog wider. */
    public enum Width {
        SMALL("dialog.sm"),
        MEDIUM("dialog.md"),
        LARGE("dialog.lg");

        private final String token;

        Width(String token) {
            this.token = token;
        }

        /** The width in unscaled pixels. */
        public int pixels() {
            return Math.round(DesignTokens.shipped().size().get(token));
        }
    }

    private final String title;
    private Status status = Status.NONE;
    private Width width = Width.SMALL;
    private JComponent body;
    private JComponent extra;
    private Runnable help;
    private final List<JButton> buttons = new ArrayList<>();
    private final List<JButton> cancels = new ArrayList<>();
    private JButton main;
    private boolean mainIsDanger;
    private boolean framed;
    private Consumer<JButton> closer = button -> { };

    public TokenDialogPane(String title) {
        super(new GridBagLayout());
        this.title = title;
        getAccessibleContext().setAccessibleName(title);
    }

    public String title() {
        return title;
    }

    public Status status() {
        return status;
    }

    public TokenDialogPane status(Status status) {
        this.status = status == null ? Status.NONE : status;
        return this;
    }

    public TokenDialogPane width(Width width) {
        this.width = width;
        return this;
    }

    /** A message as the body: body text that wraps at the dialog's width. */
    public TokenDialogPane message(String text) {
        return body(new MessageText(text));
    }

    /** Any component as the body, such as a form. */
    public TokenDialogPane body(JComponent body) {
        this.body = body;
        return this;
    }

    /** A component for the row above the buttons, such as a "don't ask again" check box. */
    public TokenDialogPane extra(JComponent extra) {
        this.extra = extra;
        return this;
    }

    /** Adds a help button to the header that runs {@code help}; the dialog stays open. */
    public TokenDialogPane help(Runnable help) {
        this.help = help;
        return this;
    }

    /** A button that closes the dialog without a choice, like Escape. */
    public JButton cancel(String text) {
        JButton button = TokenButtons.secondary(text);
        cancels.add(button);
        buttons.add(button);
        return button;
    }

    /** A further choice, shown as a secondary button. */
    public JButton secondary(String text) {
        JButton button = TokenButtons.secondary(text);
        buttons.add(button);
        return button;
    }

    /** The main action: the first button, which Enter presses. */
    public JButton primary(String text) {
        return main(TokenButtons.primary(text), false);
    }

    /**
     * A destructive main action: the first button, which Enter only presses when it has the focus. The focus starts
     * on the cancel button.
     */
    public JButton danger(String text) {
        return main(TokenButtons.danger(text), true);
    }

    private JButton main(JButton button, boolean danger) {
        if (main != null) {
            throw new IllegalStateException("A dialog has one main action");
        }
        main = button;
        mainIsDanger = danger;
        return button;
    }

    /** The footer buttons in the order they show: the main action, then the others as added. */
    public List<JButton> buttons() {
        List<JButton> ordered = new ArrayList<>();
        if (main != null) {
            ordered.add(main);
        }
        ordered.addAll(buttons);
        return ordered;
    }

    /** The button Enter presses when the focus is not on a button, or null. */
    public JButton defaultButton() {
        return mainIsDanger ? null : main;
    }

    /** Whether {@code button} closes the dialog without a choice. */
    boolean isCancel(JButton button) {
        return cancels.contains(button);
    }

    /**
     * The component that takes the focus when the dialog opens: Cancel when the main action is destructive, otherwise
     * the first input of the body, then the main action, then the first button.
     */
    Component initialFocus() {
        if (mainIsDanger && !cancels.isEmpty()) {
            return cancels.get(0);
        }
        Component input = body == null ? null : firstFocusable(body);
        if (input != null) {
            return input;
        }
        if (main != null) {
            return main;
        }
        return buttons.isEmpty() ? this : buttons.get(0);
    }

    private static Component firstFocusable(Component component) {
        if (component instanceof AbstractButton || component instanceof JTextComponent
              || component instanceof JComboBox || component instanceof JSpinner || component instanceof JList
              || component instanceof JTable || component instanceof JSlider || component instanceof JTree) {
            return component.isFocusable() && component.isEnabled() && !(component instanceof MessageText)
                  ? component : null;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component found = firstFocusable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Lays the pane out for its host: with the header and the card frame for a card, without them for a window, whose
     * title bar shows the title. {@code closer} gets the pressed button, or null when the dialog is cancelled.
     */
    void assemble(boolean card, Consumer<JButton> closer) {
        this.framed = card;
        this.closer = closer;
        removeAll();
        setOpaque(!card);
        setBackground(token("surface.panel"));
        int xl = space("xl");
        int xxl = space("xxl");
        setBorder(BorderFactory.createEmptyBorder(card ? xl : xxl, xxl, xxl, xxl));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        if (card) {
            // The header's icon buttons sit nearer the frame's corner than the text does
            c.insets = new Insets(0, 0, xl, -space("lg"));
            add(header(), c);
            c.gridy++;
        }
        int contentWidth = UIUtil.scaleForGUI(width.pixels()) - 2 * xxl;
        if (body != null) {
            if (body instanceof MessageText text) {
                text.wrapAt(contentWidth);
            }
            c.insets = new Insets(0, 0, 0, 0);
            c.weighty = 1;
            c.fill = GridBagConstraints.BOTH;
            add(body, c);
            c.gridy++;
            c.weighty = 0;
            c.fill = GridBagConstraints.HORIZONTAL;
        }
        if (extra != null) {
            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.add(extra, BorderLayout.WEST);
            c.insets = new Insets(body == null ? 0 : xl, 0, 0, 0);
            add(row, c);
            c.gridy++;
        }
        List<JButton> ordered = buttons();
        if (!ordered.isEmpty()) {
            c.insets = new Insets(body == null && extra == null ? 0 : xxl, 0, 0, 0);
            add(footer(ordered), c);
        }
        // Keeps the dialog at least its width token wide, whatever the body asks for
        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        add(Box.createHorizontalStrut(contentWidth), c);
        keys();
    }

    private JComponent header() {
        JLabel heading = new JLabel(title, status.icon(), JLabel.LEADING);
        heading.setFont(TokenFonts.font("headline"));
        heading.setForeground(token("text.primary"));
        heading.setIconTextGap(space("md"));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.TRAILING, space("xs"), 0));
        tools.setOpaque(false);
        if (help != null) {
            AbstractButton helpButton = TokenButtons.icon(ICON_HELP, Messages.getString("TokenDialog.help"), false,
                  Size.COMPACT);
            helpButton.addActionListener(event -> help.run());
            tools.add(helpButton);
        }
        AbstractButton close = TokenButtons.icon(ICON_CLOSE, Messages.getString("Close"), false, Size.COMPACT);
        close.addActionListener(event -> closer.accept(null));
        tools.add(close);

        JPanel header = new JPanel(new BorderLayout(space("md"), 0));
        header.setOpaque(false);
        header.add(heading, BorderLayout.CENTER);
        header.add(tools, BorderLayout.EAST);
        return header;
    }

    private JComponent footer(List<JButton> ordered) {
        JPanel footer = new JPanel(new EqualRow(space("md"), UIUtil.scaleForGUI(BUTTON_MIN_WIDTH)));
        footer.setOpaque(false);
        for (JButton button : ordered) {
            for (ActionListener listener : button.getActionListeners()) {
                if (listener instanceof Closing) {
                    button.removeActionListener(listener);
                }
            }
            button.addActionListener((Closing) event -> closer.accept(isCancel(button) ? null : button));
            footer.add(button);
        }
        return footer;
    }

    /** Enter presses the focused button or the default one; Escape cancels. */
    private void keys() {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "accept");
        getActionMap().put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                closer.accept(null);
            }
        });
        getActionMap().put("accept", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focused instanceof AbstractButton button && SwingUtilities.isDescendingFrom(button,
                      TokenDialogPane.this)) {
                    if (button.isEnabled()) {
                        button.doClick();
                    }
                } else if (defaultButton() != null && defaultButton().isEnabled()) {
                    defaultButton().doClick();
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!framed) {
            super.paintComponent(graphics);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            TokenFrame.paintCard(g, getWidth(), getHeight());
        } finally {
            g.dispose();
        }
    }

    static Color token(String role) {
        Mode mode = FlatLaf.isLafDark() ? Mode.DARK : Mode.LIGHT;
        return new Color(DesignTokens.shipped().color(role, mode), true);
    }

    static int space(String name) {
        return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get(name)));
    }

    /** Marks the listener that closes the dialog, so assembling again does not add a second one. */
    private interface Closing extends ActionListener {
    }

    /** Lays buttons out in a centred row, every one as wide as the widest and at least {@code minWidth}. */
    static final class EqualRow implements LayoutManager {
        private final int gap;
        private final int minWidth;

        EqualRow(int gap, int minWidth) {
            this.gap = gap;
            this.minWidth = minWidth;
        }

        private Dimension cell(Container parent) {
            Dimension cell = new Dimension(minWidth, 0);
            for (Component component : parent.getComponents()) {
                if (component.isVisible()) {
                    Dimension size = component.getPreferredSize();
                    cell.width = Math.max(cell.width, size.width);
                    cell.height = Math.max(cell.height, size.height);
                }
            }
            return cell;
        }

        private static int visible(Container parent) {
            int count = 0;
            for (Component component : parent.getComponents()) {
                if (component.isVisible()) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Dimension cell = cell(parent);
            int count = visible(parent);
            Insets insets = parent.getInsets();
            return new Dimension(count * cell.width + Math.max(0, count - 1) * gap + insets.left + insets.right,
                  cell.height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Dimension cell = cell(parent);
            int count = visible(parent);
            Insets insets = parent.getInsets();
            int rowWidth = count * cell.width + Math.max(0, count - 1) * gap;
            int x = insets.left + (parent.getWidth() - insets.left - insets.right - rowWidth) / 2;
            boolean leftToRight = parent.getComponentOrientation().isLeftToRight();
            Component[] components = parent.getComponents();
            for (int i = 0; i < components.length; i++) {
                Component component = components[leftToRight ? i : components.length - 1 - i];
                if (component.isVisible()) {
                    component.setBounds(x, insets.top, cell.width, cell.height);
                    x += cell.width + gap;
                }
            }
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    /** Body text that wraps at the dialog's content width and takes no focus. */
    static final class MessageText extends JTextArea {
        private int wrapWidth;

        MessageText(String text) {
            this(text, "body");
        }

        MessageText(String text, String type) {
            super(text);
            setEditable(false);
            setFocusable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(false);
            setBorder(null);
            setFont(TokenFonts.font(type));
            setForeground(token("text.primary"));
        }

        void wrapAt(int width) {
            wrapWidth = width;
        }

        /** A theme change installs the text area's border and margin again; the message has neither. */
        @Override
        public void updateUI() {
            super.updateUI();
            setBorder(null);
            setMargin(new Insets(0, 0, 0, 0));
        }

        @Override
        public Dimension getPreferredSize() {
            if (wrapWidth <= 0) {
                return super.getPreferredSize();
            }
            View view = getUI().getRootView(this);
            view.setSize(wrapWidth, Integer.MAX_VALUE);
            Insets insets = getInsets();
            return new Dimension(wrapWidth, (int) Math.ceil(view.getPreferredSpan(View.Y_AXIS))
                  + insets.top + insets.bottom);
        }
    }
}
