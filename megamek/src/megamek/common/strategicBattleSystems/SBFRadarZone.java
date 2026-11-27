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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import megamek.common.board.Board;

/** Atmospheric Radar Map zones (IO:BF pp. 175–176). North is twelve o'clock. */
public enum SBFRadarZone {
    CENTRAL, INNER, A, B, C, D, E, F, OUTER_1, OUTER_2, OUTER_3, OUTER_4, OUTER_5, OUTER_6,
    OUTER_7, OUTER_8, OUTER_9, OUTER_10, OUTER_11, OUTER_12;

    public boolean isOuter() {
        return ordinal() >= OUTER_1.ordinal();
    }

    private boolean isMiddle() {
        return (ordinal() >= A.ordinal()) && !isOuter();
    }

    public String label() {
        return isOuter() ? Integer.toString(ordinal() - OUTER_1.ordinal() + 1) : name();
    }

    public static SBFRadarZone parse(String name) {
        try {
            int number = Integer.parseInt(name);
            if ((number >= 1) && (number <= 12)) {
                return values()[OUTER_1.ordinal() + number - 1];
            }
        } catch (NumberFormatException ignored) {
            // Central, Inner and the middle rings are named.
        }
        return valueOf(name.toUpperCase(Locale.ROOT));
    }

    public static SBFRadarZone home(int edge) {
        return switch (edge) {
            case Board.START_N -> OUTER_12;
            case Board.START_NE -> OUTER_2;
            case Board.START_E -> OUTER_3;
            case Board.START_SE -> OUTER_5;
            case Board.START_S -> OUTER_6;
            case Board.START_SW -> OUTER_8;
            case Board.START_W -> OUTER_9;
            case Board.START_NW -> OUTER_11;
            default -> null;
        };
    }

    public SBFRadarZone oppositeOuter() {
        return values()[OUTER_1.ordinal() + (ordinal() - OUTER_1.ordinal() + 6) % 12];
    }

    public boolean adjacent(SBFRadarZone other) {
        if ((this == other) || (other == null)) {
            return false;
        }
        if ((this == CENTRAL) || (other == CENTRAL)) {
            return (this == INNER) || (other == INNER);
        }
        if ((this == INNER) || (other == INNER)) {
            return isMiddle() || other.isMiddle();
        }
        if (isOuter() == other.isOuter()) {
            int separation = Math.abs(ordinal() - other.ordinal());
            return (separation == 1) || (separation == (isOuter() ? 11 : 5));
        }
        SBFRadarZone outer = isOuter() ? this : other;
        SBFRadarZone middle = isOuter() ? other : this;
        int clock = outer.ordinal() - OUTER_1.ordinal() + 1;
        return middle.ordinal() - A.ordinal() == (clock % 12) / 2;
    }

    public List<SBFRadarZone> neighbors() {
        return Arrays.stream(values()).filter(this::adjacent).toList();
    }
}
