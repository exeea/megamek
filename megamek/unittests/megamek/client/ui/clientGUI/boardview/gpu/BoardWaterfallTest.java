/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardWaterfallTest {
    @Test
    void rockBehindTheCurtainBlocksRaysFromObliqueAngles() {
        var original = BoardRelief.tuning();
        try {
            for (float width : new float[] { .05f, .5f, 1 }) {
                GpuRiverTerrainSmokeTest.setWidth(width);
                for (int direction = 0; direction < 6; direction++) {
                    BoardScene scene = scene(direction, 1);
                    BoardSurface upper = new BoardSurface(scene, scene.tile(new Coords(3, 3)));
                    for (var face : upper.waterFaces) {
                        // Collinear mouth samples can leave subpixel ears through float rounding.
                        assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z
                                    >= -.001f * BoardGeometry.HEX_SCALE * BoardGeometry.HEX_SCALE,
                              "The upper pool must not fold over where its lip meets a bank: width " + width + ", direction " + direction);
                    }
                    var fall = upper.waterfalls.getFirst();
                    var crest = upper.crest(fall);
                    List<BoardSurface.Face> solid = new ArrayList<>(upper.walls(scene, BoardGeometry.floor(scene)));
                    solid.addAll(upper.faces.stream().filter(f -> f.finish() != BoardSurface.Finish.OUTCROP).toList());
                    // Avoid casting precisely along a triangle edge, where float ray tests can miss both faces.
                    for (int column = 1; column < 23; column++) {
                        float s = column / 23f;
                        for (int row = 1; row < 13; row++) {
                            Vector3 p = crest.point(s);
                            p.z = fall.lowA() + (p.z - fall.lowA()) * row / 13;
                            for (float angle : new float[] { -.7f, 0, .7f }) {
                                Vector3 outward = crest.normal(s).mulAdd(crest.tangent(s), angle).nor();
                                float reach = BoardGeometry.WIDTH * .4f;
                                Ray ray = new Ray(new Vector3(p).mulAdd(outward, reach), new Vector3(outward).scl(-1));
                                Vector3 hit = new Vector3();
                                assertTrue(solid.stream().anyMatch(f -> Intersector.intersectRayTriangle(ray,
                                            f.a(), f.b(), f.c(), hit) && hit.dst(ray.origin) < 2 * reach
                                            && new Vector3(f.b()).sub(f.a()).crs(new Vector3(f.c()).sub(f.a()))
                                                  .dot(ray.direction) < 0),
                                      "Open cliff: width " + width + ", direction " + direction + ", at " + p + ", angle " + angle);
                            }
                        }
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void waterRocksHaveAFoundationInTheDrawnGround() {
        var original = BoardRelief.tuning();
        try {
            GpuRiverTerrainSmokeTest.setWidth(.5f);
            for (int depth : new int[] { 0, 1, 3 }) {
                BoardScene scene = scene(3, depth);
                for (Coords coords : List.of(new Coords(3, 3), new Coords(3, 4))) {
                    BoardSurface surface = new BoardSurface(scene, scene.tile(coords));
                    List<BoardSurface.Face> ground = surface.faces.stream()
                          .filter(f -> f.finish() != BoardSurface.Finish.OUTCROP && f.finish() != BoardSurface.Finish.DRESSING).toList();
                    // Every placed rock carries its own deterministic tint on all its faces.
                    Map<Float, List<Vector3>> rocks = new HashMap<>();
                    for (var face : surface.faces) {
                        if (face.finish() == BoardSurface.Finish.OUTCROP) {
                            rocks.computeIfAbsent(surface.relief.shade(face.a()).tint(), key -> new ArrayList<>())
                                  .addAll(List.of(face.a(), face.b(), face.c()));
                        }
                    }
                    assertTrue(!rocks.isEmpty(), "The fixture includes rocks at the lip and in its receiving pool");
                    for (var rock : rocks.values()) {
                        assertTrue(rock.stream().anyMatch(p -> p.z < BoardSurface.sampleHeight(ground, p.x, p.y, Float.NaN)),
                              "A rock must enter the terrain, including the depth-" + depth + " bed at " + coords);
                    }
                }
            }
        } finally { BoardRelief.tune(original); }
    }

    @Test
    void terrainSlopeIncludesWaterDepthWhileSurfaceHeightStaysTheSame() {
        BoardSculptTest.withTransitions(true, () -> {
            Coords water = new Coords(3, 3), land = water.translated(0);
            for (int depth : new int[] { 1, 2, 3 }) {
                BoardScene scene = scene(3, depth);
                List<BoardScene.Tile> tiles = new ArrayList<>(scene.tiles());
                tiles.replaceAll(t -> t.coords().equals(water) ? tile(water, 0, depth)
                      : t.coords().equals(land) ? tile(land, 1, -1) : t);
                scene = new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
                BoardSurface surface = new BoardSurface(scene, scene.tile(water));
                assertEquals(depth == 1, surface.relief.slope(1), "Land is one surface level above water; bed depth adds to its drop");
                Vector3 center = BoardGeometry.center(water, 0);
                assertEquals(-depth * BoardGeometry.LEVEL, surface.height(center.x, center.y), .001f);
                assertEquals(BoardGeometry.waterZ(scene.tile(water)), surface.waterHeight(center.x, center.y), .001f);
            }
        });
    }

    private static BoardScene scene(int direction, int depth) {
        Coords high = new Coords(3, 3), low = high.translated(direction);
        Vector3 a = BoardGeometry.center(high, 0), b = BoardGeometry.center(low, 0);
        Vector3 middle = new Vector3(a).lerp(b, .5f), toward = new Vector3(b).sub(a);
        List<Coords> river = List.of(high.translated((direction + 3) % 6), high, low, low.translated(direction));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords coords = new Coords(x, y);
                int level = new Vector3(BoardGeometry.center(coords, 0)).sub(middle).dot(toward) < 0 ? 3 : 0;
                tiles.add(tile(coords, level, river.contains(coords) ? depth : -1));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Tile tile(Coords coords, int level, int depth) {
        return new BoardScene.Tile(coords, level, depth, false, 0, BoardScene.Surface.SAND, null,
              null, null, null, null, List.of(), List.of(), depth >= 0 ? BoardLiquid.WATER : BoardLiquid.NONE, null, true);
    }
}
