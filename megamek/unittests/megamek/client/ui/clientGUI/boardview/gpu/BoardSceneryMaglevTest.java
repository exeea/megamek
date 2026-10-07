/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.files.FileHandle;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.BoardDecoration;
import megamek.common.board.BoardEditorBlueprint;
import megamek.common.board.Coords;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Old maps retain their compositions, while new boards can place the vehicle and rail pieces separately. */
class BoardSceneryMaglevTest {
    private static final String GROUP = "Maglev and wagons";
    private static final List<String> PIECES = List.of("maglev-platform", "maglev-wagon", "maglev-cab", "maglev-coupler");

    @Test
    void allLegacyLayoutsPreserveEveryTriangleAndColorWithoutDuplicatingTheCompleteMesh() {
        for (String family : List.of("track", "station", "train")) {
            for (int number = 1; number <= (family.equals("train") ? 6 : 3); number++) {
                String asset = "scenery/fluff/maglev" + family + number;
                var layout = BoardSceneryLayouts.layout(asset);
                assertNotNull(layout, asset);
                assertTrue(Files.exists(Configuration.dataDir().toPath().resolve("models/board/" + asset + ".glb")),
                      "The complete legacy model remains available as a compatibility reference");
                List<double[]> actual = new ArrayList<>();
                for (var component : layout.components()) {
                    assertFalse(component.asset().equals(asset), "The layout must not also render its complete old mesh");
                    assertTrue(component.asset().startsWith("scenery/components/"));
                    actual.addAll(triangles(component));
                }
                var expected = triangles(new BoardSceneryLayouts.Component(asset, BoardScene.FeatureKind.SCENERY,
                      0, 0, 0, 0, 1));
                assertEquals(expected.size(), actual.size(), asset);
                for (int vertex = 0; vertex < expected.size(); vertex++) {
                    for (int channel = 0; channel < 10; channel++) {
                        assertEquals(expected.get(vertex)[channel], actual.get(vertex)[channel], .0001,
                              asset + " vertex " + vertex + " position/normal/color channel " + channel);
                    }
                }
            }
        }
    }

    @Test
    void catalogueGroupsMaglevTogetherAndStandaloneWagonsContainNoRailOrRoadCars() {
        var blueprint = BoardEditorBlueprint.get();
        for (var asset : blueprint.assets()) {
            if (!asset.id().contains("maglev")) { continue; }
            assertEquals(GROUP, asset.group(), asset.id());
            if (asset.id().startsWith("scenery/fluff/")) {
                assertNotNull(asset.snap(), "Legacy rail compositions keep their compatible endpoints");
                assertEquals("maglev-track", asset.snap().set());
            }
        }
        for (String name : PIECES) {
            var asset = blueprint.asset("scenery/components/" + name);
            assertNotNull(asset);
            assertTrue(asset.palette());
            assertNull(asset.snap(), "A standalone vehicle/platform is not a rail segment");
            var vertices = triangles(new BoardSceneryLayouts.Component(asset.id(), BoardScene.FeatureKind.SCENERY,
                  0, 0, 0, 0, 1));
            assertEquals(0, vertices.stream().mapToDouble(v -> v[2]).min().orElseThrow(), .0001);
            assertTrue(vertices.stream().allMatch(v -> Math.abs(v[0]) <= 6 && Math.abs(v[1]) <= 21),
                  "Independent parts must not include the surrounding track or parked cars");
        }
        var train = BoardSceneryLayouts.layout("scenery/components/maglev-train");
        assertEquals(Set.of("scenery/components/maglev-wagon", "scenery/components/maglev-cab",
                    "scenery/components/maglev-coupler"),
              train.components().stream().map(BoardSceneryLayouts.Component::asset).collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, train.components().size());
        assertTrue(blueprint.asset("scenery/fluff/maglevtrain1").palette());
        for (String duplicate : List.of("scenery/fluff/maglevtrain3", "scenery/fluff/maglevtrain4")) {
            assertFalse(blueprint.asset(duplicate).palette());
            assertNotNull(BoardSceneryLayouts.layout(duplicate));
        }
    }

    @Test
    void legacyTerrainAndPlacedObjectsBothResolveThroughTheSameSharedLayouts() {
        var coords = new Coords(2, 2);
        String asset = "scenery/fluff/maglevtrain2";
        var layout = BoardSceneryLayouts.layout(asset);
        var hex = new Hex(0, "pavement:1", "");
        var scenery = new BoardArtwork.Scenery(List.of(asset), Set.of(Terrains.FLUFF), Set.of(Terrains.FLUFF), 0);
        var legacy = BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null, scenery, false);
        assertEquals(layout.components().size(), legacy.size());
        assertEquals(layout.components().stream().map(BoardSceneryLayouts.Component::asset).toList(),
              legacy.stream().map(BoardScene.Feature::asset).toList());

        var decoration = new BoardDecoration("train", "prop", asset, null, .1, -.2, 30, false, 1.5,
              BoardDecoration.Placement.ground(), 0);
        hex.setDecorations(List.of(decoration));
        var placed = BoardFeatures.capture(hex, coords, Map.of(), Set.of(), c -> null,
              new BoardArtwork.Scenery(List.of(), Set.of(), Set.of(), 0), false);
        assertEquals(layout.components().size(), placed.size());
        for (int i = 0; i < placed.size(); i++) {
            var source = layout.components().get(i);
            var feature = placed.get(i);
            assertEquals(decoration, feature.decoration(), "All pieces keep the single selected layout identity");
            assertEquals(source.asset(), feature.asset());
            assertEquals(source.scale() * 1.5, feature.scale(), .0001);
            assertEquals(source.rotation() + 30, feature.rotation(), .0001);
            assertEquals(source.z() * 1.5 / BoardGeometry.MODEL_LEVEL_HEIGHT, feature.elevation(), .0001);
        }
    }

    /** Expanded indexed vertices in renderer space, including normals and display-space vertex colors. */
    private static List<double[]> triangles(BoardSceneryLayouts.Component component) {
        var root = Configuration.dataDir().toPath().resolve("models/board");
        var model = RigidGlb.loadLods(new FileHandle(root.resolve(component.asset() + ".glb").toFile()), root).getFirst();
        double angle = Math.toRadians(component.rotation()), c = Math.cos(angle), s = Math.sin(angle);
        List<double[]> result = new ArrayList<>();
        for (var mesh : model.meshes) {
            float[] values = mesh.vertices;
            for (var part : mesh.parts) {
                for (short index : part.indices) {
                    int at = (index & 0xFFFF) * RigidGlb.STRIDE;
                    double x = values[at], y = values[at + 1], nx = values[at + 3], ny = values[at + 4];
                    result.add(new double[] { (x * c - y * s) * component.scale() + component.x(),
                          (x * s + y * c) * component.scale() + component.y(),
                          values[at + 2] * component.scale() + component.z(),
                          nx * c - ny * s, nx * s + ny * c, values[at + 5],
                          values[at + 6], values[at + 7], values[at + 8], values[at + 9] });
                }
            }
        }
        return result;
    }
}
