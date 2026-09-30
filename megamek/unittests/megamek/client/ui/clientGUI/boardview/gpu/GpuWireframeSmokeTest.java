/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.renderReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The Camera menu's wireframe view on the real battle view, and its return to the shaded board. */
@Tag("on-demand")
class GpuWireframeSmokeTest {
    @Test
    void cameraToggleDrawsBoardAsGreenLinesAroundShadedUnits() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        verify(view);
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Wireframe view failed", failure.get()); }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view) throws Exception {
        var ui = (GpuBoardUi) field(view, "ui");
        assertTrue(GpuWireframe.supported(), "Desktop OpenGL has polygon line mode");
        Mix shaded = render(view, "wireframe-off.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Shaded frames");
        assertTrue(shaded.other > .6, "The shaded board is neither dark nor line green: " + shaded);

        toggle("camera-wireframe");
        assertTrue(ui.wireframe());
        Mix wire = render(view, "wireframe-on.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe frames");
        assertTrue(wire.line > .03, "Green edges cover the board: " + wire);
        assertTrue(wire.dark > .25, "The hidden-line fill leaves a dark ground between edges: " + wire);
        assertTrue(wire.other < .15, "Only units and overlays keep other colors: " + wire);

        // Units keep their shaded models. A polygon mode left in line mode would also outline the next frame's
        // composite quad, and the unit would never reach the window.
        var unit = ((Map<String, ModelInstance>) field(view, "unitInstances")).get("1:-1");
        Vector3 body = unit.calculateBoundingBox(new BoundingBox()).mul(unit.transform).getCenter(new Vector3());
        view.boardCamera.center(body);
        view.boardCamera.zoom(.25f);
        render(view, "wireframe-unit.png");
        Vector3 point = view.boardCamera.camera.project(new Vector3(body), 0, ui.bottomPixels(),
              view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        float density = (float) Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth();
        int x = Math.round(point.x * density), y = Math.round(point.y * density);
        Mix atUnit = classify(x - 4, y - 4, x + 5, y + 5, 1);
        assertTrue(atUnit.other > .6, "The unit is shaded, not drawn in lines: " + atUnit);
        view.boardCamera.zoom(4);

        view.boardCamera.setIsometric(false);
        toggle("camera-overview-icons");
        render(view, "wireframe-tactical.png");
        assertTrue(((GpuUnitIcons) field(view, "unitIcons")).active(), "The top view shows flat icons");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe with the Tactical View's flat icons");
        toggle("camera-overview-icons");

        toggle("camera-wireframe");
        assertFalse(ui.wireframe());
        Mix restored = render(view, "wireframe-restored.png");
        assertTrue(restored.other > .6, "Leaving the wireframe restores shading: " + restored);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Restored frames");
    }

    /** Click a Camera menu row and close the menu, checking that the row's checkbox follows the setting. */
    private static void toggle(String row) {
        GpuBoardTestUi.click("camera");
        CheckBox check = GpuBoardTestUi.stage().getRoot().findActor(row + "-check");
        boolean before = check.isChecked();
        GpuBoardTestUi.click(row);
        assertEquals(!before, GpuBoardTestUi.stage().getRoot().<CheckBox>findActor(row + "-check").isChecked());
        GpuBoardTestUi.click("camera");
    }

    private record Mix(double line, double dark, double other) { }

    /** Settle the board, then classify the board area outside the HUD bands, as {@link GpuBoardTestUi#capture} samples it. */
    private static Mix render(GpuBattleView view, String name) throws Exception {
        renderReady(view);
        GpuBoardTestUi.present(view);
        view.render();
        File directory = new File(System.getProperty("megamek.gpu.screenshots"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
        return classify(20, 80, Gdx.graphics.getBackBufferWidth() - 290, Gdx.graphics.getBackBufferHeight() - 70, 2);
    }

    /** Line green, near-black ground, or anything else, within back-buffer pixels whose rows count from the bottom. */
    private static Mix classify(int left, int bottom, int right, int top, int step) {
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            int line = 0;
            int dark = 0;
            int total = 0;
            for (int y = bottom; y < top; y += step) {
                for (int x = left; x < right; x += step) {
                    int pixel = image.getPixel(x, y);
                    int red = pixel >>> 24;
                    int green = (pixel >>> 16) & 255;
                    int blue = (pixel >>> 8) & 255;
                    if (green > 150 && green > red + 60 && green > blue + 40) {
                        line++;
                    } else if (Math.max(red, Math.max(green, blue)) < 30) {
                        dark++;
                    }
                    total++;
                }
            }
            return new Mix((double) line / total, (double) dark / total, (double) (total - line - dark) / total);
        } finally {
            image.dispose();
        }
    }
}
