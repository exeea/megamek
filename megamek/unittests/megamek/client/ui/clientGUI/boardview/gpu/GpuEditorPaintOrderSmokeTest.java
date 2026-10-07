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
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests visible compositing, real contents dragging, blur commits and independent in-row deletion together. */
@Tag("on-demand")
class GpuEditorPaintOrderSmokeTest {
    @Test void contentsDragAndNumericOrderChangeTheVisiblePaint() throws Exception {
        Coords owner = new Coords(7, 7);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(16, 16);
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) { board.setHex(new Coords(x, y), new Hex(0, "pavement:1", "lunar")); }
            }
            String symbols = "decal/saxarba/SMV_GroundSymbols/";
            board.getHex(owner).setDecorations(List.of(
                  new BoardDecoration("red", "decal", symbols + "FluffSystem-05-Symbol-01-RedCross-1-01", null,
                        0, 0, 0, false, 3, BoardDecoration.Placement.ground(), 4, false),
                  new BoardDecoration("green", "decal", symbols + "FluffSystem-06-Faction-01-PreAgeofWarIS-1-CapellanCondederation-04", null,
                        0, 0, 0, false, 3, BoardDecoration.Placement.ground(), 2, false)));
            editor.game().setBoard(board); editor.pointer(owner, 0, 0, false, "red"); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;
                int step; long after = 60;
                @Override public void create() { super.create(); boardCamera.setIsometric(false); }
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private void pixel(boolean red) {
                    var point = boardCamera.camera.project(BoardGeometry.center(owner, 1));
                    Pixmap pixels = Pixmap.createFromFrameBuffer((int) point.x - 2, (int) point.y - 2, 5, 5);
                    long r = 0, g = 0;
                    for (int x = 0; x < 5; x++) {
                        for (int y = 0; y < 5; y++) { int value = pixels.getPixel(x, y); r += value >>> 24; g += value >>> 16 & 255; }
                    }
                    pixels.dispose();
                    assertTrue(red ? r > g * 1.5 : g > r * 1.5, "Visible paint expected " + (red ? "red" : "green") + ": R=" + r + " G=" + g);
                }
                private Vector2 screen(Actor actor, float y) {
                    return GpuBoardTestUi.stage().stageToScreenCoordinates(actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, y)));
                }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Paint order stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        Group root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> {
                                pixel(true);
                                Actor handle = root.findActor("editor-reorder-green"), target = root.findActor("editor-content-red");
                                Vector2 from = screen(handle, handle.getHeight() / 2), to = screen(target, target.getHeight() - 2);
                                var input = Gdx.input.getInputProcessor();
                                input.touchDown((int) from.x, (int) from.y, 0, Input.Buttons.LEFT);
                                input.touchDragged((int) to.x, (int) to.y, 0);
                                input.touchUp((int) to.x, (int) to.y, 0, Input.Buttons.LEFT);
                            }
                            case 1 -> {
                                if (!source.editorState().objects().getFirst().id().equals("green")) { return; }
                                pixel(false);
                                assertEquals("red", source.editorState().object(), "Dragging the handle preserves selection");
                                GpuBoardTestUi.capture(new File(output, "editor-paint-green-above.png"));
                                var order = (TextField) ((Group) root.findActor("editor-inspector")).findActor("editor-Paint order");
                                GpuBoardTestUi.stage().setKeyboardFocus(order); order.setText("13"); GpuBoardTestUi.stage().setKeyboardFocus(null);
                            }
                            case 2 -> {
                                if (!source.editorState().objects().getFirst().id().equals("red")) { return; }
                                pixel(true);
                                GpuBoardTestUi.capture(new File(output, "editor-paint-red-above.png"));
                                Actor remove = root.findActor("editor-remove-green");
                                assertTrue(remove.isDescendantOf(root.findActor("editor-content-green")));
                                GpuBoardTestUi.click("editor-remove-green");
                            }
                            case 3 -> {
                                if (source.editorState().objects().size() != 1) { return; }
                                assertEquals("red", source.editorState().object(), "The X deletes its own row without selecting it");
                                pixel(true); Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 30;
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(output, "editor-paint-order-failure.png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Paint order workflow", failure.get()); }
    }
}
