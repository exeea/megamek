/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.GdxRuntimeException;
import megamek.client.ui.clientGUI.GUIPreferences;
import megamek.client.ui.tileset.HexTileset;
import megamek.logging.MMLogger;

/**
 * The board editor's trace overlay: a translucent reference image, such as a scanned map to trace, laid over the board
 * from the Trace settings of the Client Settings. It is a view setting, never part of the board. The image fits the
 * hexes only when looking straight down, so it shows only in the top view ({@link #topView}) and fades out as soon as
 * the camera tilts away, and in again when it returns. It lies where the old 2D editor drew it at 100 % zoom: an image
 * pixel at scale 100 % is a pixel of the 2D board, whose hexes are 84 × 72, and the offsets count from that board's
 * corner, one hex left of and above the first hex. One pass after the scene, under the editor's markers. Render thread.
 */
final class GpuTraceOverlay implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuTraceOverlay.class);
    /** Degrees of tilt from straight down within which the camera still shows the top view. */
    static final float TILT_TOLERANCE = 2;
    /** Seconds the image takes to fade fully in or out. */
    static final float FADE_SECONDS = .15f;

    /**
     * The Trace settings ({@link GUIPreferences}), copied on the Swing thread: shown or not, the image file, the opacity
     * 0 to 255, the scale in percent and the offsets in 2D board pixels.
     */
    record Settings(boolean enabled, String file, int opacity, int scale, int x, int y) {
        static final Settings NONE = new Settings(false, "", 0, 100, 0, 0);
        /** The preferences the settings copy; a change of one changes no board art. */
        static final Set<String> PREFERENCES = Set.of(GUIPreferences.SHOW_TRACE_OVERLAY,
              GUIPreferences.TRACE_OVERLAY_IMAGE_FILE, GUIPreferences.TRACE_OVERLAY_TRANSPARENCY,
              GUIPreferences.TRACE_OVERLAY_SCALE, GUIPreferences.TRACE_OVERLAY_ORIGIN_X,
              GUIPreferences.TRACE_OVERLAY_ORIGIN_Y);

        static Settings capture() {
            GUIPreferences gui = GUIPreferences.getInstance();
            return new Settings(gui.getShowTraceOverlay(), gui.getTraceOverlayImageFile(),
                  gui.getTraceOverlayTransparency(), gui.getTraceOverlayScale(), gui.getTraceOverlayOriginX(),
                  gui.getTraceOverlayOriginY());
        }
    }

    private SpriteBatch batch;
    private Texture texture;
    /** The file {@link #texture} holds, or that failed to load, in which case the texture is null. */
    private String loaded;
    /** How far the image has faded in: 0 hidden, 1 shown. */
    private float shown;

    /** The only view in which the image fits the hexes: straight down, within {@link #TILT_TOLERANCE}. */
    static boolean topView(BoardCamera camera) {
        return camera.isTopDown(TILT_TOLERANCE);
    }

    /** Where an image of {@code width} × {@code height} pixels lies on the board, in world units (y points north). */
    static Rectangle bounds(Settings settings, int width, int height) {
        float pixel = BoardGeometry.hexScale(), scale = settings.scale() / 100f;
        float w = width * scale * pixel, h = height * scale * pixel;
        float left = (settings.x() - HexTileset.HEX_W) * pixel, top = -(settings.y() - HexTileset.HEX_H) * pixel;
        return new Rectangle(left, top - h, w, h);
    }

    /** Fades towards shown while enabled in the top view, otherwise towards hidden, and draws the image over the scene. */
    void render(BoardCamera camera, Settings settings, float delta) {
        boolean wanted = settings.enabled() && topView(camera);
        shown = MathUtils.clamp(shown + (wanted ? delta : -delta) / FADE_SECONDS, 0, 1);
        if (shown == 0 || settings.opacity() <= 0 || !load(settings.file())) {
            return;
        }
        if (batch == null) {
            batch = new SpriteBatch(1);
        }
        Rectangle area = bounds(settings, texture.getWidth(), texture.getHeight());
        // Over the terrain, units and effects at every height; the markers, grid and labels drawn later stay above it.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glDepthMask(false);
        try {
            batch.setProjectionMatrix(camera.camera.combined);
            batch.setColor(1, 1, 1, Math.min(255, settings.opacity()) / 255f * shown);
            batch.begin();
            batch.draw(texture, area.x, area.y, area.width, area.height);
            batch.end();
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    /** Loads the image file once per change of the setting; a file that does not load is reported once and skipped. */
    private boolean load(String file) {
        if (!file.equals(loaded)) {
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
            loaded = file;
            if (!file.isBlank()) {
                try {
                    texture = new Texture(Gdx.files.absolute(new File(file).getAbsolutePath()), true);
                    texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
                } catch (GdxRuntimeException failure) {
                    LOGGER.warn("The trace overlay image {} could not be loaded: {}", file, failure.getMessage());
                }
            }
        }
        return texture != null;
    }

    /** How far the image has faded in, 0 hidden to 1 shown. */
    float shown() {
        return shown;
    }

    @Override
    public void dispose() {
        if (texture != null) {
            texture.dispose();
            texture = null;
        }
        loaded = null;
        if (batch != null) {
            batch.dispose();
            batch = null;
        }
    }
}
