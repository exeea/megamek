/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.common.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import megamek.common.Hex;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardLevelOffsetTest {
    @Test
    void shiftMovesGroundWaterAndAbsoluteObjectsTogetherButPreservesRelativeHeights() {
        Hex low = new Hex(-2, "water:2;bridge:1;bridge_elev:3;bridge_cf:40", "grass");
        Hex high = new Hex(1, "woods:1;foliage_elev:2;solaris_elevator:3:63", "grass");
        BoardDecoration ground = object("ground", BoardDecoration.Placement.ground());
        BoardDecoration bridge = object("bridge", BoardDecoration.Placement.on("bridge", .5));
        BoardDecoration absolute = object("absolute", BoardDecoration.Placement.absolute(4.5));
        low.setDecorations(List.of(ground, bridge, absolute));
        Board board = new Board(2, 1, new Hex[] { low, high });
        assertEquals(-2, board.getMinElevation());
        assertEquals(1, board.getMaxElevation());

        board.shiftLevels(5);

        assertEquals(3, low.getLevel());
        assertEquals(6, high.getLevel());
        assertEquals(1, low.floor());
        assertEquals(2, low.terrainLevel(Terrains.WATER));
        assertEquals(3, low.terrainLevel(Terrains.BRIDGE_ELEV));
        assertEquals(2, high.terrainLevel(Terrains.FOLIAGE_ELEV));
        assertEquals(8, high.terrainLevel(Terrains.SOLARIS_ELEVATOR));
        assertEquals(63, high.getTerrain(Terrains.SOLARIS_ELEVATOR).getExits());
        assertEquals(3, board.getMinElevation());
        assertEquals(6, board.getMaxElevation());
        assertSame(ground, low.getDecorations().get(0));
        assertSame(bridge, low.getDecorations().get(1));
        assertEquals(6.5, bridge.placement().level(low));
        assertEquals(absolute.transform(absolute.x(), absolute.y(), absolute.rotation(), absolute.mirror(),
              absolute.scale(), BoardDecoration.Placement.absolute(9.5)), low.getDecorations().get(2));

        board.shiftLevels(-5);
        assertEquals(-2, board.getMinElevation());
        assertEquals(1, board.getMaxElevation());
        assertEquals(3, high.terrainLevel(Terrains.SOLARIS_ELEVATOR));
        assertEquals(List.of(ground, bridge, absolute), low.getDecorations());
        board.shiftLevels(0);
        assertEquals(-2, low.getLevel());
    }

    private static BoardDecoration object(String id, BoardDecoration.Placement placement) {
        return new BoardDecoration(id, "prop", "scenery/vehicles/car", "Example", .2, .1, 30,
              true, 1.5, placement, 0, false, 10, 20, "group", true, 0,
              new BoardDecoration.Stretch(2, 1, 1), BoardDecoration.Colours.of("#123456"));
    }
}
