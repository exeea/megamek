/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Routes drawn by hand on the real board, through the input processor as a user drives it (the user's design of
 * 2026-10-11): a long rail with a sharp turn shows its whole ghost and the turn in red while the document is untouched;
 * stepping back over the turn and on gently clears the red, and the release builds it; a rail loop crosses itself in
 * one stroke; an S-shaped road; a route joining two existing tracks; Esc and a right click drop a drag; a drag held near
 * the board area's edge scrolls the board and the route follows, and over the panel past that edge the board stays; a
 * key during a drag builds nothing, and losing the focus drops the drag; the route shows while the pointer is past the
 * board's edge and once the drag comes back. As LWJGL does, the drags send no mouse-moves. Captures:
 * {@code route-drag/*.png}.
 */
@Tag("on-demand")
class GpuEditorRouteDrawSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "route-drag");
    private static final List<Coords> WEST = line(new Coords(4, 15), 3, 3), EAST = line(new Coords(10, 15), 3, 3);
    /** A drag pressed Ctrl+Z during, and a drag the window loses the focus during. */
    private static final List<Coords> UNDO = line(new Coords(2, 21), 2, 1, 2), PAUSED = line(new Coords(6, 21), 2, 1, 2);

    static List<Coords> line(Coords start, int... directions) {
        List<Coords> result = new ArrayList<>(List.of(start));
        for (int direction : directions) { result.add(result.getLast().translated(direction)); }
        return result;
    }

    @Test void routesAreDrawnByHandAndBuiltOnRelease() throws Exception {
        var session = new BoardEditorSession();
        var setup = new FutureTask<GpuMapSource>(() -> {
            Board board = Board.createEmptyBoard(30, 24);
            for (int x = 0; x < 30; x++) {
                for (int y = 0; y < 24; y++) { board.getHex(x, y).setTheme("grass"); }
            }
            session.game().setBoard(board);
            session.command(new Command(Action.ASSET, "", RailRoute.Kind.RAIL.asset), null);
            for (List<Coords> track : List.of(WEST, EAST)) {
                for (int i = 0; i < track.size(); i++) { session.pointer(track.get(i), 0, 0, i > 0); }
                session.finishStroke();
            }
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowSizeLimits(1, 1, -1, -1);
        config.setWindowedMode(1600, 1000);
        // What the drags draw: a long rail with a sharp turn (south five, then north-east), the hexes it takes back and
        // the gentle way on; a loop of gentle turns through its own third hex; an S-shaped road.
        List<Coords> sharp = line(new Coords(5, 2), 3, 3, 3, 3, 3, 1, 1, 1, 1);
        Coords turn = sharp.get(5);
        List<Coords> gentle = line(turn, 3, 2, 2);
        List<Coords> loop = line(new Coords(13, 2), 3, 3, 3, 2, 1, 0, 5, 4, 4);
        List<Coords> road = line(new Coords(20, 2), 3, 2, 2, 3, 4, 4, 3);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int stage;
                long after;
                AtomicBoolean applied = new AtomicBoolean(true);
                boolean started;
                /** The drag's pointer (screen), hovered after each camera change as a real pointer would be; null when none. */
                Vector3 pointer;
                long cameraSeen = -1;
                List<BoardScene.Tile> tiles;
                Vector3 mark;
                float panned;
                Coords edgeHex, back, dropped;
                List<Coords> kept;

                private Object field(Object owner, String name) throws Exception {
                    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                        try {
                            var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
                        } catch (NoSuchFieldException next) { /* superclass */ }
                    }
                    throw new NoSuchFieldException(name);
                }
                private GpuTerrain terrain() throws Exception { return (GpuTerrain) field(this, "terrain"); }
                private GpuBoardHud hud() throws Exception { return (GpuBoardHud) field(this, "ui"); }
                private boolean built(GpuEditorPreview.Data preview, Coords at) throws Exception {
                    return at.equals(preview.at()) && field(field(this, "editorPreview"), "built") == preview;
                }
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                private void next() {
                    stage++; after = frames() + 8; applied = new AtomicBoolean();
                    var marker = applied; SwingUtilities.invokeLater(() -> marker.set(true));
                }
                private Vector3 screen(Vector3 world) {
                    Vector3 result = boardCamera.camera.project(new Vector3(world));
                    result.y = Gdx.graphics.getHeight() - result.y;
                    return result;
                }
                private Vector3 over(Coords coords) { return screen(BoardGeometry.center(coords, 0)); }
                private void look(Coords centre, float zoom) {
                    boardCamera.center(BoardGeometry.center(centre, 0));
                    boardCamera.zoom(zoom / boardCamera.camera.zoom);
                }
                private void press(Vector3 point) {
                    pointer = point;
                    Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Math.round(point.y));
                    GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x),
                          Math.round(point.y), 0, Input.Buttons.LEFT));
                }
                /** A drag event, and no mouse-move: LWJGL sends none while a button is down. */
                private void drag(Vector3 point) {
                    pointer = point;
                    Gdx.input.getInputProcessor().touchDragged(Math.round(point.x), Math.round(point.y), 0);
                }
                /** A drag through each hex's centre in turn. */
                private void drag(List<Coords> cells) { cells.forEach(cell -> drag(over(cell))); }
                private void release() {
                    Gdx.input.getInputProcessor().touchUp(Math.round(pointer.x), Math.round(pointer.y), 0, Input.Buttons.LEFT);
                    pointer = null;
                }
                private void remember() { tiles = source.takeFrame().scene().tiles(); }
                private void assertUntouched() {
                    assertSame(tiles, source.takeFrame().scene().tiles(), "The drag changed no hex before its release");
                }
                private BoardScene.Feature route(Coords at) {
                    var tile = source.takeFrame().scene().tile(at);
                    return tile == null ? null : BoardRail.route(tile);
                }
                private void capture(String name) { GpuBoardTestUi.capture(new File(OUTPUT, name + ".png")); }
                /** The end of a drawn route's label: its length. */
                private String length(int hexes) { return megamek.client.ui.Messages.getString("BoardEditor.previewPath", "", hexes); }
                /** The hex under a screen point, as a press there picks it. */
                private Coords under(Vector3 point) throws Exception {
                    var hit = terrain().selectionHit(source.takeFrame().scene(), boardCamera.camera.getPickRay(point.x, point.y, 0, 0,
                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight));
                    return hit == null ? null : hit.coords();
                }

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        if (System.nanoTime() > deadline) {
                            capture("stalled");
                            fail("Route drawing smoke stalled at stage " + stage + ": " + source.editorPreview().hexes() + " stubs "
                                  + source.editorPreview().stubs() + ", hovered " + field(this, "hovered") + ", pointer " + pointer + " over the HUD "
                                  + (pointer != null && hud().hit(Math.round(pointer.x), Math.round(pointer.y))) + ", state "
                                  + source.editorState().tool() + " " + source.editorState().drawsRoutes() + " " + source.editorState().message());
                        }
                        if (!applied.get() || GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        // A camera change re-picks the hover at the real cursor, which a test does not move: here, at the
                        // test's pointer, and as in the view only after a camera change.
                        if (pointer != null && cameraSeen != boardCamera.revision()) {
                            cameraSeen = boardCamera.revision();
                            Gdx.input.getInputProcessor().mouseMoved(Math.round(pointer.x), Math.round(pointer.y));
                        }
                        if (!started) { started = true; stage = -1; next(); return; }
                        step(source.editorPreview());
                    } catch (Throwable error) {
                        failure.set(error); Gdx.app.exit();
                    }
                }

                private void step(GpuEditorPreview.Data preview) throws Exception {
                    switch (stage) {
                        // A long rail with a sharp turn, mid-drag: the whole ghost, the turn red, nothing built.
                        case 0 -> { look(sharp.get(4), .7f); next(); }
                        case 1 -> { remember(); press(over(sharp.getFirst())); drag(sharp); next(); }
                        case 2 -> {
                            if (!built(preview, sharp.getLast()) || !preview.hexes().containsAll(sharp)) { return; }
                            assertEquals(List.of(turn), preview.stubs(), "Only the sharp turn is red");
                            assertTrue(preview.label().endsWith(length(sharp.size())), preview.label());
                            assertUntouched();
                            capture("route-draw-sharp-turn");
                            // Back over the turn, hex by hex, then on gently.
                            drag(sharp.subList(5, 9).reversed());
                            drag(gentle.subList(1, gentle.size()));
                            next();
                        }
                        case 3 -> {
                            if (!built(preview, gentle.getLast())) { return; }
                            assertEquals(List.of(), preview.stubs(), "The fix leaves no sharp turn");
                            assertTrue(preview.hexes().stream().noneMatch(sharp.subList(6, 10)::contains), "The retraced hexes left the path");
                            assertUntouched();
                            capture("route-draw-retraced");
                            release();
                            next();
                        }
                        case 4 -> {
                            for (Coords at : sharp.subList(0, 6)) { assertNotNull(route(at), at + " is built"); }
                            for (Coords at : gentle) { assertNotNull(route(at), at + " is built"); }
                            for (Coords at : sharp.subList(6, 10)) { assertNull(route(at), at + " was taken back"); }
                            capture("route-draw-released");
                            // A loop that crosses itself in one stroke.
                            look(loop.get(3), .7f);
                            next();
                        }
                        case 5 -> { remember(); press(over(loop.getFirst())); drag(loop); next(); }
                        case 6 -> {
                            if (!built(preview, loop.getLast())) { return; }
                            assertTrue(preview.label().endsWith(length(loop.size())), "Its length counts the crossing twice: " + preview.label());
                            assertEquals(List.of(), preview.stubs(), "Every turn of the loop is gentle");
                            assertUntouched();
                            capture("route-draw-loop");
                            release();
                            next();
                        }
                        case 7 -> {
                            var crossing = route(loop.get(2));
                            assertNotNull(crossing);
                            assertEquals(RailRoute.side(0) | RailRoute.side(1) | RailRoute.side(3) | RailRoute.side(4),
                                  crossing.decoration().connections(), "The loop crosses its own track");
                            capture("route-draw-loop-released");
                            // An S-shaped road.
                            command(Action.CHOOSE_BRUSH, "road", "1");
                            look(road.get(3), .7f);
                            next();
                        }
                        case 8 -> { remember(); press(over(road.getFirst())); drag(road); next(); }
                        case 9 -> {
                            if (!built(preview, road.getLast()) || preview.standIns().size() < road.size()) { return; }
                            assertEquals(road, preview.hexes());
                            assertUntouched();
                            capture("route-draw-road");
                            release();
                            next();
                        }
                        case 10 -> {
                            capture("route-draw-road-released");
                            // A rail joining two existing tracks' ends, drawn in two fast moves.
                            command(Action.ASSET, "", RailRoute.Kind.RAIL.asset);
                            look(new Coords(7, 16), .7f);
                            next();
                        }
                        case 11 -> {
                            remember();
                            press(over(WEST.getLast()));
                            drag(over(new Coords(7, 18)));
                            drag(over(EAST.getLast()));
                            next();
                        }
                        case 12 -> {
                            if (!built(preview, EAST.getLast())) { return; }
                            assertUntouched();
                            capture("route-draw-join");
                            release();
                            next();
                        }
                        case 13 -> {
                            assertNotNull(route(new Coords(7, 18)));
                            capture("route-draw-join-released");
                            // Esc drops a drag.
                            look(new Coords(20, 16), .7f);
                            next();
                        }
                        case 14 -> {
                            remember();
                            press(over(new Coords(18, 14)));
                            drag(line(new Coords(18, 14), 3, 3, 2));
                            next();
                        }
                        case 15 -> {
                            if (preview.hexes().size() < 4) { return; }
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
                            release();
                            next();
                        }
                        case 16 -> {
                            assertUntouched();
                            assertNull(route(new Coords(18, 14)), "Esc dropped the drag");
                            // A right click drops one too.
                            press(over(new Coords(22, 14)));
                            drag(line(new Coords(22, 14), 3, 3, 4));
                            next();
                        }
                        case 17 -> {
                            if (preview.hexes().size() < 4) { return; }
                            var at = over(new Coords(22, 16));
                            Gdx.input.getInputProcessor().touchDown(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.RIGHT);
                            Gdx.input.getInputProcessor().touchUp(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.RIGHT);
                            release();
                            next();
                        }
                        case 18 -> {
                            assertUntouched();
                            assertNull(route(new Coords(22, 14)), "A right click dropped the drag");
                            // A drag held near the board area's right edge scrolls the board, and the route follows.
                            look(new Coords(14, 20), .7f);
                            next();
                        }
                        case 19 -> {
                            remember();
                            press(over(new Coords(14, 20)));
                            drag(over(new Coords(15, 20)));
                            // Inside the board area, away from its edges: no scroll.
                            mark = over(new Coords(14, 20));
                            next();
                        }
                        case 20 -> {
                            assertEquals(mark.x, over(new Coords(14, 20)).x, .5f, "Away from the edge the board stays");
                            var hud = hud();
                            var edge = new Vector3(hud.framingLeft() + hud.framingWidth() - 12, pointer.y, 0);
                            edgeHex = under(edge);
                            assertNotNull(edgeHex);
                            drag(edge);
                            mark = over(new Coords(14, 20));
                            after = frames() + 45;
                            stage++;
                        }
                        case 21 -> {
                            panned = mark.x - over(new Coords(14, 20)).x;
                            assertTrue(panned > 50, "Held at the edge, the board scrolled left by " + panned);
                            assertUntouched();
                            capture("route-draw-autopan");
                            // On over the panel past the board area's edge, where the drag sends no events: the board stays.
                            var hud = hud();
                            var panel = new Vector3(hud.framingLeft() + hud.framingWidth() + 24, pointer.y, 0);
                            assertTrue(hud.hit(Math.round(panel.x), Math.round(panel.y)), "A panel lies past the right edge");
                            drag(panel);
                            mark = over(new Coords(14, 20));
                            after = frames() + 30;
                            stage++;
                        }
                        case 22 -> {
                            assertEquals(mark.x, over(new Coords(14, 20)).x, .5f, "Over the panel the board stays");
                            assertUntouched();
                            release();
                            mark = over(new Coords(14, 20));
                            next();
                        }
                        case 23 -> {
                            assertEquals(mark.x, over(new Coords(14, 20)).x, .5f, "Released, the board stays");
                            var followed = source.takeFrame().scene().tiles().stream().filter(tile -> tile.coords().getX() >= 14
                                  && tile.coords().getY() >= 10 && BoardRail.route(tile) != null).toList();
                            assertTrue(followed.stream().anyMatch(tile -> tile.coords().getX() > edgeHex.getX()),
                                  "The route followed the scrolled board beyond " + edgeHex);
                            for (var tile : followed) {
                                assertEquals(0, RailRoute.stranded(BoardRail.route(tile).decoration().connections()),
                                      tile.coords() + ": the scrolled route grazed no corner into a sharp turn");
                            }
                            capture("route-draw-autopan-released");
                            // A key during the drag (Ctrl+Z here) neither builds nor undoes anything.
                            look(new Coords(5, 21), .7f);
                            next();
                        }
                        case 24 -> { remember(); press(over(UNDO.getFirst())); drag(UNDO); next(); }
                        case 25 -> {
                            if (!preview.hexes().containsAll(UNDO)) { return; }
                            GpuBoardTestUi.withModifiers(InputEvent.CTRL_DOWN_MASK, () -> {
                                Gdx.input.getInputProcessor().keyDown(Input.Keys.Z);
                                Gdx.input.getInputProcessor().keyUp(Input.Keys.Z);
                            });
                            next();
                        }
                        case 26 -> {
                            assertUntouched();
                            assertTrue(preview.hexes().containsAll(UNDO), "The drag goes on: " + preview.hexes());
                            assertFalse(source.editorState().canRedo(), "Nothing was undone");
                            release();
                            next();
                        }
                        case 27 -> {
                            for (Coords at : UNDO) { assertNotNull(route(at), at + " is built on the release"); }
                            // A window losing the focus mid-drag drops the drag: no later command builds it.
                            remember();
                            press(over(PAUSED.getFirst()));
                            drag(PAUSED);
                            next();
                        }
                        case 28 -> {
                            if (!preview.hexes().containsAll(PAUSED)) { return; }
                            pause();
                            next();
                        }
                        case 29 -> {
                            assertUntouched();
                            assertFalse(preview.hexes().containsAll(PAUSED), "Nothing is drawn any more: " + preview.hexes());
                            release();
                            command(Action.ASSET, "", RailRoute.Kind.RAIL.asset);
                            next();
                        }
                        case 30 -> {
                            assertUntouched();
                            for (Coords at : PAUSED) { assertNull(route(at), at + " was dropped with the focus"); }
                            // Past the board's edge, where the pointer hovers no hex, the drawn route keeps showing.
                            look(new Coords(17, 12), .7f);
                            next();
                        }
                        case 31 -> {
                            remember();
                            press(over(new Coords(16, 12)));
                            drag(over(new Coords(17, 12)));
                            var hud = hud();
                            var edge = new Vector3(hud.framingLeft() + hud.framingWidth() - 12, pointer.y, 0);
                            assertNotNull(under(edge), "The edge band starts over the board");
                            drag(edge);
                            mark = null;
                            after = frames() + 10;
                            stage++;
                        }
                        case 32 -> {
                            // Until the board stops at its scroll limit.
                            var at = over(new Coords(17, 12));
                            if (mark == null || mark.dst(at) > .5f) { mark = at; after = frames() + 5; return; }
                            assertNull(under(pointer), "The board's edge passed the pointer");
                            kept = preview.hexes();
                            dropped = kept.getLast();
                            assertTrue(kept.size() > 3 && (dropped.getX() == 29 || dropped.getY() == 0 || dropped.getY() == 23),
                                  "The route reached the board's edge: " + kept);
                            assertSame(preview, field(field(this, "editorPreview"), "built"), "Shown over the void");
                            assertUntouched();
                            capture("route-draw-edge-void");
                            // Back onto the board, out of the edge band, into the hex before the path's end: no camera
                            // change follows, so the hover comes from the drag alone.
                            back = kept.get(kept.size() - 2);
                            drag(over(back));
                            kept = kept.subList(0, kept.size() - 1);
                            next();
                        }
                        case 33 -> { if (preview.hexes().equals(kept)) { next(); } }
                        case 34 -> {
                            assertSame(preview, field(field(this, "editorPreview"), "built"), "Shown again on the board");
                            assertEquals(back, field(this, "hovered"), "The hover follows the drag");
                            assertUntouched();
                            capture("route-draw-edge-back");
                            release();
                            next();
                        }
                        default -> {
                            for (Coords at : kept) { assertNotNull(route(at), at + " is built"); }
                            assertNull(route(dropped), dropped + " was taken back");
                            capture("route-draw-edge-released");
                            Gdx.app.exit();
                        }
                    }
                }
            }, config);
        } finally { source.close(); }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        // The document after it all: the S road joins along its path, the join route connects both tracks.
        var checked = new FutureTask<Void>(() -> {
            var board = session.board();
            for (int i = 1; i < road.size(); i++) {
                assertTrue(board.getHex(road.get(i)).containsTerrainExit(Terrains.ROAD, road.get(i).direction(road.get(i - 1))),
                      road.get(i) + " joins the road hex before it");
            }
            var run = RailRoute.run(board::getHex, WEST.getFirst(), RailRoute.find(board.getHex(WEST.getFirst()), RailRoute.Kind.RAIL));
            assertTrue(run.stream().anyMatch(member -> member.at().equals(EAST.getFirst())), "The drawn route joins both tracks");
            return null;
        });
        SwingUtilities.invokeAndWait(checked);
        checked.get();
    }
}
