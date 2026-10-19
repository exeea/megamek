/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.client.ui.boardeditor.BoardEditorSession.Tool;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** B+click uses the same whole-hex brush as the inspector, without letting the gesture edit the board. */
@Tag("on-demand")
class GpuEditorBrushPickerSmokeTest {
    private static final Coords SAMPLE = new Coords(4, 4), TARGET = new Coords(5, 4);

    @Test
    void picksWholeHexInEveryToolAndCameraThenNormalClickStampsIt() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(9, 9);
            Hex hex = new Hex(1, "rough:1", "desert");
            hex.setDecorations(List.of(new BoardDecoration("sample-car", "prop", "scenery/vehicles/car", null,
                  0, 0, 0, false, 1, BoardDecoration.Placement.ground(), 0)));
            board.setHex(SAMPLE, hex);
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(source);
                    try {
                        view.create(); GpuBoardTestUi.present(view); settle(view, source);
                        send(source, new Command(Action.SELECT_AT, "4,4", "")); settle(view, source);
                        GpuBoardTestUi.click("editor-use-hex-brush"); settle(view, source);
                        var expected = source.editorState().activeBrush();
                        assertEquals(SAMPLE, expected.sampledHex());
                        assertEquals(1, expected.objects().size());
                        assertNull(expected.object(), "The car belongs to the hex stamp, not an individual object brush");

                        for (boolean isometric : new boolean[] { false, true }) {
                            view.boardCamera.setIsometric(isometric);
                            view.boardCamera.fit(source.takeFrame().scene()); settle(view, source);
                            for (Tool tool : Tool.values()) {
                                send(source, new Command(Action.CHOOSE_BRUSH, "vegetation", "woods-1"));
                                send(source, new Command(Action.SELECT_AT, "4,4", "sample-car"));
                                send(source, new Command(Action.TOOL, tool.name())); settle(view, source);
                                Vector2 at = screen(view, source, SAMPLE);
                                beginPick(at);
                                // Releasing B before the mouse must retain the picker and never paint or move.
                                Gdx.input.getInputProcessor().keyUp(Input.Keys.B);
                                Gdx.input.getInputProcessor().touchUp(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
                                settle(view, source);
                                var state = source.editorState();
                                assertEquals(expected, state.activeBrush(), tool + "/isometric=" + isometric);
                                assertEquals(Tool.PAINT, state.tool());
                                assertEquals(SAMPLE, state.selected());
                                assertEquals("", state.object(), "Picking samples the whole hex even with an object selected");
                                assertFalse(state.dirty(), "Sampling must not change the board");
                                assertFalse(state.canUndo(), "Sampling must not create an undo step");
                            }
                        }

                        send(source, new Command(Action.CHOOSE_BRUSH, "vegetation", "woods-1")); settle(view, source);
                        var beforeDrag = source.editorState();
                        Vector2 from = screen(view, source, SAMPLE), to = screen(view, source, TARGET);
                        beginPick(from);
                        var input = Gdx.input.getInputProcessor();
                        input.keyUp(Input.Keys.B);
                        input.touchDragged(Math.round(to.x), Math.round(to.y), 0);
                        input.touchUp(Math.round(to.x), Math.round(to.y), 0, Input.Buttons.LEFT); settle(view, source);
                        assertEquals(beforeDrag.activeBrush(), source.editorState().activeBrush(), "A drag cancels picking");
                        assertEquals(beforeDrag.selected(), source.editorState().selected());
                        assertFalse(source.editorState().dirty(), "Releasing B while dragging must not paint");
                        assertFalse(source.editorState().canUndo());

                        beginPick(from);
                        input.touchUp(Math.round(from.x), Math.round(from.y), 0, Input.Buttons.LEFT);
                        input.keyUp(Input.Keys.B); settle(view, source);
                        GpuBoardTestUi.withModifiers(0, () -> {
                            input.touchDown(Math.round(to.x), Math.round(to.y), 0, Input.Buttons.LEFT);
                            input.touchUp(Math.round(to.x), Math.round(to.y), 0, Input.Buttons.LEFT);
                        });
                        settle(view, source);
                        var stamped = source.editorState();
                        assertEquals(TARGET, stamped.selected(), "A later normal click resumes painting");
                        assertEquals(expected.elevation(), stamped.elevation());
                        assertEquals(expected.theme(), stamped.theme());
                        assertEquals(1, stamped.property("rough").value());
                        assertEquals(List.of("scenery/vehicles/car"), stamped.objects().stream().map(BoardDecoration::asset).toList());
                        assertTrue(stamped.dirty()); assertTrue(stamped.canUndo());
                        send(source, new Command(Action.UNDO)); settle(view, source);
                        assertFalse(source.editorState().dirty(), "Only the normal click created an edit");
                        assertFalse(source.editorState().canUndo());
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor brush picker", failure.get()); }
    }

    private static void beginPick(Vector2 at) {
        GpuBoardTestUi.withModifiers(0, () -> {
            when(Gdx.input.isKeyPressed(Input.Keys.B)).thenReturn(true);
            var input = Gdx.input.getInputProcessor();
            input.keyDown(Input.Keys.B);
            input.touchDown(Math.round(at.x), Math.round(at.y), 0, Input.Buttons.LEFT);
        });
    }

    private static Vector2 screen(GpuBattleView view, GpuMapSource source, Coords at) {
        Vector2 point = GpuNameplates.project(view.boardCamera.camera,
              BoardGeometry.center(at, source.takeFrame().scene().tile(at).elevation()));
        assertNotNull(point);
        point.y = Gdx.graphics.getHeight() - point.y;
        Vector2 stage = GpuBoardTestUi.stage().screenToStageCoordinates(new Vector2(point));
        assertNull(GpuBoardTestUi.stage().hit(stage.x, stage.y, true), "The click must be clear of editor panels");
        return point;
    }

    private static void send(GpuMapSource source, Command command) {
        source.editorCommand(command, source.takeFrame().boardGeneration());
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
