/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.gdx;

import com.badlogic.gdx.Gdx;

/**
 * The one logical-pixel scale of every native window: stage units per logical pixel, so that a unit is one CSS pixel of
 * the hud-v3 mock at the same physical size on every monitor. Backend-neutral: the platform's window code reads the
 * monitor's content scale and passes it in.
 */
public final class DisplayScale {
    private DisplayScale() {
    }

    /**
     * The current window's scale, on its GL thread: {@code preference} is the user's GUI scale, captured on the EDT,
     * and {@code contentScale} the monitor's content scale that the window code reads.
     */
    public static float read(float preference, float contentScale) {
        float pixelRatio = Gdx.graphics.getBackBufferWidth() / (float) Math.max(1, Gdx.graphics.getWidth());
        return calculate(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), contentScale, pixelRatio, preference);
    }

    /**
     * The scale for a window of {@code width} x {@code height} logical pixels: the monitor's DPI or the window's
     * resolution, whichever asks for more, times the preference, but never so large that 960 x 640 units do not fit.
     */
    public static float calculate(int width, int height, float dpiScale, float pixelRatio, float preference) {
        // Retina already scales logical pixels; Windows commonly reports framebuffer-sized window coordinates.
        float dpi = Math.max(1, dpiScale) / Math.max(1, pixelRatio);
        float resolution = Math.min(width / 1600f, height / 1000f);
        float desired = Math.max(dpi, resolution) * preference;
        float available = Math.min(width / 960f, height / 640f);
        return Math.max(0.01f, Math.min(desired, available));
    }
}
