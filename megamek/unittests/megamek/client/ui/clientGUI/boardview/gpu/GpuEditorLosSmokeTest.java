/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.io.File;
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
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The editor's real mouse path must measure without painting, then resume its active tool after closing LOS. */
@Tag("on-demand")
class GpuEditorLosSmokeTest {
    @Test
    void contextMenuAndAltClickUseNativeLosWhilePaintIsActive() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            editor.game().setBoard(Board.createEmptyBoard(9, 9));
            editor.command(new Command(Action.CHOOSE_BRUSH, "vegetation", "woods-1"), null);
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
                        view.create(); GpuBoardTestUi.present(view);
                        assertTrue(GpuBoardTestUi.stage().getRoot().findActor("editor-side-view").isVisible());
                        settle(view, source);
                        view.boardCamera.setIsometric(false);
                        view.boardCamera.fit(source.takeFrame().scene());
                        settle(view, source);
                        Coords from = new Coords(4, 6), to = new Coords(4, 1);
                        long revision = source.editorState().revision();
                        click(view, from, false, Input.Buttons.RIGHT);
                        settle(view, source);
                        var menu = GpuBoardTestUi.stage().getRoot().findActor("map-context-menu");
                        assertTrue(GpuBoardTestUi.shown(menu));
                        assertFalse(GpuBoardTestUi.texts(menu).contains("Top view"));
                        for (String mode : java.util.List.of("select", "paint", "erase")) {
                            assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-context-" + mode));
                        }
                        assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-context-settings"));
                        assertNotNull(GpuBoardTestUi.stage().getRoot().findActor("editor-context-undo"));
                        assertEquals(revision, source.editorState().revision(), "Opening the menu must not paint");
                        GpuBoardTestUi.click("map-line-of-sight");
                        settle(view, source);
                        assertEquals(InputEvent.ALT_DOWN_MASK, source.takeFrame().panels().los().pending());
                        click(view, to, false);
                        settle(view, source);
                        var result = source.takeFrame().panels().los();
                        assertEquals(from, result.start().coords());
                        assertEquals(to, result.end().coords());
                        assertEquals(5, result.distance());
                        assertEquals(result.ruler(), source.takeFrame().scene().tactical().ruler());
                        assertEquals(revision, source.editorState().revision(), "Measuring must not invoke the paint tool");
                        assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("los-panel")));
                        GpuBoardTestUi.click("los-start-height-caption"); settle(view, source);
                        assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("los-start-height-popup")));
                        Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE); settle(view, source);
                        assertTrue(source.takeFrame().panels().los().open(), "Escape dismisses the slider first");
                        GpuBoardTestUi.click("los-start-lock"); settle(view, source);
                        assertTrue(source.takeFrame().panels().los().start().locked());
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuBoardTestUi.capture(new File(output, "editor-native-los.png"));
                        GpuBoardTestUi.click("los-close"); settle(view, source);
                        assertFalse(source.takeFrame().panels().los().open());
                        click(view, from, true); settle(view, source);
                        assertEquals(InputEvent.ALT_DOWN_MASK, source.takeFrame().panels().los().pending());
                        click(view, to, false); settle(view, source);
                        assertEquals(to, source.takeFrame().panels().los().end().coords());
                        assertEquals(revision, source.editorState().revision(), "Alt-click still measures without painting");
                        GpuBoardTestUi.click("los-close"); settle(view, source);
                        click(view, from, false, Input.Buttons.RIGHT); settle(view, source);
                        GpuBoardTestUi.click("editor-context-settings"); settle(view, source);
                        assertTrue(GpuBoardTestUi.shown(GpuBoardTestUi.stage().getRoot().findActor("editor-settings")));
                        Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE); settle(view, source);
                        click(view, from, false); settle(view, source);
                        assertTrue(source.editorState().revision() > revision, "A normal click resumes painting");
                        assertTrue(source.editorState().properties().stream()
                              .anyMatch(property -> property.terrain().equals("woods") && property.value() > 0));
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, configuration);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Editor native LOS", failure.get()); }
    }

    private static void click(GpuBattleView view, Coords coords, boolean alt) {
        click(view, coords, alt, Input.Buttons.LEFT);
    }

    private static void click(GpuBattleView view, Coords coords, boolean alt, int button) {
        Vector2 point = GpuNameplates.project(view.boardCamera.camera, BoardGeometry.center(coords, 0));
        assertNotNull(point);
        int x = Math.round(point.x), y = Gdx.graphics.getHeight() - Math.round(point.y);
        Vector2 stage = GpuBoardTestUi.stage().screenToStageCoordinates(new Vector2(x, y));
        assertNull(GpuBoardTestUi.stage().hit(stage.x, stage.y, true), "The endpoint must be clear of editor panels");
        var input = Gdx.input.getInputProcessor();
        GpuBoardTestUi.withModifiers(alt ? InputEvent.ALT_DOWN_MASK : 0,
              () -> input.touchDown(x, y, 0, button));
        // Releasing Alt before the mouse must not turn a measurement into an edit.
        input.touchUp(x, y, 0, button);
    }

    private static void settle(GpuBattleView view, GpuMapSource source) throws Exception {
        SwingUtilities.invokeAndWait(source::refresh);
        for (int frame = 0; frame < 3; frame++) { view.render(); }
    }
}
