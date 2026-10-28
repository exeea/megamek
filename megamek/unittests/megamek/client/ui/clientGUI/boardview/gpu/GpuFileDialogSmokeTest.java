/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.gdx.UiModal;
import megamek.client.ui.gdx.UiTestStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real map HUD, source command routing and Tuning controls, with the picker in their actual stage. */
@Tag("on-demand")
class GpuFileDialogSmokeTest {
    @Test void editorOpenAndTuningPickerStayAboveTheSceneAndOwnInput() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        try {
            UiTestStage.run(ui -> {
                var skin = new GpuBoardSkin();
                var camera = new BoardCamera();
                var model = new GpuBoardTuning(skin.skin, source, camera);
                var hud = new GpuMapHud(source, skin.skin, ui.stage.getBatch(), camera, model,
                      new GpuPlaybackHistory(new UnitPlayback()), () -> null);
                try {
                    hud.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 1);
                    frame(hud, source);
                    Actor open = findText(hud.stage().getRoot(), "OPEN");
                    assertNotNull(open);
                    open.fire(new ChangeEvent());
                    await(hud, source, () -> hud.stage().getRoot().findActor("file-dialog") != null);
                    await(hud, source, () -> !((TextButton) hud.stage().getRoot().findActor("file-accept")).isDisabled());
                    assertTrue(hud.isModal());
                    assertTrue(hud.keyDown(Input.Keys.C, KeyEvent.VK_C, 0), "Tool keys stay in the picker");
                    assertSame(UiModal.active(hud.stage()), hud.stage().hit(2, 2, true));
                    GpuBoardTestUi.capture(new java.io.File(UiTestStage.OUTPUT, "file-picker-editor.png"));
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    hud.keyUp(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE);
                    await(hud, source, () -> source.editorFilePrompt() == null);

                    source.key(KeyEvent.VK_O, true, InputEvent.CTRL_DOWN_MASK);
                    await(hud, source, () -> hud.stage().getRoot().findActor("file-dialog") != null);
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    await(hud, source, () -> source.editorFilePrompt() == null);

                    hud.stage().getRoot().findActor("tuning-button").fire(new ChangeEvent());
                    frame(hud, source);
                    hud.stage().getRoot().findActor("tuning-atmosphere-tab").fire(new ChangeEvent());
                    frame(hud, source);
                    hud.stage().getRoot().findActor("tuning-environment-image").fire(new ChangeEvent());
                    await(hud, source, () -> !((TextButton) hud.stage().getRoot().findActor("file-accept")).isDisabled());
                    for (int index = 0; index < 5; index++) { frame(hud, source); }
                    assertNotNull(UiModal.active(hud.stage()));
                    assertTrue(hud.isModal());
                    assertSame(UiModal.active(hud.stage()), hud.stage().getActors().peek(),
                          "Tuning must not raise itself over its picker on later frames");
                    GpuBoardTestUi.capture(new java.io.File(UiTestStage.OUTPUT, "file-picker-tuning.png"));
                    hud.keyDown(Input.Keys.ESCAPE, KeyEvent.VK_ESCAPE, 0);
                    assertNull(UiModal.active(hud.stage()));
                    assertFalse(hud.isModal());
                    assertNull(model.atmosphereOptions().environmentImage(), "Cancel leaves lighting unchanged");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } finally { hud.dispose(); skin.dispose(); }
            });
        } finally { SwingUtilities.invokeAndWait(source::close); }
    }

    private static void frame(GpuMapHud hud, GpuMapSource source) {
        hud.update(source.takeFrame(), GpuHud.HudView.EMPTY, null, source.uiPreferences());
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        hud.draw();
    }

    private static void await(GpuMapHud hud, GpuMapSource source, BooleanSupplier ready) throws InterruptedException {
        long until = System.nanoTime() + 10_000_000_000L;
        do { frame(hud, source); if (ready.getAsBoolean()) { return; } Thread.sleep(5); }
        while (System.nanoTime() < until);
        fail("The in-scene file prompt did not respond");
    }

    private static Actor findText(Actor actor, String text) {
        if (actor instanceof TextButton button && button.getText().toString().equalsIgnoreCase(text)) { return actor; }
        if (actor instanceof com.badlogic.gdx.scenes.scene2d.Group group) {
            for (Actor child : group.getChildren()) { Actor found = findText(child, text); if (found != null) { return found; } }
        }
        return null;
    }
}
