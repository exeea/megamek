/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.utils.MeshBuilder;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GpuWaterCoverageTest {
    @Test
    void verticalWaterbedWallsKeepTheirWaterMaterialAndBlendIntoTheBedAtTheirFoot() {
        var original = BoardRelief.tuning();
        try {
            BoardWetCliffTest.tune(true);
            BoardScene scene = GpuRiverTerrainSmokeTest.mapScene(BoardScene.Surface.SAND);
            int feet = 0, high = 0, rubble = 0, sheer = 0;
            for (Coords coords : List.of(new Coords(7, 14), new Coords(8, 14), new Coords(9, 13))) {
                var surface = new BoardSurface(scene, scene.tile(coords));
                for (var face : surface.faces) {
                    if (face.finish() == BoardSurface.Finish.OUTCROP
                          && Math.max(face.a().z, Math.max(face.b().z, face.c().z)) < BoardGeometry.waterZ(surface.tile)) {
                        rubble++;
                    }
                    if (!bedBoundary(surface, face)) { continue; }
                    MeshBuilder mesh = mesh();
                    GpuTerrain.coveredFace(mesh, surface, face, List.of(surface));
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    int uvOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.TextureCoordinates).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    mesh.getVertices(vertices, 0);
                    assertEquals(3, mesh.getNumVertices());
                    for (int i = 0; i < vertices.length; i += stride) {
                        assertTrue(List.of(face.a(), face.b(), face.c()).contains(point(vertices, i)),
                              "Shading must preserve the cliff's geometry");
                        Color color = new Color();
                        Color.abgr8888ToColor(color, vertices[i + colorOffset]);
                        assertTrue(color.b < .125f && color.a < .25f,
                              "Vertical pool boundaries must keep waterbed shading even on the exact shoreline");
                        assertTrue(vertices[i + uvOffset] >= 0);
                        if (vertices[i + uvOffset] == 0) { feet++; }
                        if (vertices[i + uvOffset] > 2) { high++; }
                    }
                    Vector3 normal = new Vector3(face.b()).sub(face.a()).crs(new Vector3(face.c()).sub(face.a())).nor();
                    if (Math.abs(normal.z) < .15f) { sheer++; }
                }
            }
            assertTrue(feet > 0 && high > 0, "The wall supplies a continuous height above its bed for sediment blending");
            assertTrue(rubble > 0, "Fallen rock details should continue below the waterline");
            assertTrue(sheer > 0, "The scene must exercise vertical cliff faces");
        } finally {
            BoardRelief.tune(original);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void clippingPreservesTheWallAndTintsOnlyRockCoveredByTheStream(boolean cliffs) {
        var original = BoardRelief.tuning();
        try {
            BoardWetCliffTest.tune(cliffs);
            BoardScene scene = GpuRiverTerrainSmokeTest.mapScene(BoardScene.Surface.SAND);
            BoardSurface water = new BoardSurface(scene, scene.tile(new Coords(9, 9)));
            BoardSurface upper = new BoardSurface(scene, scene.tile(new Coords(8, 9)));
            BoardSurface land = new BoardSurface(scene, scene.tile(new Coords(8, 10)));
            List<BoardSurface> waters = List.of(water, upper);
            int wetCount = 0, dryCount = 0;
            for (BoardSurface surface : List.of(land, water)) {
                List<BoardSurface.Face> faces = new ArrayList<>(surface.walls(scene, -100));
                faces.addAll(surface.faces);
                for (BoardSurface.Face face : faces) {
                    if ((surface == land && face.landEdge() != 0)
                          || face.finish() == BoardSurface.Finish.BED) { continue; }
                    MeshBuilder mesh = mesh();
                    GpuTerrain.coveredFace(mesh, surface, face, waters);
                    int stride = mesh.getAttributes().vertexSize / Float.BYTES;
                    int colorOffset = mesh.getAttributes().findByUsage(VertexAttributes.Usage.ColorPacked).offset / Float.BYTES;
                    float[] vertices = new float[mesh.getNumVertices() * stride];
                    short[] indices = new short[mesh.getNumIndices()];
                    mesh.getVertices(vertices, 0);
                    mesh.getIndices(indices, 0);
                    float area = 0, tintedOutside = 0, dryUnderwater = 0;
                    for (int i = 0; i < indices.length; i += 3) {
                        int a = Short.toUnsignedInt(indices[i]) * stride;
                        int b = Short.toUnsignedInt(indices[i + 1]) * stride;
                        int c = Short.toUnsignedInt(indices[i + 2]) * stride;
                        Vector3 p = point(vertices, a), q = point(vertices, b), r = point(vertices, c);
                        float pieceArea = area(p, q, r);
                        area += pieceArea;
                        // Coincident cuts can leave rounding slivers with unstable centroids; their area still counts.
                        if (pieceArea < .001f) { continue; }
                        Vector3 middle = new Vector3(p).add(q).add(r).scl(1f / 3);
                        Color color = new Color();
                        Color.abgr8888ToColor(color, vertices[a + colorOffset]);
                        boolean wet = color.b >= .875f && color.b < .999f || color.b < .125f && color.a < .25f;
                        float height = Float.NEGATIVE_INFINITY;
                        for (BoardSurface body : waters) {
                            for (BoardSurface.Face top : body.waterFaces) {
                                // The support sampler deliberately accepts points just outside an edge. Shading
                                // coverage needs the actual footprint, without that picking tolerance.
                                if (Intersector.isPointInTriangle(middle.x, middle.y, top.a().x, top.a().y,
                                      top.b().x, top.b().y, top.c().x, top.c().y)) {
                                    height = Math.max(height, top.height(middle.x, middle.y));
                                }
                            }
                        }
                        if (wet) {
                            wetCount++;
                            // The pool's vertical boundary is shaded by height, including undercuts behind the outline.
                            if (!bedBoundary(surface, face) && (!Float.isFinite(height) || height < middle.z - .003f)) {
                                tintedOutside += pieceArea;
                            }
                        } else {
                            dryCount++;
                            if (height > middle.z + .003f) { dryUnderwater += pieceArea; }
                        }
                    }
                    float expected = area(face.a(), face.b(), face.c());
                    assertEquals(expected, area, Math.max(.003f, expected * .0005f),
                          "Clipping must preserve the cliff without missing or overlapping polygons");
                    // World-coordinate float rounding leaves subpixel slivers at coincident boundaries.
                    float tolerance = .005f + expected * .0005f;
                    assertTrue(tintedOutside < tolerance, "Tinted area outside the stream: " + tintedOutside);
                    assertTrue(dryUnderwater < tolerance, "Untinted area below the stream: " + dryUnderwater);
                }
            }
            assertTrue(wetCount > 0 && dryCount > 0);
            if (cliffs) {
                assertFalse(water.cliffWater.isEmpty());
                var pools = new GpuWaterShader.Pools(scene, Map.of(), Map.of(water.tile.coords(), water));
                for (var face : water.cliffWater) {
                    Vector3 point = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    assertNotNull(pools.sample(point.x, point.y, new float[4]),
                          "The flow and depth field must cover the surface inside cliff recesses");
                }
            }
        } finally {
            BoardRelief.tune(original);
        }
    }

    private static boolean bedBoundary(BoardSurface surface, BoardSurface.Face face) {
        return List.of(face.a(), face.b(), face.c()).stream().allMatch(p -> {
            var shade = surface.relief.shade(p);
            return shade != null && shade.kind() == BoardRelief.Kind.SUBMERGED_CLIFF;
        });
    }

    private static MeshBuilder mesh() {
        var mesh = new MeshBuilder();
        mesh.begin(VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal
              | VertexAttributes.Usage.TextureCoordinates | VertexAttributes.Usage.ColorPacked, GL20.GL_TRIANGLES);
        return mesh;
    }

    private static Vector3 point(float[] vertices, int at) {
        return new Vector3(vertices[at], vertices[at + 1], vertices[at + 2]);
    }

    private static float area(Vector3 a, Vector3 b, Vector3 c) {
        return new Vector3(b).sub(a).crs(new Vector3(c).sub(a)).len() * .5f;
    }
}
