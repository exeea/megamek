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
 * heat-sustained curves, one value per range from 1 to the longest reach; their averages over the short, medium and
 * long range bands the reach covers; and each of the six directions' averages and reach. Ships with capital weapons
 * read in capital points (one for ten).
 *
 * @param gunnery    the gunnery skill of the expected and sustained curves
 * @param capital    whether the values are in capital points
 * @param maximum    the maximum damage at range 1 to the reach, index 0 for range 1
 * @param expected   the expected damage, likewise
 * @param sustained  the heat-sustained damage, likewise
 * @param ranges     the range bands the reach covers, short first
 * @param directions the six directions, front first and then clockwise
 */
public record RequisitionDamage(int gunnery, boolean capital, double[] maximum, double[] expected,
      double[] sustained, List<RangeBand> ranges, List<Direction> directions) {
    /** One capital point is ten standard damage points. */
    static final int CAPITAL = 10;
    /** The last range of the short band, as the damage analysis' direction averages count it. */
    public static final int SHORT_END = 7;
    /** The last range of the medium band; the long band runs from the next to the reach. */
    public static final int MEDIUM_END = 14;
    private static final String[] DIRECTION_KEYS = { "front", "frontRight", "rearRight", "rear", "rearLeft",
                                                     "frontLeft" };

    /** The short, medium and long range bands, in the damage analysis' ranges. */
    public enum Band {
        SHORT(1, SHORT_END), MEDIUM(SHORT_END + 1, MEDIUM_END), LONG(MEDIUM_END + 1, Integer.MAX_VALUE);

        private final int from;
        private final int to;

        Band(int from, int to) {
            this.from = from;
            this.to = to;
        }

        /** The band's first range. */
        public int from() {
            return from;
        }

        /** The band's last range, as far as {@code reach} goes. */
        public int to(int reach) {
            return Math.min(to, reach);
        }

        /** The band's name, as "Short". */
        public String label() {
            return Messages.getString("Lobby.requisition.damage." + name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * One range band within the reach, {@code from} to {@code to}, and each curve's average over it.
     */
    public record RangeBand(Band band, int from, int to, double maximum, double expected, double sustained) {
        /** The ranges, as "4" or "4–6". */
        public String ranges() {
            return (from == to) ? Integer.toString(from) : from + "\u2013" + to;
        }
    }

    /** One direction from the unit's facing: its name, its averages and the longest range any weapon reaches. */
    public record Direction(String name, double maximumAverage, double shortAverage, double mediumAverage,
          double longAverage, int reach) {
        /** The expected damage's average over {@code band} in this direction. */
        public double average(Band band) {
            return switch (band) {
                case SHORT -> shortAverage;
                case MEDIUM -> mediumAverage;
                case LONG -> longAverage;
            };
        }
    }

    public RequisitionDamage {
        ranges = List.copyOf(ranges);
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
              ranges(maximum, expected, sustained), directions);
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

    /** The short, medium and long bands the curves reach into, each curve averaged over the band's ranges. */
    static List<RangeBand> ranges(double[] maximum, double[] expected, double[] sustained) {
        List<RangeBand> ranges = new ArrayList<>();
        int reach = maximum.length;
        for (Band band : Band.values()) {
            int from = band.from();
            int to = band.to(reach);
            if (from > to) {
                break;
            }
            ranges.add(new RangeBand(band, from, to, average(maximum, from, to), average(expected, from, to),
                  average(sustained, from, to)));
        }
        return ranges;
    }

    private static double average(double[] values, int from, int to) {
        double sum = 0;
        for (int range = from; range <= to; range++) {
            sum += values[range - 1];
        }
        return sum / (to - from + 1);
    }

    /** The expected damage's average over {@code band} in each direction, front first and then clockwise. */
    public double[] arcs(Band band) {
        double[] values = new double[directions.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = directions.get(i).average(band);
        }
        return values;
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
              && Arrays.equals(sustained, damage.sustained) && ranges.equals(damage.ranges)
              && directions.equals(damage.directions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(gunnery, capital, Arrays.hashCode(maximum), ranges, directions);
    }

    @Override
    public String toString() {
        return "RequisitionDamage[gunnery=" + gunnery + ", capital=" + capital + ", ranges=" + ranges + "]";
    }
}
