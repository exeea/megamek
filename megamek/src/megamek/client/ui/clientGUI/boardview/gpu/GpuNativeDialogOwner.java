/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;

import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.LongUnaryOperator;

import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import com.formdev.flatlaf.ui.FlatNativeWindowsLibrary;
import megamek.logging.MMLogger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWNativeX11;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.jawt.JAWT;
import org.lwjgl.system.jawt.JAWTDrawingSurface;
import org.lwjgl.system.jawt.JAWTDrawingSurfaceInfo;
import org.lwjgl.system.jawt.JAWTFunctions;
import org.lwjgl.system.jawt.JAWTX11DrawingSurfaceInfo;
import org.lwjgl.system.libffi.FFICIF;
import org.lwjgl.system.libffi.LibFFI;
import org.lwjgl.system.linux.X11;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.lwjgl.system.windows.User32;

/**
 * Makes Swing dialogs native owned windows of the GPU board window, the way a dialog belongs to its frame: it stays
 * above the board without being topmost over other applications, follows it when it is minimized and has no taskbar
 * button of its own. Each platform has its own relationship:
 * <ul>
 *     <li>Windows: the board window becomes the dialog's owner window. While a modal Swing dialog is open the board
 *     window is also disabled, so clicking it flashes the dialog as clicking a Swing frame behind its modal dialog
 *     does.</li>
 *     <li>macOS: the dialog becomes a child window of the board window, so AppKit orders and moves it with the
 *     board.</li>
 *     <li>Linux on X11 (including XWayland): the dialog is marked transient for the board window, which window
 *     managers keep above it. Stacking is the window manager's choice. On native Wayland GLFW has no X11 window,
 *     so dialogs float on top instead.</li>
 * </ul>
 *
 * <p>{@link #adopt}, {@link #hidden} and {@link #forget} run on the Swing thread, {@link #setOwnerEnabled} on the
 * GPU thread that owns the board window, and {@link #releaseAll} on the GPU thread before the board window is
 * destroyed: a dialog must not go down with its owner. On macOS and Linux the dialogs' native windows come from
 * JAWT, Java's supported native window interface, so no JVM flag opens AWT's internals.</p>
 */
abstract class GpuNativeDialogOwner {
    private static final MMLogger LOGGER = MMLogger.create(GpuNativeDialogOwner.class);

    /** A dialog's native window and the owner it had before, to return it to. */
    record Adoption(long handle, long previousOwner) {}

    private final long ownerHandle;
    /** Guarded by itself, as is {@link #released}. */
    private final Map<Dialog, Adoption> adopted = new IdentityHashMap<>();
    private boolean released;

    GpuNativeDialogOwner(long ownerHandle) {
        this.ownerHandle = ownerHandle;
    }

    /** The owner for a GLFW window, or null where native ownership is unavailable and dialogs must float on top. */
    static GpuNativeDialogOwner forGlfwWindow(long glfwWindow) {
        try {
            return switch (SharedLibraryLoader.os) {
                case Windows -> Win32.forGlfwWindow(glfwWindow);
                case MacOsX -> Cocoa.forGlfwWindow(glfwWindow);
                case Linux -> X11Owner.forGlfwWindow(glfwWindow);
                default -> null;
            };
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn(failure, "Swing dialogs float above the 3D window: its native handle is unavailable");
            return null;
        }
    }

    /** Windows owner for a native window handle; null where Windows ownership is unavailable. */
    static GpuNativeDialogOwner forWindows(long hwnd) {
        return Win32.supported() && hwnd != 0 ? new Win32(hwnd) : null;
    }

    /** Tests: an owner whose board is a showing Swing window standing in for the GLFW window, or null. */
    static GpuNativeDialogOwner forSwingWindow(Window board) {
        return switch (SharedLibraryLoader.os) {
            case Windows -> Win32.supported() ? forWindows(FlatNativeWindowsLibrary.getHWND(board)) : null;
            case MacOsX -> {
                long window = Jawt.withPlatformInfo(board, Cocoa::windowOfSurfaceLayers);
                yield window == 0 ? null : new Cocoa(window);
            }
            case Linux -> {
                long window = Jawt.withPlatformInfo(board, platformInfo -> {
                    JAWTX11DrawingSurfaceInfo info = JAWTX11DrawingSurfaceInfo.create(platformInfo);
                    return X11Owner.shellOf(info.display(), info.drawable());
                });
                yield window == 0 ? null : new X11Owner(window);
            }
            default -> null;
        };
    }

    long ownerHandle() {
        return ownerHandle;
    }

    /** Swing thread: owns a showing dialog by the board window. False when it could not, so it must float instead. */
    final boolean adopt(Dialog dialog) {
        if (!dialog.isDisplayable()) {
            return false;
        }
        synchronized (adopted) {
            if (released) {
                return false;
            }
            Adoption adoption = attach(dialog, adopted.get(dialog));
            if (adoption == null) {
                return false;
            }
            adopted.put(dialog, adoption);
            return true;
        }
    }

    /** Swing thread: a dialog was hidden. Platforms whose hidden owned windows misbehave let it go until shown. */
    final void hidden(Dialog dialog) {
        if (!releasesHiddenDialogs()) {
            return;
        }
        synchronized (adopted) {
            Adoption adoption = adopted.remove(dialog);
            if (adoption != null) {
                detach(adoption, false);
            }
        }
    }

    /** Swing thread: drops a disposed dialog, whose native window is already gone. */
    final void forget(Dialog dialog) {
        synchronized (adopted) {
            adopted.remove(dialog);
        }
    }

    final boolean owns(Dialog dialog) {
        synchronized (adopted) {
            return adopted.containsKey(dialog);
        }
    }

    /** The adopted dialog's native window, or 0. */
    final long adoptedHandle(Dialog dialog) {
        synchronized (adopted) {
            Adoption adoption = adopted.get(dialog);
            return adoption == null ? 0 : adoption.handle();
        }
    }

    /** Any thread, before the board window is destroyed: returns every dialog to the owner it had. */
    final void releaseAll() {
        synchronized (adopted) {
            released = true;
            for (Adoption adoption : adopted.values()) {
                detach(adoption, true);
            }
            adopted.clear();
        }
    }

    /**
     * Swing thread, under the adoption lock: makes the dialog's native window owned by the board window.
     *
     * @param previous the dialog's adoption before it was last hidden, or null
     *
     * @return the adoption to remember, or null when the dialog could not be owned
     */
    abstract Adoption attach(Dialog dialog, Adoption previous);

    /** Under the adoption lock: returns an adopted window to its previous owner; final before the owner goes. */
    abstract void detach(Adoption adoption, boolean ownerClosing);

    boolean releasesHiddenDialogs() {
        return false;
    }

    /** GPU thread: disables the board window while a modal dialog is open, where the platform supports it. */
    void setOwnerEnabled(boolean enabled) {
    }

    boolean ownerEnabled() {
        return true;
    }

    /** Windows: the board window is the native owner window ({@code GWLP_HWNDPARENT}) of each dialog. */
    static final class Win32 extends GpuNativeDialogOwner {
        /** The owner of a top-level window; despite the name it is not the parent. */
        static final int GWLP_HWNDPARENT = -8;
        static final int GWL_STYLE = -16;
        static final long WS_DISABLED = 0x08000000L;
        static final int GCLP_HICON = -14;
        static final int GCLP_HICONSM = -34;
        private static final long ICON_SMALL = 0;
        private static final long ICON_BIG = 1;
        private static volatile long enableWindow;
        private static boolean classIconsSet;

        private Win32(long hwnd) {
            super(hwnd);
        }

        static GpuNativeDialogOwner forGlfwWindow(long glfwWindow) {
            return supported() ? forWindows(GLFWNativeWin32.glfwGetWin32Window(glfwWindow)) : null;
        }

        static boolean supported() {
            try {
                return SharedLibraryLoader.os == Os.Windows && FlatNativeWindowsLibrary.isLoaded();
            } catch (LinkageError unavailable) {
                return false;
            }
        }

        static long handle(Dialog dialog) {
            return dialog.isDisplayable() ? FlatNativeWindowsLibrary.getHWND(dialog) : 0;
        }

        @Override
        Adoption attach(Dialog dialog, Adoption previous) {
            long handle = handle(dialog);
            if (handle == 0) {
                return null;
            }
            long current = User32.GetWindowLongPtr(handle, GWLP_HWNDPARENT);
            if (current != ownerHandle()) {
                User32.SetWindowLongPtr(handle, GWLP_HWNDPARENT, ownerHandle());
                if (User32.GetWindowLongPtr(handle, GWLP_HWNDPARENT) != ownerHandle()) {
                    return null;
                }
            }
            // A dialog shown again keeps the owner it had before its first adoption.
            return previous != null && previous.handle() == handle ? previous : new Adoption(handle, current);
        }

        @Override
        void detach(Adoption adoption, boolean ownerClosing) {
            if (User32.GetWindowLongPtr(adoption.handle(), GWLP_HWNDPARENT) == ownerHandle()) {
                User32.SetWindowLongPtr(adoption.handle(), GWLP_HWNDPARENT, adoption.previousOwner());
            }
        }

        /** GPU thread (the board window's own thread, so Windows sends it no message across threads). */
        @Override
        void setOwnerEnabled(boolean enabled) {
            long function = enableWindow;
            if (function == 0) {
                function = User32.getLibrary().getFunctionAddress("EnableWindow");
                enableWindow = function;
            }
            if (function != 0) {
                JNI.callPI(ownerHandle(), enabled ? 1 : 0, function);
            }
        }

        @Override
        boolean ownerEnabled() {
            return (User32.GetWindowLongPtr(ownerHandle(), GWL_STYLE) & WS_DISABLED) == 0;
        }

        /**
         * GPU thread: the taskbar may show a window's class icon rather than its own, and GLFW's class has Windows'
         * generic one, so the class gets copies of the window's icons. The copies outlive the window, whose own
         * icons GLFW destroys with it; the class is the process's, so one set serves every board window.
         */
        static synchronized void useWindowIconsForClass(long glfwWindow) {
            if (SharedLibraryLoader.os != Os.Windows || classIconsSet) {
                return;
            }
            try {
                long hwnd = GLFWNativeWin32.glfwGetWin32Window(glfwWindow);
                var library = User32.getLibrary();
                long sendMessage = library.getFunctionAddress("SendMessageW");
                long copyIcon = library.getFunctionAddress("CopyIcon");
                if (hwnd == 0 || sendMessage == 0 || copyIcon == 0) {
                    return;
                }
                long big = JNI.callPPPP(hwnd, User32.WM_GETICON, ICON_BIG, 0L, sendMessage);
                long small = JNI.callPPPP(hwnd, User32.WM_GETICON, ICON_SMALL, 0L, sendMessage);
                if (big == 0 || small == 0) {
                    return;
                }
                User32.SetClassLongPtr(hwnd, GCLP_HICON, JNI.callPP(big, copyIcon));
                User32.SetClassLongPtr(hwnd, GCLP_HICONSM, JNI.callPP(small, copyIcon));
                classIconsSet = true;
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.warn(failure, "The 3D window's taskbar button keeps the generic icon");
            }
        }
    }

    /**
     * macOS: each dialog's NSWindow is a child window of the board's NSWindow. AppKit wants windows changed on its
     * main thread, so the changes are sent there; the Swing and GPU threads only read.
     */
    static final class Cocoa extends GpuNativeDialogOwner {
        private static final long NS_WINDOW_ABOVE = 1;
        /** How long closing the board waits for AppKit to let its child windows go. */
        private static final long RELEASE_WAIT_MS = 500;

        private Cocoa(long nsWindow) {
            super(nsWindow);
        }

        static GpuNativeDialogOwner forGlfwWindow(long glfwWindow) {
            long nsWindow = GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
            return nsWindow == 0 ? null : new Cocoa(nsWindow);
        }

        @Override
        Adoption attach(Dialog dialog, Adoption previous) {
            long window = Jawt.withPlatformInfo(dialog, Cocoa::windowOfSurfaceLayers);
            if (window == 0) {
                return null;
            }
            long parent = ObjC.send(window, "parentWindow");
            if (parent != ownerHandle()) {
                ObjC.onMainThread(ownerHandle(), "addChildWindow:ordered:", window, NS_WINDOW_ABOVE);
            }
            return previous != null && previous.handle() == window ? previous : new Adoption(window, parent);
        }

        /** A hidden child window may be ordered back in with its parent, so it stops being a child until shown. */
        @Override
        boolean releasesHiddenDialogs() {
            return true;
        }

        @Override
        void detach(Adoption adoption, boolean ownerClosing) {
            // AWT keeps its own parent windows; giving one back could order the dialog out with a hidden frame.
            long window = adoption.handle();
            ObjC.onMainThread(ownerHandle(), "removeChildWindow:", window);
            if (ownerClosing) {
                // Not waitUntilDone: the AppKit thread may itself be waiting on Swing, which may wait on this one.
                long deadline = System.currentTimeMillis() + RELEASE_WAIT_MS;
                while (ObjC.send(window, "parentWindow") == ownerHandle()
                      && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        /** JAWT's macOS platform info holds the window's root layer; the NSWindow is the one whose view has it. */
        private static long windowOfSurfaceLayers(long surfaceLayers) {
            long layer = ObjC.send(surfaceLayers, "windowLayer");
            if (layer == 0) {
                return 0;
            }
            long view = ObjC.send(layer, "delegate");
            if (view != 0 && ObjC.respondsTo(view, "window")) {
                long window = ObjC.send(view, "window");
                if (window != 0 && hasLayer(window, layer)) {
                    return window;
                }
            }
            long windows = ObjC.send(ObjC.send(ObjC.cls("NSApplication"), "sharedApplication"), "windows");
            long count = windows == 0 ? 0 : ObjC.send(windows, "count");
            for (long index = 0; index < count; index++) {
                long window = ObjC.send(windows, "objectAtIndex:", index);
                if (hasLayer(window, layer)) {
                    return window;
                }
            }
            return 0;
        }

        private static boolean hasLayer(long window, long layer) {
            long content = ObjC.send(window, "contentView");
            return content != 0 && ObjC.send(content, "layer") == layer;
        }
    }

    /**
     * X11: each dialog is transient for the board window (ICCCM {@code WM_TRANSIENT_FOR}). AWT may set the hint
     * again for its own owner when a dialog shows, so a dialog is adopted again every time it is shown. Releasing
     * does nothing: a window manager ignores a transient hint that names a destroyed window.
     */
    static final class X11Owner extends GpuNativeDialogOwner {
        private X11Owner(long window) {
            super(window);
        }

        static GpuNativeDialogOwner forGlfwWindow(long glfwWindow) {
            if (GLFW.glfwGetPlatform() != GLFW.GLFW_PLATFORM_X11) {
                return null;
            }
            long window = GLFWNativeX11.glfwGetX11Window(glfwWindow);
            return window == 0 ? null : new X11Owner(window);
        }

        @Override
        Adoption attach(Dialog dialog, Adoption previous) {
            long owner = ownerHandle();
            long window = Jawt.withPlatformInfo(dialog, platformInfo -> {
                // The surface lock holds AWT's lock, so AWT's display connection is safe to use here.
                JAWTX11DrawingSurfaceInfo info = JAWTX11DrawingSurfaceInfo.create(platformInfo);
                long display = info.display();
                long shell = shellOf(display, info.drawable());
                if (shell == 0) {
                    return 0;
                }
                SharedLibraryFunctions functions = SharedLibraryFunctions.get();
                JNI.callPJJI(display, shell, owner, functions.setTransientForHint);
                JNI.callPI(display, functions.flush);
                return shell;
            });
            return window == 0 ? null : new Adoption(window, 0);
        }

        @Override
        void detach(Adoption adoption, boolean ownerClosing) {
        }

        /** JAWT gives a dialog's content window; the top-level window the window manager sees is its parent. */
        private static long shellOf(long display, long content) {
            try (MemoryStack stack = stackPush()) {
                FFICIF cif = FFICIF.malloc(stack);
                PointerBuffer types = stack.pointers(LibFFI.ffi_type_pointer, LibFFI.ffi_type_uint64,
                      LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer, LibFFI.ffi_type_pointer,
                      LibFFI.ffi_type_pointer);
                if (LibFFI.ffi_prep_cif(cif, LibFFI.FFI_DEFAULT_ABI, LibFFI.ffi_type_sint32, types) != LibFFI.FFI_OK) {
                    return 0;
                }
                LongBuffer root = stack.callocLong(1);
                LongBuffer parent = stack.callocLong(1);
                PointerBuffer children = stack.callocPointer(1);
                IntBuffer childCount = stack.callocInt(1);
                // libffi takes the address of each argument's value.
                PointerBuffer values = stack.pointers(
                      MemoryUtil.memAddress(stack.pointers(display)),
                      MemoryUtil.memAddress(stack.longs(content)),
                      MemoryUtil.memAddress(stack.pointers(MemoryUtil.memAddress(root))),
                      MemoryUtil.memAddress(stack.pointers(MemoryUtil.memAddress(parent))),
                      MemoryUtil.memAddress(stack.pointers(MemoryUtil.memAddress(children))),
                      MemoryUtil.memAddress(stack.pointers(MemoryUtil.memAddress(childCount))));
                ByteBuffer status = stack.calloc(8);
                LibFFI.ffi_call(cif, SharedLibraryFunctions.get().queryTree, status, values);
                if (children.get(0) != 0) {
                    X11.nXFree(children.get(0));
                }
                if (status.getInt(0) == 0) {
                    return 0;
                }
                return parent.get(0) == 0 || parent.get(0) == root.get(0) ? content : parent.get(0);
            }
        }

        /** The Xlib functions LWJGL has no binding for. */
        private record SharedLibraryFunctions(long queryTree, long setTransientForHint, long flush) {
            private static volatile SharedLibraryFunctions functions;

            static SharedLibraryFunctions get() {
                SharedLibraryFunctions current = functions;
                if (current == null) {
                    var library = X11.getLibrary();
                    current = new SharedLibraryFunctions(required(library, "XQueryTree"),
                          required(library, "XSetTransientForHint"), required(library, "XFlush"));
                    functions = current;
                }
                return current;
            }

            private static long required(org.lwjgl.system.SharedLibrary library, String name) {
                long address = library.getFunctionAddress(name);
                if (address == 0) {
                    throw new IllegalStateException(name + " is missing from " + library.getName());
                }
                return address;
            }
        }
    }

    /** JAWT: a showing dialog's native window, read while its drawing surface (and so AWT's lock) is held. */
    static final class Jawt {
        private Jawt() {
        }

        /** Applies the function to the component's JAWT platform info, or returns 0 when JAWT cannot provide it. */
        static long withPlatformInfo(Component component, LongUnaryOperator function) {
            try (MemoryStack stack = stackPush()) {
                JAWT awt = JAWT.calloc(stack).version(JAWTFunctions.JAWT_VERSION_9);
                if (!JAWTFunctions.JAWT_GetAWT(awt)) {
                    return 0;
                }
                JAWTDrawingSurface surface = JAWTFunctions.JAWT_GetDrawingSurface(component, awt.GetDrawingSurface());
                if (surface == null) {
                    return 0;
                }
                try {
                    int lock = JAWTFunctions.JAWT_DrawingSurface_Lock(surface, surface.Lock());
                    if ((lock & JAWTFunctions.JAWT_LOCK_ERROR) != 0) {
                        return 0;
                    }
                    try {
                        JAWTDrawingSurfaceInfo info = JAWTFunctions.JAWT_DrawingSurface_GetDrawingSurfaceInfo(surface,
                              surface.GetDrawingSurfaceInfo());
                        if (info == null) {
                            return 0;
                        }
                        try {
                            return info.platformInfo() == 0 ? 0 : function.applyAsLong(info.platformInfo());
                        } finally {
                            JAWTFunctions.JAWT_DrawingSurface_FreeDrawingSurfaceInfo(info,
                                  surface.FreeDrawingSurfaceInfo());
                        }
                    } finally {
                        JAWTFunctions.JAWT_DrawingSurface_Unlock(surface, surface.Unlock());
                    }
                } finally {
                    JAWTFunctions.JAWT_FreeDrawingSurface(surface, awt.FreeDrawingSurface());
                }
            }
        }
    }

    /** The few Objective-C messages the macOS owner sends, through LWJGL's runtime binding. */
    static final class ObjC {
        private static volatile long msgSend;

        private ObjC() {
        }

        static long cls(String name) {
            return ObjCRuntime.objc_getClass(name);
        }

        static long send(long receiver, String selector) {
            return receiver == 0 ? 0 : JNI.callPPP(receiver, ObjCRuntime.sel_getUid(selector), msgSend());
        }

        static long send(long receiver, String selector, long argument) {
            return receiver == 0 ? 0
                  : JNI.callPPPP(receiver, ObjCRuntime.sel_getUid(selector), argument, msgSend());
        }

        static boolean respondsTo(long receiver, String selector) {
            return JNI.callPPPZ(receiver, ObjCRuntime.sel_getUid("respondsToSelector:"),
                  ObjCRuntime.sel_getUid(selector), msgSend());
        }

        /**
         * Sends a message with pointer-sized arguments on AppKit's main thread without waiting for it: an
         * NSInvocation performed there. It retains its target and object arguments until it runs.
         */
        static void onMainThread(long receiver, String selectorName, long... arguments) {
            long function = msgSend();
            long pool = JNI.callP(ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPush"));
            try (MemoryStack stack = stackPush()) {
                long selector = ObjCRuntime.sel_getUid(selectorName);
                long signature = JNI.callPPPP(receiver, ObjCRuntime.sel_getUid("methodSignatureForSelector:"),
                      selector, function);
                long invocation = JNI.callPPPP(cls("NSInvocation"),
                      ObjCRuntime.sel_getUid("invocationWithMethodSignature:"), signature, function);
                JNI.callPPPV(invocation, ObjCRuntime.sel_getUid("setTarget:"), receiver, function);
                JNI.callPPPV(invocation, ObjCRuntime.sel_getUid("setSelector:"), selector, function);
                long setArgument = ObjCRuntime.sel_getUid("setArgument:atIndex:");
                for (int index = 0; index < arguments.length; index++) {
                    // Indexes 0 and 1 are the target and the selector.
                    JNI.callPPPPV(invocation, setArgument, MemoryUtil.memAddress(stack.longs(arguments[index])),
                          index + 2L, function);
                }
                JNI.callPPV(invocation, ObjCRuntime.sel_getUid("retainArguments"), function);
                JNI.callPPPPV(invocation, ObjCRuntime.sel_getUid("performSelectorOnMainThread:withObject:waitUntilDone:"),
                      ObjCRuntime.sel_getUid("invoke"), 0L, 0, function);
            } finally {
                JNI.callPV(pool, ObjCRuntime.getLibrary().getFunctionAddress("objc_autoreleasePoolPop"));
            }
        }

        private static long msgSend() {
            long function = msgSend;
            if (function == 0) {
                function = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
                msgSend = function;
            }
            return function;
        }
    }
}
