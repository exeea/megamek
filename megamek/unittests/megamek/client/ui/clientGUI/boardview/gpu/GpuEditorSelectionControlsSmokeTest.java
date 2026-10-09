/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.BoardDecoration;
import megamek.common.board.Coords;
import megamek.common.board.MaglevRoute;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Edit follows the selection independently of the library and leaves the separate paint template alone; the Brush panel
 * holds that template and hides in Select.
 */
@Tag("on-demand")
class GpuEditorSelectionControlsSmokeTest {
    @Test void editsSelectedInstancesAndHidesWithoutEditableSelection() throws Exception {
        Coords owner = new Coords(2, 2);
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(5, 5);
            board.setHex(owner, new Hex(0, "road:1:9", ""));
            board.getHex(owner).setDecorations(List.of(
                  new BoardDecoration("car", "prop", "scenery/vehicles/car", "Red car", -.2, 0, 60, false, 1.25,
                        BoardDecoration.Placement.surface("ground", "top", .5), 0, false, 18, -15),
                  new BoardDecoration("other", "prop", "scenery/vehicles/car", "Other car", .2, 0, -30, false, .75,
                        BoardDecoration.Placement.ground(), 0, false),
                  new BoardDecoration("paint", "decal", "decal/damage/rubble-light-path", "Ground marking", 0, 0, 25, false, 2,
                        BoardDecoration.Placement.ground(), 4, false),
                  new BoardDecoration("route", "prop", MaglevRoute.ASSET, "Maglev", 0, 0, 0, false, 1,
                        BoardDecoration.Placement.surface("ground", "top", 1), 0, false)));
            editor.game().setBoard(board);
            editor.command(new Command(Action.ASSET, "", "scenery/vehicles/car"), null);
            editor.command(new Command(Action.BRUSH_VALUE, "object:scale", "3"), null);
            editor.command(new Command(Action.BRUSH_VALUE, "object:rotation", "120"), null);
            editor.command(new Command(Action.TOOL, "", "SELECT"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup); GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1280, 800); config.useVsync(false);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 150_000_000_000L;
                int step; long after;
                /** Set by the EDT once it has run every command the last step sent; edits apply there, in order. */
                java.util.concurrent.atomic.AtomicBoolean applied = new java.util.concurrent.atomic.AtomicBoolean(true);
                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain"); field.setAccessible(true); return (GpuTerrain) field.get(this);
                }
                private Group edit() { return GpuBoardTestUi.stage().getRoot().findActor("editor-details"); }
                private Group brush() { return GpuBoardTestUi.stage().getRoot().findActor("editor-brush"); }
                private void command(Action action, String target, String value) {
                    source.editorCommand(new Command(action, target, value), source.takeFrame().boardGeneration());
                }
                private BoardDecoration object(String id) {
                    return source.editorState().objects().stream().filter(object -> object.id().equals(id)).findFirst().orElseThrow();
                }
                private void value(String label, double expected) { value(edit(), label, expected); }
                private void value(Group panel, String label, double expected) {
                    TextField field = panel.findActor("editor-" + label); assertNotNull(field, label);
                    assertEquals(expected, Double.parseDouble(field.getText()), .0001, label);
                }
                private void type(String label, String text) { type(edit(), label, text); }
                private void type(Group panel, String label, String text) {
                    TextField field = panel.findActor("editor-" + label); assertNotNull(field, label);
                    GpuBoardTestUi.stage().setKeyboardFocus(field); field.setText(text); GpuBoardTestUi.stage().setKeyboardFocus(null);
                }
                private void selected(String name) {
                    assertFalse(brush().isVisible(), "Select has no brush options");
                    assertTrue(GpuBoardTestUi.texts(edit()).contains(megamek.client.ui.gdx.UiTheme.upper(name)));
                    assertNull(edit().findActor("editor-precision"));
                    assertNull(edit().findActor("editor-magnetic"));
                    GpuBoardTestUi.assertHorizontalBounds(edit(), edit());
                }
                /** Edit shows no object: no scale, rotation or height of one. */
                private void noObject() { assertNull(edit().findActor("editor-Scale")); assertNull(edit().findActor("editor-Z")); }
                private void unchangedBrush(double scale) {
                    assertEquals(scale, source.editorState().activeBrush().object().scale());
                    assertEquals(120, source.editorState().activeBrush().object().rotation());
                }
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Selection controls timed out at " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < after) { return; }
                        // Then ten frames take the snapshot those commands published and rebuild the panels.
                        if (!applied.get()) { return; }
                        if (after < 0) { after = frames() + 10; return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        switch (step) {
                            case 0 -> {
                                assertNull(source.editorState().selected()); assertFalse(brush().isVisible()); noObject();
                                assertNull(GpuBoardTestUi.stage().getRoot().findActor("editor-section-panel"),
                                      "At 1280x800 the board area is too narrow for both corner panels: the side view starts collapsed");
                                GpuBoardTestUi.category("Vehicles");
                                source.editorPointer(owner, 0, 0, false, source.takeFrame().boardGeneration()); source.endEditorStroke();
                            }
                            case 1 -> {
                                noObject();
                                GpuBoardTestUi.click("editor-content-car");
                            }
                            case 2 -> {
                                // The rotation inputs read clockwise; the board stores counter-clockwise turns.
                                selected("Red car"); value("Scale", 1.25); value("X", -18); value("Y", 15); value("Z", -60);
                                type("Scale", "2"); type("X", "25"); type("Y", "-30"); type("Z", "45"); type("Height above surface", "1.5");
                                GpuBoardTestUi.click("editor-colour-0-0"); // The paint slot's first preset: silver.
                                GpuBoardTestUi.click("editor-object-mirror");
                            }
                            case 3 -> {
                                var car = object("car"); assertEquals(2, car.scale()); assertEquals(-25, car.rotationX());
                                assertEquals(30, car.rotationY()); assertEquals(-45, car.rotation());
                                assertEquals(1.5, car.placement().offset()); assertTrue(car.mirror()); unchangedBrush(3);
                                assertEquals("#b8b8b8", car.colours().slot(0), "A swatch replaces the car's paint");
                                command(Action.UNDO, "", "");
                            }
                            case 4 -> { assertFalse(object("car").mirror()); command(Action.REDO, "", ""); }
                            case 5 -> {
                                assertTrue(object("car").mirror()); selected("Red car");
                                GpuBoardTestUi.capture(new File(output, "editor-selected-prop-controls.png"));
                                GpuBoardTestUi.click("editor-content-other");
                            }
                            case 6 -> {
                                selected("Other car"); value("Scale", .75); value("Z", 30); value("Height above surface", 0);
                                TextField field = edit().findActor("editor-Scale");
                                GpuBoardTestUi.stage().setKeyboardFocus(field); field.setText("0.9");
                                GpuBoardTestUi.click("editor-tool-paint"); // Blur commits to the selected instance before switching modes.
                            }
                            case 7 -> {
                                assertEquals(.9, object("other").scale()); unchangedBrush(3);
                                assertTrue(brush().isVisible()); assertNotNull(brush().findActor("editor-precision"));
                                value(brush(), "Scale", 3); value(brush(), "Z", -120);
                                type(brush(), "Scale", "4");
                            }
                            case 8 -> {
                                unchangedBrush(4); assertEquals(.9, object("other").scale());
                                GpuBoardTestUi.click("editor-tool-select");
                            }
                            case 9 -> { selected("Other car"); value("Scale", .9); command(Action.COMPONENT, "", "road"); }
                            case 10 -> { noObject(); GpuBoardTestUi.click("editor-content-paint"); }
                            case 11 -> {
                                selected("Ground marking"); value("Paint order", 4); value("Scale", 2);
                                assertNull(edit().findActor("editor-Height above surface")); type("Paint order", "7");
                                GpuBoardTestUi.click("editor-object-span");
                            }
                            case 12 -> {
                                assertEquals(7, object("paint").drawOrder()); assertTrue(object("paint").clipToHex()); unchangedBrush(4);
                                GpuBoardTestUi.capture(new File(output, "editor-selected-decal-controls.png"));
                                command(Action.CLEAR_SELECTION, "", "");
                            }
                            case 13 -> { noObject(); GpuBoardTestUi.click("editor-content-car"); }
                            case 14 -> { selected("Red car"); GpuBoardTestUi.click("editor-remove-car"); }
                            case 15 -> { noObject(); unchangedBrush(4); GpuBoardTestUi.click("editor-content-route"); }
                            case 16 -> {
                                selected("Maglev"); assertNull(edit().findActor("editor-Scale")); assertNull(edit().findActor("editor-X"));
                                value("Height above ground", 1); type("Height above ground", "2");
                            }
                            case 17 -> {
                                assertEquals(2, object("route").placement().offset()); unchangedBrush(4);
                                command(Action.COMPONENT, "", "ground");
                            }
                            case 18 -> { noObject(); Gdx.app.exit(); return; }
                            default -> throw new AssertionError(step);
                        }
                        step++; after = -1; applied = new java.util.concurrent.atomic.AtomicBoolean();
                        var marker = applied; SwingUtilities.invokeLater(() -> marker.set(true));
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Selection controls", failure.get()); }
    }
}
