/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** An attack run takes the same time whatever its length, and slows around each shot. */
class AttackRunTimelineTest {

    @Test
    void everyRunTakesTheSameClockTime() {
        for (double motion : List.of(2.0, 10.0, 40.0)) {
            AttackRunTimeline timeline = new AttackRunTimeline(motion, List.of(motion / 2));

            assertEquals(0, timeline.clockAt(0), 1e-9, "starts at once");
            assertEquals(AttackRunTimeline.RUN_SECONDS, timeline.clockAt(motion), 1e-9,
                  "a " + motion + " s flight still takes the run's time");
            assertEquals(AttackRunTimeline.RUN_SECONDS, timeline.runSeconds(), 1e-9);
        }
    }

    /** The stretch of flight around a shot takes more than twice the clock time of the same stretch at cruise. */
    @Test
    void theAircraftSlowsAroundAShot() {
        AttackRunTimeline timeline = new AttackRunTimeline(10, List.of(5.0));

        double atShot = timeline.clockAt(5.3) - timeline.clockAt(4.7);
        double atCruise = timeline.clockAt(.6) - timeline.clockAt(0);

        assertTrue(atShot > 2 * atCruise, "slower at the shot: " + atShot + " against " + atCruise);
    }

    @Test
    void motionAtUndoesClockAt() {
        AttackRunTimeline timeline = new AttackRunTimeline(10, List.of(3.0, 7.0));

        for (double motion = 0; motion <= 10; motion += .5) {
            assertEquals(motion, timeline.motionAt(timeline.clockAt(motion)), 1e-3, "at " + motion);
        }
    }

    @Test
    void clockTimeNeverRunsBackwards() {
        AttackRunTimeline timeline = new AttackRunTimeline(10, List.of(1.0, 1.2, 9.5));

        double previous = -1;
        for (double motion = 0; motion <= 10; motion += .05) {
            double clock = timeline.clockAt(motion);
            assertTrue(clock >= previous, "monotone at " + motion);
            previous = clock;
        }
    }

    @Test
    void aFlightWithoutMotionTakesNoTime() {
        AttackRunTimeline timeline = new AttackRunTimeline(0, List.of());

        assertEquals(0, timeline.runSeconds());
        assertEquals(0, timeline.motionAt(1));
    }
}
