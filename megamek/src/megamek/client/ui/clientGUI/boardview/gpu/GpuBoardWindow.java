/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.annotations.Nullable;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

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
          // U2: the ruler's elevation diagram (its window stays hidden under the LOS card, I3)
          megamek.client.ui.clientGUI.boardview.RulerDialog.class,
          // U2: the developer conditions editor behind the Tuning utility
          megamek.client.ui.dialogs.clientDialogs.PlanetaryConditionsDialog.class);
    private final ClientGUI gui;
    private final BoardClientState initialView;
    private final Supplier<JComponent> panel;
    private final Timer startupTimer;
    private volatile GpuBoardSource source;
    private volatile String loadingMessage = Messages.getString("ClientGUI.waitingOnTheServer");
    private final Window classicWindow;
    private final Map<Dialog, Boolean> dialogOnTop = new IdentityHashMap<>();
    /**
     * The classic window hides while the GPU view runs, so a Swing dialog owned by it would open behind the native
     * window. A client dialog that opens while the GPU window cannot ask the client's prompts itself (it starts or
     * closes), or one of {@link #allowedOverBattle} while it can, is raised above it instead.
     */
    private final AWTEventListener dialogListener = event -> {
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

    private GpuBoardWindow(ClientGUI gui, BoardClientState view, Supplier<JComponent> panel) {
        this.gui = gui;
        initialView = view;
        this.panel = panel;
        classicWindow = gui == null ? null : gui.getFrame();
        startupTimer = new Timer(100, event -> initializeSource());
    }

    public static synchronized void open(BoardClientState view, Supplier<JComponent> panel) {
        open(view.getClientgui(), view, panel);
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
            if (active.gui != gui || (gui == null && active.initialView != view)) {
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
        GpuBoardWindow window = new GpuBoardWindow(gui, view, panel);
        try {
            active = window;
            if (gui != null) {
                GUIPreferences.getInstance().setUse3DBoard(true);
                gui.getMenuBar().setBoardView3D(true);
                gui.setMiniReportLocation(false);
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener, AWTEvent.COMPONENT_EVENT_MASK);
            Thread thread = new Thread(window::run, "MegaMek-GPU-board");
            thread.setDaemon(true);
            thread.start();
        } catch (RuntimeException | LinkageError failure) {
            if (active != null) {
                active.restoreDialogPresentation();
            }
            active = null;
            window.reportFailure(failure);
        }
    }

    private void run() {
        Throwable failure = null;
        try {
            // The native window owns startup; its first visible frame begins the entrance animation.
            Lwjgl3ApplicationConfiguration configuration = configuration(false);
            // Fill the desktop work area while keeping the normal title bar and window controls.
            configuration.setDecorated(true);
            configuration.setMaximized(true);
            configuration.setWindowListener(new Lwjgl3WindowAdapter() {
                @Override
                public boolean closeRequested() {
                    requestExit();
                    return false;
                }

                @Override
                public void focusLost() {
                    if (Gdx.app.getApplicationListener() instanceof GpuBattleView battle) {
                        battle.pause();
                    }
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

                @Override
                public void render() {
                    setLoadingMessage(loadingMessage);
                    super.render();
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

    private void present() {
        if (closing) {
            return;
        }
        presented = true;
        focus(true);
        if (classicWindow != null) {
            classicWindow.setVisible(false);
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
        String status = GpuBoardActions.phaseStatus(panel.get()).text();
        loadingMessage = status.isBlank() ? Messages.getString("ClientGUI.waitingOnTheServer") : status;
        BoardClientState view = gui == null ? initialView : gui.getCurrentBoardState().orElse(null);
        if (view == null) {
            return;
        }
        try {
            source = new GpuBoardSource(view, panel);
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

    private void finish(Throwable failure) {
        startupTimer.stop();
        restoreDialogPresentation();
        synchronized (GpuBoardWindow.class) {
            if (active != this) {
                return;
            }
            active = null;
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
        return window.source.ask(request);
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
     * dialog stays as before. While it holds, the HUD's unit card and sheet replace the Unit Display's window
     * ({@code ClientGUI.setUnitDisplayLocation}).
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
        return window == null || window.gui != gui ? null : window.source;
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
        configuration.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public void focusLost() {
                if (Gdx.app != null && Gdx.app.getApplicationListener() instanceof GpuBattleView battle) {
                    battle.pause();
                }
            }
        });
        return configuration;
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
    public static synchronized void cameraCommand(ClientGUI gui, megamek.client.ui.util.KeyCommandBind command) {
        if (active != null && active.gui == gui && active.application != null) {
            active.application.postRunnable(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).cameraCommand(command));
        }
    }

}
