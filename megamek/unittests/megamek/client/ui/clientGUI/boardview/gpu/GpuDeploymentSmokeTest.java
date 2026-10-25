/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Legal deployment hexes draw as native tactical geometry over the terrain. */
@Tag("on-demand")
class GpuDeploymentSmokeTest {
    @Test
    void deploymentBordersDrawAsNativeTacticalGeometry() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create(board())) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.player.setStartingPos(Board.START_ANY);
                fixture.entity.setDeployed(false);
                fixture.game.setPhase(GamePhase.DEPLOYMENT);
                fixture.view.markDeploymentHexesFor(fixture.entity);
                fixture.source.refresh();
            });
            BoardScene scene = fixture.source.takeFrame().scene();
            assertFalse(scene.tactical().fills().isEmpty(), "Deployment borders use native tactical geometry");
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    GpuTerrain terrain = new GpuTerrain();
                    GpuAtmosphere atmosphere = new GpuAtmosphere();
                    GpuTactical tactical = new GpuTactical();
                    try {
                        BoardCamera camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        assertTrue(output.isDirectory() || output.mkdirs());
                        terrain.update(scene);
                        tactical.update(scene);
                        terrain.setAtmosphere(atmosphere.lighting());
                        for (boolean isometric : new boolean[] { true, false }) {
                            camera.setIsometric(isometric);
                            camera.fit(scene);
                            drawWorld(terrain, atmosphere, camera, scene);
                            tactical.render(camera.camera, 0, !isometric);
                            GpuBoardTestUi.capture(new File(output, "deployment-depth-" + (isometric ? "isometric" : "top") + ".png"));
                        }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        terrain.dispose();
                        atmosphere.dispose();
                        tactical.dispose();
                        Gdx.app.exit();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static Board board() {
        Hex[] hexes = new Hex[7 * 7];
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 7; x++) {
                Hex hex = new Hex(x == 2 && y == 2 ? 4 : y < 2 ? 2 : 0);
                if (x == 3) {
                    hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, (1 << 0) | (1 << 3)));
                }
                if ((x == 3 && y == 3) || (x == 1 && y == 4)) {
                    hex.addTerrain(new Terrain(Terrains.BUILDING, 3, true, 0));
                    hex.addTerrain(new Terrain(Terrains.BLDG_CF, 100));
                    hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, 3));
                } else if (x == 4 && y == 5) {
                    hex.addTerrain(new Terrain(Terrains.FUEL_TANK, 2, true, 0));
                    hex.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
                    hex.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
                    hex.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
                } else if (x == 6 && y >= 4) {
                    hex.addTerrain(new Terrain(Terrains.WATER, 2));
                    if (y == 6) {
                        hex.addTerrain(new Terrain(Terrains.ICE, 1));
                    }
                }
                hexes[y * 7 + x] = hex;
            }
        }
        return new Board(7, 7, hexes);
    }

    private static void drawWorld(GpuTerrain terrain, GpuAtmosphere atmosphere, BoardCamera camera, BoardScene scene) {
        terrain.animate(0, List.of());
        terrain.renderShadows(camera.camera, List.of());
        ScreenUtils.clear(0, 0, 0, 1, true);
        atmosphere.begin(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), 0);
        terrain.render(camera.camera);
        terrain.renderTransparent(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0);
    }

}
