/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardBiomeTest {
    static BoardScene.Tile tile(Coords coords, BoardScene.Biome kind, int elevation) {
        var base = BoardSurfaceBlendTest.tile(coords, kind == BoardScene.Biome.NONE
              ? BoardScene.Surface.GRASS : BoardScene.Surface.DIRT, elevation, -1, 0);
        return new BoardScene.Tile(coords, elevation, -1, false, 0, base.surface(), base.ground(), null, null, null, null,
              List.of(), List.of(), BoardLiquid.NONE, null, true, BoardRoad.Kind.NONE, BoardFireSmoke.NONE, kind);
    }

    @Test
    void capturedSpecialGroundReplacesFieldPropsButPreservesUnsupportedArtworkAndQuicksand() {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.FIELDS, 1));
        assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
        assertEquals(BoardScene.Biome.FIELD, BoardFeatures.biome(hex));
        assertEquals(BoardScene.Surface.DIRT, BoardFeatures.surface(hex));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().noneMatch(f -> f.asset().equals("field")));
        hex.addTerrain(new Terrain(Terrains.FORTIFIED, 1));
        assertFalse(BoardFeatures.detailedGround(hex, Map.of()));
        assertTrue(BoardFeatures.capture(hex, new Coords(0, 0), Map.of()).stream().anyMatch(f -> f.asset().equals("field")));
        hex.removeAllTerrains();
        for (int level = 1; level <= 3; level++) {
            hex.addTerrain(new Terrain(Terrains.SWAMP, level));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()));
            assertEquals(level == 1 ? BoardScene.Biome.MARSH : BoardScene.Biome.QUICKSAND, BoardFeatures.biome(hex));
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
            assertEquals(1, BoardBiome.coverage(single, kind, p.x, p.y, p.z));
            p.lerp(BoardGeometry.center(center.translated(2), 1), .5f);
            assertEquals(.5, BoardBiome.coverage(single, kind, p.x, p.y, p.z), .00001);
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
                    } else {
                        float wet = BoardBiome.wetness(x / metre, y / metre);
                        assertTrue(wet >= .48f && wet <= .80f, "Reeds grow on wet edges and hummocks, leaving the pools open");
                    }
                    count++;
                }
            }
            assertTrue(count > (kind == BoardScene.Biome.FIELD ? 500 : 30), kind + " roots: " + count);
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
}
