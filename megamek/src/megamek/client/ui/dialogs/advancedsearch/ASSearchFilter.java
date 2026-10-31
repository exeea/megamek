/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedsearch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;
import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.ASUnitType;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.loaders.MekSummary;
import megamek.common.units.UnitRole;

/**
 * The Alpha Strike advanced search's criteria, free of any UI toolkit: {@link ASAdvancedSearchPanel} builds one from
 * its fields, and other views (such as the lobby's Requisition view) build their own. A null criterion is not in use;
 * a set in use that is empty lets no unit through, as a ticked "use" box with no buttons pressed does in the panel.
 * When any criterion is in use, units without Alpha Strike values never match.
 */
public final class ASSearchFilter {

    /** A whole-number span, both ends included. */
    public record IntRange(int from, int to) {
        /** The panel's reading of two fields: an empty "from" is -1, an empty "to" has no end. */
        public static IntRange of(@Nullable Integer from, @Nullable Integer to) {
            return new IntRange((from == null) ? -1 : from, (to == null) ? Integer.MAX_VALUE : to);
        }

        public boolean matches(int value) {
            return (value >= from) && (value <= to);
        }
    }

    /** A damage span; with either end missing it lets every value through, as the panel's empty combo boxes do. */
    public record DamageRange(@Nullable ASDamage from, @Nullable ASDamage to) {
        public boolean matches(ASDamage damage) {
            return (from == null) || (to == null)
                  || ((damage.asDoubleValue() >= from.asDoubleValue()) && (damage.asDoubleValue() <= to.asDoubleValue()));
        }
    }

    /** A movement criterion: the movement mode code ("" for ground, "j" for jump...) and its inches. */
    public record Movement(String mode, IntRange range) {
        public boolean matches(MekSummary unit) {
            Map<String, Integer> movement = unit.getMovement();
            return unit.hasMovementMode(mode) && range.matches(movement.get(mode));
        }
    }

    /** The movement mode codes the panel offers, in its order; "" is the unit's standard ground movement. */
    public static final List<String> MOVE_MODES = List.of("", "j", "qw", "qt", "t", "w", "h", "v", "n", "s", "f", "m",
          "g", "a", "p");

    /** The damage values the panel offers, in its order: 0, 0*, then 1 to 20. */
    public static final List<ASDamage> DAMAGE_VALUES = damageValues();

    private final @Nullable Set<ASUnitType> unitTypes;
    private final @Nullable Set<UnitRole> roles;
    private final @Nullable Set<Integer> sizes;
    private final @Nullable Set<Integer> tmms;
    private final @Nullable Set<Integer> ovs;
    private final @Nullable IntRange armor;
    private final @Nullable IntRange structure;
    private final @Nullable IntRange threshold;
    private final @Nullable IntRange pointValue;
    private final @Nullable DamageRange damageS;
    private final @Nullable DamageRange damageM;
    private final @Nullable DamageRange damageL;
    private final @Nullable DamageRange damageE;
    private final @Nullable Movement movement;
    private final List<BattleForceSUA> abilities;

    private ASSearchFilter(Builder builder) {
        unitTypes = copy(builder.unitTypes);
        roles = copy(builder.roles);
        sizes = copy(builder.sizes);
        tmms = copy(builder.tmms);
        ovs = copy(builder.ovs);
        armor = builder.armor;
        structure = builder.structure;
        threshold = builder.threshold;
        pointValue = builder.pointValue;
        damageS = builder.damageS;
        damageM = builder.damageM;
        damageL = builder.damageL;
        damageE = builder.damageE;
        movement = builder.movement;
        abilities = List.copyOf(builder.abilities);
    }

    /** A filter with no criterion in use, which every unit passes. */
    public static final ASSearchFilter NONE = new Builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** True when any criterion is in use, so that units might be filtered out. */
    public boolean isActive() {
        return (unitTypes != null) || (roles != null) || (sizes != null) || (tmms != null) || (ovs != null)
              || (armor != null) || (structure != null) || (threshold != null) || (pointValue != null)
              || (damageS != null) || (damageM != null) || (damageL != null) || (damageE != null)
              || (movement != null) || !abilities.isEmpty();
    }

    /** True when {@code unit} passes every criterion in use, or when none is in use. */
    public boolean matches(MekSummary unit) {
        if (!isActive()) {
            return true;
        }
        if (unit.getASUnitType() == ASUnitType.UNKNOWN) {
            return false;
        }
        if (((unitTypes != null) && !unitTypes.contains(unit.getASUnitType()))
              || ((roles != null) && !roles.contains(unit.getRole()))
              || ((sizes != null) && !sizes.contains(unit.getSize()))
              || ((tmms != null) && !(tmms.contains(unit.getTMM()) && unit.usesTMM()))
              || ((armor != null) && !armor.matches(unit.getFullArmor()))
              || ((structure != null) && !structure.matches(unit.getFullStructure()))
              || ((threshold != null) && !threshold.matches(unit.getThreshold()))) {
            return false;
        }
        ASDamageVector damage = unit.getStandardDamage();
        if (((damageS != null) && !damageS.matches(damage.S()))
              || ((damageM != null) && !damageM.matches(damage.M()))
              || ((damageL != null) && !damageL.matches(damage.L()))
              || ((damageE != null) && !damageE.matches(damage.E()))
              || ((ovs != null) && !(ovs.contains(unit.getOV()) && unit.usesOV()))
              || ((pointValue != null) && !pointValue.matches(unit.getPointValue()))
              || ((movement != null) && !movement.matches(unit))) {
            return false;
        }
        for (BattleForceSUA ability : abilities) {
            if (!hasAbility(unit, ability)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code unit} has {@code ability}, on the whole or, for units with arcs, in any arc. */
    public static boolean hasAbility(MekSummary unit, BattleForceSUA ability) {
        return unit.getSpecialAbilities().hasSUA(ability)
              || (unit.usesArcs() && (unit.getLeftArc().hasSUA(ability) || unit.getRightArc().hasSUA(ability)
              || unit.getFrontArc().hasSUA(ability) || unit.getRearArc().hasSUA(ability)));
    }

    private static <T> @Nullable Set<T> copy(@Nullable Set<T> set) {
        return (set == null) ? null : Set.copyOf(set);
    }

    private static List<ASDamage> damageValues() {
        List<ASDamage> values = new ArrayList<>();
        values.add(ASDamage.ZERO);
        values.add(ASDamage.MINIMAL_DAMAGE);
        for (int i = 1; i <= 20; i++) {
            values.add(new ASDamage(i, false));
        }
        return List.copyOf(values);
    }

    /** Collects the criteria; each setter takes null for "not in use". */
    public static final class Builder {
        private Set<ASUnitType> unitTypes;
        private Set<UnitRole> roles;
        private Set<Integer> sizes;
        private Set<Integer> tmms;
        private Set<Integer> ovs;
        private IntRange armor;
        private IntRange structure;
        private IntRange threshold;
        private IntRange pointValue;
        private DamageRange damageS;
        private DamageRange damageM;
        private DamageRange damageL;
        private DamageRange damageE;
        private Movement movement;
        private final Set<BattleForceSUA> abilities = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder unitTypes(@Nullable Set<ASUnitType> value) {
            unitTypes = value;
            return this;
        }

        public Builder roles(@Nullable Set<UnitRole> value) {
            roles = value;
            return this;
        }

        public Builder sizes(@Nullable Set<Integer> value) {
            sizes = value;
            return this;
        }

        public Builder tmms(@Nullable Set<Integer> value) {
            tmms = value;
            return this;
        }

        public Builder ovs(@Nullable Set<Integer> value) {
            ovs = value;
            return this;
        }

        public Builder armor(@Nullable IntRange value) {
            armor = value;
            return this;
        }

        public Builder structure(@Nullable IntRange value) {
            structure = value;
            return this;
        }

        public Builder threshold(@Nullable IntRange value) {
            threshold = value;
            return this;
        }

        public Builder pointValue(@Nullable IntRange value) {
            pointValue = value;
            return this;
        }

        public Builder damageS(@Nullable DamageRange value) {
            damageS = value;
            return this;
        }

        public Builder damageM(@Nullable DamageRange value) {
            damageM = value;
            return this;
        }

        public Builder damageL(@Nullable DamageRange value) {
            damageL = value;
            return this;
        }

        public Builder damageE(@Nullable DamageRange value) {
            damageE = value;
            return this;
        }

        public Builder movement(@Nullable Movement value) {
            movement = value;
            return this;
        }

        /** Adds an ability every matching unit must have; null is ignored. */
        public Builder ability(@Nullable BattleForceSUA value) {
            if (value != null) {
                abilities.add(value);
            }
            return this;
        }

        public ASSearchFilter build() {
            return new ASSearchFilter(this);
        }
    }
}
