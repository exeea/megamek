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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.IDisplayable;
import megamek.client.ui.Messages;
import megamek.client.ui.boardeditor.BoardEditorPanel;
import megamek.client.ui.clientGUI.AbstractClientGUI;
import megamek.client.ui.clientGUI.BoardViewsContainer;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommandBarPanel;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.overlay.UnitOverviewOverlay;
import megamek.client.ui.dialogs.BotCommands.BotCommandsDialog;
import megamek.client.ui.dialogs.BotCommands.BotCommandsPanel;
import megamek.client.ui.dialogs.forceDisplay.ForceDisplayDialog;
import megamek.client.ui.dialogs.forceDisplay.ForceDisplayPanel;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayDialog;
import megamek.client.ui.dialogs.miniReport.MiniReportDisplayPanel;
import megamek.client.ui.dialogs.minimap.MinimapDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayDialog;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.entityreadout.LiveReadoutDialog;
import megamek.client.ui.panels.StartingScenarioPanel;
import megamek.client.ui.panels.WaitingForServerPanel;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay;
import megamek.client.ui.util.KeyCommandBind;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.Report;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekFileParser;
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
    private record ClientWindow(JFrame frame, CommonMenuBar menus, BoardView view, JMenuItem gpuChoice,
          UnitOverviewOverlay overview) { }

    @Test
    void initialBoardAndSidebarClicksSelectWithoutOpeningAUnitMenuFirst() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            ClientGUI gui = ui.view().getClientgui();
            Entity second = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            UnitDisplayPanel display = onSwing(() -> {
                second.setId(2);
                second.setOwner(fixture.player);
                second.setPosition(new Coords(8, 6));
                second.setDeployed(true);
                fixture.game.addEntity(second, false);
                fixture.game.setPhase(GamePhase.INITIATIVE_REPORT);
                ReportDisplay report = mock(ReportDisplay.class);
                when(report.getComponents()).thenReturn(new Component[0]);
                fixture.panel = report;
                when(gui.getCurrentPanel()).thenAnswer(invocation -> fixture.panel);
                when(gui.getUnitDisplayDialog()).thenReturn(mock(UnitDisplayDialog.class));
                UnitDisplayPanel panel = new UnitDisplayPanel(gui, null);
                when(gui.getUnitDisplay()).thenReturn(panel);
                when(gui.getDisplayedUnit()).thenAnswer(invocation -> panel.getCurrentEntity());
                doAnswer(invocation -> panel.getCurrentEntity()).when(ui.view()).getSelectedEntity();
                setField(ClientGUI.class, gui, "client", gui.getClient());
                doCallRealMethod().when(gui).unitSelected(any());
                doCallRealMethod().when(gui).inspectUnit(org.mockito.ArgumentMatchers.anyInt());
                doCallRealMethod().when(gui).setSelectedEntityNum(org.mockito.ArgumentMatchers.anyInt());
                ui.view().addBoardViewListener(gui);
                ui.view().addOverlay(ui.overview());
                return panel;
            });
            try {
                openNative(ui);
                GpuBoardSource source = previewSource();
                assertNull(onSwing(display::getCurrentEntity));
                for (boolean isometric : new boolean[] { false, true }) {
                    input(() -> {
                        BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
                        camera.setIsometric(isometric);
                        camera.fit(source.takeFrame().scene());
                    });
                    awaitNavigation();
                    await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.entranceOpacity() == 1));
                    Vector3 before = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy());
                    input(() -> clickBoard(fixture.entity.getPosition(), Input.Buttons.LEFT));
                    await(() -> onSwing(() -> display.getCurrentEntity() == fixture.entity));
                    awaitNavigation();
                    assertEquals(fixture.entity.getId(), source.takeFrame().scene().selectedId());
                    assertEquals(before, onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy()),
                          "Selecting on the board must leave the camera alone");
                    Vector3 beforeSidebar = onGl(() -> {
                        BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
                        // Top view only pans when needed to bring the selected unit into view.
                        camera.pan(2f * Gdx.graphics.getWidth(), 0);
                        return camera.focus.cpy();
                    });
                    int unselectedBorder = onSwing(() -> sidebarBorderColor(ui.overview(), 1));
                    input(() -> {
                        float scale = Gdx.graphics.getWidth() / GpuBoardTestUi.stage().getWidth();
                        float overlayScale = scale / GUIPreferences.getInstance().getGUIScale();
                        int x = Math.round(Gdx.graphics.getWidth() - 33 * overlayScale);
                        int y = Math.round(GpuBoardUi.TOP_HEIGHT * scale + 82 * overlayScale);
                        Gdx.input.getInputProcessor().touchDown(x, y, 0, Input.Buttons.LEFT);
                        Gdx.input.getInputProcessor().touchUp(x, y, 0, Input.Buttons.LEFT);
                    });
                    await(() -> onSwing(() -> display.getCurrentEntity() == second));
                    awaitNavigation();
                    assertEquals(second.getId(), source.takeFrame().scene().selectedId());
                    assertEquals(second.getId(), onSwing(() -> ui.view().getCenterRequest().entityId()));
                    assertNotEquals(unselectedBorder, onSwing(() -> sidebarBorderColor(ui.overview(), 1)),
                          "The sidebar highlights selection without a turn");
                    assertNotEquals(beforeSidebar, onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy()),
                          "Sidebar selection retains camera navigation");
                    onSwing(() -> {
                        fixture.game.setPhase(GamePhase.STARTING_SCENARIO);
                        fixture.panel = new JPanel();
                        source.refresh();
                        return null;
                    });
                }
                assertFalse(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").isVisible()));
            } finally {
                onSwing(() -> {
                    ui.view().removeBoardViewListener(gui);
                    GUIPreferences.getInstance().removePreferenceChangeListener(ui.overview());
                    GpuBoardWindow.closeFor(ui.view());
                    ui.frame().dispose();
                    ui.menus().die();
                    return null;
                });
            }
        }
    }

    private static int sidebarBorderColor(UnitOverviewOverlay overview, int index) {
        var graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            var portraits = overview.captureLayers(graphics, new Rectangle(0, 0, 800, 600));
            return portraits.get(index).image().getRGB(10, 2);
        } finally {
            graphics.dispose();
        }
    }

    @Test
    void gameClicksSelectUnitsPlotTerrainAndDismissMenusWithoutMovingTheCamera() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/AGoAC Maps/16x17 Grassland 3.board"));
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            AtomicInteger phaseActions = new AtomicInteger();
            AtomicReference<BoardViewEvent> lastMouseEvent = new AtomicReference<>();
            AtomicInteger unitSelections = new AtomicInteger();
            RulerDialog ruler = onSwing(() -> new RulerDialog(ui.frame(), ui.view(), fixture.game));
            try {
                onSwing(() -> {
                    when(ui.view().getClientgui().getClient().isMyTurn()).thenReturn(true);
                    MovementDisplay movement = mock(MovementDisplay.class);
                    when(movement.currentEntity()).thenAnswer(invocation -> ui.view().getSelectedEntity());
                    when(movement.getComponents()).thenReturn(new Component[0]);
                    when(movement.getActionButtons()).thenReturn(List.of());
                    when(movement.getCompletionButtons()).thenReturn(List.of());
                    fixture.panel = movement;
                    ui.view().addBoardViewListener(new BoardViewListenerAdapter() {
                        @Override
                        public void hexSelected(BoardViewEvent event) {
                            phaseActions.incrementAndGet();
                        }

                        @Override
                        public void hexMoused(BoardViewEvent event) {
                            if ((event.getModifiers() & InputEvent.ALT_DOWN_MASK) == 0) {
                                phaseActions.incrementAndGet();
                            }
                            lastMouseEvent.set(event);
                        }

                        @Override
                        public void unitSelected(BoardViewEvent event) {
                            assertEquals(fixture.entity.getId(), event.getEntityId());
                            unitSelections.incrementAndGet();
                            doReturn(fixture.entity).when(ui.view()).getSelectedEntity();
                            ui.view().selectEntity(fixture.entity);
                            // Real phase selection also requests centering; mouse selection must suppress it.
                            ui.view().centerOn(fixture.entity);
                        }
                    });
                    return null;
                });
                openNative(ui);
                GpuBoardSource source = previewSource();
                Coords unit = fixture.entity.getPosition();
                Coords empty = new Coords(2, 3);
                for (boolean isometric : new boolean[] { false, true }) {
                    onSwing(() -> {
                        doReturn(null).when(ui.view()).getSelectedEntity();
                        source.refresh();
                        return null;
                    });
                    int previousSelections = unitSelections.get();
                    input(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.setIsometric(isometric));
                    awaitNavigation();
                    await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.entranceOpacity() == 1));
                    Vector3 cameraFocus = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy());
                    float zoom = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.camera.zoom);
                    int unselectedActions = phaseActions.get();
                    input(() -> clickBoard(empty, Input.Buttons.LEFT));
                    await(() -> onSwing(() -> empty.equals(ui.view().getSelected())));
                    assertEquals(unselectedActions, phaseActions.get(), "Terrain clicks cannot plot without a selected unit");
                    input(() -> clickBoard(unit, Input.Buttons.LEFT));
                    await(() -> onSwing(() -> unit.equals(ui.view().getSelected()) && unitSelections.get() == previousSelections + 1));
                    awaitNavigation();
                    onGl(() -> {
                        BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
                        assertEquals(cameraFocus, camera.focus, "Mouse selection must keep the camera position");
                        assertEquals(zoom, camera.camera.zoom, "Mouse selection must keep the camera zoom");
                        return null;
                    });
                    assertFalse(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").isVisible()));
                    assertEquals(previousSelections + 1, unitSelections.get());
                    int previousActions = phaseActions.get();
                    input(() -> clickBoard(empty, Input.Buttons.RIGHT));
                    await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("board.useHex") != null));
                    assertEquals(unit, onSwing(() -> ui.view().getSelected()), "Opening the menu does not select its hex");
                    input(() -> {
                        var processor = Gdx.input.getInputProcessor();
                        processor.touchDown(100, 200, 0, Input.Buttons.LEFT);
                        processor.touchUp(100, 200, 0, Input.Buttons.LEFT);
                    });
                    assertFalse(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").isVisible()));
                    assertEquals(unit, onSwing(() -> ui.view().getSelected()), "The dismissing click is consumed");
                    assertEquals(previousActions, phaseActions.get(), "Dismissing the menu must not plot movement");
                    input(() -> clickBoard(empty, Input.Buttons.LEFT));
                    await(() -> onSwing(() -> empty.equals(ui.view().getSelected())));
                    assertEquals(previousActions + 2, phaseActions.get(), "Terrain clicks invoke the existing movement tool");
                    assertEquals(cameraFocus, onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy()),
                          "Plotting movement must also retain the camera position");
                    input(() -> clickBoard(empty, Input.Buttons.RIGHT));
                    await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("board.useHex") != null));
                    input(() -> GpuBoardTestUi.click("board.useHex"));
                    await(() -> phaseActions.get() == previousActions + 4);
                    assertFalse(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").isVisible()));
                    input(() -> clickBoard(unit, Input.Buttons.LEFT));
                    await(() -> onSwing(() -> unit.equals(ui.view().getSelected())));
                    assertEquals(previousActions + 4, phaseActions.get(), "Unit clicks select instead of plotting");
                    assertEquals(previousSelections + 1, unitSelections.get(), "Reselecting the active unit preserves orders");
                    input(() -> clickBoard(empty, Input.Buttons.LEFT, InputEvent.SHIFT_DOWN_MASK));
                    onSwing(() -> {
                        assertEquals(previousActions + 6, phaseActions.get());
                        assertEquals(empty, lastMouseEvent.get().getCoords());
                        assertEquals(InputEvent.SHIFT_DOWN_MASK, lastMouseEvent.get().getModifiers(),
                              "Shift-click must reach the phase's orientation handler");
                        assertEquals(previousSelections + 1, unitSelections.get());
                        return null;
                    });
                    for (String measurement : List.of("board.los", "board.ruler")) {
                        input(() -> clickBoard(empty, Input.Buttons.RIGHT));
                        await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor(measurement) != null));
                        input(() -> GpuBoardTestUi.click(measurement));
                        await(() -> onSwing(() -> empty.equals(ui.view().getRulerStart()) && ui.view().getRulerEnd() == null));
                        assertTrue(source.phaseStatus.text().contains("Left-click an endpoint"));
                        assertFalse(onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").isVisible()));
                        int actionsBeforeMeasurement = phaseActions.get();
                        input(() -> clickBoard(unit, Input.Buttons.LEFT));
                        await(() -> onSwing(() -> unit.equals(ui.view().getRulerEnd())));
                        onSwing(() -> {
                            assertEquals(empty, ui.view().getRulerStart());
                            assertNull(ui.view().getFirstLOS());
                            assertEquals(actionsBeforeMeasurement, phaseActions.get(), "Measuring cannot plot movement");
                            assertEquals(previousSelections + 1, unitSelections.get(), "A unit can be a measurement endpoint");
                            assertFalse(source.phaseStatus.text().contains("Left-click an endpoint"));
                            return null;
                        });
                        assertEquals(cameraFocus, onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy()));
                        input(() -> clickBoard(empty, Input.Buttons.LEFT));
                        await(() -> phaseActions.get() == actionsBeforeMeasurement + 2);
                    }
                }
                input(() -> clickBoard(empty, Input.Buttons.RIGHT));
                await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("board.los") != null));
                input(() -> GpuBoardTestUi.click("board.los"));
                await(() -> onSwing(() -> empty.equals(ui.view().getFirstLOS())));
                onSwing(() -> {
                    ruler.dispatchEvent(new WindowEvent(ruler, WindowEvent.WINDOW_CLOSING));
                    source.refresh();
                    assertNull(ui.view().getFirstLOS(), "Closing the ruler cancels its pending LOS endpoint");
                    assertNull(ui.view().getRulerStart());
                    assertFalse(source.phaseStatus.text().contains("Left-click an endpoint"));
                    return null;
                });
                int beforeCancelClick = phaseActions.get();
                input(() -> clickBoard(empty, Input.Buttons.LEFT));
                await(() -> phaseActions.get() == beforeCancelClick + 2);
                Vector3 beforeCenter = onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus.cpy());
                onSwing(() -> {
                    ui.view().centerOn(fixture.entity);
                    source.refresh();
                    assertFalse(source.takeFrame().keepSelectionCamera(), "A later explicit centering request remains effective");
                    return null;
                });
                awaitNavigation();
                assertFalse(onGl(() -> beforeCenter.epsilonEquals(
                      ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.focus, .001f)),
                      "Centering from other selection paths must still move the camera");
                assertFalse(source.isClosed());
            } finally {
                onSwing(() -> {
                    ui.view().removeBoardViewListener(ruler);
                    ruler.dispose();
                    GUIPreferences.getInstance().removePreferenceChangeListener(ui.overview());
                    GpuBoardWindow.closeFor(ui.view());
                    ui.frame().dispose();
                    ui.menus().die();
                    return null;
                });
                await(() -> Thread.getAllStackTraces().keySet().stream()
                      .noneMatch(thread -> thread.getName().equals("MegaMek-GPU-board")));
            }
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
    void editorSwitchSharesBrushesUndoHistoryAndBoardAndRejectsStalePicks() throws Exception {
        boolean nag = GUIPreferences.getInstance().getNagForMapEdReadme();
        GUIPreferences.getInstance().setNagForMapEdReadme(false);
        BoardEditorPanel editor = onSwing(() -> {
            BoardEditorPanel result = new BoardEditorPanel(null);
            MapSettings settings = MapSettings.getInstance();
            settings.setBoardSize(8, 8);
            setField(BoardEditorPanel.class, result, "mapSettings", settings);
            result.boardNew(false);
            Board board = result.getBoardView().game.getBoard();
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) {
                    board.setHex(x, y, new Hex());
                }
            }
            editorButton(result, "buttonLW").doClick(0);
            return result;
        });
        BoardView view = editor.getBoardView();
        Board board = view.game.getBoard();
        Coords first = new Coords(3, 3);
        Coords second = new Coords(3, 4);
        try {
            openEditor(editor);
            GpuBoardSource source = previewSource();
            assertTrue(source.isEditor());
            assertSame(view, source.currentView());
            await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.entranceOpacity() == 1));
            onGl(() -> {
                BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
                var frame = source.takeFrame();
                float right = camera.camera.viewportWidth * (1 - frame.hud().sidePanelInset() / frame.hud().width());
                for (var tile : frame.scene().tiles()) {
                    for (int corner = 0; corner < 6; corner++) {
                        Vector3 point = camera.camera.project(BoardGeometry.corner(tile.coords(), tile.elevation(), corner),
                              0, 0, camera.camera.viewportWidth, camera.camera.viewportHeight);
                        assertTrue(point.x > 0 && point.x < right, "The initial fit leaves the board clear of the tools palette");
                    }
                }
                return null;
            });
            input(() -> {
                assertCameraDrag(Input.Buttons.RIGHT, false, false);
                assertCameraDrag(Input.Buttons.MIDDLE, false, true);
                assertCameraDrag(Input.Buttons.RIGHT, true, true);
                assertCameraDrag(Input.Buttons.MIDDLE, true, false);
                ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.fit(source.takeFrame().scene());
            });
            onSwing(() -> {
                assertFalse(editor.getFrame().isShowing());
                assertTrue(SwingUtilities.getWindowAncestor(editor) instanceof JDialog);
                assertTrue(editor.isShowing());
                assertEquals(Messages.getString("BoardEditor.edit2D"), editorButton(editor, "editorViewButton").getText());
                var switchButton = editorButton(editor, "editorViewButton");
                Rectangle visible = SwingUtilities.convertRectangle(switchButton.getParent(), switchButton.getBounds(), editor);
                assertTrue(editor.getVisibleRect().contains(visible), "The mode switch stays visible at the bottom of the tools");
                File output = new File("build/gpu-board-review");
                assertTrue(output.isDirectory() || output.mkdirs());
                var image = new java.awt.image.BufferedImage(editor.getWidth(), editor.getHeight(),
                      java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                editor.printAll(graphics);
                graphics.dispose();
                javax.imageio.ImageIO.write(image, "jpg", new File(output, "editor-tools.jpg"));
                return null;
            });
            input(() -> editorStroke(source, List.of(first, second), 0, true));
            onSwing(() -> {
                assertTrue(board.getHex(first).containsTerrain(Terrains.WOODS));
                assertTrue(board.getHex(second).containsTerrain(Terrains.WOODS));
                assertTrue(editor.getFrame().getTitle().endsWith("*"));
                return null;
            });
            pressShortcut(KeyCommandBind.UNDO);
            onSwing(() -> {
                assertFalse(board.getHex(first).containsTerrain(Terrains.WOODS));
                assertFalse(board.getHex(second).containsTerrain(Terrains.WOODS), "One drag is one undo step");
                return null;
            });
            pressShortcut(KeyCommandBind.REDO);
            onSwing(() -> {
                assertTrue(board.getHex(first).containsTerrain(Terrains.WOODS));
                assertTrue(board.getHex(second).containsTerrain(Terrains.WOODS));
                editorButton(editor, "butElevUp").doClick(0);
                editorButton(editor, "butElevUp").doClick(0);
                return null;
            });
            Coords elevated = new Coords(4, 2);
            Coords sampled = new Coords(4, 3);
            input(() -> editorStroke(source, List.of(elevated), InputEvent.CTRL_DOWN_MASK, false));
            onSwing(() -> {
                assertEquals(2, board.getHex(elevated).getLevel(), "Ctrl paints the selected elevation instead of measuring");
                editorButton(editor, "buttonRo").doClick(0);
                return null;
            });
            input(() -> editorStroke(source, List.of(elevated), InputEvent.ALT_DOWN_MASK, false));
            input(() -> editorStroke(source, List.of(sampled), 0, false));
            assertTrue(onSwing(() -> board.getHex(sampled).containsTerrain(Terrains.WOODS)), "Alt samples the existing hex");
            pressShortcut(KeyCommandBind.UNDO);
            pressShortcut(KeyCommandBind.UNDO);
            assertEquals(0, onSwing(() -> board.getHex(elevated).getLevel()), "Sampling does not create an undo step");
            onSwing(() -> {
                editorButton(editor, "buttonRaiseLower").doClick(0);
                return null;
            });
            input(() -> editorStroke(source, List.of(first), InputEvent.SHIFT_DOWN_MASK, false));
            input(() -> editorStroke(source, List.of(first), InputEvent.SHIFT_DOWN_MASK, false));
            assertEquals(2, onSwing(() -> board.getHex(first).getLevel()), "Consecutive clicks start fresh elevation strokes");
            await(() -> onGl(() -> {
                var field = GpuBattleView.class.getDeclaredField("scene");
                field.setAccessible(true);
                BoardScene rendered = (BoardScene) field.get(Gdx.app.getApplicationListener());
                return rendered.tile(first).elevation() == 2;
            }));
            input(() -> GpuBoardTestUi.capture(new File("build/gpu-board-review/editor-3d.png")));
            input(() -> GpuBoardTestUi.click("editor-2d"));
            await(() -> onSwing(() -> source.isClosed() && editor.getFrame().isShowing()));
            onSwing(() -> {
                assertSame(board, view.game.getBoard());
                assertSame(editor.getFrame(), SwingUtilities.getWindowAncestor(editor));
                assertEquals(Messages.getString("BoardEditor.edit3D"), editorButton(editor, "editorViewButton").getText());
                editorButton(editor, "buttonUndo").doClick(0);
                assertEquals(1, board.getHex(first).getLevel(), "2D undo includes the previous 3D edit");
                return null;
            });
            openEditor(editor);
            GpuBoardSource reopened = previewSource();
            long generation = reopened.takeFrame().boardGeneration();
            onSwing(() -> { editor.boardNew(false); return null; });
            await(() -> reopened.takeFrame().boardGeneration() != generation);
            Board replacement = view.game.getBoard();
            int oldLevel = onSwing(() -> replacement.getHex(first).getLevel());
            reopened.paintEditor(first, InputEvent.SHIFT_DOWN_MASK, generation);
            reopened.endEditorStroke();
            onSwing(() -> {
                assertEquals(oldLevel, replacement.getHex(first).getLevel(), "Old board picks cannot edit the new board");
                return null;
            });
            onGl(() -> {
                long handle = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
                var callback = GLFW.glfwSetWindowCloseCallback(handle, null);
                GLFW.glfwSetWindowCloseCallback(handle, callback);
                callback.invoke(handle);
                return null;
            });
            await(() -> onSwing(() -> reopened.isClosed() && editor.getFrame().isShowing()));
            assertSame(replacement, view.game.getBoard(), "Native close returns to the same 2D editor");
        } finally {
            onSwing(() -> {
                GpuBoardWindow.closeFor(view);
                view.dispose();
                for (Window owned : editor.getFrame().getOwnedWindows()) { owned.dispose(); }
                editor.getFrame().dispose();
                ((CommonMenuBar) editor.getMenuBar()).die();
                return null;
            });
            GUIPreferences.getInstance().setNagForMapEdReadme(nag);
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

    private static AbstractButton editorButton(BoardEditorPanel editor, String name) throws Exception {
        var field = BoardEditorPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return (AbstractButton) field.get(editor);
    }

    private static void openEditor(BoardEditorPanel editor) throws Exception {
        Application previous = Gdx.app;
        onSwing(() -> { editorButton(editor, "editorViewButton").doClick(0); return null; });
        await(() -> Gdx.app != null && Gdx.app != previous);
        await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
        awaitNavigation();
    }

    /** Real native picking and drag dispatch, including releases over the toolbar. */
    private static void editorStroke(GpuBoardSource source, List<Coords> hexes, int modifiers, boolean releaseOverToolbar) {
        Input original = Gdx.input;
        Input keyboard = mock(Input.class);
        when(keyboard.getInputProcessor()).thenReturn(original.getInputProcessor());
        when(keyboard.isKeyPressed(Input.Keys.SHIFT_LEFT)).thenReturn((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.CONTROL_LEFT)).thenReturn((modifiers & InputEvent.CTRL_DOWN_MASK) != 0);
        when(keyboard.isKeyPressed(Input.Keys.ALT_LEFT)).thenReturn((modifiers & InputEvent.ALT_DOWN_MASK) != 0);
        Gdx.input = keyboard;
        try {
            BoardCamera camera = ((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera;
            int bottom = Math.round(34 * Gdx.graphics.getHeight() / GpuBoardTestUi.stage().getHeight());
            Point last = null;
            for (Coords coords : hexes) {
                Vector3 point = camera.camera.project(BoardGeometry.center(coords, source.takeFrame().scene().tile(coords).elevation()),
                      0, bottom, camera.camera.viewportWidth, camera.camera.viewportHeight);
                Point screen = new Point(Math.round(point.x), Gdx.graphics.getHeight() - Math.round(point.y));
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
    void mapPreviewKeepsTheModalBrowserOpenAndReleasesResourcesWhenClosedOrReplaced() throws Exception {
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
            GpuBoardSource first = previewSource();
            onSwing(() -> {
                assertTrue(browser.isShowing());
                assertEquals(0, browserReturned.get(), "Preview must not accept or dismiss the map picker");
                assertFalse(GUIPreferences.getInstance().getUse3DBoard());
                assertEquals(16, first.currentView().game.getBoard().getWidth());
                assertEquals(17, first.currentView().game.getBoard().getHeight());
                assertTrue(first.currentView().game.getEntitiesVector().isEmpty());
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
            GpuBoardSource second = previewSource();
            Application secondApplication = Gdx.app;
            onSwing(() -> {
                assertEquals(6, second.currentView().game.getBoard().getWidth());
                GpuBoardWindow.openPreview(browser, Board.createEmptyBoard(8, 10));
                return null;
            });
            await(() -> second.isClosed());
            await(() -> Gdx.app != null && Gdx.app != secondApplication);
            await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
            GpuBoardSource third = previewSource();
            onSwing(() -> {
                assertEquals(8, third.currentView().game.getBoard().getWidth());
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
                GpuBoardSource preview = previewSource();
                Application previewApplication = Gdx.app;
                onSwing(() -> {
                    assertTrue(preview.currentView().game != fixture.game);
                    assertTrue(preview.currentView().game.getEntitiesVector().isEmpty());
                    GpuBoardWindow.open(ui.view(), () -> fixture.panel);
                    ui.frame().setVisible(false);
                    return null;
                });
                await(() -> GpuBoardWindow.isActiveFor(ui.view().getClientgui()));
                await(() -> Gdx.app != null && Gdx.app != previewApplication);
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                assertTrue(preview.isClosed());
                assertFalse(onSwing(() -> ui.frame().isVisible()));
                assertSame(fixture.game, previewSource().currentView().game);
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

    private static GpuBoardSource previewSource() throws Exception {
        return onGl(() -> {
            var field = GpuBattleView.class.getDeclaredField("source");
            field.setAccessible(true);
            return (GpuBoardSource) field.get(Gdx.app.getApplicationListener());
        });
    }

    @Test
    void startsDirectlyInThreeDimensionsWithoutConstructingTheClassicViewport() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture, false));
            try {
                onSwing(() -> {
                    ui.view().centerOn(fixture.entity);
                    assertEquals(fixture.entity.getId(), ui.view().getCenterRequest().entityId());
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
                    GpuBoardWindow.closeFor(ui.view());
                    ui.frame().dispose();
                    ui.view().dispose();
                    GUIPreferences.getInstance().removePreferenceChangeListener(ui.menus());
                    return null;
                });
            }
        }
    }

    @Test
    void unitDialogsOpenOverTheNativeBoardAndKeepTheirClassicSettings() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        int location = preferences.getUnitDisplayLocation();
        boolean unitEnabled = preferences.getUnitDisplayEnabled();
        boolean forceEnabled = preferences.getForceDisplayEnabled();
        boolean overviewEnabled = preferences.getShowUnitOverview();
        AtomicInteger restoredLocation = new AtomicInteger(-1);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture));
            ClientGUI gui = ui.view().getClientgui();
            UnitDisplayDialog unit = onSwing(() -> {
                preferences.setUnitDisplayLocation(1);
                preferences.setUnitDisplayEnabled(false);
                preferences.setForceDisplayEnabled(false);
                preferences.setShowUnitOverview(false);
                UnitDisplayPanel panel = new UnitDisplayPanel(gui);
                UnitDisplayDialog dialog = new UnitDisplayDialog(ui.frame(), gui);
                when(gui.getUnitDisplay()).thenReturn(panel);
                when(gui.getUnitDisplayDialog()).thenReturn(dialog);
                panel.displayEntity(fixture.entity);
                doCallRealMethod().when(gui).setUnitDisplayVisible(anyBoolean());
                doAnswer(invocation -> {
                    if (GpuBoardWindow.isActiveFor(gui)) {
                        invocation.callRealMethod();
                    } else {
                        // Observe the request; this fixture has no legacy split panes.
                        restoredLocation.set(preferences.getUnitDisplayLocation());
                    }
                    return null;
                }).when(gui).setUnitDisplayLocation(anyBoolean());
                doCallRealMethod().when(gui).setForceDisplayVisible(anyBoolean());
                doCallRealMethod().when(gui).actionPerformed(any());
                doCallRealMethod().when(gui).preferenceChange(any());
                ui.menus().addActionListener(gui);
                preferences.addPreferenceChangeListener(gui);
                ui.view().addOverlay(ui.overview());
                return dialog;
            });
            ForceDisplayDialog force = onSwing(() -> {
                ForceDisplayDialog dialog = new ForceDisplayDialog(ui.frame(), gui);
                ForceDisplayPanel panel = new ForceDisplayPanel(gui);
                when(gui.getForceDisplayPanel()).thenReturn(panel);
                dialog.add(panel);
                when(gui.getForceDisplayDialog()).thenReturn(dialog);
                // Reuse a dialog that has already emitted WINDOW_OPENED in the classic view.
                dialog.setVisible(true);
                dialog.setVisible(false);
                return dialog;
            });
            try {
                openNative(ui);
                await(() -> onSwing(() -> ui.view().sidePanelInset() > 0));
                input(() -> GpuBoardTestUi.click("battle-report-toggle"));
                pressShortcut(KeyCommandBind.UNIT_DISPLAY);
                await(() -> onSwing(() -> unit.isShowing() && unit.isAlwaysOnTop()));
                pressShortcut(KeyCommandBind.FORCE_DISPLAY);
                await(() -> onSwing(() -> force.isShowing() && force.isAlwaysOnTop()));
                onSwing(() -> {
                    assertFalse(ui.frame().isVisible());
                    assertTrue(SwingUtilities.isDescendingFrom(gui.getUnitDisplay(), unit));
                    assertSame(fixture.entity, gui.getUnitDisplay().getCurrentEntity());
                    assertEquals(1, preferences.getUnitDisplayLocation());
                    force.dispatchEvent(new WindowEvent(force, WindowEvent.WINDOW_CLOSING));
                    force.setAlwaysOnTop(false);
                    fixture.game.setPhase(GamePhase.FIRING_REPORT);
                    ui.menus().setPhase(GamePhase.FIRING_REPORT);
                    return null;
                });
                pressShortcut(KeyCommandBind.FORCE_DISPLAY);
                await(() -> onSwing(() -> force.isShowing() && force.isAlwaysOnTop()));
                pressShortcut(KeyCommandBind.UNIT_DISPLAY);
                await(() -> onSwing(() -> !unit.isVisible()));
                pressShortcut(KeyCommandBind.UNIT_DISPLAY);
                await(() -> onSwing(() -> unit.isShowing() && unit.isAlwaysOnTop()));
                input(() -> GpuBoardTestUi.click("battle-report-toggle"));
                pressShortcut(KeyCommandBind.UNIT_OVERVIEW);
                onSwing(() -> {
                    assertFalse(preferences.getShowUnitOverview());
                    assertTrue(ui.view().sidePanelInset() > 0);
                    GpuBoardWindow.showClassic(gui);
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible() && restoredLocation.get() == 1));
                onSwing(() -> {
                    assertFalse(unit.isAlwaysOnTop());
                    assertFalse(force.isAlwaysOnTop());
                    assertEquals(0, ui.view().sidePanelInset(), "2D retains its hidden overview preference");
                    return null;
                });
            } finally {
                onSwing(() -> {
                    preferences.removePreferenceChangeListener(gui);
                    preferences.removePreferenceChangeListener(gui.getForceDisplayPanel());
                    fixture.game.removeGameListener(gui.getForceDisplayPanel());
                    GpuBoardWindow.closeFor(ui.view());
                    unit.dispose();
                    force.dispose();
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    preferences.setUnitDisplayLocation(location);
                    preferences.setUnitDisplayEnabled(unitEnabled);
                    preferences.setForceDisplayEnabled(forceEnabled);
                    preferences.setShowUnitOverview(overviewEnabled);
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
                    when(gui.getCurrentBoardView()).thenReturn(Optional.empty());
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
                    when(gui.getCurrentBoardView()).thenReturn(Optional.of(ui.view()));
                    return null;
                });
                await(() -> onGl(() -> ((GpuBattleView) Gdx.app.getApplicationListener()).frames() >= 5));
                onGl(() -> {
                    assertEquals(window, ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle());
                    assertTrue(GpuBoardTestUi.stage().getRoot().findActor("board-phase-notice").isVisible());
                    assertNull(GpuBoardTestUi.stage().getRoot().findActor("board-loading-message"));
                    return null;
                });
                onSwing(() -> { phase.set(fixture.panel); return null; });
                await(() -> onGl(() -> !GpuBoardTestUi.stage().getRoot().findActor("board-phase-notice").isVisible()));
                onSwing(() -> {
                    verify(ui.view(), never()).getComponent();
                    assertFalse(ui.frame().isVisible());
                    return null;
                });
                BoardView replacement = onSwing(() -> {
                    BoardView view = new BoardView(fixture.game, null, gui, 0);
                    view.setLocalPlayer(fixture.player);
                    ui.view().dispose();
                    when(gui.getCurrentBoardView()).thenReturn(Optional.of(view));
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
    void botCommandsAndMinimapStayAccessibleWithoutTheClassicDock() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        int location = preferences.getBotCommandsLocation();
        boolean botEnabled = preferences.getBotCommandsEnabled();
        boolean mapEnabled = preferences.getMinimapEnabled();
        int botAuto = preferences.getBotCommandsAutoDisplayNonReportPhase();
        int mapAuto = preferences.getMinimapAutoDisplayNonReportPhase();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            ClientWindow ui = onSwing(() -> createClientWindow(fixture, false));
            ClientGUI gui = ui.view().getClientgui();
            BotCommandsDialog bot = onSwing(() -> {
                Player player = new Player(77, "Bot");
                player.setBot(true);
                fixture.game.addPlayer(player.getId(), player);
                preferences.setBotCommandsLocation(ClientGUI.BOT_COMMANDS_LOCATION_DOCKED);
                preferences.setBotCommandsEnabled(true);
                preferences.setBotCommandAutoDisplayNonReportPhase(GUIPreferences.SHOW);
                preferences.setMinimapAutoDisplayNonReportPhase(GUIPreferences.SHOW);
                BotCommandsDialog dialog = new BotCommandsDialog(ui.frame(), gui);
                BotCommandsPanel panel = new BotCommandsPanel(gui.getClient(), null, null, gui);
                CommandBarPanel bar = new CommandBarPanel(gui);
                JPanel top = new JPanel(new BorderLayout());
                top.add(bar, BorderLayout.NORTH);
                setField(ClientGUI.class, gui, "botCommandsPanel", panel);
                setField(ClientGUI.class, gui, "commandBarPanel", bar);
                setField(ClientGUI.class, gui, "panTop", top);
                when(gui.getBotCommandsDialog()).thenReturn(dialog);
                doCallRealMethod().when(gui).preferenceChange(any());
                doCallRealMethod().when(gui).actionPerformed(any());
                preferences.addPreferenceChangeListener(gui);
                ui.menus().addActionListener(gui);
                return dialog;
            });
            MinimapDialog minimap = onSwing(() -> {
                MinimapDialog dialog = new MinimapDialog(ui.frame());
                dialog.add(new JPanel());
                dialog.setSize(240, 200);
                when(gui.getMiniMapDialog()).thenReturn(dialog);
                setField(AbstractClientGUI.class, gui, "miniMaps", java.util.Map.of(0, dialog));
                return dialog;
            });
            try {
                openNative(ui);
                await(() -> onSwing(() -> bot.isShowing() && bot.isAlwaysOnTop()
                      && minimap.isShowing() && minimap.isAlwaysOnTop()));
                assertEquals(ClientGUI.BOT_COMMANDS_LOCATION_DOCKED, preferences.getBotCommandsLocation());
                input(() -> GpuBoardTestUi.click("battle-report-toggle"));
                pressShortcut(KeyCommandBind.MINIMAP);
                await(() -> onSwing(() -> !minimap.isVisible()));
                pressShortcut(KeyCommandBind.MINIMAP);
                await(() -> onSwing(minimap::isShowing));
                onSwing(() -> {
                    preferences.setBotCommandsEnabled(false);
                    assertFalse(bot.isVisible());
                    preferences.setBotCommandsEnabled(true);
                    assertTrue(bot.isVisible());
                    GpuBoardWindow.showClassic(gui);
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible() && !bot.isVisible()));
                onSwing(() -> {
                    assertEquals(0, bot.getContentPane().getComponentCount(), "The same panel returns to the 2D dock");
                    assertTrue(minimap.isVisible());
                    assertFalse(minimap.isAlwaysOnTop());
                    return null;
                });
            } finally {
                onSwing(() -> {
                    preferences.removePreferenceChangeListener(gui);
                    GpuBoardWindow.closeFor(ui.view());
                    bot.dispose();
                    minimap.dispose();
                    ui.frame().dispose();
                    ui.view().dispose();
                    ui.menus().die();
                    preferences.setBotCommandsLocation(location);
                    preferences.setBotCommandsEnabled(botEnabled);
                    preferences.setBotCommandAutoDisplayNonReportPhase(botAuto);
                    preferences.setMinimapAutoDisplayNonReportPhase(mapAuto);
                    preferences.setMinimapEnabled(mapEnabled);
                    return null;
                });
            }
        }
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
                await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("report-readout:1") != null));
                input(() -> GpuBoardTestUi.click("report-readout:1"));
                await(() -> onSwing(() -> java.util.Arrays.stream(ui.frame().getOwnedWindows())
                      .anyMatch(window -> window instanceof LiveReadoutDialog && window.isVisible() && window.isAlwaysOnTop())));
                onSwing(() -> {
                    assertFalse(report.isVisible(), "Opening a unit's details must not revive the old report");
                    for (var window : ui.frame().getOwnedWindows()) {
                        if (window instanceof LiveReadoutDialog) {
                            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                        }
                    }
                    GpuBoardWindow.showClassic(gui);
                    return null;
                });
                await(() -> onSwing(() -> ui.frame().isVisible() && report.isVisible()));
                assertFalse(onSwing(() -> GpuBoardWindow.isActiveFor(gui)));
            } finally {
                onSwing(() -> {
                    GpuBoardWindow.closeFor(ui.view());
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
            AtomicInteger overlayClicks = new AtomicInteger();
            AtomicInteger unitClicks = new AtomicInteger();
            GUIPreferences preferences = GUIPreferences.getInstance();
            float originalScale = preferences.getGUIScale();
            boolean originalOverview = preferences.getShowUnitOverview();
            try {
                onSwing(() -> {
                    preferences.setShowUnitOverview(true);
                    ui.view().addOverlay(ui.overview());
                    ui.view().addBoardViewListener(new BoardViewListenerAdapter() {
                        @Override
                        public void unitSelected(BoardViewEvent event) {
                            assertEquals(fixture.entity.getId(), event.getEntityId());
                            unitClicks.incrementAndGet();
                        }
                    });
                    ui.view().addOverlay(new IDisplayable() {
                        private Rectangle bounds() {
                            float scale = preferences.getGUIScale();
                            return new Rectangle(Math.round(20 * scale), Math.round(30 * scale),
                                  Math.round(80 * scale), Math.round(40 * scale));
                        }

                        @Override
                        public void draw(Graphics graphics, Rectangle rect) {
                            Rectangle bounds = bounds();
                            graphics.setColor(Color.CYAN);
                            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                        }

                        @Override
                        public boolean isHit(Point point, Dimension size) {
                            if (bounds().contains(point)) {
                                overlayClicks.incrementAndGet();
                                return true;
                            }
                            return false;
                        }
                    });
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
                // The classic window is hidden, so a client dialog must be raised above the native window.
                JDialog probe = onSwing(() -> {
                    JDialog dialog = new JDialog(ui.frame(), "GPU dialog probe", false);
                    dialog.setSize(160, 90);
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
                    int previousClicks = overlayClicks.get();
                    input(() -> {
                        float scale = Gdx.graphics.getWidth() / GpuBoardTestUi.stage().getWidth();
                        int x = Math.round(40 * scale);
                        int y = Math.round((GpuBoardUi.TOP_HEIGHT + 40) * scale);
                        Gdx.input.getInputProcessor().touchDown(x, y, 0, Input.Buttons.LEFT);
                        Gdx.input.getInputProcessor().touchUp(x, y, 0, Input.Buttons.LEFT);
                    });
                    onSwing(() -> {
                        assertEquals(previousClicks + 1, overlayClicks.get(), "Scaled HUD input must match the painted widget");
                        return null;
                    });
                    // Exercise the actual sidebar, independently of any phase selection handler.
                    float zoom = onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                        battle.boardCamera.setIsometric(size[0] != 900);
                        battle.boardCamera.pan(140, -100);
                        assertFalse(unitIsCentered(fixture));
                        return battle.boardCamera.camera.zoom;
                    });
                    Vector3 direction = onGl(() -> new Vector3(((GpuBattleView) Gdx.app.getApplicationListener()).boardCamera.camera.direction));
                    int previousUnitClicks = unitClicks.get();
                    Runnable sidebarClick = () -> {
                        float scale = Gdx.graphics.getWidth() / GpuBoardTestUi.stage().getWidth();
                        float overlayScale = scale / (size[0] == 2560 ? 1.5f : 1f);
                        int x = Math.round(Gdx.graphics.getWidth() - 33 * overlayScale);
                        int y = Math.round(GpuBoardUi.TOP_HEIGHT * scale + 29 * overlayScale);
                        Gdx.input.getInputProcessor().touchDown(x, y, 0, Input.Buttons.LEFT);
                        Gdx.input.getInputProcessor().touchUp(x, y, 0, Input.Buttons.LEFT);
                    };
                    input(sidebarClick);
                    onSwing(() -> {
                        assertEquals(fixture.entity.getId(), ui.view().getCenterRequest().entityId());
                        return null;
                    });
                    awaitNavigation();
                    assertEquals(previousUnitClicks + 1, unitClicks.get(), "Sidebar centering must retain the existing selection event");
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
                    input(sidebarClick);
                    onSwing(() -> {
                        assertEquals(previousUnitClicks + 2, unitClicks.get());
                        return null;
                    });
                    awaitNavigation();
                    onGl(() -> {
                        GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                        assertTrue(settled.epsilonEquals(battle.boardCamera.focus, .001f),
                              "A second sidebar click must retain the same framing, not snap to the unit's hex");
                        return null;
                    });
                }
                input(() -> GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.FileMenu")));
                captureMenu("file-menu.png");
                input(() -> Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE));
                input(() -> GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.ViewMenu")));
                captureMenu("menu-bar.png");
                onGl(() -> {
                    GpuBattleView battle = (GpuBattleView) Gdx.app.getApplicationListener();
                    float before = battle.boardCamera.camera.zoom;
                    GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.viewZoomIn"));
                    assertNotEquals(before, battle.boardCamera.camera.zoom, "The menu must zoom the active GPU camera");
                    Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
                    return null;
                });
                input(() -> GpuBoardTestUi.clickText(Messages.getString("CommonMenuBar.ViewMenu")));
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
                    GpuBoardWindow.closeFor(ui.view());
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
                    preferences.setShowUnitOverview(originalOverview);
                    preferences.removePreferenceChangeListener(ui.overview());
                    GpuBoardWindow.closeFor(ui.view());
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

    private ClientWindow createClientWindow(GpuBoardFixture fixture) {
        return createClientWindow(fixture, true);
    }

    private ClientWindow createClientWindow(GpuBoardFixture fixture, boolean classic) {
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
        if (classic) {
            frame.add(fixture.view.getComponent());
        }
        BoardView view = spy(fixture.view);
        // BoardViewPanel retains its original owner; let that owner create its viewport on demand too.
        doAnswer(invocation -> fixture.view.getComponent()).when(view).getComponent();
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
                fixture.view.releaseClassicView();
                view.releaseClassicView();
            }
            frame.validate();
            return null;
        }).when(gui).setClassicBoardViewEnabled(anyBoolean());
        when(gui.getMenuBar()).thenReturn(menus);
        when(gui.getCurrentBoardView()).thenReturn(Optional.of(view));
        when(gui.boardViews()).thenReturn(List.of(view));
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
                GpuBoardWindow.open(view, () -> fixture.panel);
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

    private static <T> T onGl(Callable<T> action) throws Exception {
        Application app = Gdx.app;
        FutureTask<T> task = new FutureTask<>(action);
        app.postRunnable(task);
        // A cold native startup uploads the board and models before servicing queued input.
        return task.get(30, TimeUnit.SECONDS);
    }

    private static void captureMenu(String name) throws Exception {
        await(() -> onGl(() -> GpuBoardTestUi.stage().getRoot().findActor("tactical-menu").getColor().a == 1));
        onGl(() -> {
            assertFalse(GpuBoardTestUi.stage().getRoot().findActor("command-search").getParent().isVisible(),
                  "Menu-bar dropdowns do not include command search");
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
