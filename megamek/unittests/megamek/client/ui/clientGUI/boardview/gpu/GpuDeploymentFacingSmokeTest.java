/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import megamek.client.Client;
import megamek.client.ui.clientGUI.ClientGUI;
import megamek.client.ui.clientGUI.MegaMekGUI;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.panels.phaseDisplay.DeploymentDisplay;
import megamek.client.ui.util.MegaMekController;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import megamek.common.units.Tank;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native Shift-clicks turn surface and submerged vessels through the shared deployment display. */
@Tag("on-demand")
class GpuDeploymentFacingSmokeTest {
    @Test
    void waterDeploymentChangesGameAndRenderedFacingInBothCameras() throws Exception {
        Board board = new Board(9, 9);
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) {
                Hex hex = new Hex();
                hex.addTerrain(new Terrain(Terrains.WATER, 4));
                board.setHex(x, y, hex);
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Client client = mock(Client.class);
        AtomicReference<GpuBoardSource> source = new AtomicReference<>();
        AtomicReference<DeploymentDisplay> phase = new AtomicReference<>();
        AtomicReference<Tank> actor = new AtomicReference<>();
        List<Tank> vessels = new ArrayList<>();
        Coords position = new Coords(4, 4);
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    fixture.source.close();
                    fixture.entity.setPosition(null);
                    fixture.game.setPhase(GamePhase.DEPLOYMENT);
                    fixture.game.setTurnIndex(0, fixture.player.getId());
                    fixture.player.setStartingPos(Board.START_ANY);
                    for (int index = 0; index < 3; index++) {
                        Tank vessel = new Tank();
                        vessel.setId(index + 2);
                        vessel.setOwner(fixture.player);
                        vessel.setChassis("Deployment vessel " + index);
                        vessel.setWeight(50);
                        vessel.setMovementMode(index == 0 ? EntityMovementMode.NAVAL : EntityMovementMode.SUBMARINE);
                        vessel.setElevation(index == 2 ? -2 : 0);
                        fixture.game.addEntity(vessel, false);
                        vessels.add(vessel);
                    }
                    actor.set(vessels.getFirst());
                    actor.get().setPosition(position);
                    ClientGUI gui = mock(ClientGUI.class);
                    gui.controller = mock(MegaMekController.class);
                    when(gui.getClient()).thenReturn(client);
                    when(client.getGame()).thenReturn(fixture.game);
                    when(client.getLocalPlayer()).thenReturn(fixture.player);
                    when(client.isMyTurn()).thenReturn(true);
                    BoardView view = spy(fixture.view);
                    doReturn(gui).when(view).getClientgui();
                    doReturn(List.of(view.getClientState())).when(gui).boardStates();
                    doReturn(view.getClientState()).when(gui).getBoardState(any(Entity.class));
                    try (var keys = mockStatic(MegaMekGUI.class)) {
                        keys.when(MegaMekGUI::getKeyDispatcher).thenReturn(gui.controller);
                        phase.set(spy(new DeploymentDisplay(gui)));
                    }
                    doAnswer(ignored -> actor.get()).when(phase.get()).currentEntity();
                    view.addBoardViewListener(phase.get());
                    source.set(new GpuBoardSource(view.getClientState(), phase::get));
                });
                new Lwjgl3Application(new GpuBattleView(source.get()) {
                    // Four cases per vessel: both cameras, with Shift released before or after the mouse.
                    private int scenario;
                    private int direction;
                    private boolean clicked;

                    @Override
                    public void render() {
                        super.render();
                        try {
                            if (frames() < 3) { return; }
                            if (clicked) {
                                assertFacing(direction);
                                direction++;
                                clicked = false;
                                if (direction == 6) {
                                    scenario++;
                                    direction = 0;
                                }
                                if (scenario == 12) {
                                    Gdx.app.exit();
                                    return;
                                }
                            }
                            if (direction == 0) {
                                SwingUtilities.invokeAndWait(() -> {
                                    actor.get().setPosition(null);
                                    Tank vessel = vessels.get(scenario / 4);
                                    actor.set(vessel);
                                    vessel.setPosition(position);
                                    vessel.setFacing(3);
                                    vessel.setSecondaryFacing(3);
                                    source.get().refresh();
                                });
                                boardCamera.setIsometric(scenario % 2 == 1);
                                super.render();
                            }
                            clickHex(position.translated(direction));
                            clicked = true;
                        } catch (Throwable error) {
                            failure.set(error);
                            Gdx.app.exit();
                        }
                    }

                    private void clickHex(Coords coords) throws Exception {
                        var point = screenPosition(coords);
                        Input original = Gdx.input;
                        Input shifted = spy(original);
                        doReturn(true).when(shifted).isKeyPressed(Input.Keys.SHIFT_LEFT);
                        Gdx.input = shifted;
                        try {
                            var input = original.getInputProcessor();
                            input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                            // Both release orders belong to the same Shift-click gesture.
                            if (scenario % 4 >= 2) { doReturn(false).when(shifted).isKeyPressed(Input.Keys.SHIFT_LEFT); }
                            input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                        } finally {
                            Gdx.input = original;
                        }
                        SwingUtilities.invokeAndWait(() -> { });
                        SwingUtilities.invokeAndWait(() -> { });
                        SwingUtilities.invokeAndWait(source.get()::refresh);
                    }

                    private void assertFacing(int expected) throws Exception {
                        SwingUtilities.invokeAndWait(() -> {
                            assertEquals(expected, actor.get().getFacing(), "Scenario " + scenario);
                            assertEquals(expected, actor.get().getSecondaryFacing());
                            assertEquals(position, actor.get().getPosition());
                            assertEquals(scenario / 4 == 2 ? -2 : 0, actor.get().getElevation());
                        });
                        BoardScene.Unit captured = source.get().takeFrame().scene().units().getFirst();
                        assertEquals(expected, captured.location().facing());
                        var field = GpuBattleView.class.getDeclaredField("unitInstances");
                        field.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        var instances = (Map<String, ModelInstance>) field.get(this);
                        ModelInstance instance = instances.get(actor.get().getId() + ":-1");
                        assertNotNull(instance);
                        Vector3 forward = new Vector3(Vector3.Y).rot(instance.transform).nor();
                        double radians = Math.toRadians(expected * 60);
                        assertEquals(Math.sin(radians), forward.x, .001, "Rendered heading must match the game");
                        assertEquals(Math.cos(radians), forward.y, .001, "Rendered heading must match the game");
                    }
                }, GpuBoardWindow.configuration(false));
                if (failure.get() != null) { throw new AssertionError("Native deployment facing check failed", failure.get()); }
            } finally {
                SwingUtilities.invokeAndWait(() -> {
                    when(client.isMyTurn()).thenReturn(false);
                    if (source.get() != null) { source.get().close(); }
                    if (phase.get() != null) {
                        fixture.view.removeBoardViewListener(phase.get());
                        phase.get().removeAllListeners();
                    }
                });
            }
        }
    }
}
