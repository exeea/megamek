/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuCamouflageReview.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import megamek.client.ui.clientGUI.BoardGraphics;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.units.Terrain;
import megamek.common.units.Terrains;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The 3D Graphics as a build mode of the terrain (b11-design 2.4, section 8): a game that starts in Classic builds
 * only what Classic draws (no relief, water or lava field, wave simulation, tree, scatter, grass, biome or sculpted
 * material); a switch either way rebuilds the board behind the loading stage and retires the other build; the Tactical
 * View never changes the build.
 */
@Tag("on-demand")
class GpuGraphicsModeSmokeTest {
    private static final int WIDTH = 12, HEIGHT = 10;
    private static final Coords BUILDING = new Coords(2, 7), BRIDGE = new Coords(7, 3), WOODS = new Coords(1, 2);

    @Test
    void classicBuildsOnlyItsLayersAndASwitchRebuildsBehindTheLoadingStage() throws Exception {
        GUIPreferences preferences = GUIPreferences.getInstance();
        BoardGraphics saved = preferences.getBoardGraphics();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (var fixture = GpuBoardFixture.create(board())) {
            // Chosen before the first frame, as a saved choice is: the game never builds the 3D terrain first.
            SwingUtilities.invokeAndWait(() -> {
                preferences.setBoardGraphics(BoardGraphics.CLASSIC);
                fixture.source.refresh();
            });
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(1280, 900);
            configuration.setInitialVisible(false);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    GpuBattleView view = null;
                    try {
                        view = new GpuBattleView(fixture.source);
                        view.create();
                        GpuBoardTestUi.present(view);
                        verify(view);
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        if (view != null) { view.dispose(); }
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> preferences.setBoardGraphics(saved));
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
    }

    private static void verify(GpuBattleView view) throws Exception {
        var terrain = (GpuTerrain) get(view, "terrain");
        var scene = (BoardScene) get(view, "scene");
        view.boardCamera.setIsometric(true);
        view.boardCamera.fit(scene);
        frames(view, 3);
        assertTrue(terrain.columns(), "A Classic game builds the tileset columns");
        assertTrue(((List<?>) get(terrain, "terrainMaterialSamples")).isEmpty(), "and warms up no sculpted shader");
        verifyClassic(terrain, scene);
        capture("graphics-classic.png");
        List<Object> classic = new ArrayList<>((List<?>) get(terrain, "chunks"));

        // To Enhanced: the next frame finds the board unbuilt and shows the loading stage, then the 3D chunks commit.
        graphics(BoardGraphics.ENHANCED);
        view.render();
        assertFalse(terrain.ready(scene), "A switch to Enhanced is a rebuild");
        assertTrue(GpuBoardTestUi.loading(view), "behind the loading stage");
        GpuBoardTestUi.present(view);
        frames(view, 3);
        assertFalse(terrain.columns());
        List<?> enhanced = (List<?>) get(terrain, "chunks");
        assertTrue(enhanced.stream().noneMatch(classic::contains), "The Classic chunks are retired");
        assertTrue(enhanced.stream().anyMatch(chunk -> get(chunk, "waterField") != null), "The river has its field");
        assertTrue(enhanced.stream().anyMatch(chunk -> !((Map<?, ?>) get(get(chunk, "stand"), "trees")).isEmpty()),
              "The woods have their trees");
        assertTrue(enhanced.stream().anyMatch(chunk -> materials(chunk).stream().anyMatch(GpuTerrain::sculpted)),
              "The relief is sculpted");
        capture("graphics-enhanced.png");
        List<Object> relief = new ArrayList<>(enhanced);

        // Back to Classic, by the same rule: the loading stage, then the columns; the 3D layers are released.
        graphics(BoardGraphics.CLASSIC);
        view.render();
        assertFalse(terrain.ready(scene), "A switch to Classic is a rebuild too");
        assertTrue(GpuBoardTestUi.loading(view), "behind the loading stage");
        GpuBoardTestUi.present(view);
        frames(view, 3);
        assertTrue(terrain.columns());
        assertTrue(((List<?>) get(terrain, "chunks")).stream().noneMatch(relief::contains), "The 3D chunks are retired");
        verifyClassic(terrain, scene);

        // A switch reversed after its build packed the atlases for the other graphics: one full rebuild behind the
        // loading stage, not a background repack of every chunk while the old board shows.
        graphics(BoardGraphics.ENHANCED);
        Object switching = null;
        for (int frame = 0; frame < 600 && switching == null; frame++) {
            view.render();
            Object rebuild = get(terrain, "rebuild");
            if (rebuild != null && get(rebuild, "plan") != null) { switching = rebuild; }
        }
        assertNotNull(switching, "The switch's build packs its atlases before it commits");
        assertTrue(terrain.columns(), "while the columns are still installed");
        List<Object> columns = new ArrayList<>((List<?>) get(terrain, "chunks"));
        graphics(BoardGraphics.CLASSIC);
        view.render();
        assertFalse(terrain.ready(scene), "The reversal rebuilds the columns");
        assertTrue(GpuBoardTestUi.loading(view), "behind the loading stage");
        GpuBoardTestUi.present(view);
        frames(view, 3);
        assertTrue(terrain.columns());
        assertFalse(terrain.busy(), "No rebuild is left running");
        assertTrue(((List<?>) get(terrain, "chunks")).stream().noneMatch(columns::contains), "Every chunk is rebuilt");
        verifyClassic(terrain, scene);

        // The Tactical View hides the fluff without a rebuild, in either graphics.
        view.setTacticalView(true);
        frames(view, 3);
        assertTrue(view.boardCamera.tactical());
        assertTrue(terrain.fluffHidden());
        assertTrue(terrain.columns(), "T never changes the build");
        assertFalse(terrain.busy(), "T starts no rebuild");
        capture("graphics-classic-tactical.png");
        view.setTacticalView(false);
        frames(view, 3);
        assertFalse(terrain.fluffHidden());
        assertTrue(terrain.columns());
        assertFalse(terrain.busy());
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    /**
     * A Classic build: no water or lava field, wave simulation, tree, scatter, road, decal of the 3D view, biome map,
     * grass or sculpted material; the building and the bridge's deck art stand on the columns, and a pointer straight
     * down picks each column's own hex at its top.
     */
    private static void verifyClassic(GpuTerrain terrain, BoardScene scene) throws Exception {
        for (Object chunk : (List<?>) get(terrain, "chunks")) {
            assertNull(get(chunk, "waterField"));
            assertNull(get(chunk, "lavaField"));
            for (String layer : List.of("scatter", "overlays", "surfaceDecals")) {
                assertTrue(((List<?>) get(chunk, layer)).isEmpty(), "No " + layer + " layer");
            }
            assertTrue(((Map<?, ?>) get(get(chunk, "stand"), "trees")).isEmpty(), "No tree");
            assertTrue(((Map<?, ?>) get(get(chunk, "scatterStand"), "trees")).isEmpty(), "No scattered plant");
            assertTrue(materials(chunk).stream().noneMatch(GpuTerrain::sculpted), "No sculpted material");
            assertTrue(materials(chunk).stream().noneMatch(material -> material.has(GpuWaterShader.TYPE)), "No 3D water");
        }
        assertNull(((GpuOcean) get(terrain, "ocean")).texture(), "No wave simulation");
        assertNull(((GpuOcean) get(terrain, "lavaOcean")).texture());
        assertNull(get(get(terrain, "biomes"), "texture"), "No biome map");
        assertTrue(((Map<?, ?>) get(get(terrain, "groundCover"), "chunks")).isEmpty(), "No grass");
        boolean building = false;
        for (Object chunk : (List<?>) get(terrain, "chunks")) {
            for (Object prop : (List<?>) get(chunk, "props")) {
                building |= BUILDING.equals(get(prop, "coords")) && (boolean) get(prop, "structure");
            }
        }
        assertTrue(building, "The building stands on its column");
        float floor = BoardGeometry.floor(scene);
        for (Coords coords : List.of(BUILDING, BRIDGE, WOODS, new Coords(7, 8), new Coords(4, 4))) {
            assertEquals(GpuTilesetTerrain.column(scene, scene.tile(coords), floor).top(),
                  terrain.tacticalSurface(coords).top(), "The overlays drape on the column of " + coords);
        }
        for (Coords coords : List.of(WOODS, new Coords(4, 4), new Coords(10, 1))) {
            Vector3 centre = BoardGeometry.center(coords, 0);
            var hit = terrain.selectionHit(scene, new Ray(new Vector3(centre.x, centre.y, 1000), new Vector3(0, 0, -1)));
            assertNotNull(hit);
            assertEquals(coords, hit.coords(), "A column pick hits its hex");
            assertEquals(1000 - scene.tile(coords).elevation() * BoardGeometry.level(), Math.sqrt(hit.distance()), .01,
                  "at its flat top");
        }
    }

    /** The materials of a chunk's terrain layers. */
    private static List<Material> materials(Object chunk) {
        List<Material> result = new ArrayList<>();
        for (String layer : List.of("opaque", "water")) {
            for (Object instance : (List<?>) get(chunk, layer)) {
                for (Material material : ((ModelInstance) instance).materials) { result.add(material); }
            }
        }
        return result;
    }

    private static Object get(Object object, String name) {
        try {
            return field(object, name);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Chooses the graphics on the Swing thread, as the menus do. */
    private static void graphics(BoardGraphics graphics) throws Exception {
        SwingUtilities.invokeAndWait(() -> GUIPreferences.getInstance().setBoardGraphics(graphics));
    }

    private static void frames(GpuBattleView view, int count) {
        for (int frame = 0; frame < count; frame++) { view.render(); }
    }

    private static void capture(String name) {
        File directory = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(directory.isDirectory() || directory.mkdirs());
        GpuBoardTestUi.capture(new File(directory, name));
    }

    /** Woods, a building, a fuel tank, a bridged river: what Classic draws and what it leaves to its tileset art. */
    private static Board board() {
        Hex[] hexes = new Hex[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                Hex hex = new Hex(x == 4 ? 3 : x >= 9 ? 1 : 0);
                if (x == 7) { hex.addTerrain(new Terrain(Terrains.WATER, y >= 7 ? 2 : 1)); }
                hexes[y * WIDTH + x] = hex;
            }
        }
        for (Coords woods : List.of(WOODS, new Coords(2, 2), new Coords(6, 8))) {
            hexes[woods.getY() * WIDTH + woods.getX()].addTerrain(new Terrain(Terrains.WOODS, 2));
            hexes[woods.getY() * WIDTH + woods.getX()].addTerrain(new Terrain(Terrains.FOLIAGE_ELEV, 2));
        }
        Hex building = hexes[BUILDING.getY() * WIDTH + BUILDING.getX()];
        building.addTerrain(new Terrain(Terrains.BUILDING, 2, true, 0));
        building.addTerrain(new Terrain(Terrains.BLDG_ELEV, 2));
        building.addTerrain(new Terrain(Terrains.BLDG_CF, 40));
        Hex tank = hexes[1 * WIDTH + 5];
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK, 2, true, 0));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_ELEV, 2));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_CF, 40));
        tank.addTerrain(new Terrain(Terrains.FUEL_TANK_MAGN, 100));
        Hex bridge = hexes[BRIDGE.getY() * WIDTH + BRIDGE.getX()];
        bridge.addTerrain(new Terrain(Terrains.BRIDGE, 2, true, (1 << 1) | (1 << 4)));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_ELEV, 1));
        bridge.addTerrain(new Terrain(Terrains.BRIDGE_CF, 80));
        return new Board(WIDTH, HEIGHT, hexes);
    }
}
