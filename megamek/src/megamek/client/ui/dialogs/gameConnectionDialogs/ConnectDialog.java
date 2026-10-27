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

import java.util.List;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTextField;

import megamek.client.ui.Messages;
import megamek.client.ui.design.swing.TokenButtons.Size;
import megamek.client.ui.design.swing.TokenFields;
import megamek.client.ui.design.swing.TokenFormField;
import megamek.codeUtilities.StringUtility;

/** The Connect to game (as Bot or Player) dialog: the player name, then the server address beside its port. */
public class ConnectDialog extends AbstractGameConnectionDialog {
    private String serverAddress;
    private JTextField serverAddressInput;
    private TokenFormField serverAddressField;

    public ConnectDialog(JFrame frame) {
        this(frame, "");
    }

    public ConnectDialog(JFrame frame, String playerName) {
        super(frame, Messages.getString("MegaMek.ConnectDialog.title"), playerName);
    }

    @Override
    protected JComponent createBody() {
        serverAddressInput = TokenFields.text(Size.REGULAR);
        serverAddressInput.setText(getClientPreferences().getLastConnectAddr());
        serverAddressField = new TokenFormField(Messages.getString("MegaMek.GameConnection.address"),
              serverAddressInput);
        return column(nameField(), pair(serverAddressField, portField()));
    }

    @Override
    protected String actionText() {
        return Messages.getString("MegaMek.GameConnection.connect");
    }

    @Override
    protected int lastPort() {
        return getClientPreferences().getLastConnectPort();
    }

    @Override
    protected void checkInputs(List<JComponent> wrong) {
        boolean blank = StringUtility.isNullOrBlank(serverAddressInput.getText());
        serverAddressField.setError(blank ? Messages.getString("MegaMek.GameConnection.addressError") : null);
        if (blank) {
            wrong.add(serverAddressInput);
        }
    }

    @Override
    protected void accept() {
        super.accept();
        setServerAddress(serverAddressInput.getText().trim());
        getClientPreferences().setLastConnectAddr(getServerAddress());
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }
}
