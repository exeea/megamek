/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/**
 * Immutable authored appearance for one terrain owner. Asset data stays in the shared content catalog. {@code colours}
 * replace a truss bridge's colour slot (its Body, as a placed object's colours do); only a truss stores them.
 */
public record HexAppearance(String variant, String asset, String material, Double strength,
      BoardDecoration.Colours colours) implements Serializable {
    public static final Set<String> OWNERS = Set.of("ground", "road", "building", "pavement", "fuelTank",
          "vegetation", "rough", "bridge");
    /**
     * A built bridge with concrete piers under the joints between its hexes, never at a hex centre (owner
     * {@code "bridge"}). Cosmetic, like the other bridge types: the 3D views draw them; rules and the 2D views ignore it.
     */
    public static final HexAppearance PILLARS = new HexAppearance("bridge/pillars", null, null, null);
    /** A built (artificial) bridge without piers: the bridge deck models. Every hex of a {@link BridgeSpan} has one type. */
    public static final HexAppearance BUILT_BRIDGE = new HexAppearance("bridge/built", null, null, null);
    /** A natural bridge: a rock arch. */
    public static final HexAppearance NATURAL_BRIDGE = new HexAppearance("bridge/natural", null, null, null);
    /**
     * A built bridge in the riveted steel truss design, without piers; {@link #TRUSS_PILLARS} with them. The design and
     * its colours are one choice per span ({@link BridgeSpan#truss}), cosmetic like the type.
     */
    public static final HexAppearance TRUSS = new HexAppearance("bridge/truss", null, null, null);
    public static final HexAppearance TRUSS_PILLARS = new HexAppearance("bridge/truss-pillars", null, null, null);

    /** An appearance without colours. */
    public HexAppearance(String variant, String asset, String material, Double strength) {
        this(variant, asset, material, strength, BoardDecoration.Colours.NONE);
    }

    /** Whether a hex's appearance turns its bridge's piers ({@link #PILLARS}, {@link #TRUSS_PILLARS}) on. */
    public static boolean pillars(Map<String, HexAppearance> appearance) {
        return is(appearance, PILLARS) || is(appearance, TRUSS_PILLARS);
    }

    /** Whether a hex's appearance draws its built bridge as a truss ({@link #TRUSS}, {@link #TRUSS_PILLARS}). */
    public static boolean truss(Map<String, HexAppearance> appearance) {
        return is(appearance, TRUSS) || is(appearance, TRUSS_PILLARS);
    }

    /** A built bridge hex's appearance: its design, its piers, and a truss's colours (ignored for the standard design). */
    public static HexAppearance built(boolean truss, boolean pillars, BoardDecoration.Colours colours) {
        if (!truss) { return pillars ? PILLARS : BUILT_BRIDGE; }
        return new HexAppearance((pillars ? TRUSS_PILLARS : TRUSS).variant(), null, null, null, colours);
    }

    /** Variants compare by id: a truss's colours do not change its type. */
    private static boolean is(Map<String, HexAppearance> appearance, HexAppearance type) {
        HexAppearance stored = appearance.get("bridge");
        return stored != null && type.variant().equals(stored.variant());
    }

    /**
     * The hex's stored bridge type: TRUE for built ({@link #BUILT_BRIDGE}, {@link #PILLARS}, {@link #TRUSS} or
     * {@link #TRUSS_PILLARS}), FALSE for {@link #NATURAL_BRIDGE}, null when none is stored (the 3D board's legacy decode
     * decides).
     */
    public static Boolean bridgeBuilt(Map<String, HexAppearance> appearance) {
        return pillars(appearance) || truss(appearance) || is(appearance, BUILT_BRIDGE) ? Boolean.TRUE
              : is(appearance, NATURAL_BRIDGE) ? Boolean.FALSE : null;
    }

    public HexAppearance {
        colours = colours == null ? BoardDecoration.Colours.NONE : colours;
        if (!colours.slots().isEmpty() && !"bridge/truss".equals(variant) && !"bridge/truss-pillars".equals(variant)) {
            throw new IllegalArgumentException("Only a truss bridge stores colours");
        }
        if (variant == null && asset == null && material == null) {
            throw new IllegalArgumentException("An appearance needs a variant, asset or material");
        }
        for (String key : new String[] { variant, asset, material }) {
            if (key != null && (key.isBlank() || key.contains("..") || key.startsWith("/") || key.contains("\\") || key.contains(":"))) {
                throw new IllegalArgumentException("Invalid appearance key: " + key);
            }
        }
        if (asset != null && variant != null) {
            throw new IllegalArgumentException("A design cannot contain both an asset and a variant");
        }
        if (strength != null && (!Double.isFinite(strength) || variant == null
              || strength <= 0 || strength >= 1 || Math.abs(strength * 6 - Math.rint(strength * 6)) > 0.000001)) {
            throw new IllegalArgumentException("Blend strength must be one to five sixths");
        }
    }

    public void validateOwner(String owner) {
        if (!OWNERS.contains(owner) || asset != null && !owner.equals("building")
              || material != null && !owner.equals("road") || strength != null && !owner.equals("ground")
              || !colours.slots().isEmpty() && !owner.equals("bridge")) {
            throw new IllegalArgumentException("Appearance fields do not belong to " + owner);
        }
    }
}
