/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.Messages;
import megamek.common.planetaryConditions.Atmosphere;
import megamek.common.planetaryConditions.AtmosphericTaint;

/** A whole-board print snapshot, rendered in bounded tiles with the board's existing 3D renderer and assets. */
final class GpuBoardPrint implements Disposable {
    /**
     * Official mapsheet hex: 1.5 inches corner-to-corner by 1.3 inches flat-to-flat, at 200 DPI.
     * The 18-by-22-inch map on page 2 of Catalyst's BattleForce MapPack preview has a 1.125-inch column pitch
     * and a 1.3-inch row pitch: https://d1vzi28wh99zvq.cloudfront.net/pdf_previews/114190-sample.pdf.
     * The JPEG states this density; print at 100%, without fit-to-page scaling.
     */
    static final int HEX_WIDTH_PIXELS = 300;
    static final int PIXELS_PER_HEX = 260;
    static final int DPI = 200;
    private static final int TILE_SIZE = 1024;
    /** The JPEG writer's (libjpeg's) largest side. */
    private static final int MAX_SIDE = 65500;

    /** Captured on the EDT. Only the render thread consumes the scene; callbacks marshal their own results. */
    record Request(BoardScene scene, Consumer<BufferedImage> completed, Consumer<Exception> failed) { }

    private final Request request;
    private final GpuTerrain lender;
    private final BitmapFont font, outline;
    private final BoardAtmosphere.Settings lighting;
    /** The view's air pressure: the print's lighting air is standard, its landscape the view's. */
    private final boolean vacuum;
    private final GpuAtmosphere.Options lightingOptions;
    private final BoardCamera camera = new BoardCamera();
    private GpuTerrain terrain;
    private GpuAtmosphere atmosphere;
    private GpuHexText text;
    private GpuHexGrid grid;
    private SpriteBatch batch;
    private FrameBuffer target;
    private BufferedImage image;
    private int x, y;
    private float zoomX, zoomY;
    private boolean finished;

    GpuBoardPrint(Request request, GpuTerrain lender, BitmapFont font, BitmapFont outline,
          BoardAtmosphere.Settings conditions, GpuAtmosphere.Options lightingOptions) {
        this.request = request;
        this.lender = lender;
        this.font = font;
        this.outline = outline;
        lighting = lightingOnly(conditions);
        vacuum = conditions.pressure().isVacuum();
        this.lightingOptions = lightingOptions;
    }

    /** Keep the chosen sun/moon and exposure, with clear, dry air and no animated weather or cloud shadows. */
    static BoardAtmosphere.Settings lightingOnly(BoardAtmosphere.Settings conditions) {
        return new BoardAtmosphere.Settings(conditions.hour(), 0, 0, BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT,
              0, conditions.exposure(), BoardAtmosphere.Effects.NONE, Atmosphere.STANDARD, 25,
              conditions.moonlight(), AtmosphericTaint.BREATHABLE, conditions.gravity());
    }

    /** Builds progressively, then reads one raster tile per frame. Never changes the live view's camera or terrain. */
    boolean update() {
        if (finished) { return true; }
        try {
            if (terrain == null) { initialize(); }
            terrain.refine(camera.camera);
            if (terrain.busy() || !terrain.ready(request.scene())) { return false; }
            try (TerrainSettings.Scope ignored = TerrainSettings.use(terrain.settings())) {
                renderTile();
            }
            if (y < image.getHeight()) { return false; }
            BufferedImage result = image;
            finished = true;
            dispose();
            request.completed().accept(result);
        } catch (RuntimeException failure) {
            finished = true;
            dispose();
            request.failed().accept(failure);
        } finally {
            FrameBuffer.unbind();
            Gdx.gl.glViewport(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }
        return true;
    }

    private void initialize() {
        BoardScene scene = request.scene();
        long width = ((long) scene.width() * 3 + 1) * HEX_WIDTH_PIXELS / 4;
        long height = ((long) scene.height() * 2 + 1) * PIXELS_PER_HEX / 2;
        // The only limits are the JPEG's side and one Java array of 3 bytes per pixel.
        if (width > MAX_SIDE || height > MAX_SIDE || width * height * 3 > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException(Messages.getString("GpuBoard.print.tooLarge", width, height));
        }
        try {
            image = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_3BYTE_BGR);
        } catch (OutOfMemoryError failure) {
            throw new IllegalStateException(Messages.getString("GpuBoard.print.memory", width * height * 3 >> 20),
                  failure);
        }
        terrain = new GpuTerrain(lender);
        // The live view's 3D Graphics (it is ready, so they are installed); a print keeps the fluff of the 3D view.
        terrain.setColumns(lender.columns());
        terrain.setGravity(lighting.gravity());
        terrain.setVacuum(vacuum);
        atmosphere = new GpuAtmosphere();
        atmosphere.configure(lighting);
        atmosphere.setOptions(lightingOptions);
        // Transparent SPACE hexes show the space sky they show on screen; elsewhere the empty background is the paper.
        atmosphere.setTransparentBackground(!atmosphere.spaceBackdrop(scene));
        text = new GpuHexText();
        grid = new GpuHexGrid();
        batch = new SpriteBatch();
        batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE,
              GL20.GL_ONE_MINUS_SRC_ALPHA);
        zoomX = BoardGeometry.width() / HEX_WIDTH_PIXELS;
        zoomY = BoardGeometry.height() / PIXELS_PER_HEX;
        // The full-board camera fixes build detail at print resolution, independently of the window and its DPI.
        camera((int) width, (int) height, 0, 0);
        atmosphere.updateLight(camera.camera);
        terrain.setAtmosphere(atmosphere.lighting());
        atmosphere.configureClouds(terrain, scene);
        terrain.update(scene, camera.camera);
    }

    private void camera(int width, int height, int left, int top) {
        var view = camera.camera;
        view.perspective = false;
        // The game's 84-by-72 artwork lattice differs slightly from the printed mapsheet's aspect ratio.
        view.viewportWidth = width * zoomX / zoomY;
        view.viewportHeight = height;
        view.zoom = zoomY;
        view.position.set((left + width / 2f) * zoomX, -(top + height / 2f) * zoomY, 50000);
        view.direction.set(0, 0, -1);
        view.up.set(0, 1, 0);
        view.update();
    }

    private void renderTile() {
        // Use the completed build's geometry even if tuning was changed while that build was pending.
        zoomX = BoardGeometry.width() / HEX_WIDTH_PIXELS;
        zoomY = BoardGeometry.height() / PIXELS_PER_HEX;
        int width = Math.min(TILE_SIZE, image.getWidth() - x);
        int height = Math.min(TILE_SIZE, image.getHeight() - y);
        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            if (target != null) { target.dispose(); }
            target = GpuAtmosphere.buffer(width, height, true);
        }
        camera(width, height, x, y);
        BoardScene scene = terrain.presentation(request.scene());
        // A draw whose program still links skips this tile (GpuShaderProvider): it is drawn again next frame.
        int skipped = GpuShaderProvider.unlinkedDraws();
        // No hover cutaway, editor selection, trace image or HUD belongs to a printed board.
        terrain.animate(0, List.of(), 1);
        terrain.renderShadows(List.of());
        atmosphere.setPrintTile(x, y, width, height, image.getWidth(), image.getHeight());
        atmosphere.beginPixels(width, height, 0);
        terrain.render(camera.camera, false);
        terrain.renderTransparent(camera.camera);
        atmosphere.end(camera.camera, terrain, scene, 0, null, false, target);
        if (GpuShaderProvider.unlinkedDraws() != skipped) { return; }
        target.begin();
        try {
            grid.render(camera, scene, terrain.columns(), false, GpuBattleStatus.Arc.NONE);
            text.update(scene, font, outline, terrain::roofBounds, false, true, true);
            text.render(batch, camera, atmosphere.depthTexture(), 0);
            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, width, height);
            try {
                ByteBuffer rgba = pixels.getPixels();
                byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
                for (int line = 0; line < height; line++) {
                    // The framebuffer's rows run upward. A JPEG has no alpha: the straight colour lies on white paper.
                    int from = (height - line - 1) * width * 4, to = ((y + line) * image.getWidth() + x) * 3;
                    for (int col = 0; col < width; col++, from += 4, to += 3) {
                        int alpha = rgba.get(from + 3) & 0xFF;
                        bgr[to] = paper(rgba.get(from + 2), alpha);
                        bgr[to + 1] = paper(rgba.get(from + 1), alpha);
                        bgr[to + 2] = paper(rgba.get(from), alpha);
                    }
                }
            } finally { pixels.dispose(); }
        } finally {
            target.end();
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        }
        x += width;
        if (x == image.getWidth()) { x = 0; y += height; }
    }

    private static byte paper(byte channel, int alpha) {
        return (byte) (((channel & 0xFF) * alpha + 255 * (255 - alpha) + 127) / 255);
    }

    /** The lender must outlive this job, including a cancelled build. */
    @Override public void dispose() {
        if (terrain != null) { terrain.dispose(); terrain = null; }
        if (atmosphere != null) { atmosphere.dispose(); atmosphere = null; }
        if (text != null) { text.dispose(); text = null; }
        if (grid != null) { grid.dispose(); grid = null; }
        if (batch != null) { batch.dispose(); batch = null; }
        if (target != null) { target.dispose(); target = null; }
        image = null;
        if (!finished) {
            finished = true;
            request.failed().accept(new CancellationException("Board print cancelled"));
        }
    }
}
