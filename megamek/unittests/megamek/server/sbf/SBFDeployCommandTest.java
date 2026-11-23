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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFFormationTurn;
import megamek.common.strategicBattleSystems.SBFGame;
import org.junit.jupiter.api.Test;

class SBFDeployCommandTest {

    @Test
    void playerUpdatePacketsCanReplaceTheSetupZoneAndTeam() {
        SBFGame game = new SBFGame();
        game.addPlayer(1, new Player(1, "Owner"));
        Player updated = new Player(1, "Owner");
        updated.setStartingPos(Board.START_W);
        updated.setTeam(2);
        game.setPlayer(1, updated);
        assertEquals(Board.START_W, game.getPlayer(1).getStartingPos());
        assertEquals(2, game.getTeamForPlayer(updated).getId());
        game.removePlayer(1);
        assertTrue(game.getTeams().isEmpty());
    }

    @Test
    void placesOnlyTheCurrentOwnersDueFormationInItsZoneOnce() {
        SBFGame game = new SBFGame();
        Player player = new Player(0, "North");
        player.setStartWidth(1);
        player.setTeam(1);
        game.addPlayer(0, player);
        Player partner = new Player(1, "Partner");
        partner.setTeam(1);
        game.addPlayer(1, partner);
        assertTrue(SBFDeployCommand.chooseSideEdge(game, 0, "north"));
        assertFalse(SBFDeployCommand.chooseSideEdge(game, 0, "south"));
        assertEquals(Board.START_N, partner.getStartingPos());
        game.setBoard(0, Board.createEmptyBoard(6, 6));
        game.setPhase(GamePhase.DEPLOYMENT);
        game.setCurrentRound(2);
        game.setTurns(List.of(new SBFFormationTurn(0)));
        game.changeToNextTurn();
        SBFFormation formation = new SBFFormation();
        formation.setId(23);
        formation.setOwnerId(0);
        game.addUnit(formation);
        BoardLocation north = BoardLocation.of(new Coords(2, 0), 0);
        assertFalse(SBFDeployCommand.place(game, 0, formation, north));
        formation.setDeployRound(2);
        assertFalse(SBFDeployCommand.place(game, 1, formation, north));
        assertFalse(SBFDeployCommand.place(game, 0, formation, BoardLocation.of(new Coords(2, 5), 0)));
        assertTrue(SBFDeployCommand.place(game, 0, formation, north));
        assertEquals(north, formation.getPosition());
        assertFalse(SBFDeployCommand.place(game, 0, formation, north));
    }
}
