/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.sprite.EntitySprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
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
import org.junit.jupiter.api.Test;

class GpuGameplayStateTest {
    private record Session(Game game, Entity unit, BoardClientState state, GpuBoardSource source) implements AutoCloseable {
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

    private static <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
