/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.client.ui.clientGUI.boardview.BoardArtwork;
import megamek.common.Hex;
import megamek.common.board.Coords;
import megamek.common.units.Entity;
import megamek.common.units.Terrain;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Rendered cliff strokes stay on the upper column, including when only authored exits change. */
@Tag("on-demand")
class GpuTacticalCliffSmokeTest {
    private static final Coords CENTER = new Coords(2, 2);

    @Test
    void cliffTopEditsStayOnTheirHexThroughTopObliqueAndPerspectiveViews() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try { verify(); }
                catch (Throwable error) { failure.set(error); }
                finally { Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Tactical cliff strokes", failure.get()); }
    }

    private static void verify() {
        BoardScene slope = scene(false), cliff = scene(true);
        GpuTilesetTerrain terrain = new GpuTilesetTerrain();
        GpuBoardOverlay overlay = new GpuBoardOverlay();
        ModelBatch batch = new ModelBatch();
        Pixmap wallPixels = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wallPixels.setColor(Color.GRAY);
        wallPixels.fill();
        Texture wall = new Texture(wallPixels);
        wallPixels.dispose();
        // Keep rim lightness neutral and wall materials identical, isolating the stroke's actual rendered pixels.
        GpuAssets assets = mock(GpuAssets.class);
        when(assets.inclineMask()).thenReturn(pixels(0xff808080));
        when(assets.highInclineMask()).thenReturn(pixels(0xff808080));
        when(assets.material(anyString())).thenReturn(wall);
        BoardCamera camera = new BoardCamera();
        camera.resize(960, 720);
        int checked = 0;
        try {
            for (boolean perspective : List.of(false, true)) {
                camera.setPerspective(perspective);
                for (int angle = 0; angle <= 360; angle += 60) {
                    camera.setIsometric(false);
                    camera.orbit(angle, angle == 0 ? 0 : 65);
                    camera.camera.zoom = .28f;
                    camera.center(BoardGeometry.center(CENTER, 1));
                    assertTrue(terrain.update(slope, BoardGeometry.floor(slope), assets), "Removing exits refreshes the top");
                    Pixmap plain = draw(slope, terrain, overlay, batch, camera);
                    assertTrue(terrain.update(cliff, BoardGeometry.floor(cliff), assets), "Adding exits refreshes the top");
                    Pixmap marked = draw(cliff, terrain, overlay, batch, camera);
                    try {
                        for (int edge = 0; edge < 6; edge++) {
                            for (float along : new float[] { .25f, .5f, .75f }) {
                                Vector3 point = edgePoint(edge, along, 1);
                                int base = sample(plain, camera, point), ink = sample(marked, camera, point);
                                assertTrue(base > 150, "Probe sees the upper top, angle " + angle);
                                assertTrue(ink < base * .8f, "Cliff stroke stays on edge " + edge + " at angle " + angle
                                      + ", perspective=" + perspective + ": " + ink + " vs " + base);
                                Vector3 inside = edgePoint(edge, along, 7);
                                assertEquals(sample(plain, camera, inside), sample(marked, camera, inside), 2,
                                      "The stroke does not cross the hex interior");
                                checked++;
                            }
                            Vector3 lip = edgePoint(edge, .5f, 0);
                            Vector3 outward = lip.cpy().sub(BoardGeometry.center(CENTER, 1)).nor();
                            if (outward.dot(camera.camera.direction) < -.2f) {
                                Vector3 face = lip.cpy();
                                face.z *= .5f;
                                assertEquals(sample(plain, camera, face), sample(marked, camera, face), 2,
                                      "No stroke descends or draws diagonally across the wall");
                            }
                        }
                        if (angle == 0 || angle == 120) { capture(marked, perspective, angle); }
                        assertFalse(terrain.update(cliff, BoardGeometry.floor(cliff), assets), "Camera changes reuse the artwork");
                    } finally {
                        plain.dispose();
                        marked.dispose();
                    }
                }
            }
            assertEquals(252, checked, "Every edge at seven angles in both projections");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            terrain.dispose();
            overlay.dispose();
            batch.dispose();
            wall.dispose();
        }
    }

    private static Vector3 edgePoint(int edge, float along, float inward) {
        Vector3 a = BoardGeometry.corner(CENTER, 1, edge), b = BoardGeometry.corner(CENTER, 1, edge + 1);
        Vector3 direction = b.cpy().sub(a).nor();
        return a.lerp(b, along).add(-direction.y * inward, direction.x * inward, 0);
    }

    private static Pixmap draw(BoardScene scene, GpuTilesetTerrain terrain, GpuBoardOverlay overlay, ModelBatch batch,
          BoardCamera camera) {
        ScreenUtils.clear(.12f, .16f, .2f, 1, true);
        batch.begin(camera.camera);
        terrain.render(batch, null);
        batch.end();
        // Include the real board overlay: it must not add a second contour at the sculpted surface's heights.
        overlay.update(new BoardSource.Frame(scene, List.of(), null, List.of(), "", null, 0, "", null),
              new GpuHud.HudView(true, false, Map.of(), Map.of(), Map.of(), null, Entity.NONE, 0),
              new BoardSource.UiPreferences(1, "", "", false, false, false, false, List.of(), 0),
              new GpuHudState(new GpuPlaybackHistory(new UnitPlayback())));
        overlay.render(camera.camera);
        return Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
    }

    private static int sample(Pixmap pixels, BoardCamera camera, Vector3 world) {
        Vector3 p = camera.camera.project(world.cpy());
        int x = Math.round(p.x), y = Math.round(p.y);
        assertTrue(x >= 0 && y >= 0 && x < pixels.getWidth() && y < pixels.getHeight(), "Probe remains on screen");
        return pixels.getPixel(x, y) >>> 24;
    }

    private static void capture(Pixmap pixels, boolean perspective, int angle) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        PixmapIO.writePNG(new FileHandle(new File(directory,
              "tactical-cliff-" + (perspective ? "perspective-" : "ortho-") + angle + ".png")), pixels, -1, true);
    }

    private static BoardScene scene(boolean cliff) {
        BoardScene.Pixels art = pixels(0xffe0c898);
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Coords at = new Coords(x, y);
                Hex hex = new Hex(at.equals(CENTER) ? 1 : 0);
                if (cliff && at.equals(CENTER)) { hex.addTerrain(new Terrain("cliff_top:1:63")); }
                var artwork = new BoardArtwork.HexImage(at, null, null, null, null, null, List.of(), Map.of(), null);
                BoardScene.Tile t = BoardScene.captureTile(hex, artwork, null, new BoardScene.PixelPool());
                tiles.add(new BoardScene.Tile(at, t.elevation(), -1, false, 0, BoardScene.Surface.GRASS,
                      art, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, art, false,
                      BoardRoad.Kind.NONE, BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false, t.cliffTopExits()));
            }
        }
        return new BoardScene(0, 5, 5, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static BoardScene.Pixels pixels(int argb) {
        BufferedImage image = new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 72; y++) {
            for (int x = 0; x < 84; x++) { image.setRGB(x, y, argb); }
        }
        return new BoardScene.Pixels(image);
    }
}
