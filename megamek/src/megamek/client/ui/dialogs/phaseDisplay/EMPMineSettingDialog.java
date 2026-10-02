/*
 * Copyright (C) 2025-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.phaseDisplay;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.Serial;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextField;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.codeUtilities.StringUtility;

/**
 * Dialog to set the weight threshold for an EMP mine.
 * <p>
 * EMP mines trigger when a unit of sufficient weight enters the hex. The setting determines the minimum weight (in
 * tons) that can trigger the mine. Units must weigh more than (setting - 10) tons to trigger the mine.
 * </p>
 */
public class EMPMineSettingDialog extends JDialog implements ActionListener {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final int MIN_SETTING = 10;
    private static final int MAX_SETTING = 200;

    private final JButton butOk = new JButton(Messages.getString("Okay"));
    private final JTextField fldSetting = new JTextField("10", 3);
    private final JLabel labMessage = new JLabel(Messages.getString("EMPMineSettingDialog.selectSetting"));
    private int setting;
    private final JFrame frame;

    public EMPMineSettingDialog(JFrame p) {
        super(p, Messages.getString("EMPMineSettingDialog.title"), true);
        super.setResizable(false);
        frame = p;
        butOk.addActionListener(this);
        GridBagLayout gridBagLayout = new GridBagLayout();
        getContentPane().setLayout(gridBagLayout);
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.fill = GridBagConstraints.VERTICAL;
        gridBagConstraints.insets = new Insets(1, 1, 1, 1);
        gridBagConstraints.weightx = 1.0;
        gridBagConstraints.weighty = 0.0;
        gridBagConstraints.gridwidth = GridBagConstraints.REMAINDER;
        gridBagLayout.setConstraints(labMessage, gridBagConstraints);
        getContentPane().add(labMessage);
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.gridwidth = GridBagConstraints.REMAINDER;
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        gridBagLayout.setConstraints(fldSetting, gridBagConstraints);
        getContentPane().add(fldSetting);
        gridBagConstraints.gridwidth = GridBagConstraints.REMAINDER;
        gridBagConstraints.anchor = GridBagConstraints.CENTER;
        gridBagConstraints.weightx = 0.0;
        gridBagConstraints.weighty = 0.0;
        gridBagLayout.setConstraints(butOk, gridBagConstraints);
        getContentPane().add(butOk);
        pack();
        setLocation(p.getLocation().x + p.getSize().width / 2 - getSize().width
              / 2, p.getLocation().y + p.getSize().height / 2
              - getSize().height / 2);
    }

    public int getSetting() {
        return setting;
    }

    @Override
    public void actionPerformed(ActionEvent actionEvent) {
        if (actionEvent.getSource().equals(butOk)) {
            String s = fldSetting.getText();
            try {
                if (!StringUtility.isNullOrBlank(s)) {
                    setting = Integer.parseInt(s);
                }
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(frame,
                      Messages.getString("EMPMineSettingDialog.alert.Message"),
                      Messages.getString("EMPMineSettingDialog.alert.Title"),
                      JOptionPane.WARNING_MESSAGE);
                return;
            }
            if ((setting < MIN_SETTING) || (setting > MAX_SETTING)) {
                JOptionPane.showMessageDialog(frame,
                      Messages.getString("EMPMineSettingDialog.alert.Message"),
                      Messages.getString("EMPMineSettingDialog.alert.Title"),
                      JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        setVisible(false);
    }

    /**
     * Showing the dialog asks in the owning client's native battle window instead when that draws dialogs; only a
     * setting in the valid range can be entered there. Esc there leaves the setting unset (0), as closing this window
     * does, which the minefield deployment reads as a cancel.
     */
    @Override
    public void setVisible(boolean visible) {
        ClientGUI gui = visible ? ClientGUI.forFrame(frame) : null;
        DialogAnswer answer = (gui == null) ? null : gui.askText(labMessage.getText(), getTitle(),
              fldSetting.getText(), MIN_SETTING, MAX_SETTING, List.of(butOk.getText()), JOptionPane.CLOSED_OPTION);
        if (answer == null) {
            super.setVisible(visible);
        } else if (answer.text() != null) {
            setting = Integer.parseInt(answer.text());
        }
    }
}
