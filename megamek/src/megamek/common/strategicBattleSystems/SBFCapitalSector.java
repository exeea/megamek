/*
 * Copyright (C) 2024-2025 The MegaMek Team. All Rights Reserved.
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

package megamek.common.strategicBattleSystems;

import java.io.Serializable;
import java.util.Locale;

/** One sector of the 49-sector Capital Radar Map, IO:BF pp. 180–181. Sector numbers increase clockwise. */
public record SBFCapitalSector(Ring ring, int number) implements Serializable {
    public enum Ring {
        CENTRAL(1, 1, 0), INNER(6, 1, 0), MIDDLE(12, 2, 1), OUTER(24, 4, 3), PERIPHERAL(6, 10, 5);

        public final int sectors;
        final int thrustDivisor;
        public final int fuelCost;

        Ring(int sectors, int thrustDivisor, int fuelCost) {
            this.sectors = sectors;
            this.thrustDivisor = thrustDivisor;
            this.fuelCost = fuelCost;
        }
    }

    public SBFCapitalSector {
        if (ring == null || number < 1 || number > ring.sectors) {
            throw new IllegalArgumentException("Invalid capital radar sector.");
        }
    }

    public static SBFCapitalSector parse(String label) {
        String code = label.toUpperCase(Locale.ROOT);
        if (code.equals("CENTRAL")) {
            return new SBFCapitalSector(Ring.CENTRAL, 1);
        }
        if (code.length() < 2) {
            throw new IllegalArgumentException("Missing capital sector number.");
        }
        Ring ring = switch (code.charAt(0)) {
            case 'I' -> Ring.INNER;
            case 'M' -> Ring.MIDDLE;
            case 'O' -> Ring.OUTER;
            case 'P' -> Ring.PERIPHERAL;
            default -> throw new IllegalArgumentException("Unknown capital zone.");
        };
        return new SBFCapitalSector(ring, Integer.parseInt(code.substring(1)));
    }

    @Override
    public String toString() {
        return ring == Ring.CENTRAL ? "CENTRAL" : ring.name().substring(0, 1) + number;
    }

    /** Center angles in eighths of a 60-degree Inner sector. O1 and O2 flank due north. */
    private int bearing() {
        return Math.floorMod((number - 1) * (48 / ring.sectors) - (ring == Ring.OUTER ? 1 : 0), 48);
    }

    public boolean adjacent(SBFCapitalSector other) {
        if (ring == other.ring) {
            int difference = Math.abs(number - other.number);
            return difference != 0 && Math.min(difference, ring.sectors - difference) == 1;
        }
        if (Math.abs(ring.ordinal() - other.ring.ordinal()) != 1) {
            return false;
        }
        if (ring == Ring.CENTRAL || other.ring == Ring.CENTRAL) {
            return true;
        }
        int separation = Math.abs(bearing() - other.bearing());
        return Math.min(separation, 48 - separation) < 24 / ring.sectors + 24 / other.ring.sectors;
    }

    /** Clock bearing from the center of this sector toward an adjacent sector on its local Engagement Map. */
    public SBFRadarZone directionTo(SBFCapitalSector other) {
        if (!adjacent(other)) {
            throw new IllegalArgumentException("Sectors must share an edge.");
        }
        double angle = bearing() * Math.PI / 24;
        double nextAngle = other.bearing() * Math.PI / 24;
        double east = other.ring.ordinal() * Math.sin(nextAngle) - ring.ordinal() * Math.sin(angle);
        double north = other.ring.ordinal() * Math.cos(nextAngle) - ring.ordinal() * Math.cos(angle);
        int clock = Math.floorMod((int) Math.round(Math.atan2(east, north) * 6 / Math.PI), 12);
        return SBFRadarZone.parse(Integer.toString(clock == 0 ? 12 : clock));
    }

    public boolean oppositeBearing(SBFCapitalSector other) {
        int difference = Math.abs(Math.floorMod(bearing() + 24, 48) - other.bearing());
        return ring != Ring.CENTRAL && other.ring != Ring.CENTRAL
              && Math.min(difference, 48 - difference) < Math.max(24 / ring.sectors, 24 / other.ring.sectors);

    }

    public int adjustedThrust(int actualThrust) {
        return actualThrust <= 0 ? 0 : Math.max(1, actualThrust / ring.thrustDivisor);
    }
}
