/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Window;

/**
 * The one native window MegaMek runs at a time. GLFW is process-wide and libGDX terminates it when an application
 * exits, so a native window that exits beside another takes GLFW away from it. The board window, the start window and
 * the UI library all claim the native window here before they start.
 */
final class GpuNativeWindow {

    private GpuNativeWindow() {
    }

    /**
     * EDT: returns true when no native window is open. Otherwise closes the one that can close (a map preview, the UI
     * library or the start screen), runs {@code then} on the EDT once it is gone and returns false; beside a board or
     * an editor it tells the player and returns false without running {@code then}.
     */
    static boolean claim(Window owner, Runnable then) {
        if (!GpuBoardWindow.yieldNativeWindow(owner, then)) {
            return false;
        }
        if (GpuUiLibraryWindow.closeThen(then)) {
            return false;
        }
        return !GpuStartWindow.closeThen(then);
    }
}
