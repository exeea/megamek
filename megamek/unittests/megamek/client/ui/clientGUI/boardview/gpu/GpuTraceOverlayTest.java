/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.board.Coords;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The board editor's trace overlay: it shows only in the top view, fading out when the camera tilts away and in again
 * when it returns, and it lies on the board at one image pixel per board unit.
 */
class GpuTraceOverlayTest {
    /** 48 × 48 drawn at one pixel per board unit with a one-hex margin on each side. */
    private static final int WIDTH = Math.round((2 + 48 * .75f + .25f) * BoardGeometry.TILE_WIDTH), HEIGHT = 50 * 72 + 36;

    @BeforeAll
    static void loadMathNatives() {
        GdxNativesLoader.load();
    }

    private static BoardCamera topView() {
        BoardCamera camera = new BoardCamera();
        camera.resize(1100, 750);
        camera.setIsometric(false);
        return camera;
    }

    @Test
    void onlyTheTopViewShowsTheImage() {
        BoardCamera camera = topView();
        assertTrue(GpuTraceOverlay.topView(camera), "Straight down");
        camera.orbit(60, 0);
        assertTrue(GpuTraceOverlay.topView(camera), "A turn still looks straight down");
        camera.tilt(GpuTraceOverlay.TILT_TOLERANCE / 2);
        assertTrue(GpuTraceOverlay.topView(camera), "Within the tolerance");
        camera.tilt(GpuTraceOverlay.TILT_TOLERANCE);
        assertFalse(GpuTraceOverlay.topView(camera), "Tilted away");
        camera.setIsometric(true);
        assertFalse(GpuTraceOverlay.topView(camera), "The isometric view");
        camera.tilt(-BoardCamera.MAX_TILT);
        assertTrue(GpuTraceOverlay.topView(camera), "Tilted back to straight down");
        camera.setFirstPerson(true);
        assertFalse(GpuTraceOverlay.topView(camera), "Free flight");
        camera.setFirstPerson(false);
        camera.setTactical(true, null);
        assertTrue(GpuTraceOverlay.topView(camera), "The Tactical View looks straight down");
    }

    @Test
    void theImageFadesOutWhenTheCameraTiltsAndInWhenItReturns() {
        BoardCamera camera = topView();
        GpuTraceOverlay overlay = new GpuTraceOverlay(null);
        // No file: the fade runs, and nothing is loaded or drawn.
        var settings = new GpuTraceOverlay.Settings(true, "", 200, 100, 0, 0);
        try {
            overlay.render(camera, settings, GpuTraceOverlay.FADE_SECONDS / 2);
            assertEquals(.5f, overlay.shown(), .001f);
            overlay.render(camera, settings, 1);
            assertEquals(1, overlay.shown());
            camera.tilt(10);
            overlay.render(camera, settings, GpuTraceOverlay.FADE_SECONDS / 2);
            assertEquals(.5f, overlay.shown(), .001f, "A quick fade");
            overlay.render(camera, settings, GpuTraceOverlay.FADE_SECONDS / 2);
            assertEquals(0, overlay.shown(), "Hidden while tilted");
            camera.tilt(-10);
            overlay.render(camera, settings, 1);
            assertEquals(1, overlay.shown(), "Back in the top view");
            overlay.render(camera, new GpuTraceOverlay.Settings(false, "", 200, 100, 0, 0), 1);
            assertEquals(0, overlay.shown(), "Switched off in the menu");
        } finally {
            overlay.dispose();
        }
    }

    /**
     * An image pixel at scale 100 % is a board unit and the offsets count from one hex left of and above hex 0101, as
     * the old 2D editor's did. So an image of the board with a one-hex margin placed at offset 0, 0 has each hex's
     * centre on that hex.
     */
    @Test
    void anImagePixelIsABoardUnit() {
        Rectangle area = GpuTraceOverlay.bounds(new GpuTraceOverlay.Settings(true, "map.png", 200, 100, 0, 0), WIDTH, HEIGHT);
        for (Coords hex : new Coords[] { new Coords(0, 0), new Coords(1, 0), new Coords(24, 24), new Coords(47, 47) }) {
            // The hex's centre in the image: past the margin, columns three quarters of a hex apart, odd ones lower.
            float tile = BoardGeometry.TILE_WIDTH;
            float x = tile + hex.getX() * .75f * tile + tile / 2, y = 72 + hex.getY() * 72 + (hex.getX() & 1) * 36 + 36;
            Vector3 centre = BoardGeometry.center(hex, 0);
            assertEquals(centre.x, area.x + x / WIDTH * area.width, .01f, hex + " east-west");
            assertEquals(centre.y, area.y + area.height - y / HEIGHT * area.height, .01f, hex + " north-south");
        }
        Rectangle half = GpuTraceOverlay.bounds(new GpuTraceOverlay.Settings(true, "map.png", 200, 50, 0, 0), WIDTH, HEIGHT);
        assertEquals(area.width / 2, half.width, .01f, "Scale 50 % halves the image about its top-left corner");
        assertEquals(area.x, half.x, .01f);
        assertEquals(area.y + area.height, half.y + half.height, .01f);
    }
}
