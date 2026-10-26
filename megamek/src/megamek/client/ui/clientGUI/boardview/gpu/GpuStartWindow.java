/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.AWTEvent;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Window;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.utils.Os;
import com.badlogic.gdx.utils.SharedLibraryLoader;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.util.ScreenFit;
import megamek.common.game.IGame;
import megamek.common.scenario.Scenario;
import megamek.logging.MMLogger;
import org.lwjgl.glfw.GLFW;

/**
 * MegaMek's start screen in its own native (libGDX) window: the greeting, the art and the main menu. The Swing main
 * frame stays hidden and owns the forms the menu opens, which this window adopts natively so they stay above it. Only
 * one native window may run at a time (GLFW is process-wide), so the start window closes before a board, the map
 * editor or the lobby opens, and reopens, without the greeting, when they close. There is no Swing fallback: if
 * OpenGL cannot start, MegaMek says so and exits.
 */
public final class GpuStartWindow {
    private static final MMLogger LOGGER = MMLogger.create(GpuStartWindow.class);
    /** Frames between reads of the window's bounds; a few times a second is plenty to follow a move or resize. */
    private static final int BOUNDS_POLL_FRAMES = 15;

    /** What the start screen asks MegaMek to do; each runs on the Swing thread. */
    public enum Action {
        QUICK_LOAD, MAP_EDITOR, OPEN_MAP, UNIT_BROWSER,
        RANDOM_ARMY, SKIN_EDITOR, SETTINGS, HELP_CONTENTS, HELP_SKINNING, REPORT_BUG, COPY_SYSTEM_DATA, RESET_PROMPTS,
        ABOUT, QUIT
    }

    /**
     * A new game the Host page asks MegaMek to host, its inputs already checked.
     *
     * @param password   the game's password, or blank for an open game
     * @param metaserver the server browser's URL, or blank when the game is not listed
     */
    public record HostRequest(String playerName, String password, int port, boolean register, String metaserver) { }

    /**
     * A game the Connect page asks MegaMek to join, its inputs already checked.
     *
     * @param bot whether a bot joins, set up in the bot dialog, rather than the player
     * @param sbf whether the game is Strategic BattleForce; there is no SBF bot
     */
    public record ConnectRequest(String playerName, String serverAddress, int port, boolean bot, boolean sbf) {
        public ConnectRequest {
            if (bot && sbf) {
                throw new IllegalArgumentException("A bot can't join a Strategic BattleForce game");
            }
        }
    }

    /**
     * A saved game the Load game page asks MegaMek to host, its inputs already checked.
     *
     * @param host    the server's settings and the save's player the host takes, or the observer's name
     * @param observe whether the host only watches, under a name none of the save's players has
     * @param bots    the save's players a bot played, which a bot takes back; never the host's own player
     */
    public record LoadRequest(File saveGame, HostRequest host, boolean observe, List<String> bots) {
        public LoadRequest {
            Objects.requireNonNull(saveGame);
            Objects.requireNonNull(host);
            bots = List.copyOf(bots);
            if (!observe && bots.contains(host.playerName())) {
                throw new IllegalArgumentException("The host's own player can't get a bot");
            }
        }
    }

    /** Who plays one of a scenario's players. */
    public enum Role {
        /** The host. */
        YOU,
        /** Another person, who connects to the host. */
        HUMAN,
        /** A bot the host starts. */
        BOT
    }

    /**
     * A scenario the briefing asks MegaMek to host, with the game already made from it.
     *
     * @param roles      who plays each of the game's players, in the game's player order
     * @param playerName the host's name: the player they take, or the observer's name when they take none
     * @param password   the game's password, or blank for an open game
     */
    public record ScenarioRequest(Scenario scenario, IGame game, List<Role> roles, String playerName, String password,
          int port) {
        public ScenarioRequest {
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(game);
            roles = List.copyOf(roles);
            if (roles.size() != game.getPlayersList().size()) {
                throw new IllegalArgumentException("Each of the scenario's players needs a role");
            }
            if (roles.stream().filter(role -> role == Role.YOU).count() > 1) {
                throw new IllegalArgumentException("The host takes one player at most");
            }
            if (playerName == null || playerName.isBlank()) {
                throw new IllegalArgumentException("The host needs a name");
            }
        }

        /** Whether the host takes one of the scenario's players rather than watching. */
        public boolean hasSlot() {
            return roles.contains(Role.YOU);
        }
    }

    private static GpuStartWindow open;
    private static JFrame anchor;
    private static Consumer<Action> actions;
    private static Consumer<HostRequest> hosts;
    private static Consumer<ConnectRequest> connects;
    private static Consumer<LoadRequest> loads;
    private static Consumer<ScenarioRequest> scenarios;

    private final boolean greet;
    private final GpuWindowBounds startBounds;
    private final Set<Dialog> modalDialogs = new LinkedHashSet<>();
    private final Map<Dialog, Boolean> dialogOnTop = new HashMap<>();
    private final AWTEventListener dialogListener = this::dialogEvent;
    /** Whether a modal form is open; the GPU thread reads it to send clicks on the window to that form. */
    private volatile boolean modalOpen;
    private volatile Lwjgl3Application application;
    private volatile GpuNativeDialogOwner nativeOwner;
    private volatile boolean closeRequested;
    /** Set once the native window exists; a failure before then means OpenGL itself could not start. */
    private volatile boolean windowCreated;
    private volatile GpuWindowBounds normalBounds;
    private volatile GpuWindowBounds publishedBounds;
    /** The window's bounds now, maximized or not, in GLFW screen coordinates; dialogs centre over them. */
    private volatile Rectangle currentBounds;
    private Runnable afterClose;
    /** EDT: what to run once the intro finishes, and whether it has. */
    private Runnable onSettled;
    private boolean settled;
    private GpuStartScreen screen;
    private int framesSinceBounds;

    private GpuStartWindow(boolean greet) {
        this.greet = greet;
        startBounds = GpuWindowBounds.loadStart(GUIPreferences.getInstance());
        normalBounds = startBounds;
    }

    /**
     * EDT: sets the hidden frame that owns the forms the start screen opens and the handlers of its actions and its
     * Host, Connect, Load game and briefing pages. Call once before {@link #open}; MegaMek's main frame does it even
     * when it starts without the screen (a command-line game), so the screen can open when that game ends.
     */
    public static void install(JFrame owner, Consumer<Action> handler, Consumer<HostRequest> hostHandler,
          Consumer<ConnectRequest> connectHandler, Consumer<LoadRequest> loadHandler,
          Consumer<ScenarioRequest> scenarioHandler) {
        anchor = owner;
        actions = handler;
        hosts = hostHandler;
        connects = connectHandler;
        loads = loadHandler;
        scenarios = scenarioHandler;
    }

    /** EDT: opens the start window, with the greeting when {@code greet}, or brings the open one to the front. */
    public static void open(boolean greet) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> open(greet));
            return;
        }
        if (anchor == null) {
            throw new IllegalStateException("Install the start window's owner before opening it");
        }
        if (open != null) {
            open.focus();
            return;
        }
        if (!GpuBoardWindow.yieldNativeWindow(anchor, () -> open(greet))) {
            return;
        }
        GpuStartWindow window = new GpuStartWindow(greet);
        open = window;
        Toolkit.getDefaultToolkit().addAWTEventListener(window.dialogListener,
              AWTEvent.COMPONENT_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK);
        Thread thread = new Thread(window::run, "MegaMek-start-screen");
        thread.setDaemon(true);
        thread.start();
    }

    /** EDT: reopens the start screen, without the greeting, after a game, an editor or a tool closes. */
    public static void reopen() {
        if (anchor != null) {
            open(false);
        }
    }

    /** EDT: true while the start window is open or opening. */
    public static boolean isOpen() {
        return open != null;
    }

    /**
     * EDT: closes the start window and runs {@code then} on the EDT once its native window is gone, so another native
     * window can take GLFW. Returns false, and runs nothing, when it is not open. Runs every {@code then} asked for
     * while it closes, in order: a game's board window waits on it as the game's launch closes it too.
     */
    public static boolean closeThen(Runnable then) {
        if (open == null) {
            return false;
        }
        Runnable earlier = open.afterClose;
        open.afterClose = earlier == null ? then : () -> {
            earlier.run();
            then.run();
        };
        open.requestClose();
        return true;
    }

    /** EDT: reopens the start screen in place of showing {@code window} when it is the start screen's hidden owner. */
    static boolean reopenFor(Window window) {
        if (window == null || window != anchor) {
            return false;
        }
        reopen();
        return true;
    }

    /** EDT: lays the open screen out again, as after the GUI scale changes. */
    public static void refresh() {
        GpuStartWindow window = open;
        Lwjgl3Application app = window == null ? null : window.application;
        if (app != null) {
            app.postRunnable(() -> {
                if (window.screen != null) {
                    window.screen.rescale();
                }
            });
        }
    }

    /**
     * EDT: the start window's last normal bounds in Swing screen coordinates, for a window that replaces it to open in
     * the same place, or null when it was never placed (it opened centred).
     */
    public static Rectangle lastSwingBounds() {
        GpuWindowBounds bounds = GpuWindowBounds.loadStart(GUIPreferences.getInstance());
        return bounds.centred() ? null : toSwing(bounds.rectangle());
    }

    /** EDT: true when the start window was last maximized. */
    public static boolean lastMaximized() {
        return GUIPreferences.getInstance().getStartWindowMaximized();
    }

    /**
     * GLFW screen coordinates are device pixels on Windows, while Swing's are scaled by the display's scale; macOS and
     * X11 share theirs.
     */
    private static Rectangle toSwing(Rectangle glfw) {
        double scale = swingScale();
        return new Rectangle((int) Math.round(glfw.x / scale), (int) Math.round(glfw.y / scale),
              (int) Math.round(glfw.width / scale), (int) Math.round(glfw.height / scale));
    }

    private static double swingScale() {
        if (SharedLibraryLoader.os != Os.Windows) {
            return 1;
        }
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
                  .getDefaultTransform().getScaleX();
        } catch (HeadlessException headless) {
            return 1;
        }
    }

    private void run() {
        Throwable failure = null;
        try {
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(true);
            configuration.setTitle(Messages.getString("StartScreen.title"));
            configuration.setWindowedMode(startBounds.width(), startBounds.height());
            if (!startBounds.centred()) {
                configuration.setWindowPosition(startBounds.x(), startBounds.y());
            }
            configuration.setMaximized(startBounds.maximized());
            // The hex reveal draws through a stencil mask
            configuration.setBackBufferConfig(8, 8, 8, 8, 24, 8, 4);
            configuration.setWindowListener(new Lwjgl3WindowAdapter() {
                @Override
                public void created(Lwjgl3Window window) {
                    windowCreated = true;
                    GpuGlsl.detect();
                    GpuNativeDialogOwner.Win32.useWindowIconsForClass(window.getWindowHandle());
                    fitOnScreen(window);
                    nativeOwner = GpuNativeDialogOwner.forGlfwWindow(window.getWindowHandle());
                    // Forms raised while the window was opening floated on top; they can belong to it now
                    SwingUtilities.invokeLater(GpuStartWindow.this::adoptFloating);
                }

                @Override
                public void focusGained() {
                    if (modalOpen) {
                        SwingUtilities.invokeLater(GpuStartWindow.this::frontModal);
                    }
                }

                @Override
                public boolean closeRequested() {
                    if (modalOpen) {
                        SwingUtilities.invokeLater(GpuStartWindow.this::frontModal);
                    } else {
                        dispatch(Action.QUIT);
                    }
                    return false;
                }
            });
            screen = new GpuStartScreen(this, greet, GUIPreferences.getInstance());
            new Lwjgl3Application(screen, configuration);
        } catch (RuntimeException | LinkageError error) {
            failure = error;
            try {
                String detail = GpuGlsl.glfwError();
                if (!detail.isEmpty()) {
                    failure = new com.badlogic.gdx.utils.GdxRuntimeException(detail, error);
                }
            } catch (RuntimeException | LinkageError diagnosticFailure) {
                error.addSuppressed(diagnosticFailure);
            }
        } finally {
            application = null;
            Throwable ended = failure;
            SwingUtilities.invokeLater(() -> finished(ended));
        }
    }

    private void finished(Throwable failure) {
        Toolkit.getDefaultToolkit().removeAWTEventListener(dialogListener);
        modalDialogs.clear();
        modalOpen = false;
        dialogOnTop.forEach(Dialog::setAlwaysOnTop);
        dialogOnTop.clear();
        if (open == this) {
            open = null;
        }
        if (failure != null && !closeRequested) {
            LOGGER.error(failure, "The start screen's native window failed");
            String key = windowCreated ? "StartScreen.failure" : "StartScreen.glFailure";
            JOptionPane.showMessageDialog(null, Messages.getString(key, String.valueOf(failure)),
                  Messages.getString("StartScreen.title"), JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        Runnable then = afterClose;
        afterClose = null;
        if (then != null) {
            then.run();
        }
    }

    private void requestClose() {
        closeRequested = true;
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(app::exit);
        }
    }

    private void focus() {
        Lwjgl3Application app = application;
        if (app != null) {
            app.postRunnable(() -> {
                var window = ((Lwjgl3Graphics) app.getGraphics()).getWindow();
                window.restoreWindow();
                window.focusWindow();
            });
        }
    }

    /** GPU thread: the screen's first frame. */
    void started() {
        application = (Lwjgl3Application) Gdx.app;
        if (closeRequested) {
            Gdx.app.exit();
        }
    }

    /** Any thread: runs a menu action on the Swing thread. */
    void dispatch(Action action) {
        Consumer<Action> handler = actions;
        if (handler != null) {
            SwingUtilities.invokeLater(() -> handler.accept(action));
        }
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the game the Host page describes. */
    void host(HostRequest request) {
        Consumer<HostRequest> handler = hosts;
        if (handler != null) {
            SwingUtilities.invokeLater(() -> handler.accept(request));
        }
    }

    /** Any thread: asks MegaMek, on the Swing thread, to join the game the Connect page describes. */
    void connect(ConnectRequest request) {
        Consumer<ConnectRequest> handler = connects;
        if (handler != null) {
            SwingUtilities.invokeLater(() -> handler.accept(request));
        }
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the saved game the Load game page describes. */
    void load(LoadRequest request) {
        Consumer<LoadRequest> handler = loads;
        if (handler != null) {
            SwingUtilities.invokeLater(() -> handler.accept(request));
        }
    }

    /** Any thread: asks MegaMek, on the Swing thread, to host the scenario the briefing describes. */
    void scenario(ScenarioRequest request) {
        Consumer<ScenarioRequest> handler = scenarios;
        if (handler != null) {
            SwingUtilities.invokeLater(() -> handler.accept(request));
        }
    }

    /**
     * Any thread: shows a file chooser, a form of the hidden frame and so above this window, at {@code directory}, and
     * hands the file the player opens to {@code chosen} on the GPU thread; nothing when they cancel.
     */
    void chooseFile(File directory, String title, FileFilter filter, Consumer<File> chosen) {
        SwingUtilities.invokeLater(() -> {
            JFileChooser chooser = new JFileChooser(directory);
            chooser.setDialogTitle(title);
            chooser.setFileFilter(filter);
            if (chooser.showOpenDialog(anchor) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
                return;
            }
            File file = chooser.getSelectedFile();
            Lwjgl3Application app = application;
            if (app != null) {
                app.postRunnable(() -> chosen.accept(file));
            }
        });
    }

    /**
     * Any thread: runs {@code work} on the Swing thread with the hidden frame that owns the screen's forms, so a form it
     * opens shows above this window, then hands its result to {@code done} on the GPU thread.
     */
    <T> void onSwing(Function<JFrame, T> work, Consumer<T> done) {
        SwingUtilities.invokeLater(() -> {
            T result = work.apply(anchor);
            Lwjgl3Application app = application;
            if (app != null) {
                app.postRunnable(() -> done.accept(result));
            }
        });
    }

    /**
     * EDT: the scenario the briefing asked for didn't start; {@code gameUsed} when the server had taken its game by
     * then, so the briefing has to make it again before it can start another.
     */
    public static void startFailed(boolean gameUsed) {
        GpuStartWindow window = open;
        Lwjgl3Application app = window == null ? null : window.application;
        if (app != null) {
            app.postRunnable(() -> {
                if (window.screen != null) {
                    window.screen.startFailed(gameUsed);
                }
            });
        }
    }

    /**
     * Any thread: runs {@code then} on the Swing thread once the open window's intro has finished, so a form doesn't
     * cover the greeting; at once when no window is open.
     */
    public static void whenSettled(Runnable then) {
        if (!SwingUtilities.isEventDispatchThread()) {
            // Queued after a pending open, so it sees the window that open makes
            SwingUtilities.invokeLater(() -> whenSettled(then));
            return;
        }
        GpuStartWindow window = open;
        if (window == null || window.settled) {
            then.run();
        } else {
            window.onSettled = then;
        }
    }

    /** GPU thread: the intro has finished, or was skipped. */
    void settled() {
        SwingUtilities.invokeLater(() -> {
            settled = true;
            Runnable then = onSettled;
            onSettled = null;
            if (then != null) {
                then.run();
            }
        });
    }

    /** GPU thread, every frame: follows the window as the user moves, resizes or maximizes it. */
    void frame() {
        if (++framesSinceBounds >= BOUNDS_POLL_FRAMES) {
            framesSinceBounds = 0;
            trackBounds();
        }
    }

    /** GPU thread, before the window is destroyed: returns every adopted form to its Swing owner. */
    void disposing() {
        GpuNativeDialogOwner owner = nativeOwner;
        if (owner != null) {
            owner.releaseAll();
        }
    }

    /** A window saved on a monitor that has since gone, or partly off screen, is moved into a work area. */
    private void fitOnScreen(Lwjgl3Window window) {
        if (startBounds.centred()) {
            return;
        }
        var fitted = ScreenFit.fit(startBounds.rectangle(), GpuWindowBounds.workAreas());
        if (fitted.equals(startBounds.rectangle())) {
            return;
        }
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

    /** As the board window's: the normal bounds update only while the window is neither maximized nor minimized. */
    private void trackBounds() {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics graphics)) {
            return;
        }
        Lwjgl3Window window = graphics.getWindow();
        if (window == null || window.isIconified() || graphics.isFullscreen()) {
            return;
        }
        long handle = window.getWindowHandle();
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetWindowSize(handle, width, height);
        currentBounds = new Rectangle(window.getPositionX(), window.getPositionY(), width[0], height[0]);
        boolean maximized = GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        GpuWindowBounds normal = normalBounds;
        if (!maximized) {
            normal = new GpuWindowBounds(window.getPositionX(), window.getPositionY(), width[0], height[0], false);
            normalBounds = normal;
        }
        GpuWindowBounds current = new GpuWindowBounds(normal.x(), normal.y(), normal.width(), normal.height(),
              maximized);
        if (!current.equals(publishedBounds)) {
            publishedBounds = current;
            SwingUtilities.invokeLater(() -> current.saveStart(GUIPreferences.getInstance()));
        }
    }

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
        } else if (id == ComponentEvent.COMPONENT_SHOWN && belongsToStart(dialog)) {
            centre(dialog);
            raise(dialog);
        }
    }

    /**
     * A form of the hidden main frame, a dialog without an owner of its own (which Swing gives its hidden shared frame),
     * or a dialog of either: all would otherwise hide behind the start window.
     */
    private static boolean belongsToStart(Dialog dialog) {
        Frame shared;
        try {
            shared = JOptionPane.getRootFrame();
        } catch (HeadlessException headless) {
            shared = null;
        }
        for (Window current = dialog.getOwner(); current != null; current = current.getOwner()) {
            if (current == anchor || current == shared) {
                return true;
            }
        }
        return dialog.getOwner() == null;
    }

    /** Centres a form of the hidden frame over the start window; the hidden frame would centre it on the screen. */
    private void centre(Dialog dialog) {
        Rectangle bounds = currentBounds;
        if (bounds == null || dialog.getOwner() instanceof Dialog) {
            return;
        }
        Rectangle swing = toSwing(bounds);
        dialog.setLocation(swing.x + (swing.width - dialog.getWidth()) / 2,
              swing.y + (swing.height - dialog.getHeight()) / 2);
    }

    /**
     * Keeps a form above the start window until either closes: owned natively where the platform allows, otherwise
     * floating on top. A modal form blocks the start window's input as it would its Swing owner's.
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
                setStartEnabled(false);
            }
            dialog.toFront();
            return;
        }
        dialogOnTop.putIfAbsent(dialog, dialog.isAlwaysOnTop());
        dialog.setAlwaysOnTop(true);
        dialog.toFront();
    }

    /** EDT, once the native window exists: owns the forms that floated while it opened. */
    private void adoptFloating() {
        if (open != this) {
            return;
        }
        for (Dialog dialog : new HashSet<>(dialogOnTop.keySet())) {
            if (dialog.isShowing()) {
                centre(dialog);
                raise(dialog);
            }
        }
    }

    private void endModal(Dialog dialog) {
        if (modalDialogs.remove(dialog) && modalDialogs.isEmpty()) {
            setStartEnabled(true);
        }
    }

    /** EDT: brings the newest open modal form to the front when the start window is clicked or asked to close. */
    private void frontModal() {
        Dialog newest = null;
        for (Dialog dialog : modalDialogs) {
            if (dialog.isShowing()) {
                newest = dialog;
            }
        }
        if (newest != null) {
            newest.toFront();
        }
    }

    /**
     * Blocks or restores the start window's input. The native window itself stays enabled: Windows picks the window to
     * activate as a modal form hides, before Swing reports it, and a still disabled owner would hand the activation to
     * another application, flashing the start window away until it took the focus back.
     */
    private void setStartEnabled(boolean enabled) {
        modalOpen = !enabled;
        Lwjgl3Application app = application;
        GpuStartScreen startScreen = screen;
        if (app == null || startScreen == null) {
            return;
        }
        app.postRunnable(() -> startScreen.setInputBlocked(!enabled));
    }
}
