/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.phaseDisplay;

import java.util.List;
import javax.swing.JCheckBox;

import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogAnswer;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRow;
import megamek.common.annotations.Nullable;

/**
 * One pod that a pod trigger dialog ({@link TriggerAPPodDialog}, {@link TriggerBPodDialog}) offers.
 *
 * @param label       its location and name
 * @param podNum      its equipment number
 * @param triggerable whether it can be triggered now
 */
record TriggerPod(String label, int podNum, boolean triggerable) {

    /**
     * EDT: the pod dialogs' native form, asked in the client's native battle window when that draws dialogs: the pods
     * to tick, those that cannot be triggered disabled, and the dialogs' one Okay button. Esc answers as Okay does,
     * because the dialogs' close box keeps the ticks too. The answer ticks {@code boxes}, the dialog's checkbox of each
     * pod in order, which its getActions() reads.
     *
     * @return true when the native window answered, false when the dialog must show itself
     */
    static boolean askNatively(@Nullable ClientGUI gui, String title, String message, List<TriggerPod> pods,
          List<JCheckBox> boxes) {
        if (gui == null) {
            return false;
        }
        List<DialogRow> rows = pods.stream().map(pod -> new DialogRow(pod.label(), "", null, pod.triggerable()))
              .toList();
        DialogAnswer answer = gui.askRows(message, title, rows, true, List.of(), null,
              List.of(Messages.getString("Okay")), 0);
        if (answer == null) {
            return false;
        }
        for (int row = 0; row < boxes.size(); row++) {
            boxes.get(row).setSelected(answer.selected().contains(row));
        }
        return true;
    }
}
