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

import java.util.List;

import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.server.sbf.SBFActionHandler;
import megamek.server.sbf.SBFGameManager;
import megamek.server.sbf.SBFTerrainAttackHandler;

/** A Formation devotes its standard weapons to razing an exposed terrain feature, IO:BF p. 221. */
public final class SBFTerrainAttack extends AbstractSBFAttackAction {
    private final BoardLocation target;
    private final Integer clearingPassage;

    public SBFTerrainAttack(int formation, BoardLocation target) {
        this(formation, target, null);
    }

    /** A zero mask clears the full hex. Two hexside bits designate an engineering passage. */
    public SBFTerrainAttack(int formation, BoardLocation target, Integer clearingPassage) {
        super(formation, Player.PLAYER_NONE);
        this.clearingPassage = clearingPassage;
        this.target = target;
    }

    public Integer clearingPassage() {
        return clearingPassage;
    }

    public BoardLocation terrain() {
        return target;
    }

    @Override
    public List<Integer> referencedIds() {
        return List.of();
    }

    @Override
    public boolean isDataValid(SBFGame game) {
        return game.hasBoardLocation(target) && game.getFormation(getEntityId()).isPresent()
              && (clearingPassage == null || clearingPassage == 0 || (clearingPassage > 0 && clearingPassage < 64
              && Integer.bitCount(clearingPassage) == 2));
    }

    @Override
    public SBFActionHandler getHandler(SBFGameManager manager) {
        return clearingPassage == null ? new SBFTerrainAttackHandler(this, manager) : null;
    }
}
