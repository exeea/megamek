/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises free flight through the real renderer, tuning controls and native input multiplexer. */
@Tag("on-demand")
class GpuFreeFlightSmokeTest {
    @Test
    void freeFlightControlsRenderAndRestoreTheTacticalViewWithoutIssuingOrders() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create()) {
            GpuBoardSource source = mock(GpuBoardSource.class);
            source.uiPreferences = fixture.source.uiPreferences;
            source.phaseStatus = fixture.source.phaseStatus;
            when(source.takeFrame()).thenAnswer(invocation -> fixture.source.takeFrame());
            new Lwjgl3Application(new GpuBattleView(source) {
                private Vector3 eye;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (frames() == 20) {
                            GpuBoardTestUi.click("tuning");
                        } else if (frames() == 22) {
                            assertNull(GpuBoardTestUi.stage().getRoot().findActor("tuning-perspective"));
                            GpuBoardTestUi.click("tuning-free-flight");
                            assertTrue(boardCamera.firstPerson());
                            assertTrue(boardCamera.perspective());
                            eye = boardCamera.camera.position.cpy();
                            setFov(20);
                            assertEquals(eye, boardCamera.camera.position);
                        } else if (frames() == 26) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-fov-20.png"));
                            setFov(100);
                            assertEquals(eye, boardCamera.camera.position);
                        } else if (frames() == 30) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-fov-100.png"));
                            setFov(60);
                            GpuBoardTestUi.click("tuning");
                            var processor = Gdx.input.getInputProcessor();
                            Vector3 direction = boardCamera.camera.direction.cpy();
                            processor.touchDown(300, 300, 0, Input.Buttons.RIGHT);
                            processor.touchDragged(360, 340, 0);
                            processor.touchUp(360, 340, 0, Input.Buttons.RIGHT);
                            assertEquals(eye, boardCamera.camera.position, "Mouse look rotates around the eye");
                            assertFalse(direction.epsilonEquals(boardCamera.camera.direction, .001f));
                            assertFalse(GpuBoardTestUi.stage().getRoot().<Table>findActor("tactical-menu").isVisible());
                            boardCamera.look(0, 90 - boardCamera.tilt());
                        } else if (frames() == 34) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-horizon.png"));
                            boardCamera.look(0, -20);
                            eye = boardCamera.camera.position.cpy();
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.E);
                        } else if (frames() == 38) {
                            assertTrue(eye.dst(boardCamera.camera.position) > .01f);
                            assertTrue(boardCamera.camera.position.z > eye.z, "E ascends while W flies forward");
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.W);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.E);
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            eye = boardCamera.camera.position.cpy();
                            GpuBoardTestUi.click("tuning");
                        } else if (frames() == 42) {
                            assertTrue(eye.dst(boardCamera.camera.position) > .01f, "Opening a panel must allow held flight");
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.W);
                            eye = boardCamera.camera.position.cpy();
                            GpuBoardTestUi.click("tuning");
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.W);
                            pause();
                        } else if (frames() == 46) {
                            assertEquals(eye, boardCamera.camera.position, "Losing focus stops held flight");
                            GpuBoardTestUi.click("top");
                            assertFalse(boardCamera.firstPerson());
                        } else if (frames() == 48) {
                            assertFalse(GpuBoardTestUi.stage().getRoot().<CheckBox>findActor("tuning-free-flight").isChecked());
                            GpuBoardTestUi.click("tuning");
                            GpuBoardTestUi.click("tuning-free-flight");
                            GpuBoardTestUi.click("tuning-defaults");
                            assertFalse(boardCamera.firstPerson());
                            assertFalse(boardCamera.perspective());
                            assertEquals(BoardCamera.DEFAULT_FIELD_OF_VIEW, boardCamera.fieldOfView());
                            boardCamera.reset(fixture.source.takeFrame().scene());
                        } else if (frames() == 52) {
                            GpuBoardTestUi.capture(new File(output, "free-flight-restored-tactical.png"));
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
            verify(source, never()).key(anyInt(), anyBoolean(), anyInt());
            assertEquals(0, fixture.clicks.get(), "Camera navigation must not issue gameplay orders");
        }
    }
}
