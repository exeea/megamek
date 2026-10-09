/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.boardeditor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.board.Board;
import megamek.common.board.Coords;

/** Ground sculpting rules. Computes target levels only; the editor session applies them as ordinary hex edits. */
public final class LevelSculpt {
    public enum Mode { RAISE, LOWER, LEVEL }

    private LevelSculpt() { }

    /**
     * Moves every source hex by {@code delta}. With slope, neighbours are then stepped outward toward the sources
     * until no edge differs by more than one level. An edge that is already a cliff on {@code board} before this step
     * stays a cliff, so raising a bank never fills the valley below; no neighbour moves away from the sources. Slope
     * moves any neighbouring hex's level, so a water surface, bridge or building next to the footprint can move too.
     * Returns the new level of every hex that changes; {@code board} itself is not modified.
     */
    public static Map<Coords, Integer> raise(Board board, Collection<Coords> cells, int delta, boolean slope) {
        Map<Coords, Integer> levels = new LinkedHashMap<>();
        if (delta == 0) { return levels; }
        Set<Coords> sources = new LinkedHashSet<>(cells);
        sources.forEach(at -> levels.put(at, board.getHex(at).getLevel() + delta));
        if (!slope) { return levels; }
        int sign = Integer.signum(delta);
        List<Coords> frontier = new ArrayList<>(sources);
        while (!frontier.isEmpty()) {
            List<Coords> next = new ArrayList<>();
            for (Coords at : frontier) {
                int need = level(board, levels, at) - sign;
                for (Coords neighbour : at.allAdjacent()) {
                    if (!board.contains(neighbour) || sources.contains(neighbour)
                          || Math.abs(board.getHex(neighbour).getLevel() - board.getHex(at).getLevel()) > 1) { continue; }
                    int current = level(board, levels, neighbour);
                    if (sign > 0 ? current >= need : current <= need) { continue; }
                    levels.put(neighbour, need);
                    next.add(neighbour);
                }
            }
            frontier = next;
        }
        return levels;
    }

    /** Sets every cell to {@code level}; returns the cells that change. */
    public static Map<Coords, Integer> level(Board board, Collection<Coords> cells, int level) {
        Map<Coords, Integer> levels = new LinkedHashMap<>();
        cells.stream().filter(at -> board.getHex(at).getLevel() != level).forEach(at -> levels.put(at, level));
        return levels;
    }

    private static int level(Board board, Map<Coords, Integer> levels, Coords at) {
        Integer moved = levels.get(at);
        return moved == null ? board.getHex(at).getLevel() : moved;
    }
}
