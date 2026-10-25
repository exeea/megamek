/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.server.totalWarfare;

import java.util.List;

import megamek.common.ResolvedAttack;
import megamek.common.annotations.Nullable;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.logging.MMLogger;

/**
 * Where the rules place an aircraft for an attack over a ground map, for the attack event the 3D board animates. An
 * aircraft moves before it fires, so its end-of-move hex is not where an attack happens:
 * <ul>
 *     <li>An air-to-ground attack on a unit or hex the aircraft flew over is made as it crosses into that hex from the
 *     hex before it on its flight path (the approach hex, which also sets the strafing direction, TW p.243).</li>
 *     <li>An air-to-ground attack on a unit it did not fly over (point defense firing back, for one) is made from the
 *     hex of its flight path closest to that unit.</li>
 *     <li>A ground unit shooting at an aircraft fires at the hex of its flight path closest to the shooter, or the one
 *     the player picked ({@link Compute#getClosestFlightPath(int, Coords, Entity)}).</li>
 * </ul>
 * Everything else keeps its end-of-move location: air-to-air, space and atmospheric maps, cross-board attacks,
 * aircraft without a flight path this turn, and any event that is not a shot. Only the hex and facing change; the
 * aircraft's elevation, and so its drawn altitude, stays its own.
 */
final class AirAttackPoints {
    private static final MMLogger LOGGER = MMLogger.create(AirAttackPoints.class);

    private AirAttackPoints() {
    }

    /**
     * @param attacker  the attacking unit
     * @param target    the target
     * @param kind      the kind of attack event
     * @param endOfMove the attacker's end-of-move location
     *
     * @return where the attacker is drawn firing from
     */
    static UnitLocation firingLocation(Entity attacker, Targetable target, ResolvedAttack.Kind kind,
          UnitLocation endOfMove) {
        if (!attacker.isAero() || !attacker.isAirborne()) {
            return endOfMove;
        }
        String unchanged = unchangedReason(attacker, kind);
        if ((unchanged == null) && !Compute.isAirToGround(attacker, target)) {
            unchanged = "not air-to-ground";
        }
        if (unchanged != null) {
            logUnchanged("A2G", attacker, attacker.getShortName(), target.getDisplayName(), unchanged);
            return endOfMove;
        }

        List<Coords> path = attacker.getPassedThrough();
        int targetIndex = path.indexOf(target.getPosition());
        Coords firingHex;
        int facing;
        String how;
        if (targetIndex >= 0) {
            firingHex = path.get(Math.max(0, targetIndex - 1));
            facing = facingAt(attacker, targetIndex);
            how = "approach hex";
        } else {
            firingHex = Compute.getClosestFlightPath(Entity.NONE, target.getPosition(), attacker);
            facing = Compute.getClosestFlightPathFacing(Entity.NONE, target.getPosition(), attacker);
            how = "closest flight path hex";
        }
        if (firingHex == null) {
            logUnchanged("A2G", attacker, attacker.getShortName(), target.getDisplayName(), "no flight path hex");
            return endOfMove;
        }
        LOGGER.info("[AirAttack] A2G {} -> {}: end {} firing {} ({}) facing {} altitude {}", attacker.getShortName(),
              target.getDisplayName(), endOfMove.coords().getBoardNum(), firingHex.getBoardNum(), how, facing,
              attacker.getAltitude());
        return relocated(endOfMove, firingHex, facing);
    }

    /**
     * @param attacker  the attacking unit
     * @param target    the target
     * @param kind      the kind of attack event
     * @param endOfMove the target's end-of-move location
     *
     * @return where the target is drawn when it is hit or missed
     */
    static UnitLocation targetLocation(Entity attacker, Targetable target, ResolvedAttack.Kind kind,
          UnitLocation endOfMove) {
        if (!(target instanceof Entity aircraft) || !aircraft.isAero() || !aircraft.isAirborne()) {
            return endOfMove;
        }
        String unchanged = unchangedReason(aircraft, kind);
        if ((unchanged == null) && !Compute.isGroundToAir(attacker, aircraft)) {
            unchanged = "not ground-to-air";
        }
        Coords flightPathHex = (unchanged == null)
              ? Compute.getClosestFlightPath(attacker.getId(), attacker.getPosition(), aircraft) : null;
        if ((unchanged == null) && (flightPathHex == null)) {
            unchanged = "no flight path hex";
        }
        if (unchanged != null) {
            logUnchanged("G2A", aircraft, attacker.getShortName(), aircraft.getShortName(), unchanged);
            return endOfMove;
        }
        int facing = Compute.getClosestFlightPathFacing(attacker.getId(), attacker.getPosition(), aircraft);
        Coords playerPick = aircraft.getPlayerPickedPassThrough(attacker.getId());
        LOGGER.info("[AirAttack] G2A {} -> {}: end {} flight path hex {} facing {} player pick {}",
              attacker.getShortName(), aircraft.getShortName(), endOfMove.coords().getBoardNum(),
              flightPathHex.getBoardNum(), facing, (playerPick == null) ? "none" : playerPick.getBoardNum());
        return relocated(endOfMove, flightPathHex, facing);
    }

    /**
     * Logs an aircraft attack that keeps its end-of-move location. Over a ground map this is INFO, so a playtest shows
     * every aircraft attack whether or not it moved; on space and atmospheric maps, where nothing ever moves, it is
     * DEBUG so space battles do not flood the log.
     */
    private static void logUnchanged(String direction, Entity aircraft, String attackerName, String targetName,
          String reason) {
        if (aircraft.isAirborneAeroOnGroundMap()) {
            LOGGER.info("[AirAttack] {} unchanged {} -> {}: {}", direction, attackerName, targetName, reason);
        } else {
            LOGGER.debug("[AirAttack] {} unchanged {} -> {}: {}", direction, attackerName, targetName, reason);
        }
    }

    /**
     * @return why this aircraft's location stays at its end of move, or {@code null} if it may be moved onto its
     *       flight path
     */
    private static @Nullable String unchangedReason(Entity aircraft, ResolvedAttack.Kind kind) {
        if (kind != ResolvedAttack.Kind.SHOT) {
            return "not a shot (" + kind + ")";
        }
        if (!aircraft.isAirborneAeroOnGroundMap()) {
            return "not over a ground map";
        }
        if (aircraft.getPassedThrough().isEmpty()) {
            return "no flight path this turn";
        }
        if (aircraft.getPassedThroughBoardId() != aircraft.getBoardId()) {
            return "flight path on another board";
        }
        return null;
    }

    private static int facingAt(Entity aircraft, int pathIndex) {
        List<Integer> facings = aircraft.getPassedThroughFacing();
        return (pathIndex < facings.size()) ? facings.get(pathIndex) : aircraft.getFacing();
    }

    private static UnitLocation relocated(UnitLocation location, Coords coords, int facing) {
        return new UnitLocation(location.entityId(), coords, facing, location.elevation(), location.boardId(),
              location.proneCause(), location.form(), location.fallSide(), location.hullDown());
    }
}
