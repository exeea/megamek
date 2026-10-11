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
import javax.swing.SwingUtilities;

import megamek.client.Client;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.CommonMenuBar;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.clientGUI.boardview.BoardClientState;
import megamek.client.ui.clientGUI.unitDisplay.UnitDisplayState;
import megamek.client.ui.panels.phaseDisplay.FiringDisplay;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Player;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.game.GameTurn;
import megamek.common.units.BipedMek;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import org.junit.jupiter.api.Test;

class GpuBoardActionsTest {
    private record Controls(GpuBoardActions actions, FiringDisplay phase, UnitDisplayState weapons, Entity target) { }

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
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                commands.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }).globalCommands());
                fixture.game.setPhase(GamePhase.MOVEMENT_REPORT);
                menus.get().setPhase(GamePhase.MOVEMENT_REPORT);
            });
            try {
                find(commands.get(), Messages.getString("CommonMenuBar.viewLegacy2D")).action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(1, clicks.get(),
                      "Returning to Legacy 2D is a global action, independent of the old phase");
                SwingUtilities.invokeAndWait(() -> menus.get().setPhase(GamePhase.LOUNGE));
                find(commands.get(), Messages.getString("CommonMenuBar.viewLegacy2D")).action().run();
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

    /**
     * The View menu's 3D Graphics submenu, as the GPU Menu panel captures it: its two radio items show the preference,
     * a choice sets it, and a change made elsewhere (Client Settings, a preview's menu) moves the selection too.
     */
    @Test
    void theGraphicsChoiceIsARadioGroupOverTheSharedPreference() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        BoardGraphics saved = preferences.getBoardGraphics();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
            AtomicReference<GpuBoardActions> actions = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardGraphics(BoardGraphics.ENHANCED);
                menus.set(CommonMenuBar.getMenuBarForGame());
                ClientGUI gui = mock(ClientGUI.class);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                actions.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }));
            });
            try {
                assertEquals(List.of(false, true), graphicsSelection(actions.get()));
                BoardScene.Command classic = GpuBoardActions.menuItem(capture(actions.get()),
                      ClientGUI.VIEW_GRAPHICS_CLASSIC);
                assertEquals(Messages.getString("CommonMenuBar.viewGraphicsClassic"), classic.label());
                classic.action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertEquals(BoardGraphics.CLASSIC, preferences.getBoardGraphics());
                assertEquals(List.of(true, false), graphicsSelection(actions.get()));
                SwingUtilities.invokeAndWait(() -> preferences.setBoardGraphics(BoardGraphics.ENHANCED));
                assertEquals(List.of(false, true), graphicsSelection(actions.get()));
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    menus.get().die();
                    preferences.setBoardGraphics(saved);
                });
            }
        }
    }

    /**
     * View > Hex Types, right after Hex Coords in the game's menu as the GPU Menu panel captures it (which the Overlays
     * panel's Type row runs): the item shows the one client setting, running it switches the setting, and a change
     * made elsewhere moves the selection too.
     */
    @Test
    void theHexTypesItemFollowsHexCoordsAndSwitchesTheOneSetting() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean saved = preferences.getHexTypesEnabled();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            AtomicReference<CommonMenuBar> menus = new AtomicReference<>();
            AtomicReference<GpuBoardActions> actions = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                if (preferences.getHexTypesEnabled()) { preferences.toggleHexTypes(); }
                menus.set(CommonMenuBar.getMenuBarForGame());
                menus.get().setPhase(GamePhase.MOVEMENT);
                ClientGUI gui = mock(ClientGUI.class);
                when(gui.getMenuBar()).thenReturn(menus.get());
                BoardClientState view = spy(fixture.view);
                doReturn(gui).when(view).getClientgui();
                actions.set(new GpuBoardActions(view, () -> fixture.panel, () -> false, () -> { }));
            });
            try {
                List<BoardScene.Command> commands = capture(actions.get());
                BoardScene.Command coords = GpuBoardActions.menuItem(commands, ClientGUI.VIEW_TOGGLE_HEX_COORDS);
                BoardScene.Command types = GpuBoardActions.menuItem(commands, ClientGUI.VIEW_TOGGLE_HEX_TYPES);
                assertEquals(Messages.getString("CommonMenuBar.viewToggleHexTypes"), types.label());
                List<String> view = commands.stream().filter(menu -> menu.children().contains(coords)).findFirst()
                      .orElseThrow().children().stream().map(BoardScene.Command::id).toList();
                assertEquals(view.indexOf(coords.id()) + 1, view.indexOf(types.id()), "right after Hex Coords");
                assertEquals(Boolean.FALSE, types.selected());
                assertTrue(types.enabled());
                types.action().run();
                SwingUtilities.invokeAndWait(() -> { });
                assertTrue(preferences.getHexTypesEnabled(), "the item switches the client setting");
                assertEquals(Boolean.TRUE, hexTypes(actions.get()).selected());
                SwingUtilities.invokeAndWait(preferences::toggleHexTypes);
                assertEquals(Boolean.FALSE, hexTypes(actions.get()).selected(), "and follows it");
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    menus.get().die();
                    if (preferences.getHexTypesEnabled() != saved) { preferences.toggleHexTypes(); }
                });
            }
        }
    }

    private static BoardScene.Command hexTypes(GpuBoardActions actions) throws Exception {
        return GpuBoardActions.menuItem(capture(actions), ClientGUI.VIEW_TOGGLE_HEX_TYPES);
    }

    /** Whether the Classic and the Enhanced item are selected, in a fresh capture. */
    private static List<Boolean> graphicsSelection(GpuBoardActions actions) throws Exception {
        List<BoardScene.Command> commands = capture(actions);
        return List.of(GpuBoardActions.menuItem(commands, ClientGUI.VIEW_GRAPHICS_CLASSIC).selected(),
              GpuBoardActions.menuItem(commands, ClientGUI.VIEW_GRAPHICS_ENHANCED).selected());
    }

    /** The menu bar as the GPU Menu panel captures it, on the EDT. */
    private static List<BoardScene.Command> capture(GpuBoardActions actions) throws Exception {
        FutureTask<List<BoardScene.Command>> capture = new FutureTask<>(actions::globalCommands);
        SwingUtilities.invokeAndWait(capture);
        return capture.get();
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
