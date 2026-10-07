/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import megamek.client.ui.clientGUI.boardview.RulerModel;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.annotations.Nullable;
import megamek.common.units.Entity;
import megamek.client.ui.util.ScreenFit;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;

/** Owns the default battle window. A single libGDX application avoids competing global Gdx contexts. */
public final class GpuBoardWindow {
    /** Kinds of native modal request; FORM carries the long-tail forms. */
    public enum DialogKind { MESSAGE, CHOICE, MULTI, INPUT, FORM }

    /** Input kind of one FORM field. */
    public enum FieldKind { INTEGER, TEXT, CHOICE, CHECKBOX }

    /** One CHOICE or MULTI row; the icon is optional. */
    public record DialogRow(String label, String detail, BoardScene.Pixels icon, boolean enabled) { }

    /**
     * One FORM field: min and max bound an INTEGER, choices list a CHOICE. Changing a {@code live} CHOICE or CHECKBOX
     * answers the form at once with {@link DialogAnswer#CHANGED} and its values, so that the asker can show the form
     * again as the change makes it: other rows, labels or message.
     */
    public record DialogField(String label, FieldKind kind, List<String> choices, int min, int max, String initial,
          boolean live) {
        public DialogField {
            choices = List.copyOf(choices);
        }

        /** A field whose changes wait for the form's buttons. */
        public DialogField(String label, FieldKind kind, List<String> choices, int min, int max, String initial) {
            this(label, kind, choices, min, max, initial, false);
        }
    }

    /**
     * Immutable modal request for the native window. The board source assigns {@code id} when it shows the request,
     * so callers pass 0. An empty {@code checkbox} means none. INPUT reads an integer when min or max is set, and shows
     * that range beside its field. For MULTI, {@code max}, when set, is the most rows the player can tick. Enter
     * presses {@code defaultButton}; a {@code defaultButton} of -1 means Enter presses no button, as in a Swing dialog
     * without a default button. A {@code cancelButton} of -1 means Esc closes the dialog without pressing a button, as
     * a JOptionPane's close box does: its answer's button is then -1, and it still carries the checkbox as the player
     * left it. A MESSAGE may show an {@code image} beside its text: an image file's bytes (PNG, JPEG or BMP), base64
     * encoded; null for none.
     */
    public record DialogRequest(long id, DialogKind kind, String title, String message, boolean monospace,
          List<String> buttons, int defaultButton, int cancelButton, List<DialogRow> rows,
          List<Integer> initiallySelected, String checkbox, boolean checkboxInitial, String initialText,
          Integer min, Integer max, List<DialogField> fields, String image) {
        public DialogRequest {
            buttons = List.copyOf(buttons);
            rows = List.copyOf(rows);
            initiallySelected = List.copyOf(initiallySelected);
            fields = List.copyOf(fields);
        }

        /** A request without an image. */
        public DialogRequest(long id, DialogKind kind, String title, String message, boolean monospace,
              List<String> buttons, int defaultButton, int cancelButton, List<DialogRow> rows,
              List<Integer> initiallySelected, String checkbox, boolean checkboxInitial, String initialText,
              Integer min, Integer max, List<DialogField> fields) {
            this(id, kind, title, message, monospace, buttons, defaultButton, cancelButton, rows, initiallySelected,
                  checkbox, checkboxInitial, initialText, min, max, fields, null);
        }

        DialogRequest withId(long shownId) {
            return new DialogRequest(shownId, kind, title, message, monospace, buttons, defaultButton, cancelButton,
                  rows, initiallySelected, checkbox, checkboxInitial, initialText, min, max, fields, image);
        }
    }

    /** The answer to one request; a null text means the input was cancelled. */
    public record DialogAnswer(int button, List<Integer> selected, String text, boolean checked, List<String> values) {
        /** The button of a FORM answered because a live field changed; its values are the form's as left. */
        public static final int CHANGED = -2;

        public DialogAnswer {
            selected = List.copyOf(selected);
            values = List.copyOf(values);
        }

        /** The same result as closing the Swing dialog (CLOSED_OPTION, false or null), which every caller handles. */
        public static DialogAnswer cancelled(DialogRequest request) {
            return new DialogAnswer(request.cancelButton(), List.of(), null, request.checkboxInitial(), List.of());
        }
    }

    static final boolean DEFAULT_VSYNC = true;
    /** The planar compatibility layer omits wreck sprites when the shared unit renderer supplies them. */
    public static boolean modelsEnabled() { return GpuUnitModels.ENABLED; }
    private static final MMLogger LOGGER = MMLogger.create(GpuBoardWindow.class);
    /** Volatile so that {@link #ask} and {@link #dialogPendingFor} read it without the class monitor. */
    private static volatile GpuBoardWindow active;
    /**
     * The Swing windows the user kept over the battle window (rebuild plan R9, closed by Z1), each with its decision:
     * R6 and swing-inventory Q1 (the menu-level dialogs stay Swing for now) and U2 (user item 6). Message boxes and
     * file choosers have no class of their own; {@link #approved} names the kept ones. Everything else is native.
     */
    private static final List<Class<? extends Dialog>> SWING_DIALOGS = List.of(
          // R6: Client Settings, which hold the real settings (user item 3)
          megamek.client.ui.dialogs.buttonDialogs.CommonSettingsDialog.class,
          // R6: Game Options
          megamek.client.ui.dialogs.buttonDialogs.GameOptionsDialog.class,
          // R6: Player Settings
          megamek.client.ui.panels.phaseDisplay.lobby.PlayerSettingsDialog.class,
          // R6: Edit Bots
          megamek.client.ui.dialogs.buttonDialogs.EditBotsDialog.class,
          // R6: a bot's settings, opened from Edit Bots and Player Settings
          megamek.client.ui.dialogs.buttonDialogs.BotConfigDialog.class,
          // R6: Random Army, also the game master's random reinforcements
          megamek.client.ui.dialogs.randomArmy.RandomArmyDialog.class,
          // R6: Network information
          megamek.client.ui.dialogs.buttonDialogs.NetworkInformationDialog.class,
          // U2, GM tools (swing-inventory R7): the game master vote
          megamek.client.ui.dialogs.GameMasterVoteDialog.class,
          // U2, GM tools: the new game master's notice
          megamek.client.ui.dialogs.GameMasterAppointedDialog.class,
          // U2, GM editors: building
          megamek.client.ui.dialogs.BuildingEditDialog.class,
          // U2, GM editors: hex
          megamek.client.ui.dialogs.HexEditDialog.class,
          // U2, GM editors: unit
          megamek.client.ui.dialogs.UnitEditorDialog.class,
          // U2, GM tools: the command form of the Commands menu (kick, game master commands)
          megamek.client.ui.dialogs.ClientCommandDialog.class,
          // U2, GM editors: player setup
          megamek.client.ui.dialogs.GameMasterPlayerSetupDialog.class,
          // U2, GM editors: notes
          megamek.client.ui.dialogs.NoteDialog.class,
          // U2: the reinforcement selector
          megamek.client.ui.dialogs.unitSelectorDialogs.MegaMekUnitSelectorDialog.class,
          // U2: the accessibility window
          megamek.client.ui.dialogs.AccessibilityDialog.class,
          // U2: the Nova network view
          megamek.client.ui.dialogs.phaseDisplay.NovaNetworkViewDialog.class,
          // U2: unit cache loading
          megamek.client.ui.dialogs.UnitLoadingDialog.class,
          // U2: Readme
          megamek.client.ui.dialogs.helpDialogs.MMReadMeHelpDialog.class,
          // U2: Help
          megamek.client.ui.dialogs.helpDialogs.HelpDialog.class,
          // U2: the developer conditions editor behind the Tuning utility
          megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog.class);
    private final ClientGUI gui;
    private final BoardClientState initialView;
    private final Supplier<JComponent> panel;
    private final Timer startupTimer;
    private volatile BoardSource source;
    private volatile String previewTitle;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final Window classicWindow;
    /** Preview windows own their temporary game and leave the browser's modal session intact. */
    private final boolean preview;
    private final BoardEditorSession editor;
    private final Game mapGame;
    private final Map<Dialog, Boolean> dialogOnTop = new IdentityHashMap<>();
    /**
     * The classic window hides while the GPU view runs, so a Swing dialog owned by it would open behind the native
     * window. A client dialog that opens while the GPU window cannot ask the client's prompts itself (it starts or
     * closes), or one of {@link #allowedOverBattle} while it can, is raised above it instead.
     */
    private final AWTEventListener dialogListener = event -> {
        if (closeWithPreviewOwner(event)) {
            return;
        }
        if (event.getID() == ComponentEvent.COMPONENT_SHOWN
              && event.getSource() instanceof Dialog dialog && belongsToClassicWindow(dialog)) {
            if (asksPrompts() && !allowedOverBattle(dialog)) {
                return;
            }
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

    private GpuBoardWindow(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel) {
        this(gui, view, panel, null, null, null);
    }

    private GpuBoardWindow(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel, Window previewOwner,
          BoardEditorSession editor, Game mapGame) {
        this.gui = gui;
        this.editor = editor;
        this.mapGame = mapGame;
        initialView = view;
        this.panel = panel;
        preview = previewOwner != null && editor == null;
        if (preview) { previewTitle = previewTitle(mapGame.getBoard()); }
        classicWindow = preview || editor != null ? previewOwner
                    : gui == null ? null : gui.getFrame();
        if (editor != null) {
            loadingMessage = Messages.getString("BoardEditor.edit3DLoading");
        } else if (preview) {
            loadingMessage = Messages.getString("GpuBoard.previewLoading");
        }
        startupTimer = new Timer(100, event -> initializeSource());
        startBounds = GpuWindowBounds.load(GUIPreferences.getInstance());
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

    /** The preview owns a temporary game; the shared renderer supplies all terrain and camera controls. */
    public static synchronized void openPreview(Window owner, Board board) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Open the GPU preview on the Swing event thread");
        }
        Objects.requireNonNull(owner);
        if (active != null) {
            if (active.preview && !active.closing && active.classicWindow == owner) {
                active.replacePreview(board);
            } else if (active.preview || active.closing) {
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
            start(new GpuBoardWindow(null, null, () -> null, owner, null, game));
        } catch (RuntimeException | LinkageError failure) {
            reportPreviewFailure(owner, failure);
        }
    }

    public static synchronized void open(BoardClientState view, Supplier<JComponent> panel) {
        open(view.getClientgui(), view, panel);
    }

    /** Starts an independent native editor. The owner is only a launcher/file-dialog owner, never a board view. */
    public static synchronized void openEditor(Window owner, File file, boolean chooseFile) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> openEditor(owner, file, chooseFile));
            return;
        }
        if (active != null) {
            if (active.preview) {
                active.close(false);
                active.afterClose = () -> openEditor(owner, file, chooseFile);
            } else {
                JOptionPane.showMessageDialog(owner, Messages.getString("GpuBoard.alreadyOpen"));
            }
            return;
        }
        try {
            BoardEditorSession editor = new BoardEditorSession();
            if (file != null) { editor.open(file.toPath()); }
            else if (chooseFile && !editor.chooseOpen(owner)) { return; }
            start(new GpuBoardWindow(null, null, () -> null, owner, editor, editor.game()));
        } catch (IOException | RuntimeException failure) {
            reportPreviewFailure(owner, failure);
        }
    }

    private static String previewTitle(Board board) {
        return Messages.getString("GpuBoard.previewTitle") + " - " + board.getBoardName();
    }

    /** Browsing another map reuses the same context, artwork, shaders and UI, just like loading in the editor. */
    private void replacePreview(Board board) {
        previewTitle = previewTitle(board);
        mapGame.setBoard(board); // The source's existing game listener captures and publishes the replacement.
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(() -> {
                if (!closing) { ((Lwjgl3Graphics) app.getGraphics()).getWindow().setTitle(previewTitle); }
            });
        }
        focus(false);
    }

    /** Start the chosen board window even before a scenario or server has delivered its first map. */
    public static synchronized void open(ClientGUI gui, Supplier<JComponent> panel) {
        open(gui, null, panel);
    }

    private static void open(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel) {
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
                JOptionPane.showMessageDialog(gui == null ? null : gui.getFrame(),
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
                  AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_FOCUS_EVENT_MASK
                        | AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            Thread thread = new Thread(window::run, "MegaMek-GPU-board");
            thread.setDaemon(true);
            thread.start();
            // Capture map data on its owning EDT while the native thread creates its context and loading UI.
            if (window.mapGame != null) { SwingUtilities.invokeLater(window::initializeSource); }
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
                configuration.setTitle(previewTitle);
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
                private boolean sourceAttached;

                @Override
                public void create() {
                    application = (Lwjgl3Application) Gdx.app;
                    super.create();
                    // Another preview may have been selected before the native context finished opening.
                    if (preview) { ((Lwjgl3Graphics) Gdx.graphics).getWindow().setTitle(previewTitle); }
                    prepareEntrance();
                    if (closing) {
                        application.exit();
                    }
                }

                private int framesSinceBounds;

                @Override
                public void render() {
                    BoardSource next = GpuBoardWindow.this.source;
                    if (!closing && presented && !sourceAttached && next != null) {
                        attachSource(next);
                        sourceAttached = true;
                    }
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
            try {
                String detail = GpuGlsl.glfwError();
                if (!detail.isEmpty()) { failure = new com.badlogic.gdx.utils.GdxRuntimeException(detail, error); }
            } catch (RuntimeException | LinkageError diagnosticFailure) {
                error.addSuppressed(diagnosticFailure);
            }
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
        if (!preview && classicWindow != null) {
            classicWindow.setVisible(false);
        }
        if (gui != null) {
            gui.setClassicBoardViewEnabled(false);
            gui.refreshAuxiliaryWindows();
        }
        // A source captured during native startup must publish the now-measured tools inset before camera fitting.
        if (editor != null && source != null) { source.refresh(); }
        initializeSource();
        presented = true;
        focus(true);
        if (!closing && source == null) { startupTimer.start(); }
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
        BoardClientState view = gui == null ? initialView : gui.getCurrentBoardState().orElse(null);
        if (mapGame == null && view == null) { return; }
        if (mapGame != null && (mapGame.getBoard().getWidth() < 1 || mapGame.getBoard().getHeight() < 1)) { return; }
        try {
            source = mapGame == null ? new GpuBoardSource(view, panel) : new GpuMapSource(mapGame, classicWindow, editor);
            startupTimer.stop();
        } catch (RuntimeException | LinkageError failure) {
            startupFailure = failure;
            close(false);
        }
    }

    /** Native close uses the editor or client's normal quit action. Cancelled saves leave this window running. */
    private void requestExit() {
        if (exitRequested || closing) {
            return;
        }
        exitRequested = true;
        SwingUtilities.invokeLater(() -> {
            try {
                if (editor != null) {
                    if (source != null) { source.endEditorStroke(); }
                    if (editor.confirmDiscard(classicWindow)) { close(true); }
                } else if (gui == null) {
                    close(false);
                } else {
                    gui.handleExit();
                }
            } finally {
                exitRequested = false;
            }
        });
    }

    /**
     * Whether a Swing dialog may show over the battle window while that asks the client's prompts itself, which then
     * raises it: a surface the user kept in Swing ({@link #approved}), or a dialog that opens inside one, owned by it
     * or while it shows modally (its own prompts, pickers and file choosers, whatever their owner). Any other dialog is
     * a Swing surface the native HUD should replace; it is logged at error level and not raised.
     */
    static boolean allowedOverBattle(Dialog dialog) {
        boolean allowed = approved(dialog) || insideApproved(dialog);
        if (!allowed) {
            LOGGER.error("A Swing dialog outside the allowlist opened over the battle window: {} \"{}\"",
                  dialog.getClass().getName(), dialog.getTitle());
        }
        return allowed;
    }

    /**
     * A Swing surface the user kept over the battle window: one of {@link #SWING_DIALOGS}; a file chooser (user item
     * 21); the game master's choice of the player to reinforce from a file (a modal player list; U2, GM tools); or a
     * message box the user kept, known by its title, as it has no class of its own (see
     * {@link #approvedMessageBoxes}).
     */
    private static boolean approved(Dialog dialog) {
        return SWING_DIALOGS.stream().anyMatch(type -> type.isInstance(dialog)) || shows(dialog, JFileChooser.class)
              || dialog instanceof megamek.client.ui.dialogs.PlayerListDialog players && players.isModal()
              || shows(dialog, JOptionPane.class)
                    && approvedMessageBoxes().stream().anyMatch(title -> title.equals(dialog.getTitle()));
    }

    /** Whether the dialog opens inside a kept surface: owned by one, or while one shows modally. */
    private static boolean insideApproved(Dialog dialog) {
        for (Window owner = dialog.getOwner(); owner != null; owner = owner.getOwner()) {
            if (owner instanceof Dialog parent && approved(parent)) {
                return true;
            }
        }
        return List.of(Window.getWindows()).stream().anyMatch(window -> window != dialog && window.isShowing()
              && window instanceof Dialog open && open.isModal() && approved(open));
    }

    /** The titles of the message boxes the user kept in Swing, in the client's language. */
    private static List<String> approvedMessageBoxes() {
        megamek.client.ui.BugReportMessages bugReport = new megamek.client.ui.BugReportMessages();
        return List.of(
              // D11: the exit's save question
              Messages.getString("ClientGUI.gameSaveFirst"),
              // R6: About
              Messages.getString("about.title", megamek.MMConstants.PROJECT_NAME),
              // R6: the bug report, and the result of its package (a file the player saves, user item 21)
              bugReport.get("title"), bugReport.get("package.result.title"),
              // User item 21: the version check of the unit file a game master reinforces from
              Messages.getString("MULParser.versionWarning.newerVersion.title"),
              Messages.getString("MULParser.versionWarning.olderVersion.title"));
    }

    /** Whether a Swing dialog shows a component of the type, as a message box or a file chooser does. */
    private static boolean shows(Dialog dialog, Class<?> type) {
        return dialog instanceof JDialog swing && List.of(swing.getContentPane().getComponents()).stream()
              .anyMatch(type::isInstance);
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
            if (classicWindow.isShowing() && afterClose == null) {
                classicWindow.toFront();
                classicWindow.requestFocus();
            }
        }
        if (editor != null) {
            restoreClassic = classicWindow != null && classicWindow.isDisplayable();
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

    /** EDT: the View menu and its shortcut open the native ruler while this client's board is native. */
    public static boolean showRuler(ClientGUI gui) {
        if (!isActiveFor(gui)) { return false; }
        GpuBoardSource current = sourceFor(gui);
        if (current != null) {
            current.los().model().open();
            current.refresh();
        }
        return true;
    }

    /** EDT: client ruler commands use the native model while this client's native board is active. */
    public static boolean updateRuler(ClientGUI gui, int boardId,
          java.util.function.Consumer<RulerModel> action) {
        if (!isActiveFor(gui)) { return false; }
        GpuBoardSource current = sourceFor(gui);
        if (current != null && current.currentView().getBoardId() == boardId) {
            action.accept(current.los().model());
            current.refresh();
        }
        return true;
    }

    /**
     * EDT: raises this client's presented native window, shows the request there and waits for its answer in a nested
     * Swing event loop, as a modal JDialog does. Returns null when the caller must show its Swing dialog instead: off
     * the EDT, without a presented, open window for this client, or while the caller holds this class's monitor (the
     * synchronized open() shows Swing prompts), so a native wait never starts with it held. Not synchronized: the GL
     * thread never waits on the EDT. Package-private: the client's chokepoints use {@link #route}.
     */
    static DialogAnswer ask(ClientGUI gui, DialogRequest request) {
        GpuBoardWindow window = active;
        if (!SwingUtilities.isEventDispatchThread() || window == null || window.gui != gui || !window.asksPrompts()
              || Thread.holdsLock(GpuBoardWindow.class)) {
            return null;
        }
        // Like dialogListener's toFront() for Swing prompts: a prompt during another application's use is not hidden.
        window.focus(false);
        return window.source instanceof GpuBoardSource game ? game.ask(request) : null;
    }

    /**
     * Whether this window asks its client's prompts itself: presented, open and with its board source. Otherwise
     * {@link #ask} declines and the prompts show their Swing dialogs, which the dialog listener then raises.
     */
    private boolean asksPrompts() {
        return gui != null && presented && source != null && !closing;
    }

    /**
     * EDT: the entry point of the client's prompt chokepoints. Like {@link #ask}, but null (use Swing) while this
     * client's native window does not draw dialogs.
     */
    public static DialogAnswer route(ClientGUI gui, DialogRequest request) {
        return drawsDialogsFor(gui) ? ask(gui, request) : null;
    }

    /**
     * Any thread, no monitor: whether this client's native window is presented, so its HUD draws the client's dialogs,
     * which {@link #route} requires. A chokepoint whose request is costly to build checks it first, so its Swing
     * dialog stays as before. Auxiliary windows use {@link #isActiveFor} instead, so they stay hidden during startup.
     */
    public static boolean drawsDialogsFor(ClientGUI gui) {
        GpuBoardWindow window = active;
        return window != null && window.gui == gui && window.presented;
    }

    /** The board's one HTML-to-text rule, for prompt texts that Swing renders as HTML. */
    public static String plainText(String html) {
        return GpuBoardActions.plainText(html);
    }

    /**
     * Any thread: hands a toast of this client to its native window's toast stack. {@link ClientGUI#addToast}, the
     * client's one toast entry point, calls it after applying the player's toast settings.
     */
    public static void toast(ClientGUI gui, ToastLevel level, String text, @Nullable Entity entity) {
        GpuBoardSource current = sourceFor(gui);
        if (current != null) {
            current.toasts().add(level, text, entity);
        }
    }

    /** Any thread, no monitor: true while this client's native window shows a dialog; hotkeys and stories wait. */
    public static boolean dialogPendingFor(AbstractClientGUI gui) {
        GpuBoardSource current = sourceFor(gui);
        return current != null && current.dialog() != null;
    }

    /** Any thread, no monitor: the board source of this client's native window, or null without one. */
    private static @Nullable GpuBoardSource sourceFor(AbstractClientGUI gui) {
        GpuBoardWindow window = active;
        return window == null || window.gui != gui || !(window.source instanceof GpuBoardSource game) ? null : game;
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

    public static synchronized void closeFor(BoardClientState view) {
        if (active != null && (active.initialView == view || active.source instanceof GpuBoardSource gameSource && gameSource.currentView() == view)) {
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
            JOptionPane.showMessageDialog(classicWindow, failureMessage("BoardEditor.edit3DUnavailable", failure),
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
        int choice = JOptionPane.showOptionDialog(classicWindow, failureMessage("GpuBoard.unavailable", failure),
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
        JOptionPane.showMessageDialog(owner, failureMessage("GpuBoard.previewUnavailable", failure),
              Messages.getString("GpuBoard.preview"), JOptionPane.ERROR_MESSAGE);
    }

    private static String failureMessage(String key, Throwable failure) {
        String message = Messages.getString(key);
        if (failure.getMessage() != null) { message += "\n\n" + failure.getMessage(); }
        if (GpuGraphicsCard.applied() != GpuGraphicsCard.SYSTEM) {
            message += "\n\n" + Messages.getString("GpuBoard.graphicsStartupHint", GpuGraphicsCard.applied().name());
        }
        return message;
    }

    public static synchronized void cameraCommand(ClientGUI gui, megamek.client.ui.util.KeyCommandBind command) {
        if (active != null && active.gui == gui && active.application != null) {
            active.application.postRunnable(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).cameraCommand(command));
        }
    }

}
