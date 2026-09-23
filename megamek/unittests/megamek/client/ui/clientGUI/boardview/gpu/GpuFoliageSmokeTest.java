/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Render every tree species, including the independently textured snow-covered geometry and the desert plants. */
@Tag("on-demand")
class GpuFoliageSmokeTest {
    @Test
    void rendersSummerAndWinterTreeMaterials() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                GpuTerrain terrain = new GpuTerrain();
                GpuAssets assets = new GpuAssets();
                try {
                    BoardCamera camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    camera.orbit(-45, 0);
                    for (boolean snow : new boolean[] { false, true }) {
                        BoardScene scene = treeScene(snow);
                        for (BoardScene.Tile tile : scene.tiles()) {
                            if (tile.features().isEmpty()) { continue; }
                            String name = tile.features().getFirst().asset();
                            var model = assets.model(name);
                            assertEquals(snow, model.getMaterial("snow") != null, name);
                            for (var material : model.materials) {
                                var map = material.get(TextureAttribute.class, TextureAttribute.Diffuse);
                                assertNotNull(map, name + ": " + material.id);
                                assertEquals(64, map.textureDescription.texture.getWidth());
                                assertEquals(64, map.textureDescription.texture.getHeight());
                                assertEquals(Texture.TextureWrap.Repeat, map.textureDescription.texture.getVWrap());
                                assertSame(assets.material("foliage/" + material.id), map.textureDescription.texture,
                                      "Tree variants share the small texture maps");
                            }
                        }
                        terrain.update(scene);
                        camera.fit(scene);
                        terrain.animate(0, List.of());
                        terrain.renderShadows(List.of());
                        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
                        terrain.render(camera.camera, false);
                        GpuBoardTestUi.capture(new File(System.getProperty("megamek.gpu.screenshots"),
                              "trees-" + (snow ? "snow" : "summer") + ".png"));
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                    }
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    terrain.dispose();
                    assets.dispose();
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static BoardScene treeScene(boolean snow) throws Exception {
        List<String> names = new ArrayList<>(List.of("tree", "tree-broad", "tree-slender", "birch", "pine", "pine-tall",
              "pine-broad", "willow", "tree-dead"));
        if (!snow) { names.addAll(List.of("palm", "palm-bent", "cactus", "cactus-flowers")); }
        File ground = new File(Configuration.dataDir(), "models/board/tileset/saxarba/base/base_"
              + (snow ? "snow_light" : "default") + "_0.png");
        BoardScene.Pixels pixels = new BoardScene.Pixels(ImageIO.read(ground));
        List<BoardScene.Tile> tiles = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            List<BoardScene.Feature> tree = index < names.size()
                  ? List.of(new BoardScene.Feature(names.get(index) + (snow ? "-snow" : ""), 0, 0, 20, 1.1f, 2, 0))
                  : List.of();
            tiles.add(new BoardScene.Tile(new Coords(index % 4, index / 4), 0, -1, false, 0,
                  snow ? BoardScene.Surface.SNOW : BoardScene.Surface.GRASS, pixels, null, null, tree, List.of()));
        }
        return new BoardScene(0, 4, 4, tiles, List.of(), List.of(), -1, "", List.of(), new BoardScene.Light(-24, -30));
    }
}
