/*
 * Copyright (C) 2021-2025 The MegaMek Team. All Rights Reserved.
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
import java.util.HashMap;
import java.util.Map;

import megamek.common.board.BoardLocation;

/** Private scenario and in-play minefields, IO:BF pp. 206, 214–215. */
public final class SBFMinefields implements Serializable {
    public enum Type { CONVENTIONAL, ACTIVE, EMP, INFERNO }

    public record Field(int owner, Type type, int size, int density, boolean command) implements Serializable {
        public Field {
            if (type == null || threshold(size) == 0 || density < 1 || density > 5) {
                throw new IllegalArgumentException("Invalid SBF minefield");
            }
        }
    }

    public record Construction(int owner, BoardLocation location, Field field, int completion) implements Serializable { }
    private final Map<BoardLocation, Field> fields = new HashMap<>();
    private final Map<Integer, Construction> construction = new HashMap<>();
    private final Map<Integer, Integer> lastDetonation = new HashMap<>();
    private final Map<BoardLocation, Integer> infernoOwners = new HashMap<>();
    private final Map<BoardLocation, Integer> empRounds = new HashMap<>();

    public static int threshold(int size) {
        return switch (size) {
            case 1 -> 11;
            case 2 -> 10;
            case 4 -> 9;
            case 6 -> 8;
            case 8, 12 -> 7;
            case 10 -> 6;
            default -> 0;
        };
    }

    public Map<BoardLocation, Field> fields() {
        return fields;
    }

    public Map<Integer, Construction> construction() {
        return construction;
    }

    public Map<BoardLocation, Integer> infernoOwners() {
        return infernoOwners;
    }

    public boolean inEmp(BoardLocation position, int round) {
        return Integer.valueOf(round).equals(empRounds.get(position));
    }

    public void igniteEmp(BoardLocation hex, int round) {
        empRounds.put(hex, round);
    }

    public boolean canDetonate(int observer, int round) {
        return !Integer.valueOf(round).equals(lastDetonation.get(observer));
    }

    public void detonated(int observer, int round) {
        lastDetonation.put(observer, round);
    }
}
