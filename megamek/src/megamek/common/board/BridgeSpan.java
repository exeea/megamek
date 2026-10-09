/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import megamek.common.Hex;
import megamek.common.units.Terrains;

/**
 * The one span unit of a bridge's stored type ({@link HexAppearance#bridgeBuilt}): the bridge hexes joined to each other
 * by mutual BRIDGE exits with decks within one level. The 3D board draws a span as one deck, its legacy decode types
 * it as a whole, and the 3D editor's type, Pillars and deck-height edits apply to it.
 */
public final class BridgeSpan {
    private BridgeSpan() { }

    /** Whether two decks join across {@code direction}: each has a bridge exit toward the other and they are within a level. */
    public static boolean joined(int exits, double deck, int otherExits, double otherDeck, int direction) {
        return (exits & (1 << direction)) != 0 && (otherExits & (1 << ((direction + 3) % 6))) != 0
              && Math.abs(deck - otherDeck) <= 1;
    }

    /** The absolute level of the hex's bridge deck. */
    public static int deck(Hex hex) { return hex.getLevel() + hex.terrainLevel(Terrains.BRIDGE_ELEV); }

    /** Whether the bridge hexes {@code hex} and {@code next} (null or bridgeless: no) join across {@code direction}. */
    public static boolean joined(Hex hex, Hex next, int direction) {
        return hex != null && next != null && hex.containsTerrain(Terrains.BRIDGE) && next.containsTerrain(Terrains.BRIDGE)
              && joined(hex.getTerrain(Terrains.BRIDGE).getExits(), deck(hex), next.getTerrain(Terrains.BRIDGE).getExits(),
              deck(next), direction);
    }

    /** The type the hexes of {@code span} store ({@link HexAppearance#bridgeBuilt}): built wins, null when none has one. */
    public static Boolean type(Function<Coords, Hex> hexes, List<Coords> span) {
        Boolean result = null;
        for (Coords at : span) {
            Boolean type = HexAppearance.bridgeBuilt(hexes.apply(at).getAppearance());
            if (type != null && (result == null || type)) { result = type; }
        }
        return result;
    }

    /** The span of the bridge hex at {@code start}, in breadth-first order; {@code hexes} gives null off the board. */
    public static List<Coords> of(Function<Coords, Hex> hexes, Coords start) {
        Set<Coords> found = new LinkedHashSet<>(List.of(start));
        ArrayDeque<Coords> open = new ArrayDeque<>(found);
        while (!open.isEmpty()) {
            Coords at = open.pop();
            Hex hex = hexes.apply(at);
            for (int direction = 0; direction < 6; direction++) {
                Coords next = at.translated(direction);
                if (!found.contains(next) && joined(hex, hexes.apply(next), direction)) {
                    found.add(next);
                    open.add(next);
                }
            }
        }
        return List.copyOf(found);
    }
}
