/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Actual material palettes across height changes, including concrete foundations and emissive volcanic contacts. */
@Tag("on-demand")
class GpuTerrainContactSmokeTest {
    private static final String[] NAMES = { "grass", "dirt", "sand", "rock", "concrete", "snow", "magma", "lava" };

    @Test
    void cliffsAndSubmergedContactsKeepTheirMaterialsAcrossLods() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-contact");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var lodTuning = TerrainLod.tuning();
        boolean lodEnabled = TerrainLod.enabled();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                int arrayHandle = 0;
                try {
                    GpuRiverTerrainSmokeTest.tune(.94f, true);
                    TerrainLod.setEnabled(true);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    for (var lod : TerrainLod.values()) {
                        TerrainLod.tune(switch (lod) {
                            case FULL -> new TerrainLod.Tuning(1, 1);
                            case MEDIUM -> new TerrainLod.Tuning(100_000, 1);
                            case COARSE -> new TerrainLod.Tuning(100_000, 1000);
                            case DISTANT -> new TerrainLod.Tuning(100_000, 100_000);
                        });
                        for (int height : new int[] { 1, 2, 3, 0 }) {
                            boolean shore = height == 0;
                            var scene = scene(height);
                            camera.setIsometric(true);
                            camera.camera.zoom = .2f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), shore ? 0 : 1));
                            terrain.update(scene, camera.camera);
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            var chunks = (List<?>) field(terrain, "chunks");
                            assertEquals(lod, field(chunks.getFirst(), "lod"));
                            terrain.animate(.5f, List.of());
                            frame.render(terrain, camera, scene);
                            String contact = shore ? "shore-" : height < 3 ? "bank" + height + "-" : "cliff-";
                            GpuReviewFrame.save(new File(output, contact + lod + ".png"));
                            if (height == 2 && lod == TerrainLod.FULL) {
                                camera.camera.zoom = .1f;
                                camera.center(BoardGeometry.center(new Coords(4, 4), 1));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, "bank2-close.png"));
                            }
                            if (shore) {
                                frame.render(terrain, camera, scene, false);
                                GpuReviewFrame.save(new File(output, "bed-" + lod + ".png"));
                            }
                        }
                    }
                    TerrainLod.tune(new TerrainLod.Tuning(1, 1));
                    var scene = scene(0);
                    camera.setIsometric(false);
                    camera.camera.zoom = .2f;
                    camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                    terrain.update(scene, camera.camera);
                    GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                    terrain.animate(.5f, List.of());
                    frame.render(terrain, camera, scene, false);
                    var before = screen();
                    try {
                        TextureArray array = (TextureArray) field(field(terrain, "assets"), "sculptArray");
                        arrayHandle = array.getTextureObjectHandle();
                        var layersField = GpuTerrain.class.getDeclaredField("SCULPT_LAYERS");
                        layersField.setAccessible(true);
                        var names = (List<?>) layersField.get(null);
                        // Change only land cover to a sentinel. Deep bed pixels must be independent of those maps.
                        array.bind();
                        var sentinel = new Pixmap(array.getWidth(), array.getHeight(), Pixmap.Format.RGBA8888);
                        try {
                            sentinel.setColor(0xff00ffff);
                            sentinel.fill();
                            for (String name : List.of("grass", "sand")) {
                                Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 2 * names.indexOf(name),
                                      array.getWidth(), array.getHeight(), 1, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, sentinel.getPixels());
                            }
                            Gdx.gl.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);
                        } finally { sentinel.dispose(); }
                        frame.render(terrain, camera, scene, false);
                        var after = screen();
                        try {
                            int changed = 0;
                            for (int x = 0; x < before.getWidth(); x += 4) {
                                for (int y = 0; y < before.getHeight(); y += 4) {
                                    if (before.getPixel(x, y) != after.getPixel(x, y)) { changed++; }
                                }
                            }
                            assertTrue(changed > 100, "The sentinel must affect actual dry material contacts");
                            int checked = 0;
                            var tile = scene.tile(new Coords(4, 4));
                            var surface = new BoardSurface(scene, tile);
                            var center = BoardGeometry.center(tile.coords(), 0);
                            for (int x = -8; x <= 8; x++) {
                                for (int y = -8; y <= 8; y++) {
                                    float px = center.x + x * BoardGeometry.width() / 22;
                                    float py = center.y + y * BoardGeometry.height() / 22;
                                    float z = BoardSurface.sampleHeight(surface.faces, px, py, Float.NaN);
                                    if (!Float.isFinite(z) || z > BoardGeometry.waterZ(tile) - BoardRelief.metres(.6f)) { continue; }
                                    var pixel = camera.camera.project(new Vector3(px, py, z));
                                    int sx = Math.round(pixel.x), sy = Math.round(pixel.y);
                                    if (sx < 0 || sy < 0 || sx >= before.getWidth() || sy >= before.getHeight()) { continue; }
                                    assertEquals(before.getPixel(sx, sy), after.getPixel(sx, sy),
                                          "Submerged bed must not sample the land-cover texture at " + px + ", " + py);
                                    checked++;
                                }
                            }
                            assertTrue(checked > 50, "Exercise a substantial submerged bank/bed region");
                        } finally { after.dispose(); }
                    } finally { before.dispose(); }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    if (arrayHandle != 0 && org.lwjgl.opengl.GL11.glIsTexture(arrayHandle)) {
                        failure.compareAndSet(null, new AssertionError("Terrain material array leaked after disposal"));
                    }
                    frame.dispose();
                    BoardGeometry.tune(original);
                    TerrainLod.tune(lodTuning);
                    TerrainLod.setEnabled(lodEnabled);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Cliff and waterbed material contacts", failure.get()); }
    }

    private static BoardScene scene(int height) {
        return BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              c.getX() < 4 ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS,
              c.getY() < 4 ? height : 0, height == 0 && c.getY() >= 4 ? 2 : -1, 0));
    }

    private static Pixmap screen() { return Pixmap.createFromFrameBuffer(0, 0, 1280, 960); }

    private static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    @Test
    void allFamiliesRenderAtSlopeAndCliffFeet() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 900);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(settings(13));
                try {
                    var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-contacts");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    var camera = new BoardCamera();
                    camera.resize(1280, 900);
                    for (int family = 0; family < NAMES.length; family++) {
                        int upper = family;
                        int lower = switch (family) { case 0, 4 -> 1; case 1 -> 0; case 3 -> 2; default -> 3; };
                        for (int rise : family == BoardScene.Surface.CONCRETE.ordinal()
                              ? new int[] { 0, 1, 2, 3, 4 } : new int[] { 0, 1, 4 }) {
                            var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
                                  c.getY() < 4 ? upper : lower, c.getY() < 4 ? rise : 0));
                            // Keep one renderer while changing family/elevation to exercise invalidation and rebinding.
                            terrain.update(scene);
                            terrain.animate(.5f, List.of());
                            camera.setPerspective(false);
                            camera.setIsometric(true);
                            camera.camera.zoom = .18f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), rise == 4 ? 1 : 0));
                            frame.configure(settings(13));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, NAMES[family] + "-rise" + rise + ".png"));
                            if (rise == 4) {
                                camera.setPerspective(true);
                                camera.camera.zoom = .28f;
                                camera.update();
                                frame.configure(settings(0));
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(output, NAMES[family] + "-cliff-night-perspective.png"));
                            }
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), NAMES[family] + " rise " + rise);
                        }
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    frame.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain foot contacts", failure.get()); }
    }

    private static BoardAtmosphere.Settings settings(float hour) {
        return new BoardAtmosphere.Settings(hour, 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
    }
}
