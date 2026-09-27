/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.List;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GpuSurfaceBlendTest {
    @ParameterizedTest
    @EnumSource(TerrainLod.class)
    void steepContactsKeepTheirMaterialsAndSurfaceAreaAtEveryLod(TerrainLod lod) {
        var center = BoardSurfaceBlendTest.CENTER;
        var neighbour = center.translated(BoardGeometry.edgeDirection(0));
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              c.equals(neighbour) ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS,
              c.equals(center) || c.equals(neighbour) ? 4 : 0, -1, 0));
        var tile = scene.tile(center);
        var surface = new BoardSurface(scene, tile, lod);
        var faces = surface.walls(scene, -BoardGeometry.level()).stream()
              .filter(f -> surface.relief.shade(f.a()) != null
                    && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.CLIFF).toList();
        var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> {
            var shade = surface.relief.shade(p);
            return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                  .setUV(shade.rim(), shade.foot()).setCol(1, shade.level(), .5f, shade.tint());
        }, BoardRelief.metres(lod == TerrainLod.DISTANT ? 8 : lod == TerrainLod.COARSE ? 4 : 2));
        var triangles = groups.values().stream().flatMap(List::stream).toList();
        double before = faces.stream().mapToDouble(f -> area(f.a(), f.b(), f.c())).sum();
        double after = triangles.stream().mapToDouble(t -> area(t.a().vertex().position,
              t.b().vertex().position, t.c().vertex().position)).sum();
        assertEquals(before, after, before * .00001, "Material sampling cannot change the LOD silhouette");
        assertTrue(triangles.stream().flatMap(t -> List.of(t.a(), t.b(), t.c()).stream()).anyMatch(p ->
              p.vertex().normal.z < .6f && p.cover().sand() > .1f && p.cover().grass() > .1f),
              "Steep rock faces need both materials in " + lod);
    }

    @Test
    void coincidentCliffAndGroundVerticesKeepTheirDistinctShadingRoles() {
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              c.getX() < 4 ? BoardScene.Surface.SAND : BoardScene.Surface.GRASS, 0, -1, 0));
        var tile = scene.tile(BoardSurfaceBlendTest.CENTER);
        var a = BoardGeometry.center(tile.coords(), 0);
        var b = BoardGeometry.corner(tile.coords(), 0, 0);
        var c = BoardGeometry.corner(tile.coords(), 0, 1);
        var roles = new IdentityHashMap<Vector3, Float>();
        var ground = new BoardSurface.Face(a, b, c, BoardSurface.Finish.TOP);
        var cliff = new BoardSurface.Face(new Vector3(a), new Vector3(b), new Vector3(c), BoardSurface.Finish.CAP);
        for (var p : List.of(ground.a(), ground.b(), ground.c())) { roles.put(p, 0f); }
        for (var p : List.of(cliff.a(), cliff.b(), cliff.c())) { roles.put(p, .5f); }
        var groups = GpuSurfaceBlend.prepare(scene, tile, List.of(ground, cliff), p -> new MeshPartBuilder.VertexInfo()
              .setPos(p).setNor(Vector3.Z).setUV(0, 0).setCol(1, 0, roles.get(p), 1));
        var triangles = groups.values().stream().flatMap(List::stream).toList();
        assertTrue(triangles.stream().anyMatch(t -> t.a().vertex().color.b == 0));
        assertTrue(triangles.stream().anyMatch(t -> t.a().vertex().color.b == .5f));
        for (var t : triangles) {
            assertEquals(t.a().vertex().color.b, t.b().vertex().color.b);
            assertEquals(t.a().vertex().color.b, t.c().vertex().color.b);
        }
    }

    @Test
    void crowdedCliffsKeepEveryContributingFamilyInTheirPalettes() {
        var families = List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW,
              BoardScene.Surface.DIRT, BoardScene.Surface.ROCK);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              families.get(Math.floorMod(c.getX() + 2 * c.getY(), families.size())), c.getY() < 4 ? 4 : 0, -1, 0));
        for (int x = 2; x <= 6; x++) {
            var tile = scene.tile(new Coords(x, 3));
            var surface = new BoardSurface(scene, tile);
            var faces = surface.walls(scene, -BoardGeometry.level()).stream()
                  .filter(f -> surface.relief.shade(f.a()) != null
                        && surface.relief.shade(f.a()).kind() == BoardRelief.Kind.CLIFF).toList();
            var groups = GpuSurfaceBlend.prepare(scene, tile, faces, p -> {
                var shade = surface.relief.shade(p);
                return new MeshPartBuilder.VertexInfo().setPos(p).setNor(shade.normal())
                      .setUV(shade.rim(), shade.foot()).setCol(1, shade.level(), .5f, shade.tint());
            });
            assertTrue(!groups.isEmpty());
            for (var group : groups.entrySet()) {
                var palette = group.getKey();
                for (var triangle : group.getValue()) {
                    for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        float represented = point.cover().weight(palette.base());
                        if (palette.first() != palette.base()) { represented += point.cover().weight(palette.first()); }
                        if (palette.second() != palette.base()) { represented += point.cover().weight(palette.second()); }
                        assertEquals(1, represented, .00001f, "Cliff junctions must not drop a material");
                    }
                }
            }
        }
    }

    @Test
    void crowdedBoundariesPreserveAreaHeightAndEveryContributingMaterial() {
        var families = List.of(BoardScene.Surface.GRASS, BoardScene.Surface.SAND, BoardScene.Surface.SNOW,
              BoardScene.Surface.DIRT, BoardScene.Surface.ROCK);
        var scene = BoardSurfaceBlendTest.scene(c -> BoardSurfaceBlendTest.tile(c,
              families.get(Math.floorMod(c.getX() + 2 * c.getY(), families.size())), 0, -1, 0));
        int original = 0, refined = 0;
        for (int direction = -1; direction < 6; direction++) {
            var coords = direction < 0 ? BoardSurfaceBlendTest.CENTER : BoardSurfaceBlendTest.CENTER.translated(direction);
            var tile = scene.tile(coords);
            var surface = new BoardSurface(scene, tile);
            var faces = surface.faces.stream().filter(f -> f.finish() == BoardSurface.Finish.TOP).toList();
            var palettes = GpuSurfaceBlend.prepare(scene, tile, faces, p -> new MeshPartBuilder.VertexInfo()
                  .setPos(p).setNor(Vector3.Z).setCol(Color.WHITE).setUV(0, 0));
            double before = faces.stream().mapToDouble(f -> area(f.a(), f.b(), f.c())).sum();
            double after = 0;
            original += faces.size();
            for (var group : palettes.entrySet()) {
                var palette = group.getKey();
                for (var triangle : group.getValue()) {
                    refined++;
                    after += area(triangle.a().vertex().position, triangle.b().vertex().position, triangle.c().vertex().position);
                    for (var point : List.of(triangle.a(), triangle.b(), triangle.c())) {
                        var p = point.vertex().position;
                        assertTrue(faces.stream().anyMatch(f -> Math.abs(f.height(p.x, p.y) - p.z) < .01f),
                              "Refinement must stay on an original ground triangle, including below outcrops");
                        float represented = point.cover().weight(palette.base());
                        if (palette.first() != palette.base()) { represented += point.cover().weight(palette.first()); }
                        if (palette.second() != palette.base()) { represented += point.cover().weight(palette.second()); }
                        assertEquals(1, represented, .00001f, "No contributing material may be dropped at a junction");
                    }
                }
            }
            assertEquals(before, after, before * .00001, "Subdivision must not open gaps or overlap faces");
        }
        assertTrue(refined > original);
        assertTrue(refined < 20_000, "Seven crowded hexes must remain a bounded amount of boundary work: " + refined);
        System.out.println("Surface blending, seven crowded hexes: " + original + " -> " + refined + " triangles");
    }

    private static double area(Vector3 a, Vector3 b, Vector3 c) {
        return new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len() * .5;
    }
}
