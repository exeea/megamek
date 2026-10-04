/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.ask;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.dismiss;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.onSwing;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.present;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.routingClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.client.ui.clientGUI.boardview.RulerDialog;
import megamek.client.ui.clientGUI.boardview.sprite.FieldOfFireSprite;
import megamek.client.ui.clientGUI.boardview.sprite.FiringSolutionSprite;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.clientGUI.boardview.sprite.StepSprite;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogKind;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow.DialogRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuDialogRoutingTest.Asked;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.common.Configuration;
import megamek.common.RangeType;
import megamek.common.ToHitData;
import megamek.common.board.Coords;
import megamek.common.equipment.Cargo;
import megamek.common.util.FiringSolution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The Swing cut's EDT side (I3): the board state's tactical capture leaves out what the HUD draws itself (G4), the
 * minefield display's undeployed question and the map menu go through the native window (D12, D13), and line of sight
 * stays MegaMek's ruler, which shows over the native window (G3, the user's decision of 2026-10-03).
 */
@Timeout(120)
class GpuSwingCutTest {
    private static File originalDataDir;

    /** Earlier test classes can leave the data folder on testresources; the fixtures need the staged data. */
    @BeforeAll
    static void useStagedData() {
        originalDataDir = Configuration.dataDir();
        Configuration.setDataDir(null);
    }

    @AfterAll
    static void restoreDataDir() {
        Configuration.setDataDir(originalDataDir);
    }

    /**
     * G4: MegaMek's firing solution draws into the board state's tactical capture, but the native frame leaves it out
     * (the board labels' to-hit badges stand for it), and the sprite stays shown for the classic board.
     */
    @Test
    void theNativeFrameLeavesOutMegaMeksFiringSolutions() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            FiringSolutionSprite solution = onSwing(() -> {
                ToHitData toHit = new ToHitData(4, "test");
                toHit.setLocation(new Coords(8, 5));
                toHit.setRange(3);
                FiringSolutionSprite sprite = new FiringSolutionSprite(fixture.view, new FiringSolution(toHit, true));
                fixture.view.addSprites(List.of(sprite));
                return sprite;
            });
            List<BoardTactical> captures = onSwing(() -> {
                BoardTactical all = fixture.view.captureTacticalGeometry();
                fixture.view.removeSprites(List.of(solution));
                BoardTactical without = fixture.view.captureTacticalGeometry();
                fixture.view.addSprites(List.of(solution));
                fixture.source.refresh();
                return List.of(all, without, fixture.source.takeFrame().scene().tactical());
            });
            assertNotEquals(captures.get(1), captures.get(0), "MegaMek's own capture draws the solution");
            assertEquals(captures.get(1), captures.get(2), "The native frame draws everything else, and no solution");
            assertFalse(onSwing(solution::isHidden), "The capture leaves the sprite shown for the classic board");
        }
    }

    /**
     * G4: while the plan draws the route, the native frame shows MegaMek's movement envelope and the zones, 
     * whose sprites extend the envelope's: everything the board state captures but the route's steps, 
     * of which this plan has none yet. The weapon's bracket, a field of fire too,
     * is the fire control's wall.
     */
    @Test
    void theNativeFrameShowsMegaMeksEnvelopeAndFieldOfFireWhileThePlanDrawsTheRoute() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            assertFalse(onSwing(moving::envelopeSprites).isEmpty(),
                  "MegaMek's board state shows the selected unit's envelope");
            moving.board.panel = moving.display;
            onSwing(() -> {
                moving.board.view.addSprites(List.of(
                      new FieldOfFireSprite(moving.board.view, RangeType.RANGE_SHORT, new Coords(4, 4), 63),
                      new FieldOfFireSprite(moving.board.view, new Color(0x12, 0x34, 0x56), new Coords(6, 4), 63)));
                return null;
            });
            Planned planning = planned(moving);
            assertTrue(planning.planner(), "The Sagittaire walks in a planner gear");
            assertEquals(planning.state(), planning.frame(), "The frame shows every marking of the board state");
            List<BoardScene.RangeBorder> brackets = onSwing(() -> {
                moving.board.source.refresh();
                return moving.board.source.takeFrame().scene().rangeBorders();
            });
            assertTrue(brackets.stream().anyMatch(border -> border.coords().equals(new Coords(4, 4))),
                  "The weapon's bracket reaches the fire control's walls");
        }
    }

    /**
     * G4: while the plan draws a unit's route, the native frame leaves out MegaMek's step arrows and costs too, which
     * the board state captures over the route (the user's report of 2026-10-03: the old route marks mixed with the
     * new); without a plan it shows them, and the capture leaves them shown for the classic board.
     */
    @Test
    void theNativeFrameLeavesOutMegaMeksStepArrowsWhileThePlanDrawsTheRoute() throws Exception {
        try (GpuMovementFixture moving = GpuMovementFixture.create()) {
            moving.board.panel = moving.display;
            List<StepSprite> steps = onSwing(() -> {
                moving.display.plotTo(new Coords(11, 9), 0);
                return List.copyOf(moving.board.view.getPathSprites());
            });
            assertFalse(steps.isEmpty(), "MegaMek's board state draws the plotted path's steps");
            List<BoardTactical> captures = onSwing(() -> {
                moving.board.source.refresh();
                BoardTactical frame = moving.board.source.takeFrame().scene().tactical();
                BoardTactical withSteps = moving.board.view.captureTacticalGeometry();
                steps.forEach(step -> step.setHidden(true));
                BoardTactical withoutSteps = moving.board.view.captureTacticalGeometry();
                steps.forEach(step -> step.setHidden(false));
                return List.of(frame, withSteps, withoutSteps);
            });
            assertNotEquals(captures.get(2), captures.get(1), "MegaMek's own capture draws the steps");
            assertEquals(captures.get(2), captures.get(0), "The native frame draws everything else, and no step");
            assertTrue(onSwing(() -> steps.stream().noneMatch(Sprite::isHidden)),
                  "The capture leaves the steps shown for the classic board");

            moving.board.panel = new JPanel();
            Planned classic = planned(moving);
            assertFalse(classic.planner());
            assertEquals(classic.state(), classic.frame(), "Without a plan the frame shows MegaMek's steps");
        }
    }

    /** A capture: the plan's planner flag, the frame's tactical geometry and the board state's own. */
    private record Planned(boolean planner, BoardTactical frame, BoardTactical state) { }

    /** EDT, in one event: captures the frame, then the board state's own tactical capture of the same sprites. */
    private static Planned planned(GpuMovementFixture moving) throws Exception {
        return onSwing(() -> {
            moving.board.source.refresh();
            GpuBoardSource.Frame frame = moving.board.source.takeFrame();
            return new Planned(frame.panels().move().planner(), frame.scene().tactical(),
                  moving.board.view.captureTacticalGeometry());
        });
    }

    /**
     * D12: the minefield display's question about undeployed objects is the client's confirm, so the presented
     * window asks it natively; No keeps the turn, Yes ends it.
     */
    @Test
    void theUndeployedObjectsQuestionIsAskedInTheNativeWindow() throws Exception {
        ClientGUI gui = routingClient();
        Client client = mock(Client.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(gui.getClient()).thenReturn(client);
            fixture.player.getGroundObjectsToPlace()
                  .add(GpuListPromptBridgeTest.groundObject(new Cargo(), "Supply crate", 2));
            DeployMinefieldDisplay display = GpuListPromptBridgeTest.placingCargo(gui);
            present(gui, fixture.view, fixture.source);
            try {
                Asked<Object> kept = ask(fixture.source, () -> {
                    display.ready();
                    return null;
                }, JOptionPane.NO_OPTION, false);
                DialogRequest request = kept.request();
                assertEquals(DialogKind.MESSAGE, request.kind());
                assertEquals(Messages.getString("DeployMinefieldDisplay.undeployedTitle"), request.title());
                assertEquals(Messages.getString("DeployMinefieldDisplay.undeployedCarryables", 1), request.message());
                verify(client, never()).sendDeployGroundObjects(any());

                ask(fixture.source, () -> {
                    display.ready();
                    return null;
                }, JOptionPane.YES_OPTION, false);
                verify(client).sendDeployGroundObjects(any());
            } finally {
                dismiss();
                onSwing(() -> {
                    display.removeAllListeners();
                    return null;
                });
            }
        }
    }

    /**
     * D13: over the battle window MegaMek's map menu, which the HUD's "More actions" captures, has no View group (its
     * Unit Display and readout are the HUD's unit card and record sheet); the classic board keeps it.
     */
    @Test
    void theCapturedMapMenuHasNoViewGroupWhileTheBattleWindowIsActive() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        Client client = mock(Client.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(gui.getClient()).thenReturn(client);
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            GpuBoardActions actions = new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { });
            Coords hex = fixture.entity.getPosition();
            assertTrue(labels(onSwing(() -> actions.contextCommands(hex))).contains("View"),
                  "The classic board's map menu views the unit on the hex");
            present(gui, view, fixture.source);
            try {
                List<String> labels = labels(onSwing(() -> actions.contextCommands(hex)));
                assertFalse(labels.contains("View"), "No View group over the battle window: " + labels);
                assertFalse(labels.isEmpty(), "The map menu's other groups stay");
            } finally {
                dismiss();
            }
        }
    }

    private static List<String> labels(List<BoardScene.Command> commands) {
        return commands.stream().map(BoardScene.Command::label).toList();
    }

    /**
     * G3, the user's decision of 2026-10-03: line of sight stays MegaMek's Swing ruler. Over the battle window a Ctrl
     * measurement shows it, raised above the window by its dialog listener, and its Close ends the measurement. The
     * menu's line of sight from a unit measures with it too, ending a Ctrl measurement that waits for its second
     * point.
     */
    @Test
    void theRulerShowsOverTheBattleWindowForAMeasurementAndTheMenusLineOfSight() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Shows the Swing ruler");
        ClientGUI gui = mock(ClientGUI.class);
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            JFrame frame = onSwing(JFrame::new);
            // The ruler belongs to the client's classic window, whose dialogs the battle window's listener judges.
            when(gui.getFrame()).thenReturn(frame);
            RulerDialog ruler = onSwing(() -> new RulerDialog(frame, view, fixture.game));
            var field = ClientGUI.class.getDeclaredField("rulers");
            field.setAccessible(true);
            field.set(gui, new HashMap<>(Map.of(view.getBoardId(), ruler)));
            doCallRealMethod().when(gui).measureLineOfSight(anyInt(), any(), any());
            doCallRealMethod().when(gui).setRulerHeight(anyInt(), any(), anyInt());
            // The source measures on the board the client shows.
            doReturn(Optional.of(view)).when(gui).getCurrentBoardState();
            GpuBoardSource source = onSwing(() -> new GpuBoardSource(view, () -> fixture.panel));
            Coords from = fixture.entity.getPosition();
            Coords to = new Coords(from.getX(), from.getY() - 3);
            GpuBoardWindow window = present(gui, view, source);
            Field listenerField = GpuBoardWindow.class.getDeclaredField("dialogListener");
            listenerField.setAccessible(true);
            AWTEventListener listener = (AWTEventListener) listenerField.get(window);
            Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.COMPONENT_EVENT_MASK);
            try {
                measure(view, source, from, to);
                assertTrue(onSwing(ruler::isVisible), "A Ctrl measurement shows the ruler over the battle window");
                assertTrue(onSwing(ruler::isAlwaysOnTop), "raised above it by the window's dialog listener");
                assertEquals(List.of(from, to), onSwing(() -> List.of(view.getRulerStart(), view.getRulerEnd())));
                onSwing(() -> {
                    GpuDialogRoutingTest.press(ruler, Messages.getString("Ruler.Close"));
                    return null;
                });
                assertFalse(onSwing(ruler::isVisible), "Its Close hides it");
                assertNull(onSwing(view::getRulerStart), "and ends the measurement");

                Coords other = new Coords(from.getX() + 1, from.getY() - 2);
                onSwing(() -> {
                    view.checkLOS(to);
                    return null;
                });
                source.los().lineOfSight(fixture.entity.getId(), other, Float.NaN);
                onSwing(() -> null);
                assertEquals(List.of(true, from, other), onSwing(() -> List.of(ruler.isVisible(),
                      view.getRulerStart(), view.getRulerEnd())), "The menu's line of sight measures with the ruler");
                assertNull(onSwing(view::getFirstLOS), "and ends the Ctrl measurement waiting for its second point");

                // Ctrl clicks at the heights the pointer shows (the user's decision of 2026-10-03): a building's floor
                // two levels above its hex for the first point, the ground for the second.
                Coords up = new Coords(from.getX() + 1, from.getY() - 1);
                Coords down = new Coords(from.getX(), from.getY() - 2);
                float level = BoardGeometry.level();
                int upLevel = onSwing(() -> view.getBoard().getHex(up).getLevel());
                int downLevel = onSwing(() -> view.getBoard().getHex(down).getLevel());
                source.measure(up, InputEvent.CTRL_DOWN_MASK, (upLevel + 2) * level + .1f);
                source.measure(down, InputEvent.CTRL_DOWN_MASK, downLevel * level + .1f);
                onSwing(() -> null);
                assertEquals(List.of(up, down, 2, 0), onSwing(() -> {
                    List<JSpinner> heights = GpuDialogRoutingTest.components(ruler, JSpinner.class);
                    return List.of(view.getRulerStart(), view.getRulerEnd(), heights.get(0).getValue(),
                          heights.get(1).getValue());
                }), "The ruler measures from and to the pointed heights");
            } finally {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                dismiss();
                onSwing(() -> {
                    ruler.dispose();
                    source.close();
                    frame.dispose();
                    return null;
                });
            }
        }
    }

    /** EDT: a Ctrl measurement between two hexes, as MegaMek's board reports both ends, then a capture. */
    private static void measure(BoardClientState view, GpuBoardSource source, Coords from, Coords to)
          throws Exception {
        onSwing(() -> {
            view.checkLOS(from);
            view.checkLOS(to);
            source.refresh();
            return null;
        });
    }
}
