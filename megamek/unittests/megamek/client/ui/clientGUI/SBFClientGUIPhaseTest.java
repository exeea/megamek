/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.swing.SwingUtilities;

import megamek.client.SBFClient;
import megamek.client.ui.clientGUI.boardview.gpu.GpuBoardWindow;
import megamek.client.ui.panels.phaseDisplay.SBFFiringDisplay;
import megamek.client.ui.panels.phaseDisplay.SBFMovementDisplay;
import megamek.client.ui.panels.phaseDisplay.SBFReportDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.planetaryConditions.Light;
import megamek.common.planetaryConditions.PlanetaryConditions;
import megamek.common.strategicBattleSystems.SBFGame;
import org.junit.jupiter.api.Test;

class SBFClientGUIPhaseTest {
    @Test
    void firstTurnsExposeTheirRealControllersAndRejoiningAppliesLaterWeather() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var client = mock(SBFClient.class);
            var game = new SBFGame();
            Player player = new Player(1, "Commander");
            game.addPlayer(1, player);
            when(client.getGame()).thenReturn(game);
            when(client.getLocalPlayer()).thenReturn(player);
            when(client.getName()).thenReturn(player.getName());
            var controller = spy(new MegaMekController());
            try (var launcher = mockStatic(MegaMekGUI.class); var window = mockStatic(GpuBoardWindow.class)) {
                launcher.when(MegaMekGUI::getKeyDispatcher).thenReturn(controller);
                var gui = new SBFClientGUI(client, controller);
                controller.clientGUI = gui;
                try {
                    gui.initialize();
                    game.setBoard(0, Board.createEmptyBoard(4, 4));
                    game.receivePhase(GamePhase.INITIATIVE_REPORT);
                    var report = assertInstanceOf(SBFReportDisplay.class, gui.curPanel);
                    assertTrue(report.getButDone().isEnabled());
                    game.receivePhase(GamePhase.MOVEMENT);
                    var move = assertInstanceOf(SBFMovementDisplay.class, gui.curPanel);
                    assertTrue(report.isIgnoringEvents());
                    assertFalse(move.isIgnoringEvents());
                    game.receivePhase(GamePhase.FIRING);
                    assertInstanceOf(SBFFiringDisplay.class, gui.curPanel);
                    assertTrue(move.isIgnoringEvents());
                    game.receivePhase(GamePhase.SBF_DETECTION_REPORT);
                    assertSame(report, gui.curPanel);
                    assertTrue(report.getButDone().isEnabled());

                    var conditions = new PlanetaryConditions();
                    conditions.setLight(Light.MOONLESS);
                    game.setPlanetaryConditions(conditions);
                    assertEquals(Light.MOONLESS, gui.bvGame.getPlanetaryConditions().getLight());
                } finally {
                    gui.die();
                }
                verify(client).die();
                verify(controller).removeAllActions();
                assertNull(controller.clientGUI);
                assertTrue(gui.boardStates().isEmpty());
            }
        });
    }
}
