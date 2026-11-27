/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuTestSupport.onSwing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.BorderLayout;
import java.awt.Window;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import megamek.client.Client;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardViewsContainer;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommandBarPanel;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.common.Player;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The Swing cut (I3) in a scripted battle over the real native window. Through every phase, with each auxiliary
 * window's preference switched on and the client's phase rules run, no Swing window of the client shows over the
 * battle window: the HUD stands for the minimap, the players, the rounds in the air, the force display, the bot
 * commands, the Unit Display and the report; a Ctrl measurement shows in the HUD's ruler panel, not in a Swing window.
 * A Swing dialog outside the allowlist is not raised. With the default
 * preferences the minimap keeps the player's choice through the report phases.
 */
@Tag("on-demand")
class GpuSwingCutSmokeTest {
    /** The phases of a round, in order, as the scripted battle plays them. */
    private static final List<GamePhase> ROUND = List.of(GamePhase.INITIATIVE_REPORT, GamePhase.DEPLOYMENT,
          GamePhase.MOVEMENT, GamePhase.MOVEMENT_REPORT, GamePhase.FIRING, GamePhase.FIRING_REPORT,
          GamePhase.PHYSICAL, GamePhase.PHYSICAL_REPORT, GamePhase.END_REPORT);
    /** The preferences that show the client's auxiliary windows, each switched on in every phase. */
    private static final List<String> WINDOWS = List.of(GUIPreferences.MINI_MAP_ENABLED);

    /** The client's Swing frame and its menus. */
    private record Client2d(JFrame frame, CommonMenuBar menus) {
    }

    @AfterEach
    void awaitHostClosed() throws Exception {
        // The next suite's own window must not start while the host's thread still shuts GLFW down
        await(() -> !GpuHost.isOpen());
    }

    @Test
    void aScriptedBattleShowsNoSwingWindowOverTheNativeWindow() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : WINDOWS) {
            saved.put(key, preferences.getString(key));
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientGUI gui = onSwing(() -> client(fixture));
            Client2d client = onSwing(() -> windows(gui, fixture));
            Application previous = Gdx.app;
            try {
                onSwing(() -> {
                    preferences.addPreferenceChangeListener(gui);
                    GpuBoardWindow.open(gui, () -> fixture.panel);
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> onSwing(() -> GpuBoardWindow.drawsDialogsFor(gui)));
                await(() -> onGl(() -> frames() > 5));

                // The minimap stays on through the report phases.
                onSwing(() -> {
                    preferences.setMinimapEnabled(true);
                    return null;
                });
                for (GamePhase phase : List.of(GamePhase.INITIATIVE_REPORT, GamePhase.FIRING_REPORT,
                      GamePhase.END_REPORT)) {
                    enter(fixture, client, gui, phase);
                    assertTrue(preferences.getMinimapEnabled(), phase + ": the player's minimap choice is kept");
                    assertTrue(onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("minimap"))),
                          phase + ": the HUD's minimap shows");
                }

                // Every window wanted in every phase, and a measurement: only the native window.
                Coords from = fixture.entity.getPosition();
                Coords to = new Coords(from.getX(), from.getY() - 3);
                for (GamePhase phase : ROUND) {
                    enter(fixture, client, gui, phase);
                    onSwing(() -> {
                        for (String key : WINDOWS) {
                            preferences.setValue(key, false);
                            preferences.setValue(key, true);
                        }
                        fixture.view.checkLOS(from);
                        fixture.view.checkLOS(to);
                        return null;
                    });
                    settle();
                    assertEquals(List.of(), shown(client),
                          phase + ": no Swing window over the native window, a measurement included");
                    if (phase == GamePhase.MOVEMENT) {
                        onGl(() -> {
                            File directory = new File(System.getProperty("megamek.gpu.screenshots",
                                  "build/gpu-board-review"));
                            assertTrue(directory.isDirectory() || directory.mkdirs());
                            GpuBoardTestUi.capture(new File(directory, "swing-cut-movement.png"));
                            return null;
                        });
                    }
                }

                // A dialog outside the allowlist is logged at error level and not raised; it still opens.
                JDialog probe = onSwing(() -> {
                    JDialog dialog = new JDialog(client.frame(), "Swing cut probe", false);
                    dialog.setSize(160, 90);
                    dialog.setVisible(true);
                    return dialog;
                });
                settle();
                assertTrue(onSwing(probe::isShowing));
                assertFalse(onSwing(probe::isAlwaysOnTop), "A dialog outside the allowlist is not raised");
                onSwing(() -> {
                    probe.dispose();
                    return null;
                });

            } finally {
                onSwing(() -> {
                    preferences.removePreferenceChangeListener(gui);
                    GpuBoardWindow.closeFor(gui);
                    return null;
                });
                await(() -> !GpuHost.isOpen());
                onSwing(() -> {
                    client.frame().dispose();
                    client.menus().die();
                    saved.forEach(preferences::setValue);
                    return null;
                });
            }
        }
    }

    /**
     * EDT: the phase begins as on the client; then the native window draws it.
     */
    private static void enter(GpuBoardFixture fixture, Client2d client, ClientGUI gui, GamePhase phase)
          throws Exception {
        onSwing(() -> {
            fixture.game.setPhase(phase);
            client.menus().setPhase(phase);
            return null;
        });
        settle();
    }

    /** Every AWT window that shows, the client's Swing frame included (the native window is not one). */
    private static List<Window> shown(Client2d client) throws Exception {
        return onSwing(() -> Arrays.stream(Window.getWindows()).filter(Window::isShowing).toList());
    }

    /**
     * A client of the fixture's game whose window presentation is ClientGUI's own: its auxiliary windows' setters,
     * phase rules and preference handling, and the ruler's close. Its board state is a real one over the fixture's
     * board.
     */
    private static ClientGUI client(GpuBoardFixture fixture) throws Exception {
        fixture.source.close();
        Set<String> real = Set.of("preferenceChange", "closeRuler");
        ClientGUI gui = mock(ClientGUI.class, invocation -> real.contains(invocation.getMethod().getName())
              ? invocation.callRealMethod() : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
        Client client = mock(Client.class);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        BoardViewsContainer container = mock(BoardViewsContainer.class);
        set(AbstractClientGUI.class, gui, "boardViewsContainer", container);
        fixture.view.close();
        fixture.view = new BoardClientState(fixture.game, null, gui, 0, null);
        fixture.view.setLocalPlayer(fixture.player);
        doReturn(Optional.of(fixture.view)).when(gui).getCurrentBoardState();
        doReturn(List.of(fixture.view)).when(gui).boardStates();
        doReturn(fixture.view).when(gui).getBoardState();
        doReturn(fixture.view).when(gui).getBoardState(any(BoardLocation.class));
        doReturn(fixture.view).when(gui).getBoardState(any(Entity.class));
        Player bot = new Player(77, "Bot");
        bot.setBot(true);
        fixture.game.addPlayer(bot.getId(), bot);
        return gui;
    }

    /**
     * The client's Swing frame and menus, as ClientGUI builds them.
     */
    private static Client2d windows(ClientGUI gui, GpuBoardFixture fixture) {
        JFrame frame = new JFrame("MegaMek - Swing cut test");
        frame.setSize(960, 700);
        CommonMenuBar menus = CommonMenuBar.getMenuBarForGame();
        frame.setJMenuBar(menus);
        when(gui.getFrame()).thenReturn(frame);
        when(gui.getMenuBar()).thenReturn(menus);
        CommandBarPanel bar = new CommandBarPanel(gui);
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        set(ClientGUI.class, gui, "botCommandsPanel", new BotCommandsPanel(gui.getClient(), null, null, gui));
        set(ClientGUI.class, gui, "commandBarPanel", bar);
        set(ClientGUI.class, gui, "panTop", top);
        UnitDisplayState unit = new UnitDisplayState(gui);
        when(gui.getUnitDisplayState()).thenReturn(unit);
        unit.displayEntity(fixture.entity);
        frame.setVisible(true);
        return new Client2d(frame, menus);
    }

    private static void set(Class<?> owner, Object instance, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /** Lets the EDT and the native window catch up: posted Swing events, then a few frames. */
    private static void settle() throws Exception {
        onSwing(() -> null);
        long before = onGl(() -> frames());
        await(() -> onGl(() -> frames() > before + 3));
        onSwing(() -> null);
    }

    /** GL thread: the shown board's frame count, or -1 until the host has started the board's scene. */
    private static long frames() {
        return GpuHost.sceneListener() instanceof GpuBattleView battle ? battle.frames() : -1;
    }

    private static <T> T onGl(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Gdx.app.postRunnable(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for the native window");
            Thread.sleep(25);
        }
    }
}
