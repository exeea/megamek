/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.KeyEvent;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.ChatterBox;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.common.game.Game;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Characterizes the board chat's Enter key: it sends the draft and keeps it in the shared chat history. */
class ChatterBoxOverlayTest {
    private final Client client = mock(Client.class);
    private final ChatterBox chatterBox = mock(ChatterBox.class);
    private final JPanel panel = new JPanel();
    private ChatterBoxOverlay overlay;

    @BeforeEach
    void createOverlay() throws Exception {
        ClientGUI gui = mock(ClientGUI.class);
        when(gui.getClient()).thenReturn(client);
        when(gui.getMainPanel()).thenReturn(new JPanel());
        when(client.getGame()).thenReturn(new Game());
        BoardClientState view = mock(BoardClientState.class);
        when(view.getFontMetrics(any())).thenAnswer(invocation -> panel.getFontMetrics(invocation.getArgument(0)));
        when(view.getChatterBoxActive()).thenReturn(true);
        chatterBox.history = new LinkedList<>(List.of("earlier"));
        chatterBox.historyBookmark = 1;
        FutureTask<ChatterBoxOverlay> task = new FutureTask<>(() -> new ChatterBoxOverlay(gui, view, null, chatterBox));
        SwingUtilities.invokeAndWait(task);
        overlay = task.get();
    }

    @AfterEach
    void disposeOverlay() {
        overlay.dispose();
        GUIPreferences.getInstance().removePreferenceChangeListener(overlay);
    }

    private void enter(String draft) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            overlay.setMessage(draft);
            overlay.keyPressed(new KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n'));
        });
    }

    @Test
    void enterSendsTheDraftOnceAndPutsItFirstInTheHistory() throws Exception {
        enter("Moving Alpha to the ridge");

        verify(client).sendChat("Moving Alpha to the ridge");
        assertEquals(List.of("Moving Alpha to the ridge", "earlier"), chatterBox.history);
        assertEquals(-1, chatterBox.historyBookmark);
        assertEquals("", overlay.getMessage());
        verify(chatterBox).setMessage("");
    }

    @Test
    void theHistoryKeepsTheTenNewestLines() throws Exception {
        for (int line = 1; line <= 11; line++) {
            enter("line " + line);
        }

        assertEquals(10, chatterBox.history.size());
        assertEquals("line 11", chatterBox.history.getFirst());
        assertEquals("line 2", chatterBox.history.getLast());
    }

    @Test
    void aBlankDraftSendsNothing() throws Exception {
        enter("   ");

        verify(client, never()).sendChat(anyString());
        assertEquals(List.of("earlier"), chatterBox.history);
        assertEquals(1, chatterBox.historyBookmark);
    }
}
