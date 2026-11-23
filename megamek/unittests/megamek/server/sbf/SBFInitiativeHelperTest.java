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
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
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

    @Test
    void undetectedMoversDoNotInflateTheRatioOrConsumeAnOrdinaryMovementTurn() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        game.setPhase(GamePhase.MOVEMENT);
        Player first = addPlayer(game, 1, 1, 0);
        Player second = addPlayer(game, 2, 2, 0);
        game.getTeamForPlayer(first).getInitiative().addRoll(-20, "");
        game.getTeamForPlayer(second).getInitiative().addRoll(20, "");
        for (int id = 10; id <= 14; id++) {
            SBFFormation formation = new SBFFormation();
            formation.setId(id);
            formation.setOwnerId(id == 14 ? 2 : 1);
            formation.setDeployed(true);
            formation.setPosition(BoardLocation.of(new Coords(id, 0), 0));
            game.addUnit(formation);
        }
        game.visibilityHelper().setVisibility(2, 10, SBFVisibilityStatus.BLIP);
        game.visibilityHelper().setVisibility(1, 14, SBFVisibilityStatus.BLIP);
        game.visibilityHelper().setVisibility(2, 11, SBFVisibilityStatus.SENSOR_PING);
        var turns = manager.initiativeHelper.formationTurns(GamePhase.MOVEMENT);
        assertEquals(List.of(1, 2, 1, 2), turns.stream().map(SBFTurn::playerId).toList());
        game.setTurns(turns);
        game.changeToNextTurn();
        SBFFormation hidden = game.getFormation(11).orElseThrow();
        game.visibilityHelper().setVisible(2, hidden.getId());
        hidden.setDone(true);
        manager.endCurrentTurn(hidden);
        assertEquals(0, game.getTurnIndex());
        SBFFormation located = game.getFormation(10).orElseThrow();
        located.setDone(true);
        manager.endCurrentTurn(located);
        assertEquals(2, game.getTurn().playerId());
    }

    private static Player addPlayer(SBFGame game, int id, int team, int bonus) {
        Player player = new Player(id, "Player " + id);
        player.setTeam(team);
        player.getInitiative().addRoll(bonus, "");
        game.addPlayer(id, player);
        return player;
    }
}
