/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.WeaponType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityWeightClass;
import megamek.common.units.UnitRole;

/**
 * What the Requisition view's dossier tells of one unit, read from its summary in the unit cache alone, so it shows at
 * once without loading the unit file: the header's name and details, Battle and Point Value and cost; the stat strip's
 * movement, armor and structure; the armament; and the facts below it (weight class, tech base, role, rules level,
 * year of introduction and source).
 *
 * @param name      chassis and model
 * @param details   tonnage · type · tech base · year
 * @param bv        the Battle Value for the unit file's skills
 * @param pv        the Alpha Strike Point Value
 * @param cost      the cost in C-bills, formatted, or empty when the cache has none
 * @param movement  walk, run and jump, as "4 / 6 / 4"
 * @param armor     the total armor points
 * @param structure the total internal structure points
 * @param weapons   the weapons, each with how many the unit mounts, in the unit file's order
 * @param facts     the facts under the stat strip, each a label and its value
 */
public record RequisitionDossier(String name, String details, int bv, int pv, String cost, String movement, int armor,
      int structure, List<Line> weapons, List<Fact> facts) {

    /** A weapon and how many of it the unit mounts. */
    public record Line(String name, int count) { }

    /** One fact under the stat strip: its label and value. */
    public record Fact(String label, String value) { }

    public RequisitionDossier {
        weapons = List.copyOf(weapons);
        facts = List.copyOf(facts);
    }

    /** The dossier of {@code unit}, its weapons named through the equipment types. */
    public static RequisitionDossier of(MekSummary unit) {
        return of(unit, EquipmentType::get);
    }

    /** The dossier of {@code unit}, its equipment looked up through {@code equipment}. */
    public static RequisitionDossier of(MekSummary unit, Function<String, EquipmentType> equipment) {
        String movement = Messages.getString("Lobby.requisition.movement", unit.getWalkMp(), unit.getRunMp(),
              unit.getJumpMp());
        long cost = unit.getCost();
        String costText = (cost > 0) ? Messages.getString("Lobby.requisition.cost",
              NumberFormat.getIntegerInstance().format(cost)) : "";
        return new RequisitionDossier(unit.getName(), RequisitionRow.details(unit), unit.getBV(),
              unit.getPointValue(), costText, movement, unit.getTotalArmor(), unit.getTotalInternal(),
              weapons(unit.getEquipmentNames(), unit.getEquipmentQuantities(), equipment), facts(unit));
    }

    /**
     * The weapons among a unit's equipment, by their display names, merging the entries that share a name (such as a
     * weapon mounted in several locations), in the order they first appear; the rest of the equipment is left out.
     */
    public static List<Line> weapons(@Nullable List<String> names, @Nullable List<Integer> counts,
          Function<String, EquipmentType> equipment) {
        if ((names == null) || (counts == null)) {
            return List.of();
        }
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(names.size(), counts.size()); i++) {
            EquipmentType type = equipment.apply(names.get(i));
            if (type instanceof WeaponType) {
                merged.merge(type.getName(), counts.get(i), Integer::sum);
            }
        }
        List<Line> lines = new ArrayList<>();
        merged.forEach((name, count) -> lines.add(new Line(name, count)));
        return lines;
    }

    /** The facts under the stat strip, those the unit has. */
    public static List<Fact> facts(MekSummary unit) {
        List<Fact> facts = new ArrayList<>();
        int weightClass = unit.getWeightClass();
        if ((weightClass >= 0) && (weightClass < EntityWeightClass.SIZE)) {
            facts.add(fact("weight", EntityWeightClass.getClassName(weightClass)));
        }
        facts.add(fact("tech", Messages.getString(RequisitionCatalog.TechBase.of(unit.getTechBase()).messageKey())));
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
        return facts;
    }

    private static Fact fact(String key, String value) {
        return new Fact(Messages.getString("Lobby.requisition.fact." + key), value);
    }
}
