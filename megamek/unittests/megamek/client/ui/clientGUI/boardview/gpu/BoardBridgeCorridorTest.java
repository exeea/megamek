/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Road and rail share one bridge slab, including where the track ends beside a continuing road. */
class BoardBridgeCorridorTest {
    private static final Coords AT = new Coords(4, 4);

    @Test
    void movingTheTrackBesideTheRoadKeepsOneWideDeckWithOnlyOutsideBarriers() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int beside : new int[] { 0, 9 }) {
                for (int right : new int[] { 0, 9 }) {
                    var shape = shape(9, new RailRoute.Corridor(9, beside, right), truss);
                    for (float y : new float[] { -30, -15, 0, 15, 30 }) {
                        for (float x : new float[] { -13, -8.25f, -2, 0, 2, 8.25f, 13 }) {
                            assertEquals(0, height(shape, x, y), .001f,
                                  "The road, rail and space between them belong to one unobstructed deck");
                        }
                    }
                    for (float x : new float[] { -14.65f, 14.65f }) {
                        assertTrue(height(shape, x, 0) > 1, "The outside bridge barriers are retained");
                    }
                }
            }
        }
    }

    @Test
    void trackTerminationDoesNotBuildAWallBetweenTheTrackAndContinuingRoad() {
        for (boolean truss : new boolean[] { false, true }) {
            for (int right : new int[] { 0, 8 }) {
                var shape = shape(8, new RailRoute.Corridor(8, 8, right), truss);
                for (float y : new float[] { -30, -15, 0, 4 }) {
                    for (float x : new float[] { -11, -8.25f, -2, 0, 2, 8.25f, 11 }) {
                        assertEquals(0, height(shape, x, y), .001f,
                              "The rail stop must not grow an internal parapet or rounded truss enclosure");
                    }
                }
                for (float y : new float[] { 12, 24, 34 }) {
                    assertEquals(0, height(shape, 0, y), .001f, "The road continues past the track end");
                }
            }
        }
    }

    @Test
    void taperedTrussKeepsOpenPanelsWithoutCuttingAcrossItsCarriageway() {
        var corridor = new RailRoute.Corridor(8, 8, 8);
        var shape = shape(8, corridor, true);
        var carriageway = BoardRoad.layout(AT, 9, BoardRoad.Kind.PAVED, d -> BoardRoad.Kind.PAVED)
              .bridgeCorridor(corridor).footprint(-.05f);
        for (var face : shape.facets()) {
            if (face.part() != BoardBridge.Part.STEEL) { continue; }
            for (var point : List.of(face.a(), face.b(), face.c())) {
                if (point.z > 2) {
                    assertFalse(carriageway.contains(point.x, point.y), "Steel intrudes into the road at " + point);
                }
            }
        }
        int covered = 0, longest = 0;
        for (float y = .125f; y < 35; y += .25f) {
            float hit = shape.hit(new Ray(new Vector3(-30, y, 6), new Vector3(1, 0, 0)));
            // Only the nearer truss: a hit beyond the center belongs to the far side.
            covered = hit < 30 * 30 ? covered + 1 : 0;
            longest = Math.max(longest, covered);
        }
        assertTrue(longest * .25f < 4, "The taper must keep open Warren panels rather than a dense steel wall");
    }

    private static BoardBridge.Shape shape(int railExits, RailRoute.Corridor corridor, boolean truss) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        hex.setDecorations(List.of(new BoardDecoration("track", "prop", RailRoute.Kind.RAIL.asset, null,
              0, 0, 0, false, 1, BoardDecoration.Placement.on("bridge", 0), 0).withConnections(railExits)));
        var tile = BoardBridgeSlopeTest.bridge(AT, 0, 1, 9)
              .withFeatures(BoardFeatures.capture(hex, AT, Map.of()));
        var deck = new BoardBridge.Deck(BoardRoad.Kind.PAVED, 9, 9, false,
              Collections.nCopies(6, BoardRoad.Kind.PAVED), BoardScene.Surface.CONCRETE,
              Collections.nCopies(6, 0f), false, truss ? BoardDecoration.Colours.of("#732417") : null, 9, corridor);
        return BoardBridge.shape(deck.surface(), 0, BoardBridgeMesh.build(tile, deck));
    }

    private static float height(BoardBridge.Shape shape, float x, float y) {
        return 30 - (float) Math.sqrt(shape.hit(new Ray(new Vector3(x, y, 30), new Vector3(0, 0, -1))));
    }
}
