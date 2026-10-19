/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardSceneryLayouts;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Sculpted riverbanks continue adjacent material cover and rebuild when that cover changes. */
@Tag("on-demand")
class GpuRiverbankSmokeTest {
    @Test
    void comparesRaisedWetAndDryBanksAndAridShallows() throws Exception {
        var scenes = new ArrayList<BoardScene>();
        var names = new ArrayList<String>();
        SwingUtilities.invokeAndWait(() -> {
            for (String theme : List.of("grass", "dirt", "desert", "mars")) {
                boolean arid = theme.equals("desert") || theme.equals("mars");
                for (int variant = 0; variant < 2; variant++) {
                    int depth = arid ? variant : variant == 0 ? -1 : 1;
                    Hex[] hexes = new Hex[49];
                    for (int y = 0; y < 7; y++) {
                        for (int x = 0; x < 7; x++) {
                            Hex hex = new Hex(x < 3 ? 1 : 0, "", theme);
                            if (depth >= 0 && x == 3 && y > 0 && y < 6) {
                                hex.addTerrain(new Terrain(Terrains.WATER, depth));
                            }
                            hexes[y * 7 + x] = hex;
                        }
                    }
                    var game = new Game();
                    game.setBoard(new Board(7, 7, hexes));
                    BoardSceneryLayouts.convert(game.getBoard());
                    try (var source = new GpuMapSource(game, null, null)) { scenes.add(source.takeFrame().scene()); }
                    names.add(theme + (arid ? "-depth" + depth : depth < 0 ? "-dry" : "-water"));
                }
            }
        });
        var failure = new AtomicReference<Throwable>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    Vector3 high = BoardGeometry.center(new Coords(2, 3), 1);
                    Vector3 low = BoardGeometry.center(new Coords(3, 3), 0);
                    Vector3 target = new Vector3(high).lerp(low, .5f);
                    Vector3 outward = new Vector3(low).sub(high); outward.z = 0; outward.nor();
                    camera.camera.zoom = .2f;
                    camera.camera.near = 1;
                    camera.camera.far = 3000;
                    camera.camera.up.set(Vector3.Z);
                    camera.camera.position.set(target).mulAdd(outward, 650).add(0, 0, 350);
                    camera.camera.lookAt(target);
                    camera.camera.update();
                    File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
                    assertTrue(output.isDirectory() || output.mkdirs());
                    for (int i = 0; i < scenes.size(); i++) {
                        BoardScene scene = scenes.get(i);
                        frame.prepare(terrain, camera, scene);
                        terrain.update(scene);
                        terrain.animate(0, List.of());
                        for (int settle = 0; settle < 3; settle++) { frame.render(terrain, camera, scene); }
                        Thread.sleep(600);
                        frame.render(terrain, camera, scene);
                        GpuReviewFrame.save(new File(output, "riverbank-" + names.get(i) + "-slope.png"));
                        if (names.get(i).endsWith("-depth0")) {
                            terrain.setWaterEffects(false);
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, "riverbank-" + names.get(i) + "-effects-off.png"));
                            terrain.setWaterEffects(true);
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { terrain.dispose(); frame.dispose(); Gdx.app.exit(); }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) { throw new AssertionError("Raised wet/dry bank comparison", failure.get()); }
    }

    @Test
    void banksMatchSandGrassAndSnowAndFollowSurfaceUpdates() throws Exception {
        Hex[] hexes = new Hex[49];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                Hex hex = new Hex(0);
                if (x < 3) {
                    hex.addTerrain(new Terrain(Terrains.SAND, 1));
                } else if (x > 3 && y >= 4) {
                    hex.setTheme("snow");
                    hex.addTerrain(new Terrain(Terrains.SNOW, 1));
                }
                if (x == 3 && y > 0 && y < 6) {
                    hex.addTerrain(new Terrain(Terrains.WATER, 1));
                }
                hexes[y * 7 + x] = hex;
            }
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(new Board(7, 7, hexes))) {
            SwingUtilities.invokeAndWait(fixture.source::refresh);
            BoardScene scene = fixture.source.takeFrame().scene();
            Coords river = new Coords(3, 3);
            int sandEdge = IntStream.range(0, 6).filter(edge -> {
                var tile = scene.tile(river.translated(BoardGeometry.edgeDirection(edge)));
                return tile.waterDepth() < 0 && tile.groundCover().sand() > .9f;
            }).findFirst().orElseThrow(() -> new AssertionError("The river fixture must have a sand-covered bank"));
            Coords land = river.translated(BoardGeometry.edgeDirection(sandEdge));
            // Capture through the real source so all cover, biome and artwork metadata is retained.
            // Replacing only Tile.ground is not a material edit for the sculpted-bank shader.
            SwingUtilities.invokeAndWait(() -> {
                Hex edited = fixture.game.getBoard().getHex(land).duplicate();
                edited.removeTerrain(Terrains.SAND);
                edited.setTheme("snow");
                edited.addTerrain(new Terrain(Terrains.SNOW, 1));
                fixture.game.getBoard().setHex(land, edited);
                fixture.source.refresh();
            });
            BoardScene edited = fixture.source.takeFrame().scene();
            // Gameplay sand is captured as loose cover over the theme's geology, not Tile.surface().
            assertTrue(scene.tile(land).groundCover().sand() > .9f);
            assertEquals(BoardScene.Surface.SNOW, edited.tile(land).surface());
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    try (var outputFrame = new GpuSceneTestOutput()) {
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.center(BoardGeometry.center(river, 0));
                        camera.zoom(0.25f);
                        terrain.update(scene);
                        int[] roles = GpuTerrainPreparationSmokeTest.checkBlendedRoles(terrain, "submerged riverbank");
                        assertTrue(roles[3] > 0, "The bank fixture must contain blended shore triangles");
                        outputFrame.draw(terrain, camera.camera, true);
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        GpuBoardTestUi.capture(new File(output, "riverbank-terrain-top.png"));
                        checkBanks(scene, river);
                        int sandyBank = bankPixels(camera, river, sandEdge);
                        outputFrame.draw(terrain, camera.camera, true);
                        assertEquals(sandyBank, bankPixels(camera, river, sandEdge), "A paused bank keeps the same pixels");
                        camera.setIsometric(true);
                        outputFrame.draw(terrain, camera.camera, true);
                        GpuBoardTestUi.capture(new File(output, "riverbank-terrain-isometric.png"));

                        terrain.update(edited);
                        GpuTerrainPreparationSmokeTest.checkBlendedRoles(terrain, "edited submerged riverbank");
                        camera.setIsometric(false);
                        outputFrame.draw(terrain, camera.camera, true);
                        checkBanks(edited, river);
                        assertNotEquals(sandyBank, bankPixels(camera, river, sandEdge),
                              "Changing the adjacent sand to snow must rebuild the visible bank material");
                        GpuBoardTestUi.capture(new File(output, "riverbank-updated-snow.png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        terrain.dispose();
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static void checkBanks(BoardScene scene, Coords river) {
        var water = scene.tile(river);
        for (int edge : new int[] { 0, 2, 3, 5 }) {
            var neighbor = scene.tile(river.translated(BoardGeometry.edgeDirection(edge)));
            int dominant = 0;
            for (int family = 1; family < BoardSurfaceBlend.FAMILIES; family++) {
                if (neighbor.groundCover().weight(family) > neighbor.groundCover().weight(dominant)) { dominant = family; }
            }
            Vector3 a = BoardGeometry.corner(river, water.elevation(), edge);
            Vector3 b = BoardGeometry.corner(river, water.elevation(), edge + 1);
            Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
            float epsilon = BoardRelief.metres(.001f);
            for (float along : new float[] { .25f, .5f, .75f }) {
                Vector3 seam = new Vector3(a).lerp(b, along);
                Vector3 bank = new Vector3(seam).mulAdd(outward, -epsilon);
                Vector3 land = new Vector3(seam).mulAdd(outward, epsilon);
                var bankCover = BoardSurfaceBlend.sample(scene, water, bank.x, bank.y, bank.z);
                var landCover = BoardSurfaceBlend.sample(scene, neighbor, land.x, land.y, land.z);
                // These are the shared material weights supplied to the sculpted vertices. Outgoing RGB
                // need not match: the sloped wet bank and flat ground have different normals and roughness.
                for (int family = 0; family < BoardSurfaceBlend.FAMILIES; family++) {
                    assertEquals(landCover.weight(family), bankCover.weight(family), .003f,
                          "Bank material continuity at edge " + edge + ", position " + along + ", family " + family);
                }
                assertTrue(bankCover.weight(dominant) > .5f,
                      "The bank must retain its adjacent dominant cover " + dominant + " at edge " + edge);
            }
        }
    }

    private static int bankPixels(BoardCamera camera, Coords river, int edge) {
        Pixmap pixels = ScreenUtils.getFrameBufferPixmap(0, 0,
              Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
        try {
            Vector3 a = BoardGeometry.corner(river, 0, edge);
            Vector3 b = BoardGeometry.corner(river, 0, edge + 1);
            Vector3 outward = new Vector3(b).sub(a).crs(Vector3.Z).nor();
            int hash = 1;
            for (float along : new float[] { .25f, .5f, .75f }) {
                Vector3 point = camera.camera.project(new Vector3(a).lerp(b, along).mulAdd(outward, -2));
                int px = Math.round(point.x * pixels.getWidth() / Gdx.graphics.getWidth());
                int py = Math.round(point.y * pixels.getHeight() / Gdx.graphics.getHeight());
                assertTrue(px >= 1 && px + 1 < pixels.getWidth() && py >= 1 && py + 1 < pixels.getHeight(),
                      "Bank sample must remain in the framebuffer: " + px + ", " + py);
                for (int y = -1; y <= 1; y++) {
                    for (int x = -1; x <= 1; x++) { hash = 31 * hash + pixels.getPixel(px + x, py + y); }
                }
            }
            return hash;
        } finally {
            pixels.dispose();
        }
    }
}
