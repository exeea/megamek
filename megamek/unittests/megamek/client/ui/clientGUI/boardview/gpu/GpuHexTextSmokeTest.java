/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real glyph pixels, sculpted/scattered geometry and scene depth in both cameras, including a docked viewport. */
@Tag("on-demand")
class GpuHexTextSmokeTest {
    private static final Coords LABELED = new Coords(3, 3);
    private static final int BOTTOM = 67;

    @Test
    void terrainLabelsClearTheirOwnReliefButRemainHiddenBehindForegroundGeometry() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(1200, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    checkLabels();
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Native terrain label readability", failure.get()); }
    }

    private static void checkLabels() {
        GpuTerrain terrain = new GpuTerrain();
        GpuAtmosphere atmosphere = new GpuAtmosphere();
        GpuHexText labels = new GpuHexText();
        GpuBoardSkin skin = new GpuBoardSkin();
        SpriteBatch batch = new SpriteBatch();
        ShapeRenderer object = new ShapeRenderer();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight() - BOTTOM);
            atmosphere.configure(new BoardAtmosphere.Settings(12, 0, 0, 1.5f, 0, 0));
            terrain.setAtmosphere(atmosphere.lighting());
            BoardScene scene = scene(false, true);
            terrain.update(scene);
            var font = skin.skin.getFont("default-font");
            for (boolean isometric : List.of(false, true)) {
                camera.setIsometric(isometric);
                camera.camera.zoom = .20f;
                camera.center(BoardGeometry.center(LABELED, 2));
                terrain.renderShadows(camera.camera, List.of());
                labels.update(scene, font, terrain::roofBounds);
                frame(terrain, atmosphere, camera, scene, null);
                labels.render(batch, camera.camera, atmosphere.depthTexture(), BOTTOM);
                Pixmap actual = pixels();
                capture(isometric ? "iso" : "top");
                // Clear only the destination depth: the identical glyphs now have no possible occluder.
                ScreenUtils.clear(0, 0, 0, 1, true);
                labels.render(batch, camera.camera, atmosphere.depthTexture(), BOTTOM);
                Pixmap expected = pixels();
                try {
                    int total = pink(expected);
                    assertTrue(total > 250, "The fixture must contain substantial, fully opaque coordinate and LEVEL glyphs");
                    int missing = missing(expected, actual);
                    assertTrue(missing <= total * .01, "Own relief must not cut through lettering in "
                          + (isometric ? "isometric" : "top") + ": " + missing + "/" + total);
                    // Negative control: the previous flat depth test must fail on exactly the same rocks and glyphs.
                    labels.update(scene(false, false), font, terrain::roofBounds);
                    frame(terrain, atmosphere, camera, scene, null);
                    labels.render(batch, camera.camera, atmosphere.depthTexture(), BOTTOM);
                    Pixmap flat = pixels();
                    try {
                        assertTrue(missing(expected, flat) > total * .10,
                              "The regression fixture must reproduce substantial clipping with the old flat label plane");
                    } finally { flat.dispose(); }
                } finally { actual.dispose(); expected.dispose(); }
            }

            // A foreground plateau is a different hex even where its wall lies on the exact shared boundary.
            BoardScene cliff = scene(true, true);
            terrain.update(cliff);
            labels.update(cliff, font, terrain::roofBounds);
            camera.setIsometric(true);
            camera.center(BoardGeometry.center(LABELED, 2));
            frame(terrain, atmosphere, camera, cliff, null);
            labels.render(batch, camera.camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap hidden = pixels();
            try { assertEquals(0, pink(hidden), "Terrain labels must not be revealed through a foreground cliff"); }
            finally { hidden.dispose(); }
            capture("occluded-cliff");

            // Same-hex roofs/units above the relief envelope retain their ordinary opaque depth too.
            terrain.update(scene);
            labels.update(scene, font, terrain::roofBounds);
            camera.setIsometric(false);
            camera.center(BoardGeometry.center(LABELED, 2));
            frame(terrain, atmosphere, camera, scene, object);
            labels.render(batch, camera.camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap covered = pixels();
            try { assertEquals(0, pink(covered), "A taller object in the same hex must still occlude its terrain labels"); }
            finally { covered.dispose(); }
            checkUnitOcclusion(terrain, atmosphere, camera, scene, labels, batch);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            object.dispose(); batch.dispose(); skin.dispose(); labels.dispose(); atmosphere.dispose(); terrain.dispose();
        }
    }

    private static void checkUnitOcclusion(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera,
          BoardScene scene, GpuHexText labels, SpriteBatch textBatch) {
        Model model = new ModelBuilder().createBox(BoardGeometry.WIDTH, BoardGeometry.HEIGHT, 2 * BoardGeometry.HEX_SCALE,
              new Material(ColorAttribute.createDiffuse(.1f, .2f, .1f, 1)),
              VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal);
        ModelBatch units = new ModelBatch();
        GpuUnitVisibility visibility = new GpuUnitVisibility();
        try {
            ModelInstance unit = new ModelInstance(model);
            // Above the rocks but below the label's conservative relief-plus-scatter envelope: depth alone is ambiguous.
            unit.transform.setToTranslation(BoardGeometry.centerX(LABELED), BoardGeometry.centerY(LABELED),
                  2 * BoardGeometry.LEVEL + BoardRelief.headroom(scene.tile(LABELED)) + 2 * BoardGeometry.HEX_SCALE);
            atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
            terrain.render(camera.camera, false);
            units.begin(camera.camera);
            units.render(unit);
            units.end();
            atmosphere.end(camera.camera, terrain, scene, BOTTOM);
            visibility.render(camera.camera, List.of(unit), atmosphere.depthTexture(), BOTTOM, .5f, 1);
            assertNotNull(visibility.depthTexture(), "Labels borrow the existing unit capture without a second render pass");
            labels.render(textBatch, camera.camera, atmosphere.depthTexture(), visibility.depthTexture(), BOTTOM);
            Pixmap protectedUnit = pixels();
            try { assertEquals(0, pink(protectedUnit), "Visible unit parts below the relief bound must still occlude text"); }
            finally { protectedUnit.dispose(); }
            labels.render(textBatch, camera.camera, atmosphere.depthTexture(), BOTTOM);
            Pixmap unprotectedUnit = pixels();
            try { assertTrue(pink(unprotectedUnit) > 250, "This low object must exercise the unit-mask exception"); }
            finally { unprotectedUnit.dispose(); }
            visibility.render(camera.camera, List.of(), atmosphere.depthTexture(), BOTTOM, .5f, 1);
            assertNull(visibility.depthTexture(), "A previous unit frame must not hide labels after all units leave");
            visibility.render(camera.camera, List.of(unit), atmosphere.depthTexture(), BOTTOM, 0, 1);
            assertNull(visibility.depthTexture(), "Disabling outlines must not reuse an earlier camera's unit depth");
        } finally { visibility.dispose(); units.dispose(); model.dispose(); }
    }

    private static void frame(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera, BoardScene scene,
          ShapeRenderer object) {
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
        terrain.render(camera.camera, false);
        if (object != null) {
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(true);
            object.setProjectionMatrix(camera.camera.combined);
            object.setTransformMatrix(new Matrix4().setToTranslation(0, 0, 8 * BoardGeometry.LEVEL));
            object.begin(ShapeRenderer.ShapeType.Filled);
            object.setColor(.1f, .2f, .1f, 1);
            object.rect(BoardGeometry.centerX(LABELED) - BoardGeometry.WIDTH / 2,
                  BoardGeometry.centerY(LABELED) - BoardGeometry.HEIGHT / 2, BoardGeometry.WIDTH, BoardGeometry.HEIGHT);
            object.end();
        }
        atmosphere.end(camera.camera, terrain, scene, BOTTOM);
    }

    private static BoardScene scene(boolean cliff, boolean detailedLabels) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(86, 119, 54));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        BoardScene.Pixels ground = new BoardScene.Pixels(image);
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        List<BoardView.HexText> text = List.of(new BoardView.HexText("0404", 10, font, 0xffff00ff, true, 0),
              new BoardView.HexText("LEVEL2", 60, font, 0xffff00ff, false, 0));
        List<BoardScene.Feature> rocks = List.of(
              new BoardScene.Feature("scatter-rock", 0, 21, 0, 6, .6f, 0, BoardScene.FeatureKind.SCATTER),
              new BoardScene.Feature("scatter-rock", 0, -18, 0, 6, .6f, 0, BoardScene.FeatureKind.SCATTER));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean labeled = coords.equals(LABELED);
                tiles.add(new BoardScene.Tile(coords, cliff && y >= 4 ? 8 : 2, -1, false, 0,
                      BoardScene.Surface.GRASS, ground, null, null, null, null,
                      labeled && detailedLabels ? rocks : List.of(), labeled ? text : List.of(),
                      BoardLiquid.NONE, null, detailedLabels));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static Pixmap pixels() {
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int pink(Pixmap pixels) {
        int result = 0;
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) { if (pink(pixels.getPixel(x, y))) { result++; } }
        }
        return result;
    }

    private static boolean pink(int rgba) { return (rgba >>> 24) >= 249 && (rgba >>> 8 & 255) >= 249 && (rgba >>> 16 & 255) <= 6; }

    private static int missing(Pixmap expected, Pixmap actual) {
        int result = 0;
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (pink(expected.getPixel(x, y)) && !pink(actual.getPixel(x, y))) { result++; }
            }
        }
        return result;
    }

    private static void capture(String name) {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        GpuBoardTestUi.capture(new File(output, "terrain-labels-" + name + ".png"));
    }
}
