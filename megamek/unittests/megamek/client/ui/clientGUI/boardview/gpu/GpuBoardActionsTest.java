/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.MapMenu;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Player;
import megamek.common.board.Board;
import megamek.common.board.BoardLocation;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.GameTurn;
import megamek.common.options.OptionsConstants;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;

class GpuBoardActionsTest {
    private record Controls(GpuBoardActions actions, FiringDisplay phase, UnitDisplayState weapons,
          Entity target, ClientGUI gui) { }

    @Test
    void presentationTextDecodesHtmlEntitiesWithoutRemovingLiteralComparisons() {
        assertEquals("BattleMaster (85t)  Biped Mek\nHeat < 10 & Damage > 5 — 'ready'",
              GpuBoardActions.plainText("<html><head><style>hidden</style></head><b>BattleMaster (85t)</b>"
                    + "&#160;&#xA0;Biped Mek<br>Heat &lt; 10 &amp; Damage &gt; 5 &mdash; &#39;ready&apos;</html>"));
        assertEquals("A B C", GpuBoardActions.plainText("A&nbsp;B\u00A0C"));
        assertEquals("", GpuBoardActions.plainText(null));
    }

    @Test
    void globalMenuItemSurvivesPhaseChangesAndStillChecksLiveMenuAvailability() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicInteger clicks = new AtomicInteger();
            AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
            AtomicReference<List<BoardScene.Command>> commands = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                menus.set(CommonMenuBar.getMenuBarForGame());
                menus.get().setPhase(GamePhase.MOVEMENT);
                menus.get().addActionListener(event -> clicks.incrementAndGet());
                ClientGUI gui = mock(ClientGUI.class);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                commands.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }).globalCommands());
                fixture.game.setPhase(GamePhase.MOVEMENT_REPORT);
                menus.get().setPhase(GamePhase.MOVEMENT_REPORT);
            });
            try {
                // Player Settings is available during a game, not in the lobby.
                find(commands.get(), Messages.getString("CommonMenuBar.viewPlayerSettings")).action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, clicks.get(),
                      "A menu bar item is a global action, independent of the old phase");
                SwingUtilities.invokeAndWait(() -> menus.get().setPhase(GamePhase.LOUNGE));
                find(commands.get(), Messages.getString("CommonMenuBar.viewPlayerSettings")).action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, clicks.get(), "An unavailable global menu item must not execute a stale callback");
            } finally {
                SwingUtilities.invokeAndWait(() -> menus.get().die());
            }
        }
    }

    @Test
    void menuItemsAreFoundInTheirMenusByTheirActionCommands() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
            AtomicReference<List<BoardScene.Command>> commands = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                menus.set(CommonMenuBar.getMenuBarForGame());
                ClientGUI gui = mock(ClientGUI.class);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                commands.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }).globalCommands());
            });
            try {
                // The View menu's items that the HUD's minimap and conditions buttons run.
                assertEquals(Messages.getString("CommonMenuBar.viewMinimap"),
                      GpuBoardActions.menuItem(commands.get(), ClientGUI.VIEW_MINI_MAP).label());
                assertEquals(Messages.getString("CommonMenuBar.viewPlanetaryConditions"),
                      GpuBoardActions.menuItem(commands.get(), ClientGUI.VIEW_PLANETARY_CONDITIONS_OVERLAY).label());
                assertNull(GpuBoardActions.menuItem(commands.get(), "viewMini"), "A prefix names no item");
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

    @Test
    void explicitRosterUnitUsesItsBoardAndNeedsNoHexForViewing() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.setBoard(1, Board.createEmptyBoard(32, 32));
                controls.target().setBoardId(1);
                controls.target().setPosition(new Coords(30, 30));
                List<BoardScene.Command> onBoard = flatten(controls.actions().contextCommands(
                      controls.target().getPosition(), controls.target().getId()));
                assertTrue(onBoard.stream().anyMatch(command -> command.unitAction() != null
                      && command.unitAction().type() == MapMenu.UnitActionType.TARGET),
                      "The roster unit's board supplies its hex, even outside the displayed board");

                List<BoardScene.Command> roster = flatten(controls.actions().contextCommands(null, controls.target().getId()));
                List<MapMenu.UnitAction> actions = roster.stream().map(BoardScene.Command::unitAction)
                      .filter(action -> action != null && (action.type() == MapMenu.UnitActionType.VIEW
                            || action.type() == MapMenu.UnitActionType.READOUT)).toList();
                assertEquals(List.of(new MapMenu.UnitAction(MapMenu.UnitActionType.VIEW, controls.target().getId()),
                            new MapMenu.UnitAction(MapMenu.UnitActionType.READOUT, controls.target().getId())), actions);
                assertTrue(roster.stream().filter(command -> command.children().isEmpty())
                      .allMatch(command -> command.unitAction() != null),
                      "A roster request without coordinates adds no hex actions");
            });
        }
    }

    @Test
    void explicitSensorAndHiddenUnitsKeepHexActionsWithoutRevealingIdentity() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
                controls.target().clearSeenBy();
                controls.target().addBeenDetectedBy(fixture.player);
                List<BoardScene.Command> sensor = controls.actions().contextCommands(
                      controls.target().getPosition(), controls.target().getId());
                assertEquals(List.of(MapMenu.UnitActionType.INSPECT_SENSOR, MapMenu.UnitActionType.CENTER_UNIT),
                      flatten(sensor).stream().map(BoardScene.Command::unitAction)
                            .filter(action -> action != null && action.entityId() == controls.target().getId())
                            .map(MapMenu.UnitAction::type).toList());
                assertFalse(find(sensor, "Fire").enabled(), "The shared hex's phase actions remain available");

                controls.target().addBeenSeenBy(fixture.player);
                controls.target().setHidden(true);
                List<BoardScene.Command> hidden = controls.actions().contextCommands(
                      controls.target().getPosition(), controls.target().getId());
                assertTrue(flatten(hidden).stream().noneMatch(command -> command.unitAction() != null
                      && command.unitAction().entityId() == controls.target().getId()));
                assertFalse(find(hidden, "Fire").enabled());
                assertTrue(controls.actions().contextCommands(null, controls.target().getId()).isEmpty());
            });
        }
    }

    @Test
    void sensorInspectionActionShowsOnlyAnonymousContactInformation() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            AtomicReference<BoardScene.Command> inspect = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                controls.target().clearSeenBy();
                controls.target().addBeenDetectedBy(fixture.player);
                inspect.set(flatten(controls.actions().contextCommands(controls.target().getPosition(),
                      controls.target().getId())).stream().filter(command -> command.unitAction() != null
                      && command.unitAction().type() == MapMenu.UnitActionType.INSPECT_SENSOR)
                      .findFirst().orElseThrow());
            });

            inspect.get().action().run();
            SwingUtilities.invokeAndWait(() -> { });

            verify(controls.gui()).doAlertDialog(Messages.getString("GpuBoard.hud.common.sensorContact"),
                  Messages.getString("GpuBoard.hud.context.unidentifiedHex",
                        controls.target().getPosition().getBoardNum()),
                  JOptionPane.INFORMATION_MESSAGE);
            verify(controls.gui(), never()).inspectUnit(controls.target().getId());
            verify(controls.weapons(), never()).displayEntity(any());
        }
    }

    @Test
    void identifiedAlliesRemainInspectableWithoutPersonalVisualContact() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TEAM_VISION).setValue(false);
                Player ally = new Player(3, "Ally");
                ally.setTeam(fixture.player.getTeam());
                fixture.game.addPlayer(ally.getId(), ally);
                controls.target().setOwner(ally);
                controls.target().clearSeenBy();
                assertFalse(controls.target().hasSeenEntity(fixture.player), "The player has no personal sighting");

                List<MapMenu.UnitActionType> actions = unitActionTypes(controls.actions().contextCommands(
                      controls.target().getPosition(), controls.target().getId()), controls.target().getId());
                assertTrue(actions.containsAll(List.of(MapMenu.UnitActionType.VIEW, MapMenu.UnitActionType.READOUT,
                      MapMenu.UnitActionType.CENTER_UNIT)), "The board identifies allied units without team vision");
                assertFalse(actions.contains(MapMenu.UnitActionType.INSPECT_SENSOR));
            });
        }
    }

    @Test
    void sensorsDetectAllMakesDetectedUnitsInspectableWithoutVisualContact() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_SENSORS_DETECT_ALL).setValue(true);
                controls.target().clearSeenBy();
                controls.target().addBeenDetectedBy(fixture.player);
                assertFalse(controls.target().hasSeenEntity(fixture.player), "Detection is not a visual sighting");

                List<MapMenu.UnitActionType> actions = unitActionTypes(controls.actions().contextCommands(
                      controls.target().getPosition(), controls.target().getId()), controls.target().getId());
                assertTrue(actions.containsAll(List.of(MapMenu.UnitActionType.VIEW, MapMenu.UnitActionType.READOUT,
                      MapMenu.UnitActionType.CENTER_UNIT)), "Detect-all sensors identify detected units");
                assertFalse(actions.contains(MapMenu.UnitActionType.INSPECT_SENSOR));
            });
        }
    }

    @Test
    void everyStackedSensorContactHasOneAnonymousInspectionEntry() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_DOUBLE_BLIND).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_TAC_OPS_SENSORS).setValue(true);
                fixture.game.getOptions().getOption(OptionsConstants.ADVANCED_HIDDEN_UNITS).setValue(true);
                controls.target().clearSeenBy();
                controls.target().addBeenDetectedBy(fixture.player);
                for (int id : List.of(43, 44, 45)) {
                    Entity other = new BipedMek();
                    other.setId(id);
                    other.setChassis("Private identity " + id);
                    other.setOwner(controls.target().getOwner());
                    other.setPosition(controls.target().getPosition());
                    other.setDeployed(true);
                    fixture.game.addEntity(other, false);
                    other.addBeenDetectedBy(fixture.player);
                    if (id == 44) {
                        other.setHidden(true);
                    } else if (id == 45) {
                        other.addBeenSeenBy(fixture.player);
                    }
                }

                for (int explicit : List.of(Entity.NONE, controls.target().getId(), 45)) {
                    List<BoardScene.Command> commands = flatten(controls.actions().contextCommands(
                          controls.target().getPosition(), explicit));
                    List<BoardScene.Command> sensors = commands.stream().filter(command -> command.unitAction() != null
                          && command.unitAction().type() == MapMenu.UnitActionType.INSPECT_SENSOR).toList();
                    assertEquals(List.of(42, 43), sensors.stream().map(command -> command.unitAction().entityId())
                          .sorted().toList(), "Hex, sensor and identified-unit menus cover the same contacts once");
                    assertTrue(sensors.stream().allMatch(command -> command.label().equals(
                          Messages.getString("GpuBoard.hud.context.inspectSensorReturn"))));
                    for (int sensorId : List.of(42, 43)) {
                        assertTrue(unitActionTypes(commands, sensorId).stream().allMatch(type ->
                              type == MapMenu.UnitActionType.INSPECT_SENSOR
                                    || type == MapMenu.UnitActionType.CENTER_UNIT),
                              "Anonymous contacts expose no identity-bearing unit actions");
                    }
                    assertTrue(unitActionTypes(commands, 44).isEmpty(), "Hidden contacts stay absent");
                }
            });
        }
    }

    @Test
    void clearedSelectionOffersReselectionWithoutActorCommands() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                List<BoardScene.Command> commands = flatten(controls.actions().contextCommands(
                      fixture.entity.getPosition(), fixture.entity.getId(), MapMenu.ContextTools.NO_SELECTION));
                BoardScene.Command select = commands.stream().filter(command -> command.unitAction() != null
                      && command.unitAction().type() == MapMenu.UnitActionType.SELECT
                      && command.unitAction().entityId() == fixture.entity.getId()).findFirst().orElseThrow();
                assertTrue(select.enabled(), "The still-current game actor can restore a cleared HUD selection");
                assertTrue(unitActionTypes(commands, fixture.entity.getId()).containsAll(List.of(
                      MapMenu.UnitActionType.VIEW, MapMenu.UnitActionType.READOUT,
                      MapMenu.UnitActionType.CENTER_UNIT)));
                assertTrue(commands.stream().noneMatch(command -> List.of("Fire", "Skip", "Alpha Strike")
                      .contains(command.label())), "Commands that act for the cleared selection remain absent");
                assertTrue(commands.stream().map(BoardScene.Command::unitAction).filter(action -> action != null)
                      .noneMatch(action -> List.of(MapMenu.UnitActionType.TARGET, MapMenu.UnitActionType.LINE_OF_SIGHT,
                            MapMenu.UnitActionType.PLAN_MOVE, MapMenu.UnitActionType.PIN_MOVE,
                            MapMenu.UnitActionType.MAKE_PRIMARY, MapMenu.UnitActionType.REMOVE_TARGET)
                            .contains(action.type())));
            });
        }
    }

    private static List<MapMenu.UnitActionType> unitActionTypes(List<BoardScene.Command> commands, int entityId) {
        return flatten(commands).stream().map(BoardScene.Command::unitAction)
              .filter(action -> action != null && action.entityId() == entityId).map(MapMenu.UnitAction::type).toList();
    }

    @Test
    void editorCapabilitiesBecomeSharedCommandsWithTheirBoardLocations() throws Exception {
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            Controls controls = controls(fixture);
            SwingUtilities.invokeAndWait(() -> {
                Coords hex = new Coords(3, 4);
                List<BoardScene.Command> movement = flatten(controls.actions().contextCommands(hex, Entity.NONE,
                      new MapMenu.ContextTools(true, false, fixture.entity.getId(), false, false)));
                BoardScene.Command plan = movement.stream().filter(command -> command.unitAction() != null
                      && command.unitAction().type() == MapMenu.UnitActionType.PLAN_MOVE).findFirst().orElseThrow();
                BoardScene.Command pin = movement.stream().filter(command -> command.unitAction() != null
                      && command.unitAction().type() == MapMenu.UnitActionType.PIN_MOVE).findFirst().orElseThrow();
                assertTrue(plan.enabled());
                assertFalse(pin.enabled(), "Jump planning cannot pin an intermediate waypoint");
                assertEquals(BoardLocation.of(hex, fixture.view.getBoardId()), plan.unitAction().location());
                assertEquals(plan.unitAction().location(), pin.unitAction().location());
                assertTrue(flatten(controls.actions().contextCommands(hex, Entity.NONE,
                      new MapMenu.ContextTools(false, false, Entity.NONE, false, false))).stream()
                      .noneMatch(command -> command.unitAction() != null
                            && command.unitAction().type() == MapMenu.UnitActionType.LINE_OF_SIGHT),
                      "No measuring actor is invented when the source has no own focus or local actor");

                List<MapMenu.UnitAction> orders = flatten(controls.actions().contextCommands(null,
                      controls.target().getId(), new MapMenu.ContextTools(false, false, fixture.entity.getId(), true, true)))
                      .stream().map(BoardScene.Command::unitAction)
                      .filter(action -> action != null && (action.type() == MapMenu.UnitActionType.MAKE_PRIMARY
                            || action.type() == MapMenu.UnitActionType.REMOVE_TARGET)).toList();
                assertEquals(List.of(MapMenu.UnitActionType.MAKE_PRIMARY, MapMenu.UnitActionType.REMOVE_TARGET),
                      orders.stream().map(MapMenu.UnitAction::type).toList());
                assertTrue(orders.stream().allMatch(action -> action.entityId() == controls.target().getId()
                      && action.location().equals(controls.target().getBoardLocation())));
            });
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
            BoardClientState view = spy(fixture.view);
            doReturn(gui).when(view).getClientgui();
            FiringDisplay phase = mock(FiringDisplay.class);
            when(phase.currentEntity()).thenReturn(fixture.entity);
            when(phase.getComponents()).thenReturn(new Component[0]);
            when(phase.getActionButtons()).thenReturn(List.of());
            when(phase.getCompletionButtons()).thenReturn(List.of());
            when(phase.shouldReceiveKeyCommands()).thenReturn(true);
            UnitDisplayState weapons = mock(UnitDisplayState.class);
            when(gui.getUnitDisplayState()).thenReturn(weapons);
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
            return new Controls(new GpuBoardActions(view, () -> phase, () -> false, () -> { }), phase, weapons, target, gui);
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
    void offPageCommandsUseTheOriginalModels() throws Exception {
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
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals(1, clicks.get());
        }
    }

    private static List<BoardScene.Command> flatten(List<BoardScene.Command> commands) {
        return commands.stream().flatMap(command -> java.util.stream.Stream.concat(java.util.stream.Stream.of(command),
              flatten(command.children()).stream())).toList();
    }

    private static BoardScene.Command find(List<BoardScene.Command> commands, String label) {
        return flatten(commands).stream().filter(command -> command.label().equals(label)).findFirst().orElseThrow();
    }
}
