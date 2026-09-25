/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.awt.event.InputEvent;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.dialogs.unitDisplay.UnitDisplayPanel;
import megamek.client.ui.dialogs.unitDisplay.WeaponPanel;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.GameTurn;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;

class GpuBoardActionsTest {
    private record Controls(GpuBoardActions actions, FiringDisplay phase, WeaponPanel weapons, Entity target) { }

    @Test
    void terrainPlottingRequiresAnActiveMovementUnitAndTheCurrentPlayersTurn() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                ClientGUI gui = mock(ClientGUI.class);
                Client client = mock(Client.class);
                when(gui.getClient()).thenReturn(client);
                when(client.isMyTurn()).thenReturn(true);
                BoardView view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                MovementDisplay movement = mock(MovementDisplay.class);
                when(movement.currentEntity()).thenReturn(fixture.entity);
                AtomicBoolean closed = new AtomicBoolean();
                GpuBoardActions actions = new GpuBoardActions(view, () -> movement, closed::get, () -> { });
                Coords destination = new Coords(4, 5);

                actions.defaultAction(destination, null, 0);
                verify(view).mouseAction(destination, BoardView.BOARD_HEX_DRAG, InputEvent.BUTTON1_DOWN_MASK, 1);
                verify(view).mouseAction(destination, BoardView.BOARD_HEX_CLICK, 0, 1);
                actions.defaultAction(destination, null, InputEvent.SHIFT_DOWN_MASK);
                verify(view).mouseAction(destination, BoardView.BOARD_HEX_DRAG,
                      InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, 1);
                verify(view).mouseAction(destination, BoardView.BOARD_HEX_CLICK, InputEvent.SHIFT_DOWN_MASK, 1);
                clearInvocations(view);

                when(movement.currentEntity()).thenReturn(null);
                actions.defaultAction(destination, null, 0);
                when(movement.currentEntity()).thenReturn(fixture.entity);
                when(client.isMyTurn()).thenReturn(false);
                actions.defaultAction(destination, null, 0);
                when(client.isMyTurn()).thenReturn(true);
                when(movement.isIgnoringEvents()).thenReturn(true);
                actions.defaultAction(destination, null, 0);
                when(movement.isIgnoringEvents()).thenReturn(false);
                fixture.game.setPhase(GamePhase.FIRING);
                actions.defaultAction(destination, null, 0);
                fixture.game.setPhase(GamePhase.MOVEMENT);
                actions.defaultAction(null, null, 0);
                actions.defaultAction(new Coords(-1, -1), null, 0);
                closed.set(true);
                actions.defaultAction(destination, null, 0);
                verify(view, never()).mouseAction(any(Coords.class), anyInt(), anyInt(), anyInt());
            });
        }
    }

    @Test
    void deploymentClicksPlaceTheUnitAndShiftClicksTurnItWithoutMovingIt() throws Exception {
        Board board = new Board(7, 7);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                board.setHex(x, y, new Hex());
            }
        }
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                fixture.player.setStartingPos(Board.START_ANY);
                fixture.entity.setPosition(null);
                fixture.entity.setDeployed(false);
                ClientGUI gui = mock(ClientGUI.class);
                Client client = mock(Client.class);
                gui.controller = mock(MegaMekController.class);
                when(gui.getClient()).thenReturn(client);
                when(client.getGame()).thenReturn(fixture.game);
                when(client.getLocalPlayer()).thenReturn(fixture.player);
                when(client.isMyTurn()).thenReturn(true);
                BoardView view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                when(gui.boardViews()).thenReturn(List.of(view));
                when(gui.getBoardView(any(Entity.class))).thenReturn(view);
                try (var keys = mockStatic(MegaMekGUI.class)) {
                    keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(gui.controller);
                    DeploymentDisplay phase = spy(new DeploymentDisplay(gui));
                    doReturn(fixture.entity).when(phase).currentEntity();
                    view.addBoardViewListener(phase);
                    try {
                        GpuBoardActions actions = new GpuBoardActions(view, () -> phase, () -> false, () -> { });
                        Coords destination = new Coords(3, 3);
                        Coords facing = new Coords(4, 3);
                        actions.defaultAction(destination, null, 0);
                        assertEquals(destination, fixture.entity.getPosition(), "A terrain click places the deploying unit");
                        actions.defaultAction(facing, null, InputEvent.SHIFT_DOWN_MASK);
                        assertEquals(destination, fixture.entity.getPosition(), "Shift changes facing without redeploying");
                        assertEquals(destination.direction(facing), fixture.entity.getFacing());
                        assertEquals(fixture.entity.getFacing(), fixture.entity.getSecondaryFacing());
                        when(client.isMyTurn()).thenReturn(false);
                        actions.defaultAction(new Coords(2, 2), null, 0);
                        assertEquals(destination, fixture.entity.getPosition(), "Deployment remains restricted to your turn");
                    } finally {
                        view.removeBoardViewListener(phase);
                        phase.removeAllListeners();
                    }
                }
            });
        }
    }

    @Test
    void enemyDefaultActionTargetsThePickedStackedUnitAndRechecksVisibility() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                Entity second = new BipedMek();
                second.setId(43);
                second.setOwner(controls.target().getOwner());
                second.setPosition(controls.target().getPosition());
                second.setDeployed(true);
                fixture.game.addEntity(second, false);
                second.addBeenSeenBy(fixture.player);
                controls.actions().defaultAction(second.getPosition(), second, 0);
                verify(controls.phase()).target(second);
                verify(controls.phase(), never()).target(controls.target());
                clearInvocations(controls.phase());
                second.setHidden(true);
                controls.actions().defaultAction(second.getPosition(), second, 0);
                verify(controls.phase(), never()).target(any());
            });
        }
    }

    @Test
    void presentationTextDecodesHtmlEntitiesWithoutRemovingLiteralComparisons() {
        assertEquals("BattleMaster (85t)  Biped Mek\nHeat < 10 & Damage > 5 — 'ready'",
              GpuBoardActions.plainText("<html><head><style>hidden</style></head><b>BattleMaster (85t)</b>"
                    + "&#160;&#xA0;Biped Mek<br>Heat &lt; 10 &amp; Damage &gt; 5 &mdash; &#39;ready&apos;</html>"));
        assertEquals("A B C", GpuBoardActions.plainText("A&nbsp;B\u00A0C"));
        assertEquals("", GpuBoardActions.plainText(null));
    }

    @Test
    void globalBoardSwitchSurvivesPhaseChangesAndStillChecksLiveMenuAvailability() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicInteger clicks = new AtomicInteger();
            AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
            AtomicReference<List<BoardScene.Command>> commands = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                menus.set(CommonMenuBar.getMenuBarForGame());
                menus.get().setPhase(GamePhase.MOVEMENT);
                menus.get().setBoardView3D(true);
                menus.get().addActionListener(event -> clicks.incrementAndGet());
                ClientGUI gui = mock(ClientGUI.class);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardView view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                commands.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }).globalCommands());
                fixture.game.setPhase(GamePhase.MOVEMENT_REPORT);
                menus.get().setPhase(GamePhase.MOVEMENT_REPORT);
            });
            try {
                find(commands.get(), Messages.getString("CommonMenuBar.viewClassicBoard")).action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, clicks.get(), "Returning to classic is a global action, independent of the old phase");
                SwingUtilities.invokeAndWait(() -> menus.get().setPhase(GamePhase.LOUNGE));
                find(commands.get(), Messages.getString("CommonMenuBar.viewClassicBoard")).action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, clicks.get(), "An unavailable global menu item must not execute a stale callback");
            } finally {
                SwingUtilities.invokeAndWait(() -> menus.get().die());
            }
        }
    }

    @Test
    void stackedTargetsStayDistinctAndAStaleChoiceCannotRevealAHiddenUnit() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            AtomicReference<List<BoardScene.Command>> menu = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                Entity second = new BipedMek();
                second.setId(43);
                second.setChassis("Second target");
                second.setOwner(controls.target().getOwner());
                second.setPosition(controls.target().getPosition());
                second.setDeployed(true);
                fixture.game.addEntity(second, false);
                second.addBeenSeenBy(fixture.player);
                menu.set(controls.actions().contextCommands(second.getPosition()));
            });
            assertEquals(2, flatten(menu.get()).stream().filter(command -> command.id().contains("E|")).count());
            BoardScene.Command oldTarget = flatten(menu.get()).stream().filter(command -> command.id().contains("E|42"))
                  .findFirst().orElseThrow();
            SwingUtilities.invokeAndWait(() -> controls.target().setHidden(true));
            oldTarget.action().run();
            SwingUtilities.invokeAndWait(() -> menu.set(controls.actions().contextCommands(controls.target().getPosition())));
            verify(controls.phase(), never()).target(any());
            assertEquals(1, flatten(menu.get()).stream().filter(command -> command.id().contains("E|")).count());
        }
    }

    private Controls controls(GpuBoardFixture fixture) throws Exception {
        FutureTask<Controls> task = new FutureTask<>(() -> {
            fixture.game.setPhase(GamePhase.FIRING);
            ClientGUI gui = mock(ClientGUI.class);
            Client client = mock(Client.class);
            when(gui.getClient()).thenReturn(client);
            when(client.getGame()).thenReturn(fixture.game);
            when(client.getLocalPlayer()).thenReturn(fixture.player);
            when(client.isMyTurn()).thenReturn(true);
            when(client.getMyTurn()).thenReturn(new GameTurn(fixture.player.getId()));
            BoardView view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            FiringDisplay phase = mock(FiringDisplay.class);
            when(phase.currentEntity()).thenReturn(fixture.entity);
            when(phase.getComponents()).thenReturn(new Component[0]);
            when(phase.getActionButtons()).thenReturn(List.of());
            when(phase.getCompletionButtons()).thenReturn(List.of());
            when(phase.shouldReceiveKeyCommands()).thenReturn(true);
            UnitDisplayPanel display = mock(UnitDisplayPanel.class);
            when(gui.getUnitDisplay()).thenReturn(display);
            WeaponPanel weapons = mock(WeaponPanel.class);
            display.wPan = weapons;
            weapons.weaponList = new JList<>(new String[] { "Laser A", "Laser B" });
            weapons.m_chBayWeapon = new JComboBox<>();
            when(weapons.getAmmoSelector()).thenReturn(new JComboBox<>(new String[] { "Standard", "Special" }));
            when(weapons.getSelectedEntityId()).thenReturn(fixture.entity.getId());
            when(weapons.getSelectedWeaponNum()).thenReturn(-1);
            when(weapons.getTargetSummary()).thenReturn("<html>7+ to hit</html>");
            Player enemy = new Player(2, "Enemy");
            enemy.setTeam(2);
            fixture.game.addPlayer(enemy.getId(), enemy);
            Entity target = new BipedMek();
            target.setId(42);
            target.setChassis("Target");
            target.setModel("Test");
            target.setOwner(enemy);
            target.setPosition(new Coords(6, 5));
            target.setDeployed(true);
            fixture.game.addEntity(target, false);
            target.addBeenSeenBy(fixture.player);
            when(gui.getDisplayedUnit()).thenReturn(target);
            AtomicReference<Targetable> selected = new AtomicReference<>();
            when(phase.getTarget()).thenAnswer(invocation -> selected.get());
            doAnswer(invocation -> {
                selected.set(invocation.getArgument(0));
                return null;
            }).when(phase).target(any());
            when(phase.isFireAllowed()).thenReturn(true);
            return new Controls(new GpuBoardActions(view, () -> phase, () -> false, () -> { }), phase, weapons, target);
        });
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    @Test
    void inspectionDoesNotTargetAndActionsCannotEscapeTheirActingUnit() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            AtomicReference<List<BoardScene.Command>> menu = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(fixture.entity.getId(), controls.actions().actorId());
                menu.set(controls.actions().contextCommands(controls.target().getPosition()));
            });
            verify(controls.phase(), never()).target(any());
            assertFalse(find(menu.get(), "Fire").enabled());
            BoardScene.Command target = flatten(menu.get()).stream().filter(command -> command.id().contains("E|42"))
                  .findFirst().orElseThrow();
            target.action().run();
            SwingUtilities.invokeAndWait(() -> menu.set(controls.actions().contextCommands(controls.target().getPosition())));
            verify(controls.phase(), times(1)).target(controls.target());
            BoardScene.Command fire = find(menu.get(), "Fire");
            assertTrue(fire.enabled());
            fire.action().run();
            SwingUtilities.invokeAndWait(() -> { });
            verify(controls.phase(), times(1)).fire();
            verify(controls.phase(), times(1)).target(any());
            SwingUtilities.invokeAndWait(() -> when(controls.phase().currentEntity()).thenReturn(controls.target()));
            fire.action().run();
            SwingUtilities.invokeAndWait(() -> { });
            verify(controls.phase(), times(1)).fire();
        }
    }

    @Test
    void offPageCommandsAndWeaponChoicesUseTheOriginalModels() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            AtomicInteger clicks = new AtomicInteger();
            AtomicReference<List<BoardScene.Command>> commands = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                MegaMekButton offPage = new MegaMekButton("Off page");
                offPage.setActionCommand("offPage");
                offPage.addActionListener(event -> clicks.incrementAndGet());
                when(controls.phase().getActionButtons()).thenReturn(List.of(offPage));
                commands.set(controls.actions().phaseCommands());
            });
            find(commands.get(), "Off page").action().run();
            find(commands.get(), "Laser B").action().run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, clicks.get());
            assertEquals(1, controls.weapons().weaponList.getSelectedIndex());
            SwingUtilities.invokeAndWait(() -> controls.weapons().weaponList.setEnabled(false));
            flatten(commands.get()).stream().filter(command -> command.id().equals("weapon:0"))
                  .findFirst().orElseThrow().action().run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, controls.weapons().weaponList.getSelectedIndex(), "A stale disabled list cannot select a weapon");
            BoardScene.Command ammo = find(commands.get(), "Special");
            SwingUtilities.invokeAndWait(() -> controls.weapons().getAmmoSelector().setEnabled(false));
            ammo.action().run();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(0, controls.weapons().getAmmoSelector().getSelectedIndex());
        }
    }

    private static List<BoardScene.Command> flatten(List<BoardScene.Command> commands) {
        return commands.stream().flatMap(command -> java.util.stream.Stream.concat(java.util.stream.Stream.of(command),
              flatten(command.children()).stream())).toList();
    }

    @Test
    void attackSnapshotUsesTheActingUnitsPresentationAndPendingOrders() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                when(controls.weapons().getTargetName()).thenReturn("Target Test");
                when(controls.weapons().getWeaponSummary()).thenReturn("Laser<br>Heat 3  Damage 5");
                when(controls.weapons().getFiringSolution()).thenReturn("Range: 4<br>To Hit: 7 (58%)");
                when(controls.phase().getAttackDescriptions()).thenReturn(List.of("Laser &gt; Target Test"));
                controls.weapons().weaponList.setSelectedIndex(1);
                BoardScene.Attack state = controls.actions().attackState();
                assertEquals("Target Test", state.targetName());
                assertEquals("Laser\nHeat 3  Damage 5", state.weaponDetails());
                assertEquals("Range: 4\nTo Hit: 7 (58%)", state.targetDetails());
                assertEquals(1, state.selectedWeapon());
                assertEquals(List.of("Laser > Target Test"), state.orders());
                when(controls.weapons().getSelectedEntityId()).thenReturn(controls.target().getId());
                assertEquals("", controls.actions().attackState().weaponDetails(),
                      "Inspecting another unit must not put its weapons in the acting unit's panel");
                when(controls.phase().currentEntity()).thenReturn(null);
                assertNull(controls.actions().attackState());
            });
        }
    }

    @Test
    void ammunitionCannotApplyToAWeaponSelectedAfterTheSnapshot() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            AtomicReference<BoardScene.Command> ammunition = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                controls.weapons().weaponList.setSelectedIndex(0);
                ammunition.set(find(controls.actions().phaseCommands(), "Special"));
                controls.weapons().weaponList.setSelectedIndex(1);
            });
            ammunition.get().action().run();
            SwingUtilities.invokeAndWait(() ->
                  assertEquals(0, controls.weapons().getAmmoSelector().getSelectedIndex()));
        }
    }

    private static BoardScene.Command find(List<BoardScene.Command> commands, String label) {
        return flatten(commands).stream().filter(command -> command.label().equals(label)).findFirst().orElseThrow();
    }
}
