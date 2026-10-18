/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import megamek.common.Hex;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;

/**
 * The one span unit of a bridge's stored type ({@link HexAppearance#bridgeBuilt}): the bridge hexes joined to each other
 * by their BRIDGE exits ({@link #joined(Hex, Hex, int)}). Its legacy decode types it as a whole, and the 3D editor's
 * type, Pillars and deck-height edits apply to it. The 3D board draws a deck joint where the exits are mutual
 * ({@link #joined(int, double, int, double, int)}); a one-sided exit ({@link #oneSided}), a map-making error that the
 * legacy conversion repairs ({@code BoardSceneryLayouts.convert}), joins the span without a joint.
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

    /**
     * Whether the bridge hexes {@code hex} and {@code next} (null or bridgeless: no) are one span across
     * {@code direction}: their decks join, or a one-sided exit joins them ({@link #oneSided}).
     */
    public static boolean joined(Hex hex, Hex next, int direction) {
        return bridges(hex, next) && (joined(hex.getTerrain(Terrains.BRIDGE).getExits(), deck(hex),
              next.getTerrain(Terrains.BRIDGE).getExits(), deck(next), direction) || oneSided(hex, next, direction));
    }

    /**
     * Whether exactly one of the bridge hexes {@code hex} and {@code next} has a bridge exit toward the other across
     * {@code direction}, and their decks are at the same level: a one-sided exit, which joins them into one span. Decks at
     * different levels are not joined: the exit may end the deck on purpose, so nothing is guessed.
     */
    public static boolean oneSided(Hex hex, Hex next, int direction) {
        return bridges(hex, next) && deck(hex) == deck(next)
              && ((hex.getTerrain(Terrains.BRIDGE).getExits() & (1 << direction)) != 0)
              != ((next.getTerrain(Terrains.BRIDGE).getExits() & (1 << ((direction + 3) % 6))) != 0);
    }

    private static boolean bridges(Hex hex, Hex next) {
        return hex != null && next != null && hex.containsTerrain(Terrains.BRIDGE) && next.containsTerrain(Terrains.BRIDGE);
    }

    /**
     * The one writer of a hex's implicit bridge ({@link HexAppearance#IMPLICIT_BRIDGE}), which its deck ways
     * ({@link Terrains#BRIDGE_ROAD}, {@link Terrains#BRIDGE_RAIL}) make for themselves where no bridge carries them:
     * <ul>
     * <li>deck ways and no bridge: an implicit bridge {@code elevation} above the hex, its weight and CF from
     * {@code defaults} ({@code "bridge:1;bridge_cf:40"}), its exits specified as the union of its ways' sides;</li>
     * <li>an implicit bridge: its exits become the union of its ways' sides, and it goes with its last way, as does its
     * stored type;</li>
     * <li>a drawn bridge (any other type, or none stored): nothing.</li>
     * </ul>
     * The caller puts the hex back on its board, which re-derives the neighbours' road links, and keeps decks out of
     * building and fuel-tank hexes.
     *
     * @throws IllegalArgumentException when a new bridge is due and {@code defaults} lacks the weight or the CF
     */
    public static void carry(Hex hex, int elevation, String defaults) {
        Terrain road = hex.getTerrain(Terrains.BRIDGE_ROAD), rail = hex.getTerrain(Terrains.BRIDGE_RAIL);
        int sides = (road == null ? 0 : road.getExits()) | (rail == null ? 0 : rail.getExits());
        boolean ways = road != null || rail != null;
        if (!hex.hasBridge()) {
            if (!ways) { return; }
            Terrain weight = null, cf = null;
            for (String token : defaults.split(";")) {
                Terrain terrain = new Terrain(token);
                if (terrain.getType() == Terrains.BRIDGE) { weight = terrain; }
                if (terrain.getType() == Terrains.BRIDGE_CF) { cf = terrain; }
            }
            if (weight == null || cf == null) {
                throw new IllegalArgumentException("An implicit bridge needs a weight and a CF: " + defaults);
            }
            hex.addTerrain(new Terrain(Terrains.BRIDGE, weight.getLevel(), true, sides));
            hex.addTerrain(cf);
            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, elevation));
            Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
            appearance.put("bridge", HexAppearance.IMPLICIT_BRIDGE);
            hex.setAppearance(appearance);
        } else if (HexAppearance.implicit(hex.getAppearance())) {
            if (ways) {
                hex.addTerrain(new Terrain(Terrains.BRIDGE, hex.terrainLevel(Terrains.BRIDGE), true, sides));
            } else {
                hex.removeBridge();
                Map<String, HexAppearance> appearance = new HashMap<>(hex.getAppearance());
                appearance.remove("bridge");
                hex.setAppearance(appearance);
            }
        }
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

    /**
     * The span's truss design ({@link HexAppearance#truss}), the one rule of the 3D board and the editor, independent of
     * the hex it starts from: null when no hex of {@code span} stores a truss, else the colours of the first hex (by
     * column, then row) that stores any, {@link BoardDecoration.Colours#NONE} when none does.
     */
    public static BoardDecoration.Colours truss(Function<Coords, Map<String, HexAppearance>> appearance,
          Collection<Coords> span) {
        if (span.stream().noneMatch(at -> HexAppearance.truss(appearance.apply(at)))) { return null; }
        return span.stream().sorted(Comparator.comparingInt(Coords::getX).thenComparingInt(Coords::getY))
              .map(at -> appearance.apply(at).get("bridge")).filter(type -> type != null && !type.colours().slots().isEmpty())
              .map(HexAppearance::colours).findFirst().orElse(BoardDecoration.Colours.NONE);
    }

    /**
     * The legacy decode of whether {@code span} (see {@link #of}) is built, the one rule of the legacy conversion, of the
     * 3D editor's typing of untyped bridges and of their render: a type stored on any of its hexes (built wins); else
     * built when one of its hexes has a road attached (ROAD on it at any level, or a deck end on a road hex), a rail or
     * maglev (its art, FLUFF 10 or 9, its terrain or its route marker) on it or at a deck end, pillar art
     * ({@code pillarArt}), PAVEMENT terrain (snow on it too) on it or at a deck end, or a BUILDING on it or beside it;
     * otherwise natural. {@code hexes} is null off the board.
     */
    public static boolean decode(Function<Coords, Hex> hexes, Predicate<Coords> pillarArt, List<Coords> span) {
        Boolean stored = type(hexes, span);
        if (stored != null) { return stored; }
        var members = new HashSet<>(span);
        for (Coords at : span) {
            Hex hex = hexes.apply(at);
            if (pillarArt.test(at) || artificial(hex) || hex.containsTerrain(Terrains.ROAD)) { return true; }
            int exits = hex.getTerrain(Terrains.BRIDGE).getExits();
            for (int d = 0; d < 6; d++) {
                Coords next = at.translated(d);
                Hex beside = hexes.apply(next);
                if (beside == null || members.contains(next)) { continue; }
                if (beside.containsTerrain(Terrains.BUILDING)) { return true; }
                // A deck end: an exit onto ground, not onto another deck.
                if ((exits & (1 << d)) != 0 && !beside.containsTerrain(Terrains.BRIDGE)
                      && (artificial(beside) || beside.containsTerrain(Terrains.ROAD))) { return true; }
            }
        }
        return false;
    }

    /**
     * Pavement (under snow too), a building, or a rail or maglev: its art (FLUFF 10 or 9), its terrain or its route
     * marker, which a {@code .board2} holds instead of the art. What a built bridge leads to.
     */
    private static boolean artificial(Hex hex) {
        int fluff = hex.containsTerrain(Terrains.FLUFF) ? hex.terrainLevel(Terrains.FLUFF) : -1;
        return hex.containsAnyTerrainOf(Terrains.PAVEMENT, Terrains.BUILDING, Terrains.RAIL, Terrains.BRIDGE_RAIL)
              || fluff == 9 || fluff == 10
              || hex.getDecorations().stream().anyMatch(RailRoute::isRoute);
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
