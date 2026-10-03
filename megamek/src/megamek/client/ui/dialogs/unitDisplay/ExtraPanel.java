/*
 * Copyright (C) 2015-2025 The MegaMek Team. All Rights Reserved.
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
package megamek.client.ui.dialogs.unitDisplay;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;

import megamek.MMConstants;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.tooltip.UnitToolTip;
import megamek.client.ui.comboBoxes.MMComboBox;
import megamek.client.ui.dialogs.SliderDialog;
import megamek.client.ui.panels.phaseDisplay.lobby.LobbyUtility;
import megamek.client.ui.widget.BackGroundDrawer;
import megamek.client.ui.widget.SkinXMLHandler;
import megamek.client.ui.widget.UnitDisplaySkinSpecification;
import megamek.client.ui.widget.picmap.PMUtil;
import megamek.client.ui.widget.picmap.PicMap;
import megamek.common.Configuration;
import megamek.common.Player;
import megamek.common.battleArmor.BattleArmor;
import megamek.common.board.Coords;
import megamek.common.compute.ComputeECM;
import megamek.common.compute.VirtualRealityPilotingPod;
import megamek.common.compute.VirtualRealityPilotingPod.Interference;
import megamek.common.compute.VirtualRealityPilotingPod.InterferenceState;
import megamek.common.enums.GamePhase;
import megamek.common.equipment.ICarryable;
import megamek.common.equipment.INarcPod;
import megamek.common.equipment.Mounted;
import megamek.common.equipment.Sensor;
import megamek.common.game.Game;
import megamek.common.interfaces.ILocationExposureStatus;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.Tank;
import megamek.common.util.fileUtils.MegaMekFile;

/**
 * This class shows information about a unit that doesn't belong elsewhere.
 */
public class ExtraPanel extends PicMap implements ActionListener, ItemListener {
    /** The phases a hidden unit can be set to activate in; {@link GamePhase#UNKNOWN} stops a pending activation. */
    public static final List<GamePhase> HIDDEN_ACTIVATION_PHASES = List.of(GamePhase.UNKNOWN, GamePhase.MOVEMENT,
          GamePhase.FIRING, GamePhase.PHYSICAL);
    /** The heat sink control's action, which the firing displays answer by clearing their unsent attacks. */
    private static final String CHANGE_SINKS = "changeSinks";

    private final UnitDisplayPanel unitDisplayPanel;

    private final JPanel panelMain;
    private final JLabel curSensorsL;
    private final JTextArea unusedR;
    private final JTextArea carriesR;
    private final JTextArea heatR;
    private final JTextArea lastTargetR;
    private final JTextArea sinksR;
    private final JButton sinks2B;
    private final JButton dumpBombs;
    private final JButton unitReadout;
    private final JList<String> narcList;
    private int myMekId;

    private final JComboBox<String> chSensors;

    private SliderDialog prompt;

    private int sinks;
    private boolean dontChange;

    JButton activateHidden = new JButton(Messages.getString("MekDisplay.ActivateHidden.Label"));

    MMComboBox<GamePhase> comboActivateHiddenPhase = new MMComboBox<>("comboActivateHiddenPhase");

    ExtraPanel(UnitDisplayPanel unitDisplayPanel) {
        this.unitDisplayPanel = unitDisplayPanel;
        prompt = null;

        JLabel narcLabel = new JLabel(Messages.getString("MekDisplay.AffectedBy"), SwingConstants.CENTER);
        narcLabel.setOpaque(false);
        narcLabel.setForeground(Color.WHITE);

        narcList = new JList<>(new DefaultListModel<>());

        JLabel unusedL = new JLabel(Messages.getString("MekDisplay.UnusedSpace"), SwingConstants.CENTER);
        unusedL.setOpaque(false);
        unusedL.setForeground(Color.WHITE);
        unusedR = new JTextArea("", 2, 25);
        unusedR.setEditable(false);
        unusedR.setOpaque(false);
        unusedR.setForeground(Color.WHITE);

        JLabel carriesL = new JLabel(Messages.getString("MekDisplay.Carryng"), SwingConstants.CENTER);
        carriesL.setOpaque(false);
        carriesL.setForeground(Color.WHITE);
        carriesR = new JTextArea("", 4, 25);
        carriesR.setEditable(false);
        carriesR.setOpaque(false);
        carriesR.setForeground(Color.WHITE);

        JLabel sinksL = new JLabel(
              Messages.getString("MekDisplay.activeSinksLabel"),
              SwingConstants.CENTER);
        sinksL.setOpaque(false);
        sinksL.setForeground(Color.WHITE);
        sinksR = new JTextArea("", 1, 25);
        sinksR.setEditable(false);
        sinksR.setOpaque(false);
        sinksR.setForeground(Color.WHITE);

        sinks2B = new JButton(
              Messages.getString("MekDisplay.configureActiveSinksLabel"));
        sinks2B.setActionCommand(CHANGE_SINKS);
        sinks2B.addActionListener(this);

        dumpBombs = new JButton(Messages.getString("MekDisplay.DumpBombsLabel"));
        dumpBombs.setActionCommand("dumpBombs");
        dumpBombs.addActionListener(this);

        JLabel heatL = new JLabel(Messages.getString("MekDisplay.HeatEffects"), SwingConstants.CENTER);
        heatL.setOpaque(false);
        heatL.setForeground(Color.WHITE);
        heatR = new JTextArea("", 4, 25);
        heatR.setEditable(false);
        heatR.setOpaque(false);
        heatR.setForeground(Color.WHITE);

        JLabel lblLastTarget = new JLabel(Messages.getString("MekDisplay.LastTarget"),
              SwingConstants.CENTER);
        lblLastTarget.setForeground(Color.WHITE);
        lblLastTarget.setOpaque(false);
        lastTargetR = new JTextArea("", 4, 25);
        lastTargetR.setLineWrap(true);
        lastTargetR.setWrapStyleWord(true);
        lastTargetR.setEditable(false);
        lastTargetR.setOpaque(false);
        lastTargetR.setForeground(Color.WHITE);

        curSensorsL = new JLabel(Messages.getString("MekDisplay.CurrentSensors").concat(" "),
              SwingConstants.CENTER);
        curSensorsL.setForeground(Color.WHITE);
        curSensorsL.setOpaque(false);

        chSensors = new JComboBox<>();
        chSensors.addItemListener(this);

        activateHidden.setToolTipText(Messages.getString("MekDisplay.ActivateHidden.ToolTip"));
        comboActivateHiddenPhase.setToolTipText(Messages.getString("MekDisplay.ActivateHiddenPhase.ToolTip"));
        activateHidden.addActionListener(this);
        HIDDEN_ACTIVATION_PHASES.forEach(comboActivateHiddenPhase::addItem);
        comboActivateHiddenPhase.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                  boolean cellHasFocus) {
                return super.getListCellRendererComponent(list,
                      (value instanceof GamePhase phase) ? hiddenActivationLabel(phase) : value,
                      index, isSelected, cellHasFocus);
            }
        });

        unitReadout = new JButton(Messages.getString("MekDisplay.UnitReadout"));
        unitReadout.setActionCommand("UnitReadout");
        unitReadout.addActionListener(this);

        // layout choice panel
        GridBagLayout gridBagLayout;
        GridBagConstraints c;

        gridBagLayout = new GridBagLayout();
        c = new GridBagConstraints();
        panelMain = new JPanel(gridBagLayout);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(5, 9, 1, 9);
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.weighty = 0;
        c.gridy = 0;
        c.gridx = 0;
        panelMain.add(curSensorsL, c);
        c.gridy++;
        panelMain.add(chSensors, c);

        c.gridy++;
        panelMain.add(narcLabel, c);
        c.gridy++;
        c.insets = new Insets(1, 9, 1, 9);
        JScrollPane scrollPane = new JScrollPane(narcList);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panelMain.add(scrollPane, c);

        c.gridy++;
        panelMain.add(unusedL, c);
        c.gridy++;
        panelMain.add(unusedR, c);

        c.gridy++;
        panelMain.add(carriesL, c);
        c.gridy++;
        panelMain.add(carriesR, c);

        c.gridy++;
        panelMain.add(dumpBombs, c);

        c.gridy++;
        panelMain.add(sinksL, c);
        c.gridy++;
        panelMain.add(sinksR, c);
        c.gridy++;
        panelMain.add(sinks2B, c);

        c.gridy++;
        panelMain.add(heatL, c);
        c.gridy++;
        c.insets = new Insets(1, 9, 5, 9);
        panelMain.add(heatR, c);

        c.gridy++;
        c.insets = new Insets(0, 0, 0, 0);
        panelMain.add(lblLastTarget, c);
        c.gridy++;
        c.insets = new Insets(1, 9, 5, 9);
        panelMain.add(lastTargetR, c);

        c.gridy++;
        c.insets = new Insets(1, 9, 6, 9);
        panelMain.add(activateHidden, c);
        c.gridy++;
        panelMain.add(comboActivateHiddenPhase, c);

        c.gridy++;
        panelMain.add(unitReadout, c);

        c.weightx = 1;
        c.weighty = 1;
        panelMain.add(new Label(" "), c);

        setLayout(new BorderLayout());
        add(panelMain, BorderLayout.NORTH);
        panelMain.setOpaque(false);

        setBackGround();
        onResize();
    }

    @Override
    public void onResize() {
        int width = getSize().width;
        Rectangle contentBounds = getContentBounds();
        if (contentBounds == null) {
            return;
        }
        int dx = Math.round(((width - contentBounds.width) / 2.0f));
        int minLeftMargin = 8;
        if (dx < minLeftMargin) {
            dx = minLeftMargin;
        }
        int dy = 8;
        setContentMargins(dx, dy, dx, dy);
    }

    private void setBackGround() {
        UnitDisplaySkinSpecification udSpec = SkinXMLHandler.getUnitDisplaySkin();

        Image tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBackgroundTile()).toString());
        PMUtil.setImage(tile, this);
        int b = BackGroundDrawer.TILING_BOTH;
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_HORIZONTAL | BackGroundDrawer.V_ALIGN_TOP;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_HORIZONTAL | BackGroundDrawer.V_ALIGN_BOTTOM;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_VERTICAL | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getLeftLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.TILING_VERTICAL | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getRightLine()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_TOP | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopLeftCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_BOTTOM | BackGroundDrawer.H_ALIGN_LEFT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomLeftCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_TOP | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getTopRightCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

        b = BackGroundDrawer.NO_TILING | BackGroundDrawer.V_ALIGN_BOTTOM | BackGroundDrawer.H_ALIGN_RIGHT;
        tile = getToolkit().getImage(
              new MegaMekFile(Configuration.widgetsDir(), udSpec.getBottomRightCorner()).toString());
        PMUtil.setImage(tile, this);
        addBgDrawer(new BackGroundDrawer(tile, b));

    }

    /**
     * updates fields for the specified mek
     */
    public void displayMek(Entity en) {
        // Clear the "Affected By" list.
        ((DefaultListModel<String>) narcList.getModel()).removeAllElements();
        sinks = 0;
        myMekId = en.getId();
        ClientGUI clientgui = unitDisplayPanel.getClientGUI();
        if ((clientgui != null) && (clientgui.getClient().getLocalPlayer().getId() != en.getOwnerId())) {
            sinks2B.setEnabled(false);
            dumpBombs.setEnabled(false);
            chSensors.setEnabled(false);
            dontChange = true;
        } else {
            sinks2B.setEnabled(true);
            dumpBombs.setEnabled(false);
            chSensors.setEnabled(true);
            dontChange = false;
        }

        if (clientgui != null) {
            Game game = clientgui.getClient().getGame();
            GameOptions gameOptions = game.getOptions();

            DefaultListModel<String> affectedModel = (DefaultListModel<String>) narcList.getModel();
            affectedBy(game, en).forEach(affectedModel::addElement);
            if (affectedModel.getSize() == 0) {
                affectedModel.addElement(" ");
            }

            // transport values
            String unused = en.getUnusedString();
            if (unused.isBlank()) {
                unused = Messages.getString("MekDisplay.None");
            }
            unusedR.setText(unused);
            carriesR.setText(null);
            for (String carried : carried(game, en)) {
                carriesR.append(carried);
                carriesR.append("\n");
            }
            carriesR.append(searchlightText(en));

            // Show Heat Effects, but only for Meks.
            heatR.setText("");
            sinksR.setText("");

            if (en instanceof Mek m) {
                sinks2B.setEnabled(!dontChange);
                sinks = m.getActiveSinksNextRound();
                sinksR.append(activeSinksText(m, sinks));

                if (m.hasRiscHeatSinkOverrideKit()) {
                    sinksR.append(Messages.getString("MekDisplay.RiscKit"));
                }

                boolean hasTSM = false;
                boolean mtHeat = false;
                if (m.hasTSM(false)) {
                    hasTSM = true;
                }

                if (gameOptions.booleanOption(OptionsConstants.ADVANCED_COMBAT_TAC_OPS_HEAT)) {
                    mtHeat = true;
                }
                heatR.setForeground(GUIPreferences.getInstance().getColorForHeat(en.heat));
                heatR.append(HeatEffects.getHeatEffects(en.heat, mtHeat, hasTSM));
            } else {
                // Non-Meks cannot configure their heat sinks
                sinks2B.setEnabled(false);
            }

            dumpBombs.setEnabled(false);

            refreshSensorChoices(en);

            if (en.getActiveSensor() != null) {
                String sensorDesc = "";
                if (gameOptions.booleanOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS)
                      || (gameOptions.booleanOption(OptionsConstants.ADVANCED_AERO_RULES_STRATOPS_ADVANCED_SENSORS))
                      && en.isSpaceborne()) {
                    sensorDesc = UnitToolTip.getSensorDesc(en);
                }
                String tmpStr = Messages.getString("MekDisplay.CurrentSensors") + " " + sensorDesc;
                tmpStr = String.format("<html><div WIDTH=%d>%s</div></html>", 250, tmpStr);
                curSensorsL.setText(tmpStr);
            } else {
                curSensorsL.setText((Messages.getString("MekDisplay.CurrentSensors")).concat(" "));
            }
        }

        if (en.getLastTarget() != Entity.NONE) {
            lastTargetR.setText(en.getLastTargetDisplayName());
        } else {
            lastTargetR.setText(Messages.getString("MekDisplay.None"));
        }

        activateHidden.setEnabled(!dontChange && en.isHidden());
        comboActivateHiddenPhase.setEnabled(!dontChange && en.isHidden());

        onResize();
    }

    /**
     * The Extras tab's "Affected by" list for the unit: attached (i)Narc pods, a burning inferno or fire, interference,
     * an enemy ECM field, active stealth and other effects, jammed weapons and breached locations; empty when nothing
     * affects it. The Unit Display and the GPU record sheet both use it.
     */
    public static List<String> affectedBy(Game game, Entity en) {
        List<String> affected = new ArrayList<>();
        // Walk through the list of teams. There
        // can't be more teams than players.
        StringBuilder buff;
        for (Player player : game.getPlayersList()) {
            int team = player.getTeam();
            // The messages end before the player's name ("NARCed by Team of"), so a space separates them
            if (en.isNarcedBy(team) && !player.isObserver()) {
                buff = new StringBuilder(Messages.getString("MekDisplay.NARCedBy"));
                buff.append(' ').append(player.getName())
                      .append(" [").append(player.getTeamName()).append(']');
                affected.add(buff.toString());
            }

            if (en.isINarcedBy(team) && !player.isObserver()) {
                buff = new StringBuilder(Messages.getString("MekDisplay.INarcHoming"));
                buff.append(' ').append(player.getName()).append(" [")
                      .append(player.getTeamName()).append("] ")
                      .append(Messages.getString("MekDisplay.attached"))
                      .append('.');
                affected.add(buff.toString());
            }
        }

        if (en.isINarcedWith(INarcPod.ECM)) {
            affected.add(Messages.getString("MekDisplay.iNarcECMPodAttached"));
        }

        if (en.isINarcedWith(INarcPod.HAYWIRE)) {
            affected.add(Messages.getString("MekDisplay.iNarcHaywirePodAttached"));
        }

        if (en.isINarcedWith(INarcPod.NEMESIS)) {
            affected.add(Messages.getString("MekDisplay.iNarcNemesisPodAttached"));
        }

        // Show inferno track.
        if (en.infernos.isStillBurning()) {
            affected.add(Messages.getString("MekDisplay.InfernoBurnRemaining") + en.infernos.getTurnsLeftToBurn());
        }

        if ((en instanceof Tank) && ((Tank) en).isOnFire()) {
            affected.add(Messages.getString("MekDisplay.OnFire"));
        }

        // Show electromagnetic interference.
        if (en.isSufferingEMI()) {
            affected.add(Messages.getString("MekDisplay.IsEMId"));
        }

        // Show ECM affect.
        Coords pos = en.getPosition();
        if (ComputeECM.isAffectedByAngelECM(en, pos, pos)) {
            affected.add(Messages.getString("MekDisplay.InEnemyAngelECMField"));
        } else if (ComputeECM.isAffectedByECM(en, pos, pos)) {
            affected.add(Messages.getString("MekDisplay.InEnemyECMField"));
        }

        // Virtual Reality Piloting Pod under hostile interference (IO:AE p.63)
        if (en instanceof Mek mek && mek.hasVirtualRealityPilotingPod()) {
            Interference podInterference = VirtualRealityPilotingPod.getInterference(mek);
            if (podInterference.isBlinded()) {
                affected.add(Messages.getString("MekDisplay.VrppBlinded", podInterference.source()));
            } else if (podInterference.state() == InterferenceState.DEGRADED) {
                affected.add(Messages.getString("MekDisplay.VrppDegraded", podInterference.source()));
            }
        }

        // Active Stealth Armor? If yes, we're under ECM
        if (en.isStealthActive()
              && ((en instanceof Mek) || (en instanceof Tank))) {
            affected.add(Messages.getString("MekDisplay.UnderStealth"));
        }

        // burdened due to unjettisoned body-mounted missiles on BA?
        if ((en instanceof BattleArmor) && ((BattleArmor) en).isBurdened()) {
            affected.add(Messages.getString("MekDisplay.Burdened"));
        }

        // suffering from taser feedback?
        if (en.getTaserFeedBackRounds() > 0) {
            affected.add(en.getTaserFeedBackRounds()
                  + " " + Messages.getString("MekDisplay.TaserFeedBack"));
        }

        // taser interference?
        if (en.getTaserInterference() > 0) {
            affected.add("+"
                  + en.getTaserInterference() + " "
                  + Messages.getString("MekDisplay.TaserInterference"));
        }

        // suffering from TSEMP Interference?
        if (en.getTsempEffect() == MMConstants.TSEMP_EFFECT_INTERFERENCE) {
            affected.add(Messages.getString("MekDisplay.TSEMPInterference"));
        }

        // suffering from EMP Mine Interference?
        if (en.getEMPInterferenceRounds() > 0) {
            affected.add(Messages.getString("MekDisplay.EMPInterference",
                  en.getEMPInterferenceRounds()));
        }

        // suffering from EMP Mine Shutdown?
        if (en.getEMPShutdownRounds() > 0) {
            affected.add(Messages.getString("MekDisplay.EMPShutdown",
                  en.getEMPShutdownRounds()));
        }

        if (en.hasDamagedRHS()) {
            affected.add(Messages.getString("MekDisplay.RHSDamaged"));
        }

        // Show Turret Locked.
        if ((en instanceof Tank) && !((Tank) en).hasNoTurret()
              && !en.canChangeSecondaryFacing()) {
            affected.add(Messages.getString("MekDisplay.Turretlocked"));
        }

        // Show jammed weapons.
        for (Mounted<?> weapon : en.getWeaponList()) {
            if (weapon.isJammed()) {
                affected.add(weapon.getName() + Messages.getString("MekDisplay.isJammed"));
            }
        }

        // Show breached locations.
        for (int loc = 0; loc < en.locations(); loc++) {
            if (en.getLocationStatus(loc) == ILocationExposureStatus.BREACHED) {
                affected.add(en.getLocationName(loc) + Messages.getString("MekDisplay.Breached"));
            }
        }
        return affected;
    }

    /**
     * The Extras tab's "Carrying" lines for the unit: its loaded units, clubs, cargo and picked-up MekWarriors, one
     * per line. The Unit Display and the GPU record sheet both use it.
     */
    public static List<String> carried(Game game, Entity en) {
        List<String> carried = new ArrayList<>();
        for (Entity other : en.getLoadedUnits()) {
            carried.add(other.getShortName());
        }

        // Show club(s).
        for (Mounted<?> club : en.getClubs()) {
            carried.add(club.getName());
        }

        // show cargo.
        for (ICarryable cargo : en.getDistinctCarriedObjects()) {
            carried.add(cargo.specificName());
        }

        // We may not be saving captured pilots correctly on game save; some valid pilots don't have
        // entities.
        for (int pickedUpID : en.getPickedUpMekWarriors()) {
            Entity pickedUp = game.getEntity(pickedUpID);
            carried.add((pickedUp == null) ? "(ID " + pickedUpID + ")" : pickedUp.getShortName());
        }
        return carried;
    }

    /** The state of the unit's searchlight as the Extras tab shows it below what the unit carries; "" without one. */
    public static String searchlightText(Entity en) {
        if (!en.hasSearchlight()) {
            return "";
        }
        return Messages.getString(en.isUsingSearchlight() ? "MekDisplay.SearchlightOn" : "MekDisplay.SearchlightOff");
    }

    private void refreshSensorChoices(Entity en) {
        chSensors.removeItemListener(this);
        chSensors.removeAllItems();
        sensorLabels(en).forEach(chSensors::addItem);
        int next = nextSensorIndex(en);
        if (next >= 0) {
            chSensors.setSelectedIndex(next);
        }
        chSensors.addItemListener(this);
    }

    /** The text of the hidden activation list for a phase. */
    public static String hiddenActivationLabel(GamePhase phase) {
        return phase.isUnknown() ? Messages.getString("MekDisplay.ActivateHidden.StopActivating") : phase.toString();
    }

    /** The unit's sensors as the sensor list names them, in the unit's sensor order. */
    public static List<String> sensorLabels(Entity en) {
        List<String> labels = new ArrayList<>();
        for (Sensor sensor : en.getSensors()) {
            String condition = "";
            if (sensor.isBAP() && !en.hasBAP(false)) {
                condition = " (Disabled)";
            }
            labels.add(sensor.getDisplayName() + condition);
        }
        return labels;
    }

    /** The index of the sensor the unit uses from the end of the turn (the last one of its type), or -1 for none. */
    public static int nextSensorIndex(Entity en) {
        int next = -1;
        for (int i = 0; i < en.getSensors().size(); i++) {
            if ((en.getNextSensor() != null) && (en.getSensors().elementAt(i).type() == en.getNextSensor().type())) {
                next = i;
            }
        }
        return next;
    }

    /**
     * Switches the unit to one of its sensors at the end of the turn and tells the player and the server; the sensor
     * list's action. The Unit Display and the GPU record sheet both use it.
     */
    public static void setNextSensor(ClientGUI clientgui, Entity entity, int sensorIdx) {
        Sensor sensor = entity.getSensors().elementAt(sensorIdx);
        entity.setNextSensor(sensor);
        // The player picked this themselves, so their sensor preference must not override it later
        entity.setCustomSensorChoice(true);
        String sensorMsg = Messages.getString("MekDisplay.willSwitchAtEnd",
              "Active Sensors",
              sensor.getDisplayName());
        clientgui.systemMessage(sensorMsg);
        clientgui.getClient().sendSensorChange(entity.getId(), sensorIdx);
    }

    /** The panel's text for a number of active heat sinks of the Mek, such as "4 (8) Double Heat Sink(s) active". */
    public static String activeSinksText(Mek mek, int sinks) {
        return mek.hasDoubleHeatSinks() ? Messages.getString("MekDisplay.activeSinksTextDouble", sinks, sinks * 2)
              : Messages.getString("MekDisplay.activeSinksTextSingle", sinks);
    }

    /**
     * Sets how many heat sinks the Mek keeps active from the next round, as the heat sink control does: the client's
     * menu bar first hands the control's action to its listeners, among them the phase display, which in the firing
     * phase clears its unsent attacks (FiringDisplay, PointblankShotDisplay); then the Mek and the server take the
     * number. The Unit Display and the GPU unit record both use it.
     */
    public static void setActiveSinks(ClientGUI clientgui, Mek mek, int activeSinks) {
        clientgui.getMenuBar().actionPerformed(new ActionEvent(mek, ActionEvent.ACTION_PERFORMED, CHANGE_SINKS));
        mek.setActiveSinksNextRound(activeSinks);
        clientgui.getClient().sendSinksChange(mek.getId(), activeSinks);
    }

    @Override
    public void itemStateChanged(ItemEvent ev) {
        ClientGUI clientgui = unitDisplayPanel.getClientGUI();
        if (clientgui == null) {
            return;
        }
        // Only act when a new item is selected
        if (ev.getStateChange() != ItemEvent.SELECTED) {
            return;
        }
        if ((ev.getItemSelectable() == chSensors)) {
            int sensorIdx = chSensors.getSelectedIndex();
            Entity entity = clientgui.getClient().getGame().getEntity(myMekId);

            if (entity != null) {
                setNextSensor(clientgui, entity, sensorIdx);
                refreshSensorChoices(entity);
            }
        }
    }

    @Override
    public void actionPerformed(ActionEvent ae) {
        ClientGUI clientgui = unitDisplayPanel.getClientGUI();
        if (clientgui == null) {
            return;
        }
        if (CHANGE_SINKS.equals(ae.getActionCommand()) && !dontChange) {
            Entity mekEntity = clientgui.getClient().getGame().getEntity(myMekId);

            if (mekEntity instanceof Mek mek) {
                prompt = new SliderDialog(clientgui.getFrame(),
                      Messages.getString("MekDisplay.changeSinks"),
                      Messages.getString("MekDisplay.changeSinks"), sinks,
                      0, mek.getNumberOfSinks());

                if (!prompt.showDialog()) {
                    return;
                }

                setActiveSinks(clientgui, mek, prompt.getValue());
                displayMek(mek);
            }
        } else if (activateHidden.equals(ae.getSource()) && !dontChange) {
            final GamePhase phase = comboActivateHiddenPhase.getSelectedItem();
            clientgui.getClient().sendActivateHidden(myMekId, (phase == null) ? GamePhase.UNKNOWN : phase);
        } else if (unitReadout.equals(ae.getSource())) {
            Entity entity = clientgui.getClient().getGame().getEntity(myMekId);
            LobbyUtility.mekReadout(entity, 0, false, clientgui.getFrame());
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension labelPrefSize = panelMain.getPreferredSize();
        Insets insets = getInsets();
        int height = labelPrefSize.height + insets.top + insets.bottom + 20;
        Dimension superPref = super.getPreferredSize();
        return new Dimension(superPref.width, Math.max(height, superPref.height));
    }
}
