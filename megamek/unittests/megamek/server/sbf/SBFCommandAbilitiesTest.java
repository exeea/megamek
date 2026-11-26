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
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFMovePath;
import megamek.common.strategicBattleSystems.SBFUnit;
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
    void communicationsDisruptionCostsOneRandomEnemyFourMoveOnASix() {
        // Communications Disruption, IO:BF p. 226: "rolls 1D6. If the result of this roll is 6, one random Formation of
        // the opposing force ... reduces its available Move by 4 MP (to a minimum of 1 MP)."
        var game = new SBFGame();
        game.setCurrentRound(1);
        for (int id = 1; id <= 2; id++) {
            var player = new Player(id, "Player " + id);
            player.setTeam(id);
            game.addPlayer(id, player);
        }
        var commander = new SBFUnit();
        commander.setArmor(4);
        commander.setCurrentArmor(4);
        commander.getSpecialAbilities().setSUA(BattleForceSUA.COM);
        commander.getInitiativeAbilities().add(SBFUnit.InitiativeAbility.COMMUNICATIONS_DISRUPTION);
        formation(game, 10, 1, 6).addUnit(commander);
        var first = formation(game, 20, 2, 6);
        var second = formation(game, 21, 2, 3);
        var manager = mock(SBFGameManager.class);
        when(manager.getGame()).thenReturn(game);

        SBFCommandAbilities.disruptCommunications(manager, () -> 5, size -> 1);
        assertFalse(second.isCommunicationsDisrupted());
        SBFCommandAbilities.disruptCommunications(manager, () -> 6, size -> 1);
        assertTrue(second.isCommunicationsDisrupted());
        assertFalse(first.isCommunicationsDisrupted());
        assertEquals(1, SBFMovePath.normalMovementPoints(second, game));
        first.setCommunicationsDisrupted(true);
        assertEquals(2, SBFMovePath.normalMovementPoints(first, game));
    }

    private static SBFFormation formation(SBFGame game, int id, int owner, int movement) {
        var formation = new SBFFormation();
        formation.setId(id);
        formation.setOwnerId(owner);
        formation.setMovement(movement);
        formation.setDeployed(true);
        game.addUnit(formation);
        return formation;
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
