/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Check the exported triangles against playable road footprints, including every turn and junction. */
class BoardBridgeTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    static IntStream masks() { return IntStream.range(0, 64); }

    @ParameterizedTest(name = "Bridge exits {0}: connected deck and outside rails")
    @MethodSource("masks")
    void completeDeckHasNoHolesOrRailsAcrossItsCarriageway(int exits) {
        Coords at = new Coords(0, 0);
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, exits));
        hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
        var features = BoardFeatures.capture(hex, at, Map.of()).stream()
              .filter(f -> f.asset().equals("bridge")).toList();
        assertEquals(1, features.size());
        assertEquals(exits, features.getFirst().bridgeExits());

        File root = new File(Configuration.dataDir(), "models/board");
        var file = new FileHandle(new File(root, BoardBridge.asset(exits) + ".glb"));
        var model = RigidGlb.loadLods(file, root.toPath()).getFirst();
        List<float[]> top = new ArrayList<>();
        for (var mesh : model.meshes) {
            for (var part : mesh.parts) {
                for (int i = 0; i < part.indices.length; i += 3) {
                    float[] triangle = new float[9];
                    for (int p = 0; p < 3; p++) {
                        System.arraycopy(mesh.vertices, Short.toUnsignedInt(part.indices[i + p]) * RigidGlb.STRIDE,
                              triangle, p * 3, 3);
                    }
                    if (triangle[2] == triangle[5] && triangle[5] == triangle[8]) { top.add(triangle); }
                }
            }
        }
        var road = BoardRoad.clearance(at, exits);
        var carriageway = road.footprint(-.4f);
        var rails = road.footprint(BoardRoad.SHOULDER - .3f);
        rails.subtract(road.footprint(.3f));
        var center = BoardGeometry.center(at, 0);
        int deckSamples = 0, railSamples = 0;
        for (float y = -35.69f; y < 36; y += 1) {
            for (float x = -41.77f; x < 42; x += 1) {
                if (!BoardGeometry.contains(at, x + center.x, y + center.y)) { continue; }
                if (carriageway.contains(x, y)) {
                    assertEquals(0, height(top, x, y), .001f, "Hole or rail in deck at " + x + "," + y);
                    deckSamples++;
                } else if (rails.contains(x, y)) {
                    assertEquals(2.5f, height(top, x, y), .001f, "Missing outside rail at " + x + "," + y);
                    railSamples++;
                }
            }
        }
        assertTrue(deckSamples > 100);
        assertTrue(railSamples > 10);
        if (BoardRoad.roundabout(exits)) {
            assertEquals(2.5f, height(top, 0, 0), .001f, "The bridge roundabout has a solid raised island");
            for (int angle = 0; angle < 360; angle += 5) {
                float nx = (float) Math.cos(Math.toRadians(angle)), ny = (float) Math.sin(Math.toRadians(angle));
                for (float along : new float[] { -9, 9 }) {
                    for (float across : new float[] { -5, 5 }) {
                        assertEquals(0, height(top, (18 + across) * nx - along * ny, (18 + across) * ny + along * nx),
                              .001f, "Vehicle body clearance must include the bridge's physical island and rails");
                    }
                }
            }
        }
        // Each actual exit is open all the way to the boundary; absent exits must not acquire a phantom deck.
        for (int d = 0; d < 6; d++) {
            var gate = BoardGeometry.center(at.translated(d), 0).sub(center).scl(.499f);
            float z = height(top, gate.x, gate.y);
            if ((exits & (1 << d)) != 0) { assertEquals(0, z, .001f, "Exit " + d); }
            else { assertEquals(Float.NEGATIVE_INFINITY, z, "Unconnected exit " + d); }
        }
    }

    private static float height(List<float[]> triangles, float x, float y) {
        float highest = Float.NEGATIVE_INFINITY;
        for (float[] t : triangles) {
            float denominator = (t[4] - t[7]) * (t[0] - t[6]) + (t[6] - t[3]) * (t[1] - t[7]);
            if (Math.abs(denominator) < 1e-6f) { continue; }
            float a = ((t[4] - t[7]) * (x - t[6]) + (t[6] - t[3]) * (y - t[7])) / denominator;
            float b = ((t[7] - t[1]) * (x - t[6]) + (t[0] - t[6]) * (y - t[7])) / denominator;
            if (a >= -1e-5f && b >= -1e-5f && a + b <= 1.00001f) { highest = Math.max(highest, t[2]); }
        }
        return highest;
    }
}
