/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;

/** Exact sRGB input/output boundaries; matches color-space.glsl. Alpha and material data are never transformed. */
final class GpuColor {
    private GpuColor() { }

    static float linear(float display) {
        float c = Math.max(0, display);
        return c <= .04045f ? c / 12.92f : (float) Math.pow((c + .055f) / 1.055f, 2.4);
    }

    static float display(float linear) {
        float c = Math.max(0, linear);
        return c <= .0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4) - .055f;
    }

    static Color linear(Color display) {
        Color result = linear(display.r, display.g, display.b);
        result.a = display.a;
        return result;
    }

    static Color linear(float r, float g, float b) {
        Color result = new Color();
        result.r = linear(r);
        result.g = linear(g);
        result.b = linear(b);
        result.a = 1;
        return result;
    }
}
