/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationListener;
import megamek.client.bot.BotClient;
import megamek.client.lobby.LobbySnapshot;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.event.GameListener;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameSettingsChangeEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.entity.GameEntityNewEvent;
import megamek.common.event.entity.GameEntityRemoveEvent;
import megamek.common.event.player.GamePlayerChangeEvent;
import megamek.common.event.player.GamePlayerChatEvent;
import megamek.common.event.player.GamePlayerConnectedEvent;
import megamek.common.event.player.GamePlayerDisconnectedEvent;
import megamek.logging.MMLogger;

/**
 * The game lobby as a scene of MegaMek's one native window ({@link GpuHost}), for games MegaMek hosts or joins and
 * games MekHQ starts alike. It shows while the client's game is in the lounge; the client's Swing frame stays hidden.
 * This controller lives on the Swing thread: it listens to the client's game, sends each change to the screen
 * ({@link GpuLobbyScreen}) as a {@link LobbySnapshot}, and runs the screen's actions on the Swing thread, where the
 * game is.
 *
 * <p>Ready runs the Swing lobby's own ready step ({@code ChatLounge.ready()}), whose checks ask through
 * {@code SwingLobbyPrompts}: Swing dialogs owned by the hidden client frame. This scene owns them natively, as the board
 * and the start screen own theirs, so they stay in front of the window, and a modal one blocks the screen's input.
 * Leave and the window's close box run the client's own exit ({@link ClientGUI#handleExit}), with its save question.
 * </p>
 *
 * <p>When the scene fails, for example because OpenGL can't start, the client's Swing frame shows with the Swing lobby
 * instead.</p>
 */
public final class GpuLobbyWindow {
    private static final MMLogger LOGGER = MMLogger.create(GpuLobbyWindow.class);

    /** EDT: the lobby holding the window or about to. */
    private static GpuLobbyWindow active;
    /** The client whose lobby an open from another thread is about to show. */
    private static volatile ClientGUI pending;

    private final ClientGUI gui;
    private final Set<Dialog> modalDialogs = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> dialogOnTop = new HashMap<>();
    private final AWTEventListener dialogListener = this::dialogEvent;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private final GameListener gameListener = new GameListenerAdapter() {
        @Override
        public void gamePlayerConnected(GamePlayerConnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerDisconnected(GamePlayerDisconnectedEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChange(GamePlayerChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePlayerChat(GamePlayerChatEvent event) {
            queueRefresh();
        }

        @Override
        public void gamePhaseChange(GamePhaseChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameSettingsChange(GameSettingsChangeEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityNew(GameEntityNewEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityRemove(GameEntityRemoveEvent event) {
            queueRefresh();
        }

        @Override
        public void gameEntityChange(GameEntityChangeEvent event) {
            queueRefresh();
        }
    };
    private final GpuHost.Scene scene = new GpuHost.Scene() {
        @Override
        public ApplicationListener listener() {
            GpuLobbyScreen created = new GpuLobbyScreen(GpuLobbyWindow.this, GUIPreferences.getInstance());
            screen = created;
            return created;
        }

        @Override
        public String title() {
            return Messages.getString("Lobby.windowTitle");
        }

        @Override
        public void started(long windowHandle) {
            nativeOwner = GpuNativeDialogOwner.forGlfwWindow(windowHandle);
            SwingUtilities.invokeLater(GpuLobbyWindow.this::presented);
        }

        @Override
        public void closeRequested() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuLobbyWindow.this::frontModal);
            } else {
                leave();
            }
        }

        @Override
        public void focusGained() {
            if (modalOpen) {
                SwingUtilities.invokeLater(GpuLobbyWindow.this::frontModal);
            }
        }

        @Override
        public void ended(Throwable failure) {
            SwingUtilities.invokeLater(() -> finished(failure));
        }
    };

    /** Whether a modal dialog is open; the GPU thread reads it to send clicks on the window to that dialog. */
    private volatile boolean modalOpen;
    private volatile boolean closing;
    private volatile GpuNativeDialogOwner nativeOwner;
    /** The screen while its listener exists; written on the GPU thread. */
    private volatile GpuLobbyScreen screen;
    /** The newest snapshot, which a screen shows as it is created. */
    private volatile LobbySnapshot snapshot = LobbySnapshot.EMPTY;
    private volatile GpuLobbyProbe probe;
    private boolean exitRequested;

    private GpuLobbyWindow(ClientGUI gui) {
        this.gui = gui;
    }

    /**
     * Any thread: shows {@code gui}'s lobby in the native window, replacing the scene there, or brings it to the front
     * when it already shows. Nothing for a bot's client, which has no screen.
     */
    public static void open(ClientGUI gui) {
        if (gui.getClient() instanceof BotClient) {
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            pending = gui;
            SwingUtilities.invokeLater(() -> open(gui));
            return;
        }
        if (pending == gui) {
            pending = null;
        }
        if (active != null) {
            if (active.gui == gui && !active.closing) {
                GpuHost.focus();
                return;
            }
            active.close();
        }
        GpuLobbyWindow window = new GpuLobbyWindow(gui);
        active = window;
        gui.getClient().getGame().addGameListener(window.gameListener);
        Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
        window.refresh();
        GpuHost.show(window.scene);
        window.probe = GpuLobbyProbe.applyOnce(gui);
    }

    /** EDT: ends {@code gui}'s lobby scene, if it shows; the scene that replaces it, if any, decides what shows next. */
    public static void closeFor(ClientGUI gui) {
        if (pending == gui) {
            pending = null;
        }
        if (active != null && active.gui == gui) {
            active.close();
        }
    }

    /** Whether {@code gui}'s lobby holds the native window or is about to, so its Swing frame stays hidden. */
    public static boolean isActiveFor(ClientGUI gui) {
        GpuLobbyWindow window = active;
        return pending == gui || window != null && window.gui == gui && !window.closing;
    }

    /** EDT: whether a lobby holds the window and is not closing. */
    static boolean isShowing() {
        return active != null && !active.closing;
    }

    private void close() {
        closing = true;
        GpuHost.end(scene);
    }

    /** The client whose lobby this is. */
    ClientGUI gui() {
        return gui;
    }

    /** The developer switch's shot and quit, or null; see {@link GpuLobbyProbe}. */
    GpuLobbyProbe probe() {
        return probe;
    }

    /** The newest snapshot of the lobby. */
    LobbySnapshot snapshot() {
        return snapshot;
    }

    /** Whether a modal Swing dialog over the lobby is open. */
    boolean modalOpen() {
        return modalOpen;
    }

    private void queueRefresh() {
        if (refreshQueued.compareAndSet(false, true)) {
            // Later, so the chat box has taken the same event first
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    /** EDT: reads the lobby and hands it to the screen. */
    private void refresh() {
        refreshQueued.set(false);
        if (closing) {
            return;
        }
        LobbySnapshot read;
        try {
            ChatterBox chat = gui.getChatterBox();
            read = LobbySnapshot.of(gui::getClient, chat == null ? List.of() : chat.lines(LobbySnapshot.CHAT_LINES));
        } catch (RuntimeException failure) {
            LOGGER.error(failure, "The lobby could not read the game");
            return;
        }
        snapshot = read;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.show(read);
            }
        });
    }

    // ---- The screen's actions, from the GPU thread ----

    /** Any thread: toggles the local player's ready state through the lobby's ready checks. */
    void ready() {
        SwingUtilities.invokeLater(() -> {
            if (closing) {
                return;
            }
            if (gui.chatlounge != null) {
                gui.chatlounge.ready();
            }
            queueRefresh();
        });
    }

    /** Any thread: leaves the game the way the client's exit does, after its save question. */
    void leave() {
        SwingUtilities.invokeLater(() -> {
            if (closing || exitRequested) {
                return;
            }
            exitRequested = true;
            try {
                gui.handleExit();
            } finally {
                exitRequested = false;
            }
        });
    }

    /** Any thread: sends a chat line, or runs a client command such as /who, as the chat box's input line does. */
    void chat(String text) {
        if (text.isBlank()) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            ChatterBox chat = gui.getChatterBox();
            if (!closing && chat != null) {
                chat.submit(text);
                queueRefresh();
            }
        });
    }

    /** GPU thread, as the screen's listener is disposed: returns every adopted dialog to its Swing owner. */
    void disposing() {
        screen = null;
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
    }

    // ---- The scene's life ----

    /** EDT, once the scene has started: the frame stays hidden, and dialogs that floated meanwhile are owned. */
    private void presented() {
        if (closing || active != this) {
            return;
        }
        JFrame frame = gui.getFrame();
        if (frame != null && frame.isVisible()) {
            frame.setVisible(false);
        }
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window instanceof Dialog dialog && belongsToClient(dialog)) {
                centre(dialog);
                raise(dialog);
            }
        }
        refresh();
    }

    private void finished(Throwable failure) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        gui.getClient().getGame().removeGameListener(gameListener);
        modalDialogs.clear();
        modalOpen = false;
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
        boolean replaced = closing;
        closing = true;
        if (active == this) {
            active = null;
        }
        if (failure != null && !replaced) {
            LOGGER.error(failure, "The native lobby failed; the Swing lobby shows instead");
            // The Swing lobby replaces the native window rather than standing beside the start screen
            GpuHost.closeWhenIdle();
            JFrame frame = gui.getFrame();
            if (frame != null && frame.isDisplayable()) {
                frame.setVisible(true);
                frame.toFront();
            }
        }
    }

    // ---- Dialogs over the lobby ----

    private void dialogEvent(AWTEvent event) {
        if (!(event.getSource() instanceof Dialog dialog)) {
            return;
        }
        int id = event.getID();
        if (id == ComponentEvent.COMPONENT_HIDDEN || id == WindowEvent.WINDOW_CLOSED) {
            endModal(dialog);
            GpuNativeDialogOwner owner = nativeOwner;
            if (owner != null) {
                if (id == WindowEvent.WINDOW_CLOSED) {
                    owner.forget(dialog);
                } else {
                    owner.hidden(dialog);
                }
            }
            if (id == WindowEvent.WINDOW_CLOSED) {
                Boolean wasOnTop = dialogOnTop.remove(dialog);
                if (wasOnTop != null) {
                    dialog.setAlwaysOnTop(wasOnTop);
                }
            }
        } else if (id == ComponentEvent.COMPONENT_SHOWN && !closing && belongsToClient(dialog)) {
            centre(dialog);
            raise(dialog);
        }
    }

    /**
     * A dialog of the client's hidden frame, or a modal one without an owner of its own, which Swing gives its hidden
     * shared frame: both would otherwise hide behind the native window.
     */
    private boolean belongsToClient(Dialog dialog) {
        Frame shared;
        try {
            shared = JOptionPane.getRootFrame();
        } catch (HeadlessException headless) {
            shared = null;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == gui.getFrame() || current == shared && dialog.isModal()) {
                return true;
            }
        }
        return false;
    }

    /** Centres a dialog of the hidden frame over the window; the hidden frame would centre it on the screen. */
    private static void centre(Dialog dialog) {
        Rectangle bounds = GpuHost.clientBounds();
        if (bounds == null || dialog.getOwner() instanceof Dialog) {
            return;
        }
        Rectangle swing = GpuStartWindow.toSwing(bounds);
        dialog.setLocation(swing.x + (swing.width - dialog.getWidth()) / 2,
              swing.y + (swing.height - dialog.getHeight()) / 2);
    }

    /**
     * Keeps a dialog above the window until either closes: owned natively where the platform allows, otherwise
     * floating on top. A modal dialog blocks the screen's input as it would its Swing owner's.
     */
    private void raise(Dialog dialog) {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null && (dialog.getOwner() instanceof Dialog parent && parent.isShowing() && owner.owns(parent)
              || owner.adopt(dialog))) {
            Boolean wasOnTop = dialogOnTop.remove(dialog);
            if (wasOnTop != null) {
                dialog.setAlwaysOnTop(wasOnTop);
            }
            if (dialog.isModal() && modalDialogs.add(dialog) && modalDialogs.size() == 1) {
                setInputBlocked(true);
            }
            dialog.toFront();
            return;
        }
        dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
    }

    private void endModal(Dialog dialog) {
        if (modalDialogs.remove(dialog) && modalDialogs.isEmpty()) {
            setInputBlocked(false);
        }
    }

    /** EDT: brings the newest open modal dialog to the front when the window is clicked or asked to close. */
    private void frontModal() {
        List<Dialog> showing = new ArrayList<>();
        for (Dialog dialog : modalDialogs) {
            if (dialog.isShowing()) {
                showing.add(dialog);
            }
        }
        if (!showing.isEmpty()) {
            showing.get(showing.size() - 1).toFront();
        }
    }

    /**
     * Blocks or restores the screen's input. The native window itself stays enabled, as the start screen's does, so
     * Windows doesn't hand the activation to another application as a modal dialog closes.
     */
    private void setInputBlocked(boolean blocked) {
        modalOpen = blocked;
        GpuHost.post(() -> {
            GpuLobbyScreen shown = screen;
            if (shown != null) {
                shown.setInputBlocked(blocked);
            }
        });
    }
}
