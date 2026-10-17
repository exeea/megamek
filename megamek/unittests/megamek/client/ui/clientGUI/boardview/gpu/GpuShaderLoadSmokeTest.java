/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.g3d.Renderable;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.logging.MMLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No shader compiles once the loading stage has gone. A game on Grassland 3 shows its overview, a close view, a pan, a
 * volley of a laser, an autocannon and missiles, and the Tactical View; the 3D editor on the same board shows the
 * Ground & water palette with its rendered cards. Each watches the 30 s after its loading stage and reports the
 * longest frame then. {@code -Dmegamek.gpu.shaderTiming=true} logs every link and each compile counted here; the
 * palette's samples, an offscreen renderer, are logged ("after-loading-offscreen") but not counted.
 */
@Tag("on-demand")
class GpuShaderLoadSmokeTest {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderLoadSmokeTest.class);
    private static final double WATCH_SECONDS = 30;
    private static final File GRASSLAND = new File("data/boards/AGoAC Maps/16x17 Grassland 3.board");

    @Test
    void gameCompilesNothingAfterItsLoadingStage() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Watch watch = new Watch("game");
        var fixture = GpuBoardFixture.create(BoardFile.loadSheet(GRASSLAND, false, true));
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    Player enemy = new Player(2, "Opponent");
                    enemy.setTeam(2);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(11, 9)).setOwner(enemy);
                    fixture.source.refresh();
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            });
            var source = spy(fixture.source);
            doAnswer(ignored -> {
                var frame = fixture.source.takeFrame();
                List<BoardScene.Animation> timeline = new ArrayList<>(frame.timeline());
                timeline.addAll(pending.getAndSet(List.of()));
                return frame.withTimeline(timeline);
            }).when(source).takeFrame();
            new Lwjgl3Application(new GpuBattleView(source) {
                int step;

                @Override
                public void render() {
                    try {
                        watch.before(GpuBoardTestUi.loading(this));
                        super.render();
                        if (!watch.frame(GpuBoardTestUi.loading(this))) { return; }
                        double seconds = watch.seconds();
                        BoardScene scene = step < 4 ? fixture.source.takeFrame().scene() : null;
                        if (step == 0) {
                            boardCamera.setIsometric(true);
                            boardCamera.fit(scene);
                            step++;
                        } else if (step == 1 && seconds > 5) {
                            boardCamera.camera.zoom = .5f;
                            boardCamera.center(BoardGeometry.center(new Coords(scene.width() / 2, scene.height() / 2), 0));
                            step++;
                        } else if (step == 2 && seconds > 10) {
                            boardCamera.pan(BoardGeometry.WIDTH * .01f, 0);
                            if (seconds > 15) {
                                boardCamera.fit(scene);
                                var attacker = scene.units().stream().filter(unit -> unit.id() == 1).findFirst().orElseThrow();
                                var target = scene.units().stream().filter(unit -> unit.id() == 42).findFirst().orElseThrow();
                                pending.set(List.of(shot(attacker, target, "ISMediumLaser"), shot(attacker, target, "ISAC20"),
                                      shot(attacker, target, "ISLRM20")));
                                step++;
                            }
                        } else if (step == 3 && seconds > 25) {
                            setTacticalView(true);
                            step++;
                        } else if (seconds > WATCH_SECONDS) {
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, window());
        } finally {
            // The view closed the spied source, which shares the fixture's state.
            SwingUtilities.invokeAndWait(fixture.view::close);
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        watch.check();
    }

    @Test
    void editorCompilesNothingAfterItsLoadingStage() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Watch watch = new Watch("editor");
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(BoardFile.loadSheet(GRASSLAND, false, true));
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                int step;

                @Override
                public void render() {
                    try {
                        watch.before(GpuBoardTestUi.loading(this));
                        super.render();
                        if (!watch.frame(GpuBoardTestUi.loading(this))) { return; }
                        var root = GpuBoardTestUi.stage().getRoot();
                        if (step == 0) { noScarPrograms(GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain")); }
                        // An editor opened before in this JVM left its page: the start page first.
                        if (step == 0 && root.findActor("editor-back") != null) {
                            GpuBoardTestUi.click("editor-back");
                        } else if (step == 0 && watch.seconds() > 2) {
                            GpuBoardTestUi.click("editor-category-ground-water");
                            step++;
                        } else if (step == 1 && watch.seconds() > 4) {
                            GpuBoardTestUi.reveal("editor-section-ground-water/liquids");
                            step++;
                        } else if (watch.seconds() > WATCH_SECONDS) {
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }
            }, window());
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        watch.check();
    }

    /** Where combat cannot happen, no scar program is prepared. */
    private static void noScarPrograms(Object terrain) throws Exception {
        for (Object sample : (List<?>) GpuMixedUnitBenchmarkSmokeTest.field(terrain, "terrainMaterialSamples")) {
            assertFalse(((Renderable) sample).material.has(GpuSurfaceScars.Marking.TYPE), "An editor scar program");
        }
    }

    private static Lwjgl3ApplicationConfiguration window() {
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        return config;
    }

    private static BoardScene.Combat shot(BoardScene.Unit attacker, BoardScene.Unit target, String weapon) {
        var result = new ResolvedAttack(UUID.randomUUID(), ResolvedAttack.Kind.SHOT,
              new UnitLocation(attacker.id(), attacker.location().coords(), 0, 0, 0),
              new UnitLocation(target.id(), target.location().coords(), 0, 0, 0), Targetable.TYPE_ENTITY, 0, weapon, 4, true);
        return new BoardScene.Combat(result, attacker, target, target.location());
    }

    /**
     * The frames from the one that dismissed the first loading stage, which already draws the board. The longest frame
     * leaves out that first one and any frame that ends another loading stage.
     */
    private static final class Watch {
        private final String name;
        private final int compiles = GpuGlsl.compilesAfterLoading();
        private LocalDateTime frameStart;
        private boolean wasLoading = true;
        private long loaded, previous, longest;
        private double longestAt;
        private int frames;

        Watch(String name) { this.name = name; }

        void before(boolean loading) {
            wasLoading = loading;
            frameStart = LocalDateTime.now();
        }

        /** Records one frame; true once the loading stage has gone. */
        boolean frame(boolean loading) {
            long now = System.nanoTime();
            if (loading) { return false; }
            if (loaded == 0) {
                loaded = now;
                LOGGER.info("L1 {} loading stage gone in the frame started at {}", name, frameStart);
            } else if (!wasLoading && now - previous > longest) {
                longest = now - previous;
                longestAt = seconds();
            }
            previous = now;
            frames++;
            return true;
        }

        double seconds() { return (System.nanoTime() - loaded) / 1e9; }

        void check() {
            assertTrue(loaded != 0, name + ": the loading stage never went");
            int after = GpuGlsl.compilesAfterLoading() - compiles;
            String report = String.format(Locale.ROOT, "L1 %s frames=%d longest=%.1f ms at %.1f s compilesAfterLoading=%d",
                  name, frames, longest / 1e6, longestAt, after);
            LOGGER.info(report);
            System.out.println(report);
            assertEquals(0, after, name + ": shaders compiled after the loading stage (see the timing log)");
        }
    }
}
