/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.GdxNativesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GpuAnnotationPlacementTest {
    @BeforeAll
    static void loadMathNatives() { GdxNativesLoader.load(); }

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
