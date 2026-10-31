/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.Nullable;
import megamek.client.ui.Messages;
import megamek.common.alphaStrike.ASArcSummary;
import megamek.common.alphaStrike.ASCardDisplayable;
import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.AlphaStrikeHelper;

/**
 * The Requisition dossier's AS card tab (gap analysis 7.3): an Alpha Strike unit's card values, as the printed card
 * shows them, for a view that lays the card out with its own type and colours. Read from the unit's summary in the
 * cache, which holds the converted values, so the card shows at once.
 *
 * @param name      chassis and model
 * @param type      the element type, as "BM"
 * @param size      the size class
 * @param move      the movement, as "8\"j"
 * @param tmm       the target movement modifier, or empty for units without one
 * @param role      the unit's role, or empty
 * @param skill     the skill the Point Value is for
 * @param pv        the Point Value
 * @param armor     armor points
 * @param structure structure points
 * @param threshold the damage threshold, or empty for units without one
 * @param damage    the damage at each range: S, M, L (and E for aerospace)
 * @param overheat  the overheat value, or empty for units without one
 * @param specials  the special abilities, comma-separated, or empty
 * @param arcs      large craft's arcs, each with its damage and abilities, or empty
 */
public record RequisitionAlphaStrike(String name, String type, int size, String move, String tmm, String role,
      int skill, int pv, int armor, int structure, String threshold, List<Cell> damage, String overheat,
      String specials, List<Cell> arcs) {

    /** One labelled value: a range and its damage, or an arc and what it carries. */
    public record Cell(String label, String value) { }

    public RequisitionAlphaStrike {
        damage = List.copyOf(damage);
        arcs = List.copyOf(arcs);
    }

    /** The card of {@code unit}, or null when it has no Alpha Strike values (no type or no Point Value). */
    public static @Nullable RequisitionAlphaStrike of(ASCardDisplayable unit) {
        ASUnitType type = unit.getASUnitType();
        if ((type == null) || (type == ASUnitType.UNKNOWN) || (unit.getPointValue() <= 0)) {
            return null;
        }
        List<Cell> damage = new ArrayList<>();
        ASDamageVector standard = unit.getStandardDamage();
        if ((standard != null) && !unit.usesArcs()) {
            damage.add(new Cell("S", damage(standard.S())));
            damage.add(new Cell("M", damage(standard.M())));
            damage.add(new Cell("L", damage(standard.L())));
            if (unit.usesSMLE()) {
                damage.add(new Cell("E", damage(standard.E())));
            }
        }
        List<Cell> arcs = new ArrayList<>();
        if (unit.usesArcs()) {
            arc(arcs, "front", unit.getFrontArc(), unit);
            arc(arcs, "left", unit.getLeftArc(), unit);
            arc(arcs, "right", unit.getRightArc(), unit);
            arc(arcs, "rear", unit.getRearArc(), unit);
        }
        String specials = (unit.getSpecialAbilities() == null) ? ""
              : unit.getSpecialAbilities().getSpecialsDisplayString(", ", unit);
        String role = (unit.getRole() != null) && unit.getRole().hasRole() ? unit.getRole().toString() : "";
        String name = (unit.getChassis() + " " + unit.getModel()).trim();
        return new RequisitionAlphaStrike(name, type.name(), unit.getSize(),
              AlphaStrikeHelper.getMovementAsString(unit), unit.usesTMM() ? Integer.toString(unit.getTMM()) : "",
              role, unit.getSkill(), unit.getPointValue(), unit.getFullArmor(), unit.getFullStructure(),
              unit.usesThreshold() ? Integer.toString(unit.getThreshold()) : "", damage,
              unit.usesOV() ? Integer.toString(unit.getOV()) : "", specials, arcs);
    }

    private static String damage(@Nullable ASDamage damage) {
        return (damage == null) ? "0" : damage.toString();
    }

    private static void arc(List<Cell> arcs, String key, @Nullable ASArcSummary arc, ASCardDisplayable unit) {
        if (arc != null) {
            String text = arc.getSpecialsExportString(", ", unit);
            arcs.add(new Cell(Messages.getString("Lobby.requisition.as.arc." + key), text.isBlank() ? "\u2014"
                  : text));
        }
    }
}
