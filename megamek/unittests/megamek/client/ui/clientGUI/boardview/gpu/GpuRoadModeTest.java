/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class GpuRoadModeTest {
    @Test
    void classicAndEnhancedPrepareTheSameRoadPaintAndGradedTriangles() {
        Coords junction = new Coords(2, 2), upper = junction.translated(0);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                int exits = at.equals(junction) ? 27 : 0;
                for (int d : new int[] { 0, 1, 3, 4 }) {
                    if (at.equals(junction.translated(d))) { exits = 1 << ((d + 3) % 6); }
                }
                tiles.add(BoardRoadTest.tile(at, exits == 0 ? BoardRoad.Kind.NONE : BoardRoad.Kind.PAVED,
                      exits, at.equals(upper) ? 1 : 0, BoardScene.Surface.GRASS));
            }
        }
        var scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        TerrainSettings settings = TerrainSettings.capture();
        settings.run(() -> {
            float floor = BoardGeometry.floor(scene);
            var classic = GpuTerrain.prepareColumns(scene, 0, 0, floor, Map.of(), Set.of(), Map.of(), Map.of(),
                  () -> { }, new TerrainLoadProgress());
            var enhanced = GpuTerrain.prepare(scene, 0, 0, floor, TerrainLod.FULL, Map.of(), settings, Set.of(), Map.of(),
                  () -> { }, new TerrainLoadProgress(), new GpuRoads.Masks());
            assertFalse(classic.roads().isEmpty(), "Classic installs generated roads rather than only tileset art");
            assertEquals(enhanced.roads().keySet(), classic.roads().keySet(), "Spill receivers belong to both views");
            boolean graded = false;
            for (var entry : enhanced.roads().entrySet()) {
                var actual = classic.roads().get(entry.getKey());
                assertEquals(entry.getValue().size(), actual.size());
                for (int i = 0; i < actual.size(); i++) {
                    var expected = entry.getValue().get(i);
                    var patch = actual.get(i);
                    assertEquals(expected.source(), patch.source());
                    assertEquals(expected.mask(), patch.mask(), "The same markings and feathering use the same mask");
                    assertEquals(expected.triangles(), patch.triangles(), "The carriageway drapes on one shared grade");
                    assertEquals(expected.patch().texture(), patch.patch().texture());
                    assertEquals(expected.patch().tint(), patch.patch().tint());
                    assertEquals(expected.patch().fade(), patch.patch().fade());
                    assertEquals(expected.flat(), patch.flat());
                    Area difference = new Area(expected.patch().shape());
                    difference.exclusiveOr(patch.patch().shape());
                    assertTrue(difference.isEmpty());
                    graded |= patch.triangles().stream().anyMatch(t -> Math.abs(t.a().z - t.b().z) > .001f
                          || Math.abs(t.a().z - t.c().z) > .001f);
                }
            }
            assertTrue(graded, "The fixture includes an actual road grade, not just flat paint");
        });
    }
}
