/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.moves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import megamek.common.GameBoardTestCase;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.enums.MoveStepType;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Tank;
import megamek.common.units.Terrains;
import megamek.server.totalWarfare.TWGameManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** TacOps rails (TO:AR p.49 and p.32) as the game option plays them. */
class RailRulesTest extends GameBoardTestCase {

    static {
        // The rail in 0102 runs NE-SW, across the step from 0101.
        initializeBoard("ACROSS", """
              size 1 2
              hex 0101 0 "" ""
              hex 0102 0 "rail:1:18" ""
              end""");
        initializeBoard("ALONG_HEAVY_WOODS", """
              size 1 2
              hex 0101 0 "rail:1:8" ""
              hex 0102 0 "woods:2;rail:1:9" ""
              end""");
        initializeBoard("ROAD_AND_RAIL", """
              size 1 2
              hex 0101 0 "road:1:8;rail:1:8" ""
              hex 0102 0 "road:1:9;rail:1:9" ""
              end""");
        initializeBoard("WATER", """
              size 1 2
              hex 0101 0 "water:2" ""
              hex 0102 0 "water:2;rail:1:9" ""
              end""");
    }

    private void rails() {
        getGame().getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_RAILS).setValue(true);
    }

    private int step(Entity entity, EntityMovementMode mode) {
        return getMovePathFor(entity, mode, MoveStepType.FORWARDS).getMpUsed();
    }

    @Test
    void aRailCostsMeksAndGroundVehiclesOneMoreOnlyWithTheOption() {
        setBoard("ACROSS");
        assertEquals(1, step(new BipedMek(), EntityMovementMode.BIPED), "Off, rails play as nothing");
        rails();
        assertEquals(2, step(new BipedMek(), EntityMovementMode.BIPED));
        assertEquals(2, step(new Tank(), EntityMovementMode.TRACKED));
        assertEquals(1, step(new ConvInfantry(), EntityMovementMode.INF_LEG), "Infantry pass");
    }

    @Test
    void alongTheRailAStepPaysTheRoughCostInsteadOfTheTerrain() {
        setBoard("ALONG_HEAVY_WOODS");
        assertEquals(3, step(new BipedMek(), EntityMovementMode.BIPED), "Heavy woods, option off");
        rails();
        assertEquals(2, step(new BipedMek(), EntityMovementMode.BIPED));
    }

    @Test
    void aPavedRoadKeepsRoadMovementBesideTheRail() {
        setBoard("ROAD_AND_RAIL");
        rails();
        assertEquals(1, step(new BipedMek(), EntityMovementMode.BIPED));
    }

    @Test
    void attacksWearTheRailDownToADestroyedRailThatPlaysAsClear() {
        setBoard("ACROSS");
        rails();
        TWGameManager manager = Mockito.spy(new TWGameManager());
        Mockito.doNothing().when(manager).sendChangedHex(any(Coords.class), any(int.class));
        manager.setGame(getGame());
        Coords target = new Coords(0, 1);
        manager.tryClearHex(target, 0, 10, Entity.NONE);
        Hex hex = getGame().getBoard().getHex(target);
        assertEquals(10, hex.getTerrain(Terrains.RAIL).getTerrainFactor());
        assertTrue(manager.tryClearHex(target, 0, 10, Entity.NONE).stream().anyMatch(report -> report.messageId == 3094));
        assertEquals("rail:0:18", hex.getTerrain(Terrains.RAIL).toString(), "Destroyed, its bed keeps its sides");
        assertEquals(1, step(new BipedMek(), EntityMovementMode.BIPED));
    }

    @Test
    void navalVesselsNeverEnterARailHex() {
        setBoard("WATER");
        Tank boat = new Tank();
        boat.setMovementMode(EntityMovementMode.NAVAL);
        boat.setGame(getGame());
        assertFalse(boat.isLocationProhibited(new Coords(0, 1), 0, 0), "Off, rails play as nothing");
        rails();
        assertTrue(boat.isLocationProhibited(new Coords(0, 1), 0, 0));
        assertFalse(boat.isLocationProhibited(new Coords(0, 0), 0, 0));
    }
}
