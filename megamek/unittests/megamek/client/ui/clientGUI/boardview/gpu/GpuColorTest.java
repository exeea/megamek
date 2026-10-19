/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import com.badlogic.gdx.graphics.Color;
import org.junit.jupiter.api.Test;

class GpuColorTest {
    @Test
    void exactSrgbIncludesTheDarkLinearSegmentAndPreservesHdrAndAlpha() {
        assertEquals(.0015479876, GpuColor.linear(.02f), 1e-9);
        assertEquals(.003130805, GpuColor.linear(.04045f), 1e-8);
        assertEquals(.21404114, GpuColor.linear(.5f), 1e-7);
        assertEquals(.73535698, GpuColor.display(.5f), 1e-7);
        for (float value : new float[] { 0, .02f, .04045f, .18f, .5f, 1, 4 }) {
            assertEquals(value, GpuColor.display(GpuColor.linear(value)), .000002f);
        }
        Color authored = new Color(.5f, .02f, 1, .25f);
        authored.b = 4;
        Color linear = GpuColor.linear(authored);
        assertNotSame(authored, linear);
        assertEquals(.25f, linear.a);
        assertEquals(.5f, authored.r, "Input palette colors remain reusable");
        assertEquals(4, GpuColor.display(linear.b), .000002f, "Extended values are not clamped to one");
    }
}
