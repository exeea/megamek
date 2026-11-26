/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.common.Player;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import org.junit.jupiter.api.Test;

class SBFCommandAbilitiesTest {

    @Test
    void sharpShootersTravelWithTheFormationAndOlderDataHasNone() throws Exception {
        var mapper = new ObjectMapper();
        String authored = """
              {"type":"SBFFormation","generalname":"Snipers","units":[],"sharpshooters":true}""";
        SBFFormation snipers = mapper.readValue(authored, SBFFormation.class);
        assertTrue(snipers.isSharpShooters());
        assertTrue(mapper.readValue(mapper.writeValueAsString(snipers), SBFFormation.class).isSharpShooters());
        String older = """
              {"type":"SBFFormation","generalname":"Line","units":[]}""";
        assertFalse(mapper.readValue(older, SBFFormation.class).isSharpShooters());
    }

    @Test
    void brawlersTravelWithTheFormationAndOlderDataHasNone() throws Exception {
        var mapper = new ObjectMapper();
        String authored = """
              {"type":"SBFFormation","generalname":"Close Assault","units":[],"brawlers":true}""";
        SBFFormation brawlers = mapper.readValue(authored, SBFFormation.class);
        assertTrue(brawlers.isBrawlers());
        assertTrue(mapper.readValue(mapper.writeValueAsString(brawlers), SBFFormation.class).isBrawlers());
        String older = """
              {"type":"SBFFormation","generalname":"Line","units":[]}""";
        assertFalse(mapper.readValue(older, SBFFormation.class).isBrawlers());
    }

    @Test
    void brawlersHaveTheirOwnOneThirdLimitAndCannotJoinSharpShooters() {
        // Brawlers, IO:BF p. 225: "No more than one-third (rounding down) of all Formations may have this ability."
        var game = new SBFGame();
        game.addPlayer(1, new Player(1, "Owner"));
        for (int id = 10; id < 16; id++) {
            var formation = new SBFFormation();
            formation.setId(id);
            formation.setOwnerId(1);
            formation.setBrawlers(id >= 12);
            formation.setSharpShooters(id == 12);
            game.addUnit(formation);
        }
        var manager = mock(SBFGameManager.class);
        when(manager.getGame()).thenReturn(game);
        SBFCommandAbilities.validateSetup(manager);
        assertTrue(game.getFormation(12).orElseThrow().isSharpShooters());
        assertFalse(game.getFormation(12).orElseThrow().isBrawlers());
        assertTrue(game.getFormation(13).orElseThrow().isBrawlers());
        assertTrue(game.getFormation(14).orElseThrow().isBrawlers());
        assertFalse(game.getFormation(15).orElseThrow().isBrawlers());
    }

    @Test
    void noMoreThanOneThirdOfAForcesFormationsKeepSharpShooters() {
        // Sharp Shooters, IO:BF p. 225: "No more than one-third (rounding down) of all Formations may have this ability."
        var game = new SBFGame();
        game.addPlayer(1, new Player(1, "Owner"));
        for (int id = 10; id < 14; id++) {
            var formation = new SBFFormation();
            formation.setId(id);
            formation.setOwnerId(1);
            formation.setSharpShooters(id < 12);
            game.addUnit(formation);
        }
        var manager = mock(SBFGameManager.class);
        when(manager.getGame()).thenReturn(game);
        SBFCommandAbilities.validateSetup(manager);
        assertTrue(game.getFormation(10).orElseThrow().isSharpShooters());
        assertFalse(game.getFormation(11).orElseThrow().isSharpShooters());
        assertEquals(1, game.getActiveFormations().stream().filter(SBFFormation::isSharpShooters).count());
    }
}
