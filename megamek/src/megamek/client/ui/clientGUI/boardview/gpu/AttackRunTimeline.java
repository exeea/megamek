/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;

/**
 * The pacing of an aircraft's attack run: it takes the same time whatever the length of the flight, and the aircraft
 * slows around each shot so the volley reads before it flies on. The timeline maps the playback clock to the flight's own
 * motion time and back.
 *
 * <p>Clock time is spread over the flight by a density of {@code 1 + SLOWDOWN * sum(bump)}, one smooth bump per shot,
 * so the aircraft covers the stretch around a shot at about a quarter of its cruising pace. The density is integrated
 * once into a table; both directions interpolate it, so they are monotone and cost a binary search.</p>
 */
final class AttackRunTimeline {
    /**
     * The run on the playback clock. The clock advances at half the shown speed ({@code UnitMotion.Speed.rate}), so
     * this is about three seconds at 1x.
     */
    static final double RUN_SECONDS = 1.5;
    /** How much more clock time the flight spends at a shot than at cruising pace. */
    static final double SLOWDOWN = 3;
    /** The share of the flight's motion time over which each shot's slowing spreads (one standard deviation). */
    static final double SPREAD = .06;
    private static final int SAMPLES = 256;

    private final double motionSeconds;
    /** The clock time at each evenly spaced motion sample, from 0 to {@link #RUN_SECONDS}. */
    private final double[] clock = new double[SAMPLES + 1];

    /**
     * @param motionSeconds     the flight's own motion time, at the speed it would play as a plain move
     * @param shotMotionSeconds the motion time at which each shot fires
     */
    AttackRunTimeline(double motionSeconds, List<Double> shotMotionSeconds) {
        this.motionSeconds = Math.max(0, motionSeconds);
        if (this.motionSeconds <= 0) {
            return;
        }
        double width = SPREAD * this.motionSeconds;
        double total = 0;
        double previous = density(0, shotMotionSeconds, width);
        for (int sample = 1; sample <= SAMPLES; sample++) {
            double motion = this.motionSeconds * sample / SAMPLES;
            double current = density(motion, shotMotionSeconds, width);
            total += (previous + current) / 2;
            clock[sample] = total;
            previous = current;
        }
        for (int sample = 1; sample <= SAMPLES; sample++) {
            clock[sample] *= RUN_SECONDS / total;
        }
    }

    /** @return the clock seconds the run's flight takes: {@link #RUN_SECONDS}, or none for a flight without motion */
    double runSeconds() {
        return (motionSeconds <= 0) ? 0 : RUN_SECONDS;
    }

    /** @return the clock time at which the flight reaches this motion time */
    double clockAt(double motion) {
        if (motionSeconds <= 0) {
            return 0;
        }
        double position = Math.clamp(motion / motionSeconds, 0, 1) * SAMPLES;
        int sample = Math.min((int) position, SAMPLES - 1);
        return clock[sample] + (clock[sample + 1] - clock[sample]) * (position - sample);
    }

    /** @return the flight's motion time at this clock time */
    double motionAt(double clockSeconds) {
        if (motionSeconds <= 0) {
            return 0;
        }
        double wanted = Math.clamp(clockSeconds, 0, RUN_SECONDS);
        int low = 0;
        int high = SAMPLES;
        while (high - low > 1) {
            int middle = (low + high) / 2;
            if (clock[middle] <= wanted) {
                low = middle;
            } else {
                high = middle;
            }
        }
        double span = clock[high] - clock[low];
        double fraction = (span <= 0) ? 0 : (wanted - clock[low]) / span;
        return motionSeconds * (low + fraction) / SAMPLES;
    }

    private static double density(double motion, List<Double> shots, double width) {
        double density = 1;
        for (double shot : shots) {
            double distance = (motion - shot) / width;
            density += SLOWDOWN * Math.exp(-.5 * distance * distance);
        }
        return density;
    }
}
