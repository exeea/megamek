/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

import megamek.client.ui.Messages;
import megamek.common.analysis.DamageProfile;
import megamek.common.analysis.DamageProfile.ArcSummary;
import megamek.common.units.Entity;

/**
 * The Requisition dossier's Damage tab (gap analysis 7.3): the unit's damage by range, as the unit selector's damage
 * analysis shows it, at one gunnery skill: the maximum, the expected (weighted by the chance to hit) and the
 * heat-sustained curves, one value per range from 1 to the longest reach; the same as bands of ranges that share
 * their values; and each of the six directions' averages and reach. Ships with capital weapons read in capital
 * points (one for ten).
 *
 * @param gunnery    the gunnery skill of the expected and sustained curves
 * @param capital    whether the values are in capital points
 * @param maximum    the maximum damage at range 1 to the reach, index 0 for range 1
 * @param expected   the expected damage, likewise
 * @param sustained  the heat-sustained damage, likewise
 * @param bands      the ranges grouped where the three curves keep their values
 * @param directions the six directions, front first and then clockwise
 */
public record RequisitionDamage(int gunnery, boolean capital, double[] maximum, double[] expected,
      double[] sustained, List<Band> bands, List<Direction> directions) {
    /** One capital point is ten standard damage points. */
    static final int CAPITAL = 10;
    private static final String[] DIRECTION_KEYS = { "front", "frontRight", "rearRight", "rear", "rearLeft",
                                                     "frontLeft" };

    /** A run of ranges, {@code from} to {@code to}, over which the curves keep their values. */
    public record Band(int from, int to, double maximum, double expected, double sustained) {
        /** The ranges, as "4" or "4–6". */
        public String ranges() {
            return (from == to) ? Integer.toString(from) : from + "\u2013" + to;
        }
    }

    /** One direction from the unit's facing: its name, its averages and the longest range any weapon reaches. */
    public record Direction(String name, double maximumAverage, double shortAverage, double mediumAverage,
          double longAverage, int reach) { }

    public RequisitionDamage {
        bands = List.copyOf(bands);
        directions = List.copyOf(directions);
    }

    /** The damage of {@code entity} at {@code gunnery}, without extreme range. */
    public static RequisitionDamage of(Entity entity, int gunnery) {
        return of(DamageProfile.of(entity, false, gunnery));
    }

    /** The damage of {@code profile}; no ranges when the unit has no weapon to fire. */
    public static RequisitionDamage of(DamageProfile profile) {
        boolean capital = profile.hasCapitalScaleWeapons();
        int reach = profile.hasWeapons() ? Math.max(0, profile.maxRange()) : 0;
        double[] maximum = curve(reach, profile::maxDamage, capital);
        double[] expected = curve(reach, profile::expectedDamage, capital);
        double[] sustained = curve(reach, profile::sustainedDamage, capital);
        List<Direction> directions = new ArrayList<>();
        if (reach > 0) {
            for (int direction = 0; direction < DamageProfile.DIRECTIONS; direction++) {
                ArcSummary arc = profile.arcSummary(direction);
                double scale = capital ? CAPITAL : 1;
                directions.add(new Direction(
                      Messages.getString("DamageAnalysisPanel." + DIRECTION_KEYS[direction]),
                      arc.maximumAverage() / scale, arc.shortRangeAverage() / scale,
                      arc.mediumRangeAverage() / scale, arc.longRangeAverage() / scale, arc.reach()));
            }
        }
        return new RequisitionDamage(profile.gunnery(), capital, maximum, expected, sustained,
              bands(maximum, expected, sustained), directions);
    }

    /** Whether the unit has any damage to show. */
    public boolean hasWeapons() {
        return maximum.length > 0;
    }

    /** The longest range any weapon reaches. */
    public int reach() {
        return maximum.length;
    }

    /** The highest value of the three curves. */
    public double peak() {
        double peak = 0;
        for (double value : maximum) {
            peak = Math.max(peak, value);
        }
        return peak;
    }

    private static double[] curve(int reach, IntToDoubleFunction damage, boolean capital) {
        double[] values = new double[reach];
        for (int range = 1; range <= reach; range++) {
            values[range - 1] = damage.applyAsDouble(range) / (capital ? CAPITAL : 1);
        }
        return values;
    }

    /** The ranges grouped into bands where the three curves, as shown to one decimal, keep their values. */
    static List<Band> bands(double[] maximum, double[] expected, double[] sustained) {
        List<Band> bands = new ArrayList<>();
        int from = 1;
        for (int range = 2; range <= maximum.length + 1; range++) {
            boolean end = range > maximum.length
                  || !same(maximum[range - 1], maximum[from - 1]) || !same(expected[range - 1], expected[from - 1])
                  || !same(sustained[range - 1], sustained[from - 1]);
            if (end) {
                bands.add(new Band(from, range - 1, maximum[from - 1], expected[from - 1], sustained[from - 1]));
                from = range;
            }
        }
        return bands;
    }

    private static boolean same(double one, double other) {
        return format(one).equals(format(other));
    }

    /** A damage value as shown: to one decimal, without a trailing ".0". */
    public static String format(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    @Override
    public boolean equals(Object other) {
        return (other instanceof RequisitionDamage damage) && (gunnery == damage.gunnery)
              && (capital == damage.capital) && Arrays.equals(maximum, damage.maximum)
              && Arrays.equals(expected, damage.expected)
              && Arrays.equals(sustained, damage.sustained) && bands.equals(damage.bands)
              && directions.equals(damage.directions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gunnery, capital, Arrays.hashCode(maximum), bands, directions);
    }

    @Override
    public String toString() {
        return "RequisitionDamage[gunnery=" + gunnery + ", capital=" + capital + ", bands=" + bands + "]";
    }
}
