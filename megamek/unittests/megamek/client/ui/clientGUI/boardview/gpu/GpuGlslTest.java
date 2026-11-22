/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void commentsGoBeforeTheKeyAndTheCompileWhileEveryLineKeepsItsNumber() {
        String source = "// header\r\nfloat a; // tail\nfloat/* inline */b;\n/* two\nlines */float c;\n";
        assertEquals("\r\nfloat a; \nfloat b;\n\nfloat c;\n", GpuGlsl.withoutComments(source));
        assertEquals("float d = 4.0 / 2.0;", GpuGlsl.withoutComments("float d = 4.0 / 2.0;"), "A division stays");
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

    @Test
    void integratedGpusAreToldFromTheirVendorAndRenderer() {
        assertTrue(GpuGlsl.integrated("Intel", "Intel(R) Iris(R) Xe Graphics"), "Intel's integrated graphics");
        assertTrue(GpuGlsl.integrated("Intel", "Intel(R) Arc(TM) Graphics"), "Meteor Lake's integrated Arc");
        assertFalse(GpuGlsl.integrated("Intel", "Intel(R) Arc(TM) A770 Graphics"), "a discrete Arc A-series");
        assertFalse(GpuGlsl.integrated("Intel", "Mesa Intel(R) Arc(tm) B580 Graphics (BMG G21)"), "a discrete Arc B-series");
        assertTrue(GpuGlsl.integrated("Apple", "Apple M2 Pro"), "Apple Silicon");
        assertTrue(GpuGlsl.integrated("ATI Technologies Inc.", "AMD Radeon(TM) Graphics"), "an AMD APU");
        assertTrue(GpuGlsl.integrated("ATI Technologies Inc.", "AMD Radeon 780M Graphics"), "an AMD APU by model");
        assertTrue(GpuGlsl.integrated("AMD", "AMD Radeon Graphics (radeonsi, rembrandt, LLVM 17.0.6, DRM 3.54)"),
              "an AMD APU on Mesa");
        assertTrue(GpuGlsl.integrated("AMD", "AMD Radeon 780M Graphics (radeonsi, phoenix, LLVM 17.0.6, DRM 3.57)"),
              "an AMD APU by model on Mesa");
        assertFalse(GpuGlsl.integrated("AMD", "AMD Custom GPU 0405 (radeonsi, vangogh, LLVM 17.0.6, DRM 3.54)"),
              "the Steam Deck: off, the user's choice");
        assertFalse(GpuGlsl.integrated("ATI Technologies Inc.", "AMD Custom GPU 0932"), "the Steam Deck OLED: off");
        assertFalse(GpuGlsl.integrated("ATI Technologies Inc.", "AMD Radeon RX 7900 XTX"), "a discrete Radeon");
        assertFalse(GpuGlsl.integrated("ATI Technologies Inc.", "Radeon RX Vega 64"), "a discrete Vega");
        assertFalse(GpuGlsl.integrated("ATI Technologies Inc.", "AMD Radeon Pro 5500M OpenGL Engine"), "a Mac's Radeon Pro");
        assertFalse(GpuGlsl.integrated("NVIDIA Corporation", "NVIDIA GeForce RTX 4070 Laptop GPU/PCIe/SSE2"), "NVIDIA");
        assertFalse(GpuGlsl.integrated("Mesa", "llvmpipe (LLVM 17.0.6, 256 bits)"), "the software renderer");
        assertFalse(GpuGlsl.integrated(null, null), "nothing known");
    }
}
