/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.profiling.GLProfiler;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The scenery families that share one mesh and differ by colour (parked cars, herd animals, pools and ponds) and the
 * snow forms of placed trees, each hex in close-up: the legacy render, the imported objects as game scenery, and the
 * imported objects as the editor draws them (instanced). Each view also reports one overview frame's draw calls. The
 * captures compare against the same captures made before the colour field (see docs/3d-board-editor.md).
 */
@Tag("on-demand")
class GpuSceneryColourSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
          "scenery-colours");
    /** The trees whose winter form is a material variant of the bare tree. */
    static final List<String> SNOW_SPECIES = List.of("orchard-leaning", "orchard-round", "orchard-spreading",
          "orchard-upright", "orchard-vase", "orchard-young", "pine", "pine-broad", "pine-slender", "pine-layered",
          "tree-broad", "birch", "willow-broad", "tree-layered", "birch-spreading", "birch-young", "tree-slender", "tree",
          "birch-tall");

    /**
     * Paved hexes where the imported board gets a car in a colour far from the model's red: mirrored (magenta), and
     * upright (white). Both have red >= 0x80 and blue 0xFF, so their 0xRRGGBB float is an odd integer above 2^23, which
     * a rounding decode on the GPU would turn red and black.
     */
    private static final Coords MIRRORED = new Coords(12, 1), FAR = new Coords(12, 3);
    /** Pixels of a capture whose colour may differ by more than 2/255 between two draws of the same objects. */
    private static final int EDGE_PIXELS = 40;

    /** The legacy board: every car park, the suburbs and pools with cars or ponds, all herds, and trees on snow. */
    static Map<Coords, String> fixture() {
        Map<Coords, String> hexes = new LinkedHashMap<>();
        String[] cars = { "00", "01", "02", "03", "04", "05", "06", "07", "98", "99" };
        for (int i = 0; i < cars.length; i++) { hexes.put(new Coords(i + 1, 1), "pavement:1;fluff:5:" + cars[i]); }
        String[] pools = { "fluff:7:03", "fluff:7:04", "fluff:7:05", "fluff:6:18", "fluff:92:2", "fluff:92:4",
              "fluff:94:1", "fluff:94:2", "fluff:94:3", "fluff:94:4", "fluff:94:5" };
        for (int i = 0; i < pools.length; i++) { hexes.put(new Coords(i + 1, 3), pools[i]); }
        // The tileset picks a herd's image by the hex; two rows of each herd code show every herd.
        for (int i = 0; i < 24; i++) { hexes.put(new Coords(i % 12, 5 + i / 12), "fluff:13:0" + i % 5); }
        for (int i = 0; i < SNOW_SPECIES.size(); i++) { hexes.put(new Coords(i % 10 + 1, 8 + i / 10), "snow:1"); }
        hexes.put(MIRRORED, "pavement:1");
        hexes.put(FAR, "pavement:1");
        return hexes;
    }

    /** The legacy fixture imported, with snow trees placed and the two far-coloured cars. */
    static Board imported() throws Exception {
        var hexes = fixture();
        var imported = Board.createEmptyBoard(13, 11);
        for (var entry : hexes.entrySet()) { imported.setHex(entry.getKey(), new Hex(0, entry.getValue(), "")); }
        SwingUtilities.invokeAndWait(() -> {
            BoardSceneryLayouts.importBoard(imported);
            for (int i = 0; i < SNOW_SPECIES.size(); i++) {
                var coords = new Coords(i % 10 + 1, 8 + i / 10);
                var hex = imported.getHex(coords).duplicate();
                hex.setDecorations(List.of(new BoardDecoration("snow-" + i, "prop", SNOW_SPECIES.get(i), null, 0, 0,
                      30, false, 1, BoardDecoration.Placement.ground(), 0, false)));
                imported.setHex(coords, hex);
            }
            for (var coords : List.of(MIRRORED, FAR)) {
                var hex = imported.getHex(coords).duplicate();
                hex.setDecorations(List.of(new BoardDecoration("far-" + coords.getBoardNum(), "prop", "scenery/vehicles/car", null,
                      0, 0, 30, coords.equals(MIRRORED), 1, BoardDecoration.Placement.ground(), 0, false)
                      .withColours(BoardDecoration.Colours.of(coords.equals(MIRRORED) ? "#ff00ff" : "#ffffff"))));
                imported.setHex(coords, hex);
            }
        });
        return imported;
    }

    @Test
    void colourVariantsAndSnowTreesLookTheSameAsBefore() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        var hexes = fixture();
        var legacy = Board.createEmptyBoard(13, 11);
        for (var entry : hexes.entrySet()) { legacy.setHex(entry.getKey(), new Hex(0, entry.getValue(), "")); }
        var imported = imported();
        int recoloured = 0;
        for (var entry : hexes.entrySet()) {
            var objects = imported.getHex(entry.getKey()).getDecorations();
            System.out.printf("COLOUR fixture %s %s: %s%n", entry.getKey().getBoardNum(), entry.getValue(),
                  objects.stream().filter(o -> !o.asset().equals("tree-broad")).map(o -> o.asset() + o.colours().slots()).toList());
            for (var object : objects) {
                // Every coat or paint is its family's one mesh in that coat's colours.
                assertFalse(object.asset().matches("scenery/(vehicles/car-.*|farm/(hen|rooster|bison)-.*|parks/pond-.*)"), object.asset());
                if (!object.colours().slots().isEmpty()) { recoloured++; }
            }
        }
        assertTrue(recoloured > 50, "Imported cars, animals and ponds keep their colours: " + recoloured);
        var scenes = List.of(GpuLegacyImportSmokeTest.scene(legacy), GpuLegacyImportSmokeTest.scene(imported),
              GpuLegacyImportSmokeTest.scene(imported));
        String[] views = { "legacy", "imported", "editor" };
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    for (int view = 0; view < views.length; view++) {
                        var terrain = new GpuTerrain();
                        try {
                            var scene = scenes.get(view);
                            var camera = new BoardCamera();
                            camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                            terrain.editableObjects(view == 2);
                            terrain.setTacticalView(false);
                            frame.prepare(terrain, camera, scene);
                            terrain.update(scene);
                            camera.center(BoardGeometry.center(new Coords(6, 5), 0));
                            camera.camera.zoom = .9f;
                            camera.update();
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            frame.render(terrain, camera, scene);
                            profiler.enable();
                            profiler.reset();
                            frame.render(terrain, camera, scene);
                            System.out.printf("COLOUR %s overview: %d draw calls, %d GL calls%n", views[view],
                                  profiler.getDrawCalls(), profiler.getCalls());
                            profiler.disable();
                            GpuReviewFrame.save(new File(OUTPUT, views[view] + "-overview.png"));
                            for (var coords : hexes.keySet()) {
                                camera.center(BoardGeometry.center(coords, 0));
                                camera.camera.zoom = .1f;
                                camera.update();
                                GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                                frame.render(terrain, camera, scene);
                                GpuReviewFrame.save(new File(OUTPUT, views[view] + "-" + coords.getBoardNum() + ".png"));
                            }
                        } finally { terrain.dispose(); }
                    }
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Scenery colour review", failure.get()); }
        // The editor recolours one shared mesh per model on the GPU; the game draws CPU-recoloured copies. Both must
        // show every hex alike: cars on pavement (one mirrored, drawn one by one), herds, pools, ponds and snow trees.
        for (var coords : hexes.keySet()) {
            int[] differing = differing(new File(OUTPUT, "imported-" + coords.getBoardNum() + ".png"),
                  new File(OUTPUT, "editor-" + coords.getBoardNum() + ".png"));
            System.out.printf("COLOUR parity %s: %d px over 2/255, %d px over 8/255%n", coords.getBoardNum(), differing[0], differing[1]);
            assertTrue(differing[0] <= EDGE_PIXELS, "Editor and game draw " + coords.getBoardNum() + " alike: " + differing[0]);
        }
    }

    /**
     * In the editor every colouring of a model is one mesh (option A): the fixture draws as many times as with every
     * object in its model's own colours. A partial rebuild of the chunk keeps an untouched car's paint, and a drag's
     * preview pose keeps the dragged car's.
     */
    @Test
    void theEditorDrawsEveryColouringOfAModelFromOneMeshAndKeepsItsColours() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        Coords cars = new Coords(1, 1), edit = new Coords(7, 7);
        var board = imported();
        var uniform = imported();
        for (var hex : java.util.stream.IntStream.range(0, uniform.getWidth() * uniform.getHeight())
              .mapToObj(i -> uniform.getHex(i % uniform.getWidth(), i / uniform.getWidth())).toList()) {
            hex.setDecorations(hex.getDecorations().stream().map(o -> o.withColours(BoardDecoration.Colours.NONE)).toList());
        }
        BoardScene coloured = GpuLegacyImportSmokeTest.scene(board), plain = GpuLegacyImportSmokeTest.scene(uniform);
        // Another hex of the cars' chunk changes; then one recoloured car moves, keeping its colours.
        var raised = board.getHex(edit).duplicate();
        raised.setLevel(1);
        board.setHex(edit, raised);
        BoardScene edited = GpuLegacyImportSmokeTest.scene(board);
        var lot = board.getHex(cars).duplicate();
        var car = lot.getDecorations().stream().filter(o -> o.asset().equals("scenery/vehicles/car") && !o.colours().slots().isEmpty())
              .findFirst().orElseThrow();
        lot.setDecorations(lot.getDecorations().stream().map(o -> o != car ? o
              : o.transform(o.x() + .08, o.y(), o.rotation(), o.mirror(), o.scale(), o.placement())).toList());
        board.setHex(cars, lot);
        BoardScene moved = GpuLegacyImportSmokeTest.scene(board);
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var profiler = new GLProfiler(Gdx.graphics);
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                var terrain = new GpuTerrain();
                try {
                    terrain.editableObjects(true);
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    int[] draws = new int[2];
                    for (int index = 0; index < 2; index++) {
                        var scene = index == 0 ? plain : coloured;
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        camera.center(BoardGeometry.center(new Coords(6, 5), 0));
                        camera.camera.zoom = .9f;
                        camera.update();
                        GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                        frame.render(terrain, camera, scene);
                        profiler.enable();
                        profiler.reset();
                        terrain.render(camera.camera, false);
                        draws[index] = profiler.getDrawCalls();
                        profiler.disable();
                    }
                    System.out.printf("COLOUR editor opaque draws: own colours %d, every colour %d%n", draws[0], draws[1]);
                    assertEquals(draws[0], draws[1], "Every colouring of a model shares its draws");

                    camera.center(BoardGeometry.center(cars, 0));
                    camera.camera.zoom = .1f;
                    camera.update();
                    GpuTerrainLodSmokeTest.settle(terrain, null, coloured, camera);
                    frame.render(terrain, camera, coloured);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-rebuild-before.png"));
                    Object bounds = carBounds(terrain, cars);
                    terrain.update(edited);
                    GpuTerrainLodSmokeTest.settle(terrain, null, edited, camera);
                    assertTrue(bounds == carBounds(terrain, cars), "The edit rebuilds the chunk and reuses the cars' hex");
                    frame.render(terrain, camera, edited);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-rebuild-after.png"));

                    terrain.previewEditorObjects(moved);
                    frame.render(terrain, camera, moved);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-drag-preview.png"));
                    terrain.update(moved);
                    GpuTerrainLodSmokeTest.settle(terrain, null, moved, camera);
                    frame.render(terrain, camera, moved);
                    GpuReviewFrame.save(new File(OUTPUT, "editor-drag-released.png"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Editor colour slots", failure.get()); }
        int[] rebuilt = differing(new File(OUTPUT, "editor-rebuild-before.png"), new File(OUTPUT, "editor-rebuild-after.png"));
        int[] dragged = differing(new File(OUTPUT, "editor-drag-preview.png"), new File(OUTPUT, "editor-drag-released.png"));
        int[] moving = differing(new File(OUTPUT, "editor-rebuild-after.png"), new File(OUTPUT, "editor-drag-preview.png"));
        System.out.printf("COLOUR rebuild %s, drag preview against release %s, preview against before %s%n",
              java.util.Arrays.toString(rebuilt), java.util.Arrays.toString(dragged), java.util.Arrays.toString(moving));
        assertTrue(rebuilt[0] <= EDGE_PIXELS, "An untouched car keeps its paint through a rebuild of its chunk: " + rebuilt[0]);
        assertTrue(moving[1] > 200, "The preview moves the car: " + moving[1]);
        assertTrue(dragged[0] <= EDGE_PIXELS, "The dragged car keeps its paint: " + dragged[0]);
    }

    /** The installed bounds of a placed car of the hex, which a reused hex keeps. */
    private static Object carBounds(GpuTerrain terrain, Coords coords) throws ReflectiveOperationException {
        for (Object chunk : (List<?>) field(terrain, "chunks")) {
            for (Object prop : (List<?>) field(chunk, "props")) {
                var instance = (com.badlogic.gdx.graphics.g3d.ModelInstance) field(prop, "instance");
                if (coords.equals(field(prop, "coords")) && field(prop, "decorationId") != null
                      && instance.nodes.first().id.equals("car")) { return field(prop, "bounds"); }
            }
        }
        throw new AssertionError("No car on " + coords);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** Pixels whose largest channel difference exceeds 2/255, and 8/255. */
    static int[] differing(File first, File second) throws java.io.IOException {
        var a = javax.imageio.ImageIO.read(first);
        var b = javax.imageio.ImageIO.read(second);
        int[] result = new int[2];
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y), largest = 0;
                for (int shift = 0; shift < 24; shift += 8) { largest = Math.max(largest, Math.abs((p >> shift & 255) - (q >> shift & 255))); }
                if (largest > 2) { result[0]++; }
                if (largest > 8) { result[1]++; }
            }
        }
        return result;
    }
}
