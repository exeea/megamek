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
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A captured hex, with terrain and objects, through the actual sampling button and 3D preview renderer. */
@Tag("on-demand")
class GpuEditorHexStampSmokeTest {
    @Test void identifiesTheCapturedHexAndKeepsItsPreviewWhenTheSelectionChanges() throws Exception {
        Coords sampled = new Coords(3, 3), other = new Coords(5, 5);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(8, 8); board.setNativeFormat(true);
            Hex hex = new Hex(2, "road:1:9;rough:1", "snow");
            hex.setDecorations(List.of(
                  new BoardDecoration("car", "prop", "scenery/vehicles/car", null, .2, 0, 25, false, 1,
                        BoardDecoration.Placement.ground(), 0),
                  new BoardDecoration("decal", "decal", "decal/damage/rubble-light-path", null, -.2, 0, 0, false, .7,
                        BoardDecoration.Placement.ground(), 0)));
            board.setHex(sampled, hex); editor.game().setBoard(board);
            editor.pointer(sampled, 0, 0, false); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File folder = new File("build/gpu-board-review"); assertTrue(folder.isDirectory() || folder.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step; long after;
                Texture captured;
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Hex stamp preview stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        var state = source.editorState();
                        switch (step) {
                            case 0 -> GpuBoardTestUi.click("editor-use-hex-brush");
                            case 1 -> {
                                Image preview = root.findActor("editor-hex-stamp-preview");
                                if (preview == null || preview.getDrawable() == null) { return; }
                                assertEquals(sampled, state.activeBrush().sampledHex());
                                assertEquals(2, state.activeBrush().objects().size());
                                assertNull(state.activeBrush().object());
                                Label title = root.findActor("editor-brush-title");
                                assertTrue(title.getText().toString().contains("Hex stamp · 0404"));
                                assertTrue(GpuBoardTestUi.shown(preview));
                                captured = ((TextureRegionDrawable) preview.getDrawable()).getRegion().getTexture();
                                GpuBoardTestUi.capture(new File(folder, "editor-hex-stamp.png"));
                                command(Action.THEME, "", "desert");
                            }
                            case 2 -> {
                                if (!state.theme().equals("desert")) { return; }
                                assertEquals("snow", state.activeBrush().theme());
                                command(Action.SELECT_AT, "5,5", ""); command(Action.TOOL, "", "PAINT");
                            }
                            case 3 -> {
                                if (!other.equals(state.selected()) || state.tool() != BoardEditorSession.Tool.PAINT) { return; }
                                Image preview = root.findActor("editor-hex-stamp-preview");
                                assertSame(captured, ((TextureRegionDrawable) preview.getDrawable()).getRegion().getTexture(),
                                      "The stamp preview cannot follow the inspected hex");
                                assertEquals(sampled, state.activeBrush().sampledHex());
                                // A native resize can synchronously re-enter render; advance before asking for it.
                                step++; after = frames() + 12;
                                Gdx.graphics.setWindowedMode(1100, 850);
                                return;
                            }
                            case 4 -> {
                                Group panel = root.findActor("editor-brush");
                                assertNotNull(panel);
                                GpuBoardTestUi.assertHorizontalBounds(panel, panel);
                                GpuBoardTestUi.capture(new File(folder, "editor-hex-stamp-narrow.png"));
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 12;
                    } catch (Throwable error) {
                        GpuBoardTestUi.capture(new File(folder, "editor-hex-stamp-failure.png"));
                        failure.set(error); Gdx.app.exit();
                    }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor hex stamp", failure.get()); }
    }
}
