/*
 * Copyright (C) 2014-2026 The MegaMek Team. All Rights Reserved.
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
package megamek.common.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import megamek.common.board.Board;
import org.junit.jupiter.api.Test;

/**
 * @author Deric Page (deric.page@nisc.coop) (ext 2335)
 * @since 9/3/14 1:44 PM
 */
class BoardUtilitiesTest {

    @Test
    void combiningRepeatedSheetsCopiesDecorationsAndKeepsTheirMetadata() {
        Board source = Board.createEmptyBoard(2, 2);
        source.setNativeFormat(true); source.setSourceHeader("# Original author"); source.addTag("test");
        var at = new megamek.common.board.Coords(0, 0);
        source.setAnnotations(at, java.util.List.of("annotation"));
        var object = new megamek.common.board.BoardDecoration("car", "prop", "car", null, .2, -.1, 30,
              false, .8, megamek.common.board.BoardDecoration.Placement.absolute(4), 0);
        source.getHex(at).setDecorations(java.util.List.of(object));
        Board result = BoardUtilities.combine(2, 2, 2, 1, new Board[] { source, source }, megamek.common.loaders.MapSettings.MEDIUM_GROUND);
        assertNotSame(source.getHex(at), result.getHex(at));
        var instances = java.util.List.of(result.getHex(0, 0).getDecorations().getFirst(), result.getHex(2, 0).getDecorations().getFirst());
        assertEquals(2, instances.stream().map(megamek.common.board.BoardDecoration::id).distinct().count());
        assertEquals(object.placement(), instances.get(1).placement());
        assertEquals(java.util.List.of("annotation"), result.getAnnotations(new megamek.common.board.Coords(2, 0)));
        assertEquals(source.getSourceHeader(), result.getSourceHeader());
        assertTrue(result.isNativeFormat());
        assertTrue(result.getTags().contains("test"));
        result.getHex(at).setDecorations(java.util.List.of());
        assertEquals(java.util.List.of(object), source.getHex(at).getDecorations());
    }

    @Test
    void flipsTransformCenterObjectsWithoutLosingNorthSouthEdgesOrLegacyDesigns() {
        Board board = Board.createEmptyBoard(3, 3);
        var at = new megamek.common.board.Coords(1, 1);
        var hex = new megamek.common.Hex(0, "road:1:9;building:1:65;bldg_cf:15;bldg_elev:1", "");
        var object = new megamek.common.board.BoardDecoration("tree", "prop", "tree", null, .2, -.1, 30,
              false, .8, megamek.common.board.BoardDecoration.Placement.ground(), 0);
        hex.setDecorations(java.util.List.of(object)); board.setHex(at, hex);
        BoardUtilities.flip(board, true, false);
        var flipped = board.getHex(at).getDecorations().getFirst();
        assertEquals(-.2, flipped.x()); assertEquals(-30, flipped.rotation()); assertTrue(flipped.mirror());
        assertEquals(9, board.getHex(at).getTerrain(megamek.common.units.Terrains.ROAD).getExits());
        assertEquals(65, board.getHex(at).getTerrain(megamek.common.units.Terrains.BUILDING).getExits());
        BoardUtilities.flip(board, true, false);
        assertEquals(object, board.getHex(at).getDecorations().getFirst());
    }

    @Test
    void testCraterProfile() {
        int craterRadius = 8;
        int maxDepth = 4;

        // Start at the center;
        int distanceFromCenter = 0;
        int expected = -4;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // One hex from center;
        distanceFromCenter = 1;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Three hexes from center;
        distanceFromCenter = 3;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Four hexes from center;
        distanceFromCenter = 4;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Five hexes from center;
        distanceFromCenter = 5;
        expected = -3;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Six hexes from center;
        distanceFromCenter = 6;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Seven hexes from center;
        distanceFromCenter = 7;
        expected = -2;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));

        // Eight hexes from center;
        distanceFromCenter = 8;
        expected = 0;
        assertEquals(expected, BoardUtilities.craterProfile(distanceFromCenter, craterRadius, maxDepth));
    }

    /**
     * Crater radius bounds come from unvalidated client-supplied map settings. An inverted range
     * (max below min) must not reach {@link megamek.common.compute.Compute#randomInt(int)}, which rejects a
     * bound of zero or less, nor the crater-profile array sizing.
     */
    @Test
    void addCratersToleratesAnInvertedRadiusRange() {
        Board board = emptyBoard();

        assertDoesNotThrow(() -> BoardUtilities.addCraters(board, 6, 2, 1, 1));
    }

    /** A negative minimum radius must not produce a negative crater-profile array size either. */
    @Test
    void addCratersToleratesANegativeRadiusRange() {
        Board board = emptyBoard();

        assertDoesNotThrow(() -> BoardUtilities.addCraters(board, -5, -2, 1, 1));
    }

    /** @return a small blank board that craters can be placed on */
    private static Board emptyBoard() {
        Board board = new Board();
        board.load("""
              size 5 5
              hex 0101 0 "" ""
              end""", null);
        return board;
    }
}
