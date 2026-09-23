/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Deterministic sculpted-terrain review scene: a connected multi-hex plateau with a concave notch, a tall isolated
 * formation, a one-hex ridge, stepped mixed-elevation junctions, a sunken basin, a lake and woods of every density.
 * Each surface family is captured from a tactical overview, a medium oblique angle, close rim/base views, the woods and
 * one light wood up close, with a neutral clay variant and a grid-free variant. Captures pass through the production
 * composite's clear-daylight exposure.
 */
@Tag("on-demand")
class GpuTerrainShowcaseSmokeTest {
    /** Rows are y; characters are x. Digits are levels, a/b are -1/-2, w/W are lakes of depth 1/2 at level 0. */
    static final String[] LAYOUT = {
          "0000000000000000",
          "0000033300000000",
          "0000333330000000",
          "0003333332000000",
          "0003333322005000",
          "0000330331000000",
          "0000021110000000",
          "0020000000000000",
          "0020000000000000",
          "0020000000aa0000",
          "0020000000ab0www",
          "0020000000a00wWw",
          "000000000000wwWw",
          "000000000000wwww",
    };

    /** Woods over the layout: l, h and u are light, heavy and ultra-heavy woods with the game's default heights. */
    static final String[] WOODS = {
          "................",
          "................",
          "....h...........",
          "...hh...........",
          "................",
          "................",
          "................",
          ".............l..",
          "............l...",
          ".....uu.........",
          "....uhh.........",
          "....hl..........",
          "................",
          "................",
    };

    private record View(String name, boolean isometric, float tilt, float rotation, float zoom, Coords focus,
          int level, boolean clay, float grid) { }

    private static final List<View> VIEWS = List.of(
          new View("overview", false, 0, 0, 1.02f, new Coords(8, 7), 0, false, .8f),
          new View("oblique", true, 0, 0, .52f, new Coords(7, 5), 1, false, .8f),
          new View("oblique-nogrid", true, 0, 0, .52f, new Coords(7, 5), 1, false, 1f),
          new View("oblique-clay", true, 0, 0, .52f, new Coords(7, 5), 1, true, 1f),
          new View("rim", true, 12, 30, .2f, new Coords(8, 5), 2, false, .8f),
          new View("rim-clay", true, 12, 30, .2f, new Coords(8, 5), 2, true, 1f),
          new View("base", true, 22, -40, .22f, new Coords(4, 5), 1, false, .8f),
          new View("formation", true, 18, 150, .3f, new Coords(12, 4), 2, false, .8f),
          new View("ridge", true, 10, -100, .32f, new Coords(2, 9), 1, false, .8f),
          new View("corner", true, 20, 60, .1f, new Coords(12, 4), 3, false, .8f),
          new View("notch", true, 25, 200, .12f, new Coords(8, 4), 2, false, .8f),
          new View("junction-top", false, 0, 0, .16f, new Coords(5, 6), 2, false, .8f),
          new View("woods", true, 14, -20, .24f, new Coords(5, 9), 1, false, .8f),
          new View("woods-close", true, 22, -20, .09f, new Coords(5, 11), 0, false, .8f));

    @Test
    void capturesSculptedTerrainReviewViews() throws Exception {
        String families = System.getProperty("megamek.gpu.showcase.families", "SAND,GRASS");
        float hour = Float.parseFloat(System.getProperty("megamek.gpu.showcase.hour", "13"));
        List<String> views = List.of(System.getProperty("megamek.gpu.showcase.views", "").split(","));
        // Hex transitions on or off; captures with them on are named with a "-transitions" suffix.
        boolean transitions = Boolean.parseBoolean(System.getProperty("megamek.gpu.showcase.transitions",
              String.valueOf(BoardGeometry.DEFAULT_TRANSITIONS)));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "terrain-showcase");
        Files.createDirectories(output.toPath());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        StringBuilder report = new StringBuilder();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1440, 1080);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GLProfiler profiler = new GLProfiler(Gdx.graphics);
                try {
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                    BoardAtmosphere.Settings settings = new BoardAtmosphere.Settings(hour, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0);
                    BoardAtmosphere.Lighting lighting = BoardAtmosphere.lighting(settings);
                    float exposure = lighting.exposureScale(settings.exposure());
                    terrain.setAtmosphere(lighting);
                    terrain.setExposure(exposure);
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (String name : families.split(",")) {
                        BoardScene.Surface family = BoardScene.Surface.valueOf(name.trim().toUpperCase(Locale.ROOT));
                        BoardScene scene = scene(family);
                        long start = System.nanoTime();
                        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, .8f,
                              BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE, transitions));
                        terrain.update(scene);
                        report.append(String.format(Locale.ROOT, "%s: build %.1f ms%n", family,
                              (System.nanoTime() - start) / 1e6));
                        for (View view : VIEWS) {
                            if (!views.get(0).isBlank() && !views.contains(view.name())) { continue; }
                            BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, view.grid(),
                                  BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE, transitions));
                            terrain.update(scene);
                            terrain.setClay(view.clay());
                            camera.setIsometric(view.isometric());
                            if (view.rotation() != 0 || view.tilt() != 0) { camera.orbit(view.rotation(), view.tilt()); }
                            camera.camera.zoom = view.zoom();
                            camera.center(BoardGeometry.center(view.focus(), view.level()));
                            terrain.renderShadows(camera.camera, List.of());
                            profiler.reset();
                            profiler.enable();
                            frame(terrain, camera);
                            profiler.disable();
                            report.append(String.format(Locale.ROOT, "  %s: draws=%d vertices=%.0f%n", view.name(),
                                  profiler.getDrawCalls(), profiler.getVertexCount().total));
                            capture(new File(output, family.name().toLowerCase(Locale.ROOT) + "-" + view.name()
                                  + (transitions ? "-transitions" : "") + ".png"), exposure, lighting);
                        }
                    }
                    terrain.setClay(false);
                    Files.writeString(new File(output, "report.txt").toPath(), report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Terrain showcase", failure.get()); }
        System.out.print(report);
    }

    private static void frame(GpuTerrain terrain, BoardCamera camera) {
        // Sky tone behind the plinth; transparent alpha lets the composite treat it as sky, as in production.
        ScreenUtils.clear(.42f, .56f, .69f, 1, true);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
    }

    /** The production composite's clear-air daylight path: linear exposure, grade, clamp, display encode, vignette. */
    private static void capture(File file, float exposure, BoardAtmosphere.Lighting lighting) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            ByteBuffer pixels = image.getPixels();
            float saturation = lighting.saturation();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = (y * width + x) * 4;
                    float[] c = new float[3];
                    for (int i = 0; i < 3; i++) {
                        float value = (pixels.get(index + i) & 255) / 255f;
                        c[i] = (float) Math.pow(value, 2.2) * exposure;
                    }
                    c[0] *= lighting.tint().r;
                    c[1] *= lighting.tint().g;
                    c[2] *= lighting.tint().b;
                    float luminance = .2126f * c[0] + .7152f * c[1] + .0722f * c[2];
                    float ex = (x / (float) width - .5f) * 2, ey = (y / (float) height - .5f) * 2;
                    float vignette = 1 - .09f * (ex * ex + ey * ey) * .5f;
                    for (int i = 0; i < 3; i++) {
                        float value = Math.clamp(luminance + (c[i] - luminance) * saturation, 0, 1);
                        value = (float) Math.pow(value, 1 / 2.2) * vignette;
                        pixels.put(index + i, (byte) Math.round(Math.clamp(value, 0, 1) * 255));
                    }
                    pixels.put(index + 3, (byte) 255);
                }
            }
            PixmapIO.writePNG(new FileHandle(file), image, -1, true);
        } finally {
            image.dispose();
        }
    }

    static BoardScene scene(BoardScene.Surface family) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                boolean water = cell == 'w' || cell == 'W';
                int level = switch (cell) {
                    case 'a' -> -1;
                    case 'b' -> -2;
                    case 'w', 'W' -> 0;
                    default -> cell - '0';
                };
                int depth = cell == 'w' ? 1 : cell == 'W' ? 2 : -1;
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, level, depth, false, 0, family, pixels,
                      null, null, null, null, woods(WOODS[y].charAt(x), level, family, coords), List.of(),
                      water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        assertTrue(tiles.size() == width * height);
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The trees the game captures for woods of this density in this family, with the default foliage height. */
    private static List<BoardScene.Feature> woods(char mark, int level, BoardScene.Surface family, Coords coords) {
        if (mark == '.') { return List.of(); }
        int density = mark == 'l' ? 1 : mark == 'h' ? 2 : 3;
        Hex hex = new Hex(level);
        hex.addTerrain(new Terrain(Terrains.WOODS, density));
        hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, density == 3 ? 3 : 2));
        switch (family) {
            case SAND -> hex.addTerrain(new Terrain(Terrains.SAND, 1));
            case SNOW -> hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            case CONCRETE -> hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            case ROCK -> hex.setTheme("rock");
            case DIRT -> hex.setTheme("dirt");
            default -> { }
        }
        return BoardFeatures.capture(hex, coords, Map.of());
    }
}
