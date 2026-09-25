/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.util.ScreenFit;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;

/** Owns the default battle window. A single libGDX application avoids competing global Gdx contexts. */
public final class GpuBoardWindow {
    static final boolean DEFAULT_VSYNC = true;
    /** The planar compatibility layer omits wreck sprites when the shared unit renderer supplies them. */
    public static boolean modelsEnabled() { return GpuUnitModels.ENABLED; }
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardWindow.class);
    private static GpuBoardWindow active;
    private final ClientGUI gui;
    private final BoardView initialView;
    private final Supplier<JComponent> panel;
    private final Timer startupTimer;
    private volatile GpuBoardSource source;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final Window classicWindow;
    /** Preview windows own their temporary BoardView and leave the browser's modal session intact. */
    private final boolean preview;
    private final BoardEditorPanel editor;
    private final Map<Dialog, Boolean> dialogOnTop = new IdentityHashMap<>();
    /**
     * The classic window hides while the GPU view runs, so a Swing dialog owned by it (deployment elevation choices,
     * alerts, item pickers) would open behind the native window. Every client dialog that opens while the GPU window is
     * presented is raised above it instead.
     */
    private final AWTEventListener dialogListener = event -> {
        if (closeWithPreviewOwner(event)) {
            return;
        }
        if (event.getID() == ComponentEvent.COMPONENT_SHOWN
              && event.getSource() instanceof Dialog dialog && belongsToClassicWindow(dialog)) {
            dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
            dialog.setAlwaysOnTop(true);
            dialog.toFront();
        }
    };
    private volatile Lwjgl3Application application;
    private volatile boolean closing;
    private volatile boolean presented;
    private volatile boolean restoreClassic;
    private volatile boolean exitRequested;
    private volatile Throwable startupFailure;
    private Runnable afterClose;
    /** Frames between reads of the window's bounds; a few times a second is plenty to follow a move or resize. */
    private static final int BOUNDS_POLL_FRAMES = 15;
    /** The saved bounds the window opens with, read on the Swing thread. */
    private final GpuWindowBounds startBounds;
    /** The last normal, un-maximized bounds seen on the GPU thread. */
    private volatile GpuWindowBounds normalBounds;
    /** The bounds last handed to the Swing thread to save, so an unchanged window writes nothing. */
    private volatile GpuWindowBounds publishedBounds;

    private GpuBoardWindow(ClientGUI gui, BoardView view, Supplier<JComponent> panel) {
        this(gui, view, panel, null);
    }

    private GpuBoardWindow(ClientGUI gui, BoardView view, Supplier<JComponent> panel, Window previewOwner) {
        this(gui, view, panel, previewOwner, null);
    }

    private GpuBoardWindow(ClientGUI gui, BoardView view, Supplier<JComponent> panel, Window previewOwner,
          BoardEditorPanel editor) {
        this.gui = gui;
        this.editor = editor;
        initialView = view;
        this.panel = panel;
        preview = previewOwner != null;
        classicWindow = preview ? previewOwner
              : gui == null ? SwingUtilities.getWindowAncestor(view.getPanel()) : gui.getFrame();
        if (editor != null) {
            loadingMessage = Messages.getString("BoardEditor.edit3DLoading");
        } else if (preview) {
            loadingMessage = Messages.getString("GpuBoard.previewLoading");
        }
        startupTimer = new Timer(100, event -> initializeSource());
        startBounds = GpuWindowBounds.load(GUIPreferences.getInstance());
        GpuPanelDock.restoredWidth = GUIPreferences.getInstance().getGpuReportPanelWidth();
        normalBounds = startBounds;
        publishedBounds = startBounds;
    }

    /** Load a browser entry without changing the lobby's selected boards. */
    public static void openPreview(Window owner, File boardFile) {
        Board board = new Board();
        try (var input = new FileInputStream(boardFile)) {
            var errors = new ArrayList<String>();
            board.load(input, errors, false);
            if (!errors.isEmpty() || board.getWidth() <= 0 || board.getHeight() <= 0) {
                throw new IOException("Could not load board " + boardFile + ": " + errors);
            }
            board.setMapName(boardFile.getName());
        } catch (IOException | RuntimeException failure) {
            reportPreviewFailure(owner, failure);
            return;
        }
        openPreview(owner, board);
    }

    /** The preview owns a temporary game and view; the shared renderer supplies all terrain and camera controls. */
    public static synchronized void openPreview(Window owner, Board board) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Open the GPU preview on the Swing event thread");
        }
        Objects.requireNonNull(owner);
        if (active != null) {
            if (active.preview || active.closing) {
                active.close(false);
                active.afterClose = () -> {
                    if (owner.isShowing()) {
                        openPreview(owner, board);
                    }
                };
            } else {
                JOptionPane.showMessageDialog(owner, Messages.getString("GpuBoard.alreadyOpen"));
            }
            return;
        }
        try {
            Game game = new Game();
            game.setBoard(board);
            game.setPhase(GamePhase.LOUNGE);
            BoardView view = new BoardView(game, null, null, 0);
            view.setDisplayInvalidFields(false);
            view.setUseLosTool(false);
            start(new GpuBoardWindow(null, view, () -> null, owner));
        } catch (IOException | RuntimeException | LinkageError failure) {
            reportPreviewFailure(owner, failure);
        }
    }

    public static synchronized void open(BoardView view, Supplier<JComponent> panel) {
        open(view.getClientgui(), view, panel);
    }

    /** Switch the existing editor's view; its board, tools and undo history remain owned by Swing. */
    public static synchronized void toggleEditor(BoardEditorPanel editor) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> toggleEditor(editor));
            return;
        }
        if (active != null) {
            if (active.editor == editor) {
                if (!active.closing) {
                    active.close(true);
                }
            } else if (active.preview) {
                active.close(false);
                active.afterClose = () -> {
                    if (editor.getFrame().isShowing()) {
                        toggleEditor(editor);
                    }
                };
            } else {
                JOptionPane.showMessageDialog(editor.getFrame(), Messages.getString("GpuBoard.alreadyOpen"));
            }
            return;
        }
        editor.finishBrushStroke();
        start(new GpuBoardWindow(null, editor.getBoardView(), () -> null, null, editor));
    }

    /** Start the chosen board window even before a scenario or server has delivered its first map. */
    public static synchronized void open(ClientGUI gui, Supplier<JComponent> panel) {
        open(gui, null, panel);
    }

    private static void open(ClientGUI gui, BoardView view, Supplier<JComponent> panel) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Open the GPU board on the Swing event thread");
        }
        if (active != null) {
            if (active.preview) {
                active.close(false);
                active.afterClose = () -> {
                    if (gui == null || gui.getFrame().isDisplayable()) {
                        open(gui, view, panel);
                    }
                };
            } else if (active.gui != gui || (gui == null && active.initialView != view)) {
                JOptionPane.showMessageDialog(gui == null ? view.getPanel() : gui.getFrame(),
                      Messages.getString("GpuBoard.alreadyOpen"));
            } else if (!active.closing) {
                active.focus(false);
            } else {
                // A new game may arrive while the previous game's native window is still being disposed.
                active.restoreClassic = false;
                active.afterClose = () -> open(gui, view, panel);
            }
            return;
        }
        start(new GpuBoardWindow(gui, view, panel));
    }

    private static void start(GpuBoardWindow window) {
        try {
            active = window;
            ClientGUI gui = window.gui;
            if (gui != null) {
                GUIPreferences.getInstance().setUse3DBoard(true);
                gui.getMenuBar().setBoardView3D(true);
                gui.setMiniReportLocation(false);
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
                  AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
            Thread thread = new Thread(window::run, "MegaMek-GPU-board");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | LinkageError failure) {
            window.closing = true;
            window.finish(failure);
        }
    }

    private void run() {
        Throwable failure = null;
        try {
            // The native window owns startup; its first visible frame begins the entrance animation.
            Lwjgl3ApplicationConfiguration configuration = configuration(false);
            if (preview) {
                configuration.setTitle(Messages.getString("GpuBoard.previewTitle") + " - "
                      + initialView.game.getBoard().getBoardName());
            } else if (editor != null) {
                configuration.setTitle(Messages.getString("BoardEditor.edit3D"));
            }
            // Reopen where the user left it: the normal size and place, maximized again if it was. GLFW maximizes on
            // the monitor holding the saved place, and restoring returns to the saved size.
            configuration.setDecorated(true);
            configuration.setWindowedMode(startBounds.width(), startBounds.height());
            if (!startBounds.centred()) {
                configuration.setWindowPosition(startBounds.x(), startBounds.y());
            }
            configuration.setMaximized(startBounds.maximized());
            configuration.setWindowListener(new WindowListener() {
                @Override
                public void created(Lwjgl3Window window) {
                    // The shared listener detects the shading language first; then the window is fitted on screen.
                    super.created(window);
                    fitOnScreen(window);
                }

                @Override
                public boolean closeRequested() {
                    requestExit();
                    return false;
                }
            });
            new Lwjgl3Application(new GpuBattleView(null) {
                private boolean presentationRequested;

                @Override
                public void create() {
                    application = (Lwjgl3Application) Gdx.app;
                    super.create();
                    prepareEntrance();
                    if (closing) {
                        application.exit();
                    }
                }

                private int framesSinceBounds;

                @Override
                public void render() {
                    setLoadingMessage(loadingMessage);
                    super.render();
                    if (presented && ++framesSinceBounds >= BOUNDS_POLL_FRAMES) {
                        framesSinceBounds = 0;
                        trackBounds();
                    }
                    if (!presentationRequested) {
                        presentationRequested = true;
                        SwingUtilities.invokeLater(GpuBoardWindow.this::present);
                    }
                }
            }, configuration);
        } catch (RuntimeException | LinkageError error) {
            failure = error;
        } finally {
            closing = true;
            application = null;
            if (source != null) {
                source.close();
            }
            Throwable renderingFailure = failure;
            SwingUtilities.invokeLater(() -> finish(renderingFailure == null ? startupFailure : renderingFailure));
        }
    }

    /**
     * A window saved on a monitor that has since been unplugged, or moved off the desktop, comes back onto the nearest
     * monitor's work area. Runs on the GPU thread once the native window exists. A centred window needs no check.
     */
    private void fitOnScreen(Lwjgl3Window window) {
        if (startBounds.centred()) {
            return;
        }
        var fitted = ScreenFit.fit(startBounds.rectangle(), GpuWindowBounds.workAreas());
        if (fitted.equals(startBounds.rectangle())) {
            LOGGER.debug("GPU board window restored at {}", fitted);
            return;
        }
        LOGGER.info("GPU board window saved at {} is off screen; moved to {}", startBounds.rectangle(), fitted);
        long handle = window.getWindowHandle();
        boolean maximized = startBounds.maximized();
        if (maximized) {
            window.restoreWindow();
        }
        GLFW.glfwSetWindowSize(handle, fitted.width, fitted.height);
        window.setPosition(fitted.x, fitted.y);
        if (maximized) {
            window.maximizeWindow();
        }
        normalBounds = new GpuWindowBounds(fitted.x, fitted.y, fitted.width, fitted.height, maximized);
    }

    /**
     * Follows the window as the user moves, resizes or maximizes it, and hands any change to the Swing thread to save.
     * Runs on the GPU thread. The normal bounds update only while the window is neither maximized nor minimized, so a
     * maximized window keeps the size it restores to, and a minimized one does not save the far-off position Windows
     * reports for it.
     */
    private void trackBounds() {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics graphics)) {
            return;
        }
        Lwjgl3Window window = graphics.getWindow();
        if (window == null || window.isIconified()) {
            return;
        }
        long handle = window.getWindowHandle();
        boolean maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        GpuWindowBounds normal = normalBounds;
        if (!maximized) {
            int[] width = new int[1];
            int[] height = new int[1];
            GLFW.glfwGetWindowSize(handle, width, height);
            normal = new GpuWindowBounds(window.getPositionX(), window.getPositionY(), width[0], height[0], false);
            normalBounds = normal;
        }
        GpuWindowBounds current = new GpuWindowBounds(normal.x(), normal.y(), normal.width(), normal.height(), maximized);
        if (!current.equals(publishedBounds)) {
            publishedBounds = current;
            SwingUtilities.invokeLater(() -> current.save(GUIPreferences.getInstance()));
        }
    }

    private void present() {
        if (closing) {
            return;
        }
        presented = true;
        focus(true);
        if (!preview && classicWindow != null) {
            classicWindow.setVisible(false);
        }
        if (editor != null) {
            editor.enter3DEditor();
        }
        if (gui != null) {
            gui.setClassicBoardViewEnabled(false);
            gui.refreshAuxiliaryWindows();
        }
        startupTimer.start();
    }

    private void initializeSource() {
        if (closing || source != null) {
            startupTimer.stop();
            return;
        }
        if (!preview && editor == null) {
            String status = GpuBoardActions.phaseStatus(panel.get()).text();
            loadingMessage = status.isBlank() ? Messages.getString("ClientGUI.waitingOnTheServer") : status;
        }
        BoardView view = gui == null ? initialView : gui.getCurrentBoardView()
              .filter(BoardView.class::isInstance).map(BoardView.class::cast).orElse(null);
        if (view == null) {
            return;
        }
        try {
            source = new GpuBoardSource(view, panel, editor);
            startupTimer.stop();
            application.postRunnable(() -> {
                if (!closing) {
                    ((GpuBattleView) Gdx.app.getApplicationListener()).attachSource(source);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            startupFailure = failure;
            close(false);
        }
    }

    /** Native close is the client's normal quit action. Cancelled saves leave this window running. */
    private void requestExit() {
        if (exitRequested || closing) {
            return;
        }
        exitRequested = true;
        SwingUtilities.invokeLater(() -> {
            try {
                if (gui == null) {
                    close(editor != null);
                } else {
                    gui.handleExit();
                }
            } finally {
                exitRequested = false;
            }
        });
    }

    /** True when the window is the classic window itself or a dialog chain owned by it. */
    private boolean belongsToClassicWindow(Window window) {
        for (Window current = window; current != null; current = current.getOwner()) {
            if (current == classicWindow) {
                return true;
            }
        }
        return false;
    }

    private boolean closeWithPreviewOwner(AWTEvent event) {
        if (preview && !closing && event.getSource() == classicWindow
              && (event.getID() == ComponentEvent.COMPONENT_HIDDEN || event.getID() == WindowEvent.WINDOW_CLOSED)) {
            close(false);
            return true;
        }
        return false;
    }

    private void finish(Throwable failure) {
        startupTimer.stop();
        restoreDialogPresentation();
        synchronized (GpuBoardWindow.class) {
            if (active != this) {
                return;
            }
            active = null;
        }
        if (preview) {
            initialView.dispose();
            if (classicWindow.isShowing() && afterClose == null) {
                classicWindow.toFront();
                classicWindow.requestFocus();
            }
        }
        if (editor != null) {
            editor.leave3DEditor();
            restoreClassic = (restoreClassic || failure != null) && classicWindow.isDisplayable();
        }
        // The native window is already destroyed. Never resurrect a client that is shutting down.
        if (afterClose != null) {
            afterClose.run();
        } else if (restoreClassic && classicWindow != null) {
            showClassicWindow(gui, classicWindow);
        }
        if (failure != null) {
            reportFailure(failure);
        }
    }

    private void restoreDialogPresentation() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
    }

    /** Return to the existing client without disconnecting or changing the game. */
    public static synchronized void showClassic(ClientGUI gui) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showClassic(gui));
            return;
        }
        if (active != null && active.gui == gui) {
            active.close(true);
        } else if (gui.getFrame() != null) {
            showClassicWindow(gui, gui.getFrame());
        }
    }

    /** Includes preparation: classic panels must not open while the native window is starting. */
    public static synchronized boolean isActiveFor(ClientGUI gui) {
        return active != null && active.gui == gui;
    }

    private static void showClassicWindow(ClientGUI gui, Window window) {
        if (gui != null) {
            gui.setClassicBoardViewEnabled(!gui.getClient().getGame().getPhase().isLounge());
            gui.getMenuBar().setBoardView3D(false);
        }
        if (window instanceof Frame frame && (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
        if (gui != null) {
            gui.refreshAuxiliaryWindows();
        }
    }

    static Lwjgl3ApplicationConfiguration configuration(boolean visible) {
        // Supported by current LWJGL; no retired AWT extension or macOS JVM relaunch is needed here.
        Lwjgl3ApplicationConfiguration.useGlfwAsync();
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle(Messages.getString("GpuBoard.title"));
        configuration.setWindowedMode(1280, 800);
        configuration.setWindowSizeLimits(900, 600, -1, -1);
        configuration.setInitialVisible(visible);
        // Keep rendering bounded even when VSync is disabled, we cap at 60fps. With VSync we let it do what it needs...
        configuration.setForegroundFPS(DEFAULT_VSYNC ? 0 : 60);
        configuration.useVsync(DEFAULT_VSYNC);
        configuration.setDepthBits(24);
        configuration.disableAudio(true);
        GpuGlsl.configure(configuration);
        configuration.setWindowListener(new WindowListener());
        return configuration;
    }

    /** Every board window's events: its context sets the shading language, and losing focus pauses the battle. */
    private static class WindowListener extends Lwjgl3WindowAdapter {
        @Override
        public void created(Lwjgl3Window window) {
            GpuGlsl.detect();
        }

        @Override
        public void focusLost() {
            if (Gdx.app != null && Gdx.app.getApplicationListener() instanceof GpuBattleView battle) {
                battle.pause();
            }
        }
    }

    private void focus(boolean entering) {
        Lwjgl3Application app = application;
        if (app != null && presented) {
            app.postRunnable(() -> {
                if (!closing) {
                    if (entering && app.getApplicationListener() instanceof GpuBattleView battle) {
                        battle.startEntrance();
                    }
                    var window = ((Lwjgl3Graphics) app.getGraphics()).getWindow();
                    window.setVisible(true);
                    window.focusWindow();
                }
            });
        }
    }

    private void close(boolean returnToClassic) {
        afterClose = null;
        restoreClassic = returnToClassic;
        closing = true;
        startupTimer.stop();
        if (source != null) {
            source.close();
        }
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(app::exit);
        }
    }

    public static synchronized void closeFor(BoardView view) {
        if (active != null && (active.initialView == view || active.source != null && active.source.currentView() == view)) {
            active.close(false);
        }
    }

    public static synchronized void closeFor(ClientGUI gui) {
        if (active != null && active.gui == gui) {
            active.close(false);
        }
    }

    private void reportFailure(Throwable failure) {
        if (editor != null) {
            LOGGER.error("GPU map editor failed", failure);
            JOptionPane.showMessageDialog(classicWindow, Messages.getString("BoardEditor.edit3DUnavailable"),
                  Messages.getString("BoardEditor.edit3D"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (preview) {
            reportPreviewFailure(classicWindow, failure);
            return;
        }
        LOGGER.error("GPU battle view failed", failure);
        Object[] choices = { Messages.getString("CommonMenuBar.viewGpuBoard"),
              Messages.getString("CommonMenuBar.viewClassicBoard"), Messages.getString("MegaMek.Quit.label") };
        int choice = JOptionPane.showOptionDialog(classicWindow, Messages.getString("GpuBoard.unavailable"),
              Messages.getString("CommonMenuBar.viewGpuBoard"), JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE,
              null, choices, choices[0]);
        if (choice == 0) {
            open(gui, initialView, panel);
        } else if (choice == 1 && gui != null) {
            GUIPreferences.getInstance().setUse3DBoard(false);
            showClassicWindow(gui, classicWindow);
        } else if (gui != null) {
            gui.handleExit();
        }
    }

    private static void reportPreviewFailure(Window owner, Throwable failure) {
        LOGGER.error("GPU map preview failed", failure);
        JOptionPane.showMessageDialog(owner, Messages.getString("GpuBoard.previewUnavailable"),
              Messages.getString("GpuBoard.preview"), JOptionPane.ERROR_MESSAGE);
    }
}
