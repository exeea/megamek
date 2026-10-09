/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuMaglevRouteSmokeTest {
    @Test void elevationProfilesRenderContinuouslyAtCloseRange() {
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1600, 900); config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true); camera.orbit(90 - camera.azimuth(), 70 - camera.tilt());
                    camera.zoom(.32f / camera.camera.zoom);
                    double[][] profiles = { { 1, 2, 3, 4, 5, 6, 7 }, { 1, 1, 1, 3, 5, 5, 5 }, { 1, 2, 4, 5, 4, 2, 1 } };
                    String[] names = { "steady", "transition", "crest" };
                    for (int profile = 0; profile < profiles.length; profile++) {
                        var heights = new HashMap<Coords, Double>();
                        for (int i = 0; i < profiles[profile].length; i++) { heights.put(new Coords(4, i + 1), profiles[profile][i]); }
                        var scene = BoardMaglevTest.scene(heights);
                        camera.center(BoardGeometry.center(new Coords(4, 4), (float) profiles[profile][3]));
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene); terrain.animate(0, List.of());
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "maglev-elevation-" + names[profile] + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); terrain.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Maglev elevation profiles", failure.get()); }
    }

    private static void level(Board board, Coords at, double level) {
        var route = MaglevRoute.find(board.getHex(at));
        board.getHex(at).setDecorations(List.of(route.transform(0, 0, 0, false, 1,
              megamek.common.board.BoardDecoration.Placement.absolute(level))));
    }

    @Test void paintsRendersAndEditsCurvedElevatedRoutesUsingSharedModels() throws Exception {
        var editor = new AtomicReference<BoardEditorSession>();
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession(); editor.set(session);
            var board = Board.createEmptyBoard(8, 7);
            for (int x = 0; x < 8; x++) for (int y = 0; y < 7; y++) {
                board.getHex(x, y).setTheme("lunar");
                board.getHex(x, y).setLevel(x >= 5 ? 1 : 0);
            }
            session.game().setBoard(board);
            session.command(new Command(Action.ASSET, "", MaglevRoute.ASSET), null);
            // One winding stroke joins its hexes; each then climbs a quarter level.
            var path = new java.util.ArrayList<Coords>(List.of(new Coords(1, 1)));
            for (int direction : new int[] { 3, 3, 2, 2, 1, 1, 0, 0 }) { path.add(path.getLast().translated(direction)); }
            session.command(new Command(Action.BRUSH_VALUE, "object:level", "1"), null);
            for (int i = 0; i < path.size(); i++) { session.pointer(path.get(i), 0, 0, i > 0); }
            session.finishStroke();
            // A second stroke from the route's (3, 4) adds a branch to the south.
            session.command(new Command(Action.BRUSH_VALUE, "object:level", "2"), null);
            for (var at : List.of(new Coords(3, 4), new Coords(3, 5), new Coords(3, 6))) { session.pointer(at, 0, 0, !at.equals(new Coords(3, 4))); }
            session.finishStroke();
            for (int i = 0; i < path.size(); i++) { level(board, path.get(i), 1 + i * .25); }
            level(board, new Coords(3, 5), 1.5); level(board, new Coords(3, 6), 1.5);
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            session.pointer(new Coords(3, 4), 0, 0, false,
                  MaglevRoute.find(board.getHex(3, 4)).id()); session.finishStroke();
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        var source = setup.get();
        var failure = new AtomicReference<Throwable>();
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step;
                int sides;
                long after;
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Maglev route stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                        var terrain = (GpuTerrain) field.get(this);
                        if (terrain.busy()) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        switch (step) {
                            case 0 -> {
                                var assets = new GpuAssets();
                                try {
                                    var shape = new BoardMaglev.Layout(List.of(new BoardMaglev.End(0, -36, 0, 0, 0), new BoardMaglev.End(0, 36, 0, 0, 0)));
                                    var model = assets.maglev(shape);
                                    assertSame(model, assets.maglev(shape));
                                    assets.retain(assets, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(model));
                                    assertSame(model, assets.maglev(shape));
                                    assets.retain(assets, java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
                                    assertNotSame(model, assets.maglev(shape));
                                } finally { assets.dispose(); }
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-isometric.png"));
                                boardCamera.setIsometric(false);
                            }
                            case 1 -> {
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-top.png"));
                                sides = MaglevRoute.find(editor.get().board().getHex(3, 4)).connections();
                                assertNotEquals(0, sides, "The junction is joined");
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.OBJECT_VALUE, "level", "3"), null));
                                boardCamera.setIsometric(true);
                            }
                            case 2 -> {
                                assertEquals(3, MaglevRoute.find(editor.get().board().getHex(3, 4)).placement().level());
                                assertEquals(sides, MaglevRoute.find(editor.get().board().getHex(3, 4)).connections(), "A level edit keeps the sides");
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-height-edit.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.UNDO, "", ""), null));
                            }
                            case 3 -> {
                                assertEquals(2, MaglevRoute.find(editor.get().board().getHex(3, 4)).placement().level());
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 12;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Maglev routes", failure.get()); }
    }
}
