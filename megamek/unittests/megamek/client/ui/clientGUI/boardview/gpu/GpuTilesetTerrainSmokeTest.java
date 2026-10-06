/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The Tactical View's columns follow board edits section by section. */
@Tag("on-demand")
class GpuTilesetTerrainSmokeTest {
    /** At least two sections each way. */
    private static final int WIDTH = 40, HEIGHT = 20;

    @Test
    void anEditRebuildsOnlyTheSectionsOfItsHexAndItsNeighbours() {
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var terrain = new GpuTilesetTerrain();
                var assets = new GpuAssets();
                try {
                    verify(terrain, assets);
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Tactical View sections", failure.get()); }
    }

    private static void verify(GpuTilesetTerrain terrain, GpuAssets assets) throws Exception {
        BoardScene.Pixels art = pixels(0xff60a040);
        int size = GpuTerrain.CHUNK_SIZE, rows = (HEIGHT + size - 1) / size;
        BoardScene scene = scene(Map.of(), Map.of(), art);
        float floor = BoardGeometry.floor(scene);
        terrain.update(scene, floor, assets);
        Map<?, ?> chunks = (Map<?, ?>) field(terrain, "chunks");
        assertEquals((WIDTH + size - 1) / size * rows, chunks.size());

        // Inside the first section, the hex and all its neighbours stay there.
        Coords inside = new Coords(size / 2, size / 2), edge = new Coords(size - 1, size / 2);
        scene = scene(Map.of(inside, 2), Map.of(), art);
        assertEquals(Set.of(0), rebuilt(terrain, chunks, scene, floor, assets));
        for (var face : terrain.surface(scene, inside, floor).top()) {
            assertEquals(2 * BoardGeometry.level(), face.a().z, .0001f, "The edited column stands at its new level");
        }
        // Raised on the section's eastern edge, it walls down to the neighbours across that edge too.
        scene = scene(Map.of(inside, 2, edge, 1), Map.of(), art);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene, floor, assets));
        // New art at the same height leaves the neighbours' walls and rims as they were.
        scene = scene(Map.of(inside, 2, edge, 1), Map.of(edge, pixels(0xff806040)), art);
        assertEquals(Set.of(0), rebuilt(terrain, chunks, scene, floor, assets));
        // New markings change no column.
        List<BoardScene.Tile> marked = new ArrayList<>(scene.tiles());
        marked.replaceAll(tile -> tile.withTactical(pixels(0x80ff0000)));
        scene = scene.withTiles(marked);
        assertEquals(Set.of(), rebuilt(terrain, chunks, scene, floor, assets));

        // Deepening water keeps the liquid level fixed, but changes the neighbours' underwater walls across a section edge.
        List<BoardScene.Tile> water = new ArrayList<>();
        for (var tile : scene.tiles()) { water.add(pool(tile.coords(), 2, art)); }
        scene = scene.withTiles(water);
        float bedFloor = -6 * BoardGeometry.level();
        terrain.update(scene, bedFloor, assets);
        water = new ArrayList<>(water);
        water.set(edge.getX() * HEIGHT + edge.getY(), pool(edge, 4, art));
        scene = scene.withTiles(water);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene, bedFloor, assets));
        for (var face : terrain.surface(scene, edge, bedFloor).faces()) {
            assertEquals(-4 * BoardGeometry.level(), face.a().z, .001f, "The artwork follows the edited bed depth");
        }

        // A bank borrows land art across a section boundary, even when only the land's pixels changed.
        water.set(edge.getX() * HEIGHT + edge.getY(), land(edge, art));
        scene = scene.withTiles(water);
        terrain.update(scene, bedFloor, assets);
        water = new ArrayList<>(water);
        water.set(edge.getX() * HEIGHT + edge.getY(), land(edge, pixels(0xff907050)));
        scene = scene.withTiles(water);
        assertEquals(Set.of(0, rows), rebuilt(terrain, chunks, scene, bedFloor, assets));

        // Sand fades over the solid bank; liquid keeps its existing opacity right up to its fitted boundary.
        Coords center = new Coords(1, 1);
        for (var bank : List.of(BoardScene.Surface.GRASS, BoardScene.Surface.CONCRETE)) {
            List<BoardScene.Tile> shore = new ArrayList<>();
            for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
                Coords at = new Coords(x, y);
                shore.add(at.equals(center) ? pool(at, 2, art)
                      : new BoardScene.Tile(at, 1, -1, false, 0, bank, art, null, null, null, null,
                            List.of(), List.of(), BoardLiquid.NONE, art));
            }
            scene = new BoardScene(0, 3, 3, shore, List.of(), List.of(), -1, "", List.of());
            terrain.update(scene, bedFloor, assets);
            var liquid = (ModelInstance) field(chunks.values().iterator().next(), "liquid");
            var support = terrain.surface(scene, center, bedFloor);
            int vertices = 0;
            for (var mesh : liquid.model.meshes) {
                int stride = mesh.getVertexSize() / Float.BYTES;
                int alpha = mesh.getVertexAttribute(Usage.ColorUnpacked).offset / Float.BYTES + 3;
                int position = mesh.getVertexAttribute(Usage.Position).offset / Float.BYTES;
                float[] data = new float[mesh.getNumVertices() * stride];
                mesh.getVertices(data);
                for (int i = 0; i < mesh.getNumVertices(); i++) {
                    assertEquals(1, data[i * stride + alpha], .0001f, "Water must not fade beside " + bank);
                    float x = data[i * stride + position], y = data[i * stride + position + 1];
                    assertTrue(support.water().stream().anyMatch(face -> Float.isFinite(face.height(x, y))),
                          "Uploaded water stays inside the same shore boundary used by picking: " + bank);
                    vertices++;
                }
            }
            assertTrue(vertices > 0, "Inspect the actual uploaded liquid mesh");
        }
    }

    @Test
    void capturesNaturalShoresAndARaisedPoolWithTheRealTileset() throws Exception {
        int width = 12, height = 10;
        Hex[] hexes = new Hex[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            boolean river = x == 3 || y >= 6 && x >= 2 && x <= 5;
            boolean pool = x == 8 && y == 4;
            Hex hex = new Hex(pool ? 6 : x >= 7 ? 1 : y < 5 ? 2 : 0);
            if (river || pool) { hex.addTerrain(new Terrain(Terrains.WATER, pool || x == 3 ? 1 : 2)); }
            if (x == 6 && y >= 6) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
            hexes[y * width + x] = hex;
        }
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(new Board(width, height, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            var config = GpuBoardWindow.configuration(false);
            config.setWindowedMode(1440, 1080);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuReviewFrame frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                          BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                    try {
                        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"),
                              "tactical-shores");
                        Files.createDirectories(output.toPath());
                        BoardCamera camera = new BoardCamera();
                        camera.resize(1440, 1080);
                        terrain.update(scene);
                        Coords pool = new Coords(8, 4);
                        var original = terrain.tacticalSurface(pool);
                        terrain.setTacticalView(true);
                        assertNotSame(original, terrain.tacticalSurface(pool));
                        terrain.setTacticalView(false);
                        assertSame(original, terrain.tacticalSurface(pool), "Switching views retains the exact 3D terrain");
                        terrain.setTacticalView(true);
                        for (boolean oblique : new boolean[] { false, true }) {
                            camera.setIsometric(oblique);
                            camera.camera.zoom = .65f;
                            camera.center(BoardGeometry.center(new Coords(5, 5), 2));
                            frame.render(terrain, camera, scene);
                            terrain.animate(.5f, List.of());
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, oblique ? "oblique.png" : "top.png"));
                        }
                        camera.camera.zoom = .16f;
                        camera.center(BoardGeometry.center(new Coords(8, 4), 5));
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "raised-pool.png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) { failure.set(error); }
                    finally {
                        frame.dispose();
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, config);
        }
        if (failure.get() != null) { throw new AssertionError("Tactical shore review", failure.get()); }
    }

    private static BoardScene.Tile pool(Coords coords, int depth, BoardScene.Pixels art) {
        return new BoardScene.Tile(coords, 0, depth, false, 0, BoardScene.Surface.GRASS, art, null, null, null, null,
              List.of(), List.of(), BoardLiquid.WATER, art);
    }

    private static BoardScene.Tile land(Coords coords, BoardScene.Pixels art) {
        return new BoardScene.Tile(coords, 0, -1, false, 0, BoardScene.Surface.GRASS, art, null, null, null, null,
              List.of(), List.of(), BoardLiquid.NONE, art);
    }

    /** The indices of the sections whose meshes an update to {@code scene} replaced. */
    private static Set<Object> rebuilt(GpuTilesetTerrain terrain, Map<?, ?> chunks, BoardScene scene, float floor,
          GpuAssets assets) {
        Map<Object, Object> before = new HashMap<>(chunks);
        boolean reported = terrain.update(scene, floor, assets);
        Set<Object> result = chunks.keySet().stream().filter(index -> chunks.get(index) != before.get(index))
              .collect(Collectors.toSet());
        // GpuTerrain redraws the shadow map on this report.
        assertEquals(!result.isEmpty(), reported, "The update reports exactly when it rebuilt a section");
        return result;
    }

    /** A level board with the given hexes raised or repainted; the floor stays below the lowest hex. */
    private static BoardScene scene(Map<Coords, Integer> raised, Map<Coords, BoardScene.Pixels> painted,
          BoardScene.Pixels art) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, raised.getOrDefault(coords, 0), -1, false, 0,
                      BoardScene.Surface.GRASS, art, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE,
                      painted.getOrDefault(coords, art)));
            }
        }
        return new BoardScene(0, WIDTH, HEIGHT, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels pixels(int argb) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, argb); }
        }
        return new BoardScene.Pixels(image);
    }
}
