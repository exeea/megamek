/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.badlogic.gdx.utils.FloatArray;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CPU cost of what a terrain worker does per hex before ground cover can show: the finished surface at each detail
 * level, and planting grass, crops and reeds on it. Measured on shipped boards, reported, not asserted.
 */
@Tag("on-demand")
@Tag("gpu-benchmark")
class GpuCoverPlantingSmokeTest {
    private static final Map<String, String> BOARDS = Map.of(
          "elevated", "data/boards/unofficial/Vamp/Elevated Highway.board",
          "mesacity", "data/boards/unofficial/SimonLandmine/96x102/96x102 MesaCity1.board");
    private static final Map<String, Coords> FOCUS = Map.of("elevated", new Coords(24, 28), "mesacity", new Coords(36, 24));
    /** A half-size emblem on a hex's centre: the planting cost of a painted hex. */
    private static final BoardDecoration EMBLEM = new BoardDecoration("emblem", "decal", "decal/emblems/red-cross", null, 0, 0,
          0, false, .5, BoardDecoration.Placement.ground(), 0, false);

    @ParameterizedTest
    @ValueSource(strings = { "elevated", "mesacity" })
    void timesSurfacesAndPlanting(String name) throws Exception {
        String boards = System.getProperty("megamek.gpu.coverBoards", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(boards.isEmpty() || List.of(boards.split(",")).contains(name));
        Board board = new Board();
        board.load(new File(BOARDS.get(name)));
        var report = new StringBuilder();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board)) {
            BoardScene scene = fixture.source.takeFrame().scene();
            TerrainSettings settings = TerrainSettings.capture();
            try (TerrainSettings.Scope ignored = TerrainSettings.use(settings)) {
                // The chunk around the focus and its eastern neighbour: 128 hexes of the reported views.
                Coords focus = FOCUS.get(name);
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int cx = 0; cx < 2; cx++) {
                    int startX = focus.getX() / TerrainLod.CHUNK_SIZE * TerrainLod.CHUNK_SIZE + cx * TerrainLod.CHUNK_SIZE;
                    int startY = focus.getY() / TerrainLod.CHUNK_SIZE * TerrainLod.CHUNK_SIZE;
                    for (int x = startX; x < Math.min(scene.width(), startX + TerrainLod.CHUNK_SIZE); x++) {
                        for (int y = startY; y < Math.min(scene.height(), startY + TerrainLod.CHUNK_SIZE); y++) {
                            tiles.add(scene.tile(new Coords(x, y)));
                        }
                    }
                }
                int grassHexes = 0, cropHexes = 0, reedHexes = 0, legacyPaint = 0;
                for (var tile : tiles) {
                    var kind = BoardBiome.plantKind(scene, tile);
                    boolean grows = kind == BoardScene.Biome.FIELD || kind == BoardScene.Biome.MARSH || GpuGroundCover.grows(scene, tile);
                    if (kind == BoardScene.Biome.FIELD) { cropHexes++; }
                    else if (kind == BoardScene.Biome.MARSH) { reedHexes++; }
                    else if (GpuGroundCover.grows(scene, tile)) { grassHexes++; }
                    // Cover on these plants around their legacy paint overlay as well (BoardDecals.Opacity).
                    if (grows && BoardDecals.legacyPaint(tile) != null) { legacyPaint++; }
                }
                report.append(String.format(Locale.ROOT, "%s: %d hexes around %s: %d grass, %d field, %d marsh;"
                      + " %d of them with legacy paint%n", name, tiles.size(), focus, grassHexes, cropHexes, reedHexes, legacyPaint));
                for (int round = 0; round < 2; round++) {
                    for (TerrainLod lod : new TerrainLod[] { TerrainLod.FULL, TerrainLod.MEDIUM, TerrainLod.COARSE }) {
                        Map<Coords, BoardSurface> surfaces = new HashMap<>();
                        long t0 = System.nanoTime();
                        for (var tile : tiles) { surfaces.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
                        long t1 = System.nanoTime();
                        for (var surface : surfaces.values()) {
                            if (surface.relief.sculpted() || BoardGeometry.tuning().stepsBetweenTops()) {
                                surface.walls(scene, 0, surfaces);
                            }
                        }
                        long t2 = System.nanoTime();
                        Map<Coords, BoardTacticalGeometry.Surface> supports = new HashMap<>();
                        for (var surface : surfaces.values()) {
                            supports.put(surface.tile.coords(), BoardTacticalGeometry.Surface.of(surface, scene, 0));
                        }
                        long t3 = System.nanoTime();
                        long grassRoots = 0, cropRoots = 0, canopy = 0, reeds = 0, paintedRoots = 0;
                        long plantGrass = 0, plantCrops = 0, plantReeds = 0, plantPainted = 0;
                        for (var tile : tiles) {
                            var support = supports.get(tile.coords());
                            var kind = BoardBiome.plantKind(scene, tile);
                            long start = System.nanoTime();
                            if (kind == BoardScene.Biome.FIELD) {
                                var crops = GpuBiomeVegetation.plant(scene, tile, support, null);
                                cropRoots += crops.roots().size / 4;
                                canopy += crops.canopy().size / 4;
                                plantCrops += System.nanoTime() - start;
                            } else if (kind == BoardScene.Biome.MARSH) {
                                FloatArray roots = GpuBiomeVegetation.plantReeds(scene, tile, support, 1, null);
                                reeds += roots.size / 4;
                                plantReeds += System.nanoTime() - start;
                            } else if (GpuGroundCover.grows(scene, tile)) {
                                FloatArray roots = GpuGroundCover.plant(scene, tile, support, null);
                                grassRoots += roots.size / 4;
                                plantGrass += System.nanoTime() - start;
                                // The same hex painted: its own legacy paint plus a half-size emblem on its centre.
                                long painted = System.nanoTime();
                                var paint = BoardDecals.Opacity.of(tile, List.of(new BoardDecals.Stamp(tile.coords(), EMBLEM)));
                                paintedRoots += GpuGroundCover.plant(scene, tile, support, paint).size / 4;
                                plantPainted += System.nanoTime() - painted;
                            }
                        }
                        int faces = surfaces.values().stream().mapToInt(surface -> surface.faces.size()).sum();
                        report.append(String.format(Locale.ROOT,
                              "  %s round %d: surfaces %.0f ms, walls %.0f ms, supports %.0f ms (%d faces);"
                                    + " grass %.0f ms for %d roots on %d hexes (painted: %.0f ms for %d roots);"
                                    + " crops %.0f ms for %d strips + %d runs on %d hexes;"
                                    + " reeds %.0f ms for %d clumps on %d hexes%n",
                              lod, round, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6, faces, plantGrass / 1e6,
                              grassRoots, grassHexes, plantPainted / 1e6, paintedRoots, plantCrops / 1e6, cropRoots, canopy,
                              cropHexes, plantReeds / 1e6, reeds, reedHexes));
                    }
                }
            }
        }
        System.out.print(report);
    }
}
