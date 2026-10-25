/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role.BOT;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role.HUMAN;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role.YOU;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;

import megamek.MMConstants;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.HostRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.LoadRequest;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.Role;
import megamek.client.ui.clientGUI.boardview.gpu.GpuStartWindow.ScenarioRequest;
import megamek.common.Player;
import megamek.common.game.IGame;
import megamek.common.scenario.Scenario;
import org.junit.jupiter.api.Test;

class ScenarioRequestTest {
    private final Scenario scenario = mock(Scenario.class);
    private final IGame game = mock(IGame.class);

    ScenarioRequestTest() {
        when(game.getPlayersList()).thenReturn(List.of(new Player(0, "Davion"), new Player(1, "Kurita"),
              new Player(2, "Liao")));
    }

    @Test
    void theHostTakesOneSideOrWatches() {
        assertTrue(request(List.of(YOU, BOT, HUMAN), "Davion").hasSlot());
        assertFalse(request(List.of(HUMAN, BOT, HUMAN), "Observer").hasSlot());
        assertThrows(IllegalArgumentException.class, () -> request(List.of(YOU, YOU, BOT), "Davion"));
    }

    @Test
    void eachSideNeedsARoleAndTheHostAName() {
        assertThrows(IllegalArgumentException.class, () -> request(List.of(YOU, BOT), "Davion"));
        assertThrows(IllegalArgumentException.class, () -> request(List.of(HUMAN, BOT, BOT), " "));
    }

    @Test
    void aLoadNeedsTheSaveAndTheServerSettings() {
        HostRequest host = new HostRequest("Natasha", "", MMConstants.DEFAULT_PORT, false, "");
        assertThrows(NullPointerException.class, () -> new LoadRequest(null, host));
        assertThrows(NullPointerException.class, () -> new LoadRequest(new File("autosave.sav.gz"), null));
    }

    private ScenarioRequest request(List<Role> roles, String name) {
        return new ScenarioRequest(scenario, game, roles, name, "", MMConstants.DEFAULT_PORT);
    }
}
