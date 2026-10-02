/*
 * Copyright (C) 2004 Ben Mazur (bmazur@sev.org)
 * Copyright (C) 2004-2025 The MegaMek Team. All Rights Reserved.
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

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Vector;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextArea;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.units.Entity;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.actions.TriggerAPPodAction;

/**
 * A dialog displayed to the player when they have an opportunity to trigger an Anti-Personal Pod on one of their
 * units.
 */
public class TriggerAPPodDialog extends JDialog implements ActionListener {
    @Serial
    private static final long serialVersionUID = -9009039614015364943L;

    /**
     * The <code>FirePodTracker</code>s for the entity's active AP Pods.
     */
    private final ArrayList<TriggerPodTracker> trackers = new ArrayList<>();

    /**
     * The <code>int</code> ID of the entity that can fire AP Pods.
     */
    private final int entityId;

    /** The question above the pods. */
    private final String message;

    /** The entity's AP Pods in equipment order, and the checkbox of each. */
    private final List<TriggerPod> pods;
    private final List<JCheckBox> boxes = new ArrayList<>();

    /**
     * A helper class to track when an AP Pod has been selected to be triggered.
     *
     * @param podNum   The equipment number of the AP Pod that this is listening to.
     * @param checkbox The <code>JCheckBox</code> being tracked.
     */
    private record TriggerPodTracker(JCheckBox checkbox, int podNum) {

        /**
         * Create a tracker.
         */
        private TriggerPodTracker {
        }

        /**
         * See if this AP Pod should be triggered
         *
         * @return <code>true</code> if the pod should be triggered.
         */
        public boolean isTriggered() {
            return checkbox.isSelected();
        }

        /**
         * Get the equipment number of this AP Pod.
         *
         * @return the <code>int</code> of the pod.
         */
        public int getNum() {
            return podNum;
        }
    }

    /**
     * Display a dialog that shows the AP Pods on the entity, and allows the player to fire any active pods.
     *
     * @param parent the <code>Frame</code> parent of this dialog
     * @param entity the <code>Entity</code> that can fire AP Pods.
     */
    public TriggerAPPodDialog(JFrame parent, Entity entity) {
        super(parent, Messages.getString("TriggerAPPodDialog.title"), true);
        entityId = entity.getId();
        message = Messages.getString("TriggerAPPodDialog.selectPodsToTrigger", entity.getDisplayName());

        JTextArea labMessage = new JTextArea(message);
        labMessage.setEditable(false);
        labMessage.setOpaque(false);

        // AP Pod checkbox panel.
        JPanel panPods = new JPanel();
        panPods.setLayout(new GridLayout(0, 1));

        // A checkbox for each of the entity's AP Pods; only the ones that can fire get a tracker.
        pods = podsOf(entity);
        for (TriggerPod pod : pods) {
            JCheckBox box = new JCheckBox(pod.label());
            panPods.add(box);
            boxes.add(box);
            if (pod.triggerable()) {
                trackers.add(new TriggerPodTracker(box, pod.podNum()));
            } else {
                box.setEnabled(false);
            }
        }

        // OK button.
        JButton butOkay = new JButton(Messages.getString("Okay"));
        butOkay.addActionListener(this);

        // layout
        GridBagLayout gridBagLayout = new GridBagLayout();
        GridBagConstraints c = new GridBagConstraints();
        getContentPane().setLayout(gridBagLayout);

        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(10, 10, 10, 10);
        c.weightx = 1.0;
        c.weighty = 0.0;
        c.gridwidth = GridBagConstraints.REMAINDER;
        gridBagLayout.setConstraints(labMessage, c);
        getContentPane().add(labMessage);

        gridBagLayout.setConstraints(panPods, c);
        getContentPane().add(panPods);

        c.weightx = 1.0;
        c.weighty = 1.0;
        c.fill = GridBagConstraints.VERTICAL;
        c.ipadx = 20;
        c.ipady = 5;
        gridBagLayout.setConstraints(butOkay, c);
        getContentPane().add(butOkay);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                setVisible(false);
            }
        });

        pack();
        Dimension size = getSize();
        if (size.width < GUIPreferences.getInstance().getMinimumSizeWidth()) {
            size.width = GUIPreferences.getInstance().getMinimumSizeWidth();
        }
        if (size.height < GUIPreferences.getInstance().getMinimumSizeHeight()) {
            size.height = GUIPreferences.getInstance().getMinimumSizeHeight();
        }
        setResizable(false);
        setLocation(parent.getLocation().x + parent.getSize().width / 2
                    - size.width / 2,
              parent.getLocation().y
                    + parent.getSize().height / 2 - size.height / 2);
    }

    /**
     * The entity's AP Pods in equipment order: each with its location and name, and whether it can fire now.
     */
    private static List<TriggerPod> podsOf(Entity entity) {
        List<TriggerPod> pods = new ArrayList<>();
        for (Mounted<?> mount : entity.getMisc()) {
            if (mount.getType().hasFlag(MiscType.F_AP_POD)) {
                pods.add(new TriggerPod(entity.getLocationName(mount.getLocation()) + ' ' + mount.getName(),
                      entity.getEquipmentNum(mount), mount.canFire()));
            }
        }
        return pods;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        setVisible(false);
    }

    /**
     * Showing the dialog asks in the client's native battle window instead when that draws dialogs
     * ({@link TriggerPod#askNatively}); {@link #getActions()} then triggers the ticked pods as it does here.
     */
    @Override
    public void setVisible(boolean visible) {
        if (!visible || !TriggerPod.askNatively(ClientGUI.forFrame(getOwner()), getTitle(), message, pods, boxes)) {
            super.setVisible(visible);
        }
    }

    /**
     * Get the trigger actions that the user selected.
     *
     * @return the <code>Enumeration</code> of <code>TriggerAPPodAction</code> objects that match the user's selections.
     */
    public Enumeration<TriggerAPPodAction> getActions() {
        Vector<TriggerAPPodAction> temp = new Vector<>();

        // Walk through the list of AP Pod trackers.
        for (TriggerPodTracker pod : trackers) {

            // Should we create an action for this pod?
            if (pod.isTriggered()) {
                temp.addElement(new TriggerAPPodAction(entityId, pod.getNum()));
            }
        }

        return temp.elements();
    }

}
