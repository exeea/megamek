/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.board.RailRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The editor's Paint and Erase preview on the real board, driven by the pointer as a user drives it. Each object ghost
 * (a car put down precisely, one on a roof, a wagon riding the rail, a train stamp's every member, a decal's paint)
 * stands exactly where its click then installs the object; the route direction ghost is exactly the track the stroke
 * then lays; every terrain card shows a stand-in in each hex its click changes; Erase outlines exactly the objects its
 * click removes. Hovering rebuilds no chunk and changes no tile.
 */
@Tag("on-demand")
class GpuEditorPreviewSmokeTest {
    private static final File OUTPUT = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "b10-f2");
    private static final Coords BUILDING = new Coords(5, 5), CARS = new Coords(3, 10), WOODS = new Coords(9, 12);
    private static final Coords RAIL = new Coords(14, 2), TERRAIN = new Coords(9, 7), STEP = new Coords(3, 2);

    /** A placement case: its brush, the hex and the pointer's place in it (hex widths and heights, at a level). */
    private record Place(String name, String asset, Coords at, float x, float y, float level) { }

    private static final List<Place> PLACES = List.of(
          new Place("car", "scenery/vehicles/car", new Coords(8, 9), .2f, .1f, 0),
          new Place("roof", "scenery/vehicles/car", BUILDING, .05f, .05f, 2),
          new Place("wagon", "scenery/rail/box-car", RAIL.translated(3), .15f, .1f, 0),
          new Place("train", "stamp/rail-train", RAIL.translated(3).translated(3).translated(3), 0, 0, 0),
          new Place("decal", "decal/emblems/red-cross", new Coords(11, 4), 0, 0, 0));

    /** Terrain cards: component and preset or variant, then optionally a level of one of its fields (a Brush row). */
    private static final List<String[]> CARDS = List.of(new String[] { "vegetation", "woods-1", "woods", "2" },
          new String[] { "water", "shallow", "water", "2" }, new String[] { "building", "default" },
          new String[] { "road", "1", "road", "2" }, new String[] { "bridge", "bridge/truss" }, new String[] { "hazards", "fire" },
          new String[] { "hazards", "magma-crust", "magma", "2" }, new String[] { "scenario", "deployment" },
          new String[] { "ground", "swamp" }, new String[] { "weather", "ice" });

    @Test void ghostsStandWhereTheirClicksPlaceAndEraseOutlinesWhatGoes() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var session = new BoardEditorSession();
            Board board = Board.createEmptyBoard(20, 16);
            session.game().setBoard(board);
            board.setHex(BUILDING, new Hex(0, "building:2;bldg_cf:40;bldg_elev:2", ""));
            send(session, Action.ASSET, "", RailRoute.Kind.RAIL.asset);
            List<Coords> cells = new ArrayList<>(List.of(RAIL));
            for (int direction : new int[] { 3, 3, 3, 2, 2 }) { cells.add(cells.getLast().translated(direction)); }
            session.pointer(cells.getFirst(), 0, 0, false);
            cells.forEach(cell -> session.pointer(cell, 0, 0, true));
            session.finishStroke();
            send(session, Action.ASSET, "", "scenery/vehicles/car");
            for (Coords car : List.of(CARS, CARS.translated(2))) { session.pointer(car, 0, 0, false); session.finishStroke(); }
            send(session, Action.CHOOSE_BRUSH, "vegetation", "woods-1");
            send(session, Action.BRUSH_VALUE, "terrain:woods", "2");
            send(session, Action.BRUSH, "", "2");
            session.pointer(WOODS, 0, 0, false); session.finishStroke();
            send(session, Action.BRUSH, "", "1");
            send(session, Action.TOOL, "", "SELECT");
            return new GpuMapSource(session.game(), null, session);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        assertTrue(OUTPUT.isDirectory() || OUTPUT.mkdirs());
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 300_000_000_000L;
                /** The case under way: the places, then the route step, the terrain cards and Erase; and its stage. */
                int item, stage;
                boolean started;
                long after;
                AtomicBoolean applied = new AtomicBoolean(true);
                List<Object> chunks;
                List<BoardScene.Tile> tiles;
                List<BoundingBox> ghosts = List.of();
                Vector3 pointer;

                private Object field(Object owner, String name) throws Exception {
                    for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
                        try {
                            var field = type.getDeclaredField(name); field.setAccessible(true); return field.get(owner);
                        } catch (NoSuchFieldException next) { /* superclass */ }
                    }
                    throw new NoSuchFieldException(name);
                }
                private GpuTerrain terrain() throws Exception { return (GpuTerrain) field(this, "terrain"); }
                @SuppressWarnings("unchecked")
                private List<ModelInstance> ghostInstances() throws Exception {
                    return (List<ModelInstance>) field(field(this, "editorPreview"), "ghosts");
                }
                /**
                 * The preview the source publishes is of the event at {@code at} (not of an earlier pointer the EDT has not
                 * caught up with yet), and has been built for drawing.
                 */
                private boolean built(GpuEditorPreview.Data preview, Coords at) throws Exception {
                    return at.equals(preview.at()) && field(field(this, "editorPreview"), "built") == preview
                          && field(field(this, "editorPreview"), "comparing") == null;
                }
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                /** The next stage waits for the EDT to apply this stage's commands, then for the frames that draw them. */
                private void next() {
                    stage++; after = frames() + 8; applied = new AtomicBoolean();
                    var marker = applied; SwingUtilities.invokeLater(() -> marker.set(true));
                }
                private void nextItem() { item++; stage = -1; next(); }
                /** The pointer over {@code coords}, at x, y hex widths and heights from its centre and at {@code level}. */
                private Vector3 over(Coords coords, float x, float y, float level) {
                    return screen(BoardGeometry.center(coords, level).add(x * BoardGeometry.width(), y * BoardGeometry.height(), 0));
                }
                private Vector3 screen(Vector3 world) {
                    Vector3 result = boardCamera.camera.project(new Vector3(world));
                    result.y = Gdx.graphics.getHeight() - result.y;
                    return result;
                }
                private void hover(Vector3 point) { Gdx.input.getInputProcessor().mouseMoved(Math.round(point.x), Math.round(point.y)); }
                private void press(Vector3 point) {
                    GpuBoardTestUi.withModifiers(0, () -> Gdx.input.getInputProcessor().touchDown(Math.round(point.x),
                          Math.round(point.y), 0, Input.Buttons.LEFT));
                }
                private void drag(Vector3 point) { Gdx.input.getInputProcessor().touchDragged(Math.round(point.x), Math.round(point.y), 0); }
                private void release(Vector3 point) {
                    Gdx.input.getInputProcessor().touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                }
                private List<BoundingBox> boxes(List<ModelInstance> instances) {
                    return instances.stream().map(ghost -> ghost.calculateBoundingBox(new BoundingBox()).mul(ghost.transform)).toList();
                }
                /** Every installed object's box in the hexes around {@code centre}. */
                private List<BoundingBox> installed(Coords centre) throws Exception {
                    List<BoundingBox> result = new ArrayList<>();
                    for (int x = centre.getX() - 3; x <= centre.getX() + 3; x++) {
                        for (int y = centre.getY() - 3; y <= centre.getY() + 3; y++) {
                            terrain().editorObjects(new Coords(x, y)).forEach(object -> result.add(object.bounds()));
                        }
                    }
                    return result;
                }
                private void assertInstalled(List<BoundingBox> expected, List<BoundingBox> drawn, String what) {
                    assertFalse(expected.isEmpty(), what + " shows a ghost");
                    for (var box : expected) {
                        assertTrue(drawn.stream().anyMatch(other -> other.min.epsilonEquals(box.min, .01f) && other.max.epsilonEquals(box.max, .01f)),
                              what + ": the ghost " + box + " stands where the click installed it, among " + drawn);
                    }
                }
                private void remember() throws Exception {
                    chunks = List.copyOf((List<?>) field(terrain(), "chunks"));
                    tiles = source.takeFrame().scene().tiles();
                }
                /** Hovering left every chunk and the board's tiles as they were. */
                private void assertNothingRebuilt() throws Exception {
                    assertEquals(chunks, List.copyOf((List<?>) field(terrain(), "chunks")), "Hovering rebuilt no chunk");
                    assertSame(tiles, source.takeFrame().scene().tiles(), "Hovering changed no tile");
                }
                /**
                 * Looks at {@code coords}, remembers the installed board and hovers {@code target} (a world point) from now
                 * on, every frame: a camera change re-picks the hover at the real cursor, which a test does not move.
                 */
                private void aim(Coords coords, Vector3 target) throws Exception {
                    boardCamera.center(BoardGeometry.center(coords, 0));
                    look(coords);
                    remember();
                    aimed = target;
                    after = frames() + 6;
                    stage++;
                }
                /** A close view for objects and routes, a wider one for the terrain cards' radius-6 brush. */
                private void look(Coords coords) {
                    boolean wide = coords.equals(TERRAIN) || coords.equals(WOODS);
                    boardCamera.zoom((wide ? .55f : .22f) / boardCamera.camera.zoom);
                }
                /** The world point the pointer hovers, or null while it drags or stays where it is. */
                Vector3 aimed;
                private Vector3 at(Coords coords, float x, float y, float level) {
                    return BoardGeometry.center(coords, level).add(x * BoardGeometry.width(), y * BoardGeometry.height(), 0);
                }

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Preview smoke stalled at " + item + "/" + stage + ": "
                              + source.editorPreview().objects().keySet() + " " + source.editorPreview().hexes());
                        // Count the frames from the EDT applying the commands, not from sending them: commands applied
                        // while this frame rendered reach only the frames taken after it.
                        if (!applied.get()) { after = frames() + 8; return; }
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        if (aimed != null) { pointer = screen(aimed); hover(pointer); }
                        var preview = source.editorPreview();
                        if (!started) {
                            started = true;
                            boardCamera.fit(source.takeFrame().scene());
                            command(Action.BRUSH_VALUE, "precision", "true");
                            stage = -1; next();
                            return;
                        }
                        if (item < PLACES.size()) { place(PLACES.get(item), preview); }
                        else if (item == PLACES.size()) { routeStep(preview); }
                        else if (item <= PLACES.size() + CARDS.size()) { card(CARDS.get(item - PLACES.size() - 1), preview); }
                        else { erase(preview); }
                    } catch (Throwable error) {
                        failure.set(error); Gdx.app.exit();
                    }
                }

                /** Arm the brush, hover, check the ghosts against the frame, click, check them against the installed objects. */
                private void place(Place place, GpuEditorPreview.Data preview) throws Exception {
                    switch (stage) {
                        case 0 -> { command(Action.ASSET, "", place.asset()); next(); }
                        case 1 -> aim(place.at(), at(place.at(), place.x(), place.y(), place.level()));
                        case 2 -> {
                            if (preview.objects().isEmpty() || !built(preview, place.at())) { return; }
                            assertNothingRebuilt();
                            ghosts = boxes(ghostInstances());
                            GpuBoardTestUi.capture(new File(OUTPUT, "preview-" + place.name() + ".png"));
                            press(pointer); release(pointer);
                            next();
                        }
                        default -> {
                            if (!place.name().equals("decal")) { assertInstalled(ghosts, installed(place.at()), place.name()); }
                            else { assertEquals(1, ghosts.size(), "The decal's paint is one ghost"); }
                            if (place.name().equals("wagon")) { command(Action.BRUSH_VALUE, "precision", "false"); }
                            nextItem();
                        }
                    }
                }

                /**
                 * A rail drawn south from STEP; in the next hex the pointer heads south-east: the ghost is the route drawn so
                 * far and the step's track.
                 */
                private void routeStep(GpuEditorPreview.Data preview) throws Exception {
                    Coords turn = STEP.translated(3), ahead = turn.translated(2);
                    switch (stage) {
                        case 0 -> {
                            command(Action.ASSET, "", RailRoute.Kind.RAIL.asset);
                            aimed = null;
                            boardCamera.center(BoardGeometry.center(turn, 0));
                            look(turn);
                            next();
                        }
                        case 1 -> {
                            remember();
                            // The view's hover starts where the press does, as a real pointer's would.
                            hover(over(STEP, 0, 0, 0));
                            press(over(STEP, 0, 0, 0));
                            drag(over(turn, 0, 0, 0));
                            next();
                        }
                        case 2 -> {
                            float[] border = RailRoute.border(2);
                            Vector3 edge = BoardGeometry.center(ahead, 0).sub(BoardGeometry.center(turn, 0)).scl(.3f);
                            assertEquals(Math.signum(border[0]), Math.signum(edge.x), "The world and the hex frame agree");
                            drag(over(turn, edge.x / BoardGeometry.width(), edge.y / BoardGeometry.height(), 0));
                            next();
                        }
                        case 3 -> {
                            if (!preview.objects().containsKey(ahead) || !built(preview, ahead)) { return; }
                            assertTrue(preview.objects().containsKey(turn) && preview.objects().containsKey(STEP),
                                  "The ghost lays the route drawn so far, the turn's piece and the next hex's");
                            ghosts = boxes(ghostInstances());
                            assertEquals(5, ghosts.size(), "Three pieces and the buffer stops at both ends");
                            GpuBoardTestUi.capture(new File(OUTPUT, "preview-route-direction.png"));
                            Vector3 into = over(ahead, 0, 0, 0);
                            drag(into); release(into);
                            next();
                        }
                        default -> {
                            // The buffer stop is the rail's, never an object of its own: the two pieces are the markers'.
                            List<BoundingBox> drawn = installed(turn);
                            assertEquals(3, ghosts.stream().filter(box -> drawn.stream().anyMatch(other -> other.min.epsilonEquals(box.min, .01f)
                                  && other.max.epsilonEquals(box.max, .01f))).count(), "The route's three pieces are laid as their ghosts");
                            command(Action.BRUSH, "", "6");
                            nextItem();
                        }
                    }
                }

                /** A terrain card at radius 6: a stand-in for every hex its click changes; nothing rebuilt by hovering. */
                private void card(String[] card, GpuEditorPreview.Data preview) throws Exception {
                    switch (stage) {
                        case 0 -> {
                            command(Action.CHOOSE_BRUSH, card[0], card[1]);
                            if (card.length > 2) { command(Action.BRUSH_VALUE, "terrain:" + card[2], card[3]); }
                            next();
                        }
                        case 1 -> aim(TERRAIN, at(TERRAIN, 0, 0, 0));
                        default -> {
                            if (preview.standIns().isEmpty() || !built(preview, TERRAIN)) { return; }
                            assertNothingRebuilt();
                            assertEquals(preview.hexes().size(), preview.standIns().size(), card[1] + ": a stand-in per changed hex");
                            GpuBoardTestUi.capture(new File(OUTPUT, "preview-terrain-" + card[1].replace('/', '-') + ".png"));
                            if (card == CARDS.getLast()) {
                                command(Action.BRUSH, "", "2");
                                command(Action.ASSET, "", "scenery/vehicles/car");
                                command(Action.TOOL, "", "ERASE");
                            }
                            nextItem();
                        }
                    }
                }

                /** Erase with the car brush, then with the woods card: red ghosts of exactly what goes. */
                private void erase(GpuEditorPreview.Data preview) throws Exception {
                    Coords cars = CARS.translated(1);
                    switch (stage) {
                        case 0 -> aim(cars, at(cars, 0, 0, 0));
                        case 1 -> {
                            if (preview.objects().isEmpty() || !built(preview, cars)) { return; }
                            assertTrue(preview.erase());
                            assertNothingRebuilt();
                            ghosts = boxes(ghostInstances());
                            assertEquals(2, ghosts.size(), "Both cars go");
                            assertInstalled(ghosts, installed(cars), "A red ghost is a car as it stands");
                            assertEquals(2, ((List<?>) field(field(this, "editorPreview"), "outlines")).size(), "and is outlined");
                            GpuBoardTestUi.capture(new File(OUTPUT, "preview-erase-cars.png"));
                            ghosts = installed(cars);
                            press(pointer); release(pointer);
                            next();
                        }
                        case 2 -> {
                            assertEquals(ghosts.size() - 2, installed(cars).size(), "The click removed exactly the outlined cars");
                            command(Action.CHOOSE_BRUSH, "vegetation", "woods-1");
                            command(Action.TOOL, "", "ERASE");
                            next();
                        }
                        case 3 -> aim(WOODS, at(WOODS, 0, 0, 0));
                        default -> {
                            if (preview.standIns().isEmpty() || !built(preview, WOODS)) { return; }
                            assertTrue(preview.erase());
                            assertEquals(7, preview.hexes().size(), "The woods of the radius-2 brush");
                            assertFalse(preview.label().isEmpty());
                            GpuBoardTestUi.capture(new File(OUTPUT, "preview-erase-woods.png"));
                            Gdx.app.exit();
                        }
                    }
                }
            }, config);
        } finally { source.close(); }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private static void send(BoardEditorSession session, Action action, String target, String value) {
        session.command(new Command(action, target, value), null);
    }
}
