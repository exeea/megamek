/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import megamek.common.Hex;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Real camera and playback integration: the combat camera frames a split-fire volley between the HUD's left column
 * and its right column, the contacts or the wider open log, in oblique, threshold and top views and in a smaller
 * window, and no weapon animates before the camera is ready.
 */
@Tag("on-demand")
class GpuCombatCameraSmokeTest {
    @Test
    void splitFireStaysVisibleBetweenTheHudColumnsInObliqueAndTopViews() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Hex[] hexes = new Hex[20 * 20];
        for (int i = 0; i < hexes.length; i++) { hexes[i] = new Hex(i % 20 >= 14 ? 2 : 0); }
        try (var fixture = GpuBoardFixture.create(new Board(20, 20, hexes))) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    fixture.entity.setPosition(new Coords(2, 3));
                    Player enemy = new Player(2, "Opponent");
                    enemy.setTeam(2);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(17, 5)).setOwner(enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 43, new Coords(13, 17)).setOwner(enemy);
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var source = spy(fixture.source);
            // The volleys join the source's own events, as MegaMek's attack reports would.
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            new Lwjgl3Application(new GpuBattleView(source) {
                /**
                 * The camera moves and the shots play in wall-clock time, so the review has 30 s rather than a number
                 * of frames: an uncapped hidden window renders hundreds of frames a second.
                 */
                private final long deadline = System.nanoTime() + 30_000_000_000L;
                private int step;
                private int ticks;
                private int resizedAt;
                private List<BoardScene.Animation> shots;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        ticks++;
                        assertTrue(System.nanoTime() < deadline, "Combat camera review must finish");
                        var playback = (UnitPlayback) field(this, "playback");
                        var ui = (GpuHud) field(this, "ui");
                        if (boardCamera.isFraming()) {
                            playback.attacks().forEach(shot -> assertTrue(shot.seconds <= 0,
                                  "No weapon animation may advance before the camera is ready"));
                        }
                        if (step == 0) {
                            var scene = (BoardScene) field(this, "scene");
                            assertTrue(ui.cameraWidth() > boardCamera.camera.viewportWidth / 3,
                                  "The initial fit must use the laid-out board area, not a placeholder HUD viewport");
                            assertTrue(boardCamera.camera.zoom < 10,
                                  "Opening the window must not zoom far beyond the board");
                            var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst()
                                  .orElseThrow();
                            shots = scene.units().stream().filter(unit -> unit.id() != 1)
                                  .<BoardScene.Animation>map(target -> UnitPlaybackTest.attack(attacker, target,
                                        ResolvedAttack.Kind.SHOT, true)).toList();
                            assertEquals(2, shots.size());
                            boardCamera.setIsometric(true);
                            boardCamera.orbit(25, 25);
                            boardCamera.center(BoardGeometry.center(attacker.location().coords(),
                                  attacker.location().elevation()));
                            boardCamera.zoom(.12f);
                            ((GpuPlaybackHistory) field(this, "history")).speed(UnitMotion.Speed.QUADRUPLE);
                            pending.set(shots);
                            step++;
                        } else if (step == 1 && firing(playback)) {
                            playback.togglePaused();
                            assertCoverage(ui);
                            GpuBoardTestUi.capture(new File(output, "combat-camera-firing-oblique.png"));
                            // The log takes the right column's place, wider than the contacts.
                            GpuBoardTestUi.click("utility-log");
                            nextVolley(playback, BoardCamera.ATTACK_TOP_VIEW_TILT_DEGREES, 73);
                            step++;
                        } else if (step == 2) {
                            assertTrue(ui.state.logOpen(), "The Log utility opens the log");
                            assertEquals(BoardCamera.ATTACK_TOP_VIEW_TILT_DEGREES, boardCamera.tilt(), .001f);
                            assertEquals(73, boardCamera.azimuth(), .001f);
                            if (firing(playback)) {
                                playback.togglePaused();
                                assertCoverage(ui);
                                GpuBoardTestUi.capture(new File(output, "combat-camera-log-threshold.png"));
                                resizedAt = ticks;
                                step++;
                                // A native resize may reenter render before setWindowedMode returns.
                                Gdx.graphics.setWindowedMode(900, 680);
                            }
                        } else if (step == 3 && ticks > resizedAt + 2) {
                            assertEquals(900, Gdx.graphics.getWidth());
                            nextVolley(playback, 0, 0);
                            step++;
                        } else if (step == 4) {
                            assertEquals(0, boardCamera.tilt());
                            assertEquals(0, boardCamera.azimuth());
                            if (firing(playback)) {
                                playback.togglePaused();
                                assertCoverage(ui);
                                GpuBoardTestUi.capture(new File(output, "combat-camera-log-top-small.png"));
                                Gdx.app.exit();
                            }
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void nextVolley(UnitPlayback playback, float tilt, float orbit) {
                    playback.finish();
                    if (playback.paused()) { playback.togglePaused(); }
                    boardCamera.setIsometric(false);
                    boardCamera.orbit(orbit, tilt);
                    boardCamera.zoom(.2f);
                    pending.set(shots);
                }

                /**
                 * The HUD's camera area lies between the right edge of the left column's forces panel and the left
                 * edge of the right column's panel, the open log or the contacts, and every corner of the attacker's
                 * and both targets' models projects into it, inside the window's height.
                 */
                @SuppressWarnings("unchecked")
                private void assertCoverage(GpuHud ui) throws Exception {
                    float scale = Gdx.graphics.getWidth() / ui.stage.getWidth();
                    Actor forces = ui.stage.getRoot().findActor("forces-panel");
                    Actor column = ui.stage.getRoot().findActor(ui.state.logOpen() ? "log-panel" : "contacts-panel");
                    float left = (forces.localToStageCoordinates(new Vector2()).x + forces.getWidth()) * scale;
                    float right = column.localToStageCoordinates(new Vector2()).x * scale;
                    assertTrue(ui.cameraLeft() >= left && ui.cameraLeft() + ui.cameraWidth() <= right,
                          () -> "The camera area " + ui.cameraLeft() + " + " + ui.cameraWidth()
                                + " must lie between the columns at " + left + " and " + right);
                    var instances = (Map<String, ModelInstance>) field(this, "unitInstances");
                    for (int id : new int[] { 1, 42, 43 }) {
                        var instance = instances.entrySet().stream()
                              .filter(entry -> entry.getKey().startsWith(id + ":"))
                              .findFirst().orElseThrow().getValue();
                        var bounds = UnitBounds.world(instance);
                        for (float x : new float[] { bounds.min.x, bounds.max.x }) {
                            for (float y : new float[] { bounds.min.y, bounds.max.y }) {
                                for (float z : new float[] { bounds.min.z, bounds.max.z }) {
                                    var point = boardCamera.camera.project(new Vector3(x, y, z), 0, 0,
                                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                                    assertTrue(point.x > left && point.x < right && point.y > 0
                                          && point.y < boardCamera.camera.viewportHeight,
                                          () -> "Unit " + id + " clipped or behind a HUD column: " + point);
                                }
                            }
                        }
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError("Combat camera framing failed", failure.get()); }
    }

    private static boolean firing(UnitPlayback playback) {
        return playback.attack() != null && playback.attack().seconds > UnitAttack.ANTICIPATION_SECONDS;
    }

    private static Object field(GpuBattleView view, String name) throws ReflectiveOperationException {
        var field = GpuBattleView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }
}
