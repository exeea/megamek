/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import megamek.client.Client;
import megamek.client.lobby.ForceNameRequest;
import megamek.client.lobby.LobbyError;
import megamek.client.lobby.LobbyPrompts;
import megamek.client.lobby.ReadyCheck;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.dialogs.MMDialogs.MMConfirmDialog;
import megamek.client.ui.dialogs.SBFStats.SBFStatsDialog;
import megamek.client.ui.dialogs.UnitEditorDialog;
import megamek.client.ui.dialogs.abstractDialogs.ASStatsDialog;
import megamek.client.ui.dialogs.customMek.BattlefieldSupportAssetConfigDialog;
import megamek.client.ui.dialogs.customMek.BattlefieldSupportAssetDamageDialog;
import megamek.client.ui.dialogs.customMek.CustomMekDialog;
import megamek.client.ui.dialogs.iconChooser.CamoChooserDialog;
import megamek.client.ui.dialogs.lobby.TrainOrderDialog;
import megamek.common.battlefieldSupport.BattlefieldSupportAsset;
import megamek.common.force.Force;
import megamek.common.game.Game;
import megamek.common.icons.Camouflage;
import megamek.common.units.Entity;

/** The lobby commands' Swing dialogs, centered on the client GUI's frame. */
public class SwingLobbyPrompts implements LobbyPrompts {

    private final ClientGUI clientGUI;

    /** The tab of the single-unit configuration dialog that was last shown, to reopen it on the same tab. */
    private String cmdSelectedTab = null;

    /** @param clientGUI the client GUI that owns the dialogs */
    public SwingLobbyPrompts(ClientGUI clientGUI) {
        this.clientGUI = clientGUI;
    }

    @Override
    public void error(LobbyError error, Object... args) {
        LobbyErrors.show(frame(), error, args);
    }

    @Override
    public boolean confirm(String title, String question) {
        return MMConfirmDialog.confirm(frame(), title, question);
    }

    @Override
    public void readyBlocked(ReadyCheck.Blocker blocker) {
        clientGUI.doAlertDialog(blocker.title(), blocker.message());
    }

    @Override
    public Optional<String> chooseForceName(ForceNameRequest request) {
        return ForceNameChooser.choose(frame(), request);
    }

    @Override
    public Optional<String> chooseSquadronDesignation() {
        return Optional.ofNullable(JOptionPane.showInputDialog(frame(), "Choose a squadron designation"));
    }

    @Override
    public Optional<Camouflage> chooseCamo(Camouflage initial, boolean hasIndividualCamo, List<Entity> units) {
        CamoChooserDialog ccd = new CamoChooserDialog(frame(), initial, hasIndividualCamo);
        try {
            ccd.setDisplayedEntities(new ArrayList<>(units));
            if (ccd.showDialog().isCancelled()) {
                return Optional.empty();
            }
            return Optional.of(ccd.getSelectedItem());
        } finally {
            ccd.dispose();
        }
    }

    @Override
    public CustomizeResult customizeUnits(Client client, List<Entity> units, boolean editable) {
        CustomMekDialog cmd = new CustomMekDialog(clientGUI, client, new ArrayList<>(units), editable);
        cmd.setSize(new Dimension(GUIPreferences.getInstance().getCustomUnitWidth(),
              GUIPreferences.getInstance().getCustomUnitHeight()));
        cmd.setTitle(Messages.getString("ChatLounge.CustomizeUnits"));
        cmd.setVisible(true);
        GUIPreferences.getInstance().setCustomUnitHeight(cmd.getSize().height);
        GUIPreferences.getInstance().setCustomUnitWidth(cmd.getSize().width);
        CustomizeResult result = result(cmd);
        cmd.dispose();
        return result;
    }

    @Override
    public CustomizeResult customizeUnit(Client client, Entity unit, boolean editable) {
        List<Entity> entities = new ArrayList<>();
        entities.add(unit);
        CustomMekDialog cmd = new CustomMekDialog(clientGUI, client, entities, editable);
        cmd.setSize(new Dimension(GUIPreferences.getInstance().getCustomUnitWidth(),
              GUIPreferences.getInstance().getCustomUnitHeight()));
        cmd.refreshOptions();
        cmd.refreshQuirks();
        cmd.refreshPartReps();
        cmd.setTitle(unit.getShortName());
        if (cmdSelectedTab != null) {
            cmd.setSelectedTab(cmdSelectedTab);
        }
        cmd.setVisible(true);
        GUIPreferences.getInstance().setCustomUnitHeight(cmd.getSize().height);
        GUIPreferences.getInstance().setCustomUnitWidth(cmd.getSize().width);
        cmdSelectedTab = cmd.getSelectedTab();
        CustomizeResult result = result(cmd);
        cmd.dispose();
        return result;
    }

    private static CustomizeResult result(CustomMekDialog cmd) {
        Entity next = null;
        if (cmd.isOkay() && (cmd.getStatus() != CustomMekDialog.DONE)) {
            next = cmd.getNextEntity(cmd.getStatus() == CustomMekDialog.NEXT);
        }
        return new CustomizeResult(cmd.isOkay(), next);
    }

    @Override
    public boolean configureAsset(BattlefieldSupportAsset asset, boolean editable) {
        BattlefieldSupportAssetConfigDialog dialog = new BattlefieldSupportAssetConfigDialog(frame(), asset, editable);
        if (dialog.showDialog().isConfirmed() && editable) {
            dialog.applyChoices();
            return true;
        }
        return false;
    }

    @Override
    public void editDamage(Entity unit) {
        UnitEditorDialog med = new UnitEditorDialog(frame(), unit, true);
        med.setVisible(true);
        med.dispose();
    }

    @Override
    public boolean editAssetDamage(BattlefieldSupportAsset asset) {
        BattlefieldSupportAssetDamageDialog dialog = new BattlefieldSupportAssetDamageDialog(frame(), asset);
        if (dialog.showDialog().isConfirmed()) {
            dialog.applyChoices();
            return true;
        }
        return false;
    }

    @Override
    public Optional<List<Entity>> orderTrain(Entity head, List<Entity> trailers) {
        TrainOrderDialog dialog = new TrainOrderDialog(frame(), head, trailers);
        dialog.setVisible(true);
        if (!dialog.wasConfirmed()) {
            return Optional.empty();
        }
        return Optional.of(new ArrayList<>(dialog.getOrderedTrailers()));
    }

    @Override
    public void showSbfStats(Collection<Force> forces, Game game) {
        new SBFStatsDialog(frame(), forces, game).setVisible(true);
    }

    @Override
    public void showAlphaStrikeStats(Collection<Entity> units) {
        new ASStatsDialog(frame(), units).setVisible(true);
    }

    private JFrame frame() {
        return clientGUI.getFrame();
    }
}
