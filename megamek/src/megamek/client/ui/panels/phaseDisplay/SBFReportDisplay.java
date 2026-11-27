/*
 * Copyright (C) 2000-2004 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2002-2025 The MegaMek Team. All Rights Reserved.
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

import java.awt.event.ActionEvent;
import java.util.List;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.SBFClientGUI;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.enums.GamePhase;
import megamek.common.event.GamePhaseChangeEvent;

/** Acknowledges the current report. Reports and player lists are shown by the native board. */
public class SBFReportDisplay extends StatusBarPhaseDisplay {
    public SBFReportDisplay(SBFClientGUI clientGUI) {
        super(clientGUI);
        setupStatusBar("");
        butDone.setText(Messages.getString("ReportDisplay.Done"));
        butDone.setEnabled(false);
        setupButtonPanel();
        clientGUI.getClient().getGame().addGameListener(this);
    }

    @Override
    protected List<MegaMekButton> getButtonList() {
        return List.of();
    }

    @Override
    protected void setButtons() {}

    @Override
    protected void setButtonsTooltips() {}

    @Override
    public void clear() {}

    @Override
    public void actionPerformed(ActionEvent event) {}

    @Override
    public void ready() {
        butDone.setEnabled(false);
        sendDone();
    }

    @Override
    public void gamePhaseChange(GamePhaseChangeEvent event) {
        if (!isIgnoringEvents()) {
            GamePhase phase = event.getNewPhase();
            if (phase.isReport()) {
                butDone.setEnabled(!clientGUI.getClient().getLocalPlayer().isDone());
                setStatusBarWithNotDonePlayers();
            } else {
                setStatusBarText(phase.localizedName());
            }
        }
    }

    @Override
    public void removeAllListeners() {
        clientGUI.getClient().getGame().removeGameListener(this);
    }
}
