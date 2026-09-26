/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

class BoardTerrainDetailTest {
    static final Coords WATER = new Coords(3, 3);

    @Test
    void everyBankAndShallowBarUsesItsOwnNeighbourIncludingRoadAndConcreteGround() {
        for (int depth : new int[] { 0, 1 }) {
            for (boolean mouths : new boolean[] { false, true }) {
                BoardScene scene = shores(depth, mouths);
                BoardSurface surface = new BoardSurface(scene, scene.tile(WATER));
                int[] banks = new int[6];
                for (var face : surface.faces) {
                    if (face.finish() != BoardSurface.Finish.TOP && face.finish() != BoardSurface.Finish.BED) { continue; }
                    assertTrue(face.landEdge() >= 0, "Every bank retains its source edge");
                    var land = scene.tile(WATER.translated(BoardGeometry.edgeDirection(face.landEdge())));
                    if (!land.liquid().present()) {
                        assertEquals(land.surface(), surface.family(face));
                        banks[face.landEdge()]++;
                    } else if (face.finish() == BoardSurface.Finish.TOP) {
                        fail("A mouth's dry stub must take the bank's material, not the water neighbour");
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a()));
                    assertTrue(normal.z >= -.001f * normal.len(), () -> "Folded material partition: " + face);
                }
                for (int e = 0; e < 6; e++) {
                    var land = scene.tile(WATER.translated(BoardGeometry.edgeDirection(e)));
                    if (!land.liquid().present()) { assertTrue(banks[e] > 0, "Missing shore for edge " + e); }
                }
            }
        }
    }

    @Test
    void roughUsesEngineGroundAndStableBouldersOfTheLocalGeology() {
        Coords coords = new Coords(2, 2);
        for (var family : BoardScene.Surface.values()) {
            Hex hex = roughHex(family, 1);
            assertEquals(family, BoardFeatures.surface(hex));
            assertTrue(BoardFeatures.detailedGround(hex, Map.of()), "Rough artwork is replaced by the terrain engine");
            var features = BoardFeatures.capture(hex, coords, Map.of());
            assertEquals(9, features.size());
            assertTrue(features.stream().allMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
            assertEquals(features, BoardFeatures.capture(hex, coords, Map.of()));
            hex.addTerrain(new Terrain(Terrains.ROUGH, 2));
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).size() > features.size(), "Ultra rough has more cover");
            hex.addTerrain(new Terrain(Terrains.WOODS, 1));
            var wooded = BoardFeatures.capture(hex, coords, Map.of());
            assertEquals(3, wooded.stream().filter(f -> f.kind() == BoardScene.FeatureKind.TREE).count());
            assertEquals(14, wooded.stream().filter(f -> f.kind() == BoardScene.FeatureKind.BOULDER).count());
            hex.removeTerrain(Terrains.ROUGH);
            assertTrue(BoardFeatures.capture(hex, coords, Map.of()).stream()
                  .noneMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER), "Terrain edits remove the rocks");
        }
    }

    @Test
    void roughBouldersHaveGroundedBasesAndLeaveTheUnitAnchorClearOnSlopes() {
        for (var family : BoardScene.Surface.values()) {
            BoardScene scene = rough(family);
            var tile = scene.tile(new Coords(2, 2));
            BoardSurface surface = new BoardSurface(scene, tile);
            var ground = surface.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP
                  && f.finish() != BoardSurface.Finish.DRESSING).toList();
            var rocks = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.OUTCROP).toList();
            assertFalse(rocks.isEmpty(), "Rough must generate real rock geometry for " + family);
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            int buried = 0, exposed = 0;
            for (var face : rocks) {
                assertEquals(family, surface.family(face));
                for (var p : List.of(face.a(), face.b(), face.c())) {
                    assertNotNull(surface.relief.shade(p), "Rocks use the terrain shader's local geology");
                    assertTrue(Math.hypot(p.x - center.x, p.y - center.y) > 12 * BoardGeometry.HEX_SCALE);
                    float z = BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN);
                    assertTrue(Float.isFinite(z), "The whole rock footprint has ground below it");
                    if (p.z < z) { buried++; } else { exposed++; }
                }
            }
            assertTrue(buried > 0 && exposed > 0, "Rocks emerge from the terrain instead of floating above it");
            assertEquals(center.z, surface.height(center.x, center.y), .01f);
            assertEquals(surface.faces, new BoardSurface(scene, tile).faces, "Rebuilding never reshuffles the rocks");
        }
    }

    @Test
    void roughKeepsRoadExitsAndBuildingFoundationsClear() {
        Coords coords = new Coords(2, 2);
        Hex hex = roughHex(BoardScene.Surface.GRASS, 2);
        hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 9));
        for (var rock : BoardFeatures.capture(hex, coords, Map.of())) {
            assertTrue(Math.abs(rock.x()) >= 9 + 6 * rock.scale(), "North/south road clearance includes rock width");
        }
        hex.addTerrain(new Terrain(Terrains.BUILDING, 1));
        assertTrue(BoardFeatures.capture(hex, coords, Map.of(Terrains.BUILDING, "building")).stream()
              .noneMatch(f -> f.kind() == BoardScene.FeatureKind.BOULDER));
    }

    static BoardScene shores(int depth, boolean mouths) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                BoardScene.Surface family = BoardScene.Surface.GRASS;
                boolean wet = coords.equals(WATER);
                int road = 0;
                for (int e = 0; e < 6; e++) {
                    if (coords.equals(WATER.translated(BoardGeometry.edgeDirection(e)))) {
                        family = BoardScene.Surface.values()[e];
                        wet |= mouths && (e == 1 || e == 4);
                        road = e == 0 ? 9 : 0;
                    }
                }
                tiles.add(tile(coords, family, 0, wet ? depth : -1, road, List.of()));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    static BoardScene rough(BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords coords = new Coords(x, y);
                Hex hex = roughHex(family, y < 3 ? 1 : 2);
                tiles.add(tile(coords, family, x < 2 ? 0 : 1, -1, 0, BoardFeatures.capture(hex, coords, Map.of())));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static Hex roughHex(BoardScene.Surface family, int level) {
        Hex hex = new Hex(0);
        hex.addTerrain(new Terrain(Terrains.ROUGH, level));
        switch (family) {
            case SAND -> hex.addTerrain(new Terrain(Terrains.SAND, 1));
            case SNOW -> hex.addTerrain(new Terrain(Terrains.SNOW, 1));
            case CONCRETE -> hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1));
            case DIRT -> hex.setTheme("dirt");
            case ROCK -> hex.setTheme("rock");
            default -> { }
        }
        return hex;
    }

    private static BoardScene.Tile tile(Coords coords, BoardScene.Surface family, int level, int depth, int road,
          List<BoardScene.Feature> features) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, 0xff7b875b); }
        }
        BoardScene.Pixels pixels = new BoardScene.Pixels(image);
        return new BoardScene.Tile(coords, level, depth, false, road, family, pixels, null, null, null, null,
              features, List.of(), depth < 0 ? BoardLiquid.NONE : BoardLiquid.WATER, null, road == 0);
    }
}
