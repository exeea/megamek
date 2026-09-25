/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardConcreteShoreTest {
    private static final Coords CENTER = new Coords(3, 3);

    @Test
    void neighboringConcreteBanksJoinInAStraightLineWhenTheWaterCentreIsClear() {
        var original = BoardRelief.tuning();
        try {
            for (float width : new float[] { .05f, .5f, 1 }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene scene = scene(Set.of(CENTER.translated(direction), CENTER.translated((direction + 1) % 6)),
                          BoardScene.Surface.CONCRETE, false);
                    BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                    int edge = Math.floorMod(-direction, 6), n = BoardSurface.SHORE_SEGMENTS;
                    Vector3 a = surface.outline.get(edge * n), b = surface.outline.get((edge + 2) % 6 * n);
                    for (int i = 1; i < 2 * n; i++) {
                        Vector3 p = surface.outline.get((edge * n + i) % (6 * n));
                        assertEquals(0, distance(a, b, p), .002f, "Straight concrete edge, direction=" + direction);
                    }
                    assertWaterCentre(surface);
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    @Test
    void longConcreteQuayHasNoHexByHexDentsBesideItsBuildings() {
        BoardScene scene = GpuRiverTerrainSmokeTest.quayScene(BoardScene.Surface.CONCRETE);
        float line = Float.NaN;
        for (int y = 4; y <= 7; y++) {
            BoardSurface water = new BoardSurface(scene, scene.tile(new Coords(3, y)));
            int n = BoardSurface.SHORE_SEGMENTS;
            for (int i = 0; i <= 2 * n; i++) {
                Vector3 p = water.outline.get((5 * n + i) % (6 * n));
                if (Float.isNaN(line)) { line = p.x; }
                assertEquals(line, p.x, .002f, "One continuous constructed edge at row " + y);
            }
        }
    }

    @Test
    void concreteNeverClosesAnInletAcrossItsWaterCentreOrFoldsItsBed() {
        for (int mask = 0; mask < 64; mask++) {
            Set<Coords> land = new HashSet<>();
            for (int direction = 0; direction < 6; direction++) {
                if ((mask & 1 << direction) != 0) { land.add(CENTER.translated(direction)); }
            }
            BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertWaterCentre(surface);
            for (var face : surface.faces) {
                if (face.finish() != BoardSurface.Finish.BED && face.finish() != BoardSurface.Finish.TOP) { continue; }
                Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                assertTrue(normal.z >= -.001f * normal.len(), "Unfolded concrete shore, mask=" + mask);
            }
        }
    }

    @Test
    void buildingsKeepTheirWholeConcreteFoundationAndConcreteBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        BoardScene scene = scene(land, BoardScene.Surface.CONCRETE, true);
        BoardSurface water = new BoardSurface(scene, scene.tile(CENTER));
        assertEquals(BoardScene.Surface.CONCRETE.ordinal(), water.relief.family(),
              "Authored building artwork must not turn the adjoining concrete bank into grass");
        for (Coords coords : land) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            Vector3 center = BoardGeometry.center(coords, 0);
            for (int k = 0; k < 6; k++) {
                Vector3 corner = BoardGeometry.corner(coords, 0, k);
                assertArrayEquals(new float[] { 0, 0 }, surface.relief.shoreShift(k), .0001f,
                      "No recession into a concrete building hex");
                for (float radius : new float[] { 0, .25f, .5f, .75f, .99f }) {
                    Vector3 p = new Vector3(center).lerp(corner, radius);
                    assertEquals(0, BoardSurface.sampleHeight(surface.faces, p.x, p.y, Float.NaN), .001f,
                          "The complete foundation stays at its original level");
                }
            }
        }
    }

    @Test
    void naturalMaterialsKeepTheirOwnCurvedBanks() {
        Set<Coords> land = Set.of(CENTER.translated(1), CENTER.translated(2));
        for (BoardScene.Surface family : BoardScene.Surface.values()) {
            if (family == BoardScene.Surface.CONCRETE) { continue; }
            BoardScene scene = scene(land, family, false);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            assertEquals(family.ordinal(), surface.relief.family());
            int n = BoardSurface.SHORE_SEGMENTS;
            Vector3 a = surface.outline.get(5 * n), b = surface.outline.get(n);
            float bend = 0;
            for (int i = 1; i < 2 * n; i++) {
                bend = Math.max(bend, distance(a, b, surface.outline.get((5 * n + i) % (6 * n))));
            }
            assertTrue(bend > BoardGeometry.HEX_SCALE, "Natural banks still curve: " + family);
        }
    }

    private static float distance(Vector3 a, Vector3 b, Vector3 p) {
        return Math.abs((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x))
              / (float) Math.hypot(b.x - a.x, b.y - a.y);
    }

    private static void assertWaterCentre(BoardSurface surface) {
        Vector3 center = BoardGeometry.center(CENTER, 0);
        for (int angle = 0; angle < 12; angle++) {
            float x = center.x + 8 * BoardGeometry.HEX_SCALE * (float) Math.cos(angle * Math.PI / 6);
            float y = center.y + 8 * BoardGeometry.HEX_SCALE * (float) Math.sin(angle * Math.PI / 6);
            assertTrue(Float.isFinite(BoardSurface.sampleHeight(surface.waterFaces, x, y, Float.NaN)),
                  "A water unit's footprint remains in water");
        }
    }

    private static BoardScene scene(Set<Coords> land, BoardScene.Surface family, boolean buildings) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                boolean dry = land.contains(coords);
                List<BoardScene.Feature> features = dry && buildings
                      ? List.of(new BoardScene.Feature("building", 0, 0, 0, 1, 3, 0, BoardScene.FeatureKind.BUILDING)) : List.of();
                tiles.add(new BoardScene.Tile(coords, 0, dry ? -1 : 2, false, 0,
                      dry ? family : BoardScene.Surface.GRASS, null, null, null, null, null, features, List.of(),
                      dry ? BoardLiquid.NONE : BoardLiquid.WATER, null, features.isEmpty()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
