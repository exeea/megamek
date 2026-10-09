/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;

/** Immutable authored appearance for one terrain owner. Asset data stays in the shared content catalog. */
public record HexAppearance(String variant, String asset, String material, Double strength) implements Serializable {
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

    /** Whether a hex's appearance turns its bridge's piers ({@link #PILLARS}) on. */
    public static boolean pillars(Map<String, HexAppearance> appearance) { return PILLARS.equals(appearance.get("bridge")); }

    /**
     * The hex's stored bridge type: TRUE for built ({@link #BUILT_BRIDGE} or {@link #PILLARS}), FALSE for
     * {@link #NATURAL_BRIDGE}, null when none is stored (the 3D board's legacy decode decides).
     */
    public static Boolean bridgeBuilt(Map<String, HexAppearance> appearance) {
        HexAppearance type = appearance.get("bridge");
        return PILLARS.equals(type) || BUILT_BRIDGE.equals(type) ? Boolean.TRUE
              : NATURAL_BRIDGE.equals(type) ? Boolean.FALSE : null;
    }

    public HexAppearance {
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
              || material != null && !owner.equals("road") || strength != null && !owner.equals("ground")) {
            throw new IllegalArgumentException("Appearance fields do not belong to " + owner);
        }
    }
}
