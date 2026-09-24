/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the tuning controls through the real board renderer, including both ends of the lens range. */
@Tag("on-demand")
class GpuPerspectiveSmokeTest {
    @Test
    void tuningRendersPerspectiveAndRestoresOrthographicWithoutGlErrors() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create()) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (frames() == 20) {
                            GpuBoardTestUi.click("tuning");
                        } else if (frames() == 22) {
                            GpuBoardTestUi.click("tuning-perspective");
                            assertTrue(boardCamera.perspective());
                            setFov(20);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        } else if (frames() == 26) {
                            GpuBoardTestUi.capture(new File(output, "perspective-fov-20.png"));
                            setFov(100);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        } else if (frames() == 30) {
                            GpuBoardTestUi.capture(new File(output, "perspective-fov-100.png"));
                            boardCamera.setIsometric(false);
                            setFov(60);
                            boardCamera.fit(fixture.source.takeFrame().scene());
                        } else if (frames() == 34) {
                            GpuBoardTestUi.capture(new File(output, "perspective-top.png"));
                            GpuBoardTestUi.click("tuning-defaults");
                            assertFalse(boardCamera.perspective());
                            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, boardCamera.fieldOfView());
                            boardCamera.reset(fixture.source.takeFrame().scene());
                        } else if (frames() == 38) {
                            GpuBoardTestUi.capture(new File(output, "perspective-restored-orthographic.png"));
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                private void setFov(float degrees) {
                    GpuBoardTestUi.stage().getRoot().<Slider>findActor("tuning-camera-fov").setValue(degrees);
                    assertEquals(degrees, boardCamera.fieldOfView());
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError(failure.get()); }
            assertEquals(0, fixture.clicks.get(), "Camera tuning must not issue gameplay orders");
        }
    }
}
