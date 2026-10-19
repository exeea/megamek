/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common;

import java.io.Serializable;
import java.util.UUID;

import megamek.common.board.Coords;
import megamek.common.weapons.handlers.NukeStats;

/** A server-confirmed scheduled nuclear strike, before its terrain and casualty updates. No shooter is invented. */
public record NuclearDetonation(UUID id, int boardId, Coords coords, int elevation, int baseDamage,
      int degradation, int secondaryRadius, int craterDepth) implements Serializable {
    /** The resolved rules values, including custom strikes, as a fresh local effects snapshot. */
    public NukeStats stats() {
        var stats = new NukeStats();
        stats.baseDamage = baseDamage;
        stats.degradation = degradation;
        stats.secondaryRadius = secondaryRadius;
        stats.craterDepth = craterDepth;
        return stats;
    }
}
