/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import megamek.common.ResolvedAttack;

/**
 * Groups the round's presented steps into the actions a replay plays, the way live play showed them: a unit's volley
 * plays as one action, and an aircraft re-flies its whole attack run with its shots along it.
 *
 * <p>The runs are built here from the events the history sees, with the live playback's own
 * {@link AttackRunAssembler} on a copy of the firing results' burst, so a replayed run flies the path live play flew.
 * The live queue is never touched.</p>
 */
final class ReplayActions {
    /** The burst being collected, from its open marker; empty when no burst is open. */
    private final ArrayDeque<BoardScene.Animation> burst = new ArrayDeque<>();
    /** Each run's shots by their id. */
    private final Map<UUID, AttackRun> runs = new HashMap<>();

    /** Takes the frame's events on this board: a burst's runs are built once its close marker arrives. */
    void observe(List<BoardScene.Animation> timeline, int boardId) {
        for (BoardScene.Animation event : timeline) {
            if (event.boardId() != boardId) {
                continue;
            }
            if (event instanceof FiringBurst marker && marker.open()) {
                burst.clear();
                burst.add(marker);
            } else if (!burst.isEmpty()) {
                burst.add(event);
                if (event instanceof FiringBurst marker && !marker.open()) {
                    collectRuns();
                } else if (burst.size() > UnitPlayback.MAX_PENDING_EVENTS) {
                    burst.clear();
                }
            }
        }
    }

    private void collectRuns() {
        AttackRunAssembler.assemble(burst);
        for (BoardScene.Animation event : burst) {
            if (event instanceof AttackRun run) {
                for (BoardScene.Combat shot : run.shots()) {
                    runs.put(shot.result().id(), run);
                }
            }
        }
        burst.clear();
    }

    /** Forgets the runs of shots no longer retained. */
    void retain(Set<UUID> shots) {
        runs.keySet().retainAll(shots);
    }

    void clear() {
        burst.clear();
        runs.clear();
    }

    /** @return the attack run this shot was part of, or {@code null} */
    AttackRun run(UUID shot) {
        return runs.get(shot);
    }

    /**
     * @param played the presented steps, in report order
     * @param shots  the retained shots by their id
     *
     * @return the steps as actions, in order; every step is in exactly one
     */
    List<ReplayAction> group(List<GpuPlaybackHistory.Step> played, Map<UUID, BoardScene.Combat> shots) {
        List<ReplayAction> actions = new ArrayList<>();
        int index = 0;
        while (index < played.size()) {
            GpuPlaybackHistory.Step step = played.get(index);
            BoardScene.Combat shot = shotOf(step, shots);
            if (shot == null) {
                actions.add(new ReplayAction(List.of(step), List.of(), null));
                index++;
                continue;
            }
            AttackRun run = runs.get(shot.result().id());
            List<GpuPlaybackHistory.Step> steps = new ArrayList<>(List.of(step));
            List<BoardScene.Combat> volley = new ArrayList<>(List.of(shot));
            List<GpuPlaybackHistory.Step> cards = new ArrayList<>();
            int end = index;
            for (int next = index + 1; next < played.size(); next++) {
                GpuPlaybackHistory.Step nextStep = played.get(next);
                BoardScene.Combat nextShot = shotOf(nextStep, shots);
                if (nextShot == null) {
                    // A card between two shots of the action belongs to it; one after its last shot does not.
                    cards.add(nextStep);
                    continue;
                }
                if (!joins(run, volley, nextShot)) {
                    break;
                }
                steps.addAll(cards);
                cards.clear();
                steps.add(nextStep);
                volley.add(nextShot);
                end = next;
            }
            actions.add(new ReplayAction(steps, run == null ? volley : List.of(), run));
            index = end + 1;
        }
        return actions;
    }

    /** @return the action holding {@code step} */
    static ReplayAction actionOf(GpuPlaybackHistory.Step step, List<ReplayAction> actions) {
        for (ReplayAction action : actions) {
            if (action.contains(step)) {
                return action;
            }
        }
        return new ReplayAction(List.of(step), List.of(), null);
    }

    private boolean joins(AttackRun run, List<BoardScene.Combat> volley, BoardScene.Combat next) {
        AttackRun nextRun = runs.get(next.result().id());
        if ((run != null) || (nextRun != null)) {
            return run == nextRun;
        }
        return isShot(volley.getFirst()) && isShot(next) && VolleyRule.joins(VolleyRule.primary(volley), next);
    }

    private static boolean isShot(BoardScene.Combat combat) {
        return combat.result().kind() == ResolvedAttack.Kind.SHOT;
    }

    /** @return the step's retained shot, or {@code null} for a step with nothing to animate */
    private static BoardScene.Combat shotOf(GpuPlaybackHistory.Step step, Map<UUID, BoardScene.Combat> shots) {
        return step.attack() == null ? null : shots.get(step.attack().id());
    }
}
