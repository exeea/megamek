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

import megamek.common.Player;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.server.scriptedEvents.GameEndTriggeredEvent;
import megamek.server.scriptedEvents.VictoryTriggeredEvent;
import megamek.server.trigger.SpecificRoundEndTrigger;
import org.junit.jupiter.api.Test;

class SBFVictoryTest {
    private final SBFGame game = new SBFGame();

    @Test
    void reservesKeepTheirSideInPlayAndWithdrawalCountsForElimination() {
        SBFFormation ally = add(1, 2);
        SBFFormation reserve = add(2, 3);
        reserve.setDeployRound(3);
        assertFalse(SBFVictory.atRoundEnd(game).isVictory());
        reserve.markWithdrawn();
        game.removeFormationFromPlay(reserve);
        assertEquals(2, SBFVictory.atRoundEnd(game).getWinningTeam());
        game.removeFormationFromPlay(ally);
        assertTrue(SBFVictory.atRoundEnd(game).isVictory());
        assertTrue(SBFVictory.atRoundEnd(game).isDraw());
    }

    @Test
    void aSingleSideDoesNotWinDuringSetup() {
        add(1, 1);
        add(2, 1);
        assertFalse(SBFVictory.atRoundEnd(game).isVictory());
    }

    @Test
    void scriptedEndingCanAwardVictoryToTheEliminatedPlayer() {
        add(1, Player.TEAM_NONE);
        game.removeFormationFromPlay(add(2, Player.TEAM_NONE));
        game.addScriptedEvent(new GameEndTriggeredEvent(new SpecificRoundEndTrigger(3)));
        game.addScriptedEvent(new VictoryTriggeredEvent(new SpecificRoundEndTrigger(3), false, "Side 2"));
        game.setCurrentRound(2);
        assertFalse(SBFVictory.atRoundEnd(game).isVictory());
        game.setCurrentRound(3);
        assertEquals(2, SBFVictory.atRoundEnd(game).getWinningPlayer());
    }

    private SBFFormation add(int id, int team) {
        Player player = new Player(id, "Side " + id);
        player.setTeam(team);
        game.addPlayer(id, player);
        SBFFormation formation = new SBFFormation();
        formation.setOwnerId(id);
        formation.setId(id + 10);
        game.addUnit(formation);
        return formation;
    }
}
