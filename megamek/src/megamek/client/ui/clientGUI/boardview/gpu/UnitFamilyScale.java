/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import megamek.common.units.EntityWeightClass;

/** Visual fine-tuning on top of BoardGeometry; keys follow the existing asset families. */
enum UnitFamilyScale {
    MEK("All Meks", 1.0f, 1.0f),
    // Weight-class multipliers stack with MEK and the model's authored proportions.
    MEK_LIGHT("Light Meks", MEK, 1.0f, 1.0f),
    MEK_MEDIUM("Medium Meks", MEK, 1.0f, 1.0f),
    MEK_HEAVY("Heavy Meks", MEK, 1.0f, 1.0f),
    MEK_ASSAULT("Assault Meks", MEK, 1.0f, 1.0f),
    MEK_SUPER_HEAVY("Superheavy Meks", MEK, 1.0f, 1.0f),
    // Troops are authored at canonical size (a 1.8 m soldier, 2.7 m battle armor) and drawn larger to read on the
    // board, as tall as they stood before; their transports and spacing grow with them.
    INFANTRY("Infantry", 2.0f, 1.0f),
    BATTLE_ARMOR("Battle armor", 1.8f, 1.0f),
    VEHICLE("Vehicles", 1.0f, 1.0f),
    AIRCRAFT("Aircraft", 1.0f, 1.0f),
    NAVAL("Naval", 1.0f, 1.0f),
    // Same as infantry, authored at 6m but raised for visibility and separate them from BA
    PROTOMEK("ProtoMeks", 1.3f, 1.0f),
    STATIC("Static units", 1.0f, 1.0f),
    DEFAULT("Other models", 1.0f, 1.0f);

    final String label;
    final float defaultUnitScale;
    float UNIT_SCALE;
    float HEIGHT_SCALE;
    private final UnitFamilyScale parent;

    UnitFamilyScale(String label, float unitScale, float heightScale) {
        this(label, null, unitScale, heightScale);
    }

    UnitFamilyScale(String label, UnitFamilyScale parent, float unitScale, float heightScale) {
        this.label = label;
        defaultUnitScale = unitScale;
        this.parent = parent;
        UNIT_SCALE = unitScale;
        HEIGHT_SCALE = heightScale;
    }

    float unitScale() {
        return UNIT_SCALE * (parent == null ? 1 : parent.UNIT_SCALE);
    }

    float heightScale() {
        return HEIGHT_SCALE * (parent == null ? 1 : parent.HEIGHT_SCALE);
    }

    /** Resolve per placement: legacy meshes can be shared by Meks of different weight classes. */
    UnitFamilyScale forUnit(BoardScene.Unit unit) {
        if (this != MEK || unit.model() == null || unit.model().state() == null) {
            return this;
        }
        var anatomy = unit.model().state().structure().anatomy();
        if (anatomy == null) {
            return this;
        }
        return switch (anatomy.weightClass()) {
            case EntityWeightClass.WEIGHT_ULTRA_LIGHT, EntityWeightClass.WEIGHT_LIGHT -> MEK_LIGHT;
            case EntityWeightClass.WEIGHT_MEDIUM -> MEK_MEDIUM;
            case EntityWeightClass.WEIGHT_HEAVY -> MEK_HEAVY;
            case EntityWeightClass.WEIGHT_ASSAULT -> MEK_ASSAULT;
            case EntityWeightClass.WEIGHT_SUPER_HEAVY -> MEK_SUPER_HEAVY;
            default -> this;
        };
    }

    static UnitFamilyScale forFamily(String family) {
        return switch (family) {
            case "mek", "mek-biped", "mek-tripod", "mek-quad" -> MEK;
            case "infantry" -> INFANTRY;
            case "battle-armor" -> BATTLE_ARMOR;
            case "vehicle" -> VEHICLE;
            case "aircraft" -> AIRCRAFT;
            case "naval" -> NAVAL;
            case "proto" -> PROTOMEK;
            case "static" -> STATIC;
            default -> DEFAULT;
        };
    }
}
