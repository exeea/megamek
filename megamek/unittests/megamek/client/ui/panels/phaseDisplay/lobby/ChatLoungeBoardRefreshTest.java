/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.panels.phaseDisplay.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.dialogs.minimap.MinimapPanel;
import megamek.common.Configuration;
import megamek.common.board.Board;
import megamek.common.board.BoardDimensions;
import megamek.common.board.BoardFile;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.server.ServerBoardHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ChatLoungeBoardRefreshTest {
    @TempDir
    Path temporary;
    private File previousBoards;
    private ChatLounge lounge;
    private Client client;
    private Game game;

    @BeforeEach
    void setUp() throws Exception {
        previousBoards = Configuration.boardsDir();
        Configuration.setBoardsDir(Files.createDirectory(temporary.resolve("boards")).toFile());
        client = mock(Client.class);
        game = new Game();
        game.setPhase(GamePhase.LOUNGE);
        lounge = mock(ChatLounge.class);
        when(lounge.client()).thenReturn(client);
        when(lounge.game()).thenReturn(game);
        doCallRealMethod().when(lounge).boardEditorClosed(any(), anyBoolean());
        lounge.mapSettings = MapSettings.getInstance();
        lounge.mapSettings.setBoardSize(2, 2);
        lounge.mapSettings.setMapSize(2, 1);
        lounge.mapSettings.setBoardsSelectedVector(List.of("/existing.board2",
              Board.BOARD_REQUEST_ROTATION + "/existing.board2"));
        lounge.mapSettings.setBoardTheme(0, "snow");
        lounge.mapSettings.setBoardLevelOffset(1, 3);
        lounge.mapButtons = new ArrayList<>(List.of(mock(MapPreviewButton.class)));
        lounge.mapIcons = new HashMap<>(Map.of("/existing.board2", new ImageIcon()));
        field("baseImages", new HashMap<String, Image>(Map.of("/existing.board2",
              new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB))));
        field("invalidBoards", new ArrayList<>(List.of("/existing.board2")));
        field("serverBoards", new ArrayList<>(List.of("/existing.board2")));
        field("loader", lounge.new ImageLoader());
        field("lisBoardsAvailable", mock(JList.class));
    }

    @AfterEach
    void tearDown() throws Exception {
        ChatLounge.ImageLoader loader = field("loader");
        loader.cancel(true);
        Configuration.setBoardsDir(previousBoards);
    }

    @Test
    void savedSelectedSheetKeepsAssemblyAndRequestsServerReload() throws Exception {
        Path saved = save("existing.board2", 2, 2);
        ChatLounge.ImageLoader previous = field("loader");
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(saved, false));

        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        assertNotSame(lounge.mapSettings, request.getValue());
        assertEquals(lounge.mapSettings.getBoardsSelectedVector(), request.getValue().getBoardsSelectedVector());
        assertEquals(2, request.getValue().getMapWidth());
        assertEquals("snow", request.getValue().getBoardTheme(0));
        assertEquals(3, request.getValue().getBoardLevelOffset(1));
        verify(lounge.mapButtons.getFirst()).reset();
        assertTrue(lounge.mapIcons.isEmpty());
        assertTrue(this.<Map<?, ?>>field("baseImages").isEmpty());
        assertTrue(this.<List<?>>field("invalidBoards").isEmpty());
        assertTrue(this.<List<?>>field("serverBoards").isEmpty());
        assertTrue(previous.isCancelled());
        assertNotSame(previous, field("loader"));
        assertTrue(this.<Boolean>field("boardEditorRefreshPending"));
    }

    @Test
    void savingAssembledPreviewOverItsSourceDoesNotApplyAdjustmentsTwice() throws Exception {
        Path saved = save("existing.board2", 2, 2);
        Board original = BoardFile.read(saved);
        original.getHex(0, 0).setLevel(1);
        BoardFile.save(original, saved);
        lounge.mapSettings.setMapSize(1, 1);
        lounge.mapSettings.setBoardsSelectedVector(List.of(Board.BOARD_REQUEST_ROTATION + "/existing.board2"));
        lounge.mapSettings.setBoardTheme(0, "snow");
        lounge.mapSettings.setBoardLevelOffset(0, 3);
        Board preview = ServerBoardHelper.getPossibleGameBoard(lounge.mapSettings, false);
        assertEquals(4, preview.getHex(1, 1).getLevel());
        assertEquals("snow", preview.getHex(1, 1).getTheme());
        var session = new BoardEditorSession();
        session.open(preview, null);
        session.board().getHex(0, 0).setLevel(5);
        session.board().getHex(0, 0).setTheme("desert");
        session.save(saved);

        SwingUtilities.invokeAndWait(() ->
              lounge.boardEditorClosed(session.lastSavedPath(), session.lastSaveFromAssembledPreview()));
        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        Board reloaded = ServerBoardHelper.getPossibleGameBoard(request.getValue(), false);
        assertEquals(5, reloaded.getHex(0, 0).getLevel(), "The edited height must not be shifted or rotated again");
        assertEquals("desert", reloaded.getHex(0, 0).getTheme(), "The saved theme must not be overridden again");
        assertEquals(4, reloaded.getHex(1, 1).getLevel(), "The saved rotation must not be applied again");
        assertEquals("snow", reloaded.getHex(1, 1).getTheme());
        assertEquals(3, preview.getHex(0, 0).getLevel(), "Editing still leaves the original preview untouched");
    }

    @Test
    void saveAsSelectsSavedDocumentSizeAndServerScanIncludesTheNewMap() throws Exception {
        save("existing.board2", 2, 2);
        Path saved = save("new map.board2", 3, 4);
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(saved, false));

        ArgumentCaptor<MapSettings> request = ArgumentCaptor.forClass(MapSettings.class);
        verify(client).sendMapSettings(request.capture());
        MapSettings settings = request.getValue();
        assertEquals(List.of("/new map.board2"), settings.getBoardsSelectedVector());
        assertEquals(1, settings.getMapWidth());
        assertEquals(1, settings.getMapHeight());
        assertEquals(new BoardDimensions(3, 4), settings.getBoardSize());
        assertNull(settings.getBoardTheme(0));
        assertEquals(0, settings.getBoardLevelOffset(0));
        assertTrue(ServerBoardHelper.scanForBoards(settings).contains("/new map.board2"));
        assertEquals(2, lounge.mapSettings.getMapWidth(), "The client waits for the server's authoritative response");
    }

    @Test
    void savedFileOutsideBoardRootsDoesNotReplaceLobbySelection() throws Exception {
        Path saved = temporary.resolve("outside.board2");
        BoardFile.save(Board.createEmptyBoard(3, 4), saved);
        MapSettings settings = MapSettings.getInstance(lounge.mapSettings);
        assertSame(settings, ChatLounge.settingsForSavedBoard(settings, saved, true));
        assertEquals(lounge.mapSettings.getBoardsSelectedVector(), settings.getBoardsSelectedVector());
        assertEquals(new BoardDimensions(2, 2), settings.getBoardSize());
    }

    @Test
    void finishedOldThumbnailCannotRestoreStaleImageOrValidationFlags() throws Exception {
        Path saved = save("existing.board2", 2, 2);
        ChatLounge.ImageLoader previous = field("loader");
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(saved, false));
        var prepare = ChatLounge.ImageLoader.class.getDeclaredMethod("prepareImage", String.class);
        prepare.setAccessible(true);
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        try (var minimaps = mockStatic(MinimapPanel.class)) {
            minimaps.when(() -> MinimapPanel.getMinimapImage(any(Board.class), anyInt())).thenReturn(image);
            assertNull(prepare.invoke(previous, "/existing.board2"));
            assertTrue(this.<Map<?, ?>>field("baseImages").isEmpty());
            assertTrue(this.<List<?>>field("invalidBoards").isEmpty());
            assertTrue(this.<List<?>>field("serverBoards").isEmpty());
            ChatLounge.ImageLoader current = field("loader");
            assertSame(image, prepare.invoke(current, "/existing.board2"));
            assertSame(image, this.<Map<String, Image>>field("baseImages").get("/existing.board2"));
        }
        JList<?> list = field("lisBoardsAvailable");
        SwingUtilities.invokeAndWait(() -> previous.process(List.of(image)));
        verify(list, never()).setFixedCellHeight(anyInt());
    }

    @Test
    void noSaveOrNoLongerInLobbyDoesNotReload() throws Exception {
        ChatLounge.ImageLoader previous = field("loader");
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(null, false));
        game.setPhase(GamePhase.MOVEMENT);
        Path saved = save("existing.board2", 2, 2);
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(saved, false));
        game.setPhase(GamePhase.LOUNGE);
        when(lounge.isIgnoringEvents()).thenReturn(true);
        SwingUtilities.invokeAndWait(() -> lounge.boardEditorClosed(saved, false));
        verify(client, never()).sendMapSettings(any());
        assertFalse(previous.isCancelled());
    }

    private Path save(String filename, int width, int height) throws Exception {
        Path file = Configuration.boardsDir().toPath().resolve(filename);
        BoardFile.save(Board.createEmptyBoard(width, height), file);
        return file;
    }

    private void field(String name, Object value) throws ReflectiveOperationException {
        var field = ChatLounge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(lounge, value);
    }

    @SuppressWarnings("unchecked")
    private <T> T field(String name) throws ReflectiveOperationException {
        var field = ChatLounge.class.getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(lounge);
    }
}
