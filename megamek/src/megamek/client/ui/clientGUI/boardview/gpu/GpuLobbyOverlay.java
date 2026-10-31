/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import megamek.client.ui.util.UIUtil;

/**
 * Shows the game lobby, a Swing frame, inset over the dimmed start screen in MegaMek's one native window
 * ({@link GpuHost}), so hosting or joining a game never opens a second window. The frame has no decorations of its
 * own: it moves and sizes with the native window, covering it entirely when the window is too small to leave a
 * margin. It is a native owned window of the native window, as the start screen's forms are, so it stays above it,
 * follows it when minimized and has no taskbar button of its own.
 *
 * <p>The lobby is in front only while the start screen is the scene; a board, the map editor, a map preview or the UI
 * library takes the whole window, and the lobby steps back behind it until the start screen returns. Hiding the frame,
 * as a game's board does once it shows, or disposing it, as leaving the game does, ends the overlay.</p>
 *
 * <p>Only MegaMek's own window keeps the lobby this way ({@link GpuHost#keepsOpen}); a lobby MekHQ opens is a normal
 * frame.</p>
 */
public final class GpuLobbyOverlay {
    /** The margin around the lobby in a large enough window, before GUI scaling. */
    private static final int MARGIN = 40;
    /** The least lobby size, before GUI scaling, for which the window keeps a margin around it. */
    private static final int INSET_MIN_WIDTH = 1100;
    private static final int INSET_MIN_HEIGHT = 720;

    /** The lobby frame, written on the EDT and read on the GPU thread for the window's close box. */
    private static volatile JFrame lobby;
    private static volatile Runnable exit;
    /** EDT: whether the lobby is in front of the start screen, and the owner that adopted it then. */
    private static boolean inFront;
    private static GpuNativeDialogOwner adoptedBy;

    private GpuLobbyOverlay() {
    }

    /**
     * EDT, before the frame first shows: makes {@code frame} the game lobby that shows over the start screen, when
     * MegaMek's own window is open; otherwise leaves it a normal frame. {@code exitAction} is the lobby's own exit,
     * which the window's close box asks for while the lobby shows. Returns whether the frame became the overlay.
     */
    public static boolean adopt(JFrame frame, Runnable exitAction) {
        if (!GpuHost.keepsOpen() || !GpuHost.isOpen() || frame.isDisplayable()) {
            return false;
        }
        if (lobby != null && lobby != frame) {
            release();
        }
        frame.setUndecorated(true);
        lobby = frame;
        exit = exitAction;
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent event) {
                if (lobby == frame) {
                    update();
                }
            }

            @Override
            public void componentHidden(ComponentEvent event) {
                if (lobby == frame) {
                    update();
                }
            }
        });
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (lobby == frame) {
                    release();
                }
            }
        });
        return true;
    }

    /** EDT: whether {@code frame} is the lobby shown over the start screen, whose place the overlay decides. */
    public static boolean manages(JFrame frame) {
        return frame != null && frame == lobby;
    }

    /**
     * GPU thread, as the window's close box is clicked over the start screen: asks the lobby to exit, as its own
     * close box would, and returns true; false when no lobby shows.
     */
    static boolean requestClose() {
        JFrame frame = lobby;
        Runnable action = exit;
        if (frame == null || action == null || !frame.isVisible()) {
            return false;
        }
        SwingUtilities.invokeLater(action);
        return true;
    }

    /** EDT: the start screen started or ended, so the lobby comes in front of it or steps back. */
    static void backdropChanged() {
        update();
    }

    private static void release() {
        JFrame frame = lobby;
        lobby = null;
        exit = null;
        if (frame != null) {
            stepBack(frame);
        }
    }

    private static void update() {
        JFrame frame = lobby;
        if (frame == null) {
            return;
        }
        boolean wanted = frame.isVisible() && GpuStartWindow.isBackdrop();
        if (wanted && !inFront) {
            comeForward(frame);
        } else if (!wanted && inFront) {
            stepBack(frame);
        }
    }

    private static void comeForward(JFrame frame) {
        inFront = true;
        if ((frame.getExtendedState() & (Frame.MAXIMIZED_BOTH | Frame.ICONIFIED)) != 0) {
            frame.setExtendedState(Frame.NORMAL);
        }
        GpuHost.watchBounds(GpuLobbyOverlay::place);
        GpuNativeDialogOwner owner = GpuStartWindow.backdropOwner();
        adoptedBy = owner != null && owner.adopt(frame) ? owner : null;
        if (adoptedBy == null) {
            // Native Wayland, or an owner that could not take it: the lobby floats over the window instead
            frame.setAlwaysOnTop(true);
        }
        GpuHost.dim(true, () -> {
            if (inFront && lobby == frame) {
                frame.toFront();
            }
        });
        frame.toFront();
    }

    private static void stepBack(JFrame frame) {
        inFront = false;
        GpuHost.watchBounds(null);
        GpuHost.dim(false, null);
        GpuNativeDialogOwner owner = adoptedBy;
        adoptedBy = null;
        if (owner != null) {
            owner.release(frame);
        } else {
            frame.setAlwaysOnTop(false);
        }
        if (frame.isVisible()) {
            frame.toBack();
        }
    }

    /**
     * EDT, before the lobby first shows: gives it its place over the window now, so it does not show at a size of its
     * own first and then jump into place. Returns false, leaving the frame alone, when it is not the lobby or the
     * window's place is not known yet.
     */
    public static boolean placeOverWindow(JFrame frame) {
        Rectangle client = GpuHost.clientBounds();
        if (frame == null || frame != lobby || client == null) {
            return false;
        }
        frame.setBounds(lobbyBounds(client));
        return true;
    }

    /** EDT: places the lobby over the window's client area, given in GLFW screen coordinates. */
    private static void place(Rectangle client) {
        JFrame frame = lobby;
        if (frame == null || !inFront) {
            return;
        }
        Rectangle bounds = lobbyBounds(client);
        if (!bounds.equals(frame.getBounds())) {
            frame.setBounds(bounds);
        }
    }

    private static Rectangle lobbyBounds(Rectangle client) {
        return inset(GpuStartWindow.toSwing(client), UIUtil.scaleForGUI(MARGIN), UIUtil.scaleForGUI(INSET_MIN_WIDTH),
              UIUtil.scaleForGUI(INSET_MIN_HEIGHT));
    }

    /**
     * The lobby's bounds over a window's client area: inset by {@code margin} on every side, or the whole area when
     * that would leave the lobby narrower than {@code minWidth} or lower than {@code minHeight}.
     */
    static Rectangle inset(Rectangle area, int margin, int minWidth, int minHeight) {
        if (area.width - 2 * margin < minWidth || area.height - 2 * margin < minHeight) {
            return new Rectangle(area);
        }
        return new Rectangle(area.x + margin, area.y + margin, area.width - 2 * margin, area.height - 2 * margin);
    }
}
