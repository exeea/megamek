/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import megamek.client.ui.gdx.UiButton;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("on-demand")
class GpuLightingPickerSmokeTest {
    @TempDir Path directory;

    @Test void panelOpensSwingPickerAboveNativeWindowAndSelectionSurvivesCancellation() throws Exception {
        Path file = GpuLightingControlsTest.constantPng(directory.resolve("lighting.png"), 0x804020);
        var failure = new AtomicReference<Throwable>();
        var openDialog = new AtomicReference<JDialog>();
        var opened = new AtomicInteger();
        AWTEventListener answer = event -> {
            if (!(event instanceof WindowEvent window) || event.getID() != WindowEvent.WINDOW_OPENED
                  || !(window.getWindow() instanceof JDialog dialog)
                  || !dialog.getTitle().startsWith("Lighting environment")) { return; }
            openDialog.set(dialog);
            SwingUtilities.invokeLater(() -> {
                try {
                    assertTrue(dialog.isShowing(), "the panel opens a real Swing dialog");
                    assertTrue(dialog.isAlwaysOnTop(), "the ownerless picker must appear above the native board");
                    JFileChooser chooser = List.of(dialog.getContentPane().getComponents()).stream()
                          .filter(JFileChooser.class::isInstance).map(JFileChooser.class::cast).findFirst().orElseThrow();
                    if (opened.incrementAndGet() == 2) {
                        chooser.setSelectedFile(file.toFile());
                        chooser.approveSelection();
                    } else {
                        chooser.cancelSelection();
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    dialog.dispose();
                }
            });
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(answer, AWTEvent.WINDOW_EVENT_MASK);
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuBoardSkin skin;
                GpuHudKit kit;
                Stage stage;
                GpuBoardTuning tuning;
                GpuTuningPanel panel;
                GpuHud.Inputs inputs;
                GpuLightingEnvironment selected;
                int step;
                long deadline;

                @Override public void create() {
                    try {
                        skin = new GpuBoardSkin();
                        kit = new GpuHudKit(skin.skin);
                        stage = new Stage(new ScreenViewport());
                        stage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
                        tuning = new GpuBoardTuning(skin.skin);
                        var state = new GpuHudState(new GpuPlaybackHistory(new UnitPlayback()));
                        state.dialog = GpuHudState.Dialog.TUNING;
                        panel = new GpuTuningPanel(kit, null, state, new BoardCamera(), tuning);
                        panel.actor().setSize(stage.getWidth(), stage.getHeight());
                        stage.addActor(panel.actor());
                        inputs = new GpuHud.Inputs(null, GpuHud.HudView.EMPTY, null, null,
                              GpuHud.Metrics.of((int) stage.getWidth(), (int) stage.getHeight()), List.of());
                        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    } catch (Throwable error) { fail(error); }
                }

                @Override public void render() {
                    try {
                        if (failure.get() != null) { Gdx.app.exit(); return; }
                        assertTrue(System.nanoTime() < deadline, "picker/load callback timed out at step " + step);
                        panel.update(inputs);
                        stage.act();
                        ScreenUtils.clear(.1f, .1f, .1f, 1);
                        stage.draw();
                        TextButton model = tuning.atmosphereRows().findActor("tuning-environment-image");
                        if (step == 0) {
                            click(stage, "tuning-atmosphere-tab");
                            step++;
                        } else if (step == 1) {
                            click(stage, "tuning-environment-image");
                            assertTrue(model.isDisabled());
                            step++;
                        } else if (step == 2 && opened.get() == 1 && !model.isDisabled()) {
                            assertNull(tuning.atmosphereOptions().environmentImage(), "initial Cancel keeps generated lighting");
                            click(stage, "tuning-environment-image");
                            step++;
                        } else if (step == 3 && !model.isDisabled() && opened.get() == 2) {
                            selected = tuning.atmosphereOptions().environmentImage();
                            assertNotNull(selected, "approved image is loaded back on the render thread");
                            assertEquals("lighting.png", selected.name);
                            assertEquals(GpuColor.linear(128 / 255f), selected.upwardIrradiance().r, .00001);
                            click(stage, "tuning-environment-image");
                            step++;
                        } else if (step == 4 && opened.get() == 3 && !model.isDisabled()) {
                            assertSame(selected, tuning.atmosphereOptions().environmentImage(), "Cancel keeps selected lighting");
                            UiButton shown = stage.getRoot().findActor("tuning-environment-image");
                            assertFalse(shown.isDisabled(), "the picker can be opened again");
                            click(stage, "tuning-environment-clear");
                            assertNull(tuning.atmosphereOptions().environmentImage());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { fail(error); }
                }

                private void fail(Throwable error) { failure.compareAndSet(null, error); Gdx.app.exit(); }

                @Override public void dispose() {
                    if (stage != null) { stage.dispose(); }
                    if (kit != null) { kit.dispose(); }
                    if (skin != null) { skin.dispose(); }
                }
            }, GpuBoardWindow.configuration(false));
        } finally {
            Toolkit.getDefaultToolkit().removeAWTEventListener(answer);
            SwingUtilities.invokeAndWait(() -> { if (openDialog.get() != null) { openDialog.get().dispose(); } });
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
        assertEquals(3, opened.get());
    }

    /** Click the real displayed control, including scrolling it into the panel's viewport. */
    private static void click(Stage stage, String name) {
        Actor actor = stage.getRoot().findActor(name);
        assertNotNull(actor, name);
        for (Group parent = actor.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane page) {
                Vector2 corner = actor.localToAscendantCoordinates(page.getActor(), new Vector2());
                page.scrollTo(corner.x, corner.y, actor.getWidth(), actor.getHeight(), false, true);
                page.updateVisualScroll();
                stage.draw();
                break;
            }
        }
        Vector2 point = stage.stageToScreenCoordinates(
              actor.localToStageCoordinates(new Vector2(actor.getWidth() / 2, actor.getHeight() / 2)));
        stage.touchDown((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
        stage.touchUp((int) point.x, (int) point.y, 0, Input.Buttons.LEFT);
    }
}
