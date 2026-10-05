/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import megamek.common.board.Coords;
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

        // Both natural banks and quays meet liquid with full coverage, without a shore fade at the boundary.
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
            int vertices = 0;
            for (var mesh : liquid.model.meshes) {
                int stride = mesh.getVertexSize() / Float.BYTES;
                int alpha = mesh.getVertexAttribute(Usage.ColorUnpacked).offset / Float.BYTES + 3;
                float[] data = new float[mesh.getNumVertices() * stride];
                mesh.getVertices(data);
                for (int i = 0; i < mesh.getNumVertices(); i++) {
                    assertEquals(1, data[i * stride + alpha], .0001f, "Water must not fade beside " + bank);
                    vertices++;
                }
            }
            assertTrue(vertices > 0, "Inspect the actual uploaded liquid mesh");
        }
    }

    private static BoardScene.Tile pool(Coords coords, int depth, BoardScene.Pixels art) {
        return new BoardScene.Tile(coords, 0, depth, false, 0, BoardScene.Surface.GRASS, art, null, null, null, null,
              List.of(), List.of(), BoardLiquid.WATER, art);
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
