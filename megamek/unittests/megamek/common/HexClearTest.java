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
package megamek.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import megamek.common.board.BoardDecoration;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The one CLEAR hex definition ({@link Hex#isClear}, {@link Hex#isClearDeck}) that the rules read. */
class HexClearTest {

    private static Hex hex(String terrain) {
        return new Hex(0, terrain, "");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "fluff:4", "ground_fluff:1", "road_fluff:1", "water_fluff:1", "bldg_fluff:1",
                             "metal_deposit:1", "deployment_zone:1", "arms:1", "legs:1", "cliff_top:1:01",
                             "cliff_bottom:1:08", "incline_top:1:01", "incline_bottom:1:08", "incline_high_top:1:01",
                             "incline_high_bottom:1:08" })
    void artTokensInclinesAndModifierFreeMarkersLeaveTheGroundClear(String terrain) {
        assertTrue(hex(terrain).isClear(), terrain);
    }

    @Test
    void themesLooksAndPlacedObjectsNeverCount() {
        Hex lunar = new Hex(0, "", "lunar");
        lunar.setAppearance(Map.of("ground", new HexAppearance("ground/blend/grass", null, null, .5)));
        lunar.setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0)));
        assertTrue(lunar.isClear());
    }

    @ParameterizedTest
    @ValueSource(strings = { "water:0", "water:2", "woods:1;foliage_elev:2", "jungle:1;foliage_elev:2", "rough:1",
                             "rubble:1", "road:1:09", "road:3:09", "pavement:1", "mud:1", "snow:1", "ice:1",
                             "black_ice:1", "swamp:1", "sand:1", "tundra:1", "planted_fields:1", "magma:1",
                             "building:2;bldg_cf:40;bldg_elev:2", "heavy_industrial:2",
                             "fuel_tank:1;fuel_tank_cf:40;fuel_tank_elev:1;fuel_tank_magn:10", "fortified:1",
                             "smoke:1", "fire:1", "hazardous_liquid:1", "ultra_sublevel:1", "geyser:1",
                             "ground_fluff:2000;fluff:2003" })
    void terrainWithAModifierStructuresRoadsAndClearedRubbleAreNotClear(String terrain) {
        assertFalse(hex(terrain).isClear(), terrain);
    }

    @Test
    void aBuiltBridgeAboveLeavesTheGroundClearButNotItsDeck() {
        Hex raised = hex("bridge:1:09;bridge_cf:40;bridge_elev:2");
        assertTrue(raised.isClear());
        assertFalse(raised.isClearDeck());
        assertFalse(hex("bridge:1:09;bridge_cf:40;bridge_elev:2;road:1:09").isClear(), "the road beneath counts");
        assertFalse(hex("bridge:1:09;bridge_cf:40;bridge_elev:0").isClear(), "a deck at the hex's level is its surface");
    }

    @Test
    void aNaturalBridgeIsClear() {
        Hex raised = hex("bridge:1:09;bridge_cf:40;bridge_elev:2");
        raised.setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
        assertTrue(raised.isClearDeck());
        Hex level = hex("bridge:1:09;bridge_cf:40;bridge_elev:0");
        level.setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
        assertTrue(level.isClear());
    }

    @Test
    void aRailOnTheGroundCountsButARailOnADeckOrASuspendedMaglevDoesNot() {
        Hex groundRail = hex("");
        groundRail.setDecorations(List.of(marker(RailRoute.Kind.RAIL, BoardDecoration.Placement.ground(), 9)));
        assertFalse(groundRail.isClear());
        Hex stub = hex("");
        stub.setDecorations(List.of(marker(RailRoute.Kind.RAIL, BoardDecoration.Placement.ground(), 0)));
        assertFalse(stub.isClear(), "a marker without sides still lies on the ground");

        BoardDecoration.Placement onDeck = new BoardDecoration.Placement("surface", null,
              new BoardDecoration.Receiver("bridge", "deck"), 0.0);
        Hex natural = hex("bridge:1:09;bridge_cf:40;bridge_elev:2");
        natural.setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
        natural.setDecorations(List.of(marker(RailRoute.Kind.RAIL, onDeck, 9)));
        assertTrue(natural.isClear());
        assertFalse(natural.isClearDeck(), "a rail on the deck touches the deck");

        Hex maglev = hex("");
        maglev.setDecorations(List.of(marker(RailRoute.Kind.MAGLEV, new BoardDecoration.Placement("absolute", 3.0,
              null, null), 9)));
        assertTrue(maglev.isClear());
    }

    @Test
    void railAndDeckWayTerrainCountAsTheirMarkersDo() {
        assertFalse(hex("rail:1:09").isClear(), "an intact rail on the ground");
        assertTrue(hex("rail:0:09").isClear(), "a destroyed rail converts to Clear (TO:AR p.62)");
        Hex raised = hex("bridge:1:09;bridge_cf:40;bridge_elev:2;bridge_road:1:09;bridge_rail:1:09");
        assertTrue(raised.isClear(), "a deck's road and rail leave the ground beneath clear");
        for (String way : new String[] { "bridge_road:3:09", "bridge_rail:1:09" }) {
            Hex natural = hex("bridge:1:09;bridge_cf:40;bridge_elev:2;" + way);
            natural.setAppearance(Map.of("bridge", HexAppearance.NATURAL_BRIDGE));
            assertFalse(natural.isClearDeck(), way + " touches the deck");
        }
        Hex implicit = hex("water:1;bridge:1:09;bridge_cf:40;bridge_elev:0;bridge_road:1:09");
        implicit.setAppearance(Map.of("bridge", HexAppearance.IMPLICIT_BRIDGE));
        assertFalse(implicit.isClearDeck(), "an implicit bridge is built");
    }

    private static BoardDecoration marker(RailRoute.Kind kind, BoardDecoration.Placement placement, int sides) {
        return new BoardDecoration("route", "prop", kind.asset, null, 0, 0, 0, false, 1, placement, 0, false, 0, 0,
              null, false, sides);
    }
}
