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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFTurn;
import org.junit.jupiter.api.Test;

class SBFInitiativeHelperTest {

    @Test
    void teamRollOrdersBothPhasesAndAlliesShareTheirMovementRatio() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        Player firstAlly = addPlayer(game, 1, 1, 20);
        addPlayer(game, 2, 1, 40);
        Player enemy = addPlayer(game, 3, 2, -20);
        game.getTeamForPlayer(firstAlly).getInitiative().addRoll(-20, "");
        game.getTeamForPlayer(enemy).getInitiative().addRoll(20, "");
        for (int owner : List.of(1, 2, 2, 2, 3, 3)) {
            SBFFormation formation = new SBFFormation();
            formation.setId(game.getActiveFormations().size() + 10);
            formation.setOwnerId(owner);
            formation.setDeployed(true);
            game.addUnit(formation);
        }

        assertEquals(List.of(1, 2, 3, 2, 2, 3), manager.initiativeHelper.formationTurns(GamePhase.MOVEMENT)
              .stream().map(SBFTurn::playerId).toList());
        assertEquals(List.of(1, 2, 2, 2, 3, 3), manager.initiativeHelper.formationTurns(GamePhase.FIRING)
              .stream().map(SBFTurn::playerId).toList());
    }

    private static Player addPlayer(SBFGame game, int id, int team, int bonus) {
        Player player = new Player(id, "Player " + id);
        player.setTeam(team);
        player.getInitiative().addRoll(bonus, "");
        game.addPlayer(id, player);
        return player;
    }
}
