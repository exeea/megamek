/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import org.junit.jupiter.api.Test;

class GpuBoardLabelsTest {
    @Test
    void clippedLinesKeepTheirDirectionAndClearBothBodiesAtEveryAngle() {
        Rectangle source = new Rectangle(-20, -50, 40, 100);
        for (int angle = 0; angle < 360; angle += 15) {
            Vector2 centre = new Vector2(400, 0).rotateDeg(angle);
            Rectangle target = new Rectangle(centre.x - 70, centre.y - 15, 140, 30);
            for (int offset : new int[] { -3, 0, 3 }) {
                Vector2 aside = centre.cpy().nor().rotate90(1).scl(offset);
                Vector2 from = aside.cpy();
                Vector2 to = centre.cpy().add(aside);
                assertTrue(GpuBoardLabels.clip(from, to, source, target));
                assertFalse(Intersector.intersectSegmentRectangle(from, to, source));
                assertFalse(Intersector.intersectSegmentRectangle(from, to, target));
                assertEquals(0, to.cpy().sub(from).nor().crs(centre.cpy().nor()), .0001f);
                assertTrue(to.cpy().sub(from).dot(centre) > 0, "Trimming must not reverse a short line");

                Vector2 reverseFrom = centre.cpy().add(aside);
                Vector2 reverseTo = aside.cpy();
                assertTrue(GpuBoardLabels.clip(reverseFrom, reverseTo, target, source));
                assertEquals(0, reverseFrom.dst(to), .001f);
                assertEquals(0, reverseTo.dst(from), .001f);
            }
        }
    }

    @Test
    void overlappingOrCoincidentUnitsLeaveNoLine() {
        Rectangle source = new Rectangle(0, 0, 100, 100);
        for (float x : new float[] { 0, 80, 100, 105 }) {
            Rectangle target = new Rectangle(x, 0, 100, 100);
            assertFalse(GpuBoardLabels.clip(source.getCenter(new Vector2()), target.getCenter(new Vector2()),
                  source, target));
        }
    }

    @Test
    void hexTargetsKeepTheirEndpointAndInputRectanglesAreUnchanged() {
        Rectangle source = new Rectangle(0, 0, 40, 100);
        Vector2 from = source.getCenter(new Vector2());
        Vector2 to = new Vector2(400, 200);
        assertTrue(GpuBoardLabels.clip(from, to, source, null));
        assertFalse(Intersector.intersectSegmentRectangle(from, to, source));
        assertEquals(new Vector2(400, 200), to);
        assertEquals(new Rectangle(0, 0, 40, 100), source);
    }
}
