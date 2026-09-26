/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardGeometryPickingTest {
    @Test
    void finishedTerrainKeepsBuilderPickingForWaterIceCliffsAndTalus() {
        BoardGeometry.Tuning original = BoardGeometry.tuning();
        BoardRelief.Tuning originalRelief = BoardRelief.tuning();
        try {
            BoardRelief.tune(BoardRelief.DEFAULTS);
            for (BoardGeometry.Tuning tuning : List.of(BoardGeometry.DEFAULTS,
                  new BoardGeometry.Tuning(1, 1, 1, 18, .8f, 1, false, 2),
                  new BoardGeometry.Tuning(1, 1, 1, 18, .8f, 1, false, 0))) {
                BoardGeometry.tune(tuning);
                compareFinishedTerrain();
            }
        } finally {
            BoardGeometry.tune(original);
            BoardRelief.tune(originalRelief);
        }
    }

    private static void compareFinishedTerrain() {
        BoardScene scene = scene();
        float floor = BoardGeometry.floor(scene);
        Map<Coords, BoardSurface> builders = new HashMap<>();
        for (BoardScene.Tile tile : scene.tiles()) { builders.put(tile.coords(), new BoardSurface(scene, tile)); }
        Map<Coords, BoardTacticalGeometry.Surface> finished = new HashMap<>();
        Map<BoardSurface.Finish, BoardSurface.Face> kinds = new EnumMap<>(BoardSurface.Finish.class);
        List<Ray> rays = new ArrayList<>();
        int water = 0, falls = 0, slopes = 0;
        for (BoardScene.Tile tile : scene.tiles()) {
            BoardSurface builder = builders.get(tile.coords());
            // This is how the render build finishes walls with already-built neighboring surfaces.
            builder.walls(scene, floor, builders);
            BoardTacticalGeometry.Surface surface = BoardTacticalGeometry.Surface.of(builder, scene, floor);
            finished.put(tile.coords(), surface);
            assertBorrowed(builder.faces, surface.faces());
            assertBorrowed(builder.waterFaces, surface.water());
            assertBorrowed(builder.walls(scene, floor), surface.walls());
            assertBorrowed(builder.waterfalls, surface.waterfalls());
            for (BoardSurface.Face face : surface.faces()) { kinds.putIfAbsent(face.finish(), face); }
            for (BoardSurface.Face face : surface.walls()) { kinds.putIfAbsent(face.finish(), face); }
            water += surface.water().size();
            falls += surface.waterfalls().size();
            slopes += surface.slopes().size();
            if (!surface.water().isEmpty()) { probe(rays, surface.water().getFirst()); }
            if (!surface.walls().isEmpty()) { probe(rays, surface.walls().getFirst()); }
            for (BoardSurface.Side side : surface.waterfalls()) {
                probe(rays, new BoardSurface.Face(side.a(), new Vector3(side.a().x, side.a().y, side.lowA()),
                      new Vector3(side.b().x, side.b().y, side.lowB()), BoardSurface.Finish.WALL));
            }
            Vector3 center = BoardGeometry.center(tile.coords(), tile.elevation());
            // Both overhead and oblique camera rays, including exact shared corners and edges.
            for (int edge = 0; edge < 6; edge++) {
                Vector3 point = BoardGeometry.corner(tile.coords(), tile.elevation(), edge);
                Vector3 next = BoardGeometry.corner(tile.coords(), tile.elevation(), edge + 1);
                Vector3 direction = new Vector3(center).sub(point).add(0, 0, -BoardGeometry.HEIGHT).nor();
                rays.add(new Ray(new Vector3(center).mulAdd(direction, -400), direction));
                rays.add(new Ray(new Vector3(point).add(0, 0, 400), new Vector3(0, 0, -1)));
                rays.add(new Ray(point.lerp(next, .5f).add(0, 0, 400), new Vector3(0, 0, -1)));
            }
            if (tile.water() && !tile.frozen()) {
                // A ray starting below the liquid surface must still see the recessed bed.
                center.z = BoardGeometry.waterZ(tile) - 2;
                rays.add(new Ray(center, new Vector3(0, 0, -1)));
            }
        }
        assertTrue(kinds.containsKey(BoardSurface.Finish.BED), "The fixture includes submerged riverbeds");
        assertTrue(kinds.containsKey(BoardSurface.Finish.ICE), "The fixture includes frozen water");
        assertTrue(kinds.containsKey(BoardSurface.Finish.WALL), "The fixture includes exposed cliffs");
        assertTrue(water > 0 && falls > 0, "The fixture includes liquid surfaces and waterfalls");
        if (BoardGeometry.tuning().stepsBetweenTops()) { assertTrue(slopes > 0, "The fixture includes talus/slopes"); }
        for (BoardSurface.Face face : kinds.values()) { probe(rays, face); }
        builders.clear();

        BoardSurface.Cache cache = new BoardSurface.Cache();
        int hits = 0;
        for (Ray ray : rays) {
            BoardGeometry.Hit expected = BoardGeometry.hit(scene, ray, scene.tiles(), floor, cache);
            BoardGeometry.Hit actual = BoardGeometry.hit(scene, ray, scene.tiles(), floor, finished::get);
            assertEquals(expected, actual, () -> "Picking order, distance and owner must match at " + ray);
            if (actual != null) { hits++; }
        }
        assertTrue(hits > scene.tiles().size() * 12, "The comparison must exercise hits, not just empty rays");
        Ray outside = new Ray(new Vector3(-1000, 1000, 400), new Vector3(0, 0, -1));
        assertEquals(BoardGeometry.hit(scene, outside, scene.tiles(), floor, cache),
              BoardGeometry.hit(scene, outside, scene.tiles(), floor, finished::get));
    }

    private static <T> void assertBorrowed(List<T> source, List<T> retained) {
        assertEquals(source.size(), retained.size());
        for (int i = 0; i < source.size(); i++) { assertSame(source.get(i), retained.get(i)); }
    }

    private static void probe(List<Ray> rays, BoardSurface.Face face) {
        Vector3 center = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1 / 3f);
        Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
        if (normal.isZero()) { return; }
        for (int sign : new int[] { -1, 1 }) {
            rays.add(new Ray(new Vector3(center).mulAdd(normal, sign * 2), new Vector3(normal).scl(-sign)));
        }
    }

    private static BoardScene scene() {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                boolean ice = x == 1 && y == 1;
                boolean water = ice || x == 3 && y >= 1 && y <= 5;
                int level = y < 3 ? 3 : x == 5 && y == 5 ? 1 : 0;
                tiles.add(new BoardScene.Tile(new Coords(x, y), level, water ? 2 : -1, ice, 0,
                      BoardScene.Surface.SAND, null, null, null, null, null, List.of(), List.of(),
                      water ? BoardLiquid.WATER : BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
