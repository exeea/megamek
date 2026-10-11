/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.Messages;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Hex;
import megamek.common.board.Board;
import megamek.common.board.Coords;
import megamek.common.game.Game;
import megamek.common.planetaryConditions.Fog;
import megamek.common.planetaryConditions.Weather;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The preview and editor print through their live renderer without changing its camera or label preferences. */
@Tag("on-demand")
class GpuBoardPrintSmokeTest {
    @Test
    void previewAndEditorExportWholeBoardWithOutlinedLabelsFromAnAngledCloseView() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires an OpenGL window");
        GUIPreferences preferences = GUIPreferences.getInstance();
        boolean coordinates = preferences.getCoordsEnabled();
        boolean types = preferences.getHexTypesEnabled();
        boolean outlined = preferences.getHexLabelOutline();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            preferences.setCoordsEnabled(false);
            preferences.setHexTypesEnabled(false);
            preferences.setHexLabelOutline(false);
        });
        try {
            var configuration = GpuBoardWindow.configuration(false);
            configuration.setWindowedMode(960, 720);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try {
                        for (boolean editor : List.of(false, true)) { checkPrint(editor); }
                    } catch (Throwable error) {
                        failure.set(error);
                    } finally {
                        Gdx.app.exit();
                    }
                }
            }, configuration);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                preferences.setCoordsEnabled(coordinates);
                preferences.setHexTypesEnabled(types);
                preferences.setHexLabelOutline(outlined);
            });
        }
        if (failure.get() != null) { throw new AssertionError("Native 3D board print", failure.get()); }
    }

    private static void checkPrint(boolean editing) throws Exception {
        AtomicReference<GpuMapSource> captured = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            BoardEditorSession editor = editing ? new BoardEditorSession() : null;
            Game game = editor == null ? new Game() : editor.game();
            Hex[] hexes = new Hex[12 * 9];
            for (int y = 0; y < 9; y++) {
                for (int x = 0; x < 12; x++) {
                    // The asymmetric raised copse makes the saved north-up board visually distinguishable from
                    // the live isometric close-up and exposes any image-readback inversion.
                    boolean woods = x == 3 && y == 2;
                    hexes[y * 12 + x] = new Hex(woods ? 2 : 0,
                          woods ? "woods:1;foliage_elev:2" : "pavement:1", "grass", new Coords(x, y));
                }
            }
            game.setBoard(new Board(12, 9, hexes));
            game.getPlanetaryConditions().setWeather(Weather.HEAVY_RAIN);
            game.getPlanetaryConditions().setFog(Fog.FOG_HEAVY);
            captured.set(new GpuMapSource(game, null, editor));
        });
        GpuMapSource source = captured.get();
        GpuBattleView view = new GpuBattleView(source);
        try {
            view.create();
            GpuBoardTestUi.present(view);
            GpuBoardTestUi.click("utility-menu");
            view.render();
            assertTrue(GpuBoardTestUi.texts(GpuBoardTestUi.stage().getRoot().findActor("menu-panel"))
                  .contains(Messages.getString("CommonMenuBar.PrintMenu")), "The native Menu offers Print");
            Gdx.input.getInputProcessor().keyDown(Input.Keys.ESCAPE);
            Gdx.input.getInputProcessor().keyUp(Input.Keys.ESCAPE);
            view.render();
            BoardCamera camera = view.boardCamera;
            camera.advance(BoardCamera.ENTRANCE_SECONDS);
            camera.setIsometric(true);
            camera.center(BoardGeometry.center(new Coords(3, 2), 2));
            camera.zoom(.3f);
            view.render();
            float[] projection = camera.camera.combined.val.clone();
            Vector3 focus = camera.focus.cpy();
            assertFalse(camera.isTopDown(), "The live camera starts angled and zoomed into part of the board");
            assertTrue(source.takeFrame().scene().tile(new Coords(0, 0)).text().isEmpty(),
                  "The live scene starts with coordinates and terrain types disabled");
            BoardAtmosphere.Settings weather = source.takeFrame().scenarioAtmosphere();
            assertTrue(weather.effects().rain() > 0 && weather.fog() > 0,
                  "The live board supplies rain and fog that must be excluded from printing");

            AtomicReference<BufferedImage> printed = new AtomicReference<>();
            AtomicReference<Throwable> failed = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> source.requestPrint(image -> {
                if (!SwingUtilities.isEventDispatchThread()) {
                    failed.set(new AssertionError("The completion must return to the map's EDT owner"));
                }
                printed.set(image);
            }, failed::set));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (printed.get() == null && failed.get() == null) {
                assertTrue(System.nanoTime() < deadline, "Printing must complete while the view continues to render");
                view.render();
                SwingUtilities.invokeAndWait(() -> { });
            }
            if (failed.get() != null) { throw new AssertionError("Print rendering failed", failed.get()); }
            BufferedImage image = printed.get();
            assertNotNull(image);
            File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
            assertTrue(output.isDirectory() || output.mkdirs());
            File outputFile = new File(output, editing ? "board-print-editor.png" : "board-print-preview.png");
            GpuMapSource.writePrint(image, outputFile.toPath(), GpuBoardPrint.PIXELS_PER_METRE);
            BufferedImage saved = ImageIO.read(outputFile);
            assertEquals(2775, image.getWidth(), "Twelve columns at 1.125-inch pitch plus the edge, at 200 DPI");
            assertEquals(2470, image.getHeight(), "Nine 1.3-inch rows plus the staggered half hex, at 200 DPI");
            for (int y : List.of(100, 1100, 2100)) {
                assertEquals(0, image.getRGB(0, y) >>> 24,
                      "The background stays transparent across framebuffer tile boundaries");
                assertEquals(0, saved.getRGB(0, y) >>> 24, "PNG output preserves the transparent background");
            }
            assertEquals(255, image.getRGB(150, 130) >>> 24, "The board itself stays opaque");
            assertEquals(image.getRGB(150, 130), saved.getRGB(150, 130), "PNG output preserves the board colour and alpha");
            assertOpaque(image, 100, 10, 100, 100); // The coordinate label and its antialiased outline.
            assertOpaque(image, 110, 245, 80, 30); // The horizontal grid edge between the first two rows.
            assertTrue(image.getWidth() > Gdx.graphics.getWidth(), "Print resolution is independent of window width");
            assertTrue(image.getHeight() > Gdx.graphics.getHeight(), "Print resolution is independent of window height");
            // Hex 0403: three 225-pixel column steps, two 260-pixel rows and the odd column's half-row offset.
            assertTrue(greenPixels(image, 675, 650, 300, 260) > 1_000,
                  "The raised woods must be at its north-up mapsheet position with the printed hex pitch");
            assertEquals(0, greenPixels(image, 675, image.getHeight() - 910, 300, 260),
                  "Framebuffer readback must not flip the northern woods into the southern pavement");
            for (int row = 0; row < 2; row++) {
                for (int column = 0; column < 2; column++) {
                    assertTrue(outlinedInk(image, column, row) > 40,
                          "Outlined labels must reach every corner of the full board: " + column + ", " + row);
                }
            }
            assertArrayEquals(projection, camera.camera.combined.val, .0001f, "Printing preserves the live camera");
            assertEquals(focus, camera.focus, "Printing preserves the live focus");
            assertFalse(camera.isTopDown());
            assertEquals(weather, source.takeFrame().scenarioAtmosphere(), "Printing preserves the live board's weather");
            SwingUtilities.invokeAndWait(() -> {
                GUIPreferences preferences = GUIPreferences.getInstance();
                assertFalse(preferences.getCoordsEnabled());
                assertFalse(preferences.getHexTypesEnabled());
                assertFalse(preferences.getHexLabelOutline());
                assertTrue(source.takeFrame().scene().tile(new Coords(0, 0)).text().isEmpty(),
                      "The print-only labels must not leak into the live scene");
            });
            // The offscreen job borrows assets; a normal frame after it finishes must still use valid resources.
            view.render();
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            GpuBoardTestUi.capture(new File(output,
                  editing ? "board-print-editor-live.png" : "board-print-preview-live.png"));
        } finally {
            view.dispose();
            SwingUtilities.invokeAndWait(source::close);
        }
    }

    private static void assertOpaque(BufferedImage image, int left, int top, int width, int height) {
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                assertEquals(255, image.getRGB(x, y) >>> 24,
                      "Grid and label antialiasing must preserve the opaque board at " + x + ", " + y);
            }
        }
    }

    private static int greenPixels(BufferedImage image, int left, int top, int width, int height) {
        int count = 0;
        for (int y = top; y < top + height; y++) {
            for (int x = left; x < left + width; x++) {
                int pixel = image.getRGB(x, y), red = pixel >> 16 & 255, green = pixel >> 8 & 255, blue = pixel & 255;
                if (green > red + 8 && green > blue + 8) { count++; }
            }
        }
        return count;
    }

    /** Opaque black glyph ink immediately beside its white outline. */
    private static int outlinedInk(BufferedImage image, int column, int row) {
        int left = column == 0 ? image.getWidth() / 30 : image.getWidth() * 3 / 4;
        int right = column == 0 ? image.getWidth() / 4 : image.getWidth() * 29 / 30;
        int top = row == 0 ? image.getHeight() / 30 : image.getHeight() * 3 / 4;
        int bottom = row == 0 ? image.getHeight() / 4 : image.getHeight() * 29 / 30;
        int ink = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int pixel = image.getRGB(x, y);
                if ((pixel >>> 24) != 255 || (pixel >> 16 & 255) > 20 || (pixel >> 8 & 255) > 20
                      || (pixel & 255) > 20) { continue; }
                boolean outline = false;
                for (int dy = -2; dy <= 2 && !outline; dy++) {
                    for (int dx = -2; dx <= 2 && !outline; dx++) {
                        int neighbour = image.getRGB(x + dx, y + dy);
                        outline = (neighbour >> 16 & 255) >= 240 && (neighbour >> 8 & 255) >= 240
                              && (neighbour & 255) >= 240;
                    }
                }
                if (outline) { ink++; }
            }
        }
        return ink;
    }
}
