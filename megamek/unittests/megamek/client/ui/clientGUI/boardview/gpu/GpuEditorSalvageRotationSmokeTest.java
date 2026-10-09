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
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.scenes.scene2d.Group;
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

@Tag("on-demand")
class GpuEditorSalvageRotationSmokeTest {
    @Test void drawsSeparateSalvageMeshesDeploymentOutlinesAndEditableThreeAxisProps() throws Exception {
        Coords selected = new Coords(3, 2);
        FutureTask<GpuMapSource> setup = new FutureTask<>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(6, 5);
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 5; y++) { board.setHex(x, y, new Hex(0, "", "lunar")); }
            }
            board.setHex(1, 1, new Hex(0, "arms:4", "lunar"));
            board.setHex(1, 2, new Hex(0, "legs:4", "lunar"));
            board.setHex(1, 3, new Hex(0, "arms:2;legs:2", "lunar"));
            board.setHex(4, 1, new Hex(2, "deployment_zone:1:1", "lunar"));
            board.setHex(4, 2, new Hex(0, "deployment_zone:1:1", "lunar"));
            board.getHex(selected).setDecorations(List.of(new BoardDecoration("car", "prop", "scenery/vehicles/car",
                  null, 0, 0, 0, false, 2, BoardDecoration.Placement.surface("ground", "top", 1), 0, false)));
            editor.game().setBoard(board); editor.pointer(selected, 0, 0, false);
            editor.command(new Command(Action.SELECT_OBJECT, "", "car"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review")); output.mkdirs();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                long deadline = System.nanoTime() + 150_000_000_000L, after;
                int step;
                BoundingBox initial;
                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Timed out at step " + step);
                        GpuTerrain terrain = terrain();
                        if (GpuBoardTestUi.loading(this) || terrain.busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> {
                                initial = new BoundingBox(terrain.editorObjects(selected).getFirst().bounds());
                                var scene = source.takeFrame().scene();
                                assertFalse(scene.tactical().walls().isEmpty());
                                assertTrue(BoardDeploymentGeometry.zoneFills(scene).isEmpty(), "Editor outlines must not enable zone tint");
                                assertEquals(12, scene.tiles().stream().flatMap(tile -> tile.features().stream())
                                      .filter(feature -> feature.kind() == BoardScene.FeatureKind.LIMB).count());
                                Group inspector = root.findActor("editor-inspector");
                                TextField x = inspector.findActor("editor-X"), y = inspector.findActor("editor-Y"), z = inspector.findActor("editor-Z");
                                assertNotNull(x); assertNotNull(y); assertNotNull(z);
                                assertEquals(x.localToStageCoordinates(new com.badlogic.gdx.math.Vector2()).y,
                                      z.localToStageCoordinates(new com.badlogic.gdx.math.Vector2()).y, .1, "Axes occupy one input row");
                                GpuBoardTestUi.assertHorizontalBounds(inspector, inspector);
                                command(Action.OBJECT_VALUE, "rotationX", "65");
                                command(Action.OBJECT_VALUE, "rotationY", "30");
                                command(Action.OBJECT_VALUE, "rotation", "45");
                            }
                            case 1 -> {
                                var placed = terrain.editorObjects(selected).getFirst();
                                assertTrue(placed.bounds().getDepth() > initial.getDepth() * 1.4, "Tilting changes the actual solid mesh");
                                Vector3 center = placed.bounds().getCenter(new Vector3());
                                var hit = terrain.decorationHit(source.takeFrame().scene(),
                                      new Ray(new Vector3(center.x, center.y, placed.bounds().max.z + 100), new Vector3(0, 0, -1)));
                                assertNotNull(hit); assertEquals("car", hit.id());
                                GpuBoardTestUi.capture(new File(output, "editor-salvage-rotation-deployment.png"));
                                GpuBoardTestUi.category("Vehicles");
                                command(Action.SAMPLE, "", "");
                            }
                            case 2 -> {
                                assertEquals(65, source.editorState().activeBrush().object().rotationX());
                                assertEquals(30, source.editorState().activeBrush().object().rotationY());
                                var brush = (Group) root.findActor("editor-brush");
                                assertTrue(brush.isVisible());
                                assertNotNull(brush.findActor("editor-X"));
                                assertNotNull(brush.findActor("editor-Y"));
                                assertNotNull(brush.findActor("editor-Z"));
                                GpuBoardTestUi.assertHorizontalBounds(brush, brush);
                                GpuBoardTestUi.capture(new File(output, "editor-three-axis-brush.png"));
                                command(Action.UNDO, "", "");
                            }
                            case 3 -> {
                                var object = source.editorState().objects().getFirst();
                                assertEquals(0, object.rotation()); assertEquals(65, object.rotationX()); assertEquals(30, object.rotationY());
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 10;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Salvage, deployment and XYZ rotation", failure.get()); }
    }
}
