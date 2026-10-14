/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import megamek.common.ResolvedAttack;
import megamek.common.equipment.EquipmentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders an Arrow IV round in flight: close up from the side and from ahead, then a frame sequence of its whole
 * flight at board zoom, for review.
 */
@Tag("on-demand")
class GpuArrowIVSmokeTest {
    private static final Color DAY = new Color(.62f, .66f, .72f, 1);
    private static final Color NIGHT = new Color(.03f, .04f, .07f, 1);
    private static final int SIZE = 320;

    @Test
    void drawsTheArrowIVRoundInFlight() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                FrameBuffer buffer = null;
                var effects = new GpuAttackEffects();
                try {
                    EquipmentType.initializeTypes();
                    buffer = GpuAtmosphere.buffer(SIZE, SIZE, true);
                    var round = new ResolvedAttack.Shot("", Set.of("M_STANDARD"), true, false, 1, 1, true, null, null,
                          null, false, 20, false, false, false, null, 20);
                    var shot = GroundDamagePlaybackTest.shot("ISArrowIV", true, round);
                    shot.seconds = shot.contactSeconds;
                    effects.update(List.of(shot), null, Map.of());
                    effects.groundImpacts(List.of(), ignored -> { });
                    var launch = effects.missileLaunches(shot).getFirst();
                    assertTrue(GpuMissileEffects.firesArrowIV(launch));
                    float metre = BoardRelief.metres(1);
                    float atMidFlight = timeAt(launch, .5f);
                    var base = GpuMissileEffects.position(launch, 0, .5f, new Vector3());
                    var forward = GpuMissileEffects.position(launch, 0, .502f, new Vector3()).sub(base).nor();
                    var middle = base.cpy().mulAdd(forward, 2 * metre);
                    var side = forward.cpy().crs(Vector3.Z).nor();
                    File folder = new File(System.getProperty("megamek.gpu.screenshots"));
                    assertTrue(folder.isDirectory() || folder.mkdirs());

                    var beside = camera(middle.cpy().mulAdd(side, 5.5f * metre).add(0, 0, 1.2f * metre), middle);
                    var empty = frame(buffer, effects, shot, beside, 0);
                    var sideOn = frame(buffer, effects, shot, beside, atMidFlight);
                    long drawn = different(sideOn, empty);
                    assertTrue(drawn > 2000, "The round fills the close-up, changed pixels " + drawn);
                    ImageIO.write(sideOn, "png", new File(folder, "arrow-iv-side.png"));
                    // Its rocket motor, against a night sky where the fire's glow shows: red behind the tail.
                    var motorView = camera(base.cpy().mulAdd(side, 6 * metre).add(0, 0, metre), base);
                    var night = frame(buffer, effects, shot, motorView, atMidFlight, NIGHT);
                    ImageIO.write(night, "png", new File(folder, "arrow-iv-motor.png"));
                    long fiery = redPixels(night);
                    assertTrue(fiery > 400, "The rocket motor's flame shows behind the round, red pixels " + fiery);
                    var ahead = camera(middle.cpy().mulAdd(forward, 5 * metre).mulAdd(side, 2.5f * metre)
                          .add(0, 0, 1.5f * metre), middle);
                    ImageIO.write(frame(buffer, effects, shot, ahead, atMidFlight), "png",
                          new File(folder, "arrow-iv-ahead.png"));

                    // The whole flight from about two hexes away, a player zoomed in on the target area.
                    float hex = BoardGeometry.height();
                    var start = GpuMissileEffects.position(launch, 0, 0, new Vector3());
                    var end = GpuMissileEffects.position(launch, 0, 1, new Vector3());
                    var centre = start.cpy().lerp(end, .5f).add(0, 0, hex);
                    var board = camera(centre.cpy().mulAdd(side, hex * 2.6f).add(0, 0, hex * .8f), centre);
                    File frames = new File(folder, "arrow-iv-flight-frames");
                    assertTrue(frames.isDirectory() || frames.mkdirs());
                    float first = timeAt(launch, 0), last = timeAt(launch, 1);
                    int count = 45;
                    for (int index = 0; index < count; index++) {
                        float seconds = first + (last - first) * index / (count - 1f);
                        ImageIO.write(frame(buffer, effects, shot, board, seconds), "png",
                              new File(frames, String.format("frame-%03d.png", index)));
                    }
                    reviewOpening(buffer, "M_CLUSTER", new File(folder, "arrow-iv-cluster-frames"));
                    reviewOpening(buffer, "M_FASCAM", new File(folder, "arrow-iv-fascam-frames"));
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    effects.dispose();
                    if (buffer != null) {
                        buffer.dispose();
                    }
                    Gdx.app.exit();
                }
            }
        }, GpuBoardWindow.configuration(false));
        if (failure.get() != null) {
            throw new AssertionError("Arrow IV review failed", failure.get());
        }
    }

    /**
     * Frames at 15 a second of a 20-hex Arrow IV flight's last stretch and what follows: the cluster burst or
     * minefield dispenser should open where the round is, and its spent body fall on to the ground and vanish.
     */
    private static void reviewOpening(FrameBuffer buffer, String munition, File folder) throws java.io.IOException {
        assertTrue(folder.isDirectory() || folder.mkdirs());
        var effects = new GpuAttackEffects();
        var batch = new ModelBatch();
        Model groundModel = new ModelBuilder().createBox(20000, 20000, 1,
              new Material(ColorAttribute.createDiffuse(.36f, .38f, .33f, 1)), VertexAttributes.Usage.Position);
        try {
            var ground = new ModelInstance(groundModel);
            ground.transform.setToTranslation(0, 0, -.5f);
            var shot = GpuAirOpeningTest.attack("ISArrowIV", GpuAirOpeningTest.round(munition, 1));
            effects.update(List.of(shot), null, Map.of());
            effects.groundImpacts(List.of(), ignored -> { });
            var launch = effects.missileLaunches(shot).getFirst();
            assertTrue(GpuMissileEffects.openedInTheAir(launch, 0), munition + " opens in the air");
            float hex = BoardGeometry.height();
            Vector3 opening = GpuMissileEffects.openingPoint(launch, 0);
            Vector3 aim = launch.aims()[0];
            var flight = GpuMissileEffects.position(launch, 0, .9f, new Vector3()).sub(opening).nor();
            var side = new Vector3(flight.x, flight.y, 0).crs(Vector3.Z).nor();
            var centre = opening.cpy().lerp(aim, .5f);
            var camera = camera(centre.cpy().mulAdd(side, hex * 6).add(0, 0, hex * 1.6f), centre);
            float first = timeAt(launch, .87f);
            float opened = launch.openingSeconds(0);
            int count = Math.round((opened - first + 3.2f) * 15);
            for (int index = 0; index < count; index++) {
                float seconds = first + index / 15f;
                effects.advance(1 / 15f);
                buffer.begin();
                try {
                    ScreenUtils.clear(new Color(.62f, .66f, .72f, 1), true);
                    batch.begin(camera);
                    batch.render(ground);
                    batch.end();
                    shot.seconds = Math.min(seconds, shot.duration);
                    effects.update(List.of(shot), null, Map.of());
                    effects.render(camera);
                    ImageIO.write(snapshot(), "png", new File(folder, String.format("frame-%03d.png", index)));
                } finally {
                    buffer.end();
                }
            }
        } finally {
            effects.dispose();
            batch.dispose();
            groundModel.dispose();
        }
    }

    /** The moment the round has flown {@code progress} of its way: progress grows evenly with time. */
    private static float timeAt(GpuMissileEffects.Launch launch, float progress) {
        float atZero = GpuMissileEffects.progress(launch, 0, 0);
        float atOne = GpuMissileEffects.progress(launch, 0, 1);
        return (progress - atZero) / (atOne - atZero);
    }

    private static PerspectiveCamera camera(Vector3 position, Vector3 lookAt) {
        var camera = new PerspectiveCamera(40, SIZE, SIZE);
        camera.position.set(position);
        camera.up.set(Vector3.Z);
        camera.lookAt(lookAt);
        camera.near = .5f;
        camera.far = 5000;
        camera.update();
        return camera;
    }

    private static BufferedImage frame(FrameBuffer buffer, GpuAttackEffects effects, UnitAttack shot,
          PerspectiveCamera camera, float seconds) {
        return frame(buffer, effects, shot, camera, seconds, DAY);
    }

    /** Pixels much redder than they are green or blue: fire. */
    private static long redPixels(BufferedImage image) {
        long count = 0;
        for (int row = 0; row < image.getHeight(); row++) {
            for (int column = 0; column < image.getWidth(); column++) {
                int rgb = image.getRGB(column, row);
                int red = rgb >> 16 & 255, green = rgb >> 8 & 255, blue = rgb & 255;
                if (red > 120 && red > green + 50 && red > blue + 50) {
                    count++;
                }
            }
        }
        return count;
    }

    private static BufferedImage frame(FrameBuffer buffer, GpuAttackEffects effects, UnitAttack shot,
          PerspectiveCamera camera, float seconds, Color sky) {
        buffer.begin();
        try {
            ScreenUtils.clear(sky, true);
            shot.seconds = seconds;
            effects.update(List.of(shot), null, Map.of());
            effects.render(camera);
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
            return snapshot();
        } finally {
            buffer.end();
        }
    }

    private static BufferedImage snapshot() {
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
    }

    private static long different(BufferedImage left, BufferedImage right) {
        long count = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (left.getRGB(x, y) != right.getRGB(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }
}
