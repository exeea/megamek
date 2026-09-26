/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuAnnotationPlacementTest {
    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

    @Test
    void rearUnitsUseTheBottomEdgeAndTheirActualLeftRightBearing() {
        var camera = camera();
        for (float depth : new float[] { -100, -.001f, 0 }) {
            for (float lateral : new float[] { -40, 0, 40 }) {
                var anchor = GpuBattleView.annotationAnchor(camera, camera.position.cpy().add(lateral, depth, 25), true);
                assertTrue(anchor.behind());
                assertEquals(0, anchor.y());
                assertTrue(Float.isFinite(anchor.x()));
                assertEquals(Math.signum(lateral), Math.signum(anchor.x() - 600));
            }
        }
        var ahead = camera.position.cpy().add(10, 100, 25);
        var anchor = GpuBattleView.annotationAnchor(camera, ahead, true);
        var projected = camera.project(ahead.cpy(), 0, 0, 1200, 800);
        assertFalse(anchor.behind());
        assertEquals(projected.x, anchor.x());
        assertEquals(projected.y, anchor.y());
    }

    @Test
    void rearLabelOverlapResolutionStaysOnTheBottomEdge() {
        Rectangle first = new Rectangle(500, 0, 120, 40);
        Rectangle second = GpuBattleView.spreadAnnotation(first, List.of(first), 1200, first.height, 2);
        assertEquals(0, second.y);
        assertFalse(first.overlaps(second));
    }

    private static BoardProjectionCamera camera() {
        BoardCamera board = new BoardCamera();
        board.resize(1200, 800);
        board.setFirstPerson(true);
        board.look(0, 90);
        return board.camera;
    }
}
