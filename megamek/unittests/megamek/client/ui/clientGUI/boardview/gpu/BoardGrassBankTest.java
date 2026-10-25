/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.FloatArray;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The same finished shoulder supports turf, blade roots and surface queries at both planted detail levels. */
class BoardGrassBankTest {
    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void convertedMountainLakeGrowsGrassOnIncomingSlopeToes(TerrainLod lod) throws Exception {
        var geometry = BoardGeometry.tuning();
        var relief = BoardRelief.tuning();
        try {
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            BoardRelief.tune(BoardRelief.DEFAULTS);
            var board = BoardFile.loadSheet(new File(Configuration.boardsDir(),
                  "Map Set 2/16x17 Mountain Lake.board"), false, true);
            var scene = GpuLegacyImportSmokeTest.scene(board);
            var owners = List.of(new Coords(5, 14), new Coords(5, 15), new Coords(5, 16));
            var tiles = owners.stream().map(scene::tile).toList();
            float floor = BoardGeometry.floor(scene), sink = BoardGeometry.width() * .001f;
            Map<Coords, BoardSurface> sources = new HashMap<>();
            for (var tile : tiles) { sources.put(tile.coords(), new BoardSurface(scene, tile, lod)); }
            sources = BoardVegetation.prepare(scene, tiles, lod, floor, sources);
            Set<List<Float>> positions = new HashSet<>();
            for (var tile : tiles) {
                var surface = BoardTacticalGeometry.Surface.of(sources.get(tile.coords()), scene, floor, sources);
                var planted = GpuGroundCover.plant(scene, tile, surface, null);
                var rebuilt = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile, lod), scene, floor);
                assertEquals(planted, GpuGroundCover.plant(scene, tile, rebuilt, null),
                      "An individual cache miss and shared chunk preparation use the same finished support");
                var ground = surface.vegetation();
                assertNotNull(ground);
                for (var face : ground.faces()) {
                    for (var vertex : List.of(face.a(), face.b(), face.c())) {
                        var normal = ground.normals().get(vertex);
                        assertNotNull(normal, "Every support vertex carries its rendered normal");
                        assertEquals(1, normal.len(), .002f, "Support normals are finite and normalized");
                    }
                }
                var own = new HashSet<>(surface.top());
                own.addAll(surface.slopes());
                var support = new GpuBiomeVegetation.Support(ground.faces());
                int incoming = 0;
                for (int i = 0; i < planted.size; i += 4) {
                    float x = planted.get(i), y = planted.get(i + 1), z = planted.get(i + 2);
                    assertTrue(BoardGeometry.contains(tile.coords(), x, y), "A blade stays in its owning hex");
                    assertTrue(positions.add(List.of(x, y)), "Adjacent owners cannot duplicate a blade");
                    var face = support.face(x, y);
                    assertNotNull(face);
                    assertEquals(face.height(x, y), z + sink, .002f,
                          "The root sits on the installed surface, including a neighbour's slope");
                    if (!own.contains(face)) { incoming++; }
                }
                if (!tile.coords().equals(owners.getFirst())) {
                    assertTrue(incoming > 20, lod + " restores the incoming green toe at " + tile.coords() + ": " + incoming);
                }
            }
        } finally {
            BoardRelief.tune(relief);
            BoardGeometry.tune(geometry);
        }
    }

    @Test
    void grassDensityFollowsInterpolatedSlopeInsteadOfTriangleBoundaries() {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(BoardSurfaceBlendTest.CENTER);
        var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
        var meadow = roots(GpuGroundCover.plant(scene, tile, surface, null));
        float left = BoardGeometry.centerX(tile.coords()) - BoardGeometry.width() / 2;
        // Keep the root support fixed while its shading turns gradually from level turf to a steep slope.
        Map<Vector3, Vector3> normals = new IdentityHashMap<>();
        for (var face : surface.vegetation().faces()) {
            for (var vertex : List.of(face.a(), face.b(), face.c())) {
                float up = 1 - .45f * (vertex.x - left) / BoardGeometry.width();
                normals.put(vertex, new Vector3((float) Math.sqrt(1 - up * up), 0, up));
            }
        }
        var slope = new BoardTacticalGeometry.Surface(surface.top(), surface.slopes(), surface.faces(), surface.water(),
              surface.walls(), surface.waterfalls(), surface.frozenFalls(), surface.highestTop(),
              new BoardVegetation.Ground(surface.vegetation().faces(), normals));
        var faded = roots(GpuGroundCover.plant(scene, tile, slope, null));
        assertTrue(meadow.containsAll(faded), "The fade changes density without moving or reranking surviving blades");
        int[] candidates = new int[3], kept = new int[3];
        for (var root : meadow) {
            int band = Math.min(2, (int) (3 * (root.getFirst() - left) / BoardGeometry.width()));
            candidates[band]++;
            if (faded.contains(root)) { kept[band]++; }
        }
        float previous = 1.2f;
        for (int band = 0; band < 3; band++) {
            assertTrue(candidates[band] > 500 && kept[band] > 0, "Each slope band has a populated sample");
            float retained = kept[band] / (float) candidates[band];
            assertTrue(previous - retained > .15f, "Density decreases gradually towards steep ground: " + retained);
            if (band == 0) { assertTrue(retained > .8f, "Level turf retains dense grass"); }
            if (band == 2) { assertTrue(retained < .3f, "The steep end is sparse instead of a solid blade edge"); }
            previous = retained;
        }
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void grassThinsTowardsOpenWaterWithoutMovingSurvivingBlades(TerrainLod lod) {
        var dry = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var at = BoardSurfaceBlendTest.CENTER;
        var tile = dry.tile(at);
        var support = BoardTacticalGeometry.Surface.of(new BoardSurface(dry, tile, lod), dry, BoardGeometry.floor(dry));
        var meadow = roots(GpuGroundCover.plant(dry, tile, support, null));
        float metre = BoardRelief.metres(1);
        for (int edge = 0; edge < 6; edge++) {
            var water = at.translated(BoardGeometry.edgeDirection(edge));
            var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS,
                  0, c.equals(water) ? 1 : -1, 0));
            // Keep the published support fixed to isolate density from changes in the shore's geometry.
            var planted = GpuGroundCover.plant(scene, scene.tile(at), support, null);
            assertEquals(planted, GpuGroundCover.plant(scene, scene.tile(at), support, null),
                  "Rebuilding the bank keeps the same blades");
            var shore = roots(planted);
            assertTrue(meadow.containsAll(shore), "Thinning preserves each surviving root's position, height and rank");
            var a = BoardGeometry.corner(at, 0, edge);
            var b = BoardGeometry.corner(at, 0, edge + 1);
            int[] candidates = new int[3], kept = new int[3];
            int inland = 0;
            for (var root : meadow) {
                float distance = Intersector.distanceSegmentPoint(a.x, a.y, b.x, b.y, root.get(0), root.get(1)) / metre;
                if (distance >= 3) {
                    assertTrue(shore.contains(root), "The inland meadow keeps every blade");
                    inland++;
                } else {
                    int band = (int) distance;
                    candidates[band]++;
                    if (shore.contains(root)) { kept[band]++; }
                }
            }
            float previous = 0;
            for (int band = 0; band < 3; band++) {
                assertTrue(candidates[band] > 50 && kept[band] > 0, "Each metre of shore has a populated sample");
                float retained = kept[band] / (float) candidates[band];
                if (band == 0) { assertTrue(retained < .3f, "Grass is sparse beside the shore: " + retained); }
                else { assertTrue(retained > previous + .15f, "Grass grows progressively denser inland"); }
                previous = retained;
            }
            assertTrue(previous > .7f && inland > 1000, "The fade rejoins a dense, unchanged meadow");
        }
    }

    @Test
    void inlandRaisedAndFrozenWaterDoNotClearTheMeadow() {
        var at = BoardSurfaceBlendTest.CENTER;
        var dry = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var support = BoardTacticalGeometry.Surface.of(new BoardSurface(dry, dry.tile(at)), dry, BoardGeometry.floor(dry));
        var meadow = GpuGroundCover.plant(dry, dry.tile(at), support, null);
        for (int level : new int[] { -3, -1, 0, 1 }) {
            var water = at.translated(0);
            var scene = BoardSurfaceBlendTest.scene(c -> c.equals(water)
                  ? new BoardScene.Tile(c, level, 1, level == 0, 0, BoardScene.Surface.GRASS, null, null,
                        null, null, List.of(), List.of(), BoardLiquid.WATER, null, true)
                  : dry.tile(c));
            assertEquals(meadow, GpuGroundCover.plant(scene, scene.tile(at), support, null),
                  "A frozen shore or water at another elevation does not thin this meadow: " + level);
        }
        var distant = at.translated(0, 2);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS,
              0, c.equals(distant) ? 1 : -1, 0));
        assertEquals(meadow, GpuGroundCover.plant(scene, scene.tile(at), support, null),
              "Water beyond the adjoining shore cannot remove inland grass");
    }

    private static Set<List<Float>> roots(FloatArray roots) {
        Set<List<Float>> result = new HashSet<>();
        for (int i = 0; i < roots.size; i += 4) {
            result.add(List.of(roots.get(i), roots.get(i + 1), roots.get(i + 2), roots.get(i + 3)));
        }
        return result;
    }

    @ParameterizedTest
    @EnumSource(value = TerrainLod.class, names = { "FULL", "MEDIUM" })
    void twoLevelBanksSupportGrassBetweenTheirEarthFaces(TerrainLod lod) {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS,
              c.getX() < 4 ? 2 : 0, -1, 0));
        var tile = scene.tile(new Coords(3, 4));
        var surface = new BoardSurface(scene, tile, lod);
        var finished = BoardTacticalGeometry.Surface.of(surface, scene, BoardGeometry.floor(scene));
        var roots = GpuGroundCover.plant(scene, tile, finished, null);
        var turf = GpuBankTurf.plant(scene, tile, finished, null);
        assertNotNull(turf, "Both contour bands need attached turf geometry");
        assertEquals(turf, GpuBankTurf.plant(scene, tile, finished, null), "Rebuilding the same bank keeps the same variants");
        assertTrue(checkTurf(turf) > 10, "The visible bank has multiple distinct clumps");
        float level = BoardGeometry.level();
        int middle = 0;
        for (int i = 0; i < roots.size; i += 4) {
            float z = roots.get(i + 2);
            if (z > .85f * level && z < 1.15f * level) {
                middle++;
                assertEquals(z + BoardGeometry.width() * .001f,
                      BoardSurface.sampleHeight(finished.slopes(), roots.get(i), roots.get(i + 1), Float.NaN), .002f,
                      "Roots must sit on the installed shoulder, not a separate cosmetic shelf");
            }
        }
        assertTrue(middle > 10, lod + " must grow grass at the intermediate level: " + middle);
        var center = BoardGeometry.center(tile.coords(), tile.elevation());
        assertEquals(2 * level, surface.height(center.x, center.y), .002f, "The unit anchor keeps its game elevation");
    }

    @Test
    void variedCrownsAreLimitedToDryTwoLevelGrassBanks() {
        for (var family : new BoardScene.Surface[] { BoardScene.Surface.GRASS, BoardScene.Surface.DIRT, BoardScene.Surface.SAND }) {
            for (int levels : new int[] { 1, 2, 4 }) {
                var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, family, c.getX() < 4 ? levels : 0, -1, 0));
                // A hex's clumps draw their silhouettes at random: along the bank, over three hexes, all six take part.
                var variants = new HashSet<Integer>();
                for (int row = 3; row <= 5; row++) {
                    var tile = scene.tile(new Coords(3, row));
                    var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
                    var turf = GpuBankTurf.plant(scene, tile, surface, null);
                    if (family != BoardScene.Surface.GRASS || levels != 2) { assertNull(turf); continue; }
                    assertNotNull(turf);
                    for (int clump = 0; clump < turf.size; clump += 48 * GpuBankTurf.STRIDE) {
                        float u = 1, v = 1;
                        for (int at = clump; at < clump + 48 * GpuBankTurf.STRIDE; at += GpuBankTurf.STRIDE) {
                            u = Math.min(u, turf.get(at + 7)); v = Math.min(v, turf.get(at + 8));
                        }
                        variants.add(Math.round(u * 3) + 3 * Math.round(v * 2));
                    }
                }
                if (family == BoardScene.Surface.GRASS && levels == 2) {
                    assertEquals(6, variants.size(), "All six atlas silhouettes participate");
                }
            }
        }
        var wet = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS,
              c.getX() < 4 ? 2 : 0, c.getX() < 4 ? -1 : 1, 0));
        var tile = wet.tile(new Coords(3, 4));
        assertNull(GpuBankTurf.plant(wet, tile,
              BoardTacticalGeometry.Surface.of(new BoardSurface(wet, tile), wet, BoardGeometry.floor(wet)), null));
    }

    @Test
    void turfKeepsAuthoredTerrainColorsInsteadOfAnAtlasTint() {
        for (int family : new int[] { 1, 3, 4, 5 }) {
            var cover = BoardSurfaceBlend.capture(new Hex(0, "ground_fluff:" + family + ":1", "grass"));
            var scene = BoardSurfaceBlendTest.scene(c -> {
                var tile = BoardSurfaceBlendTest.tile(c, BoardScene.Surface.GRASS, c.getX() < 4 ? 2 : 0, -1, 0);
                return new BoardScene.Tile(c, tile.elevation(), tile.waterDepth(), tile.frozen(), tile.roadExits(),
                      tile.surface(), tile.ground(), tile.normals(), tile.decals(), tile.decalsWithoutLimbs(),
                      tile.features(), tile.text(), tile.liquid(), tile.tileset(), tile.detailedGround(), tile.road(),
                      tile.fireSmoke(), tile.biome(), tile.impassable(), tile.blackIce(), tile.cliffTopExits(), tile.bare(), cover);
            });
            var tile = scene.tile(new Coords(3, 4));
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
            var turf = GpuBankTurf.plant(scene, tile, surface, null);
            assertNotNull(turf);
            for (int at = 0; at < turf.size; at += GpuBankTurf.STRIDE) {
                for (int material = 0; material < BoardScene.Surface.values().length; material++) {
                    assertEquals(cover.weight(material), turf.get(at + 9 + material), .001f,
                          "Authored material proportion for family " + material);
                }
            }
        }
    }

    /** A small crown may hang off its carrier, but may never stretch a full level at a neighbouring cliff. */
    static int checkTurf(FloatArray turf) {
        int clumpSize = 48 * GpuBankTurf.STRIDE;
        assertEquals(0, turf.size % clumpSize);
        for (int clump = 0; clump < turf.size; clump += clumpSize) {
            float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
            for (int at = clump; at < clump + clumpSize; at += GpuBankTurf.STRIDE) {
                for (int axis = 0; axis < 6; axis++) { assertTrue(Float.isFinite(turf.get(at + axis))); }
                float z = turf.get(at + 2);
                low = Math.min(low, z); high = Math.max(high, z);
                float weight = 0;
                for (int family = 0; family < BoardScene.Surface.values().length; family++) { weight += turf.get(at + 9 + family); }
                assertEquals(1, weight, .001f, "Turf inherits the installed material proportions");
            }
            assertTrue(high - low < BoardRelief.metres(3.5f), "No stretched curtain: " + (high - low));
        }
        return turf.size / clumpSize;
    }
}
