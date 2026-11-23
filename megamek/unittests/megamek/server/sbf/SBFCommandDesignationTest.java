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

package megamek.server.sbf;

import static megamek.common.alphaStrike.BattleForceSUA.COM;
import static megamek.common.alphaStrike.BattleForceSUA.LEAD;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.Player;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFUnit;
import org.junit.jupiter.api.Test;

class SBFCommandDesignationTest {
    @Test
    void commandIsUniqueAcrossOwnedFormationsAndReassignmentPreservesTheLeader() {
        SBFGame game = new SBFGame();
        Player owner = new Player(1, "Owner");
        owner.setTeam(1);
        game.addPlayer(1, owner);
        game.addPlayer(2, new Player(2, "Enemy"));
        Player ally = new Player(3, "Ally");
        ally.setTeam(1);
        game.addPlayer(3, ally);
        SBFFormation first = formation(game, 1, 10);
        SBFFormation second = formation(game, 3, 11);
        SBFFormation enemy = formation(game, 2, 12);
        assertTrue(SBFCommandDesignation.designate(game, 1, first, 0, COM));
        assertTrue(SBFCommandDesignation.designate(game, 1, first, 1, LEAD));
        assertFalse(SBFCommandDesignation.designate(game, 1, second, 1, COM));
        assertTrue(SBFCommandDesignation.designate(game, 3, second, 1, COM));
        assertFalse(first.hasSUA(COM));
        assertFalse(first.getUnits().getFirst().hasSUA(COM));
        assertTrue(first.getUnits().get(1).hasSUA(LEAD));
        assertTrue(second.hasSUA(COM));
        assertTrue(second.getUnits().get(1).hasSUA(COM));
        assertFalse(SBFCommandDesignation.designate(game, 1, enemy, 1, COM));
    }

    private static SBFFormation formation(SBFGame game, int owner, int id) {
        SBFFormation formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(owner);
        formation.addUnit(new SBFUnit());
        formation.addUnit(new SBFUnit());
        game.addUnit(formation);
        return formation;
    }
}
