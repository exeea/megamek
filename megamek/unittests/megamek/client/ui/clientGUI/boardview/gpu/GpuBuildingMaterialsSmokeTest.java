/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real tileset selection and native rendering of construction families, doors, and circular tank roofs. */
@Tag("on-demand")
class GpuBuildingMaterialsSmokeTest {
    @Test
    void selectedRoofFamiliesUseSmallContextualWallTextures() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (GpuBoardFixture fixture = GpuBoardFixture.create()) {
            SwingUtilities.invokeAndWait(() -> {
                Hex[] hexes = new Hex[9 * 9];
                for (int y = 0; y < 9; y++) {
                    for (int x = 0; x < 9; x++) {
                        Hex hex = new Hex(0);
                        if ((x & 1) == 1 && y <= 4 && (y & 1) == 0) {
                            hex.addTerrain(new Terrain(Terrains.BUILDING, (x + 1) / 2, true, 0));
                            hex.addTerrain(new Terrain(Terrains.BLDG_CF, 100));
                            hex.addTerrain(new Terrain(Terrains.BLDG_ELEV, (x + 1) / 2));
                            hex.addTerrain(new Terrain(Terrains.BLDG_CLASS, y / 2));
                        } else if ((x == 1 || x == 3) && y == 6) {
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK, x == 1 ? 2 : 4, true, 0));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
                            hex.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
                        } else if (x == 5 && y == 6) {
                            hex.addTerrain(new Terrain(Terrains.INDUSTRIAL, 2));
                        } else if (x == 4 && y == 7) {
                            // A road bank at deck height attaches to the span below; a span without an attached
                            // road is a natural bridge with no GLB deck.
                            hex.setLevel(2);
                            hex.addTerrain(new Terrain(Terrains.ROAD, 1, true, 1 << 3));
                        } else if (x == 4 && y == 8) {
                            hex.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, 9));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_CF, 100));
                            hex.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 2));
                        }
                        hexes[y * 9 + x] = hex;
                    }
                }
                fixture.game.setBoard(new Board(9, 9, hexes));
                fixture.source.refresh();
            });
            BoardScene captured = fixture.source.takeFrame().scene();
            BoardScene scene = new BoardScene(0, 9, 9, captured.tiles(), List.of(), List.of(), -1, "", List.of(),
                  new BoardScene.Light(-24, -30));
            assertEquals(15, scene.tiles().stream().flatMap(tile -> tile.features().stream())
                  .filter(feature -> feature.asset().startsWith("buildings/")).count());
            new Lwjgl3Application(new ApplicationAdapter() {
                GpuTerrain terrain;
                BoardCamera camera;
                int frames;

                @Override
                public void create() {
                    try {
                        checkMaterials(scene);
                        terrain = new GpuTerrain();
                        terrain.update(scene);
                        checkHeights(scene, terrain);
                        BoardGeometry.Tuning previous = BoardGeometry.tuning();
                        try {
                            BoardGeometry.tune(new BoardGeometry.Tuning(1.5f, 1, 1, 12, previous.gridShade()));
                            terrain.update(scene);
                            checkHeights(scene, terrain);
                        } finally {
                            BoardGeometry.tune(previous);
                            terrain.update(scene);
                        }
                        camera = new BoardCamera();
                        camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                        camera.setIsometric(true);
                        camera.fit(scene);
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void render() {
                    if (failure.get() != null) {
                        return;
                    }
                    try {
                        terrain.animate(0, List.of());
                        terrain.renderShadows(List.of());
                        ScreenUtils.clear(0.035f, 0.055f, 0.075f, 1, true);
                        terrain.render(camera.camera, false);
                        File output = new File(System.getProperty("megamek.gpu.screenshots"));
                        if (++frames == 4) {
                            GpuBoardTestUi.capture(new File(output, "building-material-families.png"));
                            camera.zoom(0.5f);
                            camera.center(BoardGeometry.center(new Coords(4, 0), 1));
                        } else if (frames == 7) {
                            GpuBoardTestUi.capture(new File(output, "building-material-construction.png"));
                            camera.center(BoardGeometry.center(new Coords(4, 2), 1));
                        } else if (frames == 10) {
                            GpuBoardTestUi.capture(new File(output, "building-material-hangars.png"));
                            camera.center(BoardGeometry.center(new Coords(4, 4), 1));
                        } else if (frames == 13) {
                            GpuBoardTestUi.capture(new File(output, "building-material-fortresses.png"));
                            camera.center(BoardGeometry.center(new Coords(3, 6), 1));
                        } else if (frames == 16) {
                            GpuBoardTestUi.capture(new File(output, "building-material-tanks-industrial.png"));
                            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                @Override
                public void dispose() {
                    if (terrain != null) {
                        terrain.dispose();
                    }
                }
            }, GpuBoardWindow.configuration(false));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static void checkMaterials(BoardScene scene) {
        GpuAssets assets = new GpuAssets();
        try {
            for (var tile : scene.tiles()) {
                for (var feature : tile.features()) {
                    if (!feature.asset().startsWith("buildings/")) { continue; }
                    assertTrue(tile.detailedGround(), "Structures must not replace the native ground: " + tile.coords());
                    assertEquals(BoardScene.Surface.GRASS, tile.surface());
                    String family = tile.coords().getY() == 6
                          ? tile.coords().getX() == 5 ? "industrial" : "tank"
                          : tile.coords().getY() == 4 ? "fortress" : tile.coords().getY() == 2 ? "hangar"
                          : List.of("light", "medium", "heavy", "hard").get((tile.coords().getX() - 1) / 2);
                    String role = family.equals("fortress") || family.equals("hangar") ? "shell" : "wall";
                    var model = assets.model(feature.asset());
                    BoundingBox bounds = model.calculateBoundingBox(new BoundingBox());
                    assertEquals(0, bounds.min.z, .0001f, feature.asset());
                    assertEquals(18, bounds.max.z, .0001f, "Buildings must be one level tall in a GLB viewer");
                    var wall = model.getMaterial(role);
                    assertNotNull(wall, feature.asset());
                    Texture texture = wall.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture;
                    assertSame(assets.material("buildings/" + family), texture, feature.asset());
                    assertEquals(128, texture.getWidth());
                    assertEquals(128, texture.getHeight());
                    assertEquals(Texture.TextureWrap.Repeat, texture.getUWrap());
                }
            }
            BoundingBox bridge = assets.model("bridge").calculateBoundingBox(new BoundingBox());
            assertEquals(18, bridge.getWidth(), .0001f);
            assertEquals(72, bridge.getHeight(), .0001f);
            assertEquals(-1.5f, bridge.min.z, .0001f, "Authored underside thickness");
            assertEquals(2.5f, bridge.max.z, .0001f, "Authored rail height");
            var deck = assets.model("bridge").getMaterial("bridge-deck");
            assertSame(assets.road("asphalt").color(),
                  deck.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture);
            var sides = assets.model("bridge").getMaterial("bridge-structure");
            assertSame(assets.sculpt("concrete").color(),
                  sides.get(TextureAttribute.class, TextureAttribute.Diffuse).textureDescription.texture);
            for (var surface : BoardScene.Surface.values()) {
                assertEquals(128, assets.material(surface.wall).getWidth());
                assertEquals(128, assets.material(surface.wall).getHeight());
            }
        } finally {
            assets.dispose();
        }
    }

    private static void checkHeights(BoardScene scene, GpuTerrain terrain) {
        for (var tile : scene.tiles()) {
            for (var feature : tile.features()) {
                if (!feature.asset().startsWith("buildings/")) { continue; }
                BoundingBox placed = terrain.roofBounds(tile.coords());
                assertNotNull(placed, feature.asset());
                assertEquals(feature.height() * BoardGeometry.level(), placed.getDepth(), .001f,
                      "Authored height must not multiply the game's building/tank/industrial level count");
            }
        }
        Coords coords = new Coords(4, 8);
        assertEquals(1, scene.tile(coords).features().stream().filter(f -> f.asset().equals("bridge")).count());
        BoundingBox placed = terrain.roofBounds(coords);
        assertNotNull(placed, "Bridge terrain must instantiate one complete GLB deck");
        float deck = 2 * BoardGeometry.level() + GpuRoads.SURFACE_LIFT * BoardGeometry.hexScale();
        // The authored deck kit sets the exact underside and rail heights, near the slab's 1.5 below and 2.5 above.
        assertEquals(deck - 1.5f * BoardGeometry.hexScale(), placed.min.z, .15f * BoardGeometry.hexScale());
        // A banked span carries the authored terminal block above its rails.
        assertEquals(deck + Math.max(2.5f, BoardBridgeFooting.terminalHeight()) * BoardGeometry.hexScale(), placed.max.z,
              .15f * BoardGeometry.hexScale());
        Ray ray = new Ray(BoardGeometry.center(coords, 0).add(0, 10 * BoardGeometry.hexScale(), deck + 50),
              new Vector3(0, 0, -1));
        BoardGeometry.Hit hit = terrain.hit(scene, ray);
        assertNotNull(hit);
        assertEquals(coords, hit.coords());
        assertEquals(50, Math.sqrt(hit.distance()), .001, "Picking must hit the elevated bridge deck");
    }
}
