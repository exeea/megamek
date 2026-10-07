/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Input-to-install timings for a scale-seven projected decal on ordinary grass terrain. */
@Tag("on-demand")
class GpuEditorDecalLatencySmokeTest {
    @Test void reportsLargeDecalEditsAndKeepsDistantChunks() throws Exception {
        Coords owner = new Coords(10, 10);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(24, 24);
            board.setHex(owner, new Hex(0, "", "grass"));
            board.getHex(owner).setDecorations(List.of(new BoardDecoration("large", "decal", "decal/saxarba/rubble_light_path", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0, false)));
            editor.game().setBoard(board); editor.pointer(owner, 0, 0, false, "large"); editor.finishStroke();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800);
        List<Command> edits = List.of(new Command(Action.OBJECT_VALUE, "scale", "7"),
              new Command(Action.OBJECT_VALUE, "x", ".2"), new Command(Action.OBJECT_VALUE, "rotation", "35"),
              new Command(Action.REMOVE_OBJECT), new Command(Action.UNDO));
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 150_000_000_000L;
                int index;
                long started, revision, firstFrame;
                double maxFrame;
                Object distant;
                private Object field(Object object, String name) throws Exception {
                    var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
                }
                private GpuTerrain terrain() throws Exception {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                @Override public void render() {
                    try {
                        long before = System.nanoTime(); super.render();
                        if (started != 0) { maxFrame = Math.max(maxFrame, (System.nanoTime() - before) / 1e6); }
                        assertTrue(System.nanoTime() < deadline, "Large decal update stalled at " + index);
                        GpuTerrain terrain = terrain();
                        if (GpuBoardTestUi.loading(this) || terrain.busy()) { return; }
                        if (started != 0) {
                            if (source.editorState().revision() == revision || frames() < firstFrame + 3) { return; }
                            var recipients = (Map<?, ?>) field(terrain, "installedPaint");
                            assertEquals(index == 3, recipients.isEmpty(), "Removing/restoring a decal updates its entire footprint");
                            if (index != 3) { assertTrue(recipients.size() > 30, "Scale seven spans many hexes"); }
                            assertSame(distant, ((List<?>) field(terrain, "chunks")).getLast());
                            System.out.printf("DECAL %s recipients=%d input-to-install=%.1fms max-frame=%.1fms%n",
                                  edits.get(index), recipients.size(), (System.nanoTime() - started) / 1e6, maxFrame);
                            index++; started = 0;
                            if (index == edits.size()) { Gdx.app.exit(); return; }
                        }
                        if (distant == null) {
                            distant = ((List<?>) field(terrain, "chunks")).getLast();
                            boardCamera.zoom(.45f);
                        }
                        revision = source.editorState().revision(); firstFrame = frames(); maxFrame = 0; started = System.nanoTime();
                        source.editorCommand(edits.get(index), source.takeFrame().boardGeneration());
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Large decal latency", failure.get()); }
    }
}
