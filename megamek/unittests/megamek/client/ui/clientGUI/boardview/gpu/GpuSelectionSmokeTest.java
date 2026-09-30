/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Component;
import java.io.File;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.client.ui.panels.phaseDisplay.MovementDisplay;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real framebuffer checks for outline occlusion and the band's width, bob and support plane in both cameras. */
@Tag("on-demand")
class GpuSelectionSmokeTest {
    @Test
    void hoverOutlineRemainsFaintBehindTheMinesRampInBothCameras() throws Exception {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 Mines 1.board"));
        Coords coords = new Coords(6, 7);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board)) {
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1200, 900);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                private int tick;

                @Override
                public void render() {
                    try {
                        // Capture the background without a hover, then draw it against exactly that scene depth.
                        var hover = GpuBattleView.class.getDeclaredField("hovered");
                        hover.setAccessible(true);
                        hover.set(this, null);
                        super.render();
                        if (frames() == 0) { return; }
                        if (++tick == 1) {
                            boardCamera.setIsometric(true);
                            boardCamera.camera.zoom = .20f;
                            boardCamera.center(BoardGeometry.center(coords, -1));
                        } else if (tick == 2) {
                            boardCamera.setIsometric(false);
                        } else {
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }

                @Override
                void renderStage(String stage) {
                    if (tick == 0 || !"annotations and UI".equals(stage)) { return; }
                    try {
                        checkHoverOcclusion(this, coords);
                    } catch (ReflectiveOperationException error) {
                        throw new IllegalStateException(error);
                    }
                }
            }, config);
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "ground", "roof", "airborne" })
    void selectedBandFollowsTheUnitPlaneAndRemainsWideInBothCameras(String scenario) throws Exception {
        Board board = new Board(7, 7);
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                board.setHex(new Coords(x, y), new Hex(0));
            }
        }
        Coords coords = new Coords(3, 3);
        board.setHex(coords, new Hex(1, scenario.equals("roof") ? "building:1;bldg_elev:2;bldg_cf:100" : "", ""));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        try (var fixture = GpuBoardFixture.create(board)) {
            boolean airborne = scenario.equals("airborne");
            var entity = airborne
                  ? new MekFileParser(new File("testresources/megamek/common/units/Cobra Transport VTOL.blk")).getEntity()
                  : fixture.entity;
            SwingUtilities.invokeAndWait(() -> {
                if (airborne) {
                    fixture.entity.setPosition(new Coords(1, 1));
                    entity.setId(2);
                    entity.setOwner(fixture.player);
                    entity.setDeployed(true);
                    fixture.game.addEntity(entity, false);
                }
                entity.setPosition(coords);
                entity.setElevation(airborne ? 3 : scenario.equals("roof") ? 2 : 0);
                var panel = mock(MovementDisplay.class);
                when(panel.currentEntity()).thenReturn(entity);
                when(panel.getComponents()).thenReturn(new Component[0]);
                when(panel.getActionButtons()).thenReturn(List.of());
                when(panel.getCompletionButtons()).thenReturn(List.of());
                fixture.panel = panel;
                fixture.source.refresh();
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                @Override
                public void render() {
                    try {
                        super.render();
                        if (frames() == 0) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (frames() == 1) {
                            ((UnitPlayback) field(this, "playback")).togglePaused();
                            clock(this, 0);
                            boardCamera.setIsometric(true);
                            boardCamera.center(BoardGeometry.center(coords, 1 + entity.getElevation()));
                            boardCamera.zoom(.35f);
                        } else {
                            var scene = (BoardScene) field(this, "scene");
                            assertEquals(entity.getId(), scene.selectedId());
                            var unit = scene.units().stream().filter(candidate -> candidate.id() == scene.selectedId()).findFirst().orElseThrow();
                            assertEquals(airborne, unit.airborne());
                            assertEquals(1 + entity.getElevation(), unit.location().elevation(), .001f);
                            var pose = (UnitFootprint.Pose) ((Map<?, ?>) field(this, "unitFootprints")).get(unit);
                            var instance = (ModelInstance) ((Map<?, ?>) field(this, "unitInstances")).get(unit.id() + ":" + unit.part());
                            float time = (float) field(this, "hoverClock");
                            float expectedHover = airborne ? hoverOffset(time, unit.id(), unit.part()) : 0;
                            assertEquals(unit.location().elevation() * BoardGeometry.LEVEL + expectedHover, pose.position().z, .001f);
                            assertEquals(instance.transform.getTranslation(new Vector3()).z, pose.outlinePoint(coords, 0).z, .001f,
                                  "The band must use the model's plane, including cosmetic flight hover");
                            checkBandPixels(this, pose, time);
                            String angle = frames() == 4 ? "top" : frames() == 2 ? "isometric-low" : "isometric-high";
                            GpuBoardTestUi.capture(new File(output, "selection-" + scenario + "-" + angle + ".png"));
                            if (frames() == 2) {
                                clock(this, SELECTION_BOB_PERIOD_SECONDS / 2);
                            } else if (frames() == 3) {
                                boardCamera.setIsometric(false);
                            } else {
                                Gdx.app.exit();
                            }
                        }
                    } catch (Throwable error) {
                        failure.compareAndSet(null, error);
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        }
    }

    private static void checkHoverOcclusion(GpuBattleView view, Coords coords) throws ReflectiveOperationException {
        var camera = view.boardCamera.camera;
        var ui = (GpuBoardUi) field(view, "ui");
        var scene = (BoardScene) field(view, "scene");
        float z = BoardTacticalGeometry.floatingZ(scene, coords);
        Vector3 center = BoardGeometry.center(coords, 0);
        Vector3 pointer = camera.project(new Vector3(center.x, center.y, z), 0, ui.bottomPixels(),
              camera.viewportWidth, camera.viewportHeight);
        Input original = Gdx.input;
        Input input = mock(Input.class, delegatesTo(original));
        when(input.getX()).thenReturn(Math.round(pointer.x));
        when(input.getY()).thenReturn(Gdx.graphics.getHeight() - Math.round(pointer.y) - 1);
        Gdx.input = input;
        Pixmap before = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            FloatBuffer depth = selectionDepth(before.getWidth(), before.getHeight());
            var hover = GpuBattleView.class.getDeclaredField("hovered");
            hover.setAccessible(true);
            hover.set(view, coords);
            var draw = GpuBattleView.class.getDeclaredMethod("renderSelectionOutlines");
            draw.setAccessible(true);
            draw.invoke(view);
            assertEquals(depth, selectionDepth(before.getWidth(), before.getHeight()), "Outlines must preserve scene depth");
            var state = BufferUtils.newIntBuffer(1);
            Gdx.gl.glGetIntegerv(GL20.GL_DEPTH_FUNC, state);
            assertEquals(GL20.GL_LEQUAL, state.get(0), "Restore depth testing for later rendering");
            Gdx.gl.glGetIntegerv(GL20.GL_DEPTH_WRITEMASK, state);
            assertEquals(1, state.get(0), "Restore depth writes for the next frame");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            Pixmap after = Pixmap.createFromFrameBuffer(0, 0, before.getWidth(), before.getHeight());
            try {
                float sx = (float) before.getWidth() / Gdx.graphics.getWidth();
                float sy = (float) before.getHeight() / Gdx.graphics.getHeight();
                int faint = 0, bright = 0;
                for (int edge = 0; edge < 6; edge++) {
                    for (float along : new float[] { .25f, .5f, .75f }) {
                        Vector3 point = BoardGeometry.corner(coords, 0, edge).lerp(BoardGeometry.corner(coords, 0, edge + 1), along);
                        BoardGeometry.inset(point, center, GpuBattleView.HOVER_HEX_INSET);
                        point.z = z;
                        camera.project(point, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                        int px = Math.round(point.x * sx), py = Math.round(point.y * sy);
                        int x = px, y = py, difference = 0;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dy = -2; dy <= 2; dy++) {
                                int change = (after.getPixel(px + dx, py + dy) >>> 24) - (before.getPixel(px + dx, py + dy) >>> 24);
                                if (change > difference) { difference = change; x = px + dx; y = py + dy; }
                            }
                        }
                        assertTrue(difference > 5, "Every sampled part of the inset outline must remain visible; edge " + edge);
                        // Find the plane's depth at the actual rasterized pixel, including the camera's tilt.
                        var ray = camera.getPickRay((x + .5f) / sx, Gdx.graphics.getHeight() - (y + .5f) / sy,
                              0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                        Vector3 fragment = new Vector3(ray.origin).mulAdd(ray.direction, (z - ray.origin.z) / ray.direction.z);
                        camera.project(fragment, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                        boolean hidden = fragment.z > depth.get(y * before.getWidth() + x);
                        float alpha = hidden ? GpuBattleView.SELECTION_OCCLUDED_ALPHA : 1;
                        for (int shift : new int[] { 24, 16, 8 }) {
                            int background = (before.getPixel(x, y) >>> shift) & 255;
                            int actual = (after.getPixel(x, y) >>> shift) & 255;
                            assertEquals(background + (255 - background) * alpha, actual, 3,
                                  "Only hidden portions should blend faintly; edge " + edge + ", hidden=" + hidden);
                        }
                        if (hidden) { faint++; } else { bright++; }
                    }
                }
                assertTrue(faint > 0 && bright > 0, "Exercise both the buried and exposed parts of the Mines ramp outline");
            } finally {
                after.dispose();
            }
        } finally {
            before.dispose();
            Gdx.input = original;
        }
    }

    private static FloatBuffer selectionDepth(int width, int height) {
        FloatBuffer depth = BufferUtils.newFloatBuffer(width * height);
        Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_DEPTH_COMPONENT, GL20.GL_FLOAT, depth);
        return depth;
    }

    private static void checkBandPixels(GpuBattleView view, UnitFootprint.Pose pose, float time) throws ReflectiveOperationException {
        var camera = view.boardCamera.camera;
        var ui = (GpuBoardUi) field(view, "ui");
        float scaleX = (float) Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth();
        float scaleY = (float) Gdx.graphics.getBackBufferHeight() / Gdx.graphics.getHeight();
        Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int wideEdges = 0;
            Coords coords = pose.unit().location().coords();
            for (int edge = 0; edge < 6; edge++) {
                int cyan = 0;
                for (float across : new float[] { .2f, .5f, .8f }) {
                    float inset = BoardGeometry.MARKER_INSET + across * GpuBattleView.SELECTION_BAND_WIDTH;
                    Vector3 point = pose.outlinePoint(coords, edge, inset).lerp(pose.outlinePoint(coords, edge + 1, inset), .5f);
                    point.z += GpuBattleView.selectionBob(time);
                    camera.project(point, 0, ui.bottomPixels(), camera.viewportWidth, camera.viewportHeight);
                    int rgba = pixels.getPixel(Math.round(point.x * scaleX), Math.round(point.y * scaleY));
                    if ((rgba >>> 24) < 32 && ((rgba >>> 16) & 255) > 220 && ((rgba >>> 8) & 255) > 220) {
                        cyan++;
                    }
                }
                if (cyan == 3) { wideEdges++; }
            }
            assertTrue(wideEdges >= 3, "At least three edges must visibly span the filled band's width at its animated height; found " + wideEdges);
        } finally {
            pixels.dispose();
        }
    }

    private static void clock(GpuBattleView view, float time) throws ReflectiveOperationException {
        var field = GpuBattleView.class.getDeclaredField("hoverClock");
        field.setAccessible(true);
        field.setFloat(view, time);
    }

    private static Object field(GpuBattleView view, String name) throws ReflectiveOperationException {
        var field = GpuBattleView.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(view);
    }
}
