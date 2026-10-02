/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.MMDialogs;

import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Image;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.border.EmptyBorder;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.util.FlatLafStyleBuilder;
import megamek.client.ui.util.FontHandler;
import megamek.client.ui.util.UIUtil;
import megamek.server.scriptedEvents.NarrativeDisplayProvider;

public class MMNarrativeStoryDialog extends MMStoryDialog {

    public MMNarrativeStoryDialog(final JFrame parent, NarrativeDisplayProvider sEvent) {
        super(parent, sEvent);
        initialize();
    }

    /**
     * Shows the story in the client's GPU battle window while that draws dialogs: its image beside its text, with OK,
     * as here. Otherwise this dialog shows.
     */
    @Override
    public void setVisible(boolean visible) {
        ClientGUI gui = visible ? ClientGUI.forFrame(getOwner()) : null;
        if ((gui != null) && shownNatively(gui)) {
            dispose();
        } else {
            super.setVisible(visible);
        }
    }

    /** @return true when the client's battle window showed the story, which the player has closed */
    private boolean shownNatively(ClientGUI gui) {
        Image image = storyImage();
        // the text pane shows the story's text as HTML
        String text = "<html>" + getStoryPoint().text() + "</html>";
        Object[] message = (image == null) ? new Object[] { text } : new Object[] { new ImageIcon(image), text };
        String ok = Messages.getString("Ok.text");
        return gui.askNative(message, getTitle(), JOptionPane.DEFAULT_OPTION, new Object[] { ok }, ok, false) != null;
    }

    @Override
    protected Container getMainPanel() {

        GridBagConstraints gbc = new GridBagConstraints();
        JPanel mainPanel = new JPanel(new GridBagLayout());

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weightx = 0.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        mainPanel.add(getImagePanel(), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        JTextPane txtDesc = new JTextPane();
        new FlatLafStyleBuilder(FontHandler.notoFont()).apply(txtDesc);
        txtDesc.setEditable(false);
        txtDesc.setContentType("text/html");
        txtDesc.setText(getStoryPoint().text());
        txtDesc.setCaretPosition(0);
        txtDesc.setBorder(new EmptyBorder(5, 20, 5, 20));
        JScrollPane scrollPane = new JScrollPane(txtDesc) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(UIUtil.scaleForGUI(400), super.getPreferredSize().height);
            }
        };
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        mainPanel.add(scrollPane, gbc);

        return mainPanel;
    }
}
