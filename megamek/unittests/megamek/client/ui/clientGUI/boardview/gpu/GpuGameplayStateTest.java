/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.IDisplayable;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardFieldOfView;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.sprite.EntitySprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.enums.MoveStepType;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.Game;
import megamek.common.loaders.MekFileParser;
import megamek.common.moves.MovePath;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import megamek.common.units.UnitLocation;
import org.junit.jupiter.api.Test;

class GpuGameplayStateTest {
    record Session(Game game, Entity unit, BoardClientState state, GpuBoardSource source) implements AutoCloseable {
        static Session create() throws Exception {
            Game game = new Game();
            game.setBoard(Board.createEmptyBoard(8, 8));
            game.setPhase(GamePhase.MOVEMENT);
            Player player = new Player(0, "Native gameplay");
            game.addPlayer(0, player);
            Entity unit = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
            unit.setId(1); unit.setOwner(player); unit.setPosition(new Coords(3, 3)); unit.setDeployed(true);
            game.addEntity(unit, false);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(player);
            ClientGUI gui = mock(ClientGUI.class);
            when(gui.getClient()).thenReturn(client);
            when(gui.getDisplayedUnit()).thenReturn(unit);
            JPanel panel = new JPanel();
            when(gui.getMainPanel()).thenReturn(panel);
            BoardClientState state = new BoardClientState(game, null, gui, 0, null);
            state.setLocalPlayer(player);
            when(gui.getCurrentBoardState()).thenReturn(Optional.of(state));
            when(gui.getBoardState()).thenReturn(state);
            when(gui.boardStates()).thenReturn(List.of(state));
            state.redrawAllEntities();
            return new Session(game, unit, state, new GpuBoardSource(state, () -> panel));
        }
        @Override public void close() { source.close(); state.close(); }
    }

    @Test
    void gameplayCaptureSelectionMovementAndTooltipsConstructNoClassicRenderer() throws Exception {
        onClient(() -> {
            try (var views = mockConstruction(BoardView.class);
                  var panels = mockConstruction(BoardViewPanel.class);
                  var units = mockConstruction(EntitySprite.class);
                  var isometric = mockConstruction(IsometricSprite.class);
                  var session = Session.create()) {
                var state = session.state();
                assertEquals(1, session.source().takeFrame().scene().units().size());
                state.select(session.unit().getPosition());
                var path = new MovePath(session.game(), session.unit());
                path.addStep(MoveStepType.FORWARDS);
                state.drawMovementData(session.unit(), path);
                assertSame(path.getLastStep(), state.getLastMovementStep());
                state.highlightSelectedEntity(session.unit());
                session.source().refresh();
                assertEquals(session.unit().getId(), session.source().takeFrame().scene().units().getFirst().id());
                assertNotNull(state.getHexTooltip(new Coords(3, 3)));
                session.game().processGameEvent(new GameEntityChangeEvent(session.game(), session.unit()));
                session.source().refresh();
                assertNotNull(session.source().takeFrame().scene());
                assertTrue(views.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertTrue(units.constructed().isEmpty());
                assertTrue(isometric.constructed().isEmpty());
            }
            return null;
        });
    }

    @Test
    void closingClassicRendererRetainsSharedSelectionListenersAndTacticalState() throws Exception {
        onClient(() -> {
            try (var session = Session.create()) {
                var state = session.state();
                var selected = new Coords(4, 4);
                state.select(selected);
                var listener = mock(BoardViewListenerAdapter.class);
                state.addBoardViewListener(listener);
                long revision = state.getRevision();
                BoardView classic = new BoardView(state, null, state.getClientgui());
                classic.getPanel().repaint();
                assertEquals(revision, state.getRevision(), "Swing repaint must not invalidate native tactical state");
                classic.dispose();
                assertFalse(state.isClosed());
                assertEquals(selected, state.getSelected());
                state.select(new Coords(5, 4));
                verify(listener).hexSelected(any(BoardViewEvent.class));
                session.source().refresh();
                assertEquals(1, session.source().takeFrame().scene().units().size());
            }
            return null;
        });
    }

    @Test
    void closingNativeSessionReleasesAllGameListenersAndStopsFurtherCapture() throws Exception {
        onClient(() -> {
            Session session = Session.create();
            assertFalse(session.game().getGameListeners().isEmpty());
            session.close();
            assertTrue(session.game().getGameListeners().isEmpty());
            assertTrue(session.source().isClosed());
            assertTrue(session.state().isClosed());
            session.close();
            return null;
        });
    }

    @Test
    void nativeVisibilityRefreshesWhenTerrainChangesWithoutAClassicRenderer() throws Exception {
        try (var options = new GpuFieldOfViewTest.Options()) {
            onClient(() -> {
                try (var views = mockConstruction(BoardView.class); var session = Session.create()) {
                    var board = session.game().getBoard();
                    var target = new Coords(6, 3);
                    session.state().select(session.unit().getPosition());
                    for (int y = 0; y < board.getHeight(); y++) { board.setHex(4, y, new Hex(5)); }
                    session.source().refresh();
                    var blocked = session.source().takeFrame().scene().fieldOfView();
                    assertEquals(BoardFieldOfView.Visibility.BLOCKED, GpuFieldOfViewTest.at(blocked, target).visibility());
                    for (int y = 0; y < board.getHeight(); y++) { board.setHex(4, y, new Hex(0)); }
                    session.source().refresh();
                    assertEquals(BoardFieldOfView.Visibility.VISIBLE,
                          GpuFieldOfViewTest.at(session.source().takeFrame().scene().fieldOfView(), target).visibility(),
                          "Removing terrain must invalidate LOS without a classic renderer listening to edits");
                    assertEquals(BoardFieldOfView.Visibility.BLOCKED, GpuFieldOfViewTest.at(blocked, target).visibility());
                    var replacement = Board.createEmptyBoard(8, 8);
                    for (int y = 0; y < replacement.getHeight(); y++) { replacement.setHex(4, y, new Hex(5)); }
                    session.game().setBoard(replacement);
                    session.source().refresh();
                    assertEquals(BoardFieldOfView.Visibility.BLOCKED,
                          GpuFieldOfViewTest.at(session.source().takeFrame().scene().fieldOfView(), target).visibility());
                    assertTrue(views.constructed().isEmpty());
                }
                return null;
            });
        }
    }

    @Test
    void nativeLocalEditsKeepTacticalPaintingLocal() throws Exception {
        try (var options = new GpuFieldOfViewTest.Options()) {
            onClient(() -> {
                try (var views = mockConstruction(BoardView.class); var session = Session.create()) {
                    var painted = new HashSet<Coords>();
                    session.state().addHexDrawPlugin((graphics, hex, game, coords, context) -> painted.add(coords));
                    session.state().select(session.unit().getPosition());
                    session.source().refresh();
                    painted.clear();
                    var edited = new Coords(4, 4);
                    session.game().getBoard().setHex(edited, new Hex(3));
                    session.source().refresh();
                    assertTrue(painted.contains(edited));
                    assertTrue(painted.size() <= 9, "A local edit repainted " + painted.size() + " tactical hexes");
                    assertTrue(views.constructed().isEmpty());
                }
                return null;
            });
        }
    }

    @Test
    void replacingTheMapRetiresCapturedCommandsEvenWhenThePresentationStateIsReused() throws Exception {
        Session session = onClient(Session::create);
        AtomicInteger clicks = new AtomicInteger();
        try {
            onClient(() -> {
                captureTimer(session.source()).stop();
                var button = new JButton("Hold position");
                button.addActionListener(event -> clicks.incrementAndGet());
                session.state().getClientgui().getMainPanel().add(button);
                session.source().refresh();
                var previous = session.source().takeFrame();
                session.game().setBoard(Board.createEmptyBoard(6, 6));
                session.source().refresh();
                previous.scene().commands().getFirst().action().run();
                return null;
            });
            onClient(() -> {
                assertEquals(0, clicks.get(), "Old commands remain stale after publishing the replacement map");
                session.source().takeFrame().scene().commands().getFirst().action().run();
                return null;
            });
            onClient(() -> { assertEquals(1, clicks.get()); return null; });
        } finally {
            onClient(() -> { session.close(); return null; });
        }
    }

    @Test
    void boardSelectionImmediatelyRejectsPreviousMapCommandsAndPicksBeforeCapture() throws Exception {
        Session session = onClient(Session::create);
        AtomicInteger clicks = new AtomicInteger();
        BoardClientState replacement = onClient(() -> {
            captureTimer(session.source()).stop();
            var button = new JButton("Hold position");
            button.addActionListener(event -> clicks.incrementAndGet());
            session.state().getClientgui().getMainPanel().add(button);
            session.source().refresh();
            var frame = session.source().takeFrame();
            session.game().setBoard(1, Board.createEmptyBoard(8, 8));
            var next = new BoardClientState(session.game(), null, session.state().getClientgui(), 1,
                  session.state().getTilesetManager());
            next.setLocalPlayer(session.unit().getOwner());
            // Both tasks are already queued when the user changes the selected board. No capture has run yet.
            frame.scene().commands().getFirst().action().run();
            session.source().primaryClick(new Coords(2, 2), Entity.NONE, InputEvent.CTRL_DOWN_MASK, frame.boardGeneration());
            when(session.state().getClientgui().getCurrentBoardState()).thenReturn(Optional.of(next));
            return next;
        });
        try {
            onClient(() -> {
                assertEquals(0, clicks.get(), "A command from an unselected map must not invoke a phase action");
                assertNull(session.state().getFirstLOS(), "A stale pick must not start measurement on the old board");
                assertNull(replacement.getFirstLOS(), "A stale pick must not be redirected to the newly selected board");
                session.source().refresh();
                var current = session.source().takeFrame();
                current.scene().commands().getFirst().action().run();
                session.source().primaryClick(new Coords(2, 2), Entity.NONE, InputEvent.CTRL_DOWN_MASK, current.boardGeneration());
                return null;
            });
            onClient(() -> {
                assertEquals(1, clicks.get(), "Fresh commands on the selected board remain usable");
                assertEquals(new Coords(2, 2), replacement.getFirstLOS());
                return null;
            });
        } finally {
            onClient(() -> { replacement.close(); session.close(); return null; });
        }
    }

    @Test
    void removingTheLastMapDetachesTheSourceAndClearsItsPlayback() throws Exception {
        onClient(() -> {
            try (var session = Session.create()) {
                Board board = session.state().getBoard();
                long generation = session.source().takeFrame().boardGeneration();
                session.state().setMovingUnits(true);
                session.state().close();
                when(session.state().getClientgui().getCurrentBoardState()).thenReturn(Optional.empty());
                session.game().receiveBoard(0, null);
                session.source().refresh();
                var empty = session.source().takeFrame();
                assertNull(empty.scene());
                assertTrue(empty.boardGeneration() > generation, "Removing a map must retire its input and playback");
                assertFalse(session.state().isMovingUnits());
                var listeners = Board.class.getDeclaredField("boardListeners");
                listeners.setAccessible(true);
                assertEquals(List.of(), listeners.get(board), "The source must release the removed board immediately");
            }
            return null;
        });
    }

    @Test
    void hudTimerFollowsReplacementStateEvenWhenItUsesTheSameBoard() throws Exception {
        onClient(() -> {
            try (var session = Session.create();
                  var replacement = new BoardClientState(session.game(), null, session.state().getClientgui(), 0,
                        session.state().getTilesetManager())) {
                var oldOverlay = mock(IDisplayable.class);
                var newOverlay = mock(IDisplayable.class);
                session.state().addOverlay(oldOverlay);
                replacement.addOverlay(newOverlay);
                long generation = session.source().takeFrame().boardGeneration();
                when(session.state().getClientgui().getCurrentBoardState()).thenReturn(Optional.of(replacement));
                session.source().refresh();
                Timer timer = captureTimer(session.source());
                for (var tick : timer.getActionListeners()) {
                    tick.actionPerformed(null);
                }
                verify(newOverlay).setIdleTime(100, true);
                verify(oldOverlay, never()).setIdleTime(anyLong(), anyBoolean());
                assertTrue(session.source().takeFrame().boardGeneration() > generation,
                      "State replacement must reject old input even when the Board object is unchanged");
            }
            return null;
        });
    }

    @Test
    void lateIdleFrameCannotFinishNewMovementOrAReplacedBoardsAnimation() throws Exception {
        Session session = onClient(Session::create);
        try {
            var listener = mock(BoardViewListenerAdapter.class);
            BoardSource.Frame moving = onClient(() -> {
                session.state().addBoardViewListener(listener);
                var idle = session.source().takeFrame();
                session.source().playbackState(idle, false);
                Coords from = session.unit().getPosition(), to = from.translated(0);
                session.unit().moved = EntityMovementType.MOVE_WALK;
                session.unit().setPosition(to);
                var path = new Vector<>(List.of(new UnitLocation(1, from, 0, 0, 0), new UnitLocation(1, to, 0, 0, 0)));
                session.game().fireGameEvent(new GameEntityChangeEvent(session.game(), session.unit(), path));
                assertTrue(session.state().isMovingUnits());
                return session.source().takeFrame();
            });
            onClient(() -> {
                assertTrue(session.state().isMovingUnits(), "An older idle callback must not finish a new move");
                verify(listener, never()).finishedMovingUnits(any());
                session.source().playbackState(moving, false);
                return null;
            });
            onClient(() -> {
                assertFalse(session.state().isMovingUnits());
                verify(listener).finishedMovingUnits(any());
                session.game().setBoard(Board.createEmptyBoard(8, 8));
                session.source().refresh();
                assertNotEquals(moving.boardGeneration(), session.source().takeFrame().boardGeneration());
                session.source().playbackState(moving, true);
                return null;
            });
            onClient(() -> {
                assertFalse(session.state().isMovingUnits(), "Old-board playback must not block the replacement board");
                return null;
            });
        } finally {
            onClient(() -> { session.close(); return null; });
        }
    }

    @Test
    void printableExportUsesSharedArtworkAndLeavesInteractiveStateIntact() throws Exception {
        onClient(() -> {
            try (var views = mockConstruction(BoardView.class); var session = Session.create()) {
                var before = session.source().takeFrame().scene();
                var image = session.state().getEntireBoardImage(false);
                assertEquals((8 - 1) * 63 + 84, image.getWidth());
                assertTrue(image.getHeight() >= 8 * 72);
                assertTrue(views.constructed().isEmpty());
                session.source().refresh();
                assertSame(before.units().getFirst().annotations(), session.source().takeFrame().scene().units().getFirst().annotations());
            }
            return null;
        });
    }

    private static Timer captureTimer(GpuBoardSource source) throws ReflectiveOperationException {
        var field = GpuBoardSource.class.getDeclaredField("timer");
        field.setAccessible(true);
        return (Timer) field.get(source);
    }

    private static <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
