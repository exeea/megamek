/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.renderReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.RenderableProvider;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import megamek.common.loaders.MekFileParser;
import megamek.common.units.ConvInfantry;
import megamek.common.units.Entity;
import megamek.common.units.EntityMovementMode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The HUD's wireframe utility on the real battle view, and its return to the shaded board. */
@Tag("on-demand")
class GpuWireframeSmokeTest {
    @Test
    void cameraToggleDrawsThermalUnitsAndRestoresShadedUnits() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create()) {
            var infantry = new ConvInfantry();
            infantry.setId(2);
            infantry.setChassis("Thermal infantry");
            infantry.setMovementMode(EntityMovementMode.INF_LEG);
            infantry.initializeInternal(28, ConvInfantry.LOC_INFANTRY);
            Entity armor = new MekFileParser(new File("testresources/megamek/common/units/Elemental BA [Laser] (Sqd5).blk")).getEntity();
            armor.setId(3);
            SwingUtilities.invokeAndWait(() -> {
                for (var troopers : List.of(infantry, armor)) {
                    troopers.setOwner(fixture.player);
                    troopers.setPosition(new Coords(7, troopers.getId() + 3));
                    troopers.setDeployed(true);
                    fixture.game.addEntity(troopers, false);
                }
                fixture.source.refresh();
            });
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var view = new GpuBattleView(fixture.source);
                    try {
                        view.create();
                        verify(view, fixture);
                        verifySprite();
                    } catch (Throwable error) { failure.set(error); }
                    finally { view.dispose(); Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
            if (failure.get() != null) { throw new AssertionError("Wireframe view failed", failure.get()); }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verify(GpuBattleView view, GpuBoardFixture fixture) throws Exception {
        var tuning = GpuBoardTestUi.tuning(view);
        assertTrue(GpuWireframe.supported(), "Desktop OpenGL has polygon line mode");
        Mix shaded = render(view, "wireframe-off.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Shaded frames");
        assertTrue(shaded.other > .6, "The shaded board is neither dark nor line green: " + shaded);
        assertNull(field(((GpuWireframe) field(view, "wireframe")).units, "batch"),
              "Normal startup must not create the thermal batch or compile its shaders");

        toggle("utility-wireframe");
        assertTrue(tuning.wireframe());
        Mix wire = render(view, "wireframe-on.png");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe frames");
        assertTrue(wire.line > .03, "Green edges cover the board: " + wire);
        assertTrue(wire.dark > .25, "The hidden-line fill leaves a dark ground between edges: " + wire);
        assertTrue(wire.other < .15, "Only units and overlays keep other colors: " + wire);

        // The same posed model must change temperature without corrupting its team-color outline or paint.
        var unit = ((Map<String, ModelInstance>) field(view, "unitInstances")).get("1:-1");
        Vector3 body = unit.calculateBoundingBox(new BoundingBox()).mul(unit.transform).getCenter(new Vector3());
        view.boardCamera.center(body);
        float originalZoom = view.boardCamera.camera.zoom;
        view.boardCamera.zoom(.08f);
        render(view, "wireframe-unit.png");
        // The cosmetic scatter draws no lines (the user's decision of 2026-10-03).
        Set<Object> scatter = visibleScatter(view);
        assertFalse(scatter.isEmpty(), "The board around the unit has scatter in view");
        List<Object> drawn = new ArrayList<>();
        var recording = new ModelBatch() {
            @Override public void render(RenderableProvider provider) { drawn.add(provider); }
        };
        try {
            ((GpuTerrain) field(view, "terrain")).renderDepth(view.boardCamera.camera, List.of(), recording);
        } finally { recording.dispose(); }
        assertFalse(drawn.isEmpty(), "The wireframe draws the board");
        assertTrue(drawn.stream().noneMatch(scatter::contains), "The wireframe draws no scatter");
        Vector3 point = view.boardCamera.camera.project(new Vector3(body), 0, 0,
              view.boardCamera.camera.viewportWidth, view.boardCamera.camera.viewportHeight);
        float density = (float) Gdx.graphics.getBackBufferWidth() / Gdx.graphics.getWidth();
        int x = Math.round(point.x * density), y = Math.round(point.y * density);
        Mix atUnit = classify(x - 4, y - 4, x + 5, y + 5, 1);
        assertTrue(atUnit.other > .6, "The unit fills its silhouette: " + atUnit);
        Color cold = colorAt(x, y);
        assertTrue(cold.b > cold.r + .2f, "Zero tracked heat must look blue/violet: " + cold);
        Object outline = unit.userData;
        toggle("utility-wireframe");
        render(view, "wireframe-unit-shaded.png");
        Color shadedUnit = colorAt(x, y);
        toggle("utility-wireframe");
        SwingUtilities.invokeAndWait(() -> { fixture.entity.heat = 30; fixture.source.refresh(); });
        render(view, "wireframe-unit-hot.png");
        Color hot = colorAt(x, y);
        assertTrue(hot.r > cold.r + .3f && hot.g > cold.g + .2f,
              "Actual game heat must change the model toward yellow/white: " + hot);
        assertSame(outline, unit.userData, "Thermal rendering must restore the outline data");
        assertSame(unit, ((Map<String, ModelInstance>) field(view, "unitInstances")).get("1:-1"),
              "Changing heat reuses the posed model");
        toggle("utility-wireframe");
        render(view, "wireframe-unit-shaded-restored.png");
        Color restoredUnit = colorAt(x, y);
        assertEquals(shadedUnit.r, restoredUnit.r, .08f);
        assertEquals(shadedUnit.g, restoredUnit.g, .08f);
        assertEquals(shadedUnit.b, restoredUnit.b, .08f);
        toggle("utility-wireframe");

        for (int id : new int[] { 2, 3 }) {
            var instance = ((Map<String, ModelInstance>) field(view, "unitInstances")).get(id + ":-1");
            view.boardCamera.center(UnitBounds.world(instance).getCenter(new Vector3()));
            view.boardCamera.zoom((id == 2 ? .06f : .10f) / view.boardCamera.camera.zoom);
            render(view, id == 2 ? "wireframe-infantry.png" : "wireframe-battle-armor.png");
            assertEquals(-1, ((BoardScene) field(view, "scene")).units().stream().filter(value -> value.id() == id)
                  .findFirst().orElseThrow().heat());
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Thermal troopers");
        }
        view.boardCamera.zoom(originalZoom / view.boardCamera.camera.zoom);

        view.boardCamera.setIsometric(false);
        toggle("utility-tactical");
        render(view, "wireframe-tactical.png");
        assertTrue(((GpuUnitIcons) field(view, "unitIcons")).active(), "The top view shows flat icons");
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Wireframe with the Tactical View's flat icons");
        toggle("utility-tactical");

        toggle("utility-wireframe");
        assertFalse(tuning.wireframe());
        Mix restored = render(view, "wireframe-restored.png");
        assertTrue(restored.other > .6, "Leaving the wireframe restores shading: " + restored);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Restored frames");
    }

    /** The scatter that the board's visible chunks show at the camera's detail. */
    private static Set<Object> visibleScatter(GpuBattleView view) throws Exception {
        Set<Object> scatter = new HashSet<>();
        for (Object chunk : (List<?>) field(field(view, "terrain"), "chunks")) {
            if ((boolean) field(chunk, "scatterVisible")
                  && view.boardCamera.camera.frustum.boundsInFrustum((BoundingBox) field(chunk, "bounds"))) {
                scatter.addAll((List<?>) field(chunk, "scatter"));
            }
        }
        return scatter;
    }

    /** Click a HUD utility: the wireframe view's or the Tactical View's switch. */
    private static void toggle(String utility) {
        GpuBoardTestUi.click(utility);
    }

    private record Mix(double line, double dark, double other) { }

    /** Flat fallback artwork must retain its alpha cutout and respond to heat despite having no vertical extent. */
    private static void verifySprite() {
        var pixels = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
        pixels.setColor(Color.WHITE);
        pixels.fillRectangle(0, 0, 1, 2);
        var texture = new Texture(pixels);
        pixels.dispose();
        var model = GpuUnitModel.sprite(new BoardScene.Pixels(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)),
              new TextureRegion(texture));
        var thermal = new GpuThermalUnits();
        var buffer = new FrameBuffer(Pixmap.Format.RGBA8888, 64, 64, true);
        try {
            var camera = new OrthographicCamera(2, 2);
            camera.position.set(0, 0, 2);
            camera.lookAt(0, 0, 0);
            camera.near = .1f;
            camera.far = 10;
            camera.update();
            var point = new BoardScene.Waypoint(new Coords(0, 0), 0, 0);
            var unit = new BoardScene.Unit(1, -1, "Sprite", point, null, false, null, 1, false, null, 0,
                  List.of(point.coords()), null, 30);
            buffer.begin();
            ScreenUtils.clear(0, 0, 0, 0, true);
            thermal.add(model.instance, unit, UnitBounds.world(model.instance));
            thermal.render(camera);
            Pixmap rendered = Pixmap.createFromFrameBuffer(0, 0, 64, 64);
            try {
                Color body = new Color(rendered.getPixel(16, 32));
                assertTrue(body.r > .7f && body.g > .3f, "A hot flat sprite uses warm colors: " + body);
                assertEquals(0, rendered.getPixel(48, 32), "Transparent sprite pixels must leave the target untouched");
            } finally { rendered.dispose(); buffer.end(); }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "Thermal sprite fallback");
        } finally {
            buffer.dispose();
            thermal.dispose();
            model.dispose();
            texture.dispose();
        }
    }

    private static Color colorAt(int x, int y) {
        Pixmap image = Pixmap.createFromFrameBuffer(x - 4, y - 4, 9, 9);
        try {
            float r = 0, g = 0, b = 0;
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) {
                    int pixel = image.getPixel(column, row);
                    r += pixel >>> 24;
                    g += (pixel >>> 16) & 255;
                    b += (pixel >>> 8) & 255;
                }
            }
            return new Color(r / (81 * 255), g / (81 * 255), b / (81 * 255), 1);
        } finally { image.dispose(); }
    }

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
