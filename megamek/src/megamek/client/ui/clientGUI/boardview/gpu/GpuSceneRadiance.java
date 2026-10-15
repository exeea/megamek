/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

/** Fixed linear storage units for half-float scene color; material and lighting calculations remain canonical. */
final class GpuSceneRadiance {
    /** Power-of-two scaling preserves half-float relative precision while reserving bright-reflection headroom. */
    static final float SCALE = 1 / 32f;

    private GpuSceneRadiance() { }

    static String functions() {
        return GpuShaderSource.read("scene-radiance.glsl");
    }

    /** Apply once to the completed fragment program, including all early returns; discard still kills the fragment. */
    static String fragment(String source) {
        return GpuShaderSource.replaceOnce(source, "void main()", functions() + "\nvoid shadeScene()", "scene fragment")
              + "\nvoid main() { shadeScene(); fragColor.rgb = storeScene(fragColor.rgb); }\n";
    }
}
