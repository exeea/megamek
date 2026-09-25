/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Rectangle;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import megamek.client.ui.clientGUI.GUIPreferences;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

/**
 * Where the GPU board window sits, in GLFW screen coordinates. The size and position are the window's normal,
 * un-maximized ones, kept even while it is maximized so a restore returns to them.
 *
 * @param x         left edge, or {@code -1} with {@code y} to centre the window
 * @param y         top edge, or {@code -1} with {@code x} to centre the window
 * @param width     normal width
 * @param height    normal height
 * @param maximized {@code true} when the window was maximized
 */
record GpuWindowBounds(int x, int y, int width, int height, boolean maximized) {
    /** The smallest window the board lays out in; matches the window's size limits. */
    static final int MINIMUM_WIDTH = 900;
    static final int MINIMUM_HEIGHT = 600;

    GpuWindowBounds {
        width = Math.max(MINIMUM_WIDTH, width);
        height = Math.max(MINIMUM_HEIGHT, height);
    }

    /** @return {@code true} when no position was saved, so the window is centred */
    boolean centred() {
        return x == -1 && y == -1;
    }

    /** Read on the Swing thread before the GPU thread starts. */
    static GpuWindowBounds load(GUIPreferences preferences) {
        return new GpuWindowBounds(preferences.getGpuBoardPosX(), preferences.getGpuBoardPosY(),
              preferences.getGpuBoardSizeWidth(), preferences.getGpuBoardSizeHeight(),
              preferences.getGpuBoardMaximized());
    }

    /** Written on the Swing thread; the preference store is not safe to write from the GPU thread. */
    void save(GUIPreferences preferences) {
        preferences.setGpuBoardPosX(x);
        preferences.setGpuBoardPosY(y);
        preferences.setGpuBoardSizeWidth(width);
        preferences.setGpuBoardSizeHeight(height);
        preferences.setGpuBoardMaximized(maximized);
    }

    Rectangle rectangle() {
        return new Rectangle(x, y, width, height);
    }

    /**
     * Every monitor's work area, the taskbar and docks excluded, in GLFW screen coordinates. Call on the GPU thread.
     */
    static List<Rectangle> workAreas() {
        List<Rectangle> areas = new ArrayList<>();
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            return areas;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            for (int index = 0; index < monitors.limit(); index++) {
                GLFW.glfwGetMonitorWorkarea(monitors.get(index), x, y, width, height);
                if (width.get(0) > 0 && height.get(0) > 0) {
                    areas.add(new Rectangle(x.get(0), y.get(0), width.get(0), height.get(0)));
                }
            }
        }
        return areas;
    }
}
