/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.Set;

/** Immutable authored appearance for one terrain owner. Asset data stays in the shared content catalog. */
public record HexAppearance(String variant, String asset, String material, Double strength) implements Serializable {
    public static final Set<String> OWNERS = Set.of("ground", "road", "building", "pavement", "fuelTank",
          "vegetation", "rough");

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
