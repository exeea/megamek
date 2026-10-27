/*
 * Copyright (C) 2020-2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.dialogs.gameConnectionDialogs;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.design.DesignTokens;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.design.swing.TokenDialogPane;
import megamek.client.ui.design.swing.TokenDialogs;
import megamek.client.ui.design.swing.TokenFields;
import megamek.client.ui.design.swing.TokenFormField;
import megamek.client.ui.util.UIUtil;
import megamek.codeUtilities.StringUtility;
import megamek.common.preference.ClientPreferences;
import megamek.common.preference.PreferenceManager;

/**
 * The base of the dialogs that host or join a game: a design system card (docs/design-system/tokens.md, Dialogs) over
 * the owner's window, in app mode, with the player name, the subclass's inputs and the port. Its main action checks the
 * inputs and shows what is wrong under them instead of closing. {@link #setVisible} shows it and waits until it closes,
 * like a modal dialog; then {@link #isConfirmed} and the getters give the result.
 */
public abstract class AbstractGameConnectionDialog {
    /** The port field's width in unscaled pixels. */
    private static final int PORT_WIDTH = 128;

    private final JFrame owner;
    private final String title;
    private final Vector<String> playerNames;
    private final ClientPreferences clientPreferences = PreferenceManager.getClientPreferences();

    private String playerName = "";
    private int port = MMConstants.DEFAULT_PORT;
    private boolean confirmed;

    private TokenDialogPane pane;
    private JButton mainButton;
    private JTextField playerNameField;
    private JComboBox<String> playerNameCombo;
    private TokenFormField nameField;
    private JTextField portInput;
    private TokenFormField portField;

    protected AbstractGameConnectionDialog(JFrame owner, String title, String playerName) {
        this(owner, title, playerName, null);
    }

    /**
     * @param playerName  the name to start with, or blank for the last one used
     * @param playerNames the names to choose from, such as a saved game's players, or null to type one
     */
    protected AbstractGameConnectionDialog(JFrame owner, String title, String playerName,
          Vector<String> playerNames) {
        this.owner = owner;
        this.title = title;
        this.playerNames = playerNames;
        setPlayerName(StringUtility.isNullOrBlank(playerName) ? clientPreferences.getLastPlayerName() : playerName);
    }

    /** The inputs between the name and the buttons; {@link #nameField} and {@link #portField} are ready. */
    protected abstract JComponent createBody();

    /** The main action's label, a verb such as "Host game". */
    protected abstract String actionText();

    /** The port the dialog starts with. */
    protected abstract int lastPort();

    /**
     * Shows the dialog and waits until it closes, or does nothing for false; the dialog is always modal. A confirmed
     * dialog has stored its inputs in the getters and the preferences.
     */
    public void setVisible(boolean visible) {
        if (!visible) {
            return;
        }
        confirmed = false;
        TokenDialogPane dialog = pane();
        setPlayerNameInput(playerName);
        JButton pressed = TokenDialogs.show(owner, dialog);
        if (pressed != null && pressed == mainButton) {
            accept();
        }
    }

    /**
     * Releases the card so the next {@link #setVisible} builds it again. The dialog is closed whenever
     * {@link #setVisible} returns; callers that used it as a {@code JDialog} still call this afterwards.
     */
    public void dispose() {
        pane = null;
        mainButton = null;
        playerNameField = null;
        playerNameCombo = null;
        nameField = null;
        portInput = null;
        portField = null;
    }

    private TokenDialogPane pane() {
        if (pane == null) {
            nameField = new TokenFormField(Messages.getString("MegaMek.GameConnection.name"), createNameInput())
                  .setHelper(Messages.getString("MegaMek.GameConnection.nameHelper"));
            portInput = TokenFields.text(Size.REGULAR);
            portInput.setText(String.valueOf(lastPort()));
            portField = new TokenFormField(Messages.getString("MegaMek.GameConnection.port"), portInput) {
                // As wide as a port needs, whatever its message
                @Override
                public Dimension getPreferredSize() {
                    Dimension size = super.getPreferredSize();
                    size.width = UIUtil.scaleForGUI(PORT_WIDTH);
                    return size;
                }

                @Override
                public Dimension getMinimumSize() {
                    return getPreferredSize();
                }
            };
            pane = new TokenDialogPane(title).body(createBody());
            mainButton = pane.primary(actionText());
            pane.cancel(Messages.getString("Cancel"));
            pane.check(button -> checkInputs());
        }
        return pane;
    }

    private JComponent createNameInput() {
        if (playerNames == null) {
            playerNameField = TokenFields.text(Size.REGULAR);
            return playerNameField;
        }
        playerNameCombo = TokenFields.size(new JComboBox<>(playerNames), Size.REGULAR);
        playerNameCombo.setEditable(true);
        // The editor's own Enter only commits the text; here it should press the main action like in a text field
        JComponent editor = (JComponent) playerNameCombo.getEditor().getEditorComponent();
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit");
        editor.getActionMap().put("submit", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (playerNameCombo.isPopupVisible()) {
                    playerNameCombo.setPopupVisible(false);
                }
                playerNameCombo.setSelectedItem(playerNameCombo.getEditor().getItem());
                mainButton.doClick();
            }
        });
        return playerNameCombo;
    }

    /** Shows an error under every input that is wrong and moves the focus to the first; gives whether all are fine. */
    private boolean checkInputs() {
        List<JComponent> wrong = new ArrayList<>();
        boolean blankName = StringUtility.isNullOrBlank(getPlayerNameFromUI());
        nameField.setError(blankName ? Messages.getString("MegaMek.GameConnection.nameError") : null);
        if (blankName) {
            wrong.add(nameField.getInput());
        }
        checkInputs(wrong);
        boolean badPort = parsePort(portInput.getText()) < 0;
        portField.setError(badPort ? Messages.getString("MegaMek.GameConnection.portError",
              String.valueOf(MMConstants.MIN_PORT), String.valueOf(MMConstants.MAX_PORT)) : null);
        if (badPort) {
            wrong.add(portInput);
        }
        contentChanged();
        if (wrong.isEmpty()) {
            return true;
        }
        reveal(wrong.get(0));
        JComponent first = wrong.get(0);
        Component focus = first instanceof JComboBox<?> combo ? combo.getEditor().getEditorComponent() : first;
        focus.requestFocusInWindow();
        return false;
    }

    /** Checks the subclass's inputs, showing their errors and adding the wrong ones to {@code wrong}. */
    protected void checkInputs(List<JComponent> wrong) {
    }

    /** Makes sure {@code input} shows, such as by opening the section it is in. */
    protected void reveal(JComponent input) {
    }

    /** Reads the checked inputs into the getters and the preferences. */
    protected void accept() {
        setPlayerName(getPlayerNameFromUI());
        setPort(parsePort(portInput.getText()));
        setConfirmed(true);
        clientPreferences.setLastPlayerName(getPlayerName());
        clientPreferences.setLastConnectPort(getPort());
    }

    /** The port in {@code text}, or -1 when it is not a number in the allowed range. */
    static int parsePort(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return (value >= MMConstants.MIN_PORT && value <= MMConstants.MAX_PORT) ? value : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Keeps the dialog at its content's size after the content grows or shrinks. A card follows by itself; the window
     * it opens in when the owner's window is too small is packed again.
     */
    protected void contentChanged() {
        if (pane == null) {
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(pane);
        if (window instanceof JDialog dialog && dialog.getContentPane() == pane) {
            dialog.pack();
        } else {
            pane.revalidate();
            pane.repaint();
        }
    }

    /** Stacks {@code rows} at the full width, {@code lg} apart. */
    protected static JPanel column(JComponent... rows) {
        JPanel column = new JPanel(new GridBagLayout());
        column.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < rows.length; i++) {
            c.insets = new Insets(i == 0 ? 0 : space("lg"), 0, 0, 0);
            column.add(rows[i], c);
        }
        return column;
    }

    /** Puts {@code wide}, which takes the spare width, and {@code narrow} side by side, {@code md} apart and top aligned. */
    protected static JPanel pair(JComponent wide, JComponent narrow) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        row.add(wide, c);
        c.weightx = 0;
        c.insets = new Insets(0, space("md"), 0, 0);
        row.add(narrow, c);
        return row;
    }

    protected static int space(String name) {
        return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get(name)));
    }

    /** The player name's form field. */
    protected TokenFormField nameField() {
        return nameField;
    }

    /** The port's form field. */
    protected TokenFormField portField() {
        return portField;
    }

    public String getPlayerName() {
        return playerName;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName == null ? "" : playerName.trim();
        setPlayerNameInput(this.playerName);
    }

    private void setPlayerNameInput(String name) {
        if (playerNameField != null) {
            playerNameField.setText(name);
        } else if (playerNameCombo != null) {
            playerNameCombo.setSelectedItem(name);
        }
    }

    public String getPlayerNameFromUI() {
        if (playerNameCombo != null) {
            Object item = playerNameCombo.isEditable() ? playerNameCombo.getEditor().getItem()
                  : playerNameCombo.getSelectedItem();
            return item == null ? "" : item.toString();
        }
        return playerNameField == null ? playerName : playerNameField.getText();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    protected ClientPreferences getClientPreferences() {
        return clientPreferences;
    }

    /**
     * Whether the dialog was confirmed. Its inputs were checked before it closed, so {@code errorTitleKey}, kept for
     * callers that still pass the title of an error message, is not used.
     */
    public boolean dataValidation(String errorTitleKey) {
        return isConfirmed();
    }
}
