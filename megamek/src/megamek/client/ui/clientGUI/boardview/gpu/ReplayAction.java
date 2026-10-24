/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

/**
 * One unit's action as a replay shows it: a volley's shots together, an aircraft's whole attack run, or one step with
 * nothing to animate (an attack not fired, a piloting roll), whose card shows at once. {@link ReplayActions} groups the
 * presented steps into these.
 *
 * @param steps  the action's steps, in report order; the cards between its shots belong to it
 * @param volley the volley's retained shots, in report order; empty for a run and for a card-only step
 * @param run    the attack run the action re-flies, or {@code null}
 */
record ReplayAction(List<GpuPlaybackHistory.Step> steps, List<BoardScene.Combat> volley, AttackRun run) {

    ReplayAction {
        steps = List.copyOf(steps);
        volley = List.copyOf(volley);
    }

    /** @return {@code true} if the action has a step at {@code step} */
    boolean contains(GpuPlaybackHistory.Step step) {
        return steps.contains(step);
    }
}
