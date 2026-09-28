/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedList;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.sprite.EntitySprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListener;
import megamek.common.game.Game;
import org.junit.jupiter.api.Test;

class ClientGUIBoardLifecycleTest {
    @Test
    void nativeMapArrivalReplacementAndBoardSelectionNeedNoClassicRenderer() throws Exception {
        onClient(() -> {
            try (var views = mockConstruction(BoardView.class);
                  var panels = mockConstruction(BoardViewPanel.class);
                  var units = mockConstruction(EntitySprite.class);
                  var isometric = mockConstruction(IsometricSprite.class);
                  var display = mockConstruction(UnitDisplayPanel.class);
                  var sound = mockConstruction(SoundManager.class);
                  var session = Session.create()) {
                Game game = session.game();
                ClientGUI gui = session.gui();
                game.receiveBoard(0, Board.createEmptyBoard(8, 8));
                BoardClientState first = gui.getBoardState();
                assertNotNull(first);
                assertTrue(gui.boardViews().isEmpty());
                int listeners = game.getGameListeners().size();
                for (int i = 0; i < 3; i++) {
                    Board oldBoard = game.getBoard();
                    BoardClientState oldState = gui.getBoardState();
                    game.receiveBoard(0, Board.createEmptyBoard(8, 8));
                    assertTrue(oldState.isClosed());
                    assertNotSame(oldState, gui.getBoardState());
                    assertEquals(listeners, game.getGameListeners().size(), "Replacing a map must detach old listeners");
                    long revision = oldState.getRevision();
                    oldBoard.setHex(new Coords(1, 1), oldBoard.getHex(1, 1).duplicate());
                    assertEquals(revision, oldState.getRevision());
                }
                game.receiveBoard(2, Board.createEmptyBoard(6, 6));
                gui.showBoardView(2);
                assertSame(gui.getBoardState(2), gui.getBoardState());
                assertTrue(views.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertTrue(units.constructed().isEmpty());
                assertTrue(isometric.constructed().isEmpty());
                gui.die();
                assertTrue(gui.boardStates().isEmpty());
                assertTrue(game.getGameListeners().isEmpty(), "Client shutdown must release all board and HUD listeners");
            }
            return null;
        });
    }

    @Test
    void switchingRenderersKeepsTheSameStateAndSelection() throws Exception {
        onClient(() -> {
            try (var display = mockConstruction(UnitDisplayPanel.class);
                  var sound = mockConstruction(SoundManager.class);
                  var session = Session.create()) {
                session.game().receiveBoard(0, Board.createEmptyBoard(8, 8));
                ClientGUI gui = session.gui();
                BoardClientState state = gui.getBoardState();
                Coords selected = new Coords(3, 4);
                state.select(selected);
                for (int i = 0; i < 2; i++) {
                    gui.setClassicBoardViewEnabled(true);
                    assertSame(state, gui.getBoardView().getClientState());
                    assertEquals(selected, state.getSelected());
                    gui.setClassicBoardViewEnabled(false);
                    assertSame(state, gui.getBoardState());
                    assertFalse(state.isClosed());
                    assertTrue(gui.boardViews().isEmpty());
                    assertEquals(selected, state.getSelected());
                }
            }
            return null;
        });
    }

    private record Session(Game game, ClientGUI gui, boolean use3D) implements AutoCloseable {
        static Session create() throws Exception {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean previous = preferences.getUse3DBoard();
            preferences.setUse3DBoard(true);
            Game game = new Game();
            game.setPhase(GamePhase.LOUNGE);
            Player player = new Player(0, "Native lifecycle");
            game.addPlayer(0, player);
            Client client = mock(Client.class);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(player);
            ClientGUI gui = new ClientGUI(client, null);
            ChatterBox history = mock(ChatterBox.class);
            history.history = new LinkedList<>();
            var chat = ClientGUI.class.getDeclaredField("cb");
            chat.setAccessible(true);
            chat.set(gui, history);
            var listener = ClientGUI.class.getDeclaredField("gameListener");
            listener.setAccessible(true);
            game.addGameListener((GameListener) listener.get(gui));
            return new Session(game, gui, previous);
        }

        @Override
        public void close() {
            if (gui.getFrame().isDisplayable() || !gui.boardStates().isEmpty()) {
                gui.die();
            }
            GUIPreferences.getInstance().setUse3DBoard(use3D);
        }
    }

    private static <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
