/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real native UI and installed meshes: brushes, owner-independent paint, and local invalidation. */
@Tag("on-demand")
class GpuEditorBrushSmokeTest {
    @Test void browsesPaintableCategoriesAndUpdatesEveryDecalRecipient() throws Exception {
        Coords owner = new Coords(7, 7);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(24, 24);
            for (int x = 0; x < 24; x++) {
                for (int y = 0; y < 24; y++) { board.setHex(new Coords(x, y), new Hex(0, "", "lunar")); }
            }
            board.getHex(owner).setDecorations(List.of(new BoardDecoration("paint", "decal", "decal/saxarba/rubble_light_path", null,
                  0, 0, 25, false, 3, BoardDecoration.Placement.ground(), 0, false)));
            editor.game().setBoard(board); editor.pointer(owner, 0, 0, false); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;
                int step; long after;
                Set<Coords> oldPaint;
                Object distantChunk;
                float[] cameraProjection;
                private Object field(Object object, String name) throws Exception {
                    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
                }
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                private Set<Coords> painted(GpuTerrain terrain) throws Exception {
                    Set<Coords> result = new HashSet<>();
                    for (Object chunk : (List<?>) field(terrain, "chunks")) {
                        for (var entry : ((Map<?, ?>) field(chunk, "tileMeshes")).entrySet()) {
                            if (((List<?>) field(entry.getValue(), "paint")).stream().anyMatch(p -> {
                                try { return !((List<?>) field(p, "faces")).isEmpty(); }
                                catch (Exception e) { throw new IllegalStateException(e); }
                            })) { result.add((Coords) entry.getKey()); }
                        }
                    }
                    return result;
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Brush smoke stalled at " + step);
                        GpuTerrain terrain = terrain();
                        if (GpuBoardTestUi.loading(this) || terrain.busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        if (step > 0 && step < 13) {
                            assertArrayEquals(cameraProjection, boardCamera.camera.combined.val, .001f,
                                  "Changing editor selections and panel sizes must leave the camera still");
                        }
                        switch (step) {
                            case 0 -> {
                                boardCamera.fit(source.takeFrame().scene());
                                cameraProjection = boardCamera.camera.combined.val.clone();
                                Vector3 pivot = boardCamera.camera.project(boardCamera.focus.cpy(), 0, 0,
                                      boardCamera.camera.viewportWidth, boardCamera.camera.viewportHeight);
                                assertEquals(boardCamera.camera.viewportWidth / 2, pivot.x, .01f);
                                assertEquals(boardCamera.camera.viewportHeight / 2, pivot.y, .01f);
                                oldPaint = painted(terrain);
                                assertTrue(oldPaint.size() > 6);
                                assertTrue(oldPaint.stream().map(at -> new Coords(at.getX() / 8, at.getY() / 8)).distinct().count() >= 4);
                                distantChunk = ((List<?>) field(terrain, "chunks")).getLast();
                                assertFalse(GpuBoardTestUi.texts(root.findActor("editor-toolbar")).contains("PLACE"));
                                assertNull(root.findActor("editor-library-space"));
                                GpuBoardTestUi.click("editor-library-vegetation");
                            }
                            case 1 -> {
                                assertNotNull(root.findActor("editor-library-vegetation/jungle-2"));
                                GpuBoardTestUi.click("editor-library-vegetation/jungle-2");
                            }
                            case 2 -> {
                                if (!previewsReady(root.findActor("editor-asset-strip"))) { return; }
                                assertEquals(BoardEditorSession.Tool.PAINT, source.editorState().tool());
                                assertEquals("vegetation/jungle-2", source.editorState().activeBrush().key());
                                assertEquals(owner, source.editorState().selected());
                                assertFalse(source.editorState().dirty());
                                assertTrue(root.findActor("editor-brush-").isDescendantOf(root.findActor("editor-brush")));
                                assertFalse(GpuBoardTestUi.texts(root.findActor("editor-inspector")).contains("LEGACY SOURCE…"));
                                GpuBoardTestUi.assertHorizontalBounds((Group) root.findActor("editor-brush"), root.findActor("editor-brush"));
                                GpuBoardTestUi.capture(new File(output, "editor-brush-vegetation.png"));
                                boardCamera.pan(30, -20); boardCamera.zoom(.8f);
                                cameraProjection = boardCamera.camera.combined.val.clone();
                                GpuBoardTestUi.click("editor-library-Vehicles");
                            }
                            case 3 -> GpuBoardTestUi.click("editor-library-Vehicles");
                            case 4 -> {
                                ((TextField) root.findActor("editor-search")).setText("car-red");
                            }
                            case 5 -> GpuBoardTestUi.click("editor-library-scenery/components/car-red");
                            case 6 -> {
                                boardCamera.setPerspective(true);
                                GpuBoardTestUi.click("map-view-fit");
                                cameraProjection = boardCamera.camera.combined.val.clone();
                                assertEquals("scenery/components/car-red", source.editorState().asset());
                                var scale = (TextField) ((Group) root.findActor("editor-brush")).findActor("editor-Scale");
                                GpuBoardTestUi.stage().setKeyboardFocus(scale); scale.setText("1.5"); GpuBoardTestUi.stage().setKeyboardFocus(null);
                            }
                            case 7 -> {
                                assertEquals(1.5, source.editorState().activeBrush().object().scale());
                                assertEquals(1, source.editorState().objects().size(), "Configuring a brush has not placed an object");
                                command(Action.SELECT_OBJECT, "", "paint");
                            }
                            case 8 -> {
                                if (!previewsReady(root.findActor("editor-asset-strip"))) { return; }
                                assertNotNull(((Group) root.findActor("editor-inspector")).findActor("editor-Scale"), "Decals expose scale too");
                                assertEquals(1.5, source.editorState().activeBrush().object().scale());
                                GpuBoardTestUi.capture(new File(output, "editor-brush-spanning-decal.png"));
                                boardCamera.pan(-20, 30); boardCamera.zoom(.8f);
                                cameraProjection = boardCamera.camera.combined.val.clone();
                                command(Action.OBJECT_VALUE, "x", "7");
                            }
                            case 9 -> {
                                var paint = source.editorState().objects().getFirst();
                                Set<Coords> next = painted(terrain);
                                assertEquals(BoardDecals.footprint(source.editorState().selected(), paint, 24, 24), next);
                                assertTrue(java.util.Collections.disjoint(oldPaint, next), "The moved paint left no old triangles");
                                assertSame(distantChunk, ((List<?>) field(terrain, "chunks")).getLast(), "A far chunk was not rebuilt");
                                Coords target = next.stream().filter(at -> at.getX() >= 16).findFirst().orElseThrow();
                                Vector3 center = BoardGeometry.center(target, 20);
                                var hit = terrain.decorationHit(source.takeFrame().scene(), new Ray(center, new Vector3(0, 0, -1)));
                                // Some recipient centres lie outside a rotated rectangle; use an interior point instead.
                                if (hit == null) {
                                    center = BoardGeometry.center(owner, 20).add(7 * BoardGeometry.width(), 0, 0);
                                    hit = terrain.decorationHit(source.takeFrame().scene(), new Ray(center, new Vector3(0, 0, -1)));
                                }
                                assertNotNull(hit); assertEquals("paint", hit.id()); assertEquals(source.editorState().selected(), hit.coords());
                                command(Action.OBJECT_VALUE, "scale", "1");
                            }
                            case 10 -> {
                                assertEquals(BoardDecals.footprint(source.editorState().selected(), source.editorState().objects().getFirst(), 24, 24), painted(terrain));
                                command(Action.REMOVE_OBJECT, "", "");
                            }
                            case 11 -> { assertTrue(painted(terrain).isEmpty()); command(Action.UNDO, "", ""); }
                            case 12 -> {
                                assertFalse(painted(terrain).isEmpty());
                                var section = root.findActor("editor-section-panel");
                                var inspector = (ScrollPane) root.findActor("editor-inspector-scroll");
                                assertTrue(section.isDescendantOf(inspector.getActor()));
                                assertTrue(section.getWidth() < root.findActor("editor-inspector").getWidth());
                                GpuBoardTestUi.click("editor-section-reveal");
                                after = frames() + 8; step++; Gdx.graphics.setWindowedMode(1280, 800); return;
                            }
                            case 13 -> {
                                assertCompactBrush(root);
                                GpuBoardTestUi.capture(new File(output, "editor-brush-compact.png"));
                                String group = megamek.common.board.BoardEditorBlueprint.get().asset(source.editorState().objects().getFirst().asset()).group();
                                GpuBoardTestUi.click("editor-library-" + group);
                            }
                            case 14 -> {
                                assertFalse(root.findActor("editor-brush").isVisible(), "An unrelated category has no empty brush panel");
                                command(Action.SAMPLE, "", "");
                            }
                            case 15 -> {
                                assertEquals("decal", source.editorState().activeBrush().object().kind());
                                assertCompactBrush(root);
                                GpuBoardTestUi.capture(new File(output, "editor-brush-decal-compact.png"));
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(output, "editor-brush-failure.png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Brush workflow failed", failure.get()); }
    }

    private static void assertCompactBrush(Group root) {
        Actor brush = root.findActor("editor-brush"), precision = root.findActor("editor-precision");
        assertTrue(brush.isVisible());
        assertTrue(brush.getHeight() < 180, "Brush controls must not stretch into a tall panel");
        assertEquals(1, precision.getParent().getChildren().size, "Precision mode occupies the first row alone");
        var top = precision.localToAscendantCoordinates((Group) brush, new com.badlogic.gdx.math.Vector2());
        var rotation = root.findActor("editor-brush-object:rotation").localToAscendantCoordinates((Group) brush, new com.badlogic.gdx.math.Vector2());
        assertTrue(top.y > rotation.y);
        GpuBoardTestUi.assertHorizontalBounds((Group) brush, brush);
    }

    private static boolean previewsReady(Actor actor) {
        if (actor instanceof Image image && "editor-card-preview".equals(image.getName())) {
            assertNotEquals("Preview unavailable", image.getUserObject());
            assertNotEquals("No preview", image.getUserObject());
            return image.getDrawable() != null;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) { if (!previewsReady(child)) { return false; } }
        }
        return true;
    }
}
