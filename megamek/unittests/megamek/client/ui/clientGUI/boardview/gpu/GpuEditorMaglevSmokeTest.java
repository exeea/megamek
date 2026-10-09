/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.common.Configuration;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardFile;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Legacy maglev imported as route markers and pieces stands where the legacy render showed it, its vehicles riding the
 * rail on every support, and the palette offers the route, platform, wagon, cab and the train stamp.
 */
@Tag("on-demand")
class GpuEditorMaglevSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
    /** A three-hex N/S train, a three-hex NE/SW train, the three stations, parallel tracks and trains on supports. */
    private static final Map<Coords, String> FIXTURE = new LinkedHashMap<>();
    /** Hexes whose imported vehicles stand somewhere else than the legacy render put them, and why. */
    private static final Map<Coords, String> MOVED = new HashMap<>();
    static {
        FIXTURE.put(new Coords(1, 1), "fluff:9:6");
        FIXTURE.put(new Coords(1, 2), "fluff:9:7");
        FIXTURE.put(new Coords(1, 3), "fluff:9:8");
        FIXTURE.put(new Coords(3, 5), "fluff:9:9");
        FIXTURE.put(new Coords(4, 5), "fluff:9:10");
        FIXTURE.put(new Coords(5, 4), "fluff:9:11");
        FIXTURE.put(new Coords(7, 1), "fluff:9:3");
        FIXTURE.put(new Coords(9, 1), "fluff:9:4");
        FIXTURE.put(new Coords(11, 1), "fluff:9:5");
        for (int x : new int[] { 3, 4 }) {
            for (int y : new int[] { 1, 2 }) { FIXTURE.put(new Coords(x, y), "fluff:9:0"); }
        }
        FIXTURE.put(new Coords(7, 4), "water:2;bridge:1:09;bridge_cf:40;bridge_elev:1;fluff:9:7");
        FIXTURE.put(new Coords(9, 4), "heavy_industrial:2;fluff:9:7");
        MOVED.put(new Coords(9, 4), "the legacy render's ray at the hex centre misses this industrial plant, so the train "
              + "stood inside it on the ground; the imported route rides on the industrial top, as on a deck or roof, and "
              + "leaves the parked cars out");
        FIXTURE.put(new Coords(11, 4), "water:1;fluff:9:7");
        MOVED.put(new Coords(11, 4), "the legacy render put the train on the river bed; the imported route keeps the hex's "
              + "level, above the water, and leaves the parked cars out");
        FIXTURE.put(new Coords(13, 4), "building:2;bldg_elev:2;bldg_cf:50;fluff:9:7");
    }

    static Board legacy() {
        Board board = Board.createEmptyBoard(15, 7);
        FIXTURE.forEach((at, terrain) -> board.setHex(at, new Hex(0, terrain, "")));
        return board;
    }

    @Test
    void importedMaglevRidesItsRouteWhereTheLegacyRenderShowedIt() throws Exception {
        Board imported = legacy();
        SwingUtilities.invokeAndWait(() -> BoardSceneryLayouts.importBoard(imported));
        Map<String, String> assets = new HashMap<>();
        for (Coords at : FIXTURE.keySet()) { imported.getHex(at).getDecorations().forEach(o -> assets.put(o.id(), o.asset())); }
        BoardScene before = GpuLegacyImportSmokeTest.scene(legacy()), after = GpuLegacyImportSmokeTest.scene(imported);
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        render((frame, camera) -> {
            for (int levelHeight : new int[] { BoardGeometry.DEFAULTS.levelHeight(), 30 }) {
                BoardGeometry.tune(new BoardGeometry.Tuning(1, 1, 1, levelHeight, .8f));
                float tolerance = .05f * BoardGeometry.hexScale();
                camera.fit(before);
                var legacyProps = props(frame, camera, before, false, "maglev-import-legacy-" + levelHeight + ".png");
                var importedProps = props(frame, camera, after, true, "maglev-import-imported-" + levelHeight + ".png");
                for (Coords at : FIXTURE.keySet()) {
                    String name = FIXTURE.get(at) + " at " + at + ", level height " + levelHeight;
                    var pieces = importedProps.get(at);
                    var route = pieces.stream().filter(p -> MaglevRoute.ASSET.equals(assets.get(p.id()))).toList();
                    assertEquals(1, route.size(), name);
                    float rail = route.getFirst().bounds().max.z;
                    for (var piece : pieces) {
                        String asset = assets.get(piece.id());
                        if (asset != null && asset.matches("scenery/maglev/(wagon|cab|coupler)")) {
                            // Wagons and cabs ride 0.5 px into the 4 px rail; the coupler sits 0.5 px higher.
                            float ride = asset.endsWith("coupler") ? 0 : .5f;
                            assertEquals(rail - ride * BoardGeometry.hexScale(), piece.bounds().min.z, tolerance, name + " " + asset);
                        }
                    }
                    float legacyTop = top(legacyProps.get(at)), importedTop = top(pieces);
                    System.out.printf("MAGLEV %s: top legacy %.2f imported %.2f (dz %.2f)%n", name, legacyTop, importedTop,
                          importedTop - legacyTop);
                    if (!MOVED.containsKey(at)) { assertEquals(legacyTop, importedTop, tolerance, name + ": the legacy height"); }
                    else { assertTrue(importedTop > legacyTop + tolerance, name + ": above the legacy height, as " + MOVED.get(at)); }
                    boolean cars = imported.getHex(at).getDecorations().stream().anyMatch(o -> o.asset().startsWith("scenery/vehicles/car"));
                    assertEquals(!MOVED.containsKey(at) && !FIXTURE.get(at).matches("fluff:9:(0|5|9)"), cars,
                          name + ": parked cars only where the hex supports them");
                }
            }
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
        });
    }

    @Test
    void aCorpusBoardsTrainsAndStationsImportAsTheyWereDrawn() throws Exception {
        var file = Configuration.boardsDir().toPath().resolve("unofficial/Derv_Maps/90x41 Whats Done Behind.board");
        Board legacy = BoardFile.read(file), imported = BoardFile.read(file);
        SwingUtilities.invokeAndWait(() -> BoardSceneryLayouts.importBoard(imported));
        BoardScene before = GpuLegacyImportSmokeTest.scene(legacy), after = GpuLegacyImportSmokeTest.scene(imported);
        // The NE/SW train runs from 7317 to 7715, the N/S one from 8015 to 8018.
        var shown = before.tiles().stream().filter(tile -> tile.coords().getX() >= 71 && tile.coords().getX() <= 80
              && tile.coords().getY() >= 12 && tile.coords().getY() <= 18).toList();
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        render((frame, camera) -> {
            camera.fit(before, shown);
            props(frame, camera, before, false, "maglev-corpus-legacy.png");
            var objects = props(frame, camera, after, true, "maglev-corpus-imported.png");
            for (var tile : shown) {
                var hex = imported.getHex(tile.coords());
                if (MaglevRoute.find(hex) == null) { continue; }
                assertEquals(hex.getDecorations().size(), objects.getOrDefault(tile.coords(), List.of()).stream()
                      .filter(p -> p.id() != null).count(), tile.coords() + ": every imported object is drawn");
            }
        });
    }

    @Test
    void thePaletteOffersTheRoutePlatformWagonCabAndTrainStamp() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            var board = Board.createEmptyBoard(5, 4);
            editor.game().setBoard(board);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        List<String> visible = List.of(MaglevRoute.ASSET, "scenery/maglev/platform", "scenery/maglev/wagon",
              "scenery/maglev/cab", "stamp/maglev-train");
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 90_000_000_000L;
                int step;
                long after;

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Maglev palette stalled at " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        switch (step) {
                            case 0 -> GpuBoardTestUi.category("Maglev and wagons");
                            case 1 -> {
                                if (!ready(root.findActor("editor-library"))) { return; }
                                for (String id : visible) { assertNotNull(root.findActor("editor-library-" + id), id); }
                                assertNull(root.findActor("editor-library-scenery/maglev/coupler"), "The coupler comes with the train");
                                GpuBoardTestUi.capture(new File(OUTPUT, "editor-maglev-palette.png"));
                                GpuBoardTestUi.click("editor-library-stamp/maglev-train");
                            }
                            case 2 -> {
                                assertEquals("stamp/maglev-train", source.editorState().activeBrush().key());
                                assertEquals(BoardEditorSession.Tool.PAINT, source.editorState().tool());
                                Gdx.app.exit(); return;
                            }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = frames() + 8;
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Maglev palette", failure.get()); }
    }

    private interface Frames { void run(GpuReviewFrame frame, BoardCamera camera) throws Exception; }

    private static void render(Frames frames) {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1400, 900);
        config.setInitialVisible(false);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override public void create() {
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    var camera = new BoardCamera();
                    camera.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    camera.setIsometric(true);
                    frames.run(frame, camera);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally { BoardGeometry.tune(BoardGeometry.DEFAULTS); frame.dispose(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Maglev import", failure.get()); }
    }

    /** One rendered screenshot and the props it installed per hex; imported objects keep their ids. */
    private static Map<Coords, List<GpuLegacyImportSmokeTest.Installed>> props(GpuReviewFrame frame, BoardCamera camera,
          BoardScene scene, boolean objects, String screenshot) throws Exception {
        var terrain = new GpuTerrain();
        try {
            terrain.editableObjects(objects);
            frame.prepare(terrain, camera, scene);
            terrain.update(scene);
            terrain.animate(0, List.of());
            frame.render(terrain, camera, scene);
            GpuReviewFrame.save(new File(OUTPUT, screenshot));
            Map<Coords, List<GpuLegacyImportSmokeTest.Installed>> result = new HashMap<>();
            for (var prop : GpuLegacyImportSmokeTest.installed(terrain)) {
                result.computeIfAbsent(prop.coords(), at -> new java.util.ArrayList<>()).add(prop);
            }
            return result;
        } finally { terrain.dispose(); }
    }

    /** The highest point of a hex's scenery, its structures aside. */
    private static float top(List<GpuLegacyImportSmokeTest.Installed> props) {
        return (float) props.stream().filter(p -> !p.structure() || p.id() != null).mapToDouble(p -> p.bounds().max.z).max().orElseThrow();
    }

    private static boolean ready(Actor actor) {
        if (actor instanceof Image image && "editor-card-preview".equals(image.getName())) {
            assertNotEquals("Preview unavailable", image.getUserObject());
            assertNotEquals("No preview", image.getUserObject());
            return image.getDrawable() != null;
        }
        if (actor instanceof Group group) {
            for (Actor child : group.getChildren()) { if (!ready(child)) { return false; } }
        }
        return true;
    }
}
