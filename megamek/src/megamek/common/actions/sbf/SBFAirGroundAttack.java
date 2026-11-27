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

package megamek.common.actions.sbf;

import java.io.Serializable;
import java.util.List;

import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.server.sbf.SBFActionHandler;
import megamek.server.sbf.SBFAirGroundRun;
import megamek.server.sbf.SBFGameManager;

/** One Squadron's air-to-ground declaration, resolved alongside standard Combat Phase attacks (IO:BF p. 178). */
public class SBFAirGroundAttack extends AbstractSBFAttackAction {
    public enum Type { STRIKE, STRAFE, ALTITUDE, DIVE }

    public record Release(BoardLocation hex, int highExplosive, int cluster) implements Serializable { }

    private final Type type;
    private final List<Integer> targets;
    private final List<Integer> flights;
    private final List<Release> releases;

    public SBFAirGroundAttack(int squadron, Type type, List<Integer> targets, List<Integer> flights, List<Release> releases) {
        super(squadron, targets.isEmpty() ? Player.PLAYER_NONE : targets.getFirst());
        this.type = type;
        this.targets = List.copyOf(targets);
        this.flights = List.copyOf(flights);
        this.releases = List.copyOf(releases);
    }

    public Type type() {
        return type;
    }

    public List<Integer> targets() {
        return targets;
    }

    @Override
    public List<Integer> referencedIds() {
        return targets;
    }

    public List<Integer> flights() {
        return flights;
    }

    public List<Release> releases() {
        return releases;
    }

    @Override
    public boolean isDataValid(SBFGame game) {
        return game.getFormation(getEntityId()).isPresent() && (type != null);
    }

    @Override
    public SBFActionHandler getHandler(SBFGameManager manager) {
        return new SBFAirGroundRun(this, manager);
    }
}
