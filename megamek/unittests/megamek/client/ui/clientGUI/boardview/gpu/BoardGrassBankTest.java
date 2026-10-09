/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;

import com.badlogic.gdx.utils.FloatArray;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The same finished shoulder supports turf, blade roots and surface queries at both planted detail levels. */
class BoardGrassBankTest {
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
                var tile = scene.tile(new Coords(3, 4));
                var surface = BoardTacticalGeometry.Surface.of(new BoardSurface(scene, tile), scene, BoardGeometry.floor(scene));
                var turf = GpuBankTurf.plant(scene, tile, surface, null);
                if (family != BoardScene.Surface.GRASS || levels != 2) { assertNull(turf); continue; }
                assertNotNull(turf);
                var variants = new HashSet<Integer>();
                for (int clump = 0; clump < turf.size; clump += 48 * GpuBankTurf.STRIDE) {
                    float u = 1, v = 1;
                    for (int at = clump; at < clump + 48 * GpuBankTurf.STRIDE; at += GpuBankTurf.STRIDE) {
                        u = Math.min(u, turf.get(at + 7)); v = Math.min(v, turf.get(at + 8));
                    }
                    variants.add(Math.round(u * 3) + 3 * Math.round(v * 2));
                }
                assertEquals(6, variants.size(), "All six atlas silhouettes participate");
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
                      tile.surface(), tile.ground(), tile.normals(), tile.decals(), tile.decalsWithoutLimbs(), tile.tactical(),
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
