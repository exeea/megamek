/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardBiomeTest {
    @Test
    void indexedRootsKeepThePublishedBankHeightIncludingTriangleEdges() {
        var original = BoardGeometry.tuning();
        try {
            GpuRiverTerrainSmokeTest.tune(.94f, true);
            var scene = BoardSurfaceBlendTest.scene(c -> c.getX() < 4
                  ? BoardSurfaceBlendTest.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : tile(c, BoardScene.Biome.MARSH, 0));
            var random = new Random(417);
            for (var lod : TerrainLod.values()) {
                var surface = new BoardSurface(scene, scene.tile(new Coords(3, 4)), lod);
                var support = new GpuBiomeVegetation.Support(surface.faces);
                for (var face : surface.faces) {
                    assertEquals(BoardSurface.sampleHeight(surface.faces, face.a().x, face.a().y, Float.NaN),
                          support.height(face.a().x, face.a().y), .00001f);
                }
                var center = BoardGeometry.center(new Coords(3, 4), 0);
                for (int i = 0; i < 500; i++) {
                    float x = center.x + (random.nextFloat() - .5f) * BoardGeometry.width();
                    float y = center.y + (random.nextFloat() - .5f) * BoardGeometry.height();
                    assertEquals(BoardSurface.sampleHeight(surface.faces, x, y, Float.NaN), support.height(x, y), .00001f);
                }
            }
        } finally { BoardGeometry.tune(original); }
    }

    @Test
    void cropRowsStayCompleteAtEveryDensityWhileReedsExtendWithoutDuplicateRoots() {
        for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH)) {
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, 0));
            var tile = scene.tile(new Coords(4, 4));
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
            var progressive = new GpuBiomeVegetation.Patch(scene, tile, surface, 0);
            progressive.prepare(scene, tile, .08f, Long.MAX_VALUE);
            assertFalse(progressive.busy());
            if (kind == BoardScene.Biome.MARSH) {
                for (int i = 3; i < progressive.roots.size; i += 4) { assertTrue(progressive.roots.items[i] < .08f); }
            }
            int distant = progressive.roots.size;
            assertTrue(distant > 0);
            progressive.prepare(scene, tile, .35f, Long.MAX_VALUE);
            if (kind == BoardScene.Biome.FIELD) {
                assertEquals(distant, progressive.roots.size, "Zooming must not fill holes left by missing crop strips");
            } else { assertTrue(progressive.roots.size > distant); }
            progressive.prepare(scene, tile, 1, Long.MAX_VALUE);
            var full = new GpuBiomeVegetation.Patch(scene, tile, surface, 0);
            full.prepare(scene, tile, Long.MAX_VALUE);
            assertEquals(full.roots, progressive.roots, "LOD preparation order must keep one deterministic lattice");
            if (kind == BoardScene.Biome.FIELD) {
                assertEquals(distant, full.roots.size, "Distant crops retain every supported row segment");
            }
        }
    }

    static BoardScene.Tile tile(Coords coords, BoardScene.Biome kind, int elevation) {
        var base = BoardSurfaceBlendTest.tile(coords, kind == BoardScene.Biome.MUD || kind == BoardScene.Biome.QUICKSAND
              ? BoardScene.Surface.DIRT : BoardScene.Surface.GRASS, elevation, -1, 0);
        return new BoardScene.Tile(coords, elevation, -1, false, 0, base.surface(), base.ground(), null, null, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE, kind);
    }

    @Test
    void capturedSpecialGroundReplacesFieldPropsButPreservesUnsupportedArtworkAndQuicksand() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertEquals(BoardScene.Biome.FIELD, BoardFeatures.biome(hex));
        assertEquals(BoardScene.Surface.GRASS, BoardFeatures.surface(hex), "Field banks retain the native grass mantle");
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().noneMatch(f -> f.asset().equals("field")));
        hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().anyMatch(f -> f.asset().equals("field")));
        hex.removeAllTerrains();
        for (int level = 1; level <= 3; level++) {
            hex.addTerrain(new Terrain(Terrains.SWAMP, level));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            assertEquals(level == 1 ? BoardScene.Biome.MARSH : BoardScene.Biome.QUICKSAND, BoardFeatures.biome(hex));
            assertEquals(level == 1 ? BoardScene.Surface.GRASS : BoardScene.Surface.DIRT, BoardFeatures.surface(hex));
            assertFalse(BoardLiquid.capture(hex).present(), "Shallow visual pools do not invent gameplay water");
        }
        hex.addTerrain(new Terrain(Terrains.WATER, 1));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(hex), "The actual waterbed keeps its material");
        hex.removeAllTerrains();
        hex.addTerrain(new Terrain(Terrains.MUD, 1));
        assertEquals(BoardScene.Biome.MUD, BoardFeatures.biome(hex));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        hex.addTerrain(new Terrain(Terrains.HAZARDOUS_LIQUID, 1));
        assertEquals(BoardScene.Biome.NONE, BoardFeatures.biome(hex));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Hazardous water needs the same sculpted bed as clear water");
        assertEquals(BoardLiquid.Kind.HAZARDOUS, BoardLiquid.capture(hex).kind());
    }

    @Test
    void connectedFieldsAndWetlandsHaveNoInternalHexSeamsAndStopBelowThePlateau() {
        Coords center = new Coords(4, 4);
        for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH, BoardScene.Biome.QUICKSAND, BoardScene.Biome.MUD)) {
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, 1));
            for (int edge = 0; edge < 6; edge++) {
                var corner = BoardGeometry.corner(center, 1, edge);
                assertEquals(1, BoardBiome.coverage(scene, kind, corner.x, corner.y, corner.z), .00001);
                assertEquals(0, BoardBiome.coverage(scene, kind, corner.x, corner.y, corner.z - BoardRelief.metres(1.3f)));
            }
            var single = BoardSurfaceBlendTest.scene(c -> tile(c, c.equals(center) ? kind : BoardScene.Biome.NONE, 1));
            var p = BoardGeometry.center(center, 1);
            assertEquals(1, BoardBiome.coverage(single, kind, p.x, p.y, p.z), .00001);
            p.lerp(BoardGeometry.center(center.translated(2), 1), .5f);
            assertEquals(kind == BoardScene.Biome.MARSH ? 1 : .5,
                  BoardBiome.coverage(single, kind, p.x, p.y, p.z), .00001);
        }
    }

    @Test
    void cropRootsFollowOneGlobalRowLatticeAndReedsLeavePoolsOpen() {
        Coords center = new Coords(4, 4);
        float metre = BoardRelief.metres(1);
        for (var kind : List.of(BoardScene.Biome.FIELD, BoardScene.Biome.MARSH)) {
            var scene = BoardSurfaceBlendTest.scene(c -> tile(c, kind, 0));
            var all = new HashSet<String>();
            int count = 0;
            for (var coords : List.of(center, center.translated(2))) {
                var tile = scene.tile(coords);
                var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
                var patch = new GpuBiomeVegetation.Patch(scene, tile, surface, 0);
                patch.prepare(scene, tile, Long.MAX_VALUE);
                assertFalse(patch.busy());
                for (int i = 0; i < patch.roots.size; i += 4) {
                    float x = patch.roots.items[i], y = patch.roots.items[i + 1], z = patch.roots.items[i + 2];
                    assertTrue(all.add(x + ":" + y), "Shared edges cannot duplicate roots");
                    assertEquals(BoardSurface.sampleHeight(surface.top(), x, y, Float.NaN), z + .018f * metre, .0001);
                    if (kind == BoardScene.Biome.FIELD) {
                        float row = BoardBiome.row(x / metre, y / metre);
                        assertTrue(Math.abs(row - Math.round(row)) < .061, "Rows have no tile-local rotation/phase");
                        assertEquals(0, Math.floorMod(Math.round(row), 2), "Alternate furrows stay open across hex boundaries");
                    } else {
                        float wet = BoardBiome.wetness(x / metre, y / metre);
                        assertTrue(wet >= .48f && wet <= .80f, "Reeds grow on wet edges and hummocks, leaving the pools open");
                    }
                    count++;
                }
            }
            // Crop clumps need fewer roots, but still exercise many sites across the shared boundary.
            assertTrue(count > (kind == BoardScene.Biome.FIELD ? 150 : 30), kind + " roots: " + count);
        }
    }

    @Test
    void changingSpecialTerrainInvalidatesItsGeometryCache() {
        Coords center = new Coords(4, 4);
        var before = BoardSurfaceBlendTest.scene(c -> tile(c, BoardScene.Biome.MARSH, 0));
        var after = BoardSurfaceBlendTest.scene(c -> tile(c, BoardScene.Biome.QUICKSAND, 0));
        assertFalse(before.tile(center).sameGeometry(after.tile(center)));
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(center)), BoardSurface.geometryKey(after, after.tile(center)));
    }

    @Test
    void cropStripsKeepTheirTextureScaleAndStayOnTheirSideOfRoadsAndFieldEdges() {
        float metre = BoardRelief.metres(1);
        Coords center = new Coords(4, 4);
        for (boolean road : new boolean[] { false, true }) {
            var scene = BoardSurfaceBlendTest.scene(c -> {
                var base = tile(c, c.getX() <= 4 ? BoardScene.Biome.FIELD : BoardScene.Biome.NONE, c.getY() < 4 ? 1 : 0);
                return new BoardScene.Tile(c, base.elevation(), -1, false, road ? 9 : 0, base.surface(), base.ground(),
                      null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true,
                      road ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, BoardFireSmoke.NONE, base.biome());
            });
            var tile = scene.tile(center);
            var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, -1);
            var patch = new GpuBiomeVegetation.Patch(scene, tile, surface, 0);
            patch.prepare(scene, tile, Long.MAX_VALUE);
            assertTrue(patch.roots.size > 0);
            assertEquals(patch.roots.size, patch.rowSpans.size);
            boolean trimmed = false;
            for (int i = 0; i < patch.roots.size; i += 4) {
                float length = patch.rowSpans.items[i], rise = patch.rowSpans.items[i + 1];
                float from = patch.rowSpans.items[i + 2], to = patch.rowSpans.items[i + 3];
                assertTrue(from >= 0 && to <= 1 && from < to);
                assertEquals(length / metre, (to - from) * GpuBiomeVegetation.ROW_LENGTH, .0001f,
                      "Trimming must crop the image, not squeeze ten plants into a short end piece");
                trimmed |= to - from < .9f;
                for (int step = 0; step <= 10; step++) {
                    float t = -.4999f + .9998f * step / 10;
                    float x = patch.roots.items[i] - BoardBiome.ROW_Y * length * t;
                    float y = patch.roots.items[i + 1] + BoardBiome.ROW_X * length * t;
                    float floor = BoardSurface.sampleHeight(surface.top(), x, y, Float.NaN);
                    assertTrue(Float.isFinite(floor), "The whole strip must have published ground support");
                    assertEquals(floor, patch.roots.items[i + 2] + rise * t + .018f * metre, .02f * metre);
                    assertEquals(Math.round(BoardBiome.row(x / metre, y / metre)), BoardBiome.row(x / metre, y / metre), .0001f);
                    assertTrue(BoardBiome.coverage(scene, BoardScene.Biome.FIELD, x, y, floor) >= .599f);
                    if (road) {
                        float distance = patch.road.distance((x - BoardGeometry.centerX(center)) / BoardGeometry.hexScale(),
                              (y - BoardGeometry.centerY(center)) / BoardGeometry.hexScale());
                        assertTrue(distance >= BoardRoad.SHOULDER + GpuBiomeVegetation.ROW_HALF_WIDTH * metre
                              / BoardGeometry.hexScale(), "Even the overhead foliage must clear the road");
                    }
                }
            }
            assertTrue(trimmed, "Field/road boundaries must produce cropped end pieces");
        }
    }

    @Test
    void marshReedsContinueAcrossExposedBanksButNeverUseTheWaterPlaneForSupport() {
        var original = BoardGeometry.tuning();
        try {
            GpuRiverTerrainSmokeTest.tune(.94f, true);
            var scene = BoardSurfaceBlendTest.scene(c -> c.getX() < 4
                  ? BoardSurfaceBlendTest.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : tile(c, BoardScene.Biome.MARSH, 0));
            var removed = BoardSurfaceBlendTest.scene(c -> c.getX() < 4
                  ? BoardSurfaceBlendTest.tile(c, BoardScene.Surface.DIRT, 0, 0, 0) : tile(c, BoardScene.Biome.NONE, 0));
            int bankRoots = 0, landRoots = 0;
            var positions = new HashSet<String>();
            float metre = BoardRelief.metres(1);
            for (int x = 2; x <= 4; x++) {
                for (int y = 2; y <= 6; y++) {
                    var owner = scene.tile(new Coords(x, y));
                    var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, owner), scene, -1);
                    if (BoardBiome.plantKind(scene, owner) != BoardScene.Biome.MARSH) { continue; }
                    var patch = new GpuBiomeVegetation.Patch(scene, owner, surface, 0);
                    patch.prepare(scene, owner, Long.MAX_VALUE);
                    for (int i = 0; i < patch.roots.size; i += 4) {
                        float px = patch.roots.items[i], py = patch.roots.items[i + 1], z = patch.roots.items[i + 2] + .018f * metre;
                        assertTrue(positions.add(px + ":" + py), "Each root has one owner across the shore join");
                        assertEquals(BoardSurface.sampleHeight(patch.ground, px, py, Float.NaN), z, .0001);
                        if (owner.liquid().present()) {
                            float water = BoardSurface.sampleHeight(surface.water(), px, py, Float.NEGATIVE_INFINITY);
                            assertTrue(z > water + .015f * metre, "No floating or submerged reed roots");
                            bankRoots++;
                        } else { landRoots++; }
                    }
                    if (owner.liquid().present()) {
                        assertNotEquals(BoardVegetation.key(scene, owner), BoardVegetation.key(removed, removed.tile(owner.coords())),
                              "Removing the neighbour's marsh must invalidate its bank reeds too");
                        assertEquals(BoardScene.Biome.NONE, BoardBiome.plantKind(removed, removed.tile(owner.coords())));
                    }
                }
            }
            assertTrue(bankRoots > 20, "Reeds must cross onto the exposed bank: " + bankRoots);
            assertTrue(landRoots > 20, "The same clumps continue into the marsh: " + landRoots);
        } finally { BoardGeometry.tune(original); }
    }
}
