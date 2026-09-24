/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.Disposable;

/**
 * Review captures drawn the way the board draws a frame (see {@link GpuBattleView}): the terrain through the atmosphere's
 * scene target, fog and composite, then read back from the screen. Sun glare is left out, so the captures show the light
 * alone. Owns its atmosphere; create it on the GL thread.
 */
final class GpuReviewFrame implements Disposable {
    private static final GpuAtmosphere.Options OPTIONS = new GpuAtmosphere.Options(GpuAtmosphere.Options.DEFAULTS.rays(),
          false, GpuClouds.MIN_SHADOW_STRENGTH, GpuClouds.MAX_SHADOW_STRENGTH, 0);
    private final GpuAtmosphere atmosphere = new GpuAtmosphere();

    GpuReviewFrame(BoardAtmosphere.Settings settings) {
        atmosphere.setOptions(OPTIONS);
        configure(settings);
    }

    /** Changes the conditions for the next frames, so a series of captures shares one atmosphere. */
    void configure(BoardAtmosphere.Settings settings) {
        atmosphere.configure(settings);
    }

    BoardAtmosphere.Lighting lighting() {
        return atmosphere.lighting();
    }

    /** One frame of the scene from the camera, lit, shadowed and composited as on the board. */
    void render(GpuTerrain terrain, BoardCamera camera, BoardScene scene) {
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        terrain.renderShadows(camera.camera, List.of());
        atmosphere.prepareClouds(terrain, scene, 0);
        atmosphere.begin((int) camera.camera.viewportWidth, (int) camera.camera.viewportHeight, 0);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0);
        atmosphere.renderWeather(camera.camera, scene);
    }

    /** Saves the screen as an opaque PNG. */
    static void save(File file) {
        int width = Gdx.graphics.getBackBufferWidth(), height = Gdx.graphics.getBackBufferHeight();
        Pixmap image = Pixmap.createFromFrameBuffer(0, 0, width, height);
        try {
            ByteBuffer pixels = image.getPixels();
            for (int i = 3; i < pixels.limit(); i += 4) { pixels.put(i, (byte) 255); }
            PixmapIO.writePNG(new FileHandle(file), image, -1, true);
        } finally {
            image.dispose();
        }
    }

    @Override
    public void dispose() {
        atmosphere.dispose();
    }
}
