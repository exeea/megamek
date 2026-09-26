/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultRenderableSorter;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Same-camera A/B measurements and actual rain/splash pixels, rather than assuming shader arithmetic is faster. */
@Tag("on-demand")
class GpuWaterShaderSmokeTest {
    @Test
    void uniformFastPathsPreserveFrozenBedWaterAndWaterfallPixels() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var configuration = GpuBoardWindow.configuration(false);
        configuration.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain reference = new GpuTerrain(), optimized = new GpuTerrain();
                try {
                    // Only the reference shader source differs. Both paths retain identical geometry and draw order.
                    configureParity(reference, true);
                    configureParity(optimized, false);
                    for (GpuTerrain terrain : List.of(reference, optimized)) {
                        terrain.setAtmosphere(BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS));
                        terrain.setWind(BoardAtmosphere.Effects.NONE);
                    }
                    // Sculpted pool beds and the older cliff/bank material both need their zero-detail path checked.
                    for (boolean falls : new boolean[] { false, true }) {
                        BoardScene scene = parityScene(falls);
                        for (GpuTerrain terrain : List.of(reference, optimized)) {
                            terrain.update(scene);
                            terrain.animate(.37f, List.of());
                        }
                        for (boolean perspective : new boolean[] { false, true }) {
                            byte[] effectsOff = null;
                            for (int mode = 0; mode < 3; mode++) {
                                BoardCamera camera = new BoardCamera();
                                camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                                camera.setPerspective(perspective);
                                camera.setIsometric(true);
                                camera.fit(scene);
                                if (mode == 0) {
                                    float scale = Gdx.graphics.getBackBufferHeight() / (float) Gdx.graphics.getHeight();
                                    camera.camera.zoom = BoardGeometry.WIDTH * scale / 8;
                                } else {
                                    camera.camera.zoom *= .8f;
                                }
                                camera.update();
                                float maximumHexPixels = 0;
                                for (BoardScene.Tile tile : scene.tiles()) {
                                    maximumHexPixels = Math.max(maximumHexPixels, BoardGeometry.WIDTH
                                          * BoardCamera.pixelsPerUnit(camera.camera,
                                                BoardGeometry.center(tile.coords(), tile.elevation())));
                                }
                                assertTrue(mode == 0 ? maximumHexPixels < 12 : maximumHexPixels > 40,
                                      "Exercise zero detail and active detail through the real camera uniforms");
                                String name = (falls ? "falls" : "pool") + (perspective ? "-perspective" : "-ortho")
                                      + "-" + new String[] { "detail-zero", "effects-off", "active-wet" }[mode];
                                for (GpuTerrain terrain : List.of(reference, optimized)) {
                                    terrain.setWaterEffects(mode != 1);
                                    terrain.setWetness(1);
                                    // Settle shader and geometry caches without advancing the shared frozen clock.
                                    for (int warmup = 0; warmup < 4; warmup++) { parityPixels(terrain, camera, true); }
                                }
                                for (boolean transparent : new boolean[] { false, true }) {
                                    byte[] before = parityPixels(reference, camera, transparent);
                                    byte[] after = parityPixels(optimized, camera, transparent);
                                    byte[] repeated = parityPixels(reference, camera, transparent);
                                    String pass = name + (transparent ? "-water" : "-bed");
                                    assertParity(before, after, repeated, pass);
                                    if (transparent && mode == 1) { effectsOff = after; }
                                    if (transparent && mode == 2) {
                                        assertTrue(!Arrays.equals(effectsOff, after),
                                              "The active water effects must affect rendered pixels: " + name);
                                    }
                                }
                            }
                        }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    reference.dispose(); optimized.dispose();
                    Gdx.app.exit();
                }
            }
        }, configuration);
        if (failure.get() != null) { throw new AssertionError("Uniform water shader fast paths", failure.get()); }
    }

    /** Recreate the prior unconditional expressions before any of these shader configurations are compiled. */
    private static void configureParity(GpuTerrain terrain, boolean reference) throws ReflectiveOperationException {
        Field field = GpuTerrain.class.getDeclaredField("batch");
        field.setAccessible(true);
        ModelBatch batch = (ModelBatch) field.get(terrain);
        // Isolate source changes from GpuOpaqueSorter's shader-identity order in independently allocated renderers.
        Field sorter = ModelBatch.class.getDeclaredField("sorter");
        sorter.setAccessible(true);
        sorter.set(batch, new DefaultRenderableSorter());
        if (!reference) { return; }
        var provider = batch.getShaderProvider();
        for (String name : List.of("sculptShader", "cliffShader", "waterShader", "waterLiquidShader")) {
            Field configField = provider.getClass().getDeclaredField(name);
            configField.setAccessible(true);
            DefaultShader.Config config = (DefaultShader.Config) configField.get(provider);
            String current = config.fragmentShader;
            config.fragmentShader = current
                  .replace("shore && u_rainDetail > 0.0 && u_waterEffects > 0.0", "shore")
                  .replace("u_rainDetail > 0.0 && u_waterEffects > 0.0", "true")
                  .replace("u_splashCount > 0", "true");
            assertTrue(!current.equals(config.fragmentShader), "The legacy expressions must replace guards in " + name);
        }
    }

    private static byte[] parityPixels(GpuTerrain terrain, BoardCamera camera, boolean transparent) {
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(.04f, .06f, .08f, 1, true);
        terrain.render(camera.camera, false);
        if (transparent) { terrain.renderTransparent(camera.camera); }
        byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, Gdx.graphics.getBackBufferWidth(),
              Gdx.graphics.getBackBufferHeight(), false);
        int varied = 0;
        for (int i = 4; i < pixels.length; i += 4) {
            if (pixels[i] != pixels[0] || pixels[i + 1] != pixels[1] || pixels[i + 2] != pixels[2]) { varied++; }
        }
        assertTrue(varied > 500, "The comparison must include visible terrain even at zero detail");
        return pixels;
    }

    private static void assertParity(byte[] expected, byte[] actual, byte[] repeated, String name) {
        int changed = 0, referenceChanged = 0, maximum = 0, referenceMaximum = 0;
        for (int pixel = 0; pixel < expected.length; pixel += 4) {
            int delta = 0, referenceDelta = 0;
            for (int channel = 0; channel < 4; channel++) {
                int value = Byte.toUnsignedInt(expected[pixel + channel]);
                delta = Math.max(delta, Math.abs(value - Byte.toUnsignedInt(actual[pixel + channel])));
                referenceDelta = Math.max(referenceDelta, Math.abs(value - Byte.toUnsignedInt(repeated[pixel + channel])));
            }
            if (delta != 0) { changed++; maximum = Math.max(maximum, delta); }
            if (referenceDelta != 0) { referenceChanged++; referenceMaximum = Math.max(referenceMaximum, referenceDelta); }
        }
        System.out.printf("Water shader parity %s: changed=%d max=%d; repeated reference changed=%d max=%d%n",
              name, changed, maximum, referenceChanged, referenceMaximum);
        // Prior native terrain comparisons measured one cliff/shore pixel varying by 1 LSB between frozen draws.
        boolean stable = referenceChanged <= 1 && referenceMaximum <= 1;
        boolean equal = changed <= Math.max(1, referenceChanged) && maximum <= Math.max(1, referenceMaximum);
        if (!stable || !equal) {
            savePixels(expected, "water-fast-path-" + name + "-reference");
            savePixels(actual, "water-fast-path-" + name + "-optimized");
            savePixels(repeated, "water-fast-path-" + name + "-repeated");
        }
        assertTrue(stable, "The frozen shader reference must remain stable: " + name);
        assertTrue(equal, "Uniform fast paths must preserve rendered pixels: " + name + ", changed=" + changed
              + ", maximum=" + maximum);
    }

    private static void savePixels(byte[] pixels, String name) {
        Pixmap image = new Pixmap(Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight(), Pixmap.Format.RGBA8888);
        try { image.getPixels().put(pixels); save(image, name); }
        finally { image.dispose(); }
    }

    private static BoardScene parityScene(boolean falls) {
        BoardScene source = scene(falls, falls ? 3 : 0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (BoardScene.Tile tile : source.tiles()) {
            // Roads keep the dry banks' older cliff shader; the water and its bed retain their sculpted material.
            int roads = falls && !tile.water() ? 1 : 0;
            tiles.add(new BoardScene.Tile(tile.coords(), tile.elevation(), tile.waterDepth(), false, roads,
                  tile.surface(), tile.ground(), null, null, null, null, List.of(), List.of(), tile.liquid(), null, true));
        }
        return new BoardScene(0, source.width(), source.height(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void proceduralWaterShowsRainAndSplashesAndMeasuresBothColorPaths() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain gif = new GpuTerrain(true, false), procedural = new GpuTerrain(true, true);
                try {
                    Gdx.graphics.setVSync(false);
                    BoardScene scene = scene(false, 0);
                    for (GpuTerrain terrain : List.of(gif, procedural)) {
                        terrain.update(scene);
                        terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0.8f, 0, 2.5f, 0, 0,
                              new BoardAtmosphere.Effects(0, 0, 0, 0, 0, 0, 0))));
                    }
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    StringBuilder report = new StringBuilder(Gdx.gl.glGetString(GL20.GL_RENDERER))
                          .append("\nSynchronized animated terrain-frame medians, 40 interleaved samples after warmup; includes CPU updates/submission.\n");
                    for (boolean isometric : new boolean[] { false, true }) {
                        camera.setIsometric(isometric);
                        camera.fit(scene);
                        camera.camera.zoom *= 0.65f;
                        camera.camera.update();
                        gif.renderShadows(List.of());
                        procedural.renderShadows(List.of());
                        for (int rain = 0; rain <= 1; rain++) {
                            report.append(isometric ? "isometric" : "top").append(", rain=").append(rain).append(": ")
                                  .append(measure(gif, procedural, camera, rain)).append('\n');
                        }
                        Pixmap authored = frame(gif, camera, 0), dry = frame(procedural, camera, 0);
                        Pixmap light = frame(procedural, camera, 0.2f), wet = frame(procedural, camera, 1);
                        try {
                            save(authored, "water-color-gif-" + isometric);
                            save(dry, "water-color-procedural-" + isometric);
                            save(wet, "water-downpour-" + isometric);
                            int[] replacement = sample(dry, camera, new Coords(5, 4), 60);
                            assertTrue(mean(replacement, 8) > mean(replacement, 24),
                                  "Clear water keeps its cool absorption palette while reflecting the sky");
                            int[] drizzle = sample(light, camera, new Coords(5, 4), 60);
                            int[] downpour = sample(wet, camera, new Coords(5, 4), 60);
                            int lightPixels = differences(replacement, drizzle, 6);
                            int wetPixels = differences(replacement, downpour, 6);
                            assertTrue(wetPixels > replacement.length / 6 && wetPixels > lightPixels * 1.5,
                                  "Rain must be clearly visible and denser at downpour: " + lightPixels + ", " + wetPixels);
                            procedural.animate(0.31f, List.of());
                            Pixmap later = frame(procedural, camera, 1);
                            try { save(later, "water-downpour-later-" + isometric); } finally { later.dispose(); }
                        } finally {
                            authored.dispose(); dry.dispose(); light.dispose(); wet.dispose();
                        }
                    }
                    File output = output();
                    Files.writeString(new File(output, "water-color-timing.txt").toPath(), report);
                    checkSplash(procedural, camera, "procedural");
                    checkSplash(gif, camera, "gif");
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    gif.dispose(); procedural.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void checkSplash(GpuTerrain terrain, BoardCamera camera, String mode) {
        BoardScene waterfall = scene(true, 3);
        terrain.update(waterfall);
        // Isolate foam from the extra shadow cast by the raised upstream bank in the live-edit comparison.
        terrain.setAtmosphere(new BoardAtmosphere.Lighting(new Vector3(0, 0, -1), Color.BLACK, Color.WHITE,
              Color.BLACK, Color.GRAY, Color.GRAY, Color.WHITE, 1, 0, false));
        camera.setIsometric(true);
        camera.fit(waterfall);
        camera.center(BoardGeometry.center(new Coords(4, 5), 0));
        camera.camera.zoom *= 0.55f;
        camera.camera.update();
        terrain.renderShadows(List.of());
        Pixmap splash = frame(terrain, camera, 0);
        try {
            save(splash, "waterfall-splash-" + mode);
            Vector3 base = BoardGeometry.center(new Coords(4, 5), 0).add(0, BoardGeometry.HEIGHT * 0.36f, 0);
            base.z = BoardGeometry.waterZ(waterfall.tile(new Coords(4, 5)));
            int[] before = sample(splash, camera, base, 9);
            terrain.animate(0.2f, List.of());
            Pixmap animated = frame(terrain, camera, 0);
            try {
                save(animated, "waterfall-splash-later-" + mode);
                assertTrue(differences(before, sample(animated, camera, base, 9), 6) > 20,
                      "Impact foam must animate at the waterfall base without rain");
            } finally { animated.dispose(); }
            // Removing the height difference must also remove the receiving surface's impact effect.
            terrain.update(scene(true, 0));
            terrain.renderShadows(List.of());
            Pixmap flat = frame(terrain, camera, 0);
            try {
                int[] after = sample(flat, camera, base, 9);
                assertTrue(mean(before, 8) > mean(after, 8) + 10,
                      "An ordinary flat connection must not retain bright impact foam after a live edit: "
                            + mean(before, 8) + " -> " + mean(after, 8));
            } finally { flat.dispose(); }
        } finally { splash.dispose(); }
    }

    private static String measure(GpuTerrain gif, GpuTerrain procedural, BoardCamera camera, float rain) {
        double[][] times = new double[2][40];
        GpuTerrain[] terrain = { gif, procedural };
        for (int sample = -12; sample < 40; sample++) {
            for (int order = 0; order < 2; order++) {
                int mode = (sample + order) & 1;
                Gdx.gl.glFinish();
                long start = System.nanoTime();
                terrain[mode].animate(1f / 60, List.of());
                draw(terrain[mode], camera, rain);
                Gdx.gl.glFinish();
                if (sample >= 0) { times[mode][sample] = (System.nanoTime() - start) / 1e6; }
            }
        }
        Arrays.sort(times[0]); Arrays.sort(times[1]);
        return "GIF=" + times[0][20] + " ms, procedural=" + times[1][20] + " ms";
    }

    private static Pixmap frame(GpuTerrain terrain, BoardCamera camera, float rain) {
        draw(terrain, camera, rain);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static void draw(GpuTerrain terrain, BoardCamera camera, float rain) {
        terrain.setWetness(rain);
        ScreenUtils.clear(0.04f, 0.06f, 0.08f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    private static int[] sample(Pixmap image, BoardCamera camera, Coords coords, int radius) {
        return sample(image, camera, BoardGeometry.center(coords, 0).add(0, 0, -BoardGeometry.HEX_SCALE), radius);
    }

    private static int[] sample(Pixmap image, BoardCamera camera, Vector3 center, int radius) {
        int side = radius * 2 + 1;
        int[] result = new int[side * side];
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                Vector3 screen = camera.camera.project(new Vector3(center).add(x, y, 0));
                result[(y + radius) * side + x + radius] = image.getPixel(Math.round(screen.x), Math.round(screen.y));
            }
        }
        return result;
    }

    private static double mean(int[] image, int shift) {
        long sum = 0;
        for (int pixel : image) { sum += (pixel >>> shift) & 255; }
        return sum / (double) image.length;
    }

    private static int differences(int[] first, int[] second, int threshold) {
        int count = 0;
        for (int i = 0; i < first.length; i++) {
            int difference = 0;
            for (int shift : new int[] { 8, 16, 24 }) { difference += Math.abs(((first[i] >>> shift) & 255) - ((second[i] >>> shift) & 255)); }
            if (difference > threshold) { count++; }
        }
        return count;
    }

    private static File output() {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        return output;
    }

    private static void save(Pixmap image, String name) {
        PixmapIO.writePNG(Gdx.files.absolute(new File(output(), name + ".png").getAbsolutePath()), image);
    }

    private static BoardScene scene(boolean river, int drop) {
        BufferedImage gray = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { gray.setRGB(x, y, 0xff888888); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(gray);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 10; y++) {
                boolean water = !river || x == 4;
                tiles.add(new BoardScene.Tile(new Coords(x, y), river && y < 5 ? drop : 0, water ? 2 : -1, false,
                      0, BoardScene.Surface.ROCK, pixels, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 12, 10, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
