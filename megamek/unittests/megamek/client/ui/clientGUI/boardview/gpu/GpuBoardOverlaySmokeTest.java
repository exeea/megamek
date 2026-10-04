/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.tileset.MekTileset;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import megamek.common.compute.Compute;
import megamek.common.compute.ComputeArc;
import megamek.common.enums.GamePhase;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * G7: the hud-v3 board overlay over the board-space harness, in 3D and in the Tactical View (its two alpha sets),
 * beside the prototype's shots 02, 03, 05, 07, 09 and 15, and its behaviour: the hover preview, non-planner movement,
 * one arc per jump, the ghost of a plotted route only, drop edges only in the Tactical View, rebuilding only when the
 * drawing changes, never for the pointer, and freeing every mesh. MegaMek's own envelope is the tactical capture's,
 * so the shots show none. The envelopes of the fixtures are drawing inputs chosen to resemble the pictures
 * (straight-line distance from the unit, MegaMek's forward arc); they are not movement rules.
 */
@Tag("on-demand")
class GpuBoardOverlaySmokeTest {
    private static final int ATLAS = GpuHudFixtures.ATLAS;
    private static final int PANTHER = 4;
    private static final int TIMBER_WOLF = 6;
    private static final int BATTLEMASTER = 8;
    /** The hex radius in world units, the overlay's unit of size. */
    private static final float RADIUS = BoardGeometry.WIDTH / 2;

    /**
     * One rendered picture: the snapshots, the view, the prototype camera's framing of the focus hex and, in 3D, the
     * instance the view draws for a unit, of which the overlay copies the moving unit's ghost (the Tactical View's
     * ghost copies the icon).
     */
    private record Shot(String name, String mock, boolean tactical, BoardScene scene, GpuBattleStatus.Snapshot status,
          GpuHudData panels, Coords focus, float hexPixels, int x, int y, int[] crop,
          Function<BoardScene.Unit, ModelInstance> shown) { }

    @Test
    void overlaysBesideTheMockInBothViews() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        BoardScene adjacent = scene.withUnits(scene.units().stream().map(unit -> unit.id() == TIMBER_WOLF
              ? moved(unit, unit(scene, ATLAS).location().coords().translated(0)) : unit).toList());
        BoardScene.UnitModel atlasModel = atlasModel();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardSpaceHarness melee = new GpuBoardSpaceHarness(adjacent);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            GpuUnitIcons icons = new GpuUnitIcons();
            GpuUnitModels models = new GpuUnitModels();
            try {
                // Shot 02's ghost is the Atlas's authored model, as GpuBattleView shows it in 3D; the harness itself
                // draws the fixture's sprites, so shot 03's ghost is the Panther's sprite, the view's fallback.
                ModelInstance atlas = authored(models, atlasModel, unit(scene, ATLAS), board);
                Function<BoardScene.Unit, ModelInstance> modelled = unit -> unit.id() == ATLAS ? atlas : null;
                Function<BoardScene.Unit, ModelInstance> sprites = board.instances::get;
                GpuHudData movement = panels(atlasMove(scene), GpuFireOrders.Snapshot.EMPTY);
                GpuHudData waypoint = panels(pantherMove(scene), GpuFireOrders.Snapshot.EMPTY);
                GpuHudData firing = panels(GpuMovePlan.Snapshot.EMPTY, fire(null));
                GpuHudData frontArc = panels(GpuMovePlan.Snapshot.EMPTY, fire(frontArc(scene)));
                GpuHudData physical = GpuHudInputTest.panels(GpuMovePlan.Snapshot.EMPTY, GpuFireOrders.Snapshot.EMPTY,
                      new GpuPhysicalOptions.Snapshot(true, ATLAS, TIMBER_WOLF, List.of(TIMBER_WOLF), List.of()),
                      GpuUnitRecord.Snapshot.EMPTY);
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                GpuBattleStatus.Snapshot pantherTurn = status(GamePhase.MOVEMENT, PANTHER);
                GpuBattleStatus.Snapshot declaring = status(GamePhase.FIRING, ATLAS);
                GpuBattleStatus.Snapshot meleeStatus = status(GamePhase.PHYSICAL, ATLAS);
                Coords atlasHex = unit(scene, ATLAS).location().coords();
                Coords pantherHex = unit(scene, PANTHER).location().coords();
                List<Shot> shots = List.of(
                      new Shot("movement-3d", "02-movement-fire-preview.jpg", false, scene, moving, movement,
                            atlasHex, 118, 960, 675, new int[] { 540, 250, 860, 640 }, modelled),
                      new Shot("movement-tactical", null, true, scene, moving, movement, atlasHex, 60.8f, 960, 600,
                            new int[] { 0, 0, 0, 0 }, null),
                      new Shot("waypoint-3d", "03-waypoint-route.jpg", false, scene, pantherTurn, waypoint,
                            pantherHex, 118, 1147, 660, new int[] { 860, 380, 660, 520 }, sprites),
                      new Shot("waypoint-tactical", null, true, scene, pantherTurn, waypoint, pantherHex, 60.8f, 960,
                            600, new int[] { 0, 0, 0, 0 }, null),
                      new Shot("firing-3d", "05-weapon-declaration.jpg", false, scene, declaring, firing, atlasHex,
                            118, 925, 790, new int[] { 330, 100, 1260, 800 }, sprites),
                      new Shot("firing-front-arc-3d", null, false, scene, declaring, frontArc, atlasHex, 118, 925,
                            790, new int[] { 0, 0, 0, 0 }, sprites),
                      new Shot("firing-tactical", "07-tactical-view.jpg", true, scene, declaring, firing, atlasHex,
                            60.8f, 868, 672, new int[] { 380, 120, 1000, 800 }, null),
                      new Shot("physical-3d", "09-physical-attacks.jpg", false, adjacent, meleeStatus, physical,
                            atlasHex, 118, 960, 505, new int[] { 700, 280, 520, 420 }, melee.instances::get),
                      new Shot("physical-tactical", null, true, adjacent, meleeStatus, physical, atlasHex, 60.8f, 960,
                            540, new int[] { 0, 0, 0, 0 }, null));
                for (Shot shot : shots) {
                    render(hud, shot.scene() == adjacent ? melee : board, overlay, icons, shot, 1920, 1080);
                }
                // Shot 15: the compact window, declaring attacks in 3D.
                Gdx.graphics.setWindowedMode(1280, 720);
                System.out.println("Compact back buffer " + Gdx.graphics.getBackBufferWidth() + " x "
                      + Gdx.graphics.getBackBufferHeight());
                if (Gdx.graphics.getBackBufferWidth() == 1280) {
                    board.camera.resize(1280, 720);
                    render(hud, board, overlay, icons, new Shot("firing-3d-1280x720", "15-compact-1280x720.jpg",
                          false, scene, declaring, firing, atlasHex, 80, 615, 520, new int[] { 250, 90, 780, 520 },
                          sprites), 1280, 720);
                }
            } finally {
                Gdx.graphics.setWindowedMode(1920, 1080);
                icons.dispose();
                overlay.dispose();
                models.dispose();
                melee.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void routeHoverAndDropEdgesFollowTheSnapshotsAndTheView() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            try {
                board.units = false;
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                Coords atlas = unit(scene, ATLAS).location().coords();
                frame(board, false, atlas, 118, 960, 600);
                GpuMovePlan.Snapshot plan = atlasMove(scene);
                Coords routeHex = plan.route().getFirst().coords();
                Pixmap planned = draw(hud, board, overlay, scene, moving, panels(plan, GpuFireOrders.Snapshot.EMPTY),
                      null, "planned");
                GpuHudData hoverPanels = panels(withRoute(plan, List.of(), plan.route(), true),
                      GpuFireOrders.Snapshot.EMPTY);
                Pixmap hovering = draw(hud, board, overlay, scene, moving, hoverPanels, null, "hover");
                Pixmap overUnit = draw(hud, board, overlay, scene, moving, hoverPanels, null, ATLAS, "hover-unit");
                Pixmap noRoute = draw(hud, board, overlay, scene, moving, panels(withRoute(plan, List.of(),
                      List.of(), true), GpuFireOrders.Snapshot.EMPTY), null, "no-route");
                Pixmap classic = draw(hud, board, overlay, scene, moving, panels(withRoute(plan, plan.route(),
                      List.of(), false), GpuFireOrders.Snapshot.EMPTY), null, "non-planner");
                Vector2 dot = board.screen(ground(scene, routeHex));
                // G3: the hover preview shows at half strength without a plotted route, never over a unit.
                float full = difference(noRoute, planned, dot);
                float half = difference(noRoute, hovering, dot);
                assertTrue(half > 5 && half < full, "The hover route is fainter: " + half + " vs " + full);
                assertTrue(difference(noRoute, overUnit, dot) < .5f, "No hover route while a unit is hovered");
                // G20: a non-planner unit gets no route line from the overlay.
                assertTrue(difference(noRoute, classic, dot) < .5f, "No route for a non-planner unit");

                // L7: elevation-drop edges appear in the Tactical View only.
                Vector3 edge = dropEdge(scene, Set.of(atlas));
                frame(board, true, atlas, 60.8f, 960, 540);
                Pixmap flatBare = draw(hud, board, overlay, null, moving, GpuHudData.EMPTY, null, "tactical-bare");
                Pixmap flat = draw(hud, board, overlay, scene, moving, GpuHudData.EMPTY, null, "tactical-drops");
                assertTrue(difference(flatBare, flat, board.screen(edge)) > 5, "A drop edge at " + edge);
                frame(board, false, atlas, 118, 960, 600);
                Pixmap solidBare = draw(hud, board, overlay, null, moving, GpuHudData.EMPTY, null, "3d-bare");
                Pixmap solid = draw(hud, board, overlay, scene, moving, GpuHudData.EMPTY, null, "3d-drops");
                assertTrue(difference(solidBare, solid, board.screen(edge)) < .5f, "No drop edge in 3D");
                for (Pixmap image : List.of(planned, hovering, overUnit, noRoute, classic, flatBare, flat, solidBare,
                      solid)) {
                    image.dispose();
                }
            } finally {
                overlay.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void aJumpIsOneArcAndOnlyAPlottedRouteLeavesAGhost() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            GpuBoardOverlay overlay = new GpuBoardOverlay();
            GpuUnitIcons icons = new GpuUnitIcons();
            List<Pixmap> images = new ArrayList<>();
            try {
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                Coords atlas = unit(scene, ATLAS).location().coords();
                frame(board, false, atlas, 118, 960, 600);
                // E2b: MegaMek's jump path lists every hex it passes, here three to the west; the prototype draws one
                // arc from the unit to the landing hex, with no step dots under it.
                board.units = false;
                Coords first = atlas.translated(5);
                Coords second = first.translated(4);
                Coords landing = second.translated(5);
                GpuMovePlan.Snapshot jump = move(ATLAS, List.of(step(scene, first, 5, GpuMovePlan.Band.JUMP),
                      step(scene, second, 5, GpuMovePlan.Band.JUMP), step(scene, landing, 5, GpuMovePlan.Band.JUMP)),
                      List.of(), List.of(), Map.of(), true);
                Pixmap still = draw(hud, board, overlay, scene, moving, GpuHudData.EMPTY, null, "jump-none");
                Pixmap jumped = draw(hud, board, overlay, scene, moving, panels(jump, GpuFireOrders.Snapshot.EMPTY),
                      null, "jump");
                images.addAll(List.of(still, jumped));
                Vector3 start = lifted(scene, atlas, .07f);
                Vector3 end = lifted(scene, landing, .07f);
                float t = 7 / 15f;
                Vector3 high = start.cpy().lerp(end, t).add(0, 0, MathUtils.sin(MathUtils.PI * t) * 1.2f * RADIUS);
                assertTrue(difference(still, jumped, board.screen(lifted(scene, first, .07f))) < .5f
                            && difference(still, jumped, board.screen(lifted(scene, second, .07f))) < .5f,
                      "No step dot under the jump's arc");
                assertTrue(difference(still, jumped, board.screen(end)) > 20, "The landing hex's dot");
                assertTrue(peak(still, jumped, board.screen(high), 6) > 40, "The arc over the jump");

                // G4: a plotted route leaves a ghost of the unit at its destination; a hover route or a non-planner
                // route does not.
                board.units = true;
                GpuMovePlan.Snapshot walk = atlasMove(scene);
                Vector3 destination = lifted(scene, walk.route().getLast().coords(), 0);
                GpuHudData plotted = panels(walk, GpuFireOrders.Snapshot.EMPTY);
                GpuHudData hover = panels(withRoute(walk, List.of(), walk.route(), true), GpuFireOrders.Snapshot.EMPTY);
                GpuHudData classic = panels(withRoute(walk, walk.route(), List.of(), false),
                      GpuFireOrders.Snapshot.EMPTY);
                Pixmap route = drawGhost(hud, board, overlay, icons, moving, plotted, false, "route");
                Pixmap ghost = drawGhost(hud, board, overlay, icons, moving, plotted, true, "ghost");
                Pixmap hoverRoute = drawGhost(hud, board, overlay, icons, moving, hover, false, "hover-route");
                Pixmap hoverGhost = drawGhost(hud, board, overlay, icons, moving, hover, true, "hover-ghost");
                Pixmap classicRoute = drawGhost(hud, board, overlay, icons, moving, classic, false, "classic-route");
                Pixmap classicGhost = drawGhost(hud, board, overlay, icons, moving, classic, true, "classic-ghost");
                frame(board, true, atlas, 60.8f, 960, 540);
                Pixmap flatRoute = drawGhost(hud, board, overlay, icons, moving, plotted, false, "tactical-route");
                Pixmap flatGhost = drawGhost(hud, board, overlay, icons, moving, plotted, true, "tactical-ghost");
                Vector2 flatDestination = board.screen(destination);
                images.addAll(List.of(route, ghost, hoverRoute, hoverGhost, classicRoute, classicGhost, flatRoute,
                      flatGhost));
                frame(board, false, atlas, 118, 960, 600);
                Vector2 solidDestination = board.screen(destination);
                assertTrue(difference(route, ghost, solidDestination, 10) > 8, "The ghost at the destination");
                assertTrue(difference(hoverRoute, hoverGhost, solidDestination, 10) < .5f, "No ghost of a hover");
                assertTrue(difference(classicRoute, classicGhost, solidDestination, 10) < .5f,
                      "No ghost for a non-planner unit");
                assertTrue(difference(flatRoute, flatGhost, flatDestination, 6) > 8,
                      "The Tactical View's ghost icon at the destination");
            } finally {
                images.forEach(Pixmap::dispose);
                icons.dispose();
                overlay.dispose();
                board.dispose();
            }
        });
    }

    @Test
    void rebuildsOnlyOnChangeAndDisposalFreesEveryMesh() throws Exception {
        BoardScene scene = GpuBoardSpaceHarness.scene();
        GpuHudTestStage.run(hud -> {
            GpuBoardSpaceHarness board = new GpuBoardSpaceHarness(scene);
            try {
                board.view(false);
                GpuBattleStatus.Snapshot moving = GpuHudFixtures.status();
                GpuHudData panels = panels(atlasMove(scene), fire(null));
                GpuHudState state = state(moving);
                GpuBoardSource.Frame frame = frame(scene, moving, panels);
                Set<Integer> before = liveBuffers();
                GpuBoardOverlay overlay = new GpuBoardOverlay();
                overlay.update(frame, view(false, null, Entity.NONE), preferences(), state);
                overlay.update(frame, view(false, null, Entity.NONE), preferences(), state);
                assertEquals(1, overlay.builds(), "The same snapshots and view state build once");
                // The source captures a new scene at every refresh: one that shows the same keeps the meshes. The
                // pointer keeps them too: the view rings the hovered hex, and the marks, drawn every frame, ring the
                // hovered unit; only whether a unit is hovered at all counts, as it hides the hover route.
                overlay.update(frame(recaptured(scene), moving, panels), view(false, null, Entity.NONE),
                      preferences(), state);
                assertEquals(1, overlay.builds(), "A recaptured equal scene keeps the meshes");
                for (int column = 0; column < 20; column++) {
                    overlay.update(frame, view(false, new Coords(column, 8), Entity.NONE), preferences(), state);
                }
                assertEquals(1, overlay.builds(), "Hovering hexes keeps the meshes");
                overlay.update(frame, view(false, null, PANTHER), preferences(), state);
                overlay.update(frame, view(false, null, TIMBER_WOLF), preferences(), state);
                assertEquals(2, overlay.builds(), "Moving from one hovered unit to another keeps the meshes");
                overlay.update(frame, view(false, null, Entity.NONE), preferences(), state);
                overlay.renderMarks(board.camera.camera, board.poses::get);
                overlay.render(board.camera.camera);
                Set<Integer> first = liveBuffers();
                first.removeAll(before);
                assertTrue(!first.isEmpty(), "The overlay's meshes are GL buffers");
                // Every change of what the meshes draw rebuilds them and frees the old ones.
                GpuBoardSource.Frame firing = frame(scene, moving, panels(GpuMovePlan.Snapshot.EMPTY, fire(null)));
                for (int change = 0; change < 20; change++) {
                    overlay.update(change % 2 == 0 ? firing : frame, view(false, null, Entity.NONE), preferences(),
                          state);
                    overlay.renderMarks(board.camera.camera, board.poses::get);
                    overlay.render(board.camera.camera);
                }
                assertEquals(23, overlay.builds());
                Set<Integer> later = liveBuffers();
                later.removeAll(before);
                System.out.println("Overlay buffers after the first build " + first.size() + ", after 20 more "
                      + later.size());
                assertEquals(first.size(), later.size(), "Rebuilding frees the old meshes");
                // The Tactical View adds the board's drop edges; disposing frees them too.
                board.view(true);
                overlay.update(frame, view(true, null, Entity.NONE), preferences(), state);
                overlay.renderMarks(board.camera.camera, board.poses::get);
                overlay.render(board.camera.camera);
                overlay.dispose();
                Set<Integer> left = liveBuffers();
                left.removeAll(before);
                assertEquals(Set.of(), left, "Disposing the overlay frees every buffer it made");
                measure(board, scene);
            } finally {
                board.dispose();
            }
        });
    }

    /**
     * Update times at the fixture's 10 units and at 100 v 100, in milliseconds: the first build (with the drop edges
     * in the Tactical View), a rebuild for a changed plan, a rebuild after a pan (the source repaints the tiles, so
     * the terrain samples start cold) and a recapture that shows the same (no rebuild); and the marks of one frame,
     * drawn where the units stand, with the GPU's work (glFinish). Not a performance claim when JaCoCo instruments the
     * run.
     */
    private static void measure(GpuBoardSpaceHarness board, BoardScene scene) {
        List<BoardScene.Unit> crowd = new ArrayList<>();
        List<GpuBattleStatus.UnitStatus> listed = new ArrayList<>();
        GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
        BoardScene.Unit template = unit(scene, ATLAS);
        GpuBattleStatus.UnitStatus own = fixture.units().getFirst();
        for (int i = 0; i < 200; i++) {
            // Distinct hexes; the Atlas keeps its fixture hex, which no other unit takes, so its route stays on board.
            Coords coords = i == 0 ? template.location().coords()
                  : new Coords(i % scene.width(), 2 * (i / scene.width()) + (i % 2));
            int id = i == 0 ? ATLAS : 100 + i;
            crowd.add(new BoardScene.Unit(id, -1, template.name(), new BoardScene.Waypoint(coords,
                  scene.tile(coords).elevation(), i % 6), template.image(), false, null, 2, false));
            listed.add(listing(own, id, i < 100 ? GpuBattleStatus.Side.OWN : GpuBattleStatus.Side.ENEMY, coords));
        }
        GpuBattleStatus.Snapshot crowded = new GpuBattleStatus.Snapshot(3, GamePhase.MOVEMENT, true,
              GpuHudFixtures.LOCAL_PLAYER, ATLAS, fixture.turns(), 0, listed, List.of(), false);
        BoardScene large = scene.withUnits(crowd);
        for (boolean tactical : new boolean[] { false, true }) {
            for (BoardScene shown : List.of(scene, large)) {
                GpuBattleStatus.Snapshot status = shown == large ? crowded : fixture;
                GpuMovePlan.Snapshot plan = atlasMove(shown);
                List<GpuHudData> plans = List.of(panels(plan, fire(null)),
                      panels(withRoute(plan, List.of(), List.of(), true), fire(null)));
                GpuBoardOverlay overlay = new GpuBoardOverlay();
                GpuHudState state = state(status);
                GpuHud.HudView view = view(tactical, null, Entity.NONE);
                long firstBuild = time(() -> overlay.update(frame(shown, status, plans.getFirst()), view,
                      preferences(), state));
                List<Long> rebuilds = new ArrayList<>();
                List<Long> recaptures = new ArrayList<>();
                List<Long> pans = new ArrayList<>();
                // The plan alternates, so each update rebuilds; the last one leaves the second plan shown.
                for (int i = 1; i <= 25; i++) {
                    GpuHudData next = plans.get(i % 2);
                    rebuilds.add(time(() -> overlay.update(frame(shown, status, next), view, preferences(),
                          state)));
                }
                GpuHudData settled = plans.get(1);
                for (int i = 0; i < 25; i++) {
                    BoardScene again = recaptured(shown);
                    recaptures.add(time(() -> overlay.update(frame(again, status, settled), view, preferences(),
                          state)));
                }
                for (int i = 0; i < 25; i++) {
                    BoardScene painted = repainted(shown);
                    pans.add(time(() -> overlay.update(frame(painted, status, settled), view, preferences(),
                          state)));
                }
                Map<BoardScene.Unit, UnitFootprint.Pose> poses = new HashMap<>();
                for (BoardScene.Unit unit : shown.units()) {
                    poses.put(unit, new UnitFootprint.Pose(unit,
                          BoardGeometry.center(unit.location().coords(), unit.location().elevation()),
                          unit.location().facing() * 60));
                }
                List<Long> marks = new ArrayList<>();
                for (int i = 0; i < 25; i++) {
                    marks.add(time(() -> {
                        overlay.renderMarks(board.camera.camera, poses::get);
                        Gdx.gl.glFinish();
                    }));
                }
                long builds = overlay.builds();
                overlay.dispose();
                System.out.printf("Overlay, %d units, %s: first build %.2f ms; rebuild median %.2f, max %.2f; after"
                            + " a pan median %.2f, max %.2f; equal recapture median %.3f ms; %d builds; marks of a"
                            + " frame median %.3f, max %.3f ms%n",
                      shown.units().size(), tactical ? "tactical" : "3D", firstBuild / 1e6, median(rebuilds),
                      max(rebuilds), median(pans), max(pans), median(recaptures), builds, median(marks), max(marks));
            }
        }
    }

    private static long time(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return System.nanoTime() - start;
    }

    /** The median of the times after five warm-up runs, in milliseconds. */
    private static double median(List<Long> times) {
        List<Long> sorted = times.subList(5, times.size()).stream().sorted().toList();
        return sorted.get(sorted.size() / 2) / 1e6;
    }

    private static double max(List<Long> times) {
        return times.subList(5, times.size()).stream().mapToLong(Long::longValue).max().orElse(0) / 1e6;
    }

    /** As the source's next capture: a new scene of new unit records with the same values. */
    private static BoardScene recaptured(BoardScene scene) {
        return scene.withUnits(scene.units().stream().map(unit -> {
            BoardScene.Waypoint at = unit.location();
            return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), new BoardScene.Waypoint(at.coords(),
                  at.elevation(), at.facing(), at.proneCause(), at.aeroState(), at.footprint(), at.form(),
                  at.fallSide(), at.hullDown()), unit.image(), unit.sensorContact(), unit.annotations(),
                  unit.height(), unit.airborne(), unit.model(), unit.outlineRgb(), unit.footprint());
        }).toList());
    }

    /** As the capture after a pan: the same hexes in a new tile list, as the source repaints them. */
    private static BoardScene repainted(BoardScene scene) {
        return new BoardScene(scene.boardId(), scene.width(), scene.height(), new ArrayList<>(scene.tiles()),
              scene.units(), scene.plannedPath(), scene.selectedId(), scene.phase(), scene.commands(), scene.light(),
              scene.firingLines(), scene.rangeBorders(), scene.markers(), scene.tactical(), scene.rangeLabels(),
              scene.fieldOfView());
    }

    /**
     * Frames the shot, draws the board with the overlay, the moving unit's ghost and, in the Tactical View, the icons,
     * in GpuBattleView's order, and compares it with the mock.
     */
    private static void render(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          GpuUnitIcons icons, Shot shot, int width, int height) {
        frame(board, shot.tactical(), shot.focus(), shot.hexPixels(), shot.x(), shot.y());
        overlay.update(frame(shot.scene(), shot.status(), shot.panels()), view(shot.tactical(), null, Entity.NONE),
              preferences(), state(shot.status()));
        Map<BoardScene.Unit, Vector3> anchors = new HashMap<>();
        board.draw(camera -> {
            if (!shot.tactical()) {
                overlay.renderGhost(camera, shot.shown());
            }
            overlay.renderMarks(camera, board.poses::get);
            overlay.render(camera);
            if (shot.tactical()) {
                icons.update(true, camera, board.scene, shot.status(),
                      unit -> unit.id() == shot.status().actorId() || unit.id() == TIMBER_WOLF
                            && shot.panels().fire().active(), unit -> false, board.poses, anchors);
                icons.render(camera);
                overlay.renderGhost(camera, icons::instance);
            }
        });
        Pixmap image = hud.captureBackBuffer("g7-" + shot.name());
        try {
            if (shot.mock() != null) {
                int[] crop = shot.crop();
                Actor area = new Actor();
                area.setBounds(crop[0], height - crop[1] - crop[3], crop[2], crop[3]);
                hud.window.addActor(area);
                hud.compare("g7-" + shot.name(), image, area, shot.mock(), crop[0], crop[1]);
                area.remove();
            }
        } finally {
            image.dispose();
        }
    }

    private static Pixmap draw(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuBattleStatus.Snapshot status, GpuHudData panels, Coords hovered, String name) {
        return draw(hud, board, overlay, scene, status, panels, hovered, Entity.NONE, name);
    }

    /**
     * Draws the board and the overlay of these snapshots (none for a null scene), its marks first where the harness
     * stands the units, and captures the back buffer.
     */
    private static Pixmap draw(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          BoardScene scene, GpuBattleStatus.Snapshot status, GpuHudData panels, Coords hovered, int hoveredUnit,
          String name) {
        overlay.update(frame(scene, status, panels), view(board.tactical(), hovered, hoveredUnit), preferences(),
              state(status));
        board.draw(camera -> {
            overlay.renderMarks(camera, board.poses::get);
            overlay.render(camera);
        });
        return hud.captureBackBuffer("g7-probe-" + name);
    }

    /**
     * Draws the board with the overlay of these snapshots as GpuBattleView orders it and, when {@code ghost}, the
     * ghost of the moving unit: of its 3D instance before the overlay, of its icon after the icons.
     */
    private static Pixmap drawGhost(GpuHudTestStage hud, GpuBoardSpaceHarness board, GpuBoardOverlay overlay,
          GpuUnitIcons icons, GpuBattleStatus.Snapshot status, GpuHudData panels, boolean ghost, String name) {
        overlay.update(frame(board.scene, status, panels), view(board.tactical(), null, Entity.NONE),
              preferences(), state(status));
        board.draw(camera -> {
            if (ghost && !board.tactical()) {
                overlay.renderGhost(camera, board.instances::get);
            }
            overlay.renderMarks(camera, board.poses::get);
            overlay.render(camera);
            if (board.tactical()) {
                icons.update(true, camera, board.scene, status, unit -> unit.id() == status.actorId(), unit -> false,
                      board.poses, new HashMap<>());
                icons.render(camera);
                if (ghost) {
                    overlay.renderGhost(camera, icons::instance);
                }
            }
        });
        return hud.captureBackBuffer("g7-probe-" + name);
    }

    /**
     * The prototype's camera: in 3D north up at 45 degrees from overhead; {@code hexPixels} per hex width; the focus
     * hex's centre at ({@code x}, {@code y}) window pixels from the top left.
     */
    static void frame(GpuBoardSpaceHarness board, boolean tactical, Coords focus, float hexPixels, float x,
          float y) {
        board.view(tactical);
        if (!tactical) {
            board.camera.orbit(-board.camera.azimuth(), 45 - board.camera.tilt());
        }
        board.camera.zoom(BoardGeometry.WIDTH / hexPixels / board.camera.camera.zoom);
        Vector3 target = ground(board.scene, focus);
        board.camera.center(target);
        for (int i = 0; i < 2; i++) {
            Vector2 at = board.screen(target);
            board.camera.pan(x - at.x, y - (Gdx.graphics.getBackBufferHeight() - at.y));
        }
    }

    static Vector3 ground(BoardScene scene, Coords coords) {
        return BoardGeometry.center(coords, scene.tile(coords).elevation());
    }

    /** A hex's centre {@code radii} hex radii above its level, where the overlay lifts its marks. */
    private static Vector3 lifted(BoardScene scene, Coords coords, float radii) {
        return ground(scene, coords).add(0, 0, radii * RADIUS);
    }

    /** The middle of a hex side whose neighbour lies lower, away from the given hexes and the fixture's units. */
    private static Vector3 dropEdge(BoardScene scene, Set<Coords> avoid) {
        Set<Coords> occupied = new HashSet<>(avoid);
        scene.units().forEach(unit -> occupied.add(unit.location().coords()));
        for (BoardScene.Tile tile : scene.tiles()) {
            Coords coords = tile.coords();
            if (coords.getX() < 6 || coords.getX() > 22 || coords.getY() < 7 || coords.getY() > 12
                  || occupied.stream().anyMatch(unit -> unit.distance(coords) < 3)) {
                continue;
            }
            for (int direction = 0; direction < 6; direction++) {
                BoardScene.Tile neighbour = scene.tile(coords.translated(direction));
                if (neighbour != null && neighbour.elevation() < tile.elevation()) {
                    int edge = Math.floorMod(1 - direction, 6);
                    return BoardGeometry.corner(coords, tile.elevation(), edge)
                          .add(BoardGeometry.corner(coords, tile.elevation(), edge + 1)).scl(.5f);
                }
            }
        }
        throw new AssertionError("The fixture board has no drop near the Atlas");
    }

    private static float difference(Pixmap before, Pixmap after, Vector2 point) {
        return difference(before, after, point, 2);
    }

    /** The mean absolute channel difference of the back-buffer texels within {@code half} of a point. */
    static float difference(Pixmap before, Pixmap after, Vector2 point, int half) {
        float total = 0;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                total += channels(before, after, Math.round(point.x) + dx, Math.round(point.y) + dy, false);
            }
        }
        return total / (3 * (2 * half + 1) * (2 * half + 1));
    }

    /** The largest channel difference of one texel within {@code half} of a point. */
    static float peak(Pixmap before, Pixmap after, Vector2 point, int half) {
        float largest = 0;
        for (int dy = -half; dy <= half; dy++) {
            for (int dx = -half; dx <= half; dx++) {
                largest = Math.max(largest, channels(before, after, Math.round(point.x) + dx,
                      Math.round(point.y) + dy, true));
            }
        }
        return largest;
    }

    /** The sum (or the largest) of one texel's absolute red, green and blue differences. */
    private static float channels(Pixmap before, Pixmap after, int x, int y, boolean largest) {
        int a = before.getPixel(x, y);
        int b = after.getPixel(x, y);
        float result = 0;
        for (int shift = 8; shift < 32; shift += 8) {
            int channel = Math.abs((a >>> shift & 0xFF) - (b >>> shift & 0xFF));
            result = largest ? Math.max(result, channel) : result + channel;
        }
        return result;
    }

    /** GL buffer names alive now; libGDX meshes are vertex and index buffers. */
    private static Set<Integer> liveBuffers() {
        int next = Gdx.gl.glGenBuffer();
        Gdx.gl.glDeleteBuffer(next);
        Set<Integer> live = new HashSet<>();
        for (int name = 1; name < next + 4096; name++) {
            if (Gdx.gl.glIsBuffer(name)) {
                live.add(name);
            }
        }
        return live;
    }

    // Snapshots.

    /** The authored 3D model MegaMek's tileset picks for the Atlas AS7-D, captured off the GL thread. */
    static BoardScene.UnitModel atlasModel() throws Exception {
        MekTileset tileset = new MekTileset(Configuration.unitImagesDir());
        tileset.loadFromFile("mekset.txt");
        Entity atlas = new MekFileParser(new File("testresources/megamek/common/units/Atlas AS7-D.mtf")).getEntity();
        atlas.setId(ATLAS);
        return UnitModelSelection.capture(atlas, -1, false, tileset);
    }

    /** The unit's authored model, placed on its hex as GpuBattleView places it. */
    static ModelInstance authored(GpuUnitModels models, BoardScene.UnitModel selection, BoardScene.Unit unit,
          GpuBoardSpaceHarness board) {
        GpuUnitModel model = models.get(selection, unit.id());
        assertNotNull(model, "The authored model of " + unit.name());
        BoardScene.Unit shown = new BoardScene.Unit(unit.id(), unit.part(), unit.name(), unit.location(), unit.image(),
              false, null, unit.height(), false, selection, 0xFFC0C0C0);
        ModelInstance instance = new ModelInstance(model.instance.model);
        model.place(instance, board.camera.camera, BoardGeometry.center(unit.location().coords(),
              unit.location().elevation()), unit.location().facing() * 60, shown);
        return instance;
    }

    /** Shot 02: the Atlas walks two hexes north; walk 3 and run 5 hexes around it, plotted automatically. */
    static GpuMovePlan.Snapshot atlasMove(BoardScene scene) {
        Coords from = unit(scene, ATLAS).location().coords();
        Coords first = from.translated(0);
        Coords second = first.translated(0);
        List<GpuMovePlan.Step> route = List.of(step(scene, first, 0, GpuMovePlan.Band.WALK),
              step(scene, second, 0, GpuMovePlan.Band.WALK));
        return move(ATLAS, route, List.of(), List.of(), envelope(scene, from, 3, 5), true);
    }

    /** Shot 03: the Panther runs north-east through a waypoint; the envelope is drawn from the waypoint. */
    static GpuMovePlan.Snapshot pantherMove(BoardScene scene) {
        Coords from = unit(scene, PANTHER).location().coords();
        Coords pin = from.translated(1);
        Coords second = pin.translated(0);
        Coords third = second.translated(1);
        Coords last = third.translated(1);
        List<GpuMovePlan.Step> route = List.of(step(scene, pin, 1, GpuMovePlan.Band.WALK),
              step(scene, second, 0, GpuMovePlan.Band.WALK), step(scene, third, 1, GpuMovePlan.Band.RUN),
              step(scene, last, 1, GpuMovePlan.Band.RUN));
        return move(PANTHER, route, List.of(), List.of(pin), envelope(scene, pin, 3, 5), true);
    }

    static GpuMovePlan.Step step(BoardScene scene, Coords coords, int facing, GpuMovePlan.Band band) {
        return new GpuMovePlan.Step(coords, scene.boardId(), scene.tile(coords).elevation(), facing, band, false);
    }

    static GpuMovePlan.Snapshot move(int unit, List<GpuMovePlan.Step> route, List<GpuMovePlan.Step> hover,
          List<Coords> pins, Map<Coords, GpuMovePlan.Band> envelope, boolean planner) {
        GpuMovePlan.Step last = route.isEmpty() ? null : route.getLast();
        return new GpuMovePlan.Snapshot(true, planner, false, unit, GpuMovePlan.Mode.AUTO, false, "", route, hover,
              pins, last == null ? null : last.coords(), last == null ? -1 : last.facing(), route.size(), 5,
              EntityMovementType.MOVE_WALK, "", true, 1, 0, true, List.of(), true, true, envelope, 0);
    }

    static GpuMovePlan.Snapshot withRoute(GpuMovePlan.Snapshot plan, List<GpuMovePlan.Step> route,
          List<GpuMovePlan.Step> hover, boolean planner) {
        return move(plan.entityId(), route, hover, List.of(), plan.envelope(), planner);
    }

    /** Hexes within {@code walk} hexes walk, within {@code run} run: a drawing input, not a movement rule. */
    static Map<Coords, GpuMovePlan.Band> envelope(BoardScene scene, Coords from, int walk, int run) {
        Map<Coords, GpuMovePlan.Band> envelope = new LinkedHashMap<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            int distance = from.distance(tile.coords());
            if (distance <= run) {
                envelope.put(tile.coords(), distance <= walk ? GpuMovePlan.Band.WALK : GpuMovePlan.Band.RUN);
            }
        }
        return envelope;
    }

    /** Shot 05: the Atlas's AC/20 queued at the Timber Wolf (A, primary) and the LRM at the BattleMaster (B). */
    static GpuFireOrders.Snapshot fire(GpuFireOrders.FrontArc frontArc) {
        List<GpuFireOrders.Target> targets = List.of(new GpuFireOrders.Target(TargetKey.unit(TIMBER_WOLF), 'A',
              "Timber Wolf", true, 0, true, null), new GpuFireOrders.Target(TargetKey.unit(BATTLEMASTER), 'B',
              "BattleMaster", false, 1, true, null));
        return new GpuFireOrders.Snapshot(true, true, ATLAS,
              new GpuFireOrders.Focus(TargetKey.unit(TIMBER_WOLF), "Timber Wolf", null), frontArc == null ? 1 : -1,
              List.of(), targets, List.of(), 0, true, true, "", null, null, frontArc, List.of(), null, Map.of(), 4, 0,
              null);
    }

    /**
     * MegaMek's forward arc of the Atlas out to 12 hexes, as ComputeArc classifies it: a stand-in for the front arc
     * that E3b's capture will supply (GpuFireOrders is still W0.2b's stub).
     */
    private static GpuFireOrders.FrontArc frontArc(BoardScene scene) {
        BoardScene.Unit atlas = unit(scene, ATLAS);
        Coords from = atlas.location().coords();
        int facing = Math.round(atlas.location().facing());
        Set<Coords> hexes = new HashSet<>();
        for (BoardScene.Tile tile : scene.tiles()) {
            int distance = from.distance(tile.coords());
            if (distance >= 1 && distance <= 12 && ComputeArc.isInArc(from, facing, tile.coords(),
                  Compute.ARC_FORWARD)) {
                hexes.add(tile.coords());
            }
        }
        return new GpuFireOrders.FrontArc(from, facing, hexes);
    }

    static GpuHudData panels(GpuMovePlan.Snapshot move, GpuFireOrders.Snapshot fire) {
        return GpuHudInputTest.panels(move, fire, GpuPhysicalOptions.Snapshot.EMPTY, GpuUnitRecord.Snapshot.EMPTY);
    }

    static GpuBattleStatus.Snapshot status(GamePhase phase, int actor) {
        GpuBattleStatus.Snapshot fixture = GpuHudFixtures.status();
        return new GpuBattleStatus.Snapshot(fixture.round(), phase, true, fixture.localPlayerId(), actor,
              fixture.turns(), fixture.turnIndex(), fixture.units(), fixture.initiative(), false);
    }

    static GpuHudState state(GpuBattleStatus.Snapshot status) {
        GpuHudState state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
        state.update(status, GpuUnitRecord.Snapshot.EMPTY, false);
        return state;
    }

    static GpuBoardSource.Frame frame(BoardScene scene, GpuBattleStatus.Snapshot status, GpuHudData panels) {
        return new GpuBoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null,
              GpuReportLog.Snapshot.EMPTY, status, panels);
    }

    static GpuHud.HudView view(boolean tactical, Coords hovered, int hoveredUnit) {
        return new GpuHud.HudView(tactical, false, Map.of(), Map.of(), Map.of(), hovered, hoveredUnit, 0);
    }

    static GpuBoardSource.UiPreferences preferences() {
        return new GpuBoardSource.UiPreferences(1, "", "", true, true, false, false, List.of(),
              GUIPreferences.getInstance().getMoveSprintColor().getRGB());
    }

    static BoardScene.Unit unit(BoardScene scene, int id) {
        return scene.units().stream().filter(unit -> unit.id() == id).findFirst().orElseThrow();
    }

    private static BoardScene.Unit moved(BoardScene.Unit unit, Coords coords) {
        return new BoardScene.Unit(unit.id(), unit.part(), unit.name(), new BoardScene.Waypoint(coords,
              unit.location().elevation(), unit.location().facing()), unit.image(), unit.sensorContact(),
              unit.annotations(), unit.height(), unit.airborne());
    }

    /** A copy of {@code template} as another unit at {@code position}. */
    static GpuBattleStatus.UnitStatus listing(GpuBattleStatus.UnitStatus template, int id,
          GpuBattleStatus.Side side, Coords position) {
        return listing(template, id, side, position, template.formation());
    }

    /** As {@link #listing(GpuBattleStatus.UnitStatus, int, GpuBattleStatus.Side, Coords)} in another formation. */
    static GpuBattleStatus.UnitStatus listing(GpuBattleStatus.UnitStatus template, int id,
          GpuBattleStatus.Side side, Coords position, String formation) {
        GpuBattleStatus.UnitStatus t = template;
        return new GpuBattleStatus.UnitStatus(id, side, false, t.name(), t.chassis(), t.model(), t.tons(),
              t.weightClass(), formation, t.pilot(), t.gunnery(), t.piloting(), t.armor(), t.structure(),
              t.heat(), t.heatRgb(), t.heatCapacity(), t.walk(), t.run(), t.jump(), t.moved(), t.mpUsed(),
              t.hexesMoved(), t.facing(), t.tmm(), id == ATLAS, true, false, false, t.damageLevel(),
              t.destroyedLocations(), t.heatEffects(), position, t.boardId(), t.icon(), t.statusWords(),
              t.weightClassIndex(), t.declaredAttacks(), t.ownerId(), t.statusTiles());
    }
}
