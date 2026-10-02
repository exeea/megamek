/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTunnelTest {
    @BeforeAll
    static void natives() { GdxNativesLoader.load(); }

    @ParameterizedTest
    @ValueSource(strings = { BoardTunnel.ASSET, BoardTunnel.BRIDGE_ASSET })
    void lowPolyPortalCarriesAFullWidthRoadInsideWithoutADarkStripAtTheMouth(String asset) {
        File root = new File(Configuration.dataDir(), "models/board");
        var model = RigidGlb.loadLods(new FileHandle(new File(root, asset + ".glb")), root.toPath()).getFirst();
        int floorTriangles = 0, total = 0;
        float start = Float.POSITIVE_INFINITY, end = Float.NEGATIVE_INFINITY;
        for (var mesh : model.meshes) {
            for (var part : mesh.parts) {
                total += part.indices.length / 3;
                if (!part.id.contains("tunnel-floor")) { continue; }
                floorTriangles += part.indices.length / 3;
                for (short index : part.indices) {
                    int i = Short.toUnsignedInt(index) * RigidGlb.STRIDE;
                    float x = mesh.vertices[i], y = mesh.vertices[i + 1], z = mesh.vertices[i + 2];
                    assertEquals(BoardRoad.Kind.PAVED.halfWidth, Math.abs(x), .001f, "The road must not narrow inside");
                    assertEquals(y < 3 ? -.02f : 0, z, .001f, "Only the hidden underlap is recessed below the approach");
                    if (y <= 6) { assertEquals(1, mesh.vertices[i + 6], .001f, "No dark threshold at the mouth"); }
                    start = Math.min(start, y);
                    end = Math.max(end, y);
                }
            }
        }
        assertEquals(8, floorTriangles, "Only four flat quads are needed for the interior light fade");
        assertEquals(2.75f, start, .001f, "A quarter-unit underlap seals the lattice's slightly oblique hex edges");
        assertEquals(BoardTunnel.DEPTH, end, .001f);
        assertTrue(total < 300, "A single inexpensive LOD0 is sufficient");
        boolean wings = false;
        for (var material : model.materials) { wings |= material.id.equals("tunnel-wings"); }
        assertEquals(asset.equals(BoardTunnel.ASSET), wings);
    }

    @Test
    void lavaTubesNaturalBridgesDoNotReceiveRoadPortals() throws Exception {
        var scene = GpuRoadSourceTest.scene("Map Pack Volcanic/16x17 Lava Tubes 1.board");
        int bridges = 0;
        for (var tile : scene.tiles()) {
            assertTrue(BoardTunnel.entrances(scene, tile).isEmpty(), tile.coords().getBoardNum());
            if (BoardBridge.feature(tile) != null) {
                assertTrue(BoardBridge.deck(scene, tile).natural());
                bridges++;
            }
        }
        assertEquals(4, bridges);
    }

    @ParameterizedTest
    @EnumSource(BoardScene.Surface.class)
    void groundAndBridgePortalsOpenNativeWallsInEveryTerrainFamily(BoardScene.Surface family) {
        var at = BoardRoadTest.CENTER;
        for (boolean bridge : new boolean[] { false, true }) {
            for (int direction = 0; direction < 6; direction++) {
                int d = direction;
                var scene = BoardSurfaceBlendTest.scene(c -> {
                    boolean approach = bridge && c.equals(at.translated((d + 3) % 6));
                    boolean road = approach || (c.equals(at) && !bridge);
                    var tile = BoardRoadTest.tile(c, road ? BoardRoad.Kind.GRAVEL : BoardRoad.Kind.NONE,
                          road ? 1 << d : 0,
                          c.equals(at.translated(d)) ? bridge ? 2 : 4 : bridge && !approach ? -2 : 0, family);
                    return bridge && c.equals(at) ? bridge(tile, (1 << d) | (1 << ((d + 3) % 6))) : tile;
                });
                var entrances = BoardTunnel.entrances(scene, scene.tile(at));
                assertEquals(1, entrances.size(), family + " direction " + d + " bridge=" + bridge);
                var tunnel = entrances.getFirst();
                assertEquals(bridge ? BoardTunnel.BRIDGE_ASSET : BoardTunnel.ASSET, tunnel.asset());
                assertEquals(BoardRoad.Kind.GRAVEL, tunnel.kind());
                assertEquals(GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale(), tunnel.origin().z, .0001f);
                var upper = new BoardSurface(scene, scene.tile(at.translated(d)));
                var walls = upper.walls(scene, BoardGeometry.floor(scene));
                float scale = BoardGeometry.hexScale();
                for (float across : new float[] { -7, 0, 7 }) {
                    Vector3 eye = new Vector3(across, -5, 4).mul(tunnel.transform());
                    assertFalse(hit(walls, new Ray(eye, tunnel.along()), 22 * scale),
                          "Cliff still blocks the carriageway: " + family + " exit " + d + " bridge=" + bridge);
                }
                Vector3 above = new Vector3(0, -5, 25).mul(tunnel.transform());
                Ray roof = new Ray(above, tunnel.along());
                assertTrue(hit(walls, roof, BoardGeometry.width()) || hit(upper.groundFaces(), roof, BoardGeometry.width()),
                      "Natural cliff/cap above the arch remains: " + family + " exit " + d + " bridge=" + bridge);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { -4, -2, -1, 0, 1 })
    void bridgeExitsIntoOpenAirOrGroundWithoutHeadroomDoNotAcquirePortals(int rise) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> {
            boolean approach = c.equals(at.translated(3));
            var tile = BoardRoadTest.tile(c, approach ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, approach ? 1 : 0,
                  c.equals(at.translated(0)) ? rise : approach ? 0 : -2,
                  BoardScene.Surface.ROCK);
            return c.equals(at) ? bridge(tile, 9) : tile;
        });
        assertFalse(BoardBridge.deck(scene, scene.tile(at)).natural());
        assertTrue(BoardTunnel.entrances(scene, scene.tile(at)).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = BoardRoad.Kind.class, names = { "PAVED", "ALLEY", "GRAVEL", "DIRT" })
    void aDistantAttachedRoadEnablesTheBridgePortalAndSuppliesItsMaterial(BoardRoad.Kind kind) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardBridgeMaterialsTest.straight(at, 0, 3, kind, BoardRoad.Kind.NONE);
        var end = at.translated(0, 2);
        var wall = BoardRoadTest.tile(end.translated(0), BoardRoad.Kind.NONE, 0, 3, BoardScene.Surface.ROCK);
        scene = BoardNaturalBridgeTest.replace(scene, wall);
        var entrances = BoardTunnel.entrances(scene, scene.tile(end));
        assertEquals(1, entrances.size());
        assertEquals(kind, entrances.getFirst().kind());
        assertEquals(BoardTunnel.BRIDGE_ASSET, entrances.getFirst().asset());
        assertTrue(BoardTunnel.entrances(scene, scene.tile(at.translated(0))).isEmpty(),
              "Connected bridge decks stay open");

        scene = BoardNaturalBridgeTest.replace(scene, BoardRoadTest.tile(at.translated(3), BoardRoad.Kind.NONE,
              0, 0, BoardScene.Surface.ROCK));
        assertTrue(BoardTunnel.entrances(scene, scene.tile(end)).isEmpty(),
              "Removing the only approach restores the whole natural span");
    }

    private static BoardScene.Tile bridge(BoardScene.Tile t, int exits) {
        return new BoardScene.Tile(t.coords(), t.elevation(), t.waterDepth(), t.frozen(), t.roadExits(), t.surface(),
              t.ground(), t.normals(), t.decals(), t.decalsWithoutLimbs(), t.tactical(),
              List.of(new BoardScene.Feature("bridge", 0, 0, 0, 1, 1, 2, BoardScene.FeatureKind.PROP, exits)),
              t.text(), t.liquid(), t.foliage(), t.detailedGround(), t.road());
    }

    @Test
    void minesBouldersLeaveBothTunnelMouthsAndTheirApproachesClear() throws Exception {
        var scene = GpuRoadSourceTest.minesScene();
        var tunnels = scene.tiles().stream().flatMap(t -> BoardTunnel.entrances(scene, t).stream()).toList();
        assertEquals(2, tunnels.size());
        int retained = 0;
        for (var tunnel : tunnels) {
            for (var tile : scene.tiles()) {
                if (tile.coords().distance(tunnel.road()) > 1) { continue; }
                for (var face : new BoardSurface(scene, tile).groundFaces()) {
                    if (face.finish() != BoardSurface.Finish.OUTCROP) { continue; }
                    var center = new Vector3(face.a()).add(face.b()).add(face.c()).scl(1f / 3);
                    assertFalse(tunnel.obstructs(center, 0, 0), "Decorative rock blocks the portal at " + tunnel.road());
                    retained++;
                }
            }
        }
        assertTrue(retained > 0, "The surrounding cliff formations remain");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5 })
    void tallRoadEndsHaveAnOpenArchAndRetainTheSurroundingCliff(int direction) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 1 << direction : 0,
              c.equals(at.translated(direction)) ? 4 : 0, BoardScene.Surface.GRASS));
        var entrances = BoardTunnel.entrances(scene, scene.tile(at));
        assertEquals(1, entrances.size());
        var tunnel = entrances.getFirst();
        var upper = new BoardSurface(scene, scene.tile(at.translated(direction)));
        var walls = upper.walls(scene, BoardGeometry.floor(scene));
        float scale = BoardGeometry.hexScale();
        Vector3 rayStart = new Vector3(tunnel.origin()).mulAdd(tunnel.along(), -5 * scale).add(0, 0, 5 * scale);
        assertFalse(hit(walls, new Ray(rayStart, tunnel.along()), 24 * scale), "The entrance is an actual hole in the wall");
        rayStart.z += 20 * scale;
        assertTrue(hit(walls, new Ray(rayStart, tunnel.along()), 30 * scale), "The cliff above the opening remains");
        assertEquals(1 << direction, scene.tile(at).roadExits(), "Portals never invent another road exit");
        Vector3 forward = new Vector3(0, 1, 0).rot(tunnel.transform()).nor();
        assertTrue(forward.epsilonEquals(tunnel.along(), .0001f));
    }

    private static boolean hit(List<BoardSurface.Face> faces, Ray ray, float reach) {
        Vector3 point = new Vector3();
        for (var face : faces) {
            if (Intersector.intersectRayTriangle(ray, face.a(), face.b(), face.c(), point)
                  && point.dst(ray.origin) < reach) { return true; }
        }
        return false;
    }

    @ParameterizedTest
    @ValueSource(ints = { -4, -2, -1, 0, 1, 2 })
    void slopesAndDownwardExitsDoNotBecomeTunnels(int rise) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c,
              c.equals(at) ? BoardRoad.Kind.PAVED : BoardRoad.Kind.NONE, c.equals(at) ? 1 : 0,
              c.equals(at.translated(0)) ? rise : 0, BoardScene.Surface.SAND));
        assertTrue(BoardTunnel.entrances(scene, scene.tile(at)).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = { 3, 4 })
    void connectedRoadsKeepTheirExistingApproach(int rise) {
        var at = BoardRoadTest.CENTER;
        var scene = BoardSurfaceBlendTest.scene(c -> BoardRoadTest.tile(c, BoardRoad.Kind.PAVED,
              c.equals(at) ? 1 : 8, c.equals(at.translated(0)) ? rise : 0, BoardScene.Surface.ROCK));
        assertTrue(BoardTunnel.entrances(scene, scene.tile(at)).isEmpty());
    }
}
