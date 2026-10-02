/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import java.util.regex.Pattern;

import megamek.common.enums.TechBase;
import megamek.common.equipment.AmmoType;
import megamek.common.equipment.Engine;
import megamek.common.equipment.EquipmentType;
import megamek.common.equipment.MiscType;
import megamek.common.equipment.Mounted;
import megamek.common.units.Entity;
import megamek.common.units.Mek;
import megamek.common.units.ProtoMek;

/**
 * The names a record sheet's critical hit table gives a unit's critical slots, as MegaMekLab's record sheets print them
 * ({@code PrintMek.formatCritName}) and MekBay shows them: "Roll Again" for an empty slot, the engine by its type
 * ("XL Fusion Engine"), arm and leg actuators with the word "Actuator" ("Upper Arm Actuator", but "Shoulder" and
 * "Hip"), the gyro and cockpit without the word "Standard" ("Gyro", "Small Cockpit"), equipment by its short name with
 * its rear or turret mark, and ammunition as "Ammo (LRM 20)". It is MegaMek's one source of these names; the GPU unit
 * record's critical table uses it.
 *
 * <p>Two deliberate differences from MegaMekLab's printout: an ammunition slot's name leaves out the shots that the
 * printout appends ("Ammo (LRM 20) 6"), which a caller shows from the bin itself; and "(Clan)" is removed with the
 * space before it, where MegaMekLab turns any parenthesis of an ammunition name into a dot and leaves a trailing
 * space.</p>
 */
public final class RecordSheetSlotNames {
    /** The name of an empty critical slot. */
    public static final String EMPTY = "Roll Again";

    private static final String STANDARD = "Standard ";
    private static final Pattern CLAN_TAG = Pattern.compile("\\s*(?:\\[Clan]|\\(Clan\\))");
    private static final Pattern AMMO_WORD = Pattern.compile("\\s*Ammo$");

    private RecordSheetSlotNames() {
    }

    /**
     * @return the record sheet name of one critical slot of the unit, {@link #EMPTY} when the slot is empty
     */
    public static String slotName(Entity entity, int location, int slot) {
        CriticalSlot critical = entity.getCritical(location, slot);
        if (critical == null) {
            return EMPTY;
        } else if (critical.getType() == CriticalSlot.TYPE_SYSTEM) {
            return systemName(entity, critical);
        }
        return equipmentName(entity, critical);
    }

    /** Meks and ProtoMeks are the units whose critical slots hold systems; a vehicle's may hold an armored engine. */
    private static String systemName(Entity entity, CriticalSlot critical) {
        int index = critical.getIndex();
        if (entity instanceof ProtoMek) {
            return ProtoMek.SYSTEM_NAMES[index];
        }
        if (!(entity instanceof Mek mek)) {
            return Mek.systemNames[index];
        }
        if (index == Mek.SYSTEM_ENGINE) {
            return engineName(mek);
        }
        String name = mek.getSystemName(index);
        if (((index >= Mek.ACTUATOR_UPPER_ARM) && (index <= Mek.ACTUATOR_HAND))
              || ((index >= Mek.ACTUATOR_UPPER_LEG) && (index <= Mek.ACTUATOR_FOOT))) {
            name += " Actuator";
        } else if (index == Mek.SYSTEM_COCKPIT) {
            name = cockpitName(mek, critical, name);
        }
        return name.replace(STANDARD, "");
    }

    /** A cockpit slot names the robotic control system, or on a multi-crew cockpit its seat's role. */
    private static String cockpitName(Mek mek, CriticalSlot critical, String name) {
        for (Mounted<?> mounted : mek.getMisc()) {
            if (mounted.getType().hasFlag(MiscType.F_SRCS)) {
                return mounted.getType().getShortName();
            }
        }
        if (mek.getCockpitType() == Mek.COCKPIT_COMMAND_CONSOLE) {
            return (mek.getCrewForCockpitSlot(Mek.LOC_HEAD, critical) == 0)
                  ? Mek.getCockpitDisplayString(Mek.COCKPIT_STANDARD) : name;
        } else if ((mek.getCockpitType() == Mek.COCKPIT_DUAL) || (mek.getCockpitType() == Mek.COCKPIT_QUADVEE)) {
            return mek.getCrew().getCrewType().getRoleName(mek.getCrewForCockpitSlot(Mek.LOC_HEAD, critical));
        }
        return name;
    }

    private static String engineName(Mek mek) {
        Engine engine = mek.getEngine();
        if (engine == null) {
            return mek.getSystemName(Mek.SYSTEM_ENGINE);
        }
        String type = switch (engine.getEngineType()) {
            case Engine.COMBUSTION_ENGINE -> "I.C.E. ";
            case Engine.NORMAL_ENGINE -> "Fusion ";
            case Engine.XL_ENGINE -> "XL Fusion ";
            case Engine.LIGHT_ENGINE -> "Light Fusion ";
            case Engine.XXL_ENGINE -> "XXL Fusion ";
            case Engine.COMPACT_ENGINE -> "Compact Fusion ";
            case Engine.FUEL_CELL -> "Fuel Cell ";
            case Engine.FISSION -> "Fission ";
            default -> "";
        };
        return (mek.isPrimitive() ? "Primitive " : "") + type + "Engine";
    }

    private static String equipmentName(Entity entity, CriticalSlot critical) {
        Mounted<?> mounted = critical.getMount();
        EquipmentType type = mounted.getType();
        Mounted<?> second = critical.getMount2();
        StringBuilder name = new StringBuilder(type.getShortName());
        if (entity.isMixedTech()) {
            if (entity.isClan() && (type.getTechBase() == TechBase.IS) && (name.indexOf("[IS]") < 0)) {
                name.append(" [IS]");
            } else if (!entity.isClan() && type.isClan() && (name.indexOf("[Clan]") < 0)) {
                name.append(" [Clan]");
            }
        }
        if ((type instanceof MiscType) && (type.hasFlag(MiscType.F_TSM) || type.hasFlag(MiscType.F_INDUSTRIAL_TSM))) {
            name.setLength(0);
            name.append(type.getName());
        }
        if (mounted.isRearMounted()) {
            name.append(" (R)");
        } else if (mounted.isMekTurretMounted()) {
            name.append(" (T)");
        } else if ((type instanceof AmmoType ammo) && (ammo.getAmmoType() != AmmoType.AmmoTypeEnum.COOLANT_POD)) {
            String ammoName = CLAN_TAG.matcher(ammo.getShortName()).replaceAll("");
            return "Ammo (" + AMMO_WORD.matcher(ammoName).replaceAll("").strip() + ")";
        } else if ((second != null) && (type instanceof MiscType) && type.hasFlag(MiscType.F_HEAT_SINK)
              && type.equals(second.getType())) {
            name.insert(0, "2 ").append("s");
        } else if ((second != null) && (type instanceof MiscType) && type.hasFlag(MiscType.F_COMPACT_HEAT_SINK)
              && (second.getType() instanceof MiscType) && second.getType().hasFlag(MiscType.F_COMPACT_HEAT_SINK)) {
            int sinks = 2 + (type.hasFlag(MiscType.F_DOUBLE_HEAT_SINK) ? 1 : 0)
                  + (second.getType().hasFlag(MiscType.F_DOUBLE_HEAT_SINK) ? 1 : 0);
            name.replace(0, 1, Integer.toString(sinks));
        }
        String result = entity.isMixedTech() ? name.toString()
              : CLAN_TAG.matcher(name).replaceFirst("").strip();
        if ((type instanceof MiscType) && (type.hasFlag(MiscType.F_VEHICLE_MINE_DISPENSER)
              || type.hasFlag(MiscType.F_SENSOR_DISPENSER))) {
            result += " (" + mounted.getBaseShotsLeft() + ")";
        }
        return result;
    }
}
