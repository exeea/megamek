/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.panels.phaseDisplay.lobby;

import java.util.Optional;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import megamek.client.lobby.ForceNameRequest;
import megamek.client.ui.Messages;

/**
 * Asks for a force's name, offering names that suit its side and read well on the radio ("Charlie Lance", "Gamma
 * Star"), while still taking any name typed in.
 */
final class ForceNameChooser {

    private ForceNameChooser() {}

    /**
     * @param frame   the frame to center on
     * @param request the names to offer and the force's name now
     *
     * @return the chosen name, trimmed, or empty when cancelled or left blank
     */
    static Optional<String> choose(JFrame frame, ForceNameRequest request) {
        JComboBox<String> names = new JComboBox<>(request.suggestions().toArray(new String[0]));
        names.setEditable(true);
        names.setSelectedItem((request.currentName() == null) ? "" : request.currentName());
        int result = JOptionPane.showConfirmDialog(frame, names, Messages.getString("ChatLounge.forceName.title"),
              JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return Optional.empty();
        }
        return ForceNameRequest.accept(names.getEditor().getItem());
    }
}
