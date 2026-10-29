/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.lobby;

import java.util.Locale;

import megamek.client.ui.Messages;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitType;

/**
 * One result of the Requisition view, as its slim card shows it: the name, a line of details (tonnage, type, tech base,
 * year), the Battle Value and the Point Value. It keeps the unit's summary, which the view hands back to add the unit or
 * show its dossier.
 *
 * @param unit    the unit's summary in the unit cache
 * @param name    chassis and model
 * @param details tonnage · type · tech base · year
 * @param bv      the Battle Value as the unit file has it, for the default skills
 * @param pv      the Alpha Strike Point Value
 * @param tons    the weight in tons
 */
public record RequisitionRow(MekSummary unit, String name, String details, int bv, int pv, double tons) {

    /** The row of {@code unit}. */
    public static RequisitionRow of(MekSummary unit) {
        return new RequisitionRow(unit, unit.getName(), details(unit), unit.getBV(), unit.getPointValue(),
              unit.getTons());
    }

    /** "70 t · BattleMek · IS · 3025": the tonnage, type, tech base and year of introduction. */
    public static String details(MekSummary unit) {
        return Messages.getString("Lobby.requisition.details", tons(unit.getTons()), typeName(unit),
              Messages.getString(RequisitionCatalog.TechBase.of(unit.getTechBase()).messageKey()),
              Integer.toString(unit.getYear()));
    }

    /** The unit's type as players name it; a support vehicle says so. */
    public static String typeName(MekSummary unit) {
        if (unit.isSupport()) {
            return Messages.getString("Lobby.requisition.type.support_vehicle");
        }
        int code = UnitType.determineUnitTypeCode(unit.getUnitType());
        return (code < 0) ? String.valueOf(unit.getUnitType()) : UnitType.getTypeDisplayableName(code);
    }

    /** Tons without a fraction when whole, else to the kilogram's useful part: "70", "0.5", "1.25". */
    public static String tons(double tons) {
        if (tons == Math.rint(tons)) {
            return Long.toString(Math.round(tons));
        }
        String text = String.format(Locale.ROOT, "%.3f", tons);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }
}
