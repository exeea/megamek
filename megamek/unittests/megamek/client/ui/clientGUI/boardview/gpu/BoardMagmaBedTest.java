/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashSet;
import java.util.List;

import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoardMagmaBedTest {
    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3, 4 })
    void omittedLavaBedIsHiddenWhileCanonicalGroundRemainsAvailable(int map) {
        BoardScene scene = BoardCliffSeamTest.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent " + map + ".board"));
        int omitted = 0, retained = 0;
        for (var tile : scene.tiles()) {
            if (!tile.liquid().molten()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> canonical = bed(surface);
            var rendered = new HashSet<>(surface.renderBed(canonical));
            assertEquals(canonical, bed(surface), "Rendering must preserve the ground used by picking and support");
            retained += rendered.size();
            for (var face : canonical) {
                if (rendered.contains(face)) { continue; }
                omitted++;
                for (int i = 1; i < 5; i++) {
                    for (int j = 1; i + j < 6; j++) {
                        Vector3 point = new Vector3(face.a()).scl(1 - (i + j) / 6f)
                              .mulAdd(face.b(), i / 6f).mulAdd(face.c(), j / 6f);
                        Ray ray = new Ray(point, Vector3.Z);
                        Vector3 hit = new Vector3();
                        assertTrue(surface.waterFaces.stream().anyMatch(top -> Intersector.intersectRayTriangle(ray,
                                    top.a(), top.b(), top.c(), hit) && hit.z > point.z),
                              () -> "Removed ground must be covered by opaque lava at " + tile.coords() + ": " + point);
                    }
                }
            }
        }
        assertTrue(omitted > 1000, "Remove a meaningful amount of hidden geometry on the shipped map");
        assertTrue(retained > 0, "Keep the bed portions that need their shoreline and crest geometry");
    }

    @Test
    void transparentWaterKeepsItsVisibleBed() {
        BoardScene lava = BoardCliffSeamTest.scene(new File("data/boards/Map Pack Volcanic/16x17 Dome Vent 2.board"));
        BoardScene scene = BoardWaterfallTest.withWater(lava);
        for (var tile : scene.tiles()) {
            if (!tile.liquid().present()) { continue; }
            BoardSurface surface = new BoardSurface(scene, tile);
            List<BoardSurface.Face> canonical = bed(surface);
            assertEquals(canonical, surface.renderBed(canonical));
        }
    }

    private static List<BoardSurface.Face> bed(BoardSurface surface) {
        return surface.faces.stream().filter(face -> face.finish() == BoardSurface.Finish.BED).toList();
    }
}
