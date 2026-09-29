/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shared water inputs must survive more chunk-field binds than the driver's texture unit count. */
@Tag("on-demand")
class GpuTerrainTextureBindingSmokeTest {
    @Test
    void pendingArtworkDoesNotRepaintTheDisplayedAtlasSlot() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTextures<String> atlas = new GpuTextures<>();
                try {
                    checkPixmapRows();
                    var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    pixels.setRGB(0, 0, 0xffff0000);
                    atlas.retainReplacedPages();
                    atlas.update(Map.of("hex", new BoardScene.Pixels(pixels)));
                    atlas.publish();
                    var displayed = atlas.region("hex");
                    pixels.setRGB(0, 0, 0xff0000ff);
                    assertEquals(java.util.Set.of("hex"), atlas.updateRegions(Map.of("hex", new BoardScene.Pixels(pixels)), Map.of()));
                    assertNotSame(displayed, atlas.region("hex"));
                    assertEquals(0xff0000ff, rgba(displayed), "The old mesh must still sample its red artwork");
                    assertEquals(0x0000ffff, rgba(atlas.region("hex")), "The pending mesh receives the blue replacement");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { atlas.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Atlas publication consistency", failure.get()); }
    }

    private static int rgba(com.badlogic.gdx.graphics.g2d.TextureRegion region) {
        Texture texture = region.getTexture();
        texture.bind();
        var data = BufferUtils.newByteBuffer(texture.getWidth() * texture.getHeight() * 4);
        org.lwjgl.opengl.GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, data);
        return data.order(java.nio.ByteOrder.BIG_ENDIAN).getInt((region.getRegionY() * texture.getWidth() + region.getRegionX()) * 4);
    }

    @Test
    void canceledAtlasReplacementsRetainOnlyTheDisplayedPages() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTextures<String> atlas = new GpuTextures<>(true);
                try {
                    var pixels = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.SAND).tiles().getFirst().ground();
                    atlas.retainReplacedPages();
                    atlas.update(Map.of("hex", pixels));
                    atlas.publish();
                    Texture displayed = atlas.region("hex").getTexture();
                    int displayedHandle = displayed.getTextureObjectHandle();
                    for (int i = 0; i < 32; i++) {
                        Texture pending = atlas.region("hex").getTexture();
                        // Changing the page format forces a repack without manufacturing enormous images.
                        atlas.updateRegions(Map.of("hex", pixels), i % 2 == 0 ? Map.of("hex", pixels) : Map.of());
                        atlas.releaseRetiredPages();
                        assertEquals(displayedHandle, displayed.getTextureObjectHandle());
                        assertTrue(org.lwjgl.opengl.GL11.glIsTexture(displayedHandle));
                        if (pending != displayed) { assertEquals(0, pending.getTextureObjectHandle()); }
                        assertEquals(1, ((List<?>) field(atlas, "retired")).size(), "Canceled pages must not accumulate");
                    }
                    atlas.publish();
                    atlas.releaseRetiredPages();
                    assertEquals(0, displayed.getTextureObjectHandle(), "Retire old pages after replacement meshes publish");
                    assertTrue(((List<?>) field(atlas, "retired")).isEmpty());
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { atlas.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Atlas replacement lifetime", failure.get()); }
    }

    @Test
    void waterKeepsItsSharedTexturesAcrossManyChunks() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                boolean previousLod = TerrainLod.enabled();
                try {
                    List<BoardScene.Tile> tiles = new ArrayList<>();
                    BoardScene.Pixels ground = GpuTerrainReliefSmokeTest.scene(BoardScene.Surface.GRASS).tiles().getFirst().ground();
                    // 45 water chunks exceed libGDX's maximum 32 texture slots, even without other samplers.
                    for (int x = 0; x < 72; x++) {
                        for (int y = 0; y < 40; y++) {
                            tiles.add(new BoardScene.Tile(new Coords(x, y), 0, 1, false, 0,
                                  BoardScene.Surface.GRASS, ground, null, null, List.of(), List.of()));
                        }
                    }
                    BoardScene scene = new BoardScene(0, 72, 40, tiles, List.of(), List.of(), -1, "", List.of());
                    BoardCamera camera = new BoardCamera();
                    camera.resize(960, 720);
                    camera.setIsometric(true);
                    camera.fit(scene);
                    TerrainLod.setEnabled(true);
                    terrain.update(scene, camera.camera);
                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                    terrain.animate(.37f, List.of());
                    ScreenUtils.clear(.2f, .26f, .31f, 1, true);
                    terrain.render(camera.camera, false);
                    terrain.renderTransparent(camera.camera);
                    ModelBatch batch = (ModelBatch) field(terrain, "batch");
                    var shaders = BaseShaderProvider.class.getDeclaredField("shaders");
                    shaders.setAccessible(true);
                    int checked = 0;
                    var current = BufferUtils.newIntBuffer(1);
                    Gdx.gl.glGetIntegerv(GL20.GL_CURRENT_PROGRAM, current);
                    for (Object shader : (Iterable<?>) shaders.get(batch.getShaderProvider())) {
                        ShaderProgram program = ((DefaultShader) shader).program;
                        // The bindings belong to the final drawn program, not previously used opaque shaders.
                        if (program.getHandle() != current.get(0)) { continue; }
                        assertTrue(program.getUniformLocation("u_waterField") >= 0, "The last draw must be water");
                        assertTexture(program, "u_rainNoise", (Texture) field(terrain, "rainNoise"));
                        Texture detail = (Texture) field(terrain, "waterDetail");
                        assertTexture(program, "u_waterDetail", detail);
                        Texture waves = ((GpuOcean) field(terrain, "ocean")).texture();
                        assertTexture(program, "u_waterOcean", waves == null ? detail : waves);
                        checked++;
                    }
                    assertTrue(checked > 0, "Exercise an actual water shader with chunk fields");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    TerrainLod.setEnabled(previousLod);
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Water texture lifetime", failure.get()); }
    }

    private static void checkPixmapRows() {
        var image = new java.awt.image.BufferedImage(12, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 12; x++) { image.setRGB(x, y, y == 1 && x > 4 ? 0x80abcdef : 0x00345678); }
        }
        var source = new BoardScene.Pixels(image);
        for (var pixels : List.of(source, source.compact())) {
            for (int border = 0; border <= 2; border++) {
                for (boolean flat : new boolean[] { false, true }) {
                    var pixmap = GpuTextures.pixmap(pixels, border, flat);
                    try {
                        for (int y = 0; y < pixmap.getHeight(); y++) {
                            for (int x = 0; x < pixmap.getWidth(); x++) {
                                int argb = image.getRGB(Math.clamp(x - border, 0, 11), Math.clamp(y - border, 0, 2));
                                assertEquals(flat ? 0x8080ffff : Integer.rotateLeft(argb, 8), pixmap.getPixel(x, y),
                                      "Atlas uploads must preserve RGBA channels and duplicate the complete border");
                            }
                        }
                    } finally { pixmap.dispose(); }
                }
            }
        }
    }

    private static void assertTexture(ShaderProgram program, String name, Texture expected) {
        assertTrue(program.getUniformLocation(name) >= 0, "Active water sampler: " + name);
        var value = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetUniformiv(program.getHandle(), program.getUniformLocation(name), value);
        int unit = value.get(0);
        Gdx.gl.glGetIntegerv(GL20.GL_ACTIVE_TEXTURE, value);
        int active = value.get(0);
        try {
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
            Gdx.gl.glGetIntegerv(GL20.GL_TEXTURE_BINDING_2D, value);
            assertEquals(expected.getTextureObjectHandle(), value.get(0), name + " must not sample another chunk's field");
        } finally { Gdx.gl.glActiveTexture(active); }
    }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
