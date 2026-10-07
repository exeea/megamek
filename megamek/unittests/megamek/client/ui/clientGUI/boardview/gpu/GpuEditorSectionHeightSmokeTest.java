/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Native pointer gestures over the shared section geometry, including supporting terrain and dependent props. */
@Tag("on-demand")
class GpuEditorSectionHeightSmokeTest {
    private record Fixture(Coords coords, String component, String field, int ground, int value, String terrain) { }

    @Test void dragsGroundAndStructureHeightsWithOneUndoPerGesture() throws Exception {
        Coords bridge = new Coords(2, 2);
        List<Fixture> fixtures = List.of(
              new Fixture(new Coords(3, 2), "building", "bldg_elev", 1, 3, "building:1;bldg_cf:15;bldg_elev:3"),
              new Fixture(new Coords(4, 2), "fuelTank", "fuel_tank_elev", 1, 2,
                    "fuel_tank:1;fuel_tank_cf:15;fuel_tank_elev:2;fuel_tank_magn:100"),
              new Fixture(new Coords(4, 3), "industry", "heavy_industrial", 0, 4, "heavy_industrial:4"),
              new Fixture(new Coords(3, 3), "vegetation", "foliage_elev", 1, 1, "woods:2;foliage_elev:1"));
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(6, 6);
            for (int x = 0; x < board.getWidth(); x++) {
                for (int y = 0; y < board.getHeight(); y++) { board.getHex(x, y).setTheme("lunar"); }
            }
            Hex hex = new Hex(2, "pavement:1;bridge:1:9;bridge_cf:40;bridge_elev:4", "lunar");
            hex.setDecorations(List.of(
                  new BoardDecoration("deck-car", "prop", "scenery/components/car-red", null, 0, 0, 0, false, .4,
                        BoardDecoration.Placement.surface("bridge", "deck", 0), 0),
                  new BoardDecoration("ground-car", "prop", "scenery/components/car-silver", null, 0, 0, 0, false, .4,
                        BoardDecoration.Placement.surface("ground", "top", .24), 0)));
            board.setHex(bridge, hex);
            for (Fixture fixture : fixtures) {
                board.setHex(fixture.coords(), new Hex(fixture.ground(), fixture.terrain(), "lunar"));
            }
            editor.game().setBoard(board); editor.pointer(bridge, 0, 0, false);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 120_000_000_000L;
                int step;
                int fixtureIndex;
                long nextFrame;
                float initialDeck, initialGround;
                boolean sectionFramed;

                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }

                private float anchor(String id) throws ReflectiveOperationException {
                    return terrain().editorObjects(bridge).stream().filter(o -> o.id().equals(id)).findFirst().orElseThrow().anchorLevel();
                }

                private void command(Action action, String value) {
                    source.editorCommand(new Command(action, value), source.takeFrame().boardGeneration());
                }

                private void next() { step++; nextFrame = frames() + 8; }

                @Override public void create() { super.create(); boardCamera.setIsometric(true); }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "Section height check stalled at step " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < nextFrame) { return; }
                        switch (step) {
                            case 0 -> {
                                initialDeck = anchor("deck-car"); initialGround = anchor("ground-car");
                                GpuHexSection section = showSection();
                                Vector2 point = componentPoint(section, terrain(), bridge, "bridge", 6);
                                drag(section, point, 2, false);
                                next();
                            }
                            case 1 -> {
                                assertEquals("bridge", source.editorState().component(), "The bridge mesh itself must be selectable in section");
                                assertEquals(6, source.editorState().property("bridge_elev").value());
                                assertEquals(initialDeck + 2, anchor("deck-car"), .05f, "The car follows the edited bridge deck");
                                assertEquals(initialGround, anchor("ground-car"), .05f, "The ground prop keeps its own support");
                                if (!sectionFramed) {
                                    showSection(); sectionFramed = true; nextFrame = frames() + 1; return;
                                }
                                GpuBoardTestUi.capture(new File(output, "editor-section-bridge-height.png"));
                                command(Action.UNDO, ""); next();
                            }
                            case 2 -> {
                                assertEquals(4, source.editorState().property("bridge_elev").value());
                                assertFalse(source.editorState().canUndo(), "Several drag previews form exactly one undo record");
                                command(Action.COMPONENT, "ground"); next();
                            }
                            case 3 -> {
                                GpuHexSection section = showSection();
                                drag(section, new Vector2(section.getWidth() - 18, height(section, 2)), 1, false); next();
                            }
                            case 4 -> {
                                assertEquals(3, source.editorState().elevation());
                                assertEquals(4, source.editorState().property("bridge_elev").value());
                                assertEquals(initialGround + 1, anchor("ground-car"), .05f);
                                assertEquals(initialDeck + 1, anchor("deck-car"), .05f);
                                command(Action.UNDO, ""); next();
                            }
                            case 5 -> {
                                assertEquals(2, source.editorState().elevation());
                                Fixture fixture = fixtures.get(fixtureIndex);
                                source.editorPointer(fixture.coords(), 0, 0, false, source.takeFrame().boardGeneration());
                                command(Action.COMPONENT, fixture.component()); next();
                            }
                            case 6 -> {
                                Fixture fixture = fixtures.get(fixtureIndex);
                                assertEquals(fixture.component(), source.editorState().component());
                                GpuHexSection section = showSection();
                                drag(section, new Vector2(section.getWidth() - 18,
                                      height(section, fixture.ground() + fixture.value())), 1, fixture.component().equals("industry"));
                                next();
                            }
                            case 7 -> {
                                Fixture fixture = fixtures.get(fixtureIndex);
                                assertEquals(fixture.value() + 1, source.editorState().property(fixture.field()).value(), fixture.component());
                                command(Action.UNDO, ""); next();
                            }
                            case 8 -> {
                                Fixture fixture = fixtures.get(fixtureIndex);
                                assertEquals(fixture.value(), source.editorState().property(fixture.field()).value());
                                assertFalse(source.editorState().canUndo());
                                if (++fixtureIndex < fixtures.size()) {
                                    fixture = fixtures.get(fixtureIndex);
                                    source.editorPointer(fixture.coords(), 0, 0, false, source.takeFrame().boardGeneration());
                                    command(Action.COMPONENT, fixture.component()); step = 6; nextFrame = frames() + 8;
                                } else {
                                    source.editorPointer(bridge, 0, 0, false, source.takeFrame().boardGeneration());
                                    command(Action.SELECT_OBJECT, "ground-car"); next();
                                }
                            }
                            case 9 -> {
                                GpuHexSection section = showSection();
                                var stage = GpuBoardTestUi.stage();
                                Vector2 point = stage.stageToScreenCoordinates(section.localToStageCoordinates(
                                      new Vector2(section.getWidth() - 18, height(section, anchor("ground-car")))));
                                var input = Gdx.input.getInputProcessor();
                                input.touchDown(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                                input.touchUp(Math.round(point.x), Math.round(point.y), 0, Input.Buttons.LEFT);
                                next();
                            }
                            case 10 -> {
                                assertEquals(.24, source.editorState().objects().stream().filter(o -> o.id().equals("ground-car"))
                                      .findFirst().orElseThrow().placement().offset());
                                assertFalse(source.editorState().canUndo(), "A click must not round or modify an existing precise offset");
                                Gdx.app.exit();
                            }
                            default -> throw new AssertionError("Unexpected section check step " + step);
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, configuration());
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Section height controls failed", failure.get()); }
    }

    private static GpuHexSection showSection() {
        var stage = GpuBoardTestUi.stage();
        var section = (GpuHexSection) stage.getRoot().findActor("editor-hex-section");
        var scroll = (ScrollPane) stage.getRoot().findActor("editor-inspector-scroll");
        assertNotNull(section); scroll.validate();
        Vector2 position = section.localToAscendantCoordinates(scroll.getActor(), new Vector2());
        scroll.scrollTo(position.x, position.y, section.getWidth(), section.getHeight(), false, true);
        scroll.updateVisualScroll(); stage.draw();
        return section;
    }

    private static float height(GpuHexSection section, float level) throws ReflectiveOperationException {
        var method = GpuHexSection.class.getDeclaredMethod("height", float.class); method.setAccessible(true);
        return (float) method.invoke(section, level);
    }

    private static Vector2 componentPoint(GpuHexSection section, GpuTerrain terrain, Coords coords,
          String component, float level) throws ReflectiveOperationException {
        var field = GpuHexSection.class.getDeclaredField("camera"); field.setAccessible(true);
        var camera = (OrthographicCamera) field.get(section);
        float target = height(section, level);
        for (int dy = 0; dy <= 12; dy++) {
            for (int sign : new int[] { -1, 1 }) {
                float y = target + sign * dy;
                for (float x = 36; x < section.getWidth() - 36; x += 3) {
                    var hit = terrain.editorSectionHit(coords, camera.getPickRay(x, Gdx.graphics.getHeight() - y,
                          0, 0, section.getWidth(), section.getHeight()));
                    if (hit != null && hit.object().isEmpty() && hit.component().equals(component)) { return new Vector2(x, y); }
                }
            }
        }
        throw new AssertionError("The installed section geometry must offer a " + component + " pick");
    }

    private static void drag(GpuHexSection section, Vector2 local, float levels, boolean cancel) throws ReflectiveOperationException {
        var stage = GpuBoardTestUi.stage();
        float pixels = height(section, levels) - height(section, 0);
        Vector2 start = stage.stageToScreenCoordinates(section.localToStageCoordinates(local.cpy()));
        Vector2 finish = stage.stageToScreenCoordinates(section.localToStageCoordinates(local.cpy().add(0, pixels)));
        var input = Gdx.input.getInputProcessor();
        input.touchDown(Math.round(start.x), Math.round(start.y), 0, Input.Buttons.LEFT);
        assertTrue(section.dragging(), "Clicking a height handle or surface starts its section gesture");
        input.touchDragged(Math.round(start.x), Math.round((start.y + finish.y) / 2), 0);
        input.touchDragged(Math.round(finish.x), Math.round(finish.y), 0);
        if (cancel) { stage.cancelTouchFocus(); }
        else { input.touchUp(Math.round(finish.x), Math.round(finish.y), 0, Input.Buttons.LEFT); }
        assertFalse(section.dragging());
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1920, 1080); return config;
    }
}
