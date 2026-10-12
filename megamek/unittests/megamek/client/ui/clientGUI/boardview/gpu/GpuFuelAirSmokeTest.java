/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.ScreenUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders an Arrow IV fuel-air round (R2) through its four beats for review, and checks that three landing together
 * stay within the volume budget and how long they take to draw.
 */
@Tag("on-demand")
class GpuFuelAirSmokeTest {
    private static final int SIZE = 320;
    private static final float[] MOMENTS = { .1f, .22f, .32f, .5f, .9f, 1.6f, 2.8f, 4.6f };

    @Test
    void drawsTheFuelAirBlastAndThreeAtOnceStayWithinBudget() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var volumes = new GpuExplosionEffects();
                var blast = new GpuArtilleryBlast();
                var batch = new ModelBatch();
                Model groundModel = null;
                FrameBuffer buffer = null;
                try {
                    buffer = GpuAtmosphere.buffer(SIZE, SIZE, true);
                    groundModel = new ModelBuilder().createBox(4000, 4000, 1,
                          new Material(ColorAttribute.createDiffuse(.36f, .38f, .33f, 1)),
                          VertexAttributes.Usage.Position);
                    var ground = new ModelInstance(groundModel);
                    ground.transform.setToTranslation(0, 0, -.5f);
                    float hex = BoardGeometry.height();
                    var camera = new PerspectiveCamera(40, SIZE, SIZE);
                    camera.position.set(0, -hex * 7, hex * 3.2f);
                    camera.up.set(Vector3.Z);
                    camera.lookAt(0, 0, hex * .7f);
                    camera.near = 1;
                    camera.far = hex * 60;
                    camera.update();
                    volumes.setLightDirection(new Vector3(-.3f, .5f, -1).nor());
                    volumes.setWind(new BoardAtmosphere.Effects(0, 0, 0, 0, 0, .4f, 90));
                    File folder = new File(System.getProperty("megamek.gpu.screenshots"));
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    float size = GpuArtilleryBlast.size(null);
                    var sheet = new BufferedImage(SIZE * 4, SIZE * 2, BufferedImage.TYPE_INT_RGB);
                    var graphics = sheet.createGraphics();
                    try {
                        for (int index = 0; index < MOMENTS.length; index++) {
                            float seconds = MOMENTS[index];
                            var image = frame(buffer, batch, ground, camera, volumes,
                                  () -> blast.emit(volumes, Vector3.Zero, size, 2, GpuArtilleryBlast.Style.FUEL_AIR,
                                        seconds, 41));
                            graphics.drawImage(image, (index % 4) * SIZE, (index / 4) * SIZE, null);
                            graphics.setColor(java.awt.Color.WHITE);
                            graphics.drawString("FAE R2 " + seconds + "s", (index % 4) * SIZE + 8,
                                  (index / 4) * SIZE + 16);
                        }
                    } finally {
                        graphics.dispose();
                    }
                    ImageIO.write(sheet, "png", new File(folder, "fuel-air-review.png"));
                    File frames = new File(folder, "fuel-air-frames");
                    assertTrue(frames.isDirectory() || frames.mkdirs());
                    int count = Math.round(GpuArtilleryBlast.SECONDS * 15);
                    for (int index = 0; index < count; index++) {
                        float seconds = index / 15f;
                        ImageIO.write(frame(buffer, batch, ground, camera, volumes,
                              () -> blast.emit(volumes, Vector3.Zero, size, 2, GpuArtilleryBlast.Style.FUEL_AIR,
                                    seconds, 41)), "png", new File(frames, String.format("frame-%03d.png", index)));
                    }
                    budget(buffer, batch, ground, camera, volumes, blast, size, hex);
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    volumes.dispose();
                    batch.dispose();
                    if (groundModel != null) {
                        groundModel.dispose();
                    }
                    if (buffer != null) {
                        buffer.dispose();
                    }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Fuel-air review failed", failure.get());
        }
    }

    /** Three Arrow IV fuel-air rounds landing a hex apart: their volumes at every moment, and the drawing time. */
    private static void budget(FrameBuffer buffer, ModelBatch batch, ModelInstance ground, PerspectiveCamera camera,
          GpuExplosionEffects volumes, GpuArtilleryBlast blast, float size, float hex) {
        Vector3[] centres = { new Vector3(-hex, 0, 0), new Vector3(0, hex * .8f, 0), new Vector3(hex, 0, 0) };
        int most = 0;
        long slowest = 0;
        for (float seconds = 0; seconds < GpuArtilleryBlast.SECONDS; seconds += .1f) {
            float moment = seconds;
            long start = System.nanoTime();
            frame(buffer, batch, ground, camera, volumes, () -> {
                for (int round = 0; round < centres.length; round++) {
                    blast.emit(volumes, centres[round], size, 2, GpuArtilleryBlast.Style.FUEL_AIR, moment, 41 + round);
                }
            });
            Gdx.gl.glFinish();
            slowest = Math.max(slowest, System.nanoTime() - start);
            most = Math.max(most, volumes.size());
        }
        System.out.println("[FuelAir] three rounds at once: at most " + most + " of " + GpuExplosionEffects.CAPACITY
              + " volumes, slowest frame " + slowest / 1_000_000 + " ms at " + SIZE + " px");
        assertTrue(most < GpuExplosionEffects.CAPACITY, "Three fuel-air rounds fit the volume budget: " + most);
    }

    private static BufferedImage frame(FrameBuffer buffer, ModelBatch batch, ModelInstance ground,
          PerspectiveCamera camera, GpuExplosionEffects volumes, Runnable enqueue) {
        buffer.begin();
        try {
            ScreenUtils.clear(new Color(.62f, .66f, .72f, 1), true);
            batch.begin(camera);
            batch.render(ground);
            batch.end();
            volumes.begin();
            enqueue.run();
            volumes.render(camera);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            Pixmap pixels = Pixmap.createFromFrameBuffer(0, 0, SIZE, SIZE);
            try {
                BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < SIZE; y++) {
                    for (int x = 0; x < SIZE; x++) {
                        image.setRGB(x, SIZE - 1 - y, pixels.getPixel(x, y) >>> 8);
                    }
                }
                return image;
            } finally {
                pixels.dispose();
            }
        } finally {
            buffer.end();
        }
    }
}
