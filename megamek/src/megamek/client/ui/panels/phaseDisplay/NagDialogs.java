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
package megamek.client.ui.panels.phaseDisplay;

import javax.swing.JCheckBox;
import javax.swing.JOptionPane;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.dialogs.ConfirmDialog;

/**
 * The "are you sure?" questions the phase displays ask before ending a turn, each with a "do not bother me again"
 * box that turns its question off in the player's preferences.
 */
final class NagDialogs {

    private NagDialogs() { }

    /**
     * Asks a yes/no question with a "do not bother" box, natively when the battle window draws dialogs, and runs
     * {@code stopAsking} when the player answers Yes with the box ticked.
     *
     * @param gui        the client GUI that shows the question
     * @param title      the question's title
     * @param body       the question
     * @param stopAsking turns this question off in the player's preferences
     *
     * @return {@code true} when the player answered Yes
     */
    static boolean confirm(ClientGUI gui, String title, String body, Runnable stopAsking) {
        JCheckBox dontBother = new JCheckBox(Messages.getString("ConfirmDialog.dontBother"));
        Integer answer = gui.askYesNo(body, title, dontBother);
        boolean confirmed;
        boolean showAgain;
        if (answer != null) {
            confirmed = answer == JOptionPane.YES_OPTION;
            showAgain = !dontBother.isSelected();
        } else {
            ConfirmDialog nag = gui.doYesNoBotherDialog(title, body);
            confirmed = nag.getAnswer();
            showAgain = nag.getShowAgain();
        }
        if (confirmed && !showAgain) {
            stopAsking.run();
        }
        return confirmed;
    }
}
