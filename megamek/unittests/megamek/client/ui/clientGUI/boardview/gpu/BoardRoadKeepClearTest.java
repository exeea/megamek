/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.geom.Area;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;
import megamek.common.board.HexAppearance;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Test;

class BoardRoadKeepClearTest {
    private static final Coords AT = new Coords(3, 3);

    @Test
    void skewBoxesAndTheirStopsClearBothHalvesOfTheCrossing() {
        for (int axis = 0; axis < 3; axis++) {
            int first = 9 << axis, second = 9 << ((axis + 1) % 3), exits = first | second;
            for (var control : List.of(HexAppearance.TrafficControl.SIGNALS, HexAppearance.TrafficControl.GANTRY)) {
                var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED).control(control);
                for (var stop : road.stopApproaches(exits)) {
                    int crossing = ((1 << stop.direction()) & first) != 0 ? second : first;
                    var through = BoardRoad.layout(AT, crossing, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED);
                    for (float across = -stop.outer(); across <= stop.outer(); across += .5f) {
                        assertFalse(through.footprint(0).contains(stop.x() - stop.ny() * across,
                                    stop.y() + stop.nx() * across),
                              "The whole box entrance must clear the crossing, including the outgoing lane: "
                                    + exits + " approach " + stop.direction());
                    }
                }
            }
        }
    }

    @Test
    void fourArmBoxesStayInsideTheRoadClearOfStopBarsAndRailReservations() {
        for (int exits = 0; exits < 64; exits++) {
            if (Integer.bitCount(exits) != 4) { continue; }
            for (var corridor : List.of(RailRoute.Corridor.NONE,
                  new RailRoute.Corridor(exits, 0, 0), new RailRoute.Corridor(exits, exits, exits))) {
                var road = BoardRoad.layout(AT, exits, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
                      .corridor(corridor).control(HexAppearance.TrafficControl.GANTRY);
                var paint = GpuRoads.keepClear(road, exits);
                assertFalse(paint.isEmpty(), "Four-arm crossing " + exits + " " + corridor);
                var outside = new Area(paint);
                outside.subtract(road.footprint(0));
                assertTrue(outside.isEmpty(), "The box cannot leave the actual carriageway");
                var conflict = new Area(paint);
                conflict.intersect(road.stopLines(exits));
                assertTrue(conflict.isEmpty(), "White stop bars stay separate from the yellow box");
                conflict = new Area(paint);
                conflict.intersect(road.medianBand());
                assertTrue(conflict.isEmpty(), "The railway reserve must not receive road paint: " + exits + " " + corridor);
                int painted = 0, clear = 0;
                for (float x = -8; x <= 8; x += .5f) {
                    for (float y = -8; y <= 8; y += .5f) {
                        if (paint.contains(x, y)) { painted++; } else { clear++; }
                    }
                }
                assertTrue(clear > painted, "A box is an open lattice, never a filled diamond");
            }
        }
    }

    @Test
    void onlySignalControlledPavedFourArmJunctionsReceiveYellowBoxes() {
        for (int exits : new int[] { 9, 11, 27, 63 }) {
            for (var control : HexAppearance.TrafficControl.values()) {
                for (var kind : new BoardRoad.Kind[] { BoardRoad.Kind.PAVED, BoardRoad.Kind.DIRT }) {
                    var tile = mock(BoardScene.Tile.class);
                    when(tile.coords()).thenReturn(AT);
                    when(tile.surface()).thenReturn(BoardScene.Surface.GRASS);
                    when(tile.road()).thenReturn(kind);
                    when(tile.roadExits()).thenReturn(exits);
                    when(tile.appearance()).thenReturn(Map.of("road", HexAppearance.withTrafficControl(null, control)));
                    var road = BoardRoad.layout(AT, exits, kind, d -> kind).control(control);
                    boolean yellow = GpuRoads.patches(tile, road).stream()
                          .anyMatch(patch -> patch.texture().equals("concrete-ground") && patch.tint().b < .2f);
                    assertEquals(exits == 27 && kind == BoardRoad.Kind.PAVED && control != HexAppearance.TrafficControl.STOPS,
                          yellow, "Only an actual signal-controlled paved crossing receives the box");
                }
            }
        }
    }
}
