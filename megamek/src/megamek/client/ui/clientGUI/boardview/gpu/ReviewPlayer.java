/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import megamek.common.board.Coords;

/**
 * Plays one replayed action on its own clock, over the waiting live queue (A.10): a volley's shots timed as live play
 * timed them, or an aircraft's attack run re-flown with its shots along it. A run flies its own motion, never the
 * aircraft's live one, so the aircraft is back where the game has it when the review ends.
 */
final class ReviewPlayer {
    private final List<UnitAttack> shots = new ArrayList<>();
    private final List<UnitAttack> visibleShots = Collections.unmodifiableList(shots);
    /** The re-flown run, or null for a volley. */
    private final AttackRunPlayer run;
    /** The run's own motion, or null for a volley. */
    private final UnitMotion motion;
    private final double duration;
    private final double contact;
    private double clock;

    /** Replays a volley: its shots in report order, the first one at least. */
    ReviewPlayer(List<BoardScene.Combat> volley) {
        UnitVolley timing = new UnitVolley();
        for (BoardScene.Combat combat : volley) {
            UnitAttack shot = new UnitAttack(combat);
            shots.add(shot);
            if (shot.shot()) {
                timing.add(shot, 0);
            }
        }
        run = null;
        motion = null;
        double end = 0;
        double impact = 0;
        for (UnitAttack shot : shots) {
            end = Math.max(end, shot.delay + shot.duration);
            impact = Math.max(impact, shot.delay + shot.contactSeconds);
        }
        duration = end;
        contact = impact;
        moveShots();
    }

    /**
     * Re-flies an attack run.
     *
     * @param attackRun the run
     * @param motion    a motion of its own for the aircraft, with the run's flight appended
     */
    ReviewPlayer(AttackRun attackRun, UnitMotion motion) {
        this.motion = motion;
        run = new AttackRunPlayer(attackRun, motion);
        shots.addAll(run.shots());
        duration = run.duration();
        contact = run.contact();
        moveShots();
    }

    /**
     * Plays on by {@code clockSeconds} of combat clock.
     *
     * @param cue receives each shot with its clock before this step, for its launch and contact sounds
     */
    void advance(double clockSeconds, BiConsumer<UnitAttack, Float> cue) {
        clock = Math.min(duration, clock + Math.max(0, clockSeconds));
        if (run != null) {
            run.advance(clock);
        }
        for (UnitAttack shot : shots) {
            float previous = shot.seconds;
            shot.seconds = Math.min(shot.duration, (float) clock - shot.delay);
            cue.accept(shot, previous);
        }
    }

    private void moveShots() {
        for (UnitAttack shot : shots) {
            shot.seconds = Math.min(shot.duration, (float) clock - shot.delay);
        }
    }

    boolean done() {
        return clock + 1e-7 >= duration;
    }

    /** @return {@code true} until the action's last impact */
    boolean beforeContact() {
        return clock + 1e-7 < contact;
    }

    /** The action's shots, timed on its clock. */
    List<UnitAttack> shots() {
        return visibleShots;
    }

    /** @return the action's first own shot, its first shot when all are point defense; null for a run without shots */
    UnitAttack first() {
        for (UnitAttack shot : shots) {
            if (!shot.defensive()) {
                return shot;
            }
        }
        return shots.isEmpty() ? null : shots.getFirst();
    }

    /** @return the re-flown run's flight, or {@code null} for a volley */
    BoardScene.Movement flight() {
        return run == null ? null : run.run().flight();
    }

    /** @return the run's own motion for this unit, or {@code null} when the action does not move it */
    UnitMotion motion(int entityId) {
        return (run != null) && (run.run().entityId() == entityId) ? motion : null;
    }

    /** @return the run's nose pitch for this unit, in degrees; level for any other unit and for a volley */
    float pitch(int entityId) {
        return motion(entityId) == null ? 0 : run.pitchDegrees();
    }

    /** @return the hexes a re-flown run strikes, for the camera to frame with its flight; none for a volley */
    List<Coords> runTargets() {
        if (run == null) {
            return List.of();
        }
        List<Coords> targets = new ArrayList<>();
        for (UnitAttack shot : shots) {
            if (shot.event.target() != null) {
                targets.add(shot.event.target().location().coords());
            }
        }
        return targets;
    }
}
