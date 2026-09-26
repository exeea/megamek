/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.event.BoardViewEvent;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.panels.phaseDisplay.ActionPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.DeployMinefieldDisplay;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.panels.phaseDisplay.InfantryVsInfantryCombatDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.panels.phaseDisplay.PhysicalDisplay;
import megamek.client.ui.panels.phaseDisplay.PointblankShotDisplay;
import megamek.client.ui.panels.phaseDisplay.PreEndDeclarationsDisplay;
import megamek.client.ui.panels.phaseDisplay.PrephaseDisplay;
import megamek.client.ui.panels.phaseDisplay.ReportDisplay;
import megamek.client.ui.panels.phaseDisplay.SelectArtyAutoHitHexDisplay;
import megamek.client.ui.panels.phaseDisplay.TargetingPhaseDisplay;
import megamek.client.ui.panels.phaseDisplay.VictorySetupDisplay;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ClientGUIUnitSelectionTest {
    private final Game game = new Game();
    private final Player player = new Player(0, "Local player");
    private final Entity unit = new BipedMek();
    private final ClientGUI gui = mock(ClientGUI.class);
    private final UnitDisplayPanel display = mock(UnitDisplayPanel.class);
    private final BoardView view = mock(BoardView.class);

    ClientGUIUnitSelectionTest() {
        player.setTeam(1);
        game.addPlayer(player.getId(), player);
        unit.setId(1);
        unit.setOwner(player);
        game.addEntity(unit, false);
        Client client = mock(Client.class);
        when(gui.getClient()).thenReturn(client);
        when(client.getGame()).thenReturn(game);
        when(client.getLocalPlayer()).thenReturn(player);
        when(gui.getUnitDisplay()).thenReturn(display);
        doCallRealMethod().when(gui).unitSelected(any());
        doCallRealMethod().when(gui).inspectUnit(anyInt());
    }

    @ParameterizedTest
    @EnumSource(value = GamePhase.class, names = { "STARTING_SCENARIO", "EXCHANGE", "INITIATIVE", "END",
          "INITIATIVE_REPORT", "TARGETING_REPORT", "MOVEMENT_REPORT", "OFFBOARD_REPORT", "FIRING_REPORT",
          "PHYSICAL_REPORT", "END_REPORT", "VICTORY", "VICTORY_SETUP", "SET_ARTILLERY_AUTO_HIT_HEXES",
          "DEPLOY_MINEFIELDS" })
    void phasesWithoutAnActingUnitSupportInspection(GamePhase phase) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            game.setPhase(phase);
            JComponent panel = switch (phase) {
                case VICTORY_SETUP -> mock(VictorySetupDisplay.class);
                case SET_ARTILLERY_AUTO_HIT_HEXES -> mock(SelectArtyAutoHitHexDisplay.class);
                case DEPLOY_MINEFIELDS -> mock(DeployMinefieldDisplay.class);
                default -> phase.isReport() ? mock(ReportDisplay.class) : new JPanel();
            };
            when(gui.getCurrentPanel()).thenReturn(panel);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display).displayEntity(unit);
            verify(gui).setSelectedEntityNum(unit.getId());
            verify(gui, never()).centerOnUnit(any());
        });
    }

    static Stream<Class<? extends JComponent>> actionPanels() {
        return Stream.of(DeploymentDisplay.class, MovementDisplay.class, FiringDisplay.class, PhysicalDisplay.class,
              TargetingPhaseDisplay.class, PrephaseDisplay.class, PointblankShotDisplay.class,
              PreEndDeclarationsDisplay.class, InfantryVsInfantryCombatDisplay.class);
    }

    @ParameterizedTest
    @MethodSource("actionPanels")
    void actionControllersRetainSelectionAndTargetOwnership(Class<? extends JComponent> type) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            when(gui.getCurrentPanel()).thenReturn(mock(type));
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display, never()).displayEntity(any());
            verify(gui, never()).setSelectedEntityNum(anyInt());
        });
    }

    @Test
    void inspectionRejectsMissingConcealedAndSensorOnlyUnits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Player enemy = new Player(2, "Enemy");
            enemy.setTeam(2);
            game.addPlayer(enemy.getId(), enemy);
            unit.setOwner(enemy);
            game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
            game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
            game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, Entity.NONE));
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            unit.addBeenDetectedBy(player);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            unit.addBeenSeenBy(player);
            unit.setHidden(true);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display, never()).displayEntity(any());
            unit.setHidden(false);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display).displayEntity(unit);
            clearInvocations(display);
            when(gui.shouldIgnoreHotKeys()).thenReturn(true);
            gui.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display, never()).displayEntity(any());
        });
    }

    static Stream<Class<? extends ActionPhaseDisplay>> endOfTurnPanels() {
        return Stream.of(PreEndDeclarationsDisplay.class, InfantryVsInfantryCombatDisplay.class);
    }

    @ParameterizedTest
    @MethodSource("endOfTurnPanels")
    void endOfTurnCombatPanelsAllowInspectionWhileWaiting(Class<? extends ActionPhaseDisplay> type) throws Exception {
        ActionPhaseDisplay panel = mock(type);
        var gameField = ActionPhaseDisplay.class.getDeclaredField("game");
        gameField.setAccessible(true);
        gameField.set(panel, game);
        var guiField = ActionPhaseDisplay.class.getDeclaredField("clientgui");
        guiField.setAccessible(true);
        guiField.set(panel, gui);
        doCallRealMethod().when(panel).unitSelected(any());
        SwingUtilities.invokeAndWait(() -> {
            panel.unitSelected(new BoardViewEvent(view, BoardViewEvent.SELECT_UNIT, unit.getId()));
            verify(display).displayEntity(unit);
            verify(gui).centerOnUnit(unit);
            verify(panel, never()).clear();
        });
    }
}
