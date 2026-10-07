/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.boardeditor.BoardEditorSession.Action;
import megamek.client.ui.boardeditor.BoardEditorSession.Command;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Render real sample thumbnails and drive the visual selectors through the same native input as the editor. */
@Tag("on-demand")
class GpuEditorVisualSmokeTest {
    @Test void previewsThemesAndMaterialsWithoutEditingUntilChosen() throws Exception {
        var setup = new FutureTask<GpuMapSource>(() -> {
            var editor = new BoardEditorSession();
            Board board = Board.createEmptyBoard(5, 5);
            board.setHex(new Coords(2, 2), new Hex(0, "road:1:9", ""));
            editor.game().setBoard(board); editor.pointer(new Coords(2, 2), 0, 0, false);
            editor.command(new Command(Action.COMPONENT, "road"), null);
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        File output = new File("build/gpu-board-review"); assertTrue(output.isDirectory() || output.mkdirs());
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000);
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 240_000_000_000L;
                int step;
                long after;
                @Override public void create() { super.create(); boardCamera.setIsometric(true); }
                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Visual editor stalled at step " + step);
                        if (GpuBoardTestUi.loading(this) || frames() < after) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        if (step == 0) {
                            if (!ready(root.findActor("editor-inspector"))) { return; }
                            assertNotEquals(pixels(root.findActor("editor-surface-1")), pixels(root.findActor("editor-surface-3")),
                                  "Linked road surfaces have distinct rendered previews");
                            GpuBoardTestUi.capture(new File(output, "editor-visual-road.png"));
                            GpuBoardTestUi.click("editor-settings-button"); next();
                        } else if (step == 1) {
                            if (!ready(root.findActor("editor-choice-Global theme"))) { return; }
                            GpuBoardTestUi.capture(new File(output, "editor-visual-settings.png"));
                            GpuBoardTestUi.click("editor-choice-Global theme"); next();
                            Gdx.graphics.setWindowedMode(1280, 800);
                        } else if (step == 2) {
                            Actor gallery = root.findActor("editor-theme-gallery"); assertTrue(gallery.isVisible());
                            if (!ready(gallery)) { return; }
                            List<Image> images = images(gallery).stream().filter(image -> image.getName() != null
                                  && image.getName().startsWith("editor-theme-preview-")).toList();
                            assertEquals(8, images.size());
                            assertEquals(8, images.stream().map(Image::getDrawable).distinct().count());
                            var scroll = (com.badlogic.gdx.scenes.scene2d.ui.ScrollPane) root.findActor("editor-theme-scroll");
                            assertEquals(0, scroll.getMaxY(), .1f, "All terrain themes must fit without scrolling");
                            var stage = GpuBoardTestUi.stage();
                            var start = scroll.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                            for (Image image : images) {
                                var point = image.localToStageCoordinates(new com.badlogic.gdx.math.Vector2(image.getWidth() / 2, image.getHeight() / 2));
                                Actor card = root.findActor(image.getName().replace("-preview", ""));
                                var corner = card.localToStageCoordinates(new com.badlogic.gdx.math.Vector2());
                                assertTrue(corner.x >= start.x - .5f && corner.y >= start.y - .5f, "No theme card is clipped");
                                assertTrue(corner.x + card.getWidth() <= start.x + scroll.getWidth() + .5f
                                      && corner.y + card.getHeight() <= start.y + scroll.getHeight() + .5f, "Every full card fits inside the gallery");
                                for (var listener : card.getListeners()) {
                                    assertFalse(listener instanceof com.badlogic.gdx.scenes.scene2d.ui.Tooltip<?>,
                                          "Visual choices must not repeat their labels in tooltips");
                                }
                                assertNotNull(stage.hit(point.x, point.y, true));
                                assertTrue(stage.hit(point.x, point.y, true).isDescendantOf(card), "Every theme is immediately clickable");
                            }
                            assertFalse(GpuBoardTestUi.texts(gallery).stream().anyMatch(text -> text.equals("PREVIOUS") || text.equals("NEXT")));
                            assertFalse(GpuBoardTestUi.texts(gallery).stream().anyMatch(text -> text.contains("plants  ·  cliff")));
                            assertNotNull(root.findActor("editor-theme-grass"));
                            assertNull(root.findActor("editor-theme-transparent"), "Transparency is not a terrain theme");
                            assertNull(root.findActor("editor-theme-"), "Default terrain is presented once as Grass");
                            assertFalse(GpuBoardTestUi.texts(gallery).stream().anyMatch(text -> text.contains("Default terrain")));
                            assertFalse(GpuBoardTestUi.shown(root.findActor("editor-theme-search")), "All themes are already visible");
                            GpuBoardTestUi.capture(new File(output, "editor-visual-themes.png"));
                            next();
                        } else if (step == 3) {
                            if (!ready(root.findActor("editor-theme-gallery"))) { return; }
                            assertNotNull(root.findActor("editor-theme-snow"));
                            GpuBoardTestUi.click("editor-theme-snow"); next();
                        } else if (step == 4) {
                            assertFalse(root.findActor("editor-theme-gallery").isVisible());
                            assertEquals("", source.editorState().theme(), "Browsing a global theme must not apply it");
                            GpuBoardTestUi.clickText("APPLY THEME TO ALL HEXES"); next();
                        } else if (step == 5) {
                            assertEquals("snow", source.editorState().theme());
                            GpuBoardTestUi.capture(new File(output, "editor-visual-snow.png"));
                            source.editorCommand(new Command(Action.UNDO), source.takeFrame().boardGeneration()); next();
                        } else if (step == 6) {
                            assertEquals("", source.editorState().theme());
                            Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
                            Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
                            assertFalse(root.findActor("editor-settings").isVisible());
                            GpuBoardTestUi.click("editor-library-Vehicles");
                            ((TextField) root.findActor("editor-search")).setText("car-red"); next();
                        } else if (step == 7) {
                            if (!ready(root.findActor("editor-asset-strip"))) { return; }
                            assertTrue(GpuBoardTestUi.shown(root.findActor("editor-search")), "The active filter must remain clearable");
                            GpuBoardTestUi.capture(new File(output, "editor-visual-props.png"));
                            GpuBoardTestUi.click("editor-library-scenery/components/car-red"); next();
                        } else if (step == 8) {
                            assertEquals("scenery/components/car-red", source.editorState().asset());
                            assertEquals(BoardEditorSession.Tool.PAINT, source.editorState().tool());
                            assertTrue(source.editorState().objects().isEmpty(), "Choosing a card does not place an object");
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
                private void next() { step++; after = frames() + 6; }
            }, config);
        } finally { SwingUtilities.invokeAndWait(source::close); }
        if (failure.get() != null) { throw new AssertionError("Visual editor failed", failure.get()); }
    }

    private static boolean ready(Actor actor) {
        List<Image> images = images(actor).stream().filter(image -> image.getName() != null && (image.getName().equals("editor-card-preview") || image.getName().startsWith("editor-theme-preview-"))).toList();
        assertFalse(images.isEmpty());
        for (Image image : images) {
            assertNotEquals("Preview unavailable", image.getUserObject());
            assertNotEquals("No preview", image.getUserObject());
            if (image.getDrawable() == null) { return false; }
        }
        return true;
    }
    private static int pixels(Actor card) {
        var drawable = (com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable) images(card).getFirst().getDrawable();
        var texture = drawable.getRegion().getTexture();
        var bytes = com.badlogic.gdx.utils.BufferUtils.newByteBuffer(texture.getWidth() * texture.getHeight() * 4);
        texture.bind();
        org.lwjgl.opengl.GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, bytes);
        return bytes.hashCode();
    }
    private static List<Image> images(Actor actor) {
        List<Image> result = new ArrayList<>();
        if (actor instanceof Image image) { result.add(image); }
        if (actor instanceof Group group) { for (Actor child : group.getChildren()) { result.addAll(images(child)); } }
        return result;
    }
}
