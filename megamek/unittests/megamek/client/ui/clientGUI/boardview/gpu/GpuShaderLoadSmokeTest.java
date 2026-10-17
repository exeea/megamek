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
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Player;
import megamek.common.ResolvedAttack;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.Targetable;
import megamek.common.units.UnitLocation;
import megamek.logging.MMLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No shader compile holds a frame once the loading stage has gone. A game on Grassland 3 shows its overview, a close
 * view, a pan, a volley of a laser, an autocannon and missiles and the Tactical View; then a tank and an infantry
 * platoon that were not on the board deploy, and SMAA and the thermal view are switched on and off. The 3D editor on the
 * same board opens every Assets category and reveals each of its sections, searches, and paints lava, water, ice and a
 * car. Each reports the longest frame after its loading stage and the programs that linked off the render thread
 * meanwhile, and asserts that no program compiled on it and that no loading stage came back.
 * {@code -Dmegamek.gpu.shaderTiming=true} logs every link: "after-loading" ones held a frame, "after-loading-deferred"
 * ones linked off the render thread while their draws waited.
 */
@Tag("on-demand")
class GpuShaderLoadSmokeTest {
    private static final MMLogger LOGGER = MMLogger.create(GpuShaderLoadSmokeTest.class);
    private static final double WATCH_SECONDS = 50;
    private static final File GRASSLAND = new File("data/boards/AGoAC Maps/16x17 Grassland 3.board");
    private static final File UNITS = new File("testresources/megamek/common/units");

    @Test
    void gameCompilesNothingAfterItsLoadingStage() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<List<BoardScene.Animation>> pending = new AtomicReference<>(List.of());
        Watch watch = new Watch("game");
        var fixture = GpuBoardFixture.create(BoardFile.loadSheet(GRASSLAND, false, true));
        List<Entity> late = new ArrayList<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    fixture.game.setPhase(GamePhase.FIRING);
                    Player enemy = new Player(2, "Opponent");
                    enemy.setTeam(2);
                    fixture.game.addPlayer(enemy.getId(), enemy);
                    GpuFiringCaptureTest.addTarget(fixture, 42, new Coords(11, 9)).setOwner(enemy);
                    // Not deployed yet: a vehicle and a formation, whose materials no Atlas shares.
                    int id = 60;
                    for (String file : List.of("Bulldog Medium Tank.blk", "Foot Platoon (AFFS) (Laser 3067+).blk")) {
                        Entity unit = new MekFileParser(new File(UNITS, file)).getEntity();
                        unit.setId(id++);
                        unit.setOwner(fixture.player);
                        unit.setDeployed(false);
                        fixture.game.addEntity(unit, false);
                        late.add(unit);
                    }
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
            // The spy holds a copy of the source's fields; the captures happen on the source.
            doAnswer(ignored -> fixture.source.reserveUnits()).when(source).reserveUnits();
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
                        var tuning = (GpuBoardTuning) GpuMixedUnitBenchmarkSmokeTest.field(this, "tuning");
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
                        } else if (step == 4 && seconds > 28) {
                            setTacticalView(false);
                            deploy(fixture, late.get(0), new Coords(7, 8));
                            step++;
                        } else if (step == 5 && seconds > 32) {
                            deploy(fixture, late.get(1), new Coords(9, 8));
                            step++;
                        } else if (step == 6 && seconds > 36) {
                            ((SelectBox<?>) GpuMixedUnitBenchmarkSmokeTest.field(tuning, "antiAliasing")).setSelectedIndex(1);
                            step++;
                        } else if (step == 7 && seconds > 39) {
                            tuning.setWireframe(true);
                            step++;
                        } else if (step == 8 && seconds > 43) {
                            tuning.setWireframe(false);
                            ((SelectBox<?>) GpuMixedUnitBenchmarkSmokeTest.field(tuning, "antiAliasing")).setSelectedIndex(0);
                            step++;
                        } else if (step == 9 && seconds > WATCH_SECONDS) {
                            assertTrue(fixture.source.takeFrame().scene().units().stream()
                                  .anyMatch(unit -> unit.id() == late.get(1).getId()), "The platoon deployed");
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

    /** Deploys a unit on Swing, as a deployment does: it appears on the board with the next capture. */
    private static void deploy(GpuBoardFixture fixture, Entity unit, Coords coords) {
        SwingUtilities.invokeLater(() -> {
            unit.setPosition(coords);
            unit.setDeployed(true);
            fixture.source.refresh();
        });
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
        // Every category and its sections, a search, then four strokes.
        List<Runnable> steps = new ArrayList<>();
        for (var category : BoardEditorBlueprint.get().categories()) {
            steps.add(() -> GpuBoardTestUi.category(category.id()));
            for (var section : category.sections()) {
                steps.add(() -> {
                    if (GpuBoardTestUi.stage().getRoot().findActor("editor-section-" + section.path()) != null) {
                        GpuBoardTestUi.reveal("editor-section-" + section.path());
                    }
                });
            }
        }
        for (String text : List.of("tree", "lava")) {
            steps.add(() -> ((TextField) GpuBoardTestUi.stage().getRoot().findActor("editor-search")).setText(text));
        }
        List<Coords> strokes = List.of(new Coords(3, 3), new Coords(7, 3), new Coords(11, 3), new Coords(5, 9));
        List<BoardEditorSession.Command> brushes = List.of(
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "water", "magma"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "water", "shallow"),
              new BoardEditorSession.Command(BoardEditorSession.Action.CHOOSE_BRUSH, "weather", "ice"),
              new BoardEditorSession.Command(BoardEditorSession.Action.ASSET, "", "scenery/vehicles/car"));
        for (int i = 0; i < strokes.size(); i++) {
            int stroke = i;
            steps.add(() -> {
                long generation = source.takeFrame().boardGeneration();
                source.editorCommand(brushes.get(stroke), generation);
                source.editorPointer(strokes.get(stroke), 0, 0, false, generation);
                source.endEditorStroke();
            });
        }
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                int step = -1;
                double stepAt;

                @Override
                public void render() {
                    try {
                        watch.before(GpuBoardTestUi.loading(this));
                        super.render();
                        if (!watch.frame(GpuBoardTestUi.loading(this))) { return; }
                        var root = GpuBoardTestUi.stage().getRoot();
                        double seconds = watch.seconds();
                        if (step == -1) {
                            noScarPrograms(GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain"));
                            // An editor opened before in this JVM left its page: the start page first.
                            if (root.findActor("editor-back") != null) {
                                GpuBoardTestUi.click("editor-back");
                            } else if (seconds > 2) {
                                step = 0;
                                stepAt = seconds;
                            }
                            return;
                        }
                        // The next step two seconds after the cards and the board settled, at most eight.
                        boolean settled = GpuEditorMaglevSmokeTest.ready(root.findActor("editor-library"))
                              && !((GpuTerrain) GpuMixedUnitBenchmarkSmokeTest.field(this, "terrain")).busy();
                        if (step < steps.size() && seconds - stepAt > (settled ? 2 : 8)) {
                            steps.get(step++).run();
                            stepAt = seconds;
                        } else if (step == steps.size() && settled && seconds - stepAt > 4) {
                            var scene = source.takeFrame().scene();
                            assertTrue(scene.tile(strokes.get(0)).liquid().molten(), "The lava stroke shows");
                            assertTrue(scene.tile(strokes.get(1)).liquid().present(), "The water stroke shows");
                            assertFalse(scene.tile(strokes.get(3)).features().isEmpty(), "The car stands");
                            Gdx.app.exit();
                        } else if (seconds - stepAt > 60) {
                            throw new AssertionError("The board did not settle after the last stroke");
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
     * leaves out that first one; a loading stage that comes back is counted, and its frames are left out.
     */
    private static final class Watch {
        /** Frames longer than this are logged with their start, to find what held them in the timing log. */
        private static final long LONG_FRAME = 100_000_000;
        private final String name;
        private final int compiles = GpuGlsl.compilesAfterLoading(), deferred = GpuGlsl.deferredAfterLoading();
        private LocalDateTime frameStart;
        private boolean wasLoading = true;
        private long loaded, previous, longest;
        private double longestAt;
        private int frames, loadingAgain;

        Watch(String name) { this.name = name; }

        void before(boolean loading) {
            wasLoading = loading;
            frameStart = LocalDateTime.now();
        }

        /** Records one frame; true once the loading stage has gone, also while it is back. */
        boolean frame(boolean loading) {
            long now = System.nanoTime();
            if (loading) {
                if (loaded != 0 && !wasLoading) { loadingAgain++; }
                return loaded != 0;
            }
            if (loaded == 0) {
                loaded = now;
                LOGGER.info("L1 {} loading stage gone in the frame started at {}", name, frameStart);
            } else if (!wasLoading) {
                if (now - previous > longest) {
                    longest = now - previous;
                    longestAt = seconds();
                }
                if (now - previous > LONG_FRAME) {
                    LOGGER.info(String.format(Locale.ROOT, "L1 %s long frame %.1f ms started at %s", name,
                          (now - previous) / 1e6, frameStart));
                }
            }
            previous = now;
            frames++;
            return true;
        }

        double seconds() { return (System.nanoTime() - loaded) / 1e9; }

        void check() {
            assertTrue(loaded != 0, name + ": the loading stage never went");
            int after = GpuGlsl.compilesAfterLoading() - compiles;
            String report = String.format(Locale.ROOT,
                  "L1 %s frames=%d longest=%.1f ms at %.1f s compilesAfterLoading=%d deferred=%d loadingAgain=%d",
                  name, frames, longest / 1e6, longestAt, after, GpuGlsl.deferredAfterLoading() - deferred, loadingAgain);
            LOGGER.info(report);
            System.out.println(report);
            assertEquals(0, after, name + ": shaders compiled on the render thread after the loading stage (see the timing log)");
            assertEquals(0, loadingAgain, name + ": a loading stage came back");
        }
    }
}
