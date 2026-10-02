/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.event.WindowEvent;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.CloseClientListener;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.BoardViewPanel;
import megamek.client.ui.clientGUI.boardview.overlay.BoardToastOverlay;
import megamek.client.ui.clientGUI.boardview.sprite.EntitySprite;
import megamek.client.ui.clientGUI.boardview.sprite.isometric.IsometricSprite;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.panels.phaseDisplay.lobby.ChatLounge;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameListener;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekSummaryCache;
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
                assertSame(gui.getTilesetManager(), first.getTilesetManager());
                assertTrue(gui.boardViews().isEmpty());
                int listeners = game.getGameListeners().size();
                for (int i = 0; i < 3; i++) {
                    Board oldBoard = game.getBoard();
                    BoardClientState oldState = gui.getBoardState();
                    game.receiveBoard(0, Board.createEmptyBoard(8, 8));
                    assertTrue(oldState.isClosed());
                    assertNotSame(oldState, gui.getBoardState());
                    assertEquals(listeners, game.getGameListeners().size(), "Replacing a map must detach old listeners");
                    assertEquals(1, session.closeListeners().size(), "The old minimap must release its client callback");
                    assertEquals(List.of(), boardListeners(oldBoard), "Replaced maps must release every subscription");
                    long revision = oldState.getRevision();
                    oldBoard.setHex(new Coords(1, 1), oldBoard.getHex(1, 1).duplicate());
                    assertEquals(revision, oldState.getRevision());
                }
                game.receiveBoard(2, Board.createEmptyBoard(6, 6));
                gui.showBoardView(2);
                assertSame(first.getTilesetManager(), gui.getBoardState(2).getTilesetManager());
                assertSame(gui.getBoardState(2).getOverlay(BoardToastOverlay.class),
                      gui.getToastOverlay());
                assertSame(gui.getBoardState(2), gui.getBoardState());
                BoardClientState removed = gui.getBoardState(2);
                Board removedBoard = removed.getBoard();
                game.receiveBoard(2, null);
                assertTrue(removed.isClosed());
                assertNull(gui.getBoardState(2));
                assertSame(gui.getBoardState(0), gui.getBoardState());
                assertEquals(List.of(), boardListeners(removedBoard));
                assertTrue(views.constructed().isEmpty());
                assertTrue(panels.constructed().isEmpty());
                assertTrue(units.constructed().isEmpty());
                assertTrue(isometric.constructed().isEmpty());
                gui.die();
                assertTrue(gui.boardStates().isEmpty());
                assertTrue(session.closeListeners().isEmpty());
                assertTrue(game.getGameListeners().isEmpty(), "Client shutdown must release all board and HUD listeners");
            }
            return null;
        });
    }

    @Test
    void lobbyCreatesClassicPreviewOnlyWhenOpenedAndReleasesItsListeners() throws Exception {
        onClient(() -> {
            try (var display = mockConstruction(UnitDisplayPanel.class);
                  var sound = mockConstruction(SoundManager.class);
                  var cache = mockStatic(MekSummaryCache.class);
                  var application = mockStatic(MegaMekGUI.class);
                  var session = Session.create()) {
                cache.when(MekSummaryCache::getInstance).thenReturn(mock(MekSummaryCache.class));
                application.when(MegaMekGUI::getKeyDispatcher).thenReturn(new MegaMekController());
                ClientGUI gui = session.gui();
                MapSettings settings = MapSettings.getInstance();
                settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
                when(gui.getClient().getMapSettings()).thenReturn(settings);
                ChatLounge lounge;
                try (var views = mockConstruction(BoardView.class);
                      var panels = mockConstruction(BoardViewPanel.class)) {
                    lounge = new ChatLounge(gui);
                    gui.chatlounge = lounge;
                    assertTrue(views.constructed().isEmpty(), "An unopened lobby preview must not create a renderer");
                    assertTrue(panels.constructed().isEmpty());
                }
                try {
                    session.game().receiveBoard(0, Board.createEmptyBoard(8, 8));
                    lounge.previewGameBoard();
                    var previewField = ChatLounge.class.getDeclaredField("previewBV");
                    previewField.setAccessible(true);
                    BoardView preview = (BoardView) previewField.get(lounge);
                    assertNotNull(preview);
                    BoardClientState state = preview.getClientState();
                    Board previous = state.getBoard();
                    session.game().receiveBoard(0, Board.createEmptyBoard(6, 6));
                    lounge.previewGameBoard();
                    assertSame(state, ((BoardView) previewField.get(lounge)).getClientState());
                    assertEquals(List.of(), boardListeners(previous), "Both gameplay and preview must detach the old map");
                    var windowField = ChatLounge.class.getDeclaredField("boardPreviewW");
                    windowField.setAccessible(true);
                    var window = (JDialog) windowField.get(lounge);
                    window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                    lounge.killPreviewBV();
                    assertTrue(state.isClosed());
                    assertTrue(state.getGame().getGameListeners().isEmpty());
                    assertNull(previewField.get(lounge));
                    lounge.previewGameBoard();
                    BoardClientState reopened = ((BoardView) previewField.get(lounge)).getClientState();
                    window = (JDialog) windowField.get(lounge);
                    window.getRootPane().getActionMap().get("closeAction").actionPerformed(null);
                    assertTrue(reopened.isClosed(), "Escape must release the preview, rather than leaving a hidden renderer");
                    assertTrue(reopened.getGame().getGameListeners().isEmpty());
                    assertNull(previewField.get(lounge));
                } finally {
                    lounge.killPreviewBV();
                    lounge.removeAllListeners();
                }
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

    private record Session(Game game, ClientGUI gui, boolean use3D, Set<CloseClientListener> closeListeners) implements AutoCloseable {
        static Session create() throws Exception {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean previous = preferences.getUse3DBoard();
            preferences.setUse3DBoard(true);
            Game game = new Game();
            game.setPhase(GamePhase.LOUNGE);
            Player player = new Player(0, "Native lifecycle");
            game.addPlayer(0, player);
            Client client = mock(Client.class);
            Set<CloseClientListener> closeListeners = new HashSet<>();
            doAnswer(call -> { closeListeners.add(call.getArgument(0)); return null; })
                  .when(client).addCloseClientListener(any());
            doAnswer(call -> { closeListeners.remove(call.getArgument(0)); return null; })
                  .when(client).removeCloseClientListener(any());
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
            return new Session(game, gui, previous, closeListeners);
        }

        @Override
        public void close() {
            if (gui.getFrame().isDisplayable() || !gui.boardStates().isEmpty()) {
                gui.die();
            }
            GUIPreferences.getInstance().setUse3DBoard(use3D);
        }
    }

    private static List<?> boardListeners(Board board) {
        try {
            var field = Board.class.getDeclaredField("boardListeners");
            field.setAccessible(true);
            return (List<?>) field.get(board);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static <T> T onClient(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
