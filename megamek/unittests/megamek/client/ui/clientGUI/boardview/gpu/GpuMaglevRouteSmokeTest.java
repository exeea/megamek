/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("on-demand")
class GpuMaglevRouteSmokeTest {
    private static void level(Board board, Coords at, int level) {
        var hex = board.getHex(at);
        hex.addTerrain(new megamek.common.units.Terrain(megamek.common.units.Terrains.BRIDGE_ELEV, level - hex.getLevel()));
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
            session.command(new Command(Action.ASSET, "", RailRoute.Kind.MAGLEV.asset), null);
            // One winding stroke joins its hexes; groups of four hexes climb one rules level.
            var path = new java.util.ArrayList<Coords>(List.of(new Coords(1, 1)));
            for (int direction : new int[] { 3, 3, 2, 2, 1, 1, 0, 0 }) { path.add(path.getLast().translated(direction)); }
            session.command(new Command(Action.BRUSH_VALUE, "wayOffset", "1"), null);
            for (int i = 0; i < path.size(); i++) { session.pointer(path.get(i), 0, 0, i > 0); }
            session.finishStroke();
            // A second stroke from the route's (3, 4) adds a branch on the same deck layer.
            for (var at : List.of(new Coords(3, 4), new Coords(3, 5), new Coords(3, 6))) { session.pointer(at, 0, 0, !at.equals(new Coords(3, 4))); }
            session.finishStroke();
            for (int i = 0; i < path.size(); i++) { level(board, path.get(i), 1 + i / 4); }
            level(board, new Coords(3, 5), 2); level(board, new Coords(3, 6), 2);
            session.command(new Command(Action.TOOL, "", "SELECT"), null);
            session.pointer(new Coords(3, 4), 0, 0, false,
                  RailRoute.find(board.getHex(3, 4), RailRoute.Kind.MAGLEV).id()); session.finishStroke();
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
                                    var shape = new BoardRail.Layout(List.of(new BoardRail.End(0, -36, 0, 0, 0), new BoardRail.End(0, 36, 0, 0, 0)));
                                    var model = assets.route(shape);
                                    assertSame(model, assets.route(shape));
                                    assets.retain(assets, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(model));
                                    assertSame(model, assets.route(shape));
                                    assets.retain(assets, java.util.Set.of(), java.util.Set.of(), java.util.Set.of());
                                    assertNotSame(model, assets.route(shape));
                                } finally { assets.dispose(); }
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-isometric.png"));
                                boardCamera.setIsometric(false);
                            }
                            case 1 -> {
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-top.png"));
                                sides = RailRoute.find(editor.get().board().getHex(3, 4), RailRoute.Kind.MAGLEV).connections();
                                assertNotEquals(0, sides, "The junction is joined");
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.HEIGHT, "bridge", "3"), null));
                                boardCamera.setIsometric(true);
                            }
                            case 2 -> {
                                assertEquals(3, megamek.common.board.BridgeSpan.deck(editor.get().board().getHex(3, 4)));
                                assertEquals(sides, RailRoute.find(editor.get().board().getHex(3, 4), RailRoute.Kind.MAGLEV).connections(), "A level edit keeps the sides");
                                GpuBoardTestUi.capture(new File(output, "maglev-routes-height-edit.png"));
                                SwingUtilities.invokeAndWait(() -> editor.get().command(new Command(Action.UNDO, "", ""), null));
                            }
                            case 3 -> {
                                assertEquals(2, megamek.common.board.BridgeSpan.deck(editor.get().board().getHex(3, 4)));
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
