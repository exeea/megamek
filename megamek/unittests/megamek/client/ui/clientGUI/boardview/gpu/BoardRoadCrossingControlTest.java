/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.HexTile;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRoadCrossingControlTest {
    private static final Coords AT = new Coords(3, 3);

    private static Hex support(Hex hex, Coords at) { return AT.equals(at) ? hex : new Hex(0, "", ""); }

    private static List<BoardScene.Feature> controls(Hex hex) {
        return BoardFeatures.roadControls(hex, AT, at -> support(hex, at), true);
    }

    @Test
    void independentCrossingsHaveFacingSignalsOutsideBothTravelEnvelopesOnTheirOwnLayer() {
        for (boolean deck : new boolean[] { false, true }) {
            for (int axis = 0; axis < 3; axis++) {
                int roadSides = 9 << axis, railSides = 9 << ((axis + 1) % 3);
                Hex hex = new Hex(0, deck ? "bridge:1:63;bridge_cf:40;bridge_elev:2;bridge_road:1:"
                      + roadSides + ";bridge_rail:1:" + railSides : "road:1:" + roadSides + ";rail:1:" + railSides, "");
                var corridor = RailRoute.corridor(hex, AT, at -> support(hex, at), deck);
                var road = BoardRoad.layout(AT, roadSides, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.NONE).corridor(corridor);
                var marker = RailRoute.markers(hex, RailRoute.Kind.RAIL).getFirst();
                var signals = controls(hex);
                assertEquals(2, signals.size(), "Both incoming lanes get a crossing signal: axis=" + axis + " deck=" + deck);
                for (var signal : signals) {
                    assertEquals("scenery/rail/crossing-signal", signal.asset());
                    assertEquals(deck ? "bridge" : "ground", signal.decoration().placement().receiver().terrain());
                    assertTrue(BoardFeatures.terrainOwned(signal.decoration()));
                    assertFalse(road.footprint(0).contains(signal.x(), signal.y()));
                    if (deck) {
                        assertTrue(road.distance(signal.x(), signal.y()) <= BoardBridge.SHOULDER - .7f + .0001f,
                              "The whole footing stays on the deck shoulder");
                    }
                    var track = RailRoute.snap(hex, marker, signal.x(), signal.y(), 0, corridor);
                    assertTrue(Math.hypot(signal.x() - track.x(), signal.y() - track.y()) >= RailRoute.BALLAST_HALF_WIDTH + .7f);
                    double angle = Math.toRadians(signal.rotation());
                    assertTrue(-Math.sin(angle) * signal.x() + Math.cos(angle) * signal.y() > 0,
                          "The face points towards the waiting approach");
                }
                assertTrue(hex.getDecorations().isEmpty(), "Derived signals are not serialized as loose objects");
            }
        }
    }

    @Test
    void parallelMaglevAndGradeSeparatedRoutesDoNotGetLevelCrossingFurniture() {
        for (String terrain : List.of("road:1:9;rail:1:9", "road:1:9;rail:2:18",
              "road:1:9;bridge:1:18;bridge_cf:40;bridge_elev:2;bridge_rail:1:18",
              "rail:1:18;bridge:1:9;bridge_cf:40;bridge_elev:2;bridge_road:1:9")) {
            assertTrue(controls(new Hex(0, terrain, "")).isEmpty(), terrain);
        }
    }

    @Test
    void savedCrossingSignalPreventsANearbyGeneratedDuplicate() {
        Hex hex = new Hex(0, "road:1:9;rail:1:18", "");
        var signal = controls(hex).getFirst();
        hex.setDecorations(List.of(new BoardDecoration("saved-crossing", "prop", signal.asset(), null,
              signal.x() / HexTile.MODEL_WIDTH, signal.y() / HexTile.HEIGHT, signal.rotation(), false, 1,
              BoardDecoration.Placement.ground(), 0, false)));
        assertEquals(1, controls(hex).size());
    }
}
