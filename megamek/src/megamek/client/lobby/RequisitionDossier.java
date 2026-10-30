/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponMounted;
import megamek.common.equipment.WeaponType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.UnitRole;
import megamek.common.units.UnitType;

/**
 * What the Requisition view's dossier tells of one unit (gap analysis 7.3), read from its summary in the unit cache
 * alone, so it shows at once without loading the unit file: the header's name and details and its Battle and Point
 * Value; the Overview tab's stat strip (movement, heat, armor and structure, weapons) and the facts under it (role,
 * rules level, year of introduction, source and cost). The unit's full record, once it has loaded, fills in what the
 * summary lacks ({@link Stats#with(Entity)}).
 *
 * @param name    chassis and model
 * @param details tonnage · type · tech base · year
 * @param bv      the Battle Value for the unit file's skills
 * @param pv      the Alpha Strike Point Value
 * @param stats   the Overview's stat strip
 * @param facts   the facts under the stat strip, each a label and its value
 */
public record RequisitionDossier(String name, String details, int bv, int pv, Stats stats, List<Fact> facts) {

    /** The dossier's tabs, in their order; the one picked stays as the Commander goes from unit to unit. */
    public enum Tab {
        OVERVIEW("overview"),
        READOUT("readout"),
        AS_CARD("ascard"),
        FACTIONS("factions"),
        DAMAGE("damage");

        private final String key;

        Tab(String key) {
            this.key = key;
        }

        /** The tab's short name, as the developer switch names it. */
        public String key() {
            return key;
        }

        public String messageKey() {
            return "Lobby.requisition.tab." + key;
        }

        /** The tab named {@code key}, or null. */
        public static @Nullable Tab of(String key) {
            for (Tab tab : values()) {
                if (tab.key.equalsIgnoreCase(key)) {
                    return tab;
                }
            }
            return null;
        }
    }

    /** {@link Stats#heat()} of a unit that sinks no heat. */
    public static final int NO_HEAT = -1;
    /** {@link Stats#heat()} of a Mek whose full record has not loaded yet. */
    public static final int HEAT_PENDING = -2;

    /**
     * The unit at a glance, for the Overview's stat strip.
     *
     * @param move         walk, run and jump points ("4 / 6 / 3"; no jump when it has none)
     * @param heat         the heat it can sink each turn, {@link #NO_HEAT} for units without heat, or
     *                     {@link #HEAT_PENDING} while a Mek's record loads
     * @param armor        armor points left and at full
     * @param structure    internal structure points left and at full
     * @param weapons      the weapons, most numerous first, as "2 ML · PPC", or empty
     */
    public record Stats(String move, int heat, int armor, int armorMax, int structure, int structureMax,
          String weapons) {

        /** The stats with what {@code entity}, the unit's full record, adds: its heat sinking. */
        public Stats with(Entity entity) {
            return new Stats(move, (entity instanceof Mek) ? entity.getHeatCapacity() : NO_HEAT, armor, armorMax,
                  structure, structureMax, weapons);
        }
    }

    /** A weapon and how many of it the unit mounts. */
    public record Line(String name, int count) { }

    /** One fact under the stat strip: its label and value. */
    public record Fact(String label, String value) { }

    public RequisitionDossier {
        facts = List.copyOf(facts);
    }

    /** The dossier of {@code unit}, its weapons named through the equipment types. */
    public static RequisitionDossier of(MekSummary unit) {
        return of(unit, EquipmentType::get);
    }

    /** The dossier of {@code unit}, its equipment looked up through {@code equipment}. */
    public static RequisitionDossier of(MekSummary unit, Function<String, EquipmentType> equipment) {
        return new RequisitionDossier(unit.getName(), RequisitionRow.details(unit), unit.getBV(),
              unit.getPointValue(), stats(unit, equipment), facts(unit));
    }

    /** The stat strip of {@code unit} from its summary, its weapons by their short names. */
    public static Stats stats(MekSummary unit, Function<String, EquipmentType> equipment) {
        String move = Messages.getString("Lobby.requisition.move", unit.getWalkMp(), unit.getRunMp());
        if (unit.getJumpMp() > 0) {
            move = Messages.getString("Lobby.requisition.moveJump", unit.getWalkMp(), unit.getRunMp(),
                  unit.getJumpMp());
        }
        boolean mek = UnitType.determineUnitTypeCode(unit.getUnitType()) == UnitType.MEK;
        int armor = Math.max(0, unit.getTotalArmor());
        int structure = Math.max(0, unit.getTotalInternal());
        List<Line> lines = weapons(unit.getEquipmentNames(), unit.getEquipmentQuantities(), equipment,
              EquipmentType::getShortName);
        return new Stats(move, mek ? HEAT_PENDING : NO_HEAT, armor, armor, structure, structure, strip(lines));
    }

    /** The weapons as the stat strip names them: most numerous first, otherwise in order, as "2 ML · PPC". */
    public static String strip(List<Line> lines) {
        List<Line> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparingInt(Line::count).reversed());
        List<String> parts = new ArrayList<>();
        for (Line line : sorted) {
            parts.add(line.count() > 1 ? line.count() + " " + line.name() : line.name());
        }
        return String.join(" \u00B7 ", parts);
    }

    /** The weapons of {@code entity}, as the stat strip names them. */
    public static String strip(Entity entity) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WeaponMounted weapon : entity.getWeaponList()) {
            counts.merge(weapon.getType().getShortName(), 1, Integer::sum);
        }
        List<Line> lines = new ArrayList<>();
        counts.forEach((name, count) -> lines.add(new Line(name, count)));
        return strip(lines);
    }

    /**
     * The weapons among a unit's equipment, by their display names, merging the entries that share a name (such as a
     * weapon mounted in several locations), in the order they first appear; the rest of the equipment is left out.
     */
    public static List<Line> weapons(@Nullable List<String> names, @Nullable List<Integer> counts,
          Function<String, EquipmentType> equipment) {
        return weapons(names, counts, equipment, EquipmentType::getName);
    }

    /** The weapons as {@link #weapons(List, List, Function)}, each named by {@code naming}. */
    public static List<Line> weapons(@Nullable List<String> names, @Nullable List<Integer> counts,
          Function<String, EquipmentType> equipment, Function<EquipmentType, String> naming) {
        if ((names == null) || (counts == null)) {
            return List.of();
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(names.size(), counts.size()); i++) {
            EquipmentType type = equipment.apply(names.get(i));
            if (type instanceof WeaponType) {
                merged.merge(naming.apply(type), counts.get(i), Integer::sum);
            }
        }
        List<Line> lines = new ArrayList<>();
        merged.forEach((name, count) -> lines.add(new Line(name, count)));
        return lines;
    }

    /** The facts under the stat strip, those the unit has: role, rules level, introduced, source and cost. */
    public static List<Fact> facts(MekSummary unit) {
        List<Fact> facts = new ArrayList<>();
        UnitRole role = RequisitionCatalog.roleOf(unit);
        if (role.hasRole()) {
            facts.add(fact("role", role.toString()));
        }
        if ((unit.getTechLevel() != null) && !unit.getTechLevel().isBlank()) {
            facts.add(fact("level", unit.getTechLevel()));
        }
        facts.add(fact("year", Integer.toString(unit.getYear())));
        String source = RequisitionCatalog.sourceOf(unit);
        if (!source.isEmpty()) {
            facts.add(fact("source", source));
        }
        long cost = unit.getCost();
        if (cost > 0) {
            facts.add(new Fact(Messages.getString("Lobby.requisition.stat.cost"),
                  Messages.getString("Lobby.requisition.cost", NumberFormat.getIntegerInstance().format(cost))));
        }
        return facts;
    }

    private static Fact fact(String key, String value) {
        return new Fact(Messages.getString("Lobby.requisition.fact." + key), value);
    }
}
