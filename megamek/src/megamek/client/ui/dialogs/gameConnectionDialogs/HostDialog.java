/*
 * Copyright (C) 2006-2025 The MegaMek Team. All Rights Reserved.
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

import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Vector;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;
import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.design.swing.TokenChoices;
import megamek.client.ui.design.swing.TokenCollapsibleSection;
import megamek.client.ui.design.swing.TokenFields;
import megamek.client.ui.design.swing.TokenFormField;
import megamek.client.ui.design.swing.TokenSections;
import megamek.codeUtilities.StringUtility;
import megamek.server.Server;

/**
 * The host game dialog shown when hosting a new game, a scenario or a saved game: the player name, then the online
 * options (password, port and the server browser) in a section that starts closed unless one of them was changed.
 */
public class HostDialog extends AbstractGameConnectionDialog {
    // Some fields are accessed for the results of the dialog
    private String serverPass;
    private boolean register;
    private String metaserver;

    private JPasswordField serverPassInput;
    private JCheckBox registerBox;
    private JTextField metaserverInput;
    private TokenFormField metaserverField;
    private TokenCollapsibleSection online;

    /** Constructs a host game dialog for hosting or loading a game. */
    public HostDialog(JFrame frame) {
        this(frame, "", null);
    }

    public HostDialog(JFrame frame, String playerName) {
        this(frame, playerName, null);
    }

    public HostDialog(JFrame frame, Vector<String> playerNames) {
        this(frame, "", playerNames);
    }

    public HostDialog(JFrame frame, String playerName, Vector<String> playerNames) {
        super(frame, Messages.getString("MegaMek.HostDialog.title"), playerName, playerNames);
    }

    @Override
    protected JComponent createBody() {
        String lastPass = getClientPreferences().getLastServerPass();
        serverPassInput = TokenFields.password(Size.REGULAR);
        serverPassInput.putClientProperty(FlatClientProperties.STYLE, "showRevealButton: true");
        serverPassInput.setText(lastPass);
        TokenFormField serverPassField = new TokenFormField(Messages.getString("MegaMek.GameConnection.password"),
              serverPassInput).setHelper(Messages.getString("MegaMek.GameConnection.passwordHelper"));

        registerBox = TokenChoices.check(Messages.getString("MegaMek.GameConnection.register"), false);
        metaserverInput = TokenFields.text(Size.REGULAR);
        metaserverInput.setText(getClientPreferences().getMetaServerName());
        metaserverField = new TokenFormField(Messages.getString("MegaMek.GameConnection.metaserver"),
              metaserverInput);
        metaserverField.setVisible(false);
        registerBox.addItemListener(event -> {
            metaserverField.setVisible(registerBox.isSelected());
            contentChanged();
        });

        boolean changed = !StringUtility.isNullOrBlank(lastPass)
              || getClientPreferences().getLastServerPort() != MMConstants.DEFAULT_PORT;
        online = TokenSections.collapsible(Messages.getString("MegaMek.GameConnection.online"),
              column(pair(serverPassField, portField()), registerBox, metaserverField), changed);
        online.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                contentChanged();
            }
        });
        return column(nameField(), online);
    }

    @Override
    protected String actionText() {
        return Messages.getString("MegaMek.GameConnection.host");
    }

    @Override
    protected int lastPort() {
        return getClientPreferences().getLastServerPort();
    }

    @Override
    protected void reveal(JComponent input) {
        if (SwingUtilities.isDescendingFrom(input, online.getContent())) {
            online.setExpanded(true);
        }
    }

    @Override
    protected void accept() {
        super.accept();
        setServerPass(new String(serverPassInput.getPassword()));
        setRegister(registerBox.isSelected());
        setMetaserver(metaserverInput.getText().trim());
        getClientPreferences().setLastServerPass(getServerPass() == null ? "" : getServerPass());
        getClientPreferences().setValue("megamek.megamek.metaservername", getMetaserver());
    }

    public String getServerPass() {
        return serverPass;
    }

    public void setServerPass(String serverPass) {
        this.serverPass = Server.validatePassword(serverPass);
    }

    public boolean isRegister() {
        return register;
    }

    public void setRegister(boolean register) {
        this.register = register;
    }

    public String getMetaserver() {
        return metaserver;
    }

    public void setMetaserver(String metaserver) {
        this.metaserver = metaserver;
    }
}
