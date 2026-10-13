/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A scar's shader variant: a surface whose shader cannot take the scar helpers draws no scar instead of failing to
 * compile, which stopped the whole 3D board (a fuel-air round charring a water hex did it).
 */
class GpuSurfaceScarsShaderTest {
    private static final String MAIN = "void main() {\n    fragColor = vec4(1.0);\n}\n";

    @Test
    void aSculptedTerrainShaderTakesTheScar() {
        String source = GpuSurfaceScars.fragment(GpuSurfaceScars.SCULPT_HELPERS + "\n" + MAIN, true);
        assertTrue(source.contains("surfaceScarMask = surfaceScar();"));
    }

    /** A sculpted water surface drawn with a liquid shader has no place for the helpers: its scar is not drawn. */
    @Test
    void aShaderThatCannotTakeTheScarDrawsNoScarRatherThanFailing() {
        String liquid = "uniform float u_time;\n" + MAIN;
        for (boolean sculpt : new boolean[] { true, false }) {
            String source = GpuSurfaceScars.fragment(liquid, sculpt);
            assertFalse(source.contains("surfaceScarMask"), "It never uses the helpers it does not have");
            assertTrue(source.contains("void main() {\ndiscard;"), "and draws nothing");
        }
    }
}
