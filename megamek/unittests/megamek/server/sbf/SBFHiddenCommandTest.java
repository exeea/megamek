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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.options.SBFRuleOptions;
import megamek.common.strategicBattleSystems.SBFElementType;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFVisibilityStatus;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class SBFHiddenCommandTest {
    @Test
    void hiddenSetupRequiresCoverAndOmitsTheFormationFromOrdinaryTurnsAndEnemySnapshots() {
        SBFGame game = new SBFGame();
        SBFGameManager manager = new SBFGameManager();
        manager.setGame(game);
        game.setBoard(0, Board.createEmptyBoard(6, 6));
        game.addPlayer(0, new Player(0, "Hidden"));
        game.addPlayer(1, new Player(1, "Opponent"));
        BoardLocation position = BoardLocation.of(new Coords(2, 2), 0);
        SBFFormation hidden = new SBFFormation();
        hidden.setId(20);
        hidden.setOwnerId(0);
        hidden.setType(SBFElementType.BM);
        hidden.setDeployed(true);
        hidden.setPosition(position);
        game.addUnit(hidden);
        game.getOptions().getOption(SBFRuleOptions.BASE_HIDDEN).setValue(true);
        assertFalse(SBFHiddenCommand.mayPlace(game, hidden, position));
        game.getHex(position).addTerrain(new Terrain(Terrains.WOODS, 1));
        assertTrue(SBFHiddenCommand.mayPlace(game, hidden, position));
        var element = new megamek.common.alphaStrike.AlphaStrikeElement();
        var unit = new megamek.common.strategicBattleSystems.SBFUnit();
        unit.setType(SBFElementType.MX);
        unit.setElements(List.of(element));
        hidden.addUnit(unit);
        element.setType(megamek.common.alphaStrike.ASUnitType.SV);
        element.getSpecialAbilities().setSUA(BattleForceSUA.LG);
        assertFalse(SBFHiddenCommand.canHideAt(game, hidden, position));
        game.getHex(position).addTerrain(new Terrain(Terrains.BUILDING, 1));
        assertTrue(SBFHiddenCommand.canHideAt(game, hidden, position));
        element.setType(megamek.common.alphaStrike.ASUnitType.MS);
        assertFalse(SBFHiddenCommand.canHideAt(game, hidden, position));
        element.setType(megamek.common.alphaStrike.ASUnitType.BM);
        hidden.setHidden(true);
        assertFalse(hidden.isEligibleForPhase(GamePhase.MOVEMENT));
        assertFalse(hidden.isEligibleForPhase(GamePhase.FIRING));
        assertTrue(hidden.isEligibleForPhase(GamePhase.PREMOVEMENT));
        assertEquals(List.of(), manager.createGameStartUnitPacket(game.getPlayer(1)).getObject(0));
        assertEquals(List.of(hidden), manager.createGameStartUnitPacket(game.getPlayer(0)).getObject(0));
        assertNull(manager.unitUpdateHelper.representationFor(game.getPlayer(1), hidden));

        SBFFormation mover = new SBFFormation();
        mover.setOwnerId(1);
        mover.setId(21);
        mover.setSkill(4);
        SBFHiddenCommand.detectOnEntry(manager, mover, position, () -> 6);
        assertTrue(hidden.isHidden());
        mover.getSpecialAbilities().setSUA(BattleForceSUA.RCN);
        SBFHiddenCommand.detectOnEntry(manager, mover, position, () -> 6);
        assertFalse(hidden.isHidden());
        assertTrue(hidden.isEligibleForPhase(GamePhase.FIRING));
        hidden.setHidden(true);
        game.getOptions().getOption(SBFRuleOptions.BASE_RECON).setValue(true);
        mover.getSpecialAbilities().setSUA(BattleForceSUA.PRB);
        SBFHiddenCommand.detectOnEntry(manager, mover, position, () -> { throw new AssertionError("A probe detects automatically"); });
        assertFalse(hidden.isHidden());
        assertEquals(SBFVisibilityStatus.EYES_ON_TARGET, game.getVisibility(game.getPlayer(1), 20));
    }
}
