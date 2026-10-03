/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardSurfaceEdgeSamplingTest {
    @ParameterizedTest
    @ValueSource(floats = { 0, 3500, 50000 })
    void localEdgeSamplingMatchesEveryHeightAtSmallAndLargeCoordinates(float offset) {
        float scale = BoardGeometry.hexScale();
        List<BoardSurface.Face> faces = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                Vector3 a = point(x, y, offset, scale), b = point(x + 1, y, offset, scale);
                Vector3 c = point(x + 1, y + 1, offset, scale), d = point(x, y + 1, offset, scale);
                faces.add(new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP));
                faces.add(new BoardSurface.Face(a, c, d, BoardSurface.Finish.TOP));
            }
        }
        for (float[] line : new float[][] { {0, 0, 20, 0}, {20, 0, 20, 20}, {0, 0, 20, 20}, {0, 20, 20, 0} }) {
            Vector3 a = point(line[0], line[1], offset, scale), b = point(line[2], line[3], offset, scale);
            var candidates = BoardSurface.edgeCandidates(faces, a, b);
            assertTrue(candidates.size() < faces.size() / 4, "Only triangles near this edge need height tests");
            for (int i = 0; i <= 1000; i++) {
                // Use the same float interpolation as roadPoint, including its rounding far from the origin.
                Vector3 p = new Vector3(a).lerp(b, i / 1000f);
                float expected = BoardSurface.sampleHeight(faces, p.x, p.y, Float.NaN);
                assertEquals(expected, BoardSurface.sampleHeight(candidates, p.x, p.y, Float.NaN),
                      "Filtering must not alter any float height at " + p);
            }
        }
    }

    @Test
    void edgeCandidatesKeepBorderToleranceAndExactRoofPrecedence() {
        float scale = BoardGeometry.hexScale(), near = .0005f * scale;
        var exact = new BoardSurface.Face(new Vector3(0, 0, 2), new Vector3(10 * scale, 0, 2),
              new Vector3(0, 10 * scale, 2), BoardSurface.Finish.TOP);
        var tolerant = new BoardSurface.Face(new Vector3(0, -near, 9), new Vector3(0, -10 * scale, 9),
              new Vector3(10 * scale, -near, 9), BoardSurface.Finish.TOP);
        var ignored = new BoardSurface.Face(new Vector3(0, 0, 20), new Vector3(10 * scale, 0, 20),
              new Vector3(0, 10 * scale, 20), BoardSurface.Finish.DRESSING);
        var faces = List.of(tolerant, ignored, exact);
        var a = new Vector3(0, 0, 0);
        var b = new Vector3(10 * scale, 0, 0);
        var candidates = BoardSurface.edgeCandidates(faces, a, b);
        assertEquals(faces, candidates, "Broad-phase selection retains the original face order and nearby roof");
        assertEquals(2, BoardSurface.sampleHeight(candidates, 5 * scale, 0, Float.NaN),
              "The exact lower roof wins over a higher roof reached only through tolerance");
        assertEquals(9, BoardSurface.sampleHeight(BoardSurface.edgeCandidates(List.of(tolerant), a, b),
              5 * scale, 0, Float.NaN), "A roof just outside the edge remains available for tolerance recovery");
    }

    private static Vector3 point(float x, float y, float offset, float scale) {
        return new Vector3(offset + x * scale, -offset + y * scale,
              (.25f * x + .5f * y + .1f * (float) Math.sin(x * y)) * scale);
    }
}
