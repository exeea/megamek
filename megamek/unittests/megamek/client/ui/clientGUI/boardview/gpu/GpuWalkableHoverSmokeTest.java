/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The hover outline rises only to levels a unit can stand on (GpuTerrain.walkableHit): a building's roof and storeys
 * and a bridge deck, never an object, however high it floats. A selected floating object shows its tether.
 */
@Tag("on-demand")
class GpuWalkableHoverSmokeTest {
    private static final Coords GROUND = new Coords(3, 3), BUILDING = new Coords(3, 2), DECK = new Coords(4, 2);

    /** A pointer target: a world point on the hex and the outline's expected level, or NaN for its ground. */
    private record Case(String name, Coords hex, Vector3 point, float level) { }

    @Test void hoverOutlineRisesOnlyToWalkableLevels() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(7, 5);
            Hex ground = new Hex(0, "", "");
            ground.setDecorations(List.of(new BoardDecoration("lifted", "prop", "scenery/vehicles/car", "Lifted car",
                  0, 0, 30, false, 1, BoardDecoration.Placement.surface("ground", "top", 3), 0)));
            board.setHex(GROUND, ground);
            board.setHex(BUILDING, new Hex(0, "building:1;bldg_cf:15;bldg_elev:3", ""));
            board.setHex(DECK, new Hex(0, "pavement:1;bridge:1:9;bridge_cf:40;bridge_elev:2", ""));
            editor.game().setBoard(board);
            editor.command(new Command(Action.TOOL, "", "SELECT"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step; long after;
                List<Case> cases;

                private Object field(String name) throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField(name); field.setAccessible(true); return field.get(this);
                }
                private float hoverTop() throws ReflectiveOperationException {
                    var method = GpuBattleView.class.getDeclaredMethod("hoverTop"); method.setAccessible(true);
                    return (float) method.invoke(this);
                }
                /** Moves the pointer onto {@code point}, as the window's own mouse events do; returns the window point. */
                private int[] point(Vector3 point) throws ReflectiveOperationException {
                    var at = boardCamera.camera.project(point.cpy(), 0, 0, boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                    int[] window = { Math.round(at.x), Math.round(Gdx.graphics.getHeight() - at.y) };
                    ((InputProcessor) field("boardInput")).mouseMoved(window[0], window[1]);
                    return window;
                }
                /** The height a click at {@code window} hands the ruler, LOS and the menu. */
                private float pointedZ(int[] window) throws ReflectiveOperationException {
                    Object input = field("boardInput");
                    var pick = input.getClass().getDeclaredMethod("pickSelection", int.class, int.class);
                    pick.setAccessible(true);
                    Object picked = pick.invoke(input, window[0], window[1]);
                    var pointed = picked.getClass().getDeclaredMethod("pointedZ");
                    pointed.setAccessible(true);
                    return (float) pointed.invoke(picked);
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Walkable hover timed out at " + step);
                        var terrain = (GpuTerrain) field("terrain");
                        if (GpuBoardTestUi.loading(this) || terrain.busy() || frames() < after) { return; }
                        var scene = (BoardScene) field("scene");
                        float level = BoardGeometry.level();
                        if (step == 0) {
                            boardCamera.fit(scene, List.of(scene.tile(GROUND), scene.tile(BUILDING), scene.tile(DECK)));
                            boardCamera.camera.zoom *= 1.8f;
                            boardCamera.center(BoardGeometry.center(GROUND, 1).add(BoardGeometry.center(BUILDING, 1))
                                  .add(BoardGeometry.center(DECK, 1)).scl(1 / 3f));
                            // A wall point of the building's third storey (floor level 2), on the side facing the camera.
                            Vector3 centre = BoardGeometry.center(BUILDING, 0);
                            Vector3 toward = new Vector3(-boardCamera.camera.direction.x, -boardCamera.camera.direction.y, 0).nor();
                            cases = List.of(
                                  new Case("object", GROUND, terrain.editorObjects(GROUND).getFirst().bounds().getCenter(new Vector3()), Float.NaN),
                                  new Case("roof", BUILDING, BoardGeometry.center(BUILDING, 0).add(0, 0, 3 * level), 3),
                                  new Case("floor", BUILDING, centre.cpy().mulAdd(toward, .42f * BoardGeometry.height()).add(0, 0, 2.5f * level), 2),
                                  new Case("deck", DECK, BoardGeometry.center(DECK, 0).add(0, 0, 2 * level), 2));
                        } else if (step <= 2 * 4) {
                            Case target = cases.get((step - 1) / 2);
                            if (step % 2 == 1) {
                                int[] window = point(target.point());
                                assertEquals(target.hex(), field("hovered"), target.name());
                                // The ruler measures to the level the ring marks: the ground under an object.
                                assertEquals(Float.isNaN(target.level()) ? 0 : (int) target.level(),
                                      GpuLosResult.pointedHeight(0, pointedZ(window)), target.name() + " ruler height");
                                if (target.name().equals("object")) {
                                    var at = boardCamera.camera.project(target.point().cpy(), 0, 0,
                                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                                    var ray = boardCamera.camera.getPickRay(Math.round(at.x), Math.round(Gdx.graphics.getHeight() - at.y), 0, 0,
                                          boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                                    var hit = terrain.decorationHit(scene, ray);
                                    assertNotNull(hit, "The pointer is on the floating object");
                                    assertEquals("lifted", hit.id());
                                }
                                float top = hoverTop();
                                if (Float.isNaN(target.level())) {
                                    assertTrue(Float.isNaN(top), "An object never raises the outline: " + top / level);
                                } else {
                                    assertEquals(target.level() * level + .5f * BoardGeometry.hexScale(), top, .001f, target.name() + " hit z=" + field("hoverZ"));
                                }
                            } else {
                                GpuBoardTestUi.capture(new File(output, "walkable-hover-" + target.name() + ".png"));
                            }
                        } else if (step == 9) {
                            // A selected floating object: its tether runs down to the ground.
                            ((InputProcessor) field("boardInput")).mouseMoved(1, 1);
                            source.editorCommand(new Command(Action.SELECT_AT, GROUND.getX() + "," + GROUND.getY(), ""),
                                  source.takeFrame().boardGeneration());
                            source.editorCommand(new Command(Action.SELECT_OBJECT, "", "lifted"), source.takeFrame().boardGeneration());
                        } else if (step == 10) {
                            if (source.editorState().selection().stream().noneMatch(item -> item.object().equals("lifted"))) { return; }
                            GpuBoardTestUi.capture(new File(output, "walkable-hover-tether.png"));
                            Gdx.app.exit(); return;
                        }
                        step++; after = frames() + 5;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Walkable hover", failure.get()); }
    }
}
