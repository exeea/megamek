/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real perspective picking, asynchronous object previews, chunk handoff, and elevated terrain dragging. */
@Tag("on-demand")
class GpuEditorDragSmokeTest {
    @Test void draggingKeepsSelectionAndDropsOntoTheHighlightedGroundFootprint() throws Exception {
        Coords owner = new Coords(7, 5), destination = new Coords(9, 5);
        Coords bridge = new Coords(10, 7), bridgeDestination = new Coords(8, 7);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(16, 12);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.setHex(new Coords(x, y), new Hex(0, "", "lunar")); }
            }
            board.setHex(owner, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:4", "lunar"));
            board.getHex(owner).setDecorations(List.of(new BoardDecoration("moving", "prop", "scenery/components/car-red", null,
                  0, 0, 0, false, 3, BoardDecoration.Placement.surface("bridge", "deck", 3.7), 0)));
            board.setHex(bridge, new Hex(0, "bridge:1:9;bridge_cf:40;bridge_elev:10", "lunar"));
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1800, 1100);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step; long after;
                Vector3 start, drag;
                float originalX;
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private Vector3 screen(Vector3 world) {
                    Vector3 result = boardCamera.camera.project(new Vector3(world)); result.y = Gdx.graphics.getHeight() - result.y;
                    return result;
                }
                private void press(Vector3 point) {
                    GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT));
                }
                private void move(Vector3 point) {
                    Gdx.input.getInputProcessor().touchDragged(Math.round(point.x), Math.round(point.y), 0);
                }
                private void release(Vector3 point) {
                    Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                }
                private void assertHoverAnchor(GpuTerrain.EditorObject object) throws Exception {
                    var method = GpuBattleView.class.getDeclaredMethod("hoverTop"); method.setAccessible(true);
                    assertEquals(object.anchorLevel() * BoardGeometry.level() + .5f * BoardGeometry.hexScale(),
                          (Float) method.invoke(this), .001f, "An editor drag follows the rendered anchor without flooring it to a lower level");
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Editor drag stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        GpuTerrain terrain = terrain(); var state = source.editorState();
                        switch (step) {
                            case 0 -> {
                                if (terrain.busy()) { return; }
                                boardCamera.zoom(1); // Freeze the fitted camera while driving exact world displacements.
                                var object = terrain.editorObjects(owner).stream().filter(o -> o.id().equals("moving")).findFirst().orElseThrow();
                                var centre = object.bounds().getCenter(new Vector3()); originalX = centre.x;
                                start = new Vector3(centre.x, centre.y, object.bounds().max.z - .1f);
                                press(screen(start)); step++; after = frames() + 5;
                            }
                            case 1 -> {
                                if (!"moving".equals(state.object())) { return; }
                                assertEquals("moving", state.object(), "The real press selects the elevated prop");
                                assertHoverAnchor(terrain.editorObjects(owner).getFirst());
                                drag = screen(new Vector3(start).add(1.5f * BoardGeometry.width(), 0, 0));
                                move(drag); step++; after = frames() + 5;
                            }
                            case 2 -> {
                                if (state.objects().isEmpty() || state.objects().getFirst().x() < 1) { return; }
                                assertEquals(owner, state.selected(), "Owner remains stable during the gesture");
                                assertEquals("moving", state.object());
                                assertEquals(List.of(destination), state.movePreview());
                                assertEquals("ground", state.objects().getFirst().placement().receiver().terrain());
                                assertEquals(3.7, state.objects().getFirst().placement().offset());
                                var object = terrain.editorObjects(owner).stream().filter(o -> o.id().equals("moving")).findFirst().orElseThrow();
                                assertEquals(originalX + 1.5f * BoardGeometry.width(), object.bounds().getCenterX(), 3);
                                assertHoverAnchor(object);
                                assertEquals("moving", terrain.editorSectionPick(owner,
                                      new Ray(new Vector3(object.bounds().getCenterX(), object.bounds().getCenterY(), 500), new Vector3(0, 0, -1))));
                                release(drag); step++; after = frames() + 2;
                            }
                            case 3 -> {
                                if (!destination.equals(state.selected())) { return; }
                                assertEquals("moving", state.object());
                                // This assertion runs during the rebuild too; the old chunk must lend its moving instance.
                                var object = terrain.editorObjects(destination).stream().filter(o -> o.id().equals("moving")).findFirst().orElseThrow();
                                assertEquals(originalX + 1.5f * BoardGeometry.width(), object.bounds().getCenterX(), 3);
                                assertTrue(object.anchorLevel() < 5, "Dropping off the level-4 bridge follows ground plus the retained offset");
                                assertTrue(terrain.editorObjects(owner).stream().noneMatch(o -> o.id().equals("moving")));
                                if (terrain.busy()) { return; }
                                start = BoardGeometry.center(bridge, 10); start.z += .5f;
                                press(screen(start)); step++; after = frames() + 5;
                            }
                            case 4 -> {
                                if (!bridge.equals(state.selected())) { return; }
                                assertEquals(bridge, state.selected()); assertEquals("", state.object());
                                drag = screen(new Vector3(start).add(-1.5f * BoardGeometry.width(), 0, 0));
                                move(drag); step++; after = frames() + 5;
                            }
                            case 5 -> {
                                if (state.movePreview().isEmpty() || state.movePreview().equals(List.of(bridge))) { return; }
                                assertEquals(List.of(bridgeDestination), state.movePreview(), "The level-10 drag projects onto the frozen grabbed plane");
                                File folder = new File("build/gpu-board-review"); assertTrue(folder.isDirectory() || folder.mkdirs());
                                GpuBoardTestUi.capture(new File(folder, "editor-elevated-drag-projection.png"));
                                release(drag); step++; after = frames() + 5;
                            }
                            case 6 -> {
                                if (!state.movePreview().isEmpty()) { return; }
                                assertEquals(bridgeDestination, state.selected(), "Release uses the highlighted base, without repicking the cursor");
                                assertEquals(10, state.property("bridge_elev").value());
                                assertTrue(state.movePreview().isEmpty());
                                if (!terrain.busy()) { Gdx.app.exit(); }
                            }
                            default -> throw new AssertionError(step);
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor drag regression", failure.get()); }
    }
}
