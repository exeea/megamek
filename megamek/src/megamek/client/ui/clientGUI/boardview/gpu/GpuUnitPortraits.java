/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import megamek.client.ui.Messages;
import megamek.client.ui.tileset.MMStaticDirectoryManager;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.annotations.Nullable;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;

/**
 * Draws single units into pictures for Swing: the rotatable model in the unit readout.
 *
 * <p>The model view is for the lobby and unit selection only (see {@link #availability(Entity)}). During a game the
 * board already shows every unit as a model, and drawing on the board's own GPU thread would hold up its frames
 * while the user drags. So the drawing happens in a small hidden window of our own, started on the first request and
 * stopped again after a minute without requests, or at once when a board window is about to open
 * ({@link #releaseForBoard()}), because libGDX allows only one application per program.</p>
 *
 * <p>Only the latest request counts: while the user drags the model round, older angles that have not been drawn yet
 * are skipped. Everything public here is called on the Swing thread; the answer also arrives on the Swing thread.</p>
 */
public final class GpuUnitPortraits {

    private static final MMLogger LOGGER = MMLogger.create(GpuUnitPortraits.class);

    /** The hidden window stops after this long without a request, so an idle readout holds no GPU resources. */
    private static final int IDLE_STOP_MILLIS = 60_000;

    /** A request not answered within this time is sent again, in case it arrived while the hidden window closed. */
    private static final int RETRY_MILLIS = 1_500;

    /** How long a board window waits for the hidden window to close before it starts its own. */
    private static final int STOP_WAIT_MILLIS = 3_000;

    /**
     * How often the idle hidden window checks for work, and the most pictures it draws per second. Each request also
     * wakes it at once, so a drag is not held to a fixed frame rate; this only bounds the loop.
     */
    private static final int HIDDEN_WINDOW_FPS = 120;

    /** Whether the model view can be used for a unit right now, and if not, why not. */
    public enum Availability {
        /** The model can be shown. */
        AVAILABLE,
        /** Unit models are switched off in this build. */
        MODELS_DISABLED,
        /** The unit is part of a game in progress; the model view is for the lobby only. */
        IN_GAME,
        /** A 3D board window is open (a game or a map preview), which owns the only libGDX application. */
        BOARD_OPEN,
        /** The mekset lists no model for the unit. */
        NO_MODEL
    }

    /**
     * A unit captured on the Swing thread, with everything the GPU thread needs to draw it, so the GPU thread never
     * reads the live {@link Entity}. A new capture is needed whenever the unit itself changes (loadout, damage,
     * camouflage); turning the camera reuses it.
     */
    public static final class Subject {
        private final BoardScene.UnitModel selection;
        private final String unitName;

        private Subject(BoardScene.UnitModel selection, String unitName) {
            this.selection = selection;
            this.unitName = unitName;
        }

        BoardScene.UnitModel selection() {
            return selection;
        }

        /**
         * @return The unit's short name, for log messages
         */
        public String unitName() {
            return unitName;
        }
    }

    /**
     * One picture to draw.
     *
     * @param subject       The unit
     * @param angle         Where the camera stands
     * @param zoom          How close the camera stands
     * @param width         The picture's width in pixels
     * @param height        The picture's height in pixels
     * @param backgroundRgb The colour behind the unit, as {@code 0xRRGGBB}, normally the panel's own background
     * @param sequence      Increases with every request, so a late answer to an older request can be recognised
     */
    record Request(Subject subject, UnitPortraitAngle angle, UnitPortraitZoom zoom, int width, int height,
          int backgroundRgb, long sequence) { }

    /**
     * The answer to a request.
     *
     * @param sequence The request's {@link Request#sequence()}
     * @param image    The picture, or {@code null} if the unit could not be drawn (the log says why)
     */
    public record Result(long sequence, @Nullable BufferedImage image) { }

    private record Job(Request request, Consumer<Result> receiver) { }

    private static final AtomicLong nextSequence = new AtomicLong();
    private static final AtomicReference<Job> pending = new AtomicReference<>();

    private static final Timer retryTimer = new Timer(RETRY_MILLIS, event -> retry());
    private static final Timer idleTimer = new Timer(IDLE_STOP_MILLIS, event -> stopHiddenWindow(0));

    static {
        retryTimer.setRepeats(false);
        idleTimer.setRepeats(false);
    }

    private static Thread hiddenWindowThread;
    private static volatile Lwjgl3Application hiddenWindow;
    private static volatile boolean hiddenWindowStopRequested;
    /** Set once the hidden window has failed to start, so a broken GPU driver is not retried on every mouse drag. */
    private static volatile boolean hiddenWindowFailed;

    /**
     * The renderer and the hidden window whose GPU thread created it. A renderer belongs to that window's GL context,
     * so a restarted window makes a new one.
     */
    private static volatile Object rendererOwner;
    private static volatile GpuUnitPortraitRenderer renderer;

    private GpuUnitPortraits() { }

    /**
     * @return {@code true} if units can be drawn as models at all in this build
     */
    public static boolean isAvailable() {
        return GpuUnitModels.ENABLED;
    }

    /**
     * Whether the model view can show a unit right now. Cheap: it reads only the unit's game phase and the mekset,
     * so it can run every time the readout changes unit. Call on the Swing thread.
     *
     * @param entity The unit, or {@code null}
     *
     * @return {@link Availability#AVAILABLE}, or the reason the model view cannot be used
     */
    public static Availability availability(@Nullable Entity entity) {
        if (entity == null) {
            return Availability.NO_MODEL;
        }
        Availability availability = availability(isAvailable(), isInGame(entity), GpuBoardWindow.sessionActive(),
              () -> hasModel(entity));
        if (availability != Availability.AVAILABLE) {
            LOGGER.debug("[UnitPortrait] {}: model view not available: {}", entity.getShortName(), availability);
        }
        return availability;
    }

    /**
     * The rule behind {@link #availability(Entity)}, in the order the reasons are reported.
     *
     * @param modelsEnabled {@code true} if unit models are switched on in this build
     * @param inGame        {@code true} if the unit belongs to a game past the lobby
     * @param boardOpen     {@code true} while a 3D board window exists
     * @param hasModel      Looks up whether the mekset lists a model; only asked when everything else allows it
     *
     * @return Whether the model view can be used, and if not, why not
     */
    static Availability availability(boolean modelsEnabled, boolean inGame, boolean boardOpen,
          BooleanSupplier hasModel) {
        if (!modelsEnabled) {
            return Availability.MODELS_DISABLED;
        }
        if (inGame) {
            return Availability.IN_GAME;
        }
        if (boardOpen) {
            return Availability.BOARD_OPEN;
        }
        return hasModel.getAsBoolean() ? Availability.AVAILABLE : Availability.NO_MODEL;
    }

    /**
     * @return {@code true} if the unit belongs to a game that has left the lobby. Units from the unit selector, and
     *       units in MekHQ outside a battle, belong to no game or to one still in its unknown starting phase.
     */
    static boolean isInGame(Entity entity) {
        Game game = entity.getGame();
        if (game == null) {
            return false;
        }
        GamePhase phase = game.getPhase();
        if (phase == null) {
            return false;
        }
        boolean isBeforeTheGame = phase.isUnknown() || phase.isLounge();
        return !isBeforeTheGame;
    }

    private static boolean hasModel(Entity entity) {
        MekTileset tileset = MMStaticDirectoryManager.getMekTileset();
        if (tileset == null) {
            LOGGER.warn("[UnitPortrait] {}: the unit tileset could not be loaded, so no model can be chosen",
                  entity.getShortName());
            return false;
        }
        try {
            boolean hasModel = tileset.modelFor(entity, -1) != null;
            if (!hasModel) {
                LOGGER.debug("[UnitPortrait] {}: the mekset lists no model for this unit", entity.getShortName());
            }
            return hasModel;
        } catch (RuntimeException exception) {
            LOGGER.warn("[UnitPortrait] {}: could not look up the unit's model", entity.getShortName(), exception);
            return false;
        }
    }

    /**
     * Captures a unit for drawing. Call on the Swing thread, again whenever the unit has changed.
     *
     * @param entity The unit, or {@code null}
     *
     * @return The captured unit, or {@code null} if it has no model to draw; the log says why
     */
    public static @Nullable Subject capture(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }
        if (!isAvailable()) {
            LOGGER.debug("[UnitPortrait] {}: unit models are switched off in this build", entity.getShortName());
            return null;
        }
        MekTileset tileset = MMStaticDirectoryManager.getMekTileset();
        if (tileset == null) {
            LOGGER.warn("[UnitPortrait] {}: the unit tileset could not be loaded, so no model can be chosen",
                  entity.getShortName());
            return null;
        }
        try {
            BoardScene.UnitModel selection = UnitModelSelection.capture(entity, -1, false, tileset);
            if (selection == null) {
                LOGGER.debug("[UnitPortrait] {}: the mekset lists no model for this unit", entity.getShortName());
                return null;
            }
            return new Subject(new UnitCamouflage().resolve(selection), entity.getShortName());
        } catch (RuntimeException exception) {
            // A unit the capture cannot read must only lose its model view, never break the readout.
            LOGGER.warn("[UnitPortrait] {}: could not capture the unit for its model", entity.getShortName(),
                  exception);
            return null;
        }
    }

    /**
     * Asks for a picture. Call on the Swing thread. The answer arrives later, also on the Swing thread; a request made
     * before it arrives replaces this one.
     *
     * @param subject       The unit, from {@link #capture(Entity)}
     * @param angle         Where the camera stands
     * @param zoom          How close the camera stands
     * @param width         The picture's width in pixels, at least 1
     * @param height        The picture's height in pixels, at least 1
     * @param backgroundRgb The colour behind the unit, as {@code 0xRRGGBB}
     * @param receiver      Receives the answer
     *
     * @return The request's sequence number, see {@link Result#sequence()}
     */
    public static long request(Subject subject, UnitPortraitAngle angle, UnitPortraitZoom zoom, int width, int height,
          int backgroundRgb, Consumer<Result> receiver) {
        long sequence = nextSequence.incrementAndGet();
        Request request = new Request(subject, angle, zoom, Math.max(1, width), Math.max(1, height),
              backgroundRgb & 0xFFFFFF, sequence);
        pending.set(new Job(request, receiver));
        idleTimer.restart();
        dispatch();
        return sequence;
    }

    /**
     * Closes the hidden window, if it runs, and waits briefly for it. A board window calls this before it starts,
     * because libGDX allows only one application at a time. Call on the Swing thread.
     */
    public static void releaseForBoard() {
        if (isHiddenWindowRunning()) {
            LOGGER.info("[UnitPortrait] A board window is opening; closing the hidden model window first");
            stopHiddenWindow(STOP_WAIT_MILLIS);
        }
    }

    private static void dispatch() {
        if (GpuBoardWindow.sessionActive()) {
            // A readout opened in the lobby can stay open into the game; it gets no picture once the board opens.
            LOGGER.debug("[UnitPortrait] A board window is open; the model view is for the lobby only");
            answerPendingWithNothing();
            return;
        }
        startHiddenWindow();
        // Wake the window now rather than at its next loop: a drag feels smooth only if each angle is drawn at once.
        Lwjgl3Application application = hiddenWindow;
        if (application != null) {
            application.postRunnable(GpuUnitPortraits::renderPending);
        }
        retryTimer.restart();
    }

    /** Sends an unanswered request again, for example one that arrived while the hidden window was closing. */
    private static void retry() {
        if (pending.get() != null) {
            LOGGER.debug("[UnitPortrait] Request not answered within {} ms; sending it again", RETRY_MILLIS);
            dispatch();
        }
    }

    /** Draws the latest request, if there is one. Runs on the hidden window's GPU thread. */
    private static void renderPending() {
        Job job = pending.getAndSet(null);
        if (job == null) {
            return;
        }
        BufferedImage image = null;
        long start = System.nanoTime();
        try {
            image = currentRenderer().render(job.request());
        } catch (RuntimeException exception) {
            LOGGER.error("[UnitPortrait] {}: drawing the model failed", job.request().subject().unitName(), exception);
        }
        GpuUnitPortraitRenderer.Timings timings = currentRenderer().lastTimings();
        LOGGER.debug("[UnitPortrait] {}: request {} drawn at {}x{} in {} ms (draw {} / read back {} / shrink {} ms)",
              job.request().subject().unitName(), job.request().sequence(), job.request().width(),
              job.request().height(), (System.nanoTime() - start) / 1_000_000, timings.drawNanos() / 1_000_000,
              timings.readNanos() / 1_000_000, timings.shrinkNanos() / 1_000_000);
        Result result = new Result(job.request().sequence(), image);
        SwingUtilities.invokeLater(() -> {
            if (pending.get() == null) {
                retryTimer.stop();
            }
            job.receiver().accept(result);
        });
    }

    /**
     * @return The renderer for the hidden window running on this GPU thread. A renderer belongs to the GL context it
     *       was made in; once that window has closed its context is gone, so a new one is made rather than reused.
     */
    private static GpuUnitPortraitRenderer currentRenderer() {
        if ((renderer == null) || (rendererOwner != Gdx.app)) {
            renderer = new GpuUnitPortraitRenderer();
            rendererOwner = Gdx.app;
        }
        return renderer;
    }

    /** Tells the waiting viewer that its unit cannot be drawn, so it shows a message instead of waiting forever. */
    private static void answerPendingWithNothing() {
        Job job = pending.getAndSet(null);
        if (job != null) {
            retryTimer.stop();
            job.receiver().accept(new Result(job.request().sequence(), null));
        }
    }

    private static boolean isHiddenWindowRunning() {
        return (hiddenWindowThread != null) && hiddenWindowThread.isAlive();
    }

    private static void startHiddenWindow() {
        if (isHiddenWindowRunning()) {
            return;
        }
        if (hiddenWindowFailed) {
            answerPendingWithNothing();
            return;
        }
        LOGGER.info("[UnitPortrait] Starting the hidden model window");
        hiddenWindowStopRequested = false;
        hiddenWindowThread = new Thread(GpuUnitPortraits::runHiddenWindow, "MegaMek-GPU-unit-portrait");
        hiddenWindowThread.setDaemon(true);
        hiddenWindowThread.start();
    }

    /**
     * @param waitMillis How long to wait for the window to close; {@code 0} to not wait
     */
    private static void stopHiddenWindow(long waitMillis) {
        Thread thread = hiddenWindowThread;
        if ((thread == null) || !thread.isAlive()) {
            return;
        }
        LOGGER.debug("[UnitPortrait] Stopping the hidden model window");
        hiddenWindowStopRequested = true;
        Lwjgl3Application application = hiddenWindow;
        if (application != null) {
            application.exit();
        }
        if (waitMillis <= 0) {
            return;
        }
        try {
            thread.join(waitMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOGGER.warn("[UnitPortrait] The hidden model window did not close within {} ms", waitMillis);
        }
    }

    private static void runHiddenWindow() {
        try {
            Lwjgl3ApplicationConfiguration configuration = GpuBoardWindow.configuration(false);
            configuration.setTitle(Messages.getString("GpuUnitPortraits.hiddenWindowTitle"));
            configuration.setWindowSizeLimits(-1, -1, -1, -1);
            configuration.setWindowedMode(64, 64);
            configuration.useVsync(false);
            configuration.setForegroundFPS(HIDDEN_WINDOW_FPS);
            configuration.setIdleFPS(HIDDEN_WINDOW_FPS);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    hiddenWindow = (Lwjgl3Application) Gdx.app;
                    // The window draws nothing of its own; it only runs when a request wakes it.
                    Gdx.graphics.setContinuousRendering(false);
                    if (hiddenWindowStopRequested) {
                        Gdx.app.exit();
                        return;
                    }
                    LOGGER.debug("[UnitPortrait] Hidden model window ready");
                    // The request that started the window came before there was a window to wake; draw it now.
                    renderPending();
                }

                @Override
                public void render() {
                    if (hiddenWindowStopRequested) {
                        Gdx.app.exit();
                        return;
                    }
                    renderPending();
                }

                @Override
                public void dispose() {
                    // The context is still current here, the only moment the renderer's GPU memory can be freed.
                    if ((renderer != null) && (rendererOwner == Gdx.app)) {
                        renderer.dispose();
                        renderer = null;
                        rendererOwner = null;
                    }
                }
            }, configuration);
        } catch (RuntimeException | LinkageError failure) {
            hiddenWindowFailed = true;
            LOGGER.error("[UnitPortrait] The hidden model window could not start; models stay unavailable until"
                  + " MegaMek restarts", failure);
            SwingUtilities.invokeLater(GpuUnitPortraits::answerPendingWithNothing);
        } finally {
            hiddenWindow = null;
            LOGGER.debug("[UnitPortrait] Hidden model window closed");
        }
    }
}
