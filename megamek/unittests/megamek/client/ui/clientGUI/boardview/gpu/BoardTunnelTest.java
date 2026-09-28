/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardTunnelTest {
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
