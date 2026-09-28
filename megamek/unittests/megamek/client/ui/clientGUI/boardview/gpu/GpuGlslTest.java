/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GpuGlslTest {
    @Test
    void materialDefinesFollowTheRuntimeVersionWhenAnEditorPreservesCommentsBeforeTheVersion() {
        String source = "// edited shader\r\n#version 330 core\r\nin vec3 a_position;\nvoid main() {}\n";
        String compiled = GpuGlsl.source("#define colorFlag\n", source, 460);
        assertTrue(compiled.startsWith("#version 460 core\n#define colorFlag\n// edited shader"));
        assertEquals(1, compiled.split("#version", -1).length - 1);
        assertTrue(compiled.contains("in vec3 a_position;"));
        assertTrue(GpuGlsl.source("", "void main() {}", 330).startsWith("#version 330 core\nvoid main()"));
    }

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
