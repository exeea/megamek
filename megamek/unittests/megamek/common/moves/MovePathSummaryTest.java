/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.List;

import megamek.common.Player;
import megamek.common.TargetRollModifier;
import megamek.common.ToHitData;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The target movement modifier of a plotted path (a Dervish: walk 5, run 8, jump 5 on a clear board). The walked and
 * run distances are pinned by StepSpriteTmmTest, which draws the same modifier.
 */
class MovePathSummaryTest {

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @Test
    void jumpingThreeHexes() throws Exception {
        ToHitData tmm = MovePathSummary.tmm(path(MoveStepType.START_JUMP, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.FORWARDS));

        assertEquals(2, tmm.getValue());
        assertEquals(List.of("target moved 3-4 hexes", "target jumped"), descriptions(tmm));
    }

    @Test
    void turningInPlace() throws Exception {
        ToHitData tmm = MovePathSummary.tmm(path(MoveStepType.TURN_LEFT));

        assertEquals(0, tmm.getValue());
        assertEquals(List.of(), descriptions(tmm));
    }

    /** A path without steps is standing still, as its heat is. */
    @Test
    void anEmptyPath() throws Exception {
        ToHitData tmm = MovePathSummary.tmm(path());

        assertEquals(0, tmm.getValue());
        assertEquals(List.of(), descriptions(tmm));
    }

    private static List<String> descriptions(ToHitData tmm) {
        return tmm.getModifiers().stream().map(TargetRollModifier::description).toList();
    }

    private static MovePath path(MoveStepType... steps) throws Exception {
        Game game = new Game();
        game.setBoard(ClearBoard.of(16, 17));
        game.setPhase(GamePhase.MOVEMENT);
        Player player = new Player(0, "Player");
        game.addPlayer(0, player);
        Entity dervish = new MekFileParser(new File("testresources/megamek/common/units/Dervish DV-11DK.mtf"))
              .getEntity();
        dervish.setId(1);
        dervish.setOwner(player);
        dervish.setPosition(new Coords(7, 12));
        dervish.setFacing(0);
        dervish.setDeployed(true);
        game.addEntity(dervish, false);
        MovePath path = new MovePath(game, dervish);
        for (MoveStepType step : steps) {
            path.addStep(step);
        }
        return path;
    }
}
