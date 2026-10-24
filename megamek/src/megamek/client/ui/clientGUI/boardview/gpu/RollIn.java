/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import megamek.common.board.Coords;

/**
 * Shapes an attack run's flight like an aircraft rolling in on a ground target: it cruises at its altitude, noses down
 * a little before its first shot starts, stays low through the firing pass over its targets, and pulls out after the
 * last one. How low follows the altitude its attacks cost it under the rules: a strike ends one level lower and a dive
 * bomb two, and the run stays there, so it ends at the altitude the game shows. A strafing run costs nothing, so it dips
 * {@link #STRAFE_DIP} level and climbs back. Air-to-air shots do not dip.
 *
 * <p>Only the run's own copy of the path changes; the game's altitude does not.</p>
 */
final class RollIn {
    /** Levels a strafing run dips for its roll-in. A strafe costs no altitude, so it climbs back after. */
    static final float STRAFE_DIP = 1;
    /** Hexes before its first shot starts over which the aircraft noses into its dive. */
    static final int ROLL_IN_HEXES = 2;
    /** Hexes after its last target over which the aircraft pulls out to its altitude after the attack. */
    static final int PULL_OUT_HEXES = 2;

    private RollIn() {
    }

    /**
     * @param flight the run's held move, at the altitude the aircraft flew it
     * @param shots  the run's shots
     * @param drop   the levels the aircraft's attacks cost it (a strike 1, a dive bomb 2)
     *
     * @return the flight with its heights shaped, or the flight itself when it has nothing to shape
     */
    static BoardScene.Movement shape(BoardScene.Movement flight, List<BoardScene.Combat> shots, float drop) {
        List<BoardScene.Waypoint> path = flight.path();
        boolean groundAttack = false;
        int firstShot = Integer.MAX_VALUE;
        int lastTarget = -1;
        for (BoardScene.Combat shot : shots) {
            groundAttack |= (shot.target() == null) || !shot.target().airborne();
            Coords firing = shot.result().attacker().coords();
            int fired = indexOf(path, firing);
            int started = indexOf(path, AttackRunPlayer.leadHex(path, firing));
            if ((fired >= 0) && (started >= 0)) {
                firstShot = Math.min(firstShot, started);
                lastTarget = Math.max(lastTarget, hexesOn(path, fired, 1));
            }
        }
        float depth = Math.max(drop, groundAttack ? STRAFE_DIP : 0);
        if ((depth <= 0) || (path.size() < 2)) {
            return flight;
        }
        if (firstShot == Integer.MAX_VALUE) {
            // No shot on the path: descend to the altitude after the attacks over the whole flight.
            firstShot = path.size() - 1;
            lastTarget = path.size() - 1;
            depth = drop;
        }
        int rollStart = hexesOn(path, firstShot, -ROLL_IN_HEXES);
        int pullEnd = hexesOn(path, lastTarget, PULL_OUT_HEXES);
        List<BoardScene.Waypoint> shaped = new ArrayList<>();
        float offset = 0;
        for (int index = 0; index < path.size(); index++) {
            BoardScene.Waypoint point = path.get(index);
            // A turn in place repeats a hex; it keeps the height the hex was reached at.
            boolean repeated = (index > 0) && point.coords().equals(path.get(index - 1).coords());
            if (!repeated) {
                offset = offset(index, rollStart, firstShot, lastTarget, pullEnd, depth, drop);
            }
            shaped.add(new BoardScene.Waypoint(point.coords(), point.elevation() - offset, point.facing(),
                  point.proneCause(), point.aeroState(), point.footprint(), point.form(), point.fallSide(),
                  point.hullDown()));
        }
        return new BoardScene.Movement(flight.entityId(), flight.boardId(), shaped, flight.type(), flight.jumpMP(),
              flight.movementMP(), flight.unit(), flight.gravity());
    }

    /** The levels below its flight altitude the aircraft is at this path point. */
    private static float offset(int index, int rollStart, int firstShot, int lastTarget, int pullEnd, float depth,
          float drop) {
        if (index < firstShot) {
            return depth * ease(index, rollStart, firstShot);
        }
        if (index <= lastTarget) {
            return depth;
        }
        return depth + (drop - depth) * ease(index, lastTarget, pullEnd);
    }

    /** Smoothstep from 0 at {@code from} to 1 at {@code to}; 1 past it, and 1 when the two coincide. */
    private static float ease(int index, int from, int to) {
        if (to <= from) {
            return (index >= to) ? 1 : 0;
        }
        float fraction = Math.clamp((index - from) / (float) (to - from), 0, 1);
        return fraction * fraction * (3 - 2 * fraction);
    }

    /** @return the first index of the hex on the path, or -1 */
    private static int indexOf(List<BoardScene.Waypoint> path, Coords hex) {
        for (int index = 0; index < path.size(); index++) {
            if (path.get(index).coords().equals(hex)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * @return the index {@code hexes} distinct hexes after (or, when negative, before) {@code from} along the path,
     *       stopping at its ends
     */
    private static int hexesOn(List<BoardScene.Waypoint> path, int from, int hexes) {
        int step = (hexes < 0) ? -1 : 1;
        int index = from;
        int moved = 0;
        while ((moved < Math.abs(hexes)) && (index + step >= 0) && (index + step < path.size())) {
            index += step;
            if (!path.get(index).coords().equals(path.get(index - step).coords())) {
                moved++;
            }
        }
        return index;
    }
}
