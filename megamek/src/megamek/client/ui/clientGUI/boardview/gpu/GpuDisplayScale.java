/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.FloatBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.utils.BufferUtils;
import org.lwjgl.glfw.GLFW;

/** The desktop window's monitor content scale: the native UI's one GLFW query, which DisplayScale takes in. */
final class GpuDisplayScale {
    private final FloatBuffer scaleX = BufferUtils.newFloatBuffer(1);
    private final FloatBuffer scaleY = BufferUtils.newFloatBuffer(1);

    /** The larger of the window's horizontal and vertical content scales, on the GL thread. */
    float contentScale() {
        var graphics = (Lwjgl3Graphics) Gdx.graphics;
        GLFW.glfwGetWindowContentScale(graphics.getWindow().getWindowHandle(), scaleX, scaleY);
        return Math.max(scaleX.get(0), scaleY.get(0));
    }
}
