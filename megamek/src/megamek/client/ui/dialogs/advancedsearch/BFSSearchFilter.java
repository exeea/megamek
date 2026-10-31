/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.dialogs.advancedsearch;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;

import jakarta.annotation.Nullable;
import megamek.common.battlefieldSupport.BFSArtilleryType;
import megamek.common.battlefieldSupport.BFSAssetType;
import megamek.common.battlefieldSupport.BFSDamage;
import megamek.common.battlefieldSupport.BFSRange;
import megamek.common.battlefieldSupport.BFSSpecial;
import megamek.common.battlefieldSupport.BFSSpecialType;
import megamek.common.loaders.MekSummary;
import megamek.common.units.EntityMovementMode;

/**
 * The Battlefield Support advanced search's criteria, free of any UI toolkit: {@link BFSAdvancedSearchPanel} builds one
 * from its fields, and other views (such as the lobby's Requisition view) build their own. A null criterion is not in
 * use. When any criterion is in use, only Battlefield Support Assets match.
 */
public final class BFSSearchFilter {

    /** The movement modes offered as filters, in display order. */
    public static final List<EntityMovementMode> MOVE_MODES = List.of(
          EntityMovementMode.TRACKED, EntityMovementMode.WHEELED, EntityMovementMode.HOVER,
          EntityMovementMode.VTOL, EntityMovementMode.WIGE, EntityMovementMode.INF_LEG,
          EntityMovementMode.INF_JUMP, EntityMovementMode.INF_MOTORIZED, EntityMovementMode.NONE);

    /**
     * The parameter-valued Specials whose numeric value can be filtered with an "at least" threshold. Artillery is
     * excluded because its parameter is a type token rather than a number (see the artillery type filter).
     */
    public static final List<BFSSpecialType> NUMERIC_SPECIALS = Arrays.stream(BFSSpecialType.values())
          .filter(BFSSpecialType::takesValue)
          .filter(type -> type != BFSSpecialType.ARTILLERY)
          .toList();

    /** The Specials without a value, which a filter can require. */
    public static final List<BFSSpecialType> PLAIN_SPECIALS = Arrays.stream(BFSSpecialType.values())
          .filter(type -> !type.takesValue())
          .toList();

    /** An asset's numbers a span can filter on. */
    public enum Stat {
        MP(MekSummary::getBfsMp),
        TMM(MekSummary::getBfsTmm),
        SKILL(MekSummary::getBfsSkill),
        DAMAGE(unit -> damage(unit, BFSDamage::total)),
        DAMAGE_PER_HIT(unit -> damage(unit, BFSDamage::perHit)),
        DAMAGE_HITS(unit -> damage(unit, BFSDamage::hits)),
        DESTROY_CHECK(MekSummary::getBfsDestroyCheck),
        THRESHOLD(MekSummary::getBfsThreshold),
        BSP(MekSummary::getBfsBsp),
        BV(MekSummary::getBV),
        LONG_RANGE(null);

        private final @Nullable ToIntFunction<MekSummary> value;

        Stat(@Nullable ToIntFunction<MekSummary> value) {
            this.value = value;
        }

        boolean matches(MekSummary unit, IntRange range) {
            if (value != null) {
                return range.matches(value.applyAsInt(unit));
            }
            BFSRange bfsRange = unit.getBfsRange();
            return (bfsRange != null) && !bfsRange.isKeyword() && range.matches(bfsRange.longRange());
        }

        private static int damage(MekSummary unit, ToIntFunction<BFSDamage> part) {
            BFSDamage damage = unit.getBfsDamage();
            return (damage == null) ? 0 : part.applyAsInt(damage);
        }
    }

    /** A whole-number span, both ends included. */
    public record IntRange(int from, int to) {
        /** The panel's reading of two fields: an empty end is open. */
        public static IntRange of(@Nullable Integer from, @Nullable Integer to) {
            return new IntRange((from == null) ? Integer.MIN_VALUE : from, (to == null) ? Integer.MAX_VALUE : to);
        }

        public boolean matches(int value) {
            return (value >= from) && (value <= to);
        }
    }

    private final @Nullable Set<BFSAssetType> assetTypes;
    private final @Nullable Set<EntityMovementMode> moveModes;
    private final @Nullable Set<BFSSpecialType> specials;
    private final Map<BFSSpecialType, Integer> numericSpecials;
    private final @Nullable Set<BFSArtilleryType> artilleryTypes;
    private final Map<Stat, IntRange> ranges;

    private BFSSearchFilter(Builder builder) {
        assetTypes = copy(builder.assetTypes);
        moveModes = copy(builder.moveModes);
        specials = copy(builder.specials);
        numericSpecials = Map.copyOf(builder.numericSpecials);
        artilleryTypes = copy(builder.artilleryTypes);
        ranges = Map.copyOf(builder.ranges);
    }

    /** A filter with no criterion in use, which every unit passes. */
    public static final BFSSearchFilter NONE = new Builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /** True when any criterion is in use, so that units might be filtered out. */
    public boolean isActive() {
        return (assetTypes != null) || (moveModes != null) || (specials != null) || !numericSpecials.isEmpty()
              || (artilleryTypes != null) || !ranges.isEmpty();
    }

    /**
     * @param unit the unit summary to test, which may be {@code null}
     *
     * @return true when no criterion is in use, or when {@code unit} is a Battlefield Support Asset that passes every
     *       criterion in use
     */
    public boolean matches(@Nullable MekSummary unit) {
        if (!isActive()) {
            return true;
        }
        if ((unit == null) || !unit.isBattlefieldSupportAsset()) {
            return false;
        }
        if (((assetTypes != null) && !assetTypes.contains(unit.getBfsAssetType()))
              || ((moveModes != null) && !moveModes.contains(unit.getBfsMovementMode()))
              || ((specials != null) && !new HashSet<>(unit.getBfsSpecials()).containsAll(specials))) {
            return false;
        }
        for (Map.Entry<BFSSpecialType, Integer> numeric : numericSpecials.entrySet()) {
            if (!hasAtLeast(unit.getBfsSpecialDetails(), numeric.getKey(), numeric.getValue())) {
                return false;
            }
        }
        if ((artilleryTypes != null) && !matchesArtilleryType(unit)) {
            return false;
        }
        for (Stat stat : Stat.values()) {
            IntRange range = ranges.get(stat);
            if ((range != null) && !stat.matches(unit, range)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasAtLeast(List<BFSSpecial> specials, BFSSpecialType type, int minimum) {
        return specials.stream()
              .filter(special -> special.knownType().orElse(null) == type)
              .anyMatch(special -> special.intValue().orElse(Integer.MIN_VALUE) >= minimum);
    }

    private boolean matchesArtilleryType(MekSummary unit) {
        if (!unit.getBfsSpecials().contains(BFSSpecialType.ARTILLERY)) {
            return false;
        }
        if (artilleryTypes.isEmpty()) {
            return true;
        }
        BFSArtilleryType type = artilleryTypeOf(unit);
        return (type != null) && artilleryTypes.contains(type);
    }

    private static @Nullable BFSArtilleryType artilleryTypeOf(MekSummary unit) {
        return unit.getBfsSpecialDetails().stream()
              .filter(special -> special.knownType().orElse(null) == BFSSpecialType.ARTILLERY)
              .map(BFSSpecial::value)
              .map(BFSArtilleryType::fromString)
              .filter(Objects::nonNull)
              .findFirst()
              .orElse(null);
    }

    private static <T> @Nullable Set<T> copy(@Nullable Set<T> set) {
        return (set == null) ? null : Set.copyOf(set);
    }

    /** Collects the criteria; each setter takes null for "not in use". */
    public static final class Builder {
        private Set<BFSAssetType> assetTypes;
        private Set<EntityMovementMode> moveModes;
        private Set<BFSSpecialType> specials;
        private final Map<BFSSpecialType, Integer> numericSpecials = new EnumMap<>(BFSSpecialType.class);
        private Set<BFSArtilleryType> artilleryTypes;
        private final Map<Stat, IntRange> ranges = new EnumMap<>(Stat.class);

        private Builder() {
        }

        public Builder assetTypes(@Nullable Set<BFSAssetType> value) {
            assetTypes = value;
            return this;
        }

        public Builder moveModes(@Nullable Set<EntityMovementMode> value) {
            moveModes = value;
            return this;
        }

        /** The value-less Specials a matching asset must all have. */
        public Builder specials(@Nullable Set<BFSSpecialType> value) {
            specials = value;
            return this;
        }

        /** Requires the numeric Special {@code type} at {@code minimum} or more; a null minimum stops requiring it. */
        public Builder numericSpecial(BFSSpecialType type, @Nullable Integer minimum) {
            if (minimum == null) {
                numericSpecials.remove(type);
            } else {
                numericSpecials.put(type, minimum);
            }
            return this;
        }

        /** Requires an Artillery Special of one of {@code value}'s types, of any type when it is empty. */
        public Builder artilleryTypes(@Nullable Set<BFSArtilleryType> value) {
            artilleryTypes = value;
            return this;
        }

        public Builder range(Stat stat, @Nullable IntRange value) {
            if (value == null) {
                ranges.remove(stat);
            } else {
                ranges.put(stat, value);
            }
            return this;
        }

        public BFSSearchFilter build() {
            return new BFSSearchFilter(this);
        }
    }
}
