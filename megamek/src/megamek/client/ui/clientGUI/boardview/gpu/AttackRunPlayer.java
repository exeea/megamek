/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/**
 * Plays one {@link AttackRun} on the playback's combat clock. The aircraft flies its path at the run's pace
 * ({@link AttackRunTimeline}); each shot launches as the aircraft is over the hex the shot was made from, so its muzzles,
 * which follow the moving model, fire from there. A shot whose hex is not on the path fires at the end of the flight.
 */
final class AttackRunPlayer {
    private static final MMLogger LOGGER = MMLogger.create(AttackRunPlayer.class);
    /**
     * Hexes of the flight path before the firing hex at which a shot's animation starts. The firing hex is the one
     * before the target, so with one more the attack starts two hexes out and reads as the aircraft closing in.
     */
    static final int LEAD_HEXES = 1;
    /** The steepest the nose pitches in a roll-in or a pull-out, in degrees. */
    static final float MAX_PITCH_DEGREES = 25;
    /** How much of the gap to the flight's climb or dive angle the nose closes each step, so it never twitches. */
    private static final float PITCH_EASING = .2f;

    private final AttackRun run;
    private final UnitMotion motion;
    private final AttackRunTimeline timeline;
    private final List<UnitAttack> shots = new ArrayList<>();
    /** The flight's motion time already played. */
    private double motionPlayed;
    /** Where the aircraft was at the last step, to measure its climb or dive. */
    private final Vector3 previous = new Vector3();
    /** The nose's pitch now, in degrees: negative nose down. */
    private float pitch;

    /**
     * @param run    the run
     * @param motion the aircraft's motion, with the run's flight just appended
     */
    AttackRunPlayer(AttackRun run, UnitMotion motion) {
        this.run = run;
        this.motion = motion;
        previous.set(motion.position());
        double motionSeconds = motion.remainingSeconds();
        List<Double> fireAt = new ArrayList<>();
        for (BoardScene.Combat combat : run.shots()) {
            // Where the server says the shot was made from (the approach hex), not the captured unit's pose.
            Coords firing = combat.result().attacker().coords();
            Coords lead = leadHex(run.flight().path(), firing);
            double reach = motion.secondsToReach(lead);
            if (reach < 0) {
                LOGGER.info("[AirRun] unit {}: firing hex {} is not on its flight path, so the shot fires at the end",
                      run.entityId(), firing.getBoardNum());
                reach = motionSeconds;
            }
            fireAt.add(reach);
        }
        timeline = new AttackRunTimeline(motionSeconds, fireAt);
        for (int index = 0; index < run.shots().size(); index++) {
            BoardScene.Combat combat = run.shots().get(index);
            UnitAttack shot = new UnitAttack(combat);
            double launch = timeline.clockAt(fireAt.get(index));
            // The shot's own clock runs its wind-up before it launches; launch over the firing hex.
            shot.delay = (float) Math.max(0, launch - UnitAttack.ANTICIPATION_SECONDS);
            shots.add(shot);
            Coords firing = combat.result().attacker().coords();
            LOGGER.info("[AirRun] unit {}: shot at {} from {} starts over {} at {} of the run's {} clock seconds",
                  run.entityId(), combat.target() == null ? "a hex" : combat.target().name(), firing.getBoardNum(),
                  leadHex(run.flight().path(), firing).getBoardNum(), String.format("%.2f", launch),
                  String.format("%.2f", timeline.runSeconds()));
        }
    }

    /**
     * The hex {@link #LEAD_HEXES} hexes before the firing hex along the flight path, where the shot's animation starts;
     * the path's first hex when it is shorter, and the firing hex itself when it is not on the path.
     */
    static Coords leadHex(List<BoardScene.Waypoint> path, Coords firing) {
        int index = -1;
        for (int step = 0; step < path.size(); step++) {
            if (path.get(step).coords().equals(firing)) {
                index = step;
                break;
            }
        }
        if (index < 0) {
            return firing;
        }
        Coords lead = firing;
        int stepsBack = 0;
        // Walk back over distinct hexes: a turn in place repeats a hex on the path.
        for (int step = index - 1; (step >= 0) && (stepsBack < LEAD_HEXES); step--) {
            Coords earlier = path.get(step).coords();
            if (!earlier.equals(lead)) {
                lead = earlier;
                stepsBack++;
            }
        }
        return lead;
    }

    /** The run being played. */
    AttackRun run() {
        return run;
    }

    /** The run's shots, timed on the combat clock. */
    List<UnitAttack> shots() {
        return shots;
    }

    /** @return the clock seconds until the last shot has finished and the flight has ended */
    double duration() {
        double duration = timeline.runSeconds();
        for (UnitAttack shot : shots) {
            duration = Math.max(duration, shot.delay + shot.duration);
        }
        return duration;
    }

    /** @return the clock seconds of the run's last impact, or none for a run without shots */
    double contact() {
        double contact = 0;
        for (UnitAttack shot : shots) {
            contact = Math.max(contact, shot.delay + shot.contactSeconds);
        }
        return contact;
    }

    /** Moves the aircraft to where the run puts it at this clock time, and turns its nose with its climb or dive. */
    void advance(double clockSeconds) {
        double wanted = timeline.motionAt(clockSeconds);
        if (wanted > motionPlayed) {
            motion.advance(wanted - motionPlayed, 1);
            motionPlayed = wanted;
        }
        Vector3 now = motion.position();
        float across = Vector3.len(now.x - previous.x, now.y - previous.y, 0);
        float target = 0;
        if (motion.isMoving() && (across > 1e-4f)) {
            target = MathUtils.clamp(MathUtils.atan2(now.z - previous.z, across) * MathUtils.radiansToDegrees,
                  -MAX_PITCH_DEGREES, MAX_PITCH_DEGREES);
        }
        pitch += (target - pitch) * PITCH_EASING;
        previous.set(now);
    }

    /** @return the nose's pitch now, in degrees: negative while the aircraft dives, positive while it climbs */
    float pitchDegrees() {
        return pitch;
    }
}
