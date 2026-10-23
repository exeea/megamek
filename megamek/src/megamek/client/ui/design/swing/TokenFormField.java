/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.UIManager;

import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.swing.TokenFields.Status;
import megamek.client.ui.util.UIUtil;

/**
 * An input with its label above it and a helper or status message below it, the design system's form field. A status
 * colors the input's edge and puts its icon and text in the message, so it never relies on color alone. The message
 * line is only there while it has text. Dense option grids may still put labels to the left; this is the default.
 */
public class TokenFormField extends JPanel {
    static final String LABEL_COLOR = "MegaMek.field.labelForeground";
    static final String HELPER_COLOR = "MegaMek.field.helperForeground";

    private final JLabel label = new JLabel();
    private final JLabel message = new JLabel();
    private final JComponent input;
    private String helper;
    private Status status = Status.NONE;
    private String statusText;

    public TokenFormField(String labelText, JComponent input) {
        super(new GridBagLayout());
        this.input = input;
        setOpaque(false);
        label.setText(labelText);
        label.setLabelFor(target());
        int gap = UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("xs")));
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.weightx = 1;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.anchor = GridBagConstraints.NORTHWEST;
        add(label, cell);
        cell.insets = new Insets(gap, 0, 0, 0);
        add(input, cell);
        add(message, cell);
        refresh();
    }

    /** A hint under the input, shown while there is no status message. */
    public TokenFormField setHelper(String text) {
        helper = text;
        refresh();
        return this;
    }

    /** An error under the input, replacing the helper; null clears it. */
    public TokenFormField setError(String text) {
        return setStatus(text == null ? Status.NONE : Status.ERROR, text);
    }

    /** A warning under the input, replacing the helper; null clears it. */
    public TokenFormField setWarning(String text) {
        return setStatus(text == null ? Status.NONE : Status.WARNING, text);
    }

    public TokenFormField setStatus(Status status, String text) {
        this.status = status;
        statusText = status == Status.NONE ? null : text;
        refresh();
        return this;
    }

    public JComponent getInput() {
        return input;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the super constructor before the fields exist
        if (message != null) {
            refresh();
        }
    }

    /** The component that takes the focus and the label: the text inside a spinner or scroll pane. */
    private JComponent target() {
        if (input instanceof JSpinner spinner && spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            return editor.getTextField();
        } else if (input instanceof JScrollPane scroll && scroll.getViewport().getView() instanceof JComponent view) {
            return view;
        }
        return input;
    }

    private void refresh() {
        label.setFont(TokenFonts.font("label"));
        label.setForeground(uiColor(LABEL_COLOR, "Label.foreground"));
        message.setFont(TokenFonts.font("small"));
        TokenFields.outline(input, status);
        String text = statusText != null ? statusText : helper;
        message.setText(text);
        message.setIcon(statusText != null ? status.icon() : null);
        message.setIconTextGap(UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("xs"))));
        message.setForeground(statusText != null ? status.color() : uiColor(HELPER_COLOR, "Label.disabledForeground"));
        message.setVisible(text != null && !text.isEmpty());
        Component described = target();
        if (described instanceof JComponent component) {
            component.getAccessibleContext().setAccessibleDescription(text);
        }
        input.repaint();
    }

    private static Color uiColor(String key, String fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : UIManager.getColor(fallback);
    }
}
