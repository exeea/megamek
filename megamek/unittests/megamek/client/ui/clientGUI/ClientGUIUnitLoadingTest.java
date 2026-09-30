/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.clientGUI.audio.SoundManager;
import megamek.client.ui.dialogs.UnitLoadingDialog;
import megamek.client.ui.dialogs.randomArmy.RandomArmyDialog;
import megamek.client.ui.dialogs.unitSelectorDialogs.MegaMekUnitSelectorDialog;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.loaders.MapSettings;
import megamek.common.loaders.MekSummaryCache;
import org.junit.jupiter.api.Test;

class ClientGUIUnitLoadingTest {
    @Test
    void enteringLobbyDoesNotWaitForUnitsOrConstructUnitTools() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        AtomicReference<ClientGUI> opened = new AtomicReference<>();
        FutureTask<Void> startup = new FutureTask<>(() -> {
            GUIPreferences preferences = GUIPreferences.getInstance();
            boolean use3D = preferences.getUse3DBoard();
            preferences.setUse3DBoard(false);
            try (var sound = mockConstruction(SoundManager.class);
                  var cacheAccess = mockStatic(MekSummaryCache.class);
                  var application = mockStatic(MegaMekGUI.class)) {
                MekSummaryCache cache = mock(MekSummaryCache.class);
                cacheAccess.when(MekSummaryCache::getInstance).thenReturn(cache);
                MegaMekController controller = new MegaMekController();
                application.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                Game game = new Game();
                game.setPhase(GamePhase.LOUNGE);
                Player player = new Player(0, "Loading test");
                game.addPlayer(0, player);
                Client client = mock(Client.class);
                when(client.getName()).thenReturn(player.getName());
                when(client.getGame()).thenReturn(game);
                when(client.getLocalPlayer()).thenReturn(player);
                MapSettings settings = MapSettings.getInstance();
                settings.setBoardsSelectedVector(List.of(MapSettings.BOARD_GENERATED));
                when(client.getMapSettings()).thenReturn(settings);
                ClientGUI gui = new ClientGUI(client, controller);
                opened.set(gui);
                try {
                    gui.initialize();
                    gui.switchPanel(GamePhase.LOUNGE);
                    assertTrue(gui.getFrame().isShowing());
                    UnitLoadingDialog progress = Arrays.stream(gui.getFrame().getOwnedWindows())
                          .filter(UnitLoadingDialog.class::isInstance)
                          .map(UnitLoadingDialog.class::cast)
                          .findFirst().orElseThrow();
                    assertTrue(progress.isShowing());
                    assertFalse(progress.isModal(), "Entering the lobby must not block its controls");
                    assertTrue(Arrays.stream(gui.getFrame().getOwnedWindows())
                          .noneMatch(window -> (window instanceof MegaMekUnitSelectorDialog)
                                || (window instanceof RandomArmyDialog)));
                    verify(cache, never()).getAllMeks();
                } finally {
                    gui.die();
                    opened.set(null);
                }
            } finally {
                preferences.setUse3DBoard(use3D);
            }
            return null;
        });
        SwingUtilities.invokeLater(startup);
        try {
            startup.get(20, TimeUnit.SECONDS);
        } finally {
            // Also release a modal progress window if a regression makes initialize() wait indefinitely.
            SwingUtilities.invokeAndWait(() -> {
                ClientGUI gui = opened.get();
                if (gui != null) {
                    gui.getFrame().dispose();
                }
            });
        }
    }
}
