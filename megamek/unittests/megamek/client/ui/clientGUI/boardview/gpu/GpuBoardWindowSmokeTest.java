/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Window;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardViewsContainer;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.overlay.UnitOverviewOverlay;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayPanel;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Report;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Exercises the real Swing/native window handoff and the shared menu through Scene2D input. */
@Tag("on-demand")
class GpuBoardWindowSmokeTest {
    private boolean boardStyle;

    @BeforeEach
    void saveBoardStyle() {
        boardStyle = GUIPreferences.getInstance().getUse3DBoard();
    }

    @AfterEach
    void restoreBoardStyle() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream()
              .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
        GUIPreferences.getInstance().setUse3DBoard(boardStyle);
    }

    @Test
    void nativeGameplayRendersWithoutConstructingAClassicBoard() throws Exception {
        var classic = onSwing(() -> mockConstruction(BoardView.class));
        var panels = onSwing(() -> mockConstruction(BoardViewPanel.class));
        var session = onSwing(GpuGameplayStateTest.Session::create);
        JFrame owner = onSwing(JFrame::new);
        CommonMenuBar menus = onSwing(CommonMenuBar::getMenuBarForGame);
        ClientGUI gui = session.state().getClientgui();
        try {
            Application previous = Gdx.app;
            onSwing(() -> {
                session.source().close();
                when(gui.getFrame()).thenReturn(owner);
                when(gui.getMenuBar()).thenReturn(menus);
                menus.setPhase(GamePhase.MOVEMENT);
                GpuBoardWindow.open(session.state(), gui::getMainPanel);
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() > 3));
            for (boolean isometric : new boolean[] { false, true }) {
                onGl(() -> {
                    GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                    battle.boardCamera.setIsometric(isometric);
                    return null;
                });
                awaitNavigation();
                onGl(() -> {
                    File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(directory.isDirectory() || directory.mkdirs());
                    GpuBoardTestUi.capture(new File(directory, "native-state-" + (isometric ? "iso" : "top") + ".png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    return null;
                });
            }
            onSwing(() -> {
                assertTrue(classic.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertFalse(session.state().isClosed());
                return null;
            });
        } finally {
            onSwing(() -> { GpuBoardWindow.closeFor(gui); return null; });
            await(() -> !GpuBoardWindow.isActiveFor(gui));
            onSwing(() -> {
                session.close();
                owner.dispose();
                menus.die();
                panels.close();
                classic.close();
                return null;
            });
        }
    }
    private record ClientWindow(JFrame frame, CommonMenuBar menus, BoardView view, JMenuItem gpuChoice,
          UnitOverviewOverlay overview) { }

    @Test
    void startsDirectlyInThreeDimensionsWithoutConstructingTheClassicViewport() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture, false));
            try {
                onSwing(() -> {
                    ui.view().centerOn(fixture.entity);
                    assertEquals(fixture.entity.getId(), ui.view().getClientState().getCenterRequest().entityId());
                    assertNull(ui.view().getPanel().getParent());
                    return null;
                });
                openNative(ui);
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    assertNull(ui.view().getPanel().getParent());
                    assertFalse(ui.frame().isVisible());
                    assertTrue(GpuBoardWindow.isActiveFor(ui.view().getClientgui()));
                    GpuBoardWindow.showClassic(ui.view().getClientgui());
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible()));
                onSwing(() -> {
                    verify(ui.view()).getComponent();
                    assertTrue(ui.view().getPanel().isShowing(), "The legacy viewport is created on explicit return");
                    return null;
                });
            } finally {
                onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    ui.view().dispose();
                    GUIPreferences.getInstance().removePreferenceChangeListener(ui.menus());
                    return null;
                });
            }
        }
    }

    /**
     * W1 (unit panel design 14 U4): once the native HUD draws, its switch on, no phase shows the Unit Display's window,
     * however the client's preferences, phase rules or keys ask for it; the panel stays in its hidden dialog with its
     * unit, the firing display's weapon list. Back on the classic board the window shows again.
     */
    @Test
    void theUnitDisplaysWindowStaysHiddenOverTheNativeHudInEveryPhase() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        int location = preferences.getUnitDisplayLocation();
        boolean unitEnabled = preferences.getUnitDisplayEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            ClientGUI gui = ui.view().getClientgui();
            UnitDisplayDialog unit = onSwing(() -> {
                preferences.setUnitDisplayLocation(0);
                preferences.setUnitDisplayEnabled(true);
                UnitDisplayPanel panel = new UnitDisplayPanel(gui);
                UnitDisplayDialog dialog = new UnitDisplayDialog(ui.frame(), gui);
                when(gui.getUnitDisplay()).thenReturn(panel);
                when(gui.getUnitDisplayDialog()).thenReturn(dialog);
                panel.displayEntity(fixture.entity);
                doCallRealMethod().when(gui).setUnitDisplayVisible(anyBoolean());
                doAnswer(invocation -> {
                    // This fixture has no classic split panes; the classic board's request is only observed
                    if (GpuBoardWindow.isActiveFor(gui)) {
                        invocation.callRealMethod();
                    } else {
                        dialog.setVisible(invocation.getArgument(0));
                    }
                    return null;
                }).when(gui).setUnitDisplayLocation(anyBoolean());
                doCallRealMethod().when(gui).maybeShowUnitDisplay();
                doCallRealMethod().when(gui).actionPerformed(any());
                doCallRealMethod().when(gui).preferenceChange(any());
                ui.menus().addActionListener(gui);
                preferences.addPreferenceChangeListener(gui);
                return dialog;
            });
            try {
                openNative(ui);
                await(() -> onSwing(() -> GpuBoardWindow.drawsDialogsFor(gui)));
                for (GamePhase phase : List.of(GamePhase.DEPLOYMENT, GamePhase.MOVEMENT, GamePhase.FIRING,
                      GamePhase.PHYSICAL, GamePhase.FIRING_REPORT, GamePhase.END_REPORT)) {
                    onSwing(() -> {
                        fixture.game.setPhase(phase);
                        ui.menus().setPhase(phase);
                        gui.maybeShowUnitDisplay();
                        gui.refreshAuxiliaryWindows();
                        preferences.setUnitDisplayEnabled(true);
                        return null;
                    });
                    pressShortcut(KeyCommandBind.UNIT_DISPLAY);
                    pressShortcut(KeyCommandBind.UNIT_DISPLAY);
                    onSwing(() -> {
                        assertFalse(Arrays.stream(Window.getWindows())
                              .anyMatch(window -> window instanceof UnitDisplayDialog && window.isShowing()), phase
                              + ": no Unit Display window");
                        assertTrue(SwingUtilities.isDescendingFrom(gui.getUnitDisplay(), unit));
                        assertSame(fixture.entity, gui.getUnitDisplay().getCurrentEntity());
                        return null;
                    });
                }
                onSwing(() -> {
                    GpuBoardWindow.showClassic(gui);
                    return null;
                });
                await(() -> onSwing(unit::isShowing));
            } finally {
                onSwing(() -> {
                    preferences.removePreferenceChangeListener(gui);
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    unit.dispose();
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    preferences.setUnitDisplayLocation(location);
                    preferences.setUnitDisplayEnabled(unitEnabled);
                    return null;
                });
            }
        }
    }

    @Test
    void nativeStartupShowsPhaseMessagesBeforeTheFirstMapArrives() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture, false));
            ClientGUI gui = ui.view().getClientgui();
            AtomicReference<JComponent> phase = new AtomicReference<>();
            try {
                Application previous = Gdx.app;
                onSwing(() -> {
                    phase.set(new StartingScenarioPanel());
                    when(gui.getCurrentBoardState()).thenReturn(Optional.empty());
                    GpuBoardWindow.open(gui, phase::get);
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> onGl(() -> {
                    var label = GpuBoardTestUi.stage().getRoot().findActor("board-loading-message");
                    return label instanceof com.badlogic.gdx.scenes.scene2d.ui.Label message
                          && message.getText().toString().contains("Starting Scenario");
                }));
                assertFalse(onSwing(() -> ui.frame().isVisible()));
                long window = onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    phase.set(new WaitingForServerPanel());
                    return null;
                });
                await(() -> onGl(() -> {
                    com.badlogic.gdx.scenes.scene2d.ui.Label message = GpuBoardTestUi.stage().getRoot()
                          .findActor("board-loading-message");
                    return message.getText().toString().equals(Messages.getString("ClientGUI.waitingOnTheServer"));
                }));
                onSwing(() -> {
                    doReturn(Optional.of(ui.view().getClientState())).when(gui).getCurrentBoardState();
                    return null;
                });
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                // With the board, the HUD's phase header names the server wait until a phase display replaces it.
                String waiting = UiTheme.upper(Messages.getString("ClientGUI.waitingOnTheServer"));
                await(() -> onGl(() -> phaseHeaderTexts().contains(waiting)));
                onGl(() -> {
                    assertEquals(window, ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                    assertNull(GpuBoardTestUi.stage().getRoot().findActor("board-loading-message"));
                    return null;
                });
                onSwing(() -> { phase.set(fixture.panel); return null; });
                await(() -> onGl(() -> !phaseHeaderTexts().contains(waiting)));
                onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    assertFalse(ui.frame().isVisible());
                    return null;
                });
                BoardView replacement = onSwing(() -> {
                    BoardView view = new BoardView(fixture.game, null, gui, 0);
                    view.setLocalPlayer(fixture.player);
                    ui.view().dispose();
                    fixture.view.close();
                    doReturn(Optional.of(view.getClientState())).when(gui).getCurrentBoardState();
                    return view;
                });
                try {
                    assertTrue(GpuBoardWindow.isActiveFor(gui), "Replacing the map must not dispose the client's window");
                    onGl(() -> {
                        assertEquals(window, ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                        return null;
                    });
                    Application restarting = Gdx.app;
                    onSwing(() -> {
                        GpuBoardWindow.showClassic(gui);
                        GpuBoardWindow.open(gui, phase::get);
                        return null;
                    });
                    await(() -> Gdx.app != null && Gdx.app != restarting);
                    await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() > 0));
                    assertFalse(onSwing(() -> ui.frame().isVisible()), "A queued native restart never flashes 2D");
                } finally {
                    onSwing(() -> {
                        GpuBoardWindow.closeFor(gui);
                        replacement.dispose();
                        return null;
                    });
                }
            } finally {
                onSwing(() -> {
                    GpuBoardWindow.closeFor(gui);
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    return null;
                });
            }
        }
    }

    @Test
    void nativeCloseUsesTheSavePromptAndCancellationKeepsTheSameWindow() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean noSaveNag = preferences.getNoSaveNag();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture, false));
            ClientGUI gui = ui.view().getClientgui();
            AtomicInteger quit = new AtomicInteger();
            try {
                onSwing(() -> {
                    preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, false);
                    doCallRealMethod().when(gui).handleExit();
                    doAnswer(invocation -> {
                        quit.incrementAndGet();
                        GpuBoardWindow.closeFor(gui);
                        ui.frame().dispose();
                        return null;
                    }).when(gui).die();
                    return null;
                });
                openNative(ui);
                long window = onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                for (int response : new int[] { JOptionPane.CANCEL_OPTION, JOptionPane.YES_OPTION, JOptionPane.NO_OPTION }) {
                    onGl(() -> {
                        // Invoke LWJGL's installed OS close callback; closeWindow() would bypass its confirmation hook.
                        var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                        GLFW.glfwSetWindowCloseCallback(window, callback);
                        callback.invoke(window);
                        return null;
                    });
                    await(() -> onSwing(() -> savePrompt(ui.frame()) != null));
                    onSwing(() -> {
                        JOptionPane prompt = savePrompt(ui.frame());
                        assertEquals(Messages.getString("ClientGUI.gameSaveDialogMessage"), prompt.getMessage());
                        assertTrue(SwingUtilities.getWindowAncestor(prompt).isAlwaysOnTop());
                        prompt.setValue(response);
                        return null;
                    });
                    onSwing(() -> null);
                    if (response != JOptionPane.NO_OPTION) {
                        assertEquals(0, quit.get(), "Cancelling or failing to save must keep the game open");
                        assertTrue(GpuBoardWindow.isActiveFor(gui));
                        assertFalse(onSwing(() -> ui.frame().isVisible()));
                        assertEquals(window, onGl(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle()));
                    }
                }
                await(() -> !GpuBoardWindow.isActiveFor(gui));
                assertEquals(1, quit.get());
                assertFalse(onSwing(() -> ui.frame().isVisible()));
            } finally {
                onSwing(() -> {
                    for (Window owned : ui.frame().getOwnedWindows()) {
                        owned.dispose();
                    }
                    GpuBoardWindow.closeFor(gui);
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    preferences.setValue(GUIPreferences.ADVANCED_NO_SAVE_NAG, noSaveNag);
                    return null;
                });
            }
        }
    }

    /** The texts of the HUD's phase header, on the GL thread. */
    private static List<String> phaseHeaderTexts() {
        return GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("phase-header"));
    }

    private static JOptionPane savePrompt(JFrame frame) {
        for (Window window : frame.getOwnedWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()) {
                for (var child : dialog.getContentPane().getComponents()) {
                    if (child instanceof JOptionPane pane) {
                        return pane;
                    }
                }
            }
        }
        return null;
    }

    @Test
    void classicReportStaysHiddenUntilReturningFromTheNativeBoard() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean enabled = preferences.getMiniReportEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            ClientGUI gui = ui.view().getClientgui();
            MiniReportDisplayDialog report = onSwing(() -> {
                MiniReportDisplayDialog dialog = new MiniReportDisplayDialog(ui.frame(), gui);
                when(gui.getMiniReportDisplayDialog()).thenReturn(dialog);
                when(gui.getMiniReportDisplay()).thenReturn(mock(MiniReportDisplayPanel.class));
                doAnswer(invocation -> {
                    if (GpuBoardWindow.isActiveFor(gui)) {
                        invocation.callRealMethod();
                    } else {
                        // The fixture has no classic split panes. Observe the restored visibility request instead.
                        dialog.setVisible(invocation.getArgument(0));
                    }
                    return null;
                }).when(gui).setMiniReportLocation(anyBoolean());
                preferences.setMiniReportEnabled(true);
                dialog.setVisible(true);
                return dialog;
            });
            try {
                openNative(ui);
                await(() -> onSwing(() -> !ui.frame().isVisible()));
                onSwing(() -> {
                    assertTrue(GpuBoardWindow.isActiveFor(gui));
                    assertFalse(report.isVisible(), "Hide an already open classic report during the handoff");
                    fixture.game.setPhase(GamePhase.FIRING_REPORT);
                    fixture.game.setAllReports(List.of(List.of(new Report(3000),
                          new Report(6065).addDesc(fixture.entity).add(10).add("Left Torso"))));
                    gui.setMiniReportLocation(true);
                    assertFalse(report.isVisible(), "Phase and preference updates must not reopen the legacy dialog");
                    assertTrue(preferences.getMiniReportEnabled(), "Suppressing the old window preserves 2D preferences");
                    return null;
                });
                // The report phase opens the HUD's log, which stands for the classic report.
                await(() -> onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("log-panel"))));
                onSwing(() -> {
                    assertFalse(report.isVisible(), "The native log must not revive the old report");
                    GpuBoardWindow.showClassic(gui);
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible() && report.isVisible()));
                assertFalse(onSwing(() -> GpuBoardWindow.isActiveFor(gui)));
            } finally {
                onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    report.dispose();
                    ui.frame().dispose();
                    ui.view().dispose();
                    preferences.removePreferenceChangeListener(ui.menus());
                    preferences.setMiniReportEnabled(enabled);
                    return null;
                });
            }
        }
    }

    @Test
    void switchesExclusiveWindowsThroughMenusAndNeverRestoresClassicOnClientDisposal() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            Coords position = fixture.entity.getPosition();
            GUIPreferences preferences = GUIPreferences.getInstance();
            float originalScale = preferences.getGUIScale();
            try {
                onSwing(() -> {
                    ui.view().centerOnHex(position);
                    return null;
                });
                openNative(ui);
                await(() -> onSwing(() -> !ui.frame().isVisible()));
                awaitMaximizedWindow();
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.entranceOpacity() == 1));
                onGl(() -> {
                    BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
                    assertTrue(camera.isIsometric());
                    for (var tile : fixture.source.takeFrame().scene().tiles()) {
                        for (int corner = 0; corner < 6; corner++) {
                            Vector3 point = camera.camera.project(BoardGeometry.corner(tile.coords(), tile.elevation(), corner),
                                  0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
                            assertTrue(point.x > 0 && point.x < camera.camera.viewportWidth
                                  && point.y > 0 && point.y < camera.camera.viewportHeight,
                                  "Opening the 3D board must fit the whole map despite earlier 2D focus requests");
                        }
                    }
                    return null;
                });
                onSwing(() -> { ui.view().centerOnHex(position); return null; });
                await(() -> onGl(() -> unitIsCentered(fixture)));
                assertTrue(onSwing(() -> ui.frame().isDisplayable()), "Switching must preserve the original client");
                // The classic window is hidden, so an allowed client dialog, here the About box, must be raised above
                // the native window.
                JDialog probe = onSwing(() -> {
                    JDialog dialog = new JOptionPane("GPU dialog probe").createDialog(ui.frame(),
                          Messages.getString("about.title", megamek.MMConstants.PROJECT_NAME));
                    dialog.setModal(false);
                    dialog.setVisible(true);
                    return dialog;
                });
                await(() -> onSwing(probe::isAlwaysOnTop));
                onSwing(() -> { probe.dispose(); return null; });
                input(() -> ((Lwjgl3Graphics) Gdx.graphics).getWindow().restoreWindow());
                for (int[] size : new int[][] { { 900, 600 }, { 2043, 1200 }, { 2560, 1600 }, { 3840, 2160 }, { 1280, 800 } }) {
                    onSwing(() -> { preferences.setValue(GUIPreferences.GUI_SCALE, size[0] == 2560 ? 1.5f : 1f); return null; });
                    input(() -> assertTrue(Gdx.graphics.setWindowedMode(size[0], size[1])));
                    long resizedFrame = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames());
                    // Allow the EDT to publish the resized overlay and upload it on the render thread.
                    await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() > resizedFrame + 15));
                    assertFalse(onSwing(() -> ui.frame().isVisible()), "Resizing must not fall back to the classic board");
                    // A client's request to centre on the unit, as Center camera gives it, keeps zoom and angle.
                    float zoom = onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                        battle.boardCamera.setIsometric(size[0] != 900);
                        battle.boardCamera.pan(140, -100);
                        assertFalse(unitIsCentered(fixture));
                        return battle.boardCamera.camera.zoom;
                    });
                    Vector3 direction = onGl(() -> new Vector3(((GpuBattleView) Gdx.app.getApplicationListener())
                          .boardCamera.camera.direction));
                    Callable<Long> centerOnUnit = () -> {
                        ui.view().centerOn(fixture.entity);
                        var request = ui.view().getClientState().getCenterRequest();
                        assertEquals(fixture.entity.getId(), request.entityId());
                        return request.sequence();
                    };
                    awaitCenterRequest(onSwing(centerOnUnit));
                    Vector3 settled = onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                        assertEquals(zoom, battle.boardCamera.camera.zoom, 0.001f, "Centering preserves zoom");
                        assertTrue(direction.epsilonEquals(battle.boardCamera.camera.direction, 0.001f), "Centering preserves the view angle");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuBoardTestUi.capture(new File(output, "resize-" + size[0] + ".png"));
                        return battle.boardCamera.focus.cpy();
                    });
                    awaitCenterRequest(onSwing(centerOnUnit));
                    onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                        assertTrue(settled.epsilonEquals(battle.boardCamera.focus, .001f),
                              "A second request must retain the same framing, not snap to the unit's hex");
                        return null;
                    });
                }
                // The HUD's Menu holds the menu bar's commands; its groups stay open when it opens again.
                input(() -> GpuBoardTestUi.click("utility-menu"));
                input(() -> GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.FileMenu")));
                captureMenu("menu-file.png");
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                input(() -> GpuBoardTestUi.click("utility-menu"));
                input(() -> GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.ViewMenu")));
                captureMenu("menu-view.png");
                onGl(() -> {
                    GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                    float before = battle.boardCamera.camera.zoom;
                    GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.viewZoomIn"));
                    assertNotEquals(before, battle.boardCamera.camera.zoom, "The menu must zoom the active GPU camera");
                    return null;
                });
                input(() -> GpuBoardTestUi.click("utility-menu"));
                onGl(() -> {
                    GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.viewClassicBoard"));
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible()));
                assertSame(ui.menus(), onSwing(() -> ui.frame().getJMenuBar()));
                assertEquals(position, fixture.entity.getPosition());

                assertFalse(preferences.getUse3DBoard(), "The last selected board is saved");
                assertEquals(ClientGUI.VIEW_GPU_BOARD, ui.gpuChoice().getActionCommand());
                // Reopen through the same menu. Client disposal must never bring the old frame back.
                openNative(ui);
                await(() -> onSwing(() -> !ui.frame().isVisible()));
                awaitMaximizedWindow();
                assertTrue(preferences.getUse3DBoard());
                assertEquals(ClientGUI.VIEW_CLASSIC_BOARD, ui.gpuChoice().getActionCommand());
                onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    return null;
                });
                await(() -> Thread.getAllStackTraces().keySet().stream()
                      .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
                onSwing(() -> {
                    assertFalse(ui.frame().isDisplayable());
                    assertFalse(ui.frame().isVisible());
                    return null;
                });
            } finally {
                onSwing(() -> {
                    preferences.setValue(GUIPreferences.GUI_SCALE, originalScale);
                    preferences.removePreferenceChangeListener(ui.overview());
                    GpuBoardWindow.closeFor(ui.view().getClientState());
                    ui.frame().dispose();
                    ui.menus().die();
                    return null;
                });
                await(() -> Thread.getAllStackTraces().keySet().stream()
                      .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
            }
        }
    }

    private static void awaitMaximizedWindow() throws Exception {
        await(() -> onGl(() -> {
            long handle = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
            return GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_VISIBLE) == GLFW.GLFW_TRUE
                  && GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
        }));
        onGl(() -> {
            long handle = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
            assertEquals(GLFW.GLFW_TRUE, GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_DECORATED),
                  "The maximized board must retain its title bar and window controls");
            assertFalse(Gdx.graphics.isFullscreen(), "The board must remain a normal desktop window");
            return null;
        });
    }

    private static boolean unitIsCentered(GpuBoardFixture fixture) {
        Coords position = fixture.entity.getPosition();
        Vector3 expected = BoardGeometry.center(position, fixture.game.getBoard().getHex(position).getLevel());
        return ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.epsilonEquals(expected, 0.01f);
    }

    private ClientWindow createClientWindow(GpuBoardFixture fixture) throws Exception {
        return createClientWindow(fixture, true);
    }

    private ClientWindow createClientWindow(GpuBoardFixture fixture, boolean classic) throws Exception {
        fixture.source.close();
        ClientGUI gui = mock(ClientGUI.class, invocation -> switch (invocation.getMethod().getName()) {
            case "refreshAuxiliaryWindows", "setMapVisible", "setBotCommandsLocation",
                 "setPlayerListVisible", "setRoundsInAirVisible" -> invocation.callRealMethod();
            default -> org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        BoardViewsContainer container = mock(BoardViewsContainer.class);
        when(container.isClassicViewEnabled()).thenAnswer(invocation -> !GpuBoardWindow.isActiveFor(gui));
        setField(AbstractClientGUI.class, gui, "boardViewsContainer", container);
        setField(AbstractClientGUI.class, gui, "miniMaps", new HashMap<>());
        Client client = mock(Client.class);
        JFrame frame = new JFrame("MegaMek - Classic board switch test");
        CommonMenuBar menus = CommonMenuBar.getMenuBarForGame();
        menus.setPhase(GamePhase.MOVEMENT);
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getFrame()).thenReturn(frame);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        fixture.view.close();
        fixture.view = new BoardClientState(fixture.game, null, gui, 0, null);
        fixture.view.setLocalPlayer(fixture.player);
        BoardView renderer = new BoardView(fixture.view, null, gui);
        if (classic) {
            frame.add(renderer.getComponent());
        }
        BoardView view = spy(renderer);
        // BoardViewPanel retains its original owner; let that owner create its viewport on demand too.
        doAnswer(invocation -> renderer.getComponent()).when(view).getComponent();
        doReturn(gui).when(view).getClientgui();
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(fixture.game);
        when(client.getLocalPlayer()).thenReturn(fixture.player);
        when(gui.getFrame()).thenReturn(frame);
        doAnswer(invocation -> {
            frame.getContentPane().removeAll();
            if (invocation.getArgument(0, Boolean.class)) {
                frame.add(view.getComponent());
            } else {
                renderer.releaseClassicView();
                view.releaseClassicView();
            }
            frame.validate();
            return null;
        }).when(gui).setClassicBoardViewEnabled(anyBoolean());
        when(gui.getMenuBar()).thenReturn(menus);
        doReturn(Optional.of(fixture.view)).when(gui).getCurrentBoardState();
        when(gui.boardViews()).thenReturn(List.of(view));
        doReturn(List.of(fixture.view)).when(gui).boardStates();
        doReturn(fixture.view).when(gui).getBoardState();
        doReturn(fixture.view).when(gui).getBoardState(any(BoardLocation.class));
        doReturn(fixture.view).when(gui).getBoardState(any(Entity.class));
        when(gui.getBoardView()).thenReturn(view);
        when(gui.getBoardView(any(BoardLocation.class))).thenReturn(view);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        doCallRealMethod().when(gui).centerOnUnit(any());
        doAnswer(invocation -> {
            BoardLocation location = invocation.getArgument(0);
            if (fixture.game.hasBoardLocation(location)) {
                view.centerOnHex(location.coords());
            }
            return null;
        }).when(gui).centerOnHex(any());
        UnitOverviewOverlay overview = new UnitOverviewOverlay(gui);
        menus.addActionListener(event -> {
            if (event.getActionCommand().equals(ClientGUI.VIEW_GPU_BOARD)) {
                GUIPreferences.getInstance().setUse3DBoard(true);
                GpuBoardWindow.open(fixture.view, () -> fixture.panel);
            } else if (event.getActionCommand().equals(ClientGUI.VIEW_CLASSIC_BOARD)) {
                GUIPreferences.getInstance().setUse3DBoard(false);
                GpuBoardWindow.showClassic(gui);
            }
        });
        frame.setJMenuBar(menus);
        frame.setSize(960, 700);
        frame.setVisible(classic);
        JMenu viewMenu = (JMenu) java.util.Arrays.stream(menus.getComponents())
              .filter(component -> component instanceof JMenu menu
                    && menu.getText().equals(Messages.getString("CommonMenuBar.ViewMenu"))).findFirst().orElseThrow();
        JMenuItem gpuChoice = java.util.Arrays.stream(viewMenu.getMenuComponents())
              .filter(component -> component instanceof JMenuItem item
                    && ClientGUI.VIEW_GPU_BOARD.equals(item.getActionCommand()))
              .map(JMenuItem.class::cast).findFirst().orElseThrow();
        return new ClientWindow(frame, menus, view, gpuChoice, overview);
    }

    private static void openNative(ClientWindow ui) throws Exception {
        Application previous = Gdx.app;
        onSwing(() -> {
            ui.gpuChoice().doClick(0);
            assertTrue(GpuBoardWindow.isActiveFor(ui.view().getClientgui()), "Native ownership includes startup");
            return null;
        });
        await(() -> Gdx.app != null && Gdx.app != previous);
        awaitMaximizedWindow();
        await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() > 0));
        assertFalse(onSwing(() -> ui.frame().isVisible()), "The native window replaces the old window");
    }

    private static <T> T onSwing(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void awaitNavigation() throws Exception {
        long previous = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames());
        await(() -> onGl(() -> {
            GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
            return battle.frames() > previous + 2 && !battle.boardCamera.isFraming();
        }));
    }

    /**
     * Waits until the render thread has applied the client's center request {@code sequence} (or a later one) and its
     * framing has ended. The source publishes the request with its next frame (its timer runs every 100 ms, later
     * under load), so a few rendered frames alone do not show that the request arrived.
     */
    private static void awaitCenterRequest(long sequence) throws Exception {
        var applied = GpuBattleView.class.getDeclaredField("centerSequence");
        applied.setAccessible(true);
        await(() -> onGl(() -> {
            GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
            return applied.getLong(battle) >= sequence && !battle.boardCamera.isFraming();
        }));
    }

    private static <T> T onGl(Callable<T> action) throws Exception {
        Application app = Gdx.app;
        FutureTask<T> task = new FutureTask<>(action);
        app.postRunnable(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private static void captureMenu(String name) throws Exception {
        await(() -> onGl(() -> GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("menu-panel"))));
        onGl(() -> {
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            GpuBoardTestUi.capture(new File(output, name));
            return null;
        });
    }

    private static void pressShortcut(KeyCommandBind bind) throws Exception {
        input(() -> GpuBoardTestUi.press(bind));
        onSwing(() -> null);
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value) {
        try {
            var field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void input(Runnable action) throws Exception {
        long previous = onGl(() -> {
            action.run();
            return ((GpuBattleView) Gdx.app.getApplicationListener()).frames();
        });
        await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() > previous));
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for the board window handoff");
            Thread.sleep(25);
        }
    }
}
