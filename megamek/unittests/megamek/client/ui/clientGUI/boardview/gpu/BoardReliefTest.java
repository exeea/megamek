/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Test;

/** Rim prominence by drop, concrete slabs, tree pits, special artwork, joined landforms and compressed elevations. */
class BoardReliefTest {
    private static final Coords CENTER = new Coords(2, 2);

    private static BoardScene scene(int centerLevel, boolean detailed, BoardScene.Surface family) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                boolean center = x == CENTER.getX() && y == CENTER.getY();
                tiles.add(new BoardScene.Tile(new Coords(x, y), center ? centerLevel : 0, -1, false, 0, family, null,
                      null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, center ? detailed : true));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    /** Largest distance the cliff's upper tenth stands out beyond the logical edge. */
    private static float rimReach(int level) {
        BoardScene scene = scene(level, true, BoardScene.Surface.SAND);
        BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
        Vector3 center = BoardGeometry.center(CENTER, level);
        float apothem = BoardGeometry.HEIGHT / 2, reach = 0;
        for (BoardSurface.Face face : surface.walls(scene, BoardGeometry.floor(scene))) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                if (p.z < center.z - .1f * BoardGeometry.LEVEL * level) { continue; }
                float radial = (float) Math.hypot(p.x - center.x, p.y - center.y);
                // Compare against the hexagon's own radius in that direction.
                float angle = (float) Math.atan2(p.y - center.y, p.x - center.x);
                float sector = (float) (Math.PI / 3);
                float local = (float) Math.abs(((angle % sector) + sector) % sector - sector / 2);
                float boundary = apothem / (float) Math.cos(local);
                reach = Math.max(reach, radial - Math.min(boundary, BoardGeometry.WIDTH / 2));
            }
        }
        return reach;
    }

    @Test
    void deepDropsCarryMoreProminentRimFormationsThanShallowOnes() {
        float shallow = Math.max(rimReach(1), rimReach(2));
        float deep = Math.min(rimReach(3), rimReach(4));
        assertTrue(deep > shallow * 1.2f, "Caprock reach: shallow " + shallow + ", deep " + deep);
        assertTrue(shallow > 0, "Even shallow drops have a readable lip");
    }

    @Test
    void specialArtworkKeepsALevelTopInsideItsSculptedOutline() {
        BoardScene scene = scene(2, false, BoardScene.Surface.GRASS);
        BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
        for (BoardSurface.Face face : surface.faces) {
            for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                assertEquals(2 * BoardGeometry.LEVEL, p.z, .001f, "Special ground art stays on its game level");
            }
        }
        assertTrue(surface.relief.sculpted(), "Its cliffs are still sculpted");
    }

    @Test
    void joinedLandformsShareHeightsAcrossFamiliesAndKeepAnchorsLevel() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), 1, -1, false, 0,
                      x < 2 ? BoardScene.Surface.GRASS : BoardScene.Surface.SAND, null, null, null, null, null,
                      List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        BoardScene scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
        float maximum = Float.NEGATIVE_INFINITY, minimum = Float.POSITIVE_INFINITY;
        for (Coords coords : List.of(new Coords(1, 2), new Coords(2, 2))) {
            BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
            Vector3 center = BoardGeometry.center(coords, 1);
            assertEquals(center.z, surface.height(center.x, center.y), .003f, "Unit anchors stay level");
            for (int direction = 0; direction < 6; direction++) {
                Coords other = coords.translated(direction);
                BoardSurface neighbor = new BoardSurface(scene, scene.tile(other));
                int edge = Math.floorMod(1 - direction, 6);
                for (int step = 1; step < BoardRelief.EDGE_STEPS; step++) {
                    Vector3 p = BoardGeometry.corner(coords, 1, edge)
                          .lerp(BoardGeometry.corner(coords, 1, edge + 1), step / (float) BoardRelief.EDGE_STEPS);
                    // Nudge inside each hex: both sampled faces must agree on either side of the shared edge.
                    Vector3 inside = new Vector3(p).lerp(center, .002f);
                    Vector3 outside = new Vector3(p).lerp(BoardGeometry.center(other, 1), .002f);
                    assertEquals(surface.height(inside.x, inside.y), neighbor.height(outside.x, outside.y), .05f,
                          "Joined landforms have no seam");
                    maximum = Math.max(maximum, surface.height(inside.x, inside.y));
                    minimum = Math.min(minimum, surface.height(inside.x, inside.y));
                }
            }
        }
        assertTrue(maximum - minimum > BoardRelief.metres(.3f), "Open ground has real, restrained relief");
        assertTrue(maximum - minimum < BoardGeometry.LEVEL * .6f, "Relief never reads as another level");
    }

    @Test
    void concreteStandsInFlatSlabsAndFromThreeLevelsOnBedrockUnderASlab() {
        float m = BoardRelief.metres(1);
        for (int level : new int[] { 2, 3 }) {
            BoardScene scene = scene(level, true, BoardScene.Surface.CONCRETE);
            BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
            Vector3 center = BoardGeometry.center(CENTER, level);
            float underside = (level - 1) * BoardGeometry.LEVEL;
            int slab = 0, rock = 0;
            for (BoardSurface.Face face : surface.walls(scene, BoardGeometry.floor(scene))) {
                for (Vector3 p : List.of(face.a(), face.b(), face.c())) {
                    // Distance beyond the nearest logical edge; the rounded corners are skipped.
                    float beyond = -Float.MAX_VALUE, along = 0;
                    for (int edge = 0; edge < 6; edge++) {
                        Vector3 a = BoardGeometry.corner(CENTER, level, edge);
                        Vector3 b = BoardGeometry.corner(CENTER, level, edge + 1);
                        float ex = b.x - a.x, ey = b.y - a.y, length = (float) Math.hypot(ex, ey);
                        float nx = ey / length, ny = -ex / length;
                        if (nx * (a.x - center.x) + ny * (a.y - center.y) < 0) {
                            nx = -nx;
                            ny = -ny;
                        }
                        float distance = (p.x - a.x) * nx + (p.y - a.y) * ny;
                        if (distance > beyond) {
                            beyond = distance;
                            along = ((p.x - a.x) * ex + (p.y - a.y) * ey) / (length * length);
                        }
                    }
                    if (along < .15f || along > .85f) { continue; }
                    if (level == 2 || p.z > underside - .005f * BoardGeometry.LEVEL) {
                        assertEquals(0, beyond, .01f * m, "Cast concrete faces are flat, at " + p);
                        slab++;
                    } else if (p.z > .45f * underside) {
                        assertTrue(beyond < -.45f * m, "The bedrock stands back under the slab, at " + p);
                        rock++;
                    }
                }
            }
            assertTrue(slab > 0 && (level == 2 || rock > 0), "Both parts were measured");
        }
    }

    @Test
    void treesOnPavingStandInPlantingPitsThatNeverTouchOrRaiseTheGround() {
        for (int density = 1; density <= 3; density++) {
            for (BoardScene.Surface family : List.of(BoardScene.Surface.CONCRETE, BoardScene.Surface.GRASS)) {
                Hex hex = new Hex(0);
                if (family == BoardScene.Surface.CONCRETE) { hex.addTerrain(new Terrain(Terrains.PAVEMENT, 1)); }
                hex.addTerrain(new Terrain(Terrains.WOODS, density));
                hex.addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
                List<BoardScene.Feature> trees = BoardFeatures.capture(hex, CENTER, Map.of());
                List<BoardScene.Tile> tiles = new ArrayList<>();
                for (int x = 0; x < 5; x++) {
                    for (int y = 0; y < 5; y++) {
                        Coords coords = new Coords(x, y);
                        tiles.add(new BoardScene.Tile(coords, 0, -1, false, 0, family, null, null, null, null, null,
                              coords.equals(CENTER) ? trees : List.of(), List.of(), BoardLiquid.NONE, null, true));
                    }
                }
                BoardScene scene = new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(CENTER));
                List<Vector3> pits = new ArrayList<>();
                for (BoardSurface.Face face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.DRESSING) { pits.addAll(List.of(face.a(), face.b(), face.c())); }
                }
                if (family == BoardScene.Surface.GRASS) {
                    assertTrue(pits.isEmpty(), "Only paved ground has planting pits");
                    continue;
                }
                float spacing = Float.POSITIVE_INFINITY;
                List<Vector3> trunks = new ArrayList<>();
                for (BoardScene.Feature tree : trees) {
                    Vector3 trunk = new Vector3(BoardGeometry.centerX(CENTER) + tree.x() * BoardGeometry.HEX_SCALE,
                          BoardGeometry.centerY(CENTER) + tree.y() * BoardGeometry.HEX_SCALE, 0);
                    for (Vector3 other : trunks) {
                        spacing = Math.min(spacing, Math.max(Math.abs(trunk.x - other.x), Math.abs(trunk.y - other.y)));
                    }
                    trunks.add(trunk);
                    assertTrue(pits.stream().anyMatch(p -> Math.hypot(p.x - trunk.x, p.y - trunk.y) < .01f),
                          "Every tree stands in its own pit");
                }
                for (Vector3 p : pits) {
                    float own = Float.POSITIVE_INFINITY;
                    for (Vector3 trunk : trunks) { own = Math.min(own, Math.max(Math.abs(p.x - trunk.x), Math.abs(p.y - trunk.y))); }
                    assertTrue(own < spacing / 2, "Neighbouring pits never touch");
                    assertTrue(p.z < BoardRelief.metres(.2f), "A pit is thin dressing on the slab");
                }
                Vector3 center = BoardGeometry.center(CENTER, 0);
                assertEquals(center.z, surface.height(center.x, center.y), .001f, "Pits never raise what stands there");
            }
        }
    }

    @Test
    void compressedElevationSettingsKeepSculptedGeometryAboveThePickingFloor() {
        BoardGeometry.Tuning previous = BoardGeometry.tuning();
        try {
            BoardGeometry.tune(new BoardGeometry.Tuning(1, previous.unitScale(), previous.unitHeightScale(), 1,
                  previous.gridShade(), previous.multiHexUnitScale()));
            BoardScene scene = scene(3, true, BoardScene.Surface.ROCK);
            float floor = BoardGeometry.floor(scene);
            for (BoardScene.Tile tile : scene.tiles()) {
                BoardSurface surface = new BoardSurface(scene, tile);
                List<BoardSurface.Face> faces = new ArrayList<>(surface.faces);
                faces.addAll(surface.walls(scene, floor));
                for (BoardSurface.Face face : faces) {
                    assertTrue(Math.min(face.a().z, Math.min(face.b().z, face.c().z)) >= floor - .001f,
                          "Sculpted geometry stays inside the board and ray bounds at minimum level height");
                }
            }
            assertNotNull(BoardGeometry.pick(scene, new com.badlogic.gdx.math.collision.Ray(
                  new Vector3(BoardGeometry.center(CENTER, 3)).add(0, 0, 100), new Vector3(0, 0, -1))));
        } finally { BoardGeometry.tune(previous); }
    }
}
