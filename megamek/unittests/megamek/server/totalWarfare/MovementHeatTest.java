/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.server.totalWarfare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.Set;

import megamek.common.MMRandom;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.equipment.EquipmentType;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.moves.MovePathSummary;
import megamek.common.net.packets.Packet;
import megamek.common.options.OptionsConstants;
import megamek.common.rules.RulesManager;
import megamek.common.units.Entity;
import megamek.utils.ClearBoard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The heat the end of movement adds once the server has executed a real move (every die shows 6), and the heat a
 * preview of the same plotted path shows.
 */
class MovementHeatTest {
    private static final String UNITS = "testresources/megamek/common/units/";

    private RulesManager previousRules;
    private TWGameManager manager;
    private Game game;

    @BeforeAll
    static void initializeEquipment() {
        EquipmentType.initializeTypes();
    }

    @BeforeEach
    void setUp() {
        previousRules = Game.rulesManager;
        Compute.setRNG(new MaxRolls());
        manager = spy(new TWGameManager());
        doNothing().when(manager).send(any(Packet.class));
        doNothing().when(manager).sendServerChat(anyString());
        game = manager.getGame();
        game.initializeRulesManager(OptionsConstants.RULES_CORE);
        game.addPlayer(0, new Player(0, "Player"));
        game.setPhase(GamePhase.MOVEMENT);
        game.setBoard(ClearBoard.of(16, 17));
    }

    @AfterEach
    void tearDown() {
        Game.rulesManager = previousRules;
        Compute.setRNG(MMRandom.R_DEFAULT);
    }

    @Test
    void anAtlasStandingStill() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");

        assertMovementHeat(atlas, new MovePath(game, atlas), 0);
    }

    @Test
    void anAtlasWalkingTwoHexes() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");

        assertMovementHeat(atlas, plan(atlas, MoveStepType.FORWARDS, MoveStepType.FORWARDS), 1, "Movement (Walking)");
    }

    @Test
    void anAtlasRunningFourHexes() throws Exception {
        Entity atlas = unit("Atlas AS7-D.mtf");

        assertMovementHeat(atlas, plan(atlas, MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS), 2, "Movement (Running)");
    }

    /**
     * Evading (TacOps) means running, 2 heat more for a Mek without super-cooled myomer. The unit only evades once the
     * server executes the step, so the preview takes it from the plan.
     */
    @Test
    void anAtlasRunningFourHexesWhileEvading() throws Exception {
        game.getOptions().getOption(OptionsConstants.ADVANCED_GROUND_MOVEMENT_TAC_OPS_EVADE).setValue(true);
        Entity atlas = unit("Atlas AS7-D.mtf");

        assertMovementHeat(atlas, plan(atlas, MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.EVADE), 4, "Movement (Running)");
    }

    /** The Atlas has no jump jets, so a Dervish jumps (standard jump jets: 1 heat per hex, at least 3). */
    @Test
    void aDervishJumpingTwoHexes() throws Exception {
        Entity dervish = unit("Dervish DV-11DK.mtf");

        assertMovementHeat(dervish, plan(dervish, MoveStepType.START_JUMP, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS), 3, "Movement (Jumping)");
    }

    @Test
    void aDervishJumpingFiveHexes() throws Exception {
        Entity dervish = unit("Dervish DV-11DK.mtf");

        assertMovementHeat(dervish, plan(dervish, MoveStepType.START_JUMP, MoveStepType.FORWARDS,
              MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS, MoveStepType.FORWARDS), 5,
              "Movement (Jumping)");
    }

    /** Zero heat is not itemized, so standing still leaves no breakdown line. */
    private void assertMovementHeat(Entity unit, MovePath plan, int heat, String... breakdownLines) {
        assertEquals(heat, MovePathSummary.heat(plan), "preview of the plotted path");
        assertFalse(unit.isEvading(), "the preview leaves the unit as it was");
        new MovePathHandler(manager, unit, plan, null).processMovement();
        manager.addMovementHeat();

        assertEquals(heat, unit.heatBuildup, "server movement heat");
        assertEquals(Set.of(breakdownLines), unit.getHeatBreakdown().buildup().keySet());
    }

    private Entity unit(String file) throws Exception {
        Entity unit = new MekFileParser(new File(UNITS + file)).getEntity();
        unit.setId(1);
        unit.setOwner(game.getPlayer(0));
        game.addEntity(unit);
        unit.setPosition(new Coords(7, 12));
        unit.setFacing(0);
        unit.setSecondaryFacing(0);
        unit.setDeployed(true);
        return unit;
    }

    private MovePath plan(Entity unit, MoveStepType... steps) {
        MovePath plan = new MovePath(game, unit);
        for (MoveStepType step : steps) {
            plan.addStep(step);
        }
        return plan;
    }

    /** Every die shows 6. */
    private static final class MaxRolls extends MMRandom {
        @Override
        public int randomInt(int maxValue) {
            return maxValue - 1;
        }

        @Override
        public float randomFloat() {
            return 0.999f;
        }
    }
}
