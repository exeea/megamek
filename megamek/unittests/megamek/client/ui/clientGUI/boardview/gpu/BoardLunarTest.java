/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.boardview.BoardHexText;
import megamek.common.Hex;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardLunarTest {
    private static final Coords ORIGIN = new Coords(0, 0);
    private static final BoardScene.Pixels PIXELS = new BoardScene.Pixels(
          new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));

    @ParameterizedTest
    @CsvSource({ "2, 1, 1", "0, 2, -2", "2, 2, 0", "-1, 2, -3", "2, 0, 2", "2, -1, 2" })
    void depthBecomesDryGroundForLabelsSupportAndPicking(int elevation, int depth, int expected) {
        var original = tile(elevation, depth, BoardLiquid.WATER, BoardScene.Surface.GRASS, List.of());
        var lunar = original.lunar();
        var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
        var surface = new BoardSurface(scene, lunar);
        var center = BoardGeometry.center(ORIGIN, expected);
        assertEquals(expected, lunar.elevation());
        assertEquals(expected * BoardGeometry.level(), BoardGeometry.groundZ(lunar), .001f);
        assertEquals(center.z, surface.height(center.x, center.y), .001f);
        assertEquals(ORIGIN, BoardGeometry.pick(scene, new Ray(center.cpy().add(0, 0, 500), new Vector3(0, 0, -1))));
        assertTrue(surface.waterFaces.isEmpty());
        assertEquals(expected == 0 ? List.of() : List.of(Messages.getString("BoardView1.LEVEL") + expected),
              lunar.text().stream().map(BoardHexText::text).toList());
        assertEquals(lunar, lunar.lunar(), "Repeated presentation must not lower the bed again");
        assertEquals(elevation, original.elevation());
        assertEquals(depth, original.waterDepth());
    }

    @ParameterizedTest
    @CsvSource({ "0, 2, 1, 3", "2, 1, 1, 2", "-1, 3, 2, 5", "0, 0, 1, 1", "0, -1, 1, 1" })
    void bridgesAndTheirLabelsStayAtTheOriginalAbsoluteLevel(int elevation, int depth, int height, int expectedHeight) {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, height, BoardScene.FeatureKind.PROP, 9);
        var label = new BoardHexText(Messages.getString("BoardView1.HEIGHT") + " " + height, 46,
              new Font(Font.SANS_SERIF, Font.PLAIN, 10), -1, false, height);
        var original = new BoardScene.Tile(ORIGIN, elevation, depth, false, 0, BoardScene.Surface.ROCK,
              PIXELS, null, null, List.of(bridge), List.of(label));
        var lunar = original.lunar();
        var raised = BoardBridge.feature(lunar);
        assertEquals(expectedHeight, raised.elevation());
        assertEquals(bridge.bridgeExits(), raised.bridgeExits());
        assertEquals(elevation + height, lunar.elevation() + raised.elevation());
        assertEquals(Messages.getString("BoardView1.HEIGHT") + " " + expectedHeight, lunar.text().getFirst().text());
        assertEquals(elevation + height, lunar.elevation() + lunar.text().getFirst().elevation());
        assertEquals(lunar, lunar.lunar(), "Repeated presentation must not raise the bridge again");
        assertEquals(height, BoardBridge.feature(original).elevation(), "The source bridge stays unchanged");

        var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
        var shape = BoardNaturalBridge.build(scene, lunar, BoardBridge.deck(scene, lunar), TerrainLod.COARSE, new HashMap<>());
        var center = BoardGeometry.center(ORIGIN, elevation + height);
        assertEquals(elevation + height, shape.level());
        assertEquals(100, shape.hit(new Ray(center.add(0, 0, 10), new Vector3(0, 0, -1))), .001f,
              "Rendered and picked deck geometry stays at the original level");
    }

    @Test
    void spansOverDifferentDepthsKeepTheirConnectedDeckLevel() {
        var bridge = new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 1, BoardScene.FeatureKind.PROP, 9);
        var deep = new BoardScene.Tile(ORIGIN, 0, 2, false, 0, BoardScene.Surface.ROCK,
              PIXELS, null, null, List.of(bridge), List.of()).lunar();
        var shallow = new BoardScene.Tile(ORIGIN.translated(3), 0, 1, false, 0, BoardScene.Surface.ROCK,
              PIXELS, null, null, List.of(bridge), List.of()).lunar();
        assertEquals(3, BoardBridge.feature(deep).elevation());
        assertEquals(2, BoardBridge.feature(shallow).elevation());
        assertTrue(BoardBridge.connected(deep, shallow, 3));
        assertTrue(BoardBridge.connected(shallow, deep, 0));
    }

    @Test
    void allLiquidAndSurfaceFamiliesBecomeDryRockWithoutVegetationOrIce() {
        var building = feature("building", BoardScene.FeatureKind.BUILDING);
        var boulder = feature("boulder-0", BoardScene.FeatureKind.BOULDER);
        var features = List.of(building, boulder, feature("tree-oak", BoardScene.FeatureKind.TREE),
              feature("foliage-temperate", BoardScene.FeatureKind.TREE),
              feature("scatter-plant", BoardScene.FeatureKind.SCATTER),
              feature("scatter-rock", BoardScene.FeatureKind.SCATTER),
              feature("rough/felled-trunk", BoardScene.FeatureKind.ROUGH), feature("field", BoardScene.FeatureKind.PROP));
        for (var kind : BoardLiquid.Kind.values()) {
            for (var family : BoardScene.Surface.values()) {
                var lunar = tile(0, -1, new BoardLiquid(kind, "", 2), family, features).lunar();
                var scene = new BoardScene(0, 1, 1, List.of(lunar), List.of(), List.of(), -1, "", List.of());
                assertEquals(BoardScene.Surface.LUNAR, lunar.surface());
                assertTrue(lunar.detailedGround(), "The LUNAR material must replace captured ground artwork");
                assertEquals(BoardLiquid.NONE, lunar.liquid());
                assertFalse(lunar.water());
                assertFalse(lunar.frozen());
                assertFalse(lunar.blackIce());
                assertEquals(BoardScene.Biome.NONE, lunar.biome());
                assertEquals(BoardScene.Biome.NONE, BoardBiome.kind(lunar));
                assertFalse(GpuGroundCover.grows(scene, lunar));
                assertFalse(BoardScatter.allowed(lunar), "Neither captured scatter nor field cover dresses bare rock");
                assertEquals(List.of(building, new BoardScene.Feature(BoardRocks.OUTCROP, 0, 0, 0, 3, 1.5f, 0,
                      BoardScene.FeatureKind.BOULDER)), lunar.features(), "A loose boulder becomes bedrock");
                assertNull(lunar.foliage());
                assertNull(lunar.decals());
                assertNull(lunar.decalsWithoutLimbs());
                assertEquals(BoardRoad.Kind.PAVED, lunar.road());
                assertTrue(lunar.impassable());
            }
        }
    }

    @Test
    void roughBouldersBecomeFewerLargerOutcropsOnce() {
        var boulders = new ArrayList<BoardScene.Feature>();
        for (int i = 0; i < 9; i++) {
            boulders.add(new BoardScene.Feature("rough-boulder", i, -i, 40 * i, .8f, .3f, 0, BoardScene.FeatureKind.BOULDER));
        }
        var lunar = tile(0, -1, BoardLiquid.NONE, BoardScene.Surface.GRASS, boulders).lunar();
        assertEquals(3, lunar.features().size(), "Every third boulder of the spiral, from its centre, becomes bedrock");
        for (int i = 0; i < lunar.features().size(); i++) {
            var outcrop = lunar.features().get(i);
            var boulder = boulders.get(3 * i);
            assertEquals(BoardRocks.OUTCROP, outcrop.asset());
            assertEquals(BoardScene.FeatureKind.BOULDER, outcrop.kind(), "Outcrops remain rough cover for support");
            assertEquals(boulder.x(), outcrop.x());
            assertEquals(boulder.y(), outcrop.y());
            assertTrue(outcrop.scale() > boulder.scale() && outcrop.height() >= boulder.height());
        }
        assertEquals(lunar, lunar.lunar(), "Repeated presentation must not thin the outcrops again");
    }

    @Test
    void openGroundKeepsNoLooseStonesOrShrubs() {
        // The relief dresses featureless open ground with field stones and shrubs. Stripping a meadow's captured
        // scatter must not hand the bare plain to that dressing, nor may a rocky plain's cached surface keep it.
        for (boolean meadow : new boolean[] { true, false }) {
            var tiles = new ArrayList<BoardScene.Tile>();
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    var coords = new Coords(x, y);
                    tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0,
                          meadow ? BoardScene.Surface.GRASS : BoardScene.Surface.ROCK, PIXELS, null, null, null, null,
                          meadow ? BoardFeatures.capture(new Hex(0), coords, Map.of()) : List.of(), List.of(),
                          BoardLiquid.NONE, null, true));
                }
            }
            var plain = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
            var surfaces = new BoardSurface.Cache();
            assertTrue(standing(plain, surfaces) > 0, "At one g featureless hexes carry field stones and shrubs");
            assertEquals(0, standing(plain.withTiles(tiles.stream().map(BoardScene.Tile::lunar).toList()), surfaces),
                  meadow ? "Stripped scatter leaves no field cover behind" : "Bare rock never reuses a one-g surface");
        }
    }

    /** Faces standing on a flat plain without rough: its field stones and shrubs, which also carry units. */
    private static long standing(BoardScene scene, BoardSurface.Cache surfaces) {
        return scene.tiles().stream().mapToLong(tile -> surfaces.get(scene, tile).faces.stream()
              .filter(face -> face.finish() == BoardSurface.Finish.OUTCROP).count()).sum();
    }

    private static BoardScene.Feature feature(String asset, BoardScene.FeatureKind kind) {
        return new BoardScene.Feature(asset, 0, 0, 0, 1, 1, 0, kind);
    }

    private static BoardScene.Tile tile(int elevation, int depth, BoardLiquid liquid, BoardScene.Surface family,
          List<BoardScene.Feature> features) {
        var font = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        var labels = new ArrayList<BoardHexText>();
        if (elevation != 0) {
            labels.add(new BoardHexText(Messages.getString("BoardView1.LEVEL") + elevation, 66, font, -1, false, 0));
        }
        if (depth > 0) {
            labels.add(new BoardHexText(Messages.getString("BoardView1.DEPTH") + depth, 56, font, -1, false, 0));
        }
        labels.add(new BoardHexText(Messages.getString("BoardView1.LowFoliage"), 46, font, -1, false, 0));
        return new BoardScene.Tile(ORIGIN, elevation, depth, true, 0, family, PIXELS, PIXELS, PIXELS, PIXELS,
              null, features, labels, liquid, PIXELS, false, BoardRoad.Kind.PAVED, BoardFireSmoke.NONE,
              BoardScene.Biome.MARSH, true, true);
    }
}
