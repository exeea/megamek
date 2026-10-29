/*
 * Copyright (C) 2021-2026 The MegaMek Team. All Rights Reserved.
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

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import megamek.client.lobby.LobbyError;

/** Contains static methods that show common info/error messages for the lobby. */
public final class LobbyErrors {

    /**
     * Shows the given lobby error: under its title as an error when it has one, otherwise as a plain notice.
     *
     * @param owner the frame to center on
     * @param error the error to show
     * @param args  the arguments of an error that takes them
     */
    public static void show(JFrame owner, LobbyError error, Object... args) {
        String title = error.title();
        if (title == null) {
            JOptionPane.showMessageDialog(owner, error.message(args));
        } else {
            JOptionPane.showMessageDialog(owner, error.message(args), title, JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void showOnlyOwnBot(JFrame owner) {
        show(owner, LobbyError.ONLY_OWN_BOT);
    }

    public static void showOnlySingleEntityOrForce(JFrame owner) {
        show(owner, LobbyError.SINGLE_UNIT_OR_FORCE);
    }

    public static void showSingleOwnerRequired(JFrame owner) {
        show(owner, LobbyError.SINGLE_OWNER);
    }

    public static void showForceNoAttachSubForce(JFrame owner) {
        show(owner, LobbyError.FORCE_ATTACH_TO_SUB_FORCE);
    }

    public static void showNoDetachOffBoard(JFrame owner) {
        show(owner, LobbyError.NO_DETACH_OFF_BOARD);
    }

    public static void showOnlyTeam(JFrame owner) {
        show(owner, LobbyError.ONLY_TEAM);
    }

    public static void showOnlyC3M(JFrame owner) {
        show(owner, LobbyError.ONLY_C3M);
    }

    public static void showNoDualLoad(JFrame owner) {
        show(owner, LobbyError.NO_DUAL_LOAD);
    }

    public static void showNoDualTow(JFrame owner) {
        show(owner, LobbyError.NO_DUAL_TOW);
    }

    public static void showOnlyTrainMembers(JFrame owner) {
        show(owner, LobbyError.ONLY_TRAIN_MEMBERS);
    }

    public static void showSingleTractorRequired(JFrame owner) {
        show(owner, LobbyError.SINGLE_TRACTOR_REQUIRED);
    }

    public static void showNoSuchBay(JFrame owner) {
        show(owner, LobbyError.NO_SUCH_BAY);
    }

    public static void showSquadronTooMany(JFrame owner) {
        show(owner, LobbyError.SQUADRON_TOO_MANY);
    }

    public static void showOnlyFighter(JFrame owner) {
        show(owner, LobbyError.ONLY_FIGHTERS);
    }

    public static void showLoadOnlyAllied(JFrame owner) {
        show(owner, LobbyError.LOAD_ONLY_ALLIED);
    }

    public static void showExceedC3Capacity(JFrame owner) {
        show(owner, LobbyError.EXCEED_C3_CAPACITY);
    }

    public static void showSameC3(JFrame owner) {
        show(owner, LobbyError.SAME_C3);
    }

    public static void showCannotConfigEnemies(JFrame owner) {
        show(owner, LobbyError.CANNOT_CONFIG_ENEMIES);
    }

    public static void showCannotViewHidden(JFrame owner) {
        show(owner, LobbyError.CANNOT_VIEW_HIDDEN);
    }

    public static void showCannotEditDamage(JFrame owner) {
        show(owner, LobbyError.CANNOT_EDIT_DAMAGE);
    }

    public static void showSingleUnit(JFrame owner, String action) {
        show(owner, LobbyError.SINGLE_UNIT, action);
    }

    public static void showTenUnits(JFrame owner) {
        show(owner, LobbyError.TEN_UNITS);
    }

    public static void showHeatTracking(JFrame owner) {
        show(owner, LobbyError.HEAT_TRACKING);
    }

    public static void showOnlyMeks(JFrame owner) {
        show(owner, LobbyError.ONLY_MEKS);
    }

    public static void showOnlyTeammate(JFrame owner) {
        show(owner, LobbyError.ONLY_TEAMMATE);
    }

    public static void showOnlyEntityOrForce(JFrame owner) {
        show(owner, LobbyError.ENTITY_OR_FORCE);
    }

    @Deprecated(since = "0.51.0", forRemoval = true)
    public static void showOnlyEmptyForce(JFrame owner) {
        show(owner, LobbyError.FORCE_EMPTY);
    }

    public static void showSBFConversion(JFrame owner) {
        show(owner, LobbyError.SBF_CONVERSION);
    }

    public static void showCannotEditWhileDone(JFrame owner) {
        show(owner, LobbyError.PLAYER_DONE);
    }

    public static void showCannotDisconnectMasterUnit(JFrame owner) {
        show(owner, LobbyError.CANNOT_DISCONNECT_MASTER);
    }

    public static void showADFReadError(JFrame owner, String message) {
        show(owner, LobbyError.ADF_READ, message);
    }

    private LobbyErrors() {}
}
