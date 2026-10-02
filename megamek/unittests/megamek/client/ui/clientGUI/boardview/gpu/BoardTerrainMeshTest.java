/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;

class BoardTerrainMeshTest {
    private static final Coords AT = new Coords(3, 3);

    @Test
    void meteorRimBouldersDoNotStretchDownTheCliff() {
        var scene = BoardCliffSeamTest.scene(new File("data/boards/unofficial/Unknown/meteor.board"));
        var tile = scene.tile(new Coords(11, 11));
        var surface = new BoardSurface(scene, tile);
        float height = (float) tile.features().stream().filter(f -> f.kind() == BoardScene.FeatureKind.BOULDER)
              .mapToDouble(BoardScene.Feature::height).max().orElseThrow() * BoardGeometry.level();
        assertTrue(surface.rough.size() > 20, "Rough still places rocks on hex 1212");
        for (var face : surface.rough) {
            var shade = surface.relief.shade(face.a());
            float extent = (shade.rim() + shade.foot()) * BoardRelief.metres(1);
            assertTrue(extent <= height * 2.2f, "A rim boulder must not become a cliff-height pillar: " + extent);
        }
    }

    @Test
    void topsKeepTheirFootprintAndExactLevel() {
        for (var family : BoardScene.Surface.values()) {
            for (int level : new int[] { 0, 2, 6 }) {
                var scene = scene(family, level);
                var surface = new BoardSurface(scene, scene.tile(AT));
                var top = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
                Vector3 center = BoardGeometry.center(AT, level);
                assertEquals(center.z, surface.height(center.x, center.y), .001f);
                float error = 0;
                double area = 0;
                for (var face : top) {
                    float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                    assertTrue(cross > 0, "Tops must not fold or overlap");
                    area += cross * .5;
                    for (int a = 0; a <= 5; a++) {
                        for (int b = 0; b <= 5 - a; b++) {
                            Vector3 p = new Vector3(face.a()).scl(a / 5f).mulAdd(face.b(), b / 5f)
                                  .mulAdd(face.c(), (5 - a - b) / 5f);
                            error = Math.max(error, Math.abs(p.z - surface.relief.groundHeight(p.x, p.y)));
                        }
                    }
                }
                if (level == 0) {
                    assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05);
                }
                assertEquals(0, error, .001f, "Every top stays flat at its level: " + family);
                if (level == 0) { assertEquals(6, top.size(), "An open flat top needs six triangles"); }
                else { assertTrue(top.size() <= 120, "Only the cliff outline needs extra triangles: " + top.size()); }
                int walls = surface.walls(scene, BoardGeometry.floor(scene)).size();
                assertTrue(walls <= (level <= 2 ? 900 : 5400), "Avoid dense slope/cliff grids: " + walls);
                System.out.printf("MESH %s level=%d top=%d walls=%d errorMetres=%.4f%n", family, level, top.size(),
                      walls, error / BoardRelief.metres(1));
            }
        }
    }

    @Test
    void flatTopsUseSixTrianglesAtEveryDetailIncludingBoardEdges() {
        for (var family : BoardScene.Surface.values()) {
            var scene = scene(family, 0);
            for (var detail : TerrainLod.values()) {
                for (var at : List.of(AT, new Coords(0, 0), new Coords(6, 3))) {
                    var surface = new BoardSurface(scene, scene.tile(at), detail);
                    var top = surface.groundFaces().stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
                    assertEquals(6, top.size(), family + " " + detail + " " + at);
                    double area = 0;
                    for (var face : top) {
                        for (var p : List.of(face.a(), face.b(), face.c())) { assertEquals(0, p.z, .0001f); }
                        float cross = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z;
                        assertTrue(cross > 0, "No inverted triangles");
                        area += cross * .5;
                    }
                    assertEquals(BoardGeometry.width() * BoardGeometry.height() * .75, area, .05,
                          "The entire hex remains covered");
                }
            }
        }
    }

    @Test
    void shoreBasinsKeepSparseUpwardGeometry() {
        for (boolean mouths : new boolean[] { false, true }) {
            var scene = BoardTerrainDetailTest.shores(1, mouths);
            var surface = new BoardSurface(scene, scene.tile(BoardTerrainDetailTest.WATER));
            for (var finish : List.of(BoardSurface.Finish.TOP, BoardSurface.Finish.BED)) {
                var faces = surface.faces.stream().filter(f -> f.finish() == finish).toList();
                assertTrue(!faces.isEmpty() && faces.size() <= (finish == BoardSurface.Finish.TOP ? 250 : 360),
                      "Keep the curved bank outline without unnecessary interior rings: " + finish + " " + faces.size());
                for (var face : faces) {
                    assertTrue(new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).z >= -.001f,
                          "Shore and bed triangles must not fold (vertical shore ends are allowed): " + face);
                }
                System.out.printf("MESH shore mouths=%s %s=%d%n", mouths, finish, faces.size());
            }
        }
    }

    private static BoardScene scene(BoardScene.Surface family, int level) {
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Coords at = new Coords(x, y);
                tiles.add(new BoardScene.Tile(at, at.equals(AT) ? level : 0, -1, false, 0, family,
                      null, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
