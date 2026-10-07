/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.io.Serializable;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A visual-only instance owned by one Hex. Heights are board levels; XY uses hex width/height units. */
public record BoardDecoration(String id, String kind, String asset, String name, double x, double y,
      double rotation, boolean mirror, double scale, Placement placement, int drawOrder, boolean clipToHex) implements Serializable {
    /** Historical instances clipped paint to their owner. New editor brushes explicitly allow overflow. */
    public BoardDecoration(String id, String kind, String asset, String name, double x, double y,
          double rotation, boolean mirror, double scale, Placement placement, int drawOrder) {
        this(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, "decal".equals(kind));
    }
    public record Receiver(String terrain, String surface) implements Serializable {
        public Receiver {
            if (!Set.of("ground/top", "bridge/deck", "building/roof", "industrial/top")
                  .contains(terrain + "/" + surface)) {
                throw new IllegalArgumentException("Unsupported receiving surface: " + terrain + "/" + surface);
            }
        }
    }

    public record Placement(String mode, Double level, Receiver receiver, Double offset) implements Serializable {
        public Placement {
            if ("absolute".equals(mode)) {
                if (level == null || !Double.isFinite(level) || receiver != null || offset != null) {
                    throw new IllegalArgumentException("Absolute placement requires only a finite level");
                }
            } else if ("surface".equals(mode)) {
                if (level != null || receiver == null || offset == null || !Double.isFinite(offset)) {
                    throw new IllegalArgumentException("Surface placement requires a receiver and finite offset");
                }
            } else {
                throw new IllegalArgumentException("Unknown placement mode: " + mode);
            }
        }

        public static Placement ground() { return surface("ground", "top", 0); }
        public static Placement absolute(double level) { return new Placement("absolute", level, null, null); }
        public static Placement surface(String terrain, String surface, double offset) {
            return new Placement("surface", null, new Receiver(terrain, surface), offset);
        }
    }

    public BoardDecoration {
        if (id == null || id.isBlank() || !Set.of("prop", "decal").contains(kind)) {
            throw new IllegalArgumentException("An object requires an ID and supported kind");
        }
        if (asset == null || asset.isBlank() || asset.contains("..") || asset.startsWith("/") || asset.contains("\\") || asset.contains(":")) {
            throw new IllegalArgumentException("An object requires a logical asset key");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(rotation)
              || !Double.isFinite(scale) || scale <= 0) {
            throw new IllegalArgumentException("Object transforms must be finite; scale must be positive");
        }
        Objects.requireNonNull(placement, "placement");
        if (kind.equals("decal") && (!placement.mode().equals("surface") || placement.offset() != 0)) {
            throw new IllegalArgumentException("A decal requires a receiver and zero offset");
        }
        if (kind.equals("prop") && drawOrder != 0) {
            throw new IllegalArgumentException("Only decals have paint order");
        }
    }

    public BoardDecoration duplicate() {
        return new BoardDecoration(UUID.randomUUID().toString(), kind, asset, name, x, y, rotation, mirror,
              scale, placement, drawOrder, clipToHex);
    }

    public BoardDecoration transform(double x, double y, double rotation, boolean mirror, double scale,
          Placement placement) {
        return new BoardDecoration(id, kind, asset, name, x, y, rotation, mirror, scale, placement, drawOrder, clipToHex);
    }

    /** Reflection in board space, not in anisotropic normalized XY. */
    public BoardDecoration flip(boolean horizontal, boolean vertical) {
        double angle = rotation;
        boolean reflected = mirror;
        if (horizontal) { angle = -angle; reflected = !reflected; }
        if (vertical) { angle = 180 - angle; reflected = !reflected; }
        return transform(horizontal ? -x : x, vertical ? -y : y, angle, reflected, scale, placement);
    }
}
