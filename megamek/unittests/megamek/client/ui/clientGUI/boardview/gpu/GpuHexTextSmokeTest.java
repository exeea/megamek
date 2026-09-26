/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
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
            checkStaticMeshes(batch);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            object.dispose(); batch.dispose(); skin.dispose(); labels.dispose(); atmosphere.dispose(); terrain.dispose();
        }
    }

    private record LegacyChunk(BitmapFontCache glyphs, BoundingBox bounds) { }

    /** Compares the same font-cache draws before and after static upload, including alpha and atlas-page ordering. */
    private static void checkStaticMeshes(SpriteBatch batch) {
        GpuHexText labels = new GpuHexText();
        Texture first = texture(0xffffffff), second = texture(0x5fff8fc0), depth = texture(0xffffffff);
        BitmapFont font = font(first, second);
        GLProfiler profiler = new GLProfiler(Gdx.graphics);
        String meshesBefore = Mesh.getManagedStatus();
        try {
            BoardCamera camera = new BoardCamera();
            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            camera.setIsometric(false);
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            BoardScene scene = textScene(24, "ABAB");
            assertEquals(1, font.getRegions().size);
            labels.update(scene, font, coords -> null);
            assertEquals(2, font.getRegions().size, "Later chunks introduce another incremental font atlas page");
            List<LegacyChunk> legacy = legacy(scene, font);
            assertVertices(labels, legacy, font);
            ShaderProgram previous = batch.getShader();
            camera.fit(scene);
            parity(labels, legacy, batch, camera, depth);
            assertSame(previous, batch.getShader(), "The annotation batch keeps its shader after direct mesh rendering");
            assertArrayEquals(new Matrix4().val, batch.getTransformMatrix().val,
                  "Later annotations retain their identity transform");
            ScreenUtils.clear(.1f, .2f, .3f, 1, true);
            batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0,
                  Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight()));
            batch.begin();
            batch.draw(first, 12, 12, 32, 32);
            batch.end();
            Pixmap annotation = pixels();
            try { assertEquals(0xffffffff, annotation.getPixel(28, 28), "The restored SpriteBatch still draws annotations"); }
            finally { annotation.dispose(); }
            camera.camera.zoom = .2f;
            camera.center(BoardGeometry.center(new Coords(15, 15), 0));
            long visible = legacy.stream().filter(chunk -> camera.camera.frustum.boundsInFrustum(chunk.bounds())).count();
            assertTrue(visible > 0 && visible < legacy.size(), "The close view must contain culled chunk ranges");
            parity(labels, legacy, batch, camera, depth);

            // One font page over many chunks crosses both signed-short and unsigned-short index boundaries.
            BoardScene large = textScene(48, "AAAAAAAA");
            labels.update(large, font, coords -> null);
            List<Object> pages = pages(labels);
            assertEquals(2, pages.size(), "Consecutive chunks share bounded pages instead of individual draw calls");
            assertVertices(labels, legacy(large, font), font);
            labels.update(large, font, coords -> { throw new AssertionError("Unchanged labels must keep their meshes"); });
            assertSame(pages.getFirst(), pages(labels).getFirst());
            camera.fit(large);
            profiler.enable();
            try {
                ScreenUtils.clear(.1f, .2f, .3f, 1, true);
                profiler.reset();
                labels.render(batch, camera.camera, depth, 0);
                assertEquals(2, profiler.getDrawCalls(), "All visible ranges merge into one draw per static mesh page");
                camera.center(new com.badlogic.gdx.math.Vector3(-10000, 10000, 0));
                profiler.reset();
                labels.render(batch, camera.camera, depth, 0);
                assertEquals(0, profiler.getDrawCalls(), "An off-board view must submit no glyph meshes");
            } finally { profiler.disable(); }
            labels.update(textScene(1, ""), font, coords -> null);
            assertEquals(meshesBefore, Mesh.getManagedStatus(), "Replacing text releases all old static meshes");
            assertTrue(Gdx.gl.glIsTexture(first.getTextureObjectHandle()));
            assertTrue(Gdx.gl.glIsTexture(second.getTextureObjectHandle()), "Font atlas pages are borrowed, not disposed");
        } finally {
            if (profiler.isEnabled()) { profiler.disable(); }
            labels.dispose();
            font.dispose();
            first.dispose(); second.dispose(); depth.dispose();
        }
        assertEquals(meshesBefore, Mesh.getManagedStatus());
    }

    private static void parity(GpuHexText labels, List<LegacyChunk> legacy, SpriteBatch batch, BoardCamera camera,
          Texture depth) {
        ScreenUtils.clear(.1f, .2f, .3f, 1, true);
        labels.render(batch, camera.camera, depth, 0);
        Pixmap actual = pixels();
        // The unchanged text shader/depth uniforms were just set by render. Only the submission path differs.
        ScreenUtils.clear(.1f, .2f, .3f, 1, true);
        ShaderProgram previous = batch.getShader();
        batch.setShader(field(labels, "shader"));
        batch.setProjectionMatrix(camera.camera.combined);
        batch.setTransformMatrix(new Matrix4().setToTranslation(0, 0, .6f * BoardGeometry.HEX_SCALE));
        batch.begin();
        try {
            for (LegacyChunk chunk : legacy) {
                if (camera.camera.frustum.boundsInFrustum(chunk.bounds())) { chunk.glyphs().draw(batch); }
            }
        } finally {
            batch.end();
            batch.setTransformMatrix(new Matrix4());
            batch.setShader(previous);
        }
        Pixmap expected = pixels();
        try {
            int background = actual.getPixel(0, 0);
            boolean ink = false;
            for (int y = 0; y < actual.getHeight() && !ink; y++) {
                for (int x = 0; x < actual.getWidth() && !ink; x++) { ink = actual.getPixel(x, y) != background; }
            }
            assertTrue(ink, "Pixel parity must include rendered glyphs");
            assertTrue(actual.getPixels().equals(expected.getPixels()),
                  "Static glyph meshes must reproduce every pixel of the existing BitmapFontCache draw path");
        } finally { actual.dispose(); expected.dispose(); }
    }

    private static void assertVertices(GpuHexText labels, List<LegacyChunk> chunks, BitmapFont font) {
        FloatArray expected = new FloatArray(), actual = new FloatArray();
        List<Texture> textures = new ArrayList<>();
        for (LegacyChunk chunk : chunks) {
            for (int region = 0; region < chunk.glyphs().getPageCount(); region++) {
                int count = chunk.glyphs().getVertexCount(region);
                if (count == 0) { continue; }
                expected.addAll(chunk.glyphs().getVertices(region), 0, count);
                for (int glyph = 0; glyph < count / 20; glyph++) { textures.add(font.getRegion(region).getTexture()); }
            }
        }
        int glyph = 0;
        for (Object page : pages(labels)) {
            Mesh mesh = field(page, "mesh");
            assertTrue(mesh.getNumVertices() <= 65_532);
            assertEquals(mesh.getNumVertices() / 4 * 6, mesh.getNumIndices());
            float[] vertices = new float[mesh.getNumVertices() * 5];
            mesh.getVertices(vertices);
            actual.addAll(vertices);
            short[] indices = new short[mesh.getNumIndices()];
            mesh.getIndices(indices);
            int[] quad = { 0, 1, 2, 2, 3, 0 };
            for (int i = 0; i < indices.length; i++) {
                assertEquals(i / 6 * 4 + quad[i % 6], Short.toUnsignedInt(indices[i]), "SpriteBatch quad winding");
            }
            for (int i = 0; i < vertices.length / 20; i++) { assertSame(textures.get(glyph++), field(page, "texture")); }
        }
        assertEquals(expected.size, actual.size);
        for (int i = 0; i < expected.size; i++) {
            assertEquals(Float.floatToRawIntBits(expected.items[i]), Float.floatToRawIntBits(actual.items[i]),
                  "Glyph position, packed alpha/color and UV bits must survive upload at float " + i);
        }
    }

    /** Independent copy of the previous single-plane layout/draw input, without static mesh construction. */
    private static List<LegacyChunk> legacy(BoardScene scene, BitmapFont font) {
        Map<Coords, LegacyChunk> chunks = new HashMap<>();
        float scaleX = font.getData().scaleX, scaleY = font.getData().scaleY;
        var color = new com.badlogic.gdx.graphics.Color(font.getColor());
        try {
            for (BoardScene.Tile tile : scene.tiles()) {
                for (BoardView.HexText text : tile.text()) {
                    Coords cell = new Coords(tile.coords().getX() / GpuTerrain.CHUNK_SIZE,
                          tile.coords().getY() / GpuTerrain.CHUNK_SIZE);
                    LegacyChunk chunk = chunks.computeIfAbsent(cell,
                          key -> new LegacyChunk(new BitmapFontCache(font, false), new BoundingBox().inf()));
                    float x = BoardGeometry.centerX(tile.coords()), y = BoardGeometry.centerY(tile.coords());
                    chunk.bounds().ext(x - BoardGeometry.WIDTH, y - BoardGeometry.WIDTH, -1)
                          .ext(x + BoardGeometry.WIDTH, y + BoardGeometry.WIDTH, 1);
                    font.getData().setScale(text.font().getSize2D() / GpuBoardUi.FONT_RESOLUTION);
                    int argb = text.argb();
                    font.setColor((argb >>> 16 & 255) / 255f, (argb >>> 8 & 255) / 255f,
                          (argb & 255) / 255f, (argb >>> 24 & 255) / 255f);
                    GlyphLayout layout = new GlyphLayout(font, text.text());
                    float baseline = y + BoardGeometry.HEIGHT / 2 - text.baseline() * BoardGeometry.HEX_SCALE;
                    chunk.glyphs().addText(layout, x - layout.width / 2,
                          text.fromTop() ? baseline : baseline + layout.height);
                }
            }
        } finally { font.getData().setScale(scaleX, scaleY); font.setColor(color); }
        return List.copyOf(chunks.values());
    }

    private static BoardScene textScene(int size, String text) {
        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, GpuBoardUi.FONT_RESOLUTION);
        List<BoardView.HexText> labels = text.isEmpty() ? List.of()
              : List.of(new BoardView.HexText(text, 22, font, 0xafff70ff, true, 0));
        List<BoardView.HexText> firstPage = text.isEmpty() ? List.of()
              : List.of(new BoardView.HexText(text.replace('B', 'A'), 22, font, 0xafff70ff, true, 0));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, BoardScene.Surface.GRASS,
                      null, null, null, List.of(), x < size / 2 ? firstPage : labels));
            }
        }
        return new BoardScene(0, size, size, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BitmapFont font(Texture first, Texture second) {
        Array<TextureRegion> regions = new Array<>();
        regions.add(new TextureRegion(first));
        BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData() {
            @Override
            public BitmapFont.Glyph getGlyph(char character) {
                if (character == 'B' && super.getGlyph(character) == null) {
                    regions.add(new TextureRegion(second));
                    BitmapFont.Glyph glyph = glyph('B', 1);
                    setGlyph(character, glyph);
                    setGlyphRegion(glyph, regions.peek());
                }
                return super.getGlyph(character);
            }
        };
        data.lineHeight = 16; data.capHeight = 14; data.down = -16; data.spaceXadvance = 7;
        data.setGlyph('A', glyph('A', 0));
        BitmapFont result = new BitmapFont(data, regions, false);
        assertFalse(result.ownsTexture());
        return result;
    }

    private static BitmapFont.Glyph glyph(char character, int page) {
        BitmapFont.Glyph glyph = new BitmapFont.Glyph();
        glyph.id = character; glyph.page = page; glyph.width = 11; glyph.height = 14;
        glyph.xadvance = 7; glyph.yoffset = -14;
        return glyph;
    }

    private static Texture texture(int rgba) {
        Pixmap pixels = new Pixmap(16, 16, Pixmap.Format.RGBA8888);
        try { pixels.setColor(rgba); pixels.fill(); return new Texture(pixels); }
        finally { pixels.dispose(); }
    }

    private static List<Object> pages(GpuHexText labels) {
        Map<?, List<Object>> groups = field(labels, "groups");
        return groups.values().stream().flatMap(List::stream).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object object, String name) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(object);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
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
