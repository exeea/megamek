/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BoardRimTest {
    private static final Coords CENTER = new Coords(3, 3);
    private final BoardScene.Pixels ground = pixels(0xff505050);
    private final BoardScene.Pixels neutral = pixels(0xff8080ff);

    @AfterEach
    void restoreGeometry() { BoardGeometry.tune(BoardGeometry.DEFAULTS); }

    @Test
    void concurrentCompositionMatchesSerialArtwork() throws Exception {
        BoardRim parallel = new BoardRim(), serial = new BoardRim();
        BoardScene.Pixels mask = pixels(0xff606060);
        var scenes = java.util.stream.IntStream.range(0, 48)
              .mapToObj(i -> scene(i % 6, false, i % 2 == 0, ground, neutral)).toList();
        var expected = scenes.stream().map(scene -> serial.material(scene, scene.tile(CENTER),
              BoardGeometry.floor(scene), mask, mask)).toList();
        TerrainSettings settings = TerrainSettings.capture();
        try (var workers = new java.util.concurrent.ForkJoinPool(2)) {
            var actual = workers.submit(() -> scenes.parallelStream().map(scene -> settings.call(() ->
                  parallel.material(scene, scene.tile(CENTER), BoardGeometry.floor(scene), mask, mask))).toList()).get();
            assertEquals(expected, actual, "Parallel cache publication must preserve the exact composed pixels");
        }
    }

    @Test
    void rimsLeaveMissingGroundNormalsAbsent() {
        for (int neighborElevation : new int[] { 0, -1 }) {
            BoardScene scene = scene(0, false, neighborElevation, ground, null);
            BoardRim.Images material = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
                  assets(pixels(0xff606060)));
            assertNotEquals(ground.rgba(probe(0, 0.5f, 9)), material.color().rgba(probe(0, 0.5f, 9)));
            assertNull(material.normal(), "A rim must not create normals for ground that has none");
        }
    }

    @Test
    void rotatesColorOnAllSixEdgesAndPreservesGroundNormalsAtDifferentScales() {
        GpuAssets assets = assets(pixels(0xff606060));
        BoardScene.Pixels normal = pixels(0xffb060ee);
        for (float scale : new float[] { 0.5f, 1, 2 }) {
            BoardGeometry.tune(new BoardGeometry.Tuning(scale, 0.7f, 1, 18, 0.8f));
            for (int edge = 0; edge < 6; edge++) {
                BoardScene scene = scene(edge, false, false, ground, normal);
                BoardRim.Images material = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene), assets);
                int index = probe(edge, 0.5f, 9);
                assertEquals(Math.round(0x50 * shade(0x60, 1)), material.color().rgba(index) >>> 24, 1,
                      "The original mask shades the top layer by its lightness about mid gray");
                assertSame(normal, material.normal(), "Rim color must leave the ground's existing normals untouched");
                int centre = 36 * 84 + 42;
                assertEquals(ground.rgba(centre), material.color().rgba(centre));
            }
        }
    }

    @Test
    void midGrayPreservesGroundAndOriginalMaskAlphaWeightsTheShade() {
        BoardScene scene = scene(0, false, false);
        int index = probe(0, 0.5f, 9);
        BoardRim.Images transparent = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
              assets(pixels(0x00c03080)));
        assertEquals(ground.rgba(index), transparent.color().rgba(index), "Transparent pixels leave the ground untouched");
        BoardRim.Images gray = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
              assets(pixels(0xff808080)));
        assertEquals(ground.rgba(index), gray.color().rgba(index), "Mid gray leaves the top layer unchanged");
        BoardRim.Images faded = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
              assets(pixels(0x80606060)));
        assertEquals(Math.round(0x50 * shade(0x60, 0x80 / 255f)), faded.color().rgba(index) >>> 24, 1,
              "Half coverage takes half the shade");
    }

    @Test
    void brightRimsSaturateEachChannelWithoutCorruptingColorOrCoverage() {
        BoardScene scene = scene(0, false, false, pixels(0x80f08020), neutral);
        BoardRim.Images material = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
              assets(pixels(0xffffffff)));
        int pixel = material.color().rgba(probe(0, 0.5f, 9));
        assertEquals(255, pixel >>> 24, "A highlight saturates red instead of wrapping to a dark value");
        assertEquals(Math.round(128 * shade(255, 1)), pixel >>> 16 & 255);
        assertEquals(Math.round(32 * shade(255, 1)), pixel >>> 8 & 255);
        assertEquals(128, pixel & 255, "RGB overflow must never set alpha bits");
    }

    /** The libGDX branch's lightness rule, weighted by the original mask's coverage and opacity. */
    private static float shade(int gray, float coverage) {
        return 1 + coverage * BoardRim.BLEND_OPACITY * (gray / 128f - 1);
    }

    @Test
    void roadMouthStaysOpenAndRemovingTheCliffRestoresOriginalMaps() {
        GpuAssets assets = assets(pixels(0xff606060));
        BoardRim rims = new BoardRim();
        for (int edge = 0; edge < 6; edge++) {
            BoardScene scene = scene(edge, true, false);
            BoardRim.Images material = rims.material(scene, scene.tile(CENTER), BoardGeometry.floor(scene), assets);
            int middle = probe(edge, 0.5f, 3);
            assertEquals(ground.rgba(middle), material.color().rgba(middle), "Keep the road approach clear at edge " + edge);
            assertNotEquals(ground.rgba(probe(edge, 0.12f, 3)), material.color().rgba(probe(edge, 0.12f, 3)),
                  "The exposed shoulder keeps its rim at edge " + edge);
        }
        BoardScene level = scene(0, false, true);
        BoardRim.Images material = rims.material(level, level.tile(CENTER), BoardGeometry.floor(level), assets);
        assertSame(ground, material.color());
        assertSame(neutral, material.normal());
    }

    @Test
    void usesTheInclineMaskUpToTwoLevelsAndTheHighMaskBeyond() {
        GpuAssets assets = assets(pixels(0xff606060), pixels(0xffc0c0c0));
        BoardScene twoLevels = scene(0, false, 0, ground, neutral);
        BoardRim.Images gentle = new BoardRim().material(twoLevels, twoLevels.tile(CENTER),
              BoardGeometry.floor(twoLevels), assets);
        assertEquals(Math.round(0x50 * shade(0x60, 1)), gentle.color().rgba(probe(0, 0.5f, 9)) >>> 24, 1,
              "A two-level drop wears the incline mask");
        assertSame(neutral, gentle.normal());
        BoardScene threeLevels = scene(0, false, -1, ground, neutral);
        BoardRim.Images steep = new BoardRim().material(threeLevels, threeLevels.tile(CENTER),
              BoardGeometry.floor(threeLevels), assets);
        assertEquals(Math.round(0x50 * shade(0xc0, 1)), steep.color().rgba(probe(0, 0.5f, 9)) >>> 24, 1,
              "A three-level drop wears the high mask");
        assertSame(neutral, steep.normal());
    }

    @Test
    void tacticalColumnsShadeEachDropEdgeWithTheBoardsInclineSplit() {
        BoardScene.Pixels incline = pixels(0xff606060), high = pixels(0xffc0c0c0);
        int centre = 36 * 84 + 42;
        for (int edge = 0; edge < 6; edge++) {
            BoardScene gentle = scene(edge, false, 0, ground, null);
            BoardScene.Pixels art = new BoardRim().column(gentle, gentle.tile(CENTER), ground, incline, high);
            assertEquals(Math.round(0x50 * shade(0x60, 1)), art.rgba(probe(edge, 0.5f, 9)) >>> 24, 1,
                  "A two-level drop wears the incline mask at edge " + edge);
            assertEquals(ground.rgba(centre), art.rgba(centre));
            BoardScene road = scene(edge, true, 0, ground, null);
            BoardScene.Pixels ramp = new BoardRim().column(road, road.tile(CENTER), ground, incline, high);
            assertEquals(ground.rgba(probe(edge, 0.5f, 3)), ramp.rgba(probe(edge, 0.5f, 3)),
                  "A road ramping through the edge keeps its mouth clear at edge " + edge);
            assertNotEquals(ground.rgba(probe(edge, 0.12f, 3)), ramp.rgba(probe(edge, 0.12f, 3)),
                  "Beside the mouth the drop keeps its rim at edge " + edge);
            BoardScene steep = scene(edge, false, -1, ground, null);
            assertEquals(Math.round(0x50 * shade(0xc0, 1)),
                  new BoardRim().column(steep, steep.tile(CENTER), ground, incline, high).rgba(probe(edge, 0.5f, 9)) >>> 24,
                  1, "A three-level drop wears the high mask at edge " + edge);
            BoardScene cliff = cliffTop(gentle, BoardGeometry.edgeDirection(edge));
            assertEquals(Math.round(0x50 * shade(0xc0, 1)),
                  new BoardRim().column(cliff, cliff.tile(CENTER), ground, incline, high).rgba(probe(edge, 0.5f, 9)) >>> 24,
                  1, "A two-level drop the map marks as a cliff side wears the high mask at edge " + edge);
        }
        BoardScene level = scene(0, false, 2, ground, null);
        assertSame(ground, new BoardRim().column(level, level.tile(CENTER), ground, incline, high),
              "A column without a lower neighbour keeps its art");
    }

    @Test
    void reusesMaterialsForUnchangedInputsAndReleasesUnusedCombinations() {
        GpuAssets assets = assets(pixels(0xfff0f0f0));
        BoardRim rims = new BoardRim();
        BoardScene scene = scene(0, false, false);
        BoardRim.Images first = rims.material(scene, scene.tile(CENTER), BoardGeometry.floor(scene), assets);
        rims.retainUsed();
        BoardScene equivalent = scene(0, false, false);
        assertSame(first, rims.material(equivalent, equivalent.tile(CENTER), BoardGeometry.floor(equivalent), assets));
        rims.retainUsed();
        rims.retainUsed();
        assertNotSame(first, rims.material(scene, scene.tile(CENTER), BoardGeometry.floor(scene), assets));
    }

    @Test
    void smallCustomTexturesKeepRimCoverageAndAlignExistingGroundNormals() {
        BoardScene.Pixels smallGround = pixels(0xff505050, 2, 2);
        BoardScene.Pixels smallNormal = pixels(0xffb060ee, 2, 2);
        BoardScene scene = scene(0, false, false, smallGround, smallNormal);
        BoardRim.Images material = new BoardRim().material(scene, scene.tile(CENTER), BoardGeometry.floor(scene),
              assets(pixels(0xff606060)));
        assertEquals(84, material.color().width());
        assertEquals(72, material.color().height());
        assertEquals(Math.round(0x50 * shade(0x60, 1)), material.color().rgba(probe(0, 0.5f, 9)) >>> 24, 1);
        assertEquals(smallGround.rgba(0), material.color().rgba(36 * 84 + 42));
        assertEquals(material.color().width(), material.normal().width(), "Atlas colors and normals must align");
        assertEquals(material.color().height(), material.normal().height());
        for (int index = 0; index < 84 * 72; index++) {
            assertEquals(smallNormal.rgba(0), material.normal().rgba(index));
        }
    }

    private BoardScene scene(int edge, boolean road, boolean level) {
        return scene(edge, road, level, ground, neutral);
    }

    private BoardScene scene(int edge, boolean road, boolean level, BoardScene.Pixels color, BoardScene.Pixels normal) {
        return scene(edge, road, level ? 2 : 0, color, normal);
    }

    /** A level board except the one neighbor across {@code edge} sits at {@code neighborElevation}. */
    private BoardScene scene(int edge, boolean road, int neighborElevation, BoardScene.Pixels color, BoardScene.Pixels normal) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        int direction = BoardGeometry.edgeDirection(edge);
        Coords low = CENTER.translated(direction);
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                tiles.add(new BoardScene.Tile(coords, coords.equals(low) ? neighborElevation : 2, -1, false,
                      road && coords.equals(CENTER) ? 1 << direction : 0, BoardScene.Surface.GRASS,
                      color, normal, null, null, List.of(), List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** The centre hex with its manual cliff-top exit toward {@code direction}. */
    private static BoardScene cliffTop(BoardScene scene, int direction) {
        BoardScene.Tile t = scene.tile(CENTER);
        List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
        tiles.set(CENTER.getX() * scene.height() + CENTER.getY(), new BoardScene.Tile(t.coords(), t.elevation(),
              t.waterDepth(), t.frozen(), t.roadExits(), t.surface(), t.ground(), t.normals(), t.decals(),
              t.decalsWithoutLimbs(), t.tactical(), t.features(), t.text(), t.liquid(), t.tileset(), t.detailedGround(),
              t.road(), t.fireSmoke(), t.biome(), t.impassable(), t.blackIce(), 1 << direction));
        return scene.withTiles(tiles);
    }

    private static int probe(int edge, float along, float inward) {
        Vector3 a = BoardGeometry.corner(CENTER, 2, edge), b = BoardGeometry.corner(CENTER, 2, edge + 1);
        Vector3 direction = b.cpy().sub(a).nor();
        Vector3 point = a.lerp(b, along).mulAdd(new Vector3(-direction.y, direction.x, 0), inward * BoardGeometry.HEX_SCALE);
        float u = 0.5f + (point.x - BoardGeometry.centerX(CENTER)) / BoardGeometry.WIDTH * BoardRim.GROUND_UV_SCALE;
        float v = 0.5f - (point.y - BoardGeometry.centerY(CENTER)) / BoardGeometry.HEIGHT * BoardRim.GROUND_UV_SCALE;
        return (int) (v * 72) * 84 + (int) (u * 84);
    }

    private static GpuAssets assets(BoardScene.Pixels mask) {
        return assets(mask, mask);
    }

    private static GpuAssets assets(BoardScene.Pixels incline, BoardScene.Pixels high) {
        GpuAssets assets = mock(GpuAssets.class);
        when(assets.inclineMask()).thenReturn(incline);
        when(assets.highInclineMask()).thenReturn(high);
        return assets;
    }

    private static BoardScene.Pixels pixels(int color) {
        return pixels(color, 84, 72);
    }

    private static BoardScene.Pixels pixels(int color, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, color); }
        }
        return new BoardScene.Pixels(image);
    }
}
