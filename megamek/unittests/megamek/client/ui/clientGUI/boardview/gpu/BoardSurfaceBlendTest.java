/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardSurfaceBlendTest {
    static final Coords CENTER = new Coords(4, 4);
    private static final BoardScene.Pixels PIXELS = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));

    static BoardScene.Tile tile(Coords coords, BoardScene.Surface family, int level, int depth, int road) {
        return new BoardScene.Tile(coords, level, depth, false, road, family, PIXELS, null, null, null, null,
              List.of(), List.of(), depth < 0 ? BoardLiquid.NONE : BoardLiquid.WATER, null, true);
    }

    static BoardScene scene(Function<Coords, BoardScene.Tile> tiles) {
        List<BoardScene.Tile> result = new ArrayList<>();
        for (int x = 0; x < 9; x++) {
            for (int y = 0; y < 9; y++) { result.add(tiles.apply(new Coords(x, y))); }
        }
        return new BoardScene(0, 9, 9, result, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene.Tile tile(Coords coords, int family, int level) {
        var surface = family >= BoardSurfaceBlend.CRUST ? BoardScene.Surface.ROCK : BoardScene.Surface.values()[family];
        var liquid = family < BoardSurfaceBlend.CRUST ? BoardLiquid.NONE : new BoardLiquid(
              family == BoardSurfaceBlend.CRUST ? BoardLiquid.Kind.MAGMA_CRUST : BoardLiquid.Kind.MAGMA, "", 0);
        return new BoardScene.Tile(coords, level, -1, false, 0, surface, PIXELS, null, null, null, null,
              List.of(), List.of(), liquid, null, true);
    }

    @Test
    void everyFamilyMeetsTheReceivingNaturalGroundAtItsCliffFootInEveryDirection() {
        for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
            int upper = family, lower = (family + 3) % BoardScene.Surface.values().length;
            if (lower == BoardScene.Surface.CONCRETE.ordinal()) { lower = BoardScene.Surface.ROCK.ordinal(); }
            int receiving = lower;
            for (int direction = 0; direction < 6; direction++) {
                var next = CENTER.translated(direction);
                var scene = scene(c -> tile(c, c.equals(CENTER) ? upper : receiving, c.equals(CENTER) ? 4 : 0));
                var high = scene.tile(CENTER);
                var low = scene.tile(next);
                var p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
                p.z = BoardGeometry.groundZ(low);
                var foot = BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z);
                assertEquals(foot, BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z), "Shared foot, family " + family);
                assertTrue(foot.weight(upper) > .05f && foot.weight(lower) > .05f, foot.toString());
                p.z += BoardRelief.metres(BoardSurfaceBlend.FOOT_METRES + .1f);
                var above = BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z);
                assertEquals(0, above.weight(lower), "Receiving terrain stays near the foot");
                assertEquals(1, above.weight(upper), .00001f);
            }
        }
    }

    @Test
    void naturalCliffsDoNotSpillTheirCoverAcrossLowerPavement() {
        for (int rise : new int[] { 1, 2, 3, 4, 8 }) {
            var scene = scene(c -> tile(c, c.equals(CENTER) ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SAND,
                  c.equals(CENTER) ? 0 : rise, -1, 0));
            var pavement = scene.tile(CENTER);
            assertFalse(BoardSurfaceBlend.boundary(scene, pavement));
            for (int edge = 0; edge < 6; edge++) {
                var point = BoardGeometry.corner(CENTER, 0, edge);
                assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.CONCRETE),
                      BoardSurfaceBlend.sample(scene, pavement, point.x, point.y, point.z));
            }
        }
    }

    @Test
    void concreteKeepsItsLowSlabsAndBlendsOnlyBelowTallFoundations() {
        for (int level = 0; level <= 4; level++) {
            int rise = level;
            var scene = scene(c -> tile(c, c.getY() < 4 ? BoardScene.Surface.CONCRETE.ordinal()
                  : BoardScene.Surface.DIRT.ordinal(), c.getY() < 4 ? rise : 0));
            var high = scene.tile(new Coords(4, 3));
            var low = scene.tile(new Coords(4, 4));
            assertEquals(level >= 3, BoardSurfaceBlend.boundary(scene, high));
            assertEquals(level >= 3, BoardSurfaceBlend.boundary(scene, low));
            if (level < 3) {
                var p = BoardGeometry.center(high.coords(), 0).lerp(BoardGeometry.center(low.coords(), 0), .5f);
                assertEquals(BoardSurfaceBlend.solid(high.surface()), BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z));
                assertEquals(BoardSurfaceBlend.solid(low.surface()), BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z));
            }
        }
    }

    @Test
    void tallConcreteContactFollowsTheFittedFootAtEveryDetail() {
        var scene = scene(c -> tile(c, c.getY() < 4 ? BoardScene.Surface.CONCRETE.ordinal()
              : BoardScene.Surface.DIRT.ordinal(), c.getY() < 4 ? 4 : 0));
        int checked = 0;
        for (var lod : TerrainLod.values()) {
            for (int x = 2; x <= 6; x++) {
                var low = scene.tile(new Coords(x, 4));
                var surface = new BoardSurface(scene, low, lod);
                for (int edge = 0; edge < 6; edge++) {
                    var high = scene.tile(low.coords().translated(BoardGeometry.edgeDirection(edge)));
                    if (high == null || high.elevation() != 4) { continue; }
                    for (float t : new float[] { .1f, .35f, .6f, .9f }) {
                        var p = surface.relief.seam(edge, edge, t);
                        var cover = BoardSurfaceBlend.sample(scene, low, p.x, p.y, p.z);
                        assertEquals(cover, BoardSurfaceBlend.sampleCliff(scene, high, p.x, p.y, p.z));
                        assertTrue(cover.concrete() > .05f && cover.dirt() > .05f,
                              "Both materials reach the fitted foot: " + p + " " + cover);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked >= 60);
    }

    @Test
    void lunarAndRockKeepDistinctCoverageWithoutBecomingVolcanic() {
        var scene = scene(c -> tile(c, c.equals(CENTER) ? BoardScene.Surface.LUNAR : BoardScene.Surface.ROCK, 0, -1, 0));
        var lunar = scene.tile(CENTER);
        var rock = scene.tile(CENTER.translated(2));
        assertTrue(BoardSurfaceBlend.boundary(scene, lunar));
        assertTrue(BoardSurfaceBlend.boundary(scene, rock));
        assertFalse(new GpuSurfaceBlend.Palette(BoardScene.Surface.LUNAR, BoardScene.Surface.ROCK,
              BoardScene.Surface.SAND).volcanic());
        var center = BoardGeometry.center(CENTER, 0);
        assertEquals(1, BoardSurfaceBlend.sample(scene, lunar, center.x, center.y, center.z).lunar());
        var edge = center.lerp(BoardGeometry.center(rock.coords(), 0), .5f);
        var cover = BoardSurfaceBlend.sample(scene, lunar, edge.x, edge.y, edge.z);
        assertEquals(cover, BoardSurfaceBlend.sample(scene, rock, edge.x, edge.y, edge.z));
        assertTrue(cover.lunar() > .05f && cover.rock() > .05f, cover.toString());
        assertEquals(0, cover.crust());
        assertEquals(0, cover.bank());
    }

    @Test
    void volcanicBoundariesShareCoverageOnLevelGround() {
        for (int family : new int[] { BoardSurfaceBlend.CRUST, BoardSurfaceBlend.BANK }) {
            for (var host : new BoardScene.Surface[] { BoardScene.Surface.ROCK, BoardScene.Surface.LUNAR }) {
                var scene = scene(c -> tile(c, c.equals(CENTER) ? family : host.ordinal(), 0));
                var next = CENTER.translated(2);
                var p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
                var cover = BoardSurfaceBlend.sample(scene, scene.tile(CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(next), p.x, p.y, p.z));
                assertTrue(cover.weight(host) > .05f && cover.weight(family) > .05f, cover.toString());
                assertTrue(BoardSurfaceBlend.boundary(scene, scene.tile(CENTER)));
            }
        }
    }

    @Test
    void bothSidesAgreeInAllDirectionsAndTileCentresKeepTheirIdentity() {
        for (int direction = 0; direction < 6; direction++) {
            Coords next = CENTER.translated(direction);
            BoardScene scene = scene(c -> tile(c, c.equals(CENTER) ? BoardScene.Surface.SNOW : BoardScene.Surface.GRASS, 0, -1, 0));
            Vector3 a = BoardGeometry.center(CENTER, 0), b = BoardGeometry.center(next, 0);
            for (float t : new float[] { 0, .35f, .45f, .5f, .55f, .65f, 1 }) {
                Vector3 p = new Vector3(a).lerp(b, t);
                var cover = BoardSurfaceBlend.sample(scene, scene.tile(CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(next), p.x, p.y, p.z));
                assertEquals(1, cover.grass() + cover.snow(), .00001f);
                if (t == 0) { assertEquals(1, cover.snow(), .00001f); }
                if (t == 1) { assertEquals(1, cover.grass(), .00001f); }
                if (t == .5f) { assertTrue(cover.snow() > .1f && cover.grass() > .1f); }
            }
        }
    }

    @Test
    void threeCoversMeetAtOneCornerWithoutChoosingOneNeighbourForTheHex() {
        Coords a = CENTER.translated(BoardGeometry.edgeDirection(0));
        Coords b = CENTER.translated(BoardGeometry.edgeDirection(5));
        BoardScene scene = scene(c -> tile(c, c.equals(a) ? BoardScene.Surface.SNOW
              : c.equals(b) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        Vector3 p = BoardGeometry.corner(CENTER, 0, 0);
        var cover = BoardSurfaceBlend.sample(scene, scene.tile(CENTER), p.x, p.y, p.z);
        assertTrue(cover.snow() > .1f && cover.sand() > .1f && cover.grass() > .1f, cover.toString());
        assertEquals(1, cover.grass() + cover.sand() + cover.snow(), .00001f);
        assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(a), p.x, p.y, p.z));
        assertEquals(cover, BoardSurfaceBlend.sample(scene, scene.tile(b), p.x, p.y, p.z));
    }

    @Test
    void uniformFamiliesHaveNoBoundaryBand() {
        for (var family : BoardScene.Surface.values()) {
            var scene = scene(c -> tile(c, family, 0, -1, 0));
            assertFalse(BoardSurfaceBlend.boundary(scene, scene.tile(CENTER)));
            for (int edge = 0; edge < 6; edge++) {
                var p = BoardGeometry.corner(CENTER, 0, edge);
                assertEquals(1, BoardSurfaceBlend.sample(scene, scene.tile(CENTER), p.x, p.y, p.z).weight(family), .00001f);
            }
        }
    }

    @Test
    void waterRoadsAndLevelConcreteEdgesBlockNaturalCover() {
        Coords next = CENTER.translated(2);
        for (int mode = 0; mode < 3; mode++) {
            final int obstacle = mode;
            var scene = scene(c -> c.equals(next) ? tile(c, obstacle == 2 ? BoardScene.Surface.CONCRETE : BoardScene.Surface.SNOW,
                  0, obstacle == 0 ? 1 : -1, obstacle == 1 ? 9 : 0)
                  : tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
            Vector3 p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .49f);
            assertEquals(1, BoardSurfaceBlend.sample(scene, scene.tile(CENTER), p.x, p.y, p.z).grass(), .00001f);
            assertFalse(BoardSurfaceBlend.boundary(scene, scene.tile(CENTER)));
        }
    }

    @Test
    void cliffContactsContinueDownAllSixCornersWithoutBorrowingTheValleyCover() {
        for (int corner = 0; corner < 6; corner++) {
            var neighbour = CENTER.translated(BoardGeometry.edgeDirection(corner));
            var scene = scene(c -> tile(c, c.equals(CENTER) ? BoardScene.Surface.GRASS
                  : c.equals(neighbour) ? BoardScene.Surface.SAND : BoardScene.Surface.SNOW,
                  c.equals(CENTER) || c.equals(neighbour) ? 4 : 0, -1, 0));
            var p = BoardGeometry.corner(CENTER, 0, corner);
            for (int level = 1; level <= 4; level++) {
                p.z = level * BoardGeometry.level();
                var cover = BoardSurfaceBlend.sampleCliff(scene, scene.tile(CENTER), p.x, p.y, p.z);
                assertEquals(cover, BoardSurfaceBlend.sampleCliff(scene, scene.tile(neighbour), p.x, p.y, p.z));
                assertTrue(cover.grass() > .1f && cover.sand() > .1f, "Corner " + corner + ": " + cover);
                assertEquals(0, cover.snow(), "The valley floor cannot paint a cliff above it");
                assertEquals(1, cover.grass() + cover.sand(), .00001f);
            }
        }
    }

    @Test
    void valleyCoverCannotPaintTheCliffAboveItsFootBand() {
        var next = CENTER.translated(2);
        var scene = scene(c -> tile(c, c.equals(CENTER) ? BoardScene.Surface.SAND
              : BoardScene.Surface.GRASS, c.equals(CENTER) ? 4 : 1, -1, 0));
        var p = BoardGeometry.center(CENTER, 3).lerp(BoardGeometry.center(next, 3), .5f);
        assertEquals(BoardSurfaceBlend.solid(BoardScene.Surface.SAND),
              BoardSurfaceBlend.sampleCliff(scene, scene.tile(CENTER), p.x, p.y, p.z));
    }

    @Test
    void neighbouringBanksAgreeFromTheirLandCornerDownToTheWaterline() {
        for (int depth : new int[] { 0, 1 }) {
            var scene = BoardTerrainDetailTest.shores(depth, false);
            var water = scene.tile(BoardTerrainDetailTest.WATER);
            var surface = new BoardSurface(scene, water);
            // The sand/rock and dirt/sand junctions from the mixed-bank regression fixture.
            for (int corner : new int[] { 2, 3 }) {
                var first = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(corner - 1)));
                var second = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(corner)));
                var outer = BoardGeometry.corner(water.coords(), 0, corner);
                var inner = surface.waterBoundary(corner).getFirst();
                for (float t : new float[] { 0, .25f, .5f, .75f, 1 }) {
                    var p = new Vector3(outer).lerp(inner, t);
                    var cover = BoardSurfaceBlend.sample(scene, first, p.x, p.y, p.z);
                    assertEquals(cover, BoardSurfaceBlend.sample(scene, second, p.x, p.y, p.z));
                    assertTrue(cover.weight(first.surface()) > .1f, "First bank at " + t + ": " + cover);
                    assertTrue(cover.weight(second.surface()) > .1f, "Second bank at " + t + ": " + cover);
                    assertEquals(0, cover.grass(), "The water's nominal grass family must not paint its banks");
                    assertEquals(0, cover.concrete(), "Hard edges stay hard");
                }
            }
            for (int edge : new int[] { 0, 4 }) {
                var protectedLand = scene.tile(water.coords().translated(BoardGeometry.edgeDirection(edge)));
                var p = surface.waterBoundary(edge).getFirst();
                assertEquals(BoardSurfaceBlend.solid(protectedLand.surface()),
                      BoardSurfaceBlend.sample(scene, protectedLand, p.x, p.y, p.z));
            }
        }
    }

    @Test
    void shallowBarsShareTheirCentralCoverAndKeepBankPalettesBounded() {
        var scene = BoardTerrainDetailTest.shores(0, false);
        var water = scene.tile(BoardTerrainDetailTest.WATER);
        var center = BoardGeometry.center(water.coords(), 0);
        var banks = scene.tiles().stream().filter(t -> BoardSurfaceBlend.natural(t)
              && t.coords().distance(water.coords()) == 1).toList();
        var centralCover = BoardSurfaceBlend.sample(scene, banks.getFirst(), center.x, center.y, center.z);
        assertEquals(1, Integer.bitCount(centralCover.mask()), "Exposed bars share one centre instead of radial material wedges");
        for (int bearing = 0; bearing < 72; bearing++) {
            double angle = bearing * Math.PI / 36;
            for (int step = 0; step <= 8; step++) {
                float radius = step * BoardGeometry.HEIGHT * .05f;
                float x = center.x + radius * (float) Math.cos(angle), y = center.y + radius * (float) Math.sin(angle);
                var cover = BoardSurfaceBlend.sample(scene, banks.getFirst(), x, y, center.z);
                assertTrue(Integer.bitCount(cover.mask()) <= 3, "Bounded palette at radius " + radius + ": " + cover);
                assertEquals(1, cover.grass() + cover.dirt() + cover.sand() + cover.rock() + cover.concrete() + cover.snow(), .00001f);
                for (var bank : banks) { assertEquals(cover, BoardSurfaceBlend.sample(scene, bank, x, y, center.z)); }
            }
        }
    }

    @Test
    void editingANeighbourChangesCoverageAndTheExistingSurfaceCacheKey() {
        Coords next = CENTER.translated(2);
        var before = scene(c -> tile(c, BoardScene.Surface.GRASS, 0, -1, 0));
        var after = scene(c -> tile(c, c.equals(next) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        var p = BoardGeometry.center(CENTER, 0).lerp(BoardGeometry.center(next, 0), .5f);
        assertEquals(0, BoardSurfaceBlend.sample(before, before.tile(CENTER), p.x, p.y, p.z).sand());
        assertTrue(BoardSurfaceBlend.sample(after, after.tile(CENTER), p.x, p.y, p.z).sand() > .1f);
        assertNotEquals(BoardSurface.geometryKey(before, before.tile(CENTER)), BoardSurface.geometryKey(after, after.tile(CENTER)));
    }
}
