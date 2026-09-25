/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Review scene for water hexes inside sculpted land, after a user's desert map: a depth-1 river at level 2 winds
 * between land at levels 1 to 4, beside higher ground and above lower ground, and falls into a lake of depth-1 and
 * depth-2 hexes at level 1. Views: straight down with and without the grid, an oblique overview, a bank beside the
 * channel, a water hex's edge against higher land, the fall and a clay overview. Captures are drawn as on the board,
 * through the atmosphere composite ({@link GpuReviewFrame}); a report lists the renderer, build time, draws and vertices.
 */
@Tag("on-demand")
class GpuRiverTerrainSmokeTest {
    /** Rows are y; characters are x. Digits are land levels, r is river (level 2, depth 1), w/W lake (level 1). */
    static final String[] LAYOUT = {
          "33r22233344433",
          "43r32233444443",
          "43rr3223344333",
          "3323r432233322",
          "2222rrrr333222",
          "11221112r33222",
          "11111122rr3322",
          "1111112222r332",
          "1111111121r122",
          "111111111www22",
          "111111111wWW11",
          "1111111111ww11",
    };

    private record View(String name, boolean isometric, float tilt, float rotation, float zoom, Coords focus,
          int level, boolean clay, float grid) { }

    private static final List<View> VIEWS = List.of(
          new View("top", false, 0, 0, .88f, new Coords(7, 5), 2, false, .8f),
          new View("top-nogrid", false, 0, 0, .88f, new Coords(7, 5), 2, false, 1f),
          new View("oblique", true, 0, 0, .62f, new Coords(6, 5), 2, false, .8f),
          // The channel at (8, 6) runs between banks at levels 2 and 3.
          new View("bank", true, -10, 0, .14f, new Coords(8, 6), 2, false, .8f),
          // From the south onto river hex (5, 4) and the level-4 land north of it.
          new View("wall", true, -10, -45, .13f, new Coords(5, 4), 3, false, .8f),
          // From the lake up at the fall where the river at (10, 8) pours into it.
          new View("fall", true, -8, 0, .1f, new Coords(10, 9), 1, false, .8f),
          // From beyond the top edge at the river's head, which pours off the board, and the lake the bottom edge cuts.
          new View("edge", true, -10, 180, .14f, new Coords(2, 0), 2, false, .8f),
          new View("cut", true, -10, 0, .14f, new Coords(10, 11), 1, false, .8f),
          new View("clay", true, 0, 0, .62f, new Coords(6, 5), 2, true, 1f));

    private static final List<View> MAP_VIEWS = List.of(
          new View("lake", false, 0, 0, .34f, new Coords(4, 12), 2, false, .8f),
          new View("junction", false, 0, 0, .24f, new Coords(6, 8), 2, false, .8f),
          new View("peninsulas", false, 0, 0, .40f, new Coords(8, 9), 1, false, .8f),
          new View("shallow", false, 0, 0, .20f, new Coords(10, 12), 0, false, .8f),
          new View("shallow-oblique", true, -25, 180, .13f, new Coords(10, 12), 0, false, .8f),
          new View("slope-banks", true, -20, 150, .13f, new Coords(8, 6), 0, false, .8f),
          new View("ridge", true, -10, 0, .20f, new Coords(6, 9), 2, false, .8f));

    private static final List<View> DROP_VIEWS = List.of(
          new View("drops", true, -10, 0, .48f, new Coords(4, 3), 1, false, .8f),
          new View("drops-top", false, 0, 0, .50f, new Coords(4, 3), 1, false, .8f),
          new View("fall-front", true, -18, 0, .13f, new Coords(7, 3), 1, false, .8f),
          new View("fall-left", true, -18, 60, .13f, new Coords(7, 3), 1, false, .8f),
          new View("fall-right", true, -18, -60, .13f, new Coords(7, 3), 1, false, .8f),
          new View("fall-back", true, -18, 180, .13f, new Coords(7, 3), 1, false, .8f),
          new View("fall-top", false, 0, 0, .13f, new Coords(7, 3), 1, false, .8f));

    private static final List<View> QUAY_VIEWS = List.of(
          new View("quay", false, 0, 0, .56f, new Coords(4, 5), 0, false, .8f),
          new View("quay-oblique", true, -15, 0, .40f, new Coords(4, 5), 0, false, .8f));

    private static final List<View> DOCK_VIEWS = List.of(
          new View("dock", false, 0, 0, .85f, new Coords(7, 5), 0, false, .8f),
          new View("dock-oblique", true, -15, 0, .65f, new Coords(7, 5), 0, false, .8f));

    private static final List<View> COAST_PATTERN_VIEWS = List.of(
          new View("coast-patterns", false, 0, 0, .76f, new Coords(5, 6), 0, false, .8f),
          new View("coast-patterns-oblique", true, -15, 0, .62f, new Coords(5, 6), 0, false, .8f));

    private static final List<View> PAVED_MAP_VIEWS = List.of(
          new View("aerobase", false, 0, 0, 1.3f, new Coords(7, 8), 0, false, .8f),
          new View("aerobase-oblique", true, -15, 0, .96f, new Coords(7, 8), 0, false, .8f));

    @Test
    void capturesWaterHexesInSculptedLand() throws Exception {
        String families = System.getProperty("megamek.gpu.river.families", "SAND");
        List<String> views = List.of(System.getProperty("megamek.gpu.river.views", "").split(","));
        // Hex transitions on or off; captures with them on are named with a "-transitions" suffix.
        boolean transitions = Boolean.parseBoolean(System.getProperty("megamek.gpu.river.transitions",
              String.valueOf(BoardGeometry.DEFAULT_TRANSITIONS)));
        String suffix = transitions ? "-transitions" : "";
        boolean map = Boolean.getBoolean("megamek.gpu.river.map");
        boolean drops = Boolean.getBoolean("megamek.gpu.river.drops");
        boolean quay = Boolean.getBoolean("megamek.gpu.river.quay");
        boolean dock = Boolean.getBoolean("megamek.gpu.river.dock");
        boolean patterns = Boolean.getBoolean("megamek.gpu.river.coastPatterns");
        boolean paving = Boolean.getBoolean("megamek.gpu.river.paving");
        boolean pavedMap = Boolean.getBoolean("megamek.gpu.river.pavedMap");
        BoardConcrete.Mode concreteMode = BoardConcrete.Mode.valueOf(System.getProperty("megamek.gpu.river.concreteMode",
              (paving || pavedMap ? BoardConcrete.Mode.EVERYWHERE : BoardConcrete.DEFAULT_MODE).name()));
        BoardConcrete.Mode originalConcrete = BoardConcrete.mode();
        float width = Float.parseFloat(System.getProperty("megamek.gpu.river.width", "1"));
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
              "river-terrain");
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
                GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    report.append(Gdx.gl.glGetString(GL20.GL_RENDERER)).append(" / ")
                          .append(Gdx.gl.glGetString(GL20.GL_VERSION)).append('\n');
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    for (String name : families.split(",")) {
                        BoardScene.Surface family = BoardScene.Surface.valueOf(name.trim().toUpperCase(Locale.ROOT));
                        BoardConcrete.tune(concreteMode);
                        BoardScene scene = pavedMap ? pavedMapScene() : paving ? pavedGroundScene()
                              : patterns ? coastPatternScene() : dock ? dockScene()
                              : quay ? quayScene(family) : drops ? dropScene(family) : map ? mapScene(family) : scene(family);
                        setWidth(width);
                        long start = System.nanoTime();
                        tune(.8f, transitions);
                        terrain.update(scene);
                        report.append(String.format(Locale.ROOT, "%s: build %.1f ms%n", family,
                              (System.nanoTime() - start) / 1e6));
                        for (View view : pavedMap ? PAVED_MAP_VIEWS : paving || patterns ? COAST_PATTERN_VIEWS
                              : dock ? DOCK_VIEWS : quay ? QUAY_VIEWS
                              : drops ? DROP_VIEWS : map ? MAP_VIEWS : VIEWS) {
                            if (!views.get(0).isBlank() && !views.contains(view.name())) { continue; }
                            tune(view.grid(), transitions);
                            terrain.update(scene);
                            // Advances the water's wind waves once, as the running board does every frame.
                            terrain.animate(.5f, List.of());
                            terrain.setClay(view.clay());
                            camera.setIsometric(view.isometric());
                            camera.orbit(view.rotation(), view.tilt());
                            camera.camera.zoom = view.zoom();
                            camera.center(BoardGeometry.center(view.focus(), view.level()));
                            profiler.reset();
                            profiler.enable();
                            frame.render(terrain, camera, scene);
                            profiler.disable();
                            report.append(String.format(Locale.ROOT, "  %s: draws=%d vertices=%.0f%n", view.name(),
                                  profiler.getDrawCalls(), profiler.getVertexCount().total));
                            GpuReviewFrame.save(new File(output, family.name().toLowerCase(Locale.ROOT) + "-"
                                  + view.name() + suffix + "-width" + Math.round(100 * width) + ".png"));
                        }
                    }
                    terrain.setClay(false);
                    Files.writeString(new File(output, "report" + suffix + ".txt").toPath(), report);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    BoardGeometry.tune(BoardGeometry.DEFAULTS);
                    BoardRelief.tune(BoardRelief.DEFAULTS);
                    BoardConcrete.tune(originalConcrete);
                    frame.dispose();
                    terrain.dispose();
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("River terrain review", failure.get()); }
        System.out.print(report);
    }

    static void tune(float grid, boolean transitions) {
        BoardGeometry.tune(new BoardGeometry.Tuning(1, .7f, 1, 18, grid, BoardGeometry.DEFAULT_MULTI_HEX_UNIT_SCALE,
              transitions, BoardGeometry.DEFAULT_PADDING));
    }

    static void setWidth(float width) {
        var t = BoardRelief.tuning();
        BoardRelief.tune(new BoardRelief.Tuning(t.shoreShift(), t.shoreRoom(), t.shoreReach(), t.shoreNarrow(),
              t.shoreHard(), t.shorePool(), t.shoreIsle(), t.shoreBlend(), t.shoreWander(), t.wanderCell(),
              t.shoreSpread(), t.landKeep(), t.shoreLip(), t.transition(), t.fullDetailHexes(), t.mediumDetailHexes(), width));
    }

    /** A paved waterfront, including an inlet that must stay open and buildings whose foundations stay intact. */
    static BoardScene quayScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 11; y++) {
                boolean land = x >= 4 && y >= 2 && y <= 8 || x >= 6 && y >= 1 && y <= 9;
                land &= !(x == 4 && y == 3);
                boolean building = land && (x == 4 && y == 5 || x == 5 && y == 7);
                List<BoardScene.Feature> features = building
                      ? List.of(new BoardScene.Feature("buildings/saxarba/building_hard/building_hard_00",
                            0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                Hex hex = new Hex(0);
                if (family == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                if (building) { hex.addTerrain(new Terrain(Terrains.BUILDING, 4)); }
                boolean detailed = BoardFeatures.detailedGround(hex, building
                      ? Map.of(Terrains.BUILDING, features.getFirst().asset()) : Map.of());
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, land ? -1 : 2, false, 0,
                      land ? family : BoardScene.Surface.GRASS, pixels, null, null, null, null, features, List.of(),
                      land ? BoardLiquid.NONE : BoardLiquid.WATER, null, detailed));
            }
        }
        return new BoardScene(0, 9, 11, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** A diagonal and a vertical paved finger connected to a broad waterfront. */
    static BoardScene dockScene() {
        Set<Coords> dock = new HashSet<>();
        Coords at = new Coords(8, 7);
        for (int i = 0; i < 6; i++) { dock.add(at); at = at.translated(5); }
        at = new Coords(10, 6);
        for (int i = 0; i < 5; i++) { dock.add(at); at = at.translated(0); }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 12; y++) {
                Coords coords = new Coords(x, y);
                boolean land = x >= 8 && y >= 6 || dock.contains(coords);
                tiles.add(new BoardScene.Tile(coords, 0, land ? -1 : 2, false, 0,
                      land ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, pixels, null, null, null, null,
                      List.of(), List.of(), land ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 14, 12, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Isolated platforms, straight spans and an angled span, with water on every exposed side. */
    static BoardScene coastPatternScene() {
        Coords building = new Coords(7, 2), bend = new Coords(8, 7);
        Set<Coords> land = new HashSet<>(Set.of(new Coords(2, 2), building, bend));
        for (int y = 6; y <= 10; y++) { land.add(new Coords(2, y)); }
        for (int d : new int[] { 5, 3 }) {
            land.add(bend.translated(d));
            land.add(bend.translated(d, 2));
        }
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 12; x++) {
            for (int y = 0; y < 13; y++) {
                Coords coords = new Coords(x, y);
                boolean dry = land.contains(coords);
                List<BoardScene.Feature> features = coords.equals(building) ? List.of(new BoardScene.Feature(
                      "buildings/saxarba/building_hard/building_hard_00", 0, 0, 0, 1, 2, 0,
                      BoardScene.FeatureKind.BUILDING)) : List.of();
                tiles.add(new BoardScene.Tile(coords, 0, dry ? -1 : 2, false, 0,
                      dry ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, pixels, null, null, null, null,
                      features, List.of(), dry ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 12, 13, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene pavedGroundScene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 14; x++) {
            for (int y = 0; y < 10; y++) {
                boolean paved = x >= 3 && x <= 10 && y >= 3 && y <= 5;
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0,
                      paved ? BoardScene.Surface.CONCRETE : BoardScene.Surface.GRASS, pixels, null, null, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 14, 10, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The reported broad junctions and triangular terrain islands; buildings retain their protected hexes. */
    static BoardScene pavedMapScene() {
        Board board = new Board();
        board.load(new File("data/boards/Deserts/16x17 AeroBase 1.board"));
        BoardScene.Pixels pixels = groundPixels();
        Map<Integer, String> models = Map.of(Terrains.BUILDING, "buildings/saxarba/building_hard/building_hard_00");
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                Coords at = new Coords(x, y);
                Hex hex = board.getHex(at);
                Map<Integer, String> selected = hex.containsTerrain(Terrains.BUILDING) ? models : Map.of();
                tiles.add(new BoardScene.Tile(at, hex.getLevel(), -1, false, 0, BoardFeatures.surface(hex),
                      pixels, null, null, null, null, BoardFeatures.capture(hex, at, selected), List.of(),
                      BoardLiquid.NONE, null, BoardFeatures.detailedGround(hex, selected)));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Parallel streams drop one, two and three surface levels, from left to right. */
    static BoardScene dropScene(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        BoardScene.Pixels pixels = groundPixels();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 7; y++) {
                int level = y < 3 ? x / 3 + 1 : 0;
                int depth = x % 3 == 1 ? 1 : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, 9, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The printed reference's wrapped peninsulas, raised banks and depth-zero shallows. */
    static BoardScene mapScene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        Board board = new Board();
        board.load(new File("data/boards/Map Pack Savannahs/16x17 Mountain Lake (Savannah).board"));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < board.getWidth(); x++) {
            for (int y = 0; y < board.getHeight(); y++) {
                var hex = board.getHex(x, y);
                int depth = hex.containsTerrain(Terrains.WATER) ? hex.terrainLevel(Terrains.WATER) : -1;
                tiles.add(new BoardScene.Tile(new Coords(x, y), hex.getLevel(), depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE,
                      null, true));
            }
        }
        return new BoardScene(0, board.getWidth(), board.getHeight(), tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Every hex, water included, carries the family, as the game gives a desert map's water hexes its theme. */
    static BoardScene scene(BoardScene.Surface family) {
        BoardScene.Pixels pixels = groundPixels();
        int width = LAYOUT[0].length(), height = LAYOUT.length;
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                char cell = LAYOUT[y].charAt(x);
                int level = switch (cell) {
                    case 'r' -> 2;
                    case 'w', 'W' -> 1;
                    default -> cell - '0';
                };
                int depth = switch (cell) {
                    case 'r', 'w' -> 1;
                    case 'W' -> 2;
                    default -> -1;
                };
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, depth, false, 0, family, pixels,
                      null, null, null, null, List.of(), List.of(),
                      depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, width, height, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels groundPixels() {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff8a8a70); }
        }
        return new BoardScene.Pixels(image);
    }
}
