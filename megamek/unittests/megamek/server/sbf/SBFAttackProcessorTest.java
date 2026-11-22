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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import megamek.common.Player;
import megamek.common.actions.sbf.SBFStandardUnitAttack;
import megamek.common.alphaStrike.ASRange;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.strategicBattleSystems.SBFFormation;
import megamek.common.strategicBattleSystems.SBFGame;
import megamek.common.strategicBattleSystems.SBFToHitData;
import megamek.common.strategicBattleSystems.SBFUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests what the server does with submitted SBF attacks: Sprinting (IO:BF p.199). */
class SBFAttackProcessorTest {

    private static final int OWNER_ID = 1;
    private static final int ENEMY_ID = 2;
    private static final int FORMATION_ID = 17;
    private static final int TARGET_ID = 20;

    private SBFGame game;
    private SBFGameManager gameManager;
    private SBFAttackProcessor processor;
    private SBFFormation formation;
    private SBFFormation target;

    @BeforeEach
    void setUp() {
        game = new SBFGame();
        game.setPhase(GamePhase.FIRING);
        game.addPlayer(OWNER_ID, new Player(OWNER_ID, "Owner"));
        game.addPlayer(ENEMY_ID, new Player(ENEMY_ID, "Enemy"));
        formation = formation(FORMATION_ID, OWNER_ID, new Coords(1, 1), 2);
        target = formation(TARGET_ID, ENEMY_ID, new Coords(2, 1), 1);
        game.addUnit(formation);
        game.addUnit(target);

        gameManager = mock(SBFGameManager.class);
        when(gameManager.getGame()).thenReturn(game);
        processor = new SBFAttackProcessor(gameManager);
    }

    @Test
    void sprintedFormationCannotAttack() {
        formation.setSprintedThisTurn(true);

        processor.processAttacks(List.of(attack(0, target, ASRange.LONG)), formation);

        assertTrue(game.getActionsVector().isEmpty());
        assertFalse(formation.isDone());
        verify(gameManager, never()).sendUnitUpdate(any());
        verify(gameManager, never()).sendPendingActions();
        verify(gameManager, never()).endCurrentTurn(any());
    }

    @Test
    void attacksAgainstSprintingTargetReceiveToHitBonus() {
        SBFStandardUnitAttack submitted = attack(0, target, ASRange.LONG);
        int normalTargetNumber = SBFToHitData.compileToHit(game, submitted).getValue();

        target.setSprintedThisTurn(true);

        SBFToHitData sprintedTargetToHit = SBFToHitData.compileToHit(game, submitted);
        assertEquals(normalTargetNumber - 1, sprintedTargetToHit.getValue());
        assertTrue(sprintedTargetToHit.getDesc().contains("target sprinted"));
    }

    private SBFFormation formation(int id, int ownerId, Coords coords, int unitCount) {
        SBFFormation result = new SBFFormation();
        result.setId(id);
        result.setOwnerId(ownerId);
        result.setPosition(BoardLocation.of(coords, 0));
        for (int i = 0; i < unitCount; i++) {
            result.addUnit(new SBFUnit());
        }
        return result;
    }

    private SBFStandardUnitAttack attack(int unitNumber, SBFFormation attackTarget, ASRange range) {
        return new SBFStandardUnitAttack(FORMATION_ID, unitNumber, attackTarget.getId(), range);
    }
}
