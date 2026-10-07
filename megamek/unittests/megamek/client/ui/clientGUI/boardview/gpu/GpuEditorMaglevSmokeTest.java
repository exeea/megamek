/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shared maglev pieces are selectable, previewable assets as well as compatible old-map compositions. */
@Tag("on-demand")
class GpuEditorMaglevSmokeTest {
    @Test void previewsAndRendersSeparateWagonsBesideCompleteLegacyLayouts() throws Exception {
        Coords train = new Coords(3, 1);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            var board = Board.createEmptyBoard(5, 4);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.getHex(x, y).setTheme("lunar"); }
            }
            place(board, new Coords(1, 1), "scenery/fluff/maglevtrain2");
            place(board, train, "scenery/components/maglev-train");
            place(board, new Coords(1, 3), "scenery/components/maglev-wagon");
            place(board, new Coords(2, 3), "scenery/components/maglev-platform");
            place(board, new Coords(3, 3), "scenery/components/maglev-cab");
            editor.game().setBoard(board); editor.pointer(train, 0, 0, false); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File("build/gpu-board-review");
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;
                int step;
                long after;

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Maglev palette stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> {
                                assertEquals(3, source.takeFrame().scene().tile(train).features().stream()
                                      .filter(feature -> feature.decoration() != null).count());
                                GpuBoardTestUi.click("editor-library-Maglev and wagons");
                            }
                            case 1 -> ((TextField) root.findActor("editor-search")).setText("wagon");
                            case 2 -> {
                                if (!ready(root.findActor("editor-asset-strip"))) { return; }
                                assertNotNull(root.findActor("editor-library-scenery/components/maglev-wagon"));
                                assertNotNull(root.findActor("editor-library-scenery/components/maglev-train"));
                                GpuBoardTestUi.click("editor-library-scenery/components/maglev-wagon");
                            }
                            case 3 -> {
                                assertEquals("scenery/components/maglev-wagon", source.editorState().asset());
                                assertEquals(BoardEditorSession.Tool.PAINT, source.editorState().tool());
                                GpuBoardTestUi.capture(new File(output, "editor-maglev-palette.png"));
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Maglev palette", failure.get()); }
    }

    private static void place(Board board, Coords at, String asset) {
        board.getHex(at).setDecorations(List.of(new BoardDecoration("maglev-" + at, "prop", asset, null,
              0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0)));
    }

    private static boolean ready(Actor actor) {
        if (actor instanceof Image image && "editor-card-preview".equals(image.getName())) {
            assertNotEquals("Preview unavailable", image.getUserObject());
            assertNotEquals("No preview", image.getUserObject());
            return image.getDrawable() != null;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) { if (!ready(child)) { return false; } }
        }
        return true;
    }
}
