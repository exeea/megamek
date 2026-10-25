/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.Vector3;
import megamek.client.ui.boardeditor.BoardEditorSession;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.common.Configuration;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The editor's trace overlay over 48x48 (decision P of b9-decisions.md): a reference image drawn at one pixel per board
 * unit (hex outlines, a river and one hex filled magenta) lies on the hexes in the top view and is gone once the
 * camera tilts, and comes back with the top view. Captures {@code b9-trace-overlay-top.png} and
 * {@code b9-trace-overlay-tilted.png}.
 */
@Tag("on-demand")
class GpuEditorTraceOverlaySmokeTest {
    /** 48x48 at one pixel per board unit, with a one-hex margin on each side. */
    private static final int WIDTH = Math.round((2 + 48 * .75f + .25f) * BoardGeometry.TILE_WIDTH), HEIGHT = 50 * 72 + 36;
    /** The hex the reference image fills magenta, near the board's middle. */
    private static final Coords MARKED = new Coords(24, 24);

    @Test void theTraceImageShowsInTheTopViewOnlyAndLiesOnTheHexes() throws Exception {
        File output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"));
        assertTrue(output.isDirectory() || output.mkdirs());
        File reference = new File(output, "b9-trace-reference.png");
        ImageIO.write(reference(), "png", reference);
        GUIPreferences gui = GUIPreferences.getInstance();
        boolean show = gui.getShowTraceOverlay();
        String file = gui.getTraceOverlayImageFile();
        int opacity = gui.getTraceOverlayTransparency(), scale = gui.getTraceOverlayScale();
        int x = gui.getTraceOverlayOriginX(), y = gui.getTraceOverlayOriginY();
        var setup = new FutureTask<GpuMapSource>(() -> {
            // The offsets count from one hex left of and above the first hex, where the image's margin begins.
            gui.setTraceOverlayImageFile(reference.getAbsolutePath());
            gui.setTraceOverlayTransparency(200);
            gui.setTraceOverlayScale(100);
            gui.setTraceOverlayOriginX(0);
            gui.setTraceOverlayOriginY(0);
            gui.setValue(GUIPreferences.SHOW_TRACE_OVERLAY, true);
            var editor = new BoardEditorSession();
            editor.open(new File(Configuration.boardsDir(), "unofficial/VictorMorson/48x48.board").toPath());
            return new GpuMapSource(editor.game(), null, editor);
        });
        SwingUtilities.invokeAndWait(setup);
        GpuMapSource source = setup.get();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            new Lwjgl3Application(new GpuBattleView(source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int step;
                long nextFrame;

                private GpuTraceOverlay overlay() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("traceOverlay");
                    field.setAccessible(true);
                    return (GpuTraceOverlay) field.get(this);
                }

                private GpuTerrain terrain() throws ReflectiveOperationException {
                    var field = GpuBattleView.class.getDeclaredField("terrain");
                    field.setAccessible(true);
                    return (GpuTerrain) field.get(this);
                }

                /** How magenta the marked hex's centre shows on screen: the smaller of red and blue less green. */
                private int magenta() {
                    var scene = source.takeFrame().scene();
                    Vector3 centre = BoardGeometry.center(MARKED, scene.tile(MARKED).elevation());
                    boardCamera.camera.project(centre, 0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    Pixmap pixel = Pixmap.createFromFrameBuffer(Math.round(centre.x), Math.round(centre.y), 1, 1);
                    try {
                        int rgba = pixel.getPixel(0, 0);
                        int red = rgba >>> 24, green = rgba >>> 16 & 0xFF, blue = rgba >>> 8 & 0xFF;
                        return Math.min(red, blue) - green;
                    } finally { pixel.dispose(); }
                }

                @Override public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, () -> "The trace overlay check stalled at step " + step);
                        if (GpuBoardTestUi.loading(this) || terrain().busy() || frames() < nextFrame) { return; }
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var overlay = overlay();
                        if (step == 0) {
                            // The editor's top view, the whole board fitted, then a closer look at its middle.
                            boardCamera.setIsometric(false);
                            boardCamera.fit(source.takeFrame().scene());
                            boardCamera.zoom(.55f);
                            step++; nextFrame = frames() + 4;
                        } else if (step == 1) {
                            if (overlay.shown() < 1) { return; }
                            int shown = magenta();
                            GpuBoardTestUi.capture(new File(output, "b9-trace-overlay-top.png"));
                            assertTrue(shown > 80, "The marked hex shows the image's magenta in the top view: " + shown);
                            boardCamera.tilt(35);
                            step++; nextFrame = frames() + 2;
                        } else if (step == 2) {
                            if (overlay.shown() > 0) { return; }
                            int hidden = magenta();
                            GpuBoardTestUi.capture(new File(output, "b9-trace-overlay-tilted.png"));
                            assertTrue(hidden < 40, "Tilted, the hex shows its own terrain: " + hidden);
                            boardCamera.tilt(-35);
                            step++; nextFrame = frames() + 2;
                        } else if (step == 3) {
                            if (overlay.shown() < 1) { return; }
                            assertTrue(magenta() > 80, "Back in the top view, the image is back");
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }
            }, configuration());
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                source.close();
                gui.setTraceOverlayImageFile(file);
                gui.setTraceOverlayTransparency(opacity);
                gui.setTraceOverlayScale(scale);
                gui.setTraceOverlayOriginX(x);
                gui.setTraceOverlayOriginY(y);
                gui.setValue(GUIPreferences.SHOW_TRACE_OVERLAY, show);
            });
        }
        if (failure.get() != null) { throw new AssertionError("Trace overlay failed", failure.get()); }
    }

    /**
     * A reference map as a scan of the 2D board would be: every hex outline, a river, a road and the marked hex filled
     * magenta, on a transparent background.
     */
    private static BufferedImage reference() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(Color.MAGENTA);
            graphics.fill(hex(MARKED));
            graphics.setColor(new Color(20, 20, 60));
            graphics.setStroke(new BasicStroke(3));
            for (int column = 0; column < 48; column++) {
                for (int row = 0; row < 48; row++) { graphics.draw(hex(new Coords(column, row))); }
            }
            graphics.setColor(new Color(30, 110, 255));
            graphics.setStroke(new BasicStroke(26, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            graphics.drawPolyline(new int[] { 0, 700, 1300, 1800, 2400, WIDTH }, new int[] { 600, 900, 1500, 1400, 2300, 2600 }, 6);
            graphics.setColor(new Color(240, 200, 40));
            graphics.setStroke(new BasicStroke(14));
            graphics.drawLine(WIDTH / 2 - 400, 0, WIDTH / 2 + 300, HEIGHT);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 120));
            graphics.drawString("REFERENCE", 300, 400);
        } finally { graphics.dispose(); }
        return image;
    }

    /** A hex's outline in the image: past the margin, columns three quarters of a hex apart, odd ones half a hex lower. */
    private static Path2D hex(Coords hex) {
        float tile = BoardGeometry.TILE_WIDTH;
        float left = tile + hex.getX() * .75f * tile, top = 72 + hex.getY() * 72 + (hex.getX() & 1) * 36;
        var outline = new Path2D.Float();
        outline.moveTo(left + tile / 4, top);
        outline.lineTo(left + tile * .75f, top);
        outline.lineTo(left + tile, top + 36);
        outline.lineTo(left + tile * .75f, top + 72);
        outline.lineTo(left + tile / 4, top + 72);
        outline.lineTo(left, top + 36);
        outline.closePath();
        return outline;
    }

    private static com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration configuration() {
        var config = GpuBoardWindow.configuration(false); config.setWindowedMode(1600, 1000); return config;
    }
}
