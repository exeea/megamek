/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class TerrainLodTest {
    @Test
    void waterCliffsMeetAcrossMixedDetailChunkBoundaries() {
        var previous = BoardRelief.tuning();
        try {
            BoardWetCliffTest.tune(true);
            Coords land = new Coords(7, 3);
            var scene = waterScene(land);
            for (TerrainLod lod : TerrainLod.values()) {
                var upper = new BoardSurface(scene, scene.tile(land), lod);
                var walls = upper.walls(scene, BoardGeometry.floor(scene));
                for (int direction = 0; direction < 6; direction++) {
                    Coords at = land.translated(direction);
                    var water = new BoardSurface(scene, scene.tile(at), at.getX() < 8 ? lod : TerrainLod.FULL);
                    int edge = Math.floorMod(1 - direction, 6);
                    var submerged = water.faces.stream().filter(face -> face.landEdge() == (edge + 3) % 6
                          && face.finish() == BoardSurface.Finish.WALL)
                          .flatMap(face -> List.of(face.a(), face.b(), face.c()).stream()).toList();
                    for (var face : walls) {
                        if (face.landEdge() != edge) { continue; }
                        for (var p : List.of(face.a(), face.b(), face.c())) {
                            if (Math.abs(p.z) > .001f) { continue; }
                            assertTrue(submerged.stream().anyMatch(q -> q.epsilonEquals(p, .001f)),
                                  lod + ": submerged cliff must meet every upper-cliff vertex at " + p);
                        }
                    }
                }
            }
        } finally { BoardRelief.tune(previous); }
    }

    @Test
    void waterFieldDoesNotIntroduceASeamBetweenDetailLevels() {
        var scene = waterScene(new Coords(7, 3));
        Map<Coords, BoardSurface> left = new HashMap<>(), right = new HashMap<>();
        for (var tile : scene.tiles()) {
            (tile.coords().getX() < 8 ? left : right).put(tile.coords(),
                  new BoardSurface(scene, tile, tile.coords().getX() < 8 ? TerrainLod.DISTANT : TerrainLod.FULL));
        }
        var a = GpuWaterShader.Field.prepare(scene, Map.of(), left);
        var b = GpuWaterShader.Field.prepare(scene, Map.of(), right);
        assertEquals(a.spacing(), b.spacing());
        int compared = 0;
        for (int y = Math.max(a.firstY(), b.firstY()); y < Math.min(a.firstY() + a.height(), b.firstY() + b.height()); y++) {
            for (int x = Math.max(a.firstX(), b.firstX()); x < Math.min(a.firstX() + a.width(), b.firstX() + b.width()); x++) {
                for (int channel = 0; channel < 4; channel++) {
                    int ai = ((y - a.firstY()) * a.width() + x - a.firstX()) * 4 + channel;
                    int bi = ((y - b.firstY()) * b.width() + x - b.firstX()) * 4 + channel;
                    assertEquals(Byte.toUnsignedInt(a.pixels()[ai]), Byte.toUnsignedInt(b.pixels()[bi]), 1,
                          "Shared water-field sample " + x + "," + y + " channel " + channel);
                    compared++;
                }
            }
        }
        assertTrue(compared > 100, "The fields must overlap at a real chunk boundary");
    }

    private static BoardScene waterScene(Coords land) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 7; y++) {
                var at = new Coords(x, y);
                boolean dry = at.equals(land);
                tiles.add(new BoardScene.Tile(at, dry ? 5 : 0, dry ? -1 : 1, false, 0, BoardScene.Surface.SAND,
                      null, null, null, null, null, List.of(), List.of(), dry ? BoardLiquid.NONE : BoardLiquid.WATER, null, true));
            }
        }
        return new BoardScene(0, 10, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    @Test
    void screenSizeSelectsDetailWithHysteresis() {
        assertEquals(TerrainLod.FULL, TerrainLod.select(200, null));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(40, null));
        assertEquals(TerrainLod.COARSE, TerrainLod.select(10, null));
        assertEquals(TerrainLod.DISTANT, TerrainLod.select(3, null));
        assertEquals(TerrainLod.FULL, TerrainLod.select(60, TerrainLod.FULL));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(68, TerrainLod.MEDIUM));
        assertEquals(TerrainLod.FULL, TerrainLod.select(72, TerrainLod.MEDIUM));
        assertEquals(TerrainLod.MEDIUM, TerrainLod.select(55, TerrainLod.FULL));
        assertEquals(TerrainLod.FULL, TerrainLod.select(200, TerrainLod.DISTANT));
        assertEquals(TerrainLod.DISTANT, TerrainLod.select(3, TerrainLod.FULL));
    }

    @Test
    void disablingDetailSelectionKeepsFullDetailWithoutInvalidatingTerrainShape() {
        boolean previous = TerrainLod.enabled();
        int revision = BoardGeometry.terrainRevision();
        try {
            TerrainLod.setEnabled(false);
            assertEquals(TerrainLod.FULL, TerrainLod.select(1, null), "New terrain starts at full detail");
            for (TerrainLod current : TerrainLod.values()) {
                assertEquals(TerrainLod.FULL, TerrainLod.select(1, current), "Installed terrain refines to full detail");
            }
            TerrainLod.setEnabled(true);
            assertEquals(TerrainLod.DISTANT, TerrainLod.select(1, TerrainLod.FULL));
            assertEquals(revision, BoardGeometry.terrainRevision(), "The switch only changes render sampling");
        } finally { TerrainLod.setEnabled(previous); }
    }

    @Test
    void changingDetailThresholdsDoesNotInvalidateTerrainShape() {
        var previous = TerrainLod.tuning();
        int revision = BoardGeometry.terrainRevision();
        try {
            TerrainLod.tune(new TerrainLod.Tuning(80, 40));
            assertEquals(TerrainLod.COARSE, TerrainLod.select(30, null));
            assertEquals(revision, BoardGeometry.terrainRevision());
        } finally {
            TerrainLod.tune(previous);
        }
    }
}
