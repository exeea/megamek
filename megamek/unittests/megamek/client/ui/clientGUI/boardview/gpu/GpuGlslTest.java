/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GpuGlslTest {
    @Test
    void shadersUseTheContextsOwnVersionBetweenTheBoardsLimits() {
        assertEquals(330, GpuGlsl.select(3, 3, GpuGlsl.MAXIMUM), "OpenGL 3.3, the least the board asks for");
        assertEquals(410, GpuGlsl.select(4, 1, GpuGlsl.MAXIMUM), "OpenGL 4.1, every Mac");
        assertEquals(450, GpuGlsl.select(4, 5, GpuGlsl.MAXIMUM), "OpenGL 4.5, the software renderer of the smoke tests");
        assertEquals(460, GpuGlsl.select(4, 6, GpuGlsl.MAXIMUM), "OpenGL 4.6, current Windows and Linux drivers");
        assertEquals(460, GpuGlsl.select(5, 0, GpuGlsl.MAXIMUM), "A newer context still compiles GLSL 4.60");
        assertEquals(330, GpuGlsl.select(4, 6, 330), "The troubleshooting cap");
        assertEquals(330, GpuGlsl.select(4, 6, 100), "A cap below the minimum");
    }
}
