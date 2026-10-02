/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.event.ActionEvent;
import java.lang.reflect.Field;
import java.util.Set;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.overlay.ToastLevel;
import megamek.common.Player;
import org.junit.jupiter.api.Test;

/**
 * The client's own key handling stays on after a refused command: the native battle window forwards every key it
 * does not handle itself to the client, which ignores them while its hotkeys are off.
 */
class ClientGUIHotKeyTest {
    @Test
    void aRefusedReinforcementLeavesTheHotKeysOn() throws Exception {
        Set<String> real = Set.of("actionPerformed", "shouldIgnoreHotKeys");
        ClientGUI gui = mock(ClientGUI.class, invocation -> real.contains(invocation.getMethod().getName())
              ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        Player player = new Player(0, "Unassigned");
        player.setTeam(Player.TEAM_UNASSIGNED);
        Client client = mock(Client.class);
        when(client.getLocalPlayer()).thenReturn(player);
        Field field = ClientGUI.class.getDeclaredField("client");
        field.setAccessible(true);
        field.set(gui, client);

        gui.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, ClientGUI.FILE_UNITS_REINFORCE_RAT));

        verify(gui).addToast(ToastLevel.ERROR,
              Messages.getString("ClientGUI.openUnitListFileDialog.noReinforceMessage"));
        assertFalse(gui.shouldIgnoreHotKeys(), "A player without a team keeps the hotkeys");
    }
}
