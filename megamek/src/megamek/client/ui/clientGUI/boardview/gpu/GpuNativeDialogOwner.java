/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Dialog;
import java.util.IdentityHashMap;
import java.util.Map;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import megamek.logging.MMLogger;
import org.lwjgl.system.JNI;
import org.lwjgl.system.windows.User32;

/**
 * Windows only: makes Swing dialogs native owned windows of the GPU board window, the way a dialog belongs to its
 * frame. An owned window always stays above its owner without being topmost over other applications, hides when the
 * owner is minimized and has no taskbar button of its own. While a modal Swing dialog is open, the board window is
 * disabled, so clicking it flashes the dialog as clicking a Swing frame behind its modal dialog does.
 *
 * <p>{@link #adopt} and {@link #forget} run on the Swing thread, {@link #setOwnerEnabled} on the GPU thread that
 * owns the board window, and {@link #releaseAll} on the GPU thread before the board window is destroyed: Windows
 * destroys owned windows with their owner, which would take the dialogs' native windows with it.</p>
 */
final class GpuNativeDialogOwner {
    private static final MMLogger LOGGER = MMLogger.create(GpuNativeDialogOwner.class);
    /** The owner of a top-level window; despite the name it is not the parent. */
    static final int GWLP_HWNDPARENT = -8;
    static final int GWL_STYLE = -16;
    static final long WS_DISABLED = 0x08000000L;

    private record Adoption(long handle, long previousOwner) {}

    private final long ownerHandle;
    /** Guarded by itself, as is {@link #released}. */
    private final Map<Dialog, Adoption> adopted = new IdentityHashMap<>();
    private boolean released;
    private static volatile long enableWindow;

    GpuNativeDialogOwner(long ownerHandle) {
        this.ownerHandle = ownerHandle;
    }

    /** The owner for a GLFW window, or null where native ownership is unavailable and dialogs must float on top. */
    static GpuNativeDialogOwner forGlfwWindow(long glfwWindow) {
        if (!supported()) {
            return null;
        }
        try {
            long handle = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
            return handle == 0 ? null : new GpuNativeDialogOwner(handle);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(failure, "Swing dialogs float above the 3D window: its native handle is unavailable");
            return null;
        }
    }

    static boolean supported() {
        try {
            return SharedLibraryLoader.os == Os.Windows && FlatNativeWindowsLibrary.isLoaded();
        } catch (LinkageError unavailable) {
            return false;
        }
    }

    long ownerHandle() {
        return ownerHandle;
    }

    /** Swing thread: owns a showing dialog by the board window. False when it could not, so it must float instead. */
    boolean adopt(Dialog dialog) {
        long handle = handle(dialog);
        if (handle == 0) {
            return false;
        }
        synchronized (adopted) {
            if (released) {
                return false;
            }
            long current = User32.GetWindowLongPtr(handle, GWLP_HWNDPARENT);
            if (current == ownerHandle) {
                return true;
            }
            User32.SetWindowLongPtr(handle, GWLP_HWNDPARENT, ownerHandle);
            if (User32.GetWindowLongPtr(handle, GWLP_HWNDPARENT) != ownerHandle) {
                return false;
            }
            Adoption previous = adopted.get(dialog);
            // A dialog shown again keeps the owner it had before its first adoption.
            adopted.put(dialog, previous != null && previous.handle() == handle ? previous
                  : new Adoption(handle, current));
        }
        return true;
    }

    /** Swing thread: drops a disposed dialog, whose native window is already gone. */
    void forget(Dialog dialog) {
        synchronized (adopted) {
            adopted.remove(dialog);
        }
    }

    boolean owns(Dialog dialog) {
        synchronized (adopted) {
            return adopted.containsKey(dialog);
        }
    }

    /** Any thread, before the board window is destroyed: returns every dialog to the owner it had. */
    void releaseAll() {
        synchronized (adopted) {
            released = true;
            for (Adoption adoption : adopted.values()) {
                if (User32.GetWindowLongPtr(adoption.handle(), GWLP_HWNDPARENT) == ownerHandle) {
                    User32.SetWindowLongPtr(adoption.handle(), GWLP_HWNDPARENT,
                          adoption.previousOwner());
                }
            }
            adopted.clear();
        }
    }

    /** GPU thread (the board window's own thread, so Windows sends it no message across threads). */
    void setOwnerEnabled(boolean enabled) {
        long function = enableWindow;
        if (function == 0) {
            function = User32.getLibrary().getFunctionAddress("EnableWindow");
            enableWindow = function;
        }
        if (function != 0) {
            JNI.callPI(ownerHandle, enabled ? 1 : 0, function);
        }
    }

    boolean ownerEnabled() {
        return (User32.GetWindowLongPtr(ownerHandle, GWL_STYLE) & WS_DISABLED) == 0;
    }

    static long handle(Dialog dialog) {
        return dialog.isDisplayable() ? FlatNativeWindowsLibrary.getHWND(dialog) : 0;
    }
}
