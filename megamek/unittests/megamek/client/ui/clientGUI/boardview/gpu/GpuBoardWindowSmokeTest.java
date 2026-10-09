/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
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
import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.boardeditor.BoardEditorSession;
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
import megamek.client.ui.dialogs.unitDisplay.WeaponPanel;
import megamek.client.ui.gdx.UiTheme;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Hex;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardFile;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MapSettings;
import megamek.common.units.Entity;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Exercises the real Swing/native window handoff and the shared menu through Scene2D input. */
@Tag("on-demand")
class GpuBoardWindowSmokeTest {
    private boolean boardStyle;

    private static final List<String> WINDOW_BOUNDS = List.of(GUIPreferences.GPU_BOARD_POS_X, GUIPreferences.GPU_BOARD_POS_Y,
          GUIPreferences.GPU_BOARD_SIZE_WIDTH, GUIPreferences.GPU_BOARD_SIZE_HEIGHT);
    private final java.util.Map<String, Integer> savedBounds = new java.util.HashMap<>();
    private boolean savedMaximized;

    @BeforeEach
    void saveBoardStyle() {
        var prefs = GUIPreferences.getInstance();
        boardStyle = prefs.getUse3DBoard();
        // The native window reopens at its last saved bounds; every case starts from the maximized default.
        for (String key : WINDOW_BOUNDS) { savedBounds.put(key, prefs.getInt(key)); }
        savedMaximized = prefs.getBoolean(GUIPreferences.GPU_BOARD_MAXIMIZED);
        prefs.setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, true);
    }

    @AfterEach
    void restoreBoardStyle() throws Exception {
        await(() -> Thread.getAllStackTraces().keySet().stream()
              .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
        var prefs = GUIPreferences.getInstance();
        prefs.setUse3DBoard(boardStyle);
        for (String key : WINDOW_BOUNDS) { prefs.setValue(key, savedBounds.get(key)); }
        prefs.setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, savedMaximized);
    }

    @Test
    void nativeGameplayRendersWithoutConstructingAClassicBoardOrInspector() throws Exception {
        var classic = onSwing(() -> mockConstruction(BoardView.class));
        var panels = onSwing(() -> mockConstruction(BoardViewPanel.class));
        var inspector = onSwing(() -> mockConstruction(UnitDisplayPanel.class));
        var inspectorDialog = onSwing(() -> mockConstruction(UnitDisplayDialog.class));
        var weapons = onSwing(() -> mockConstruction(WeaponPanel.class));
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
                assertTrue(inspector.constructed().isEmpty());
                assertTrue(inspectorDialog.constructed().isEmpty());
                assertTrue(weapons.constructed().isEmpty());
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
                weapons.close();
                inspectorDialog.close();
                inspector.close();
                classic.close();
                return null;
            });
        }
    }
    private record ClientWindow(JFrame frame, CommonMenuBar menus, BoardView view, JMenuItem gpuChoice,
          UnitOverviewOverlay overview) { }

    private static int sidebarBorderColor(UnitOverviewOverlay overview, int index) {
        var graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            var portraits = overview.captureLayers(graphics, new Rectangle(0, 0, 800, 600));
            return portraits.get(index).image().getRGB(10, 2);
        } finally {
            graphics.dispose();
        }
    }

    private static void clickBoard(Coords coords, int button) {
        clickBoard(coords, button, 0);
    }

    private static void clickBoard(Coords coords, int button, int modifiers) {
        Vector3 point = ((GpuBattleView) Gdx.app.getApplicationListener()).screenPosition(coords);
        var processor = Gdx.input.getInputProcessor();
        Input original = Gdx.input;
        Input keys = mock(Input.class);
        when(keys.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        Gdx.input = keys;
        try {
            processor.touchDown(Math.round(point.x), Math.round(point.y), 0, button);
            processor.touchUp(Math.round(point.x), Math.round(point.y), 0, button);
        } finally {
            Gdx.input = original;
        }
    }

    @Test
    void nativeCloseWhileEditorLoadsStopsWorkersAndAllowsReopening() throws Exception {
        var file = Files.createTempFile("native-editor-loading-", ".board2");
        megamek.common.board.BoardFile.save(Board.createEmptyBoard(64, 64), file);
        byte[] saved = Files.readAllBytes(file);
        JFrame launcher = onSwing(() -> {
            var frame = new JFrame("Editor launcher");
            frame.setSize(400, 300);
            frame.setVisible(true);
            return frame;
        });
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                Application previous = Gdx.app;
                onSwing(() -> { GpuBoardWindow.openEditor(launcher, file.toFile(), false); return null; });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> previewSource() != null);
                BoardSource source = previewSource();
                var workers = new AtomicReference<ExecutorService>();
                await(() -> onGl(() -> {
                    var view = (GpuBattleView) Gdx.app.getApplicationListener();
                    var terrainField = GpuBattleView.class.getDeclaredField("terrain");
                    terrainField.setAccessible(true);
                    var terrain = (GpuTerrain) terrainField.get(view);
                    if (terrain == null || terrain.buildDetails().stream()
                          .noneMatch(status -> status.section() > 0)) { return false; }
                    assertFalse(terrain.ready(source.takeFrame().scene()), "Close must happen during map loading");
                    var field = GpuTerrain.class.getDeclaredField("detailWorker");
                    field.setAccessible(true);
                    workers.set((ExecutorService) field.get(terrain));
                    long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                    var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                    GLFW.glfwSetWindowCloseCallback(window, callback);
                    callback.invoke(window);
                    return true;
                }));
                await(() -> onSwing(launcher::isShowing));
                await(() -> Thread.getAllStackTraces().keySet().stream()
                      .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
                assertTrue(workers.get().isTerminated(), "No terrain workers may outlive their native window");
                assertTrue(source.isClosed());
                assertNull(onSwing(() -> savePrompt(launcher)), "Cancelling loading must not report a startup failure");
                assertArrayEquals(saved, Files.readAllBytes(file), "Closing the loader must not change the board file");
            }
        } finally {
            onSwing(() -> {
                GpuBoardWindow.closeFor((ClientGUI) null);
                for (Window owned : launcher.getOwnedWindows()) { owned.dispose(); }
                launcher.dispose();
                return null;
            });
            Files.deleteIfExists(file);
        }
    }

    @Test
    void standaloneEditorOwnsItsCommandsAndNeverConstructsAClassicPanel() throws Exception {
        var file = Files.createTempFile("native-editor-", ".board2");
        megamek.common.board.BoardFile.save(Board.createEmptyBoard(8, 8), file);
        JFrame launcher = onSwing(() -> { var frame = new JFrame("Editor launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        Application previous = Gdx.app;
        try {
            onSwing(() -> {
                try (var panels = mockConstruction(BoardEditorPanel.class); var views = mockConstruction(BoardView.class)) {
                    GpuBoardWindow.openEditor(launcher, file.toFile(), false);
                    assertTrue(panels.constructed().isEmpty()); assertTrue(views.constructed().isEmpty());
                }
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            // The loading window exists before the EDT attaches the standalone document.
            await(() -> previewSource() != null);
            BoardSource source = previewSource();
            await(() -> previewReady(source)); awaitNavigation();
            var field = GpuMapSource.class.getDeclaredField("editor"); field.setAccessible(true);
            var editor = (megamek.client.ui.boardeditor.BoardEditorSession) field.get(source);
            Coords first = new Coords(3, 3), second = new Coords(3, 4);
            onSwing(() -> {
                editor.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                      megamek.client.ui.boardeditor.BoardEditorSession.Action.CHOOSE_BRUSH, "vegetation", "woods-1"), launcher);
                editor.command(new megamek.client.ui.boardeditor.BoardEditorSession.Command(
                      megamek.client.ui.boardeditor.BoardEditorSession.Action.TOOL, "PAINT"), launcher);
                source.refresh(); return null;
            });
            awaitNavigation();
            input(() -> editorStroke(List.of(first, second), 0, true));
            onSwing(() -> {
                assertTrue(editor.board().getHex(first).containsTerrain(Terrains.WOODS),
                      () -> "Expected woods at the press: " + editor.snapshot());
                assertTrue(editor.board().getHex(second).containsTerrain(Terrains.WOODS));
                assertTrue(editor.dirty()); return null;
            });
            pressShortcut(KeyCommandBind.UNDO);
            assertFalse(onSwing(() -> editor.board().getHex(second).containsTerrain(Terrains.WOODS)));
            pressShortcut(KeyCommandBind.REDO);
            assertTrue(onSwing(() -> editor.board().getHex(second).containsTerrain(Terrains.WOODS)));
            input(() -> editorWheel(first, true, true, -.4f, -.6f));
            assertEquals(1, onSwing(() -> editor.board().getHex(first).getLevel()));
            input(() -> {
                assertCameraDrag(Input.Buttons.RIGHT, false, false);
                assertCameraDrag(Input.Buttons.MIDDLE, false, true);
            });
            long generation = source.takeFrame().boardGeneration();
            onSwing(() -> { editor.save(file); editor.open(file); source.refresh(); return null; });
            assertNotEquals(generation, source.takeFrame().boardGeneration());
            source.adjustEditorElevation(first, 9, generation); source.endEditorStroke();
            assertEquals(1, onSwing(() -> editor.board().getHex(first).getLevel()));
            assertFalse(onSwing(() -> launcher.isShowing()));
            onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); return null; });
            await(source::isClosed);
            await(() -> onSwing(launcher::isShowing));
            assertTrue(onSwing(() -> launcher.isShowing()), "Closing returns to the launcher, not a hidden 2D editor");
            assertTrue(onSwing(() -> editor.game().getGameListeners().isEmpty()));
        } finally {
            onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); launcher.dispose(); return null; });
            Files.deleteIfExists(file);
        }
    }

    private static void assertCameraDrag(int button, boolean shift, boolean orbit) {
        BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
        Vector3 direction = camera.camera.direction.cpy();
        Vector3 focus = camera.focus.cpy();
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn(shift);
        Gdx.input = keyboard;
        try {
            int x = Gdx.graphics.getWidth() / 3, y = Gdx.graphics.getHeight() / 2;
            original.getInputProcessor().touchDown(x, y, 0, button);
            original.getInputProcessor().touchDragged(x + 80, y + 35, 0);
            original.getInputProcessor().touchUp(x + 80, y + 35, 0, button);
            assertEquals(orbit, !direction.epsilonEquals(camera.camera.direction, 0.001f),
                  "Orbit changes the viewing angle; pan preserves it");
            assertEquals(!orbit, !focus.epsilonEquals(camera.focus, 0.001f),
                  "Pan moves the focus; orbit preserves it");
        } finally {
            Gdx.input = original;
        }
    }

    /** Dispatch wheel events through the native input multiplexer, including fractional trackpad deltas. */
    private static void editorWheel(Coords coords, boolean control, boolean release, float... amounts) {
        Input original = Gdx.input;
        Input pointer = mock(Input.class);
        Vector3 screen = coords == null ? new Vector3(10, 10, 0)
              : ((GpuBattleView) Gdx.app.getApplicationListener()).screenPosition(coords);
        when(pointer.getX()).thenReturn(Math.round(screen.x));
        when(pointer.getY()).thenReturn(Math.round(screen.y));
        when(pointer.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn(control);
        Gdx.input = pointer;
        try {
            for (float amount : amounts) {
                original.getInputProcessor().scrolled(0, amount);
            }
            if (release) {
                original.getInputProcessor().keyUp(Input.Keys.CONTROL_LEFT);
            }
        } finally {
            Gdx.input = original;
        }
    }

    /** Real native picking and drag dispatch, including releases over the toolbar. */
    private static void editorStroke(List<Coords> hexes, int modifiers, boolean releaseOverToolbar) {
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.getInputProcessor()).thenReturn(original.getInputProcessor());
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        Gdx.input = keyboard;
        try {
            GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
            Point last = null;
            for (Coords coords : hexes) {
                // Pick against the frame actually on screen, not a newer Swing snapshot awaiting rendering.
                Vector3 point = battle.screenPosition(coords);
                Point screen = new Point(Math.round(point.x), Math.round(point.y));
                var stage = GpuBoardTestUi.stage();
                var stagePoint = stage.screenToStageCoordinates(new com.badlogic.gdx.math.Vector2(screen.x, screen.y));
                assertNull(stage.hit(stagePoint.x, stagePoint.y, true), "Paint gesture must reach the board at " + coords);
                if (last == null) {
                    original.getInputProcessor().touchDown(screen.x, screen.y, 0, Input.Buttons.LEFT);
                } else {
                    original.getInputProcessor().touchDragged(screen.x, screen.y, 0);
                }
                last = screen;
            }
            original.getInputProcessor().touchUp(releaseOverToolbar ? 10 : last.x,
                  releaseOverToolbar ? 10 : last.y, 0, Input.Buttons.LEFT);
        } finally {
            Gdx.input = original;
        }
    }

    @Test
    void mapPreviewReusesItsWindowWhileBrowsingAndReleasesResourcesWhenClosed() throws Exception {
        GUIPreferences.getInstance().setUse3DBoard(false);
        AtomicInteger browserReturned = new AtomicInteger();
        JDialog browser = onSwing(() -> {
            JFrame frame = new JFrame("Lobby");
            frame.setSize(800, 600);
            frame.setVisible(true);
            JDialog dialog = new JDialog(frame, "Map browser", true);
            dialog.setSize(600, 400);
            SwingUtilities.invokeLater(() -> {
                dialog.setVisible(true);
                browserReturned.incrementAndGet();
            });
            return dialog;
        });
        try {
            await(() -> onSwing(browser::isShowing));
            assertEquals(0, browserReturned.get(), "The map browser starts a modal session");
            Application previous = Gdx.app;
            onSwing(() -> {
                GpuBoardWindow.openPreview(browser, new File("data/boards/AGoAC Maps/16x17 Grassland 3.board"));
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
            BoardSource first = previewSource();
            onSwing(() -> {
                assertTrue(browser.isShowing());
                assertEquals(0, browserReturned.get(), "Preview must not accept or dismiss the map picker");
                assertFalse(GUIPreferences.getInstance().getUse3DBoard());
                assertEquals(16, first.takeFrame().scene().width());
                assertEquals(17, first.takeFrame().scene().height());
                assertTrue(first.takeFrame().scene().units().isEmpty());
                return null;
            });
            onGl(() -> {
                File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                assertTrue(output.isDirectory() || output.mkdirs());
                GpuBoardTestUi.capture(new File(output, "map-browser-preview.png"));
                long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                var callback = GLFW.glfwSetWindowCloseCallback(window, null);
                GLFW.glfwSetWindowCloseCallback(window, callback);
                callback.invoke(window);
                return null;
            });
            await(() -> !GpuBoardWindow.isActiveFor(null));
            assertTrue(first.isClosed());
            assertTrue(onSwing(browser::isShowing));
            assertEquals(0, browserReturned.get());

            Application firstApplication = Gdx.app;
            onSwing(() -> {
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(6, 8));
                return null;
            });
            await(() -> Gdx.app != null && Gdx.app != firstApplication);
            await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
            BoardSource second = previewSource();
            Application secondApplication = Gdx.app;
            onSwing(() -> {
                assertEquals(6, second.takeFrame().scene().width());
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(8, 10));
                return null;
            });
            await(() -> previewReady(second));
            assertSame(secondApplication, Gdx.app, "Browsing another map retains the GL context and assets");
            assertFalse(second.isClosed());
            BoardSource third = previewSource();
            assertSame(second, third, "The same source follows the authoritative game's new board");
            long generation = third.takeFrame().boardGeneration();
            onSwing(() -> {
                // Same dimensions still mean a new board. Rapid choices must converge to the final one.
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(8, 10));
                Board last = Board.createEmptyBoard(8, 10);
                last.setHex(3, 4, new Hex(3));
                GpuBoardWindow.openPreview(browser, last);
                return null;
            });
            await(() -> third.takeFrame().boardGeneration() > generation && previewReady(third));
            assertSame(secondApplication, Gdx.app);
            assertEquals(3, third.takeFrame().scene().tile(new Coords(3, 4)).elevation());
            onSwing(() -> {
                assertEquals(8, third.takeFrame().scene().width());
                assertEquals(0, browserReturned.get());
                browser.setVisible(false);
                return null;
            });
            await(() -> !GpuBoardWindow.isActiveFor(null));
            assertTrue(third.isClosed());
            assertFalse(GUIPreferences.getInstance().getUse3DBoard());
        } finally {
            onSwing(() -> { browser.getOwner().dispose(); return null; });
        }
    }

    @Test
    void startingTheGameClosesItsMapPreviewBeforeOpeningTheBattleWindow() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create(Board.createEmptyBoard(10, 10))) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            try {
                Application previous = Gdx.app;
                onSwing(() -> {
                    GpuBoardWindow.openPreview(ui.frame(), fixture.game.getBoard());
                    return null;
                });
                await(() -> Gdx.app != null && Gdx.app != previous);
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                BoardSource preview = previewSource();
                Application previewApplication = Gdx.app;
                onSwing(() -> {
                    assertTrue(preview instanceof GpuMapSource, "Preview uses the model directly");
                    assertTrue(preview.takeFrame().scene().units().isEmpty());
                    GpuBoardWindow.open(ui.view().getClientState(), () -> fixture.panel);
                    ui.frame().setVisible(false);
                    return null;
                });
                await(() -> GpuBoardWindow.isActiveFor(ui.view().getClientgui()));
                await(() -> Gdx.app != null && Gdx.app != previewApplication);
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                assertTrue(preview.isClosed());
                assertFalse(onSwing(() -> ui.frame().isVisible()));
                assertSame(fixture.game, ((GpuBoardSource) previewSource()).currentView().game);
            } finally {
                onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view().getClientgui());
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    return null;
                });
            }
        }
    }

    @Test
    void previewMenuSwitchesBothWaysWithoutRegeneratingTheBoard() throws Exception {
        JFrame launcher = onSwing(() -> { var frame = new JFrame("Preview launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        Board board = Board.createEmptyBoard(7, 9);
        board.setHex(3, 4, new Hex(3));
        try {
            Application previous = Gdx.app;
            onSwing(() -> { GpuBoardWindow.openPreview(launcher, board); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            for (int round = 0; round < 2; round++) {
                await(() -> previewSource() != null);
                BoardSource source = previewSource();
                await(() -> previewReady(source)); awaitNavigation();
                input(() -> GpuBoardTestUi.click("utility-menu"));
                captureMenu("preview-view-switch-menu.png");
                onGl(() -> { GpuBoardTestUi.click("/viewClassicBoard"); return null; });
                await(() -> onSwing(() -> mapWindow("board-preview-2d") != null));
                assertTrue(source.isClosed());
                JDialog classic = onSwing(() -> (JDialog) mapWindow("board-preview-2d"));
                captureClassic(classic, "preview-view-3d", "preview-view-switch-2d.png");
                Application nativeView = Gdx.app;
                onSwing(() -> { namedButton(classic, "preview-view-3d").doClick(0); return null; });
                await(() -> Gdx.app != null && Gdx.app != nativeView);
                await(() -> previewSource() != null);
                BoardSource resumed = previewSource();
                await(() -> previewReady(resumed));
                assertFalse(onSwing(classic::isDisplayable), "Each switch releases the old classic renderer");
                var field = GpuMapSource.class.getDeclaredField("game"); field.setAccessible(true);
                assertSame(board, ((megamek.common.game.Game) field.get(resumed)).getBoard(), "Switching keeps the exact preview board");
                assertEquals(3, resumed.takeFrame().scene().tile(new Coords(3, 4)).elevation());
            }
        } finally {
            onSwing(() -> { GpuBoardWindow.closeFor((ClientGUI) null); launcher.dispose(); return null; });
        }
    }

    @Test
    void editorMenuSwitchesBothWaysWithSharedEditsUndoAndNativeSave() throws Exception {
        var file = Files.createTempFile("editor-view-switch-", ".board2");
        Board board = Board.createEmptyBoard(8, 8);
        Coords at = new Coords(3, 3);
        var car = new BoardDecoration("kept-car", "prop", "scenery/vehicles/car", null, 0, 0, 0, false, 1,
              BoardDecoration.Placement.ground(), 0);
        board.getHex(at).setDecorations(List.of(car));
        BoardFile.save(board, file);
        byte[] saved = Files.readAllBytes(file);
        JFrame launcher = onSwing(() -> { var frame = new JFrame("Editor launcher"); frame.setSize(400, 300); frame.setVisible(true); return frame; });
        try {
            Application previous = Gdx.app;
            onSwing(() -> { GpuBoardWindow.openEditor(launcher, file.toFile(), false); return null; });
            await(() -> Gdx.app != null && Gdx.app != previous);
            await(() -> previewSource() != null);
            var editorField = GpuMapSource.class.getDeclaredField("editor"); editorField.setAccessible(true);
            BoardEditorSession session = (BoardEditorSession) editorField.get(previewSource());
            onSwing(() -> {
                session.pointer(at, 0, 0, false);
                session.command(new BoardEditorSession.Command(BoardEditorSession.Action.ELEVATION, "2"), launcher);
                return null;
            });
            for (int round = 0; round < 2; round++) {
                BoardSource source = previewSource();
                await(() -> previewReady(source)); awaitNavigation();
                input(() -> GpuBoardTestUi.click("utility-menu"));
                captureMenu("editor-view-switch-menu.png");
                onGl(() -> { GpuBoardTestUi.click("/viewClassicBoard"); return null; });
                await(() -> onSwing(() -> mapWindow("board-editor-2d") != null));
                assertTrue(source.isClosed());
                JFrame classicFrame = onSwing(() -> (JFrame) mapWindow("board-editor-2d"));
                BoardEditorPanel classic = onSwing(() -> Arrays.stream(classicFrame.getContentPane().getComponents())
                      .filter(BoardEditorPanel.class::isInstance).map(BoardEditorPanel.class::cast).findFirst().orElseThrow());
                assertSame(session.game(), classic.getGame());
                assertTrue(classic.hasClassicView());
                captureClassic(classicFrame, "editor-view-3d", "editor-view-switch-2d.png");
                if (round == 0) {
                    assertArrayEquals(saved, Files.readAllBytes(file), "Switching unsaved edits must not save or reload a file");
                    onSwing(() -> {
                        classic.actionPerformed(new java.awt.event.ActionEvent(classic, 0, ClientGUI.BOARD_UNDO));
                        assertEquals(0, session.board().getHex(at).getLevel(), "2D Undo includes the preceding 3D edit");
                        classic.actionPerformed(new java.awt.event.ActionEvent(classic, 0, ClientGUI.BOARD_REDO));
                        classic.adjustElevation(Map.of(at, 1)); classic.finishBrushStroke();
                        assertEquals(3, session.board().getHex(at).getLevel());
                        setField(BoardEditorPanel.class, classic, "curHex", new Hex(0, "woods:1;foliage_elev:2", "grass"));
                        classic.paintIn3D(at, 0); classic.finishBrushStroke();
                        assertEquals(List.of(car), session.board().getHex(at).getDecorations(), "2D terrain painting preserves native objects");
                        assertTrue(session.dirty());
                        return null;
                    });
                } else {
                    onSwing(() -> {
                        classic.actionPerformed(new java.awt.event.ActionEvent(classic, 0, ClientGUI.BOARD_SAVE));
                        assertFalse(session.dirty());
                        return null;
                    });
                    assertEquals(List.of(car), BoardFile.read(file).getHex(at).getDecorations(), "2D Save uses the native document format");
                }
                Application nativeView = Gdx.app;
                onSwing(() -> { namedButton(classicFrame, "editor-view-3d").doClick(0); return null; });
                await(() -> Gdx.app != null && Gdx.app != nativeView);
                await(() -> previewSource() != null);
                BoardSource resumed = previewSource();
                assertSame(session, editorField.get(resumed));
                await(() -> previewReady(resumed));
                assertFalse(onSwing(classicFrame::isDisplayable));
                assertFalse(classic.hasClassicView());
                assertEquals(3, resumed.takeFrame().scene().tile(at).elevation());
                onSwing(() -> {
                    session.command(new BoardEditorSession.Command(BoardEditorSession.Action.UNDO), launcher);
                    assertFalse(session.board().getHex(at).containsTerrain(Terrains.WOODS), "3D Undo includes the preceding 2D brush");
                    session.command(new BoardEditorSession.Command(BoardEditorSession.Action.REDO), launcher);
                    assertTrue(session.board().getHex(at).containsTerrain(Terrains.WOODS));
                    return null;
                });
            }
        } finally {
            onSwing(() -> {
                GpuBoardWindow.closeFor((ClientGUI) null);
                Window classic = mapWindow("board-editor-2d");
                if (classic instanceof JFrame frame) {
                    Arrays.stream(frame.getContentPane().getComponents()).filter(BoardEditorPanel.class::isInstance)
                          .map(BoardEditorPanel.class::cast).forEach(BoardEditorPanel::dispose);
                }
                launcher.dispose(); return null;
            });
            Files.deleteIfExists(file);
        }
    }

    private static Window mapWindow(String name) {
        return Arrays.stream(Window.getWindows()).filter(window -> name.equals(window.getName()) && window.isShowing())
              .findFirst().orElse(null);
    }

    private static void captureClassic(Window window, String buttonName, String filename) throws Exception {
        onSwing(() -> {
            AbstractButton button = namedButton(window, buttonName);
            assertTrue(button.isShowing(), "The return control is visible in the classic view");
            assertTrue(button.getVisibleRect().width > 0 && button.getVisibleRect().height > 0);
            var image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { window.printAll(graphics); } finally { graphics.dispose(); }
            File output = new File("build/gpu-board-review");
            output.mkdirs();
            ImageIO.write(image, "png", new File(output, filename));
            return null;
        });
    }

    private static AbstractButton namedButton(java.awt.Container parent, String name) {
        for (var component : parent.getComponents()) {
            if (component instanceof AbstractButton button && name.equals(button.getName())) { return button; }
            if (component instanceof java.awt.Container child) {
                AbstractButton button = namedButton(child, name);
                if (button != null) { return button; }
            }
        }
        return null;
    }

    private static boolean previewReady(BoardSource source) throws Exception {
        return onGl(() -> {
            var view = (GpuBattleView) Gdx.app.getApplicationListener();
            var terrainField = GpuBattleView.class.getDeclaredField("terrain");
            terrainField.setAccessible(true);
            var terrain = (GpuTerrain) terrainField.get(view);
            var generationField = GpuBattleView.class.getDeclaredField("boardGeneration");
            generationField.setAccessible(true);
            return terrain != null && generationField.getLong(view) == source.takeFrame().boardGeneration()
                  && terrain.ready(source.takeFrame().scene());
        });
    }

    private static BoardSource previewSource() throws Exception {
        return onGl(() -> {
            var field = GpuBattleView.class.getDeclaredField("source");
            field.setAccessible(true);
            return (BoardSource) field.get(Gdx.app.getApplicationListener());
        });
    }

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
            // A case that restored the window to a plain size saved that; the reopened board must be maximized again.
            GUIPreferences.getInstance().setValue(GUIPreferences.GPU_BOARD_MAXIMIZED, true);
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
        // A cold native startup uploads the board and models before servicing queued input.
        return task.get(30, TimeUnit.SECONDS);
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
