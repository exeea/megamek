/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.annotations.Nullable;
import megamek.logging.MMLogger;

/**
 * Draws one unit on its own into a picture, the way the 3D board would show it: the same model, the same fitted
 * weapons, camouflage and damage, and the same unit shader. Used by the unit readout. Everything here runs on the GPU
 * thread of whichever libGDX application is running at the time, see {@link GpuUnitPortraits}.
 *
 * <p>The unit is built with the board's own steps (library assembly, then damage, equipment state and camouflage,
 * then placement) so the readout can never show a loadout the board would not.</p>
 */
final class GpuUnitPortraitRenderer implements Disposable {

    private static final MMLogger LOGGER = MMLogger.create(GpuUnitPortraitRenderer.class);

    /**
     * The model library caches one assembled unit per id. The portrait only ever shows one unit at a time, so it uses
     * one id that no real unit has; showing the next unit replaces (and frees) the previous assembly.
     */
    static final int PORTRAIT_SLOT = Integer.MIN_VALUE + 1;

    /** The picture is drawn this many times larger on each side and then shrunk, which smooths the edges. */
    static final int SUPERSAMPLING = 2;

    /** The camera's vertical field of view, in degrees. Narrow, so the unit is not distorted like a close-up photo. */
    private static final float FIELD_OF_VIEW = 30;

    /** Room left around the unit, as a multiple of what would exactly fit it. */
    private static final float FRAMING_MARGIN = 1.08f;

    /**
     * The closest the camera comes to the unit's middle when zoomed in, as a multiple of the unit's footprint radius,
     * so it never ends up inside the unit.
     */
    private static final float MIN_CAMERA_CLEARANCE = 1.25f;

    /** The key light comes from this far to the viewer's left of the camera, and this high above the ground. */
    private static final float LIGHT_YAW_OFFSET = 45;
    private static final float LIGHT_ELEVATION = 50;

    private final GpuUnitModels library = new GpuUnitModels();
    private final ModelBatch batch = new ModelBatch(GpuUnitShader.provider(), new GpuOpaqueSorter());
    private final GpuUnitCamouflage camouflage = new GpuUnitCamouflage();
    private final UnitDamageDisplay damageDisplay = new UnitDamageDisplay();
    private final Environment environment = new Environment();
    private final DirectionalLight keyLight = new DirectionalLight();
    private final Color keyLightColor;
    private final PerspectiveCamera camera = new PerspectiveCamera(FIELD_OF_VIEW, 1, 1);

    private FrameBuffer frameBuffer;
    private Timings lastTimings = new Timings(0, 0, 0);

    /**
     * How long the last picture took, step by step, for the log.
     *
     * @param drawNanos   Building the unit if needed, aiming the camera and issuing the draw calls
     * @param readNanos   Reading the picture back from the GPU, which also waits for the GPU to finish drawing it
     * @param shrinkNanos Shrinking and flipping the picture into a Swing image
     */
    record Timings(long drawNanos, long readNanos, long shrinkNanos) { }
    private BoardScene.UnitModel shownSelection;
    private GpuUnitInstance shownInstance;

    GpuUnitPortraitRenderer() {
        // The board's default daylight, so a unit looks the same here as on an ordinary map.
        BoardAtmosphere.Lighting lighting = BoardAtmosphere.lighting(BoardAtmosphere.DEFAULTS);
        keyLightColor = new Color(lighting.direct());
        environment.set(ColorAttribute.createAmbientLight(lighting.ambient()));
        environment.add(keyLight);
    }

    /**
     * Draws the requested unit from the requested side.
     *
     * @param request What to draw, from where and how large
     *
     * @return The picture at the requested size, or {@code null} if the unit has no model that can be drawn; the log
     *       says why
     */
    @Nullable BufferedImage render(GpuUnitPortraits.Request request) {
        long start = System.nanoTime();
        GpuUnitInstance instance = instanceFor(request.subject());
        if (instance == null) {
            return null;
        }
        int renderWidth = request.width() * SUPERSAMPLING;
        int renderHeight = request.height() * SUPERSAMPLING;
        aimCamera(instance, request.angle(), request.zoom(), renderWidth, renderHeight);
        instance.equipmentDetail(camera, true);

        ensureFrameBuffer(renderWidth, renderHeight);
        frameBuffer.begin();
        try {
            Color background = new Color();
            Color.rgb888ToColor(background, request.backgroundRgb());
            Gdx.gl.glClearColor(background.r, background.g, background.b, 1);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            batch.begin(camera);
            batch.render(instance, environment);
            batch.end();
            long drawn = System.nanoTime();
            Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, renderWidth, renderHeight);
            long read = System.nanoTime();
            try {
                BufferedImage image = shrink(pixmap, request.width(), request.height());
                lastTimings = new Timings(drawn - start, read - drawn, System.nanoTime() - read);
                return image;
            } finally {
                pixmap.dispose();
            }
        } finally {
            frameBuffer.end();
        }
    }

    /**
     * @return The unit ready to draw, built once per unit and reused while only the camera moves, or {@code null}
     *       if the library cannot build it
     */
    private @Nullable GpuUnitInstance instanceFor(GpuUnitPortraits.Subject subject) {
        BoardScene.UnitModel selection = subject.selection();
        if (selection == shownSelection) {
            return shownInstance;
        }
        shownSelection = selection;
        shownInstance = null;
        GpuUnitModel visual = library.get(selection, PORTRAIT_SLOT);
        if (visual == null) {
            LOGGER.warn("[UnitPortrait] {}: the model library could not build {} (fallback {}); the board would show"
                  + " its sprite instead", subject.unitName(), selection.asset(), selection.fallback());
            return null;
        }
        // The same order as the board: damage first on a fresh instance, then equipment state and paint.
        GpuUnitInstance instance = new GpuUnitInstance(visual);
        UnitDamageDisplay.show(instance, selection.damage());
        UnitModelState state = selection.state();
        if ((state != null) && (state.appearance() != null)) {
            visual.showEquipment(instance, state.appearance());
            camouflage.apply(instance, visual.instance, state.appearance());
        }
        damageDisplay.applyTexture(instance, selection.damage(), PORTRAIT_SLOT);
        visual.place(instance, camera, new Vector3(), 0, 1, false);
        LOGGER.debug("[UnitPortrait] {}: built from {}", subject.unitName(), selection.asset());
        shownInstance = instance;
        return instance;
    }

    /**
     * Stands the camera on the requested side of the unit, far enough back that the whole unit fits the picture, or
     * nearer by the zoom factor and aimed at the zoomed-in part, and turns the key light with it so the side facing
     * the viewer is always lit.
     */
    private void aimCamera(GpuUnitInstance instance, UnitPortraitAngle angle, UnitPortraitZoom zoom, int width,
          int height) {
        BoundingBox bounds = UnitBounds.world(instance);
        Vector3 center = bounds.getCenter(new Vector3());
        Vector3 dimensions = bounds.getDimensions(new Vector3());

        Vector3 front = unitFront(instance);
        Vector3 viewDirection = direction(front, angle.yawDegrees(), angle.pitchDegrees());

        // Fit an upright cylinder round the unit rather than its box: seen from any side the cylinder is equally
        // wide, so the unit keeps its size on screen while it is dragged round.
        float footprintRadius = Math.max(0.001f, (float) Math.hypot(dimensions.x, dimensions.y) / 2);
        float pitch = angle.pitchDegrees() * MathUtils.degreesToRadians;
        float verticalExtent = dimensions.z / 2 * MathUtils.cos(pitch) + footprintRadius * MathUtils.sin(pitch);
        float verticalHalfAngle = FIELD_OF_VIEW / 2 * MathUtils.degreesToRadians;
        float horizontalHalfAngle = (float) Math.atan(Math.tan(verticalHalfAngle) * width / height);
        float fittingDistance = Math.max(footprintRadius / (float) Math.tan(horizontalHalfAngle),
              verticalExtent / (float) Math.tan(verticalHalfAngle));
        // Measured from the cylinder's near edge, so the side closest to the camera fits as well.
        float wholeUnitDistance = footprintRadius + fittingDistance * FRAMING_MARGIN;
        float distance = Math.max(footprintRadius * MIN_CAMERA_CLEARANCE, wholeUnitDistance / zoom.factor());

        // The zoomed-in part: the whole view's plane through the unit's middle, moved across and up by the zoom's
        // centre, in units of that plane's half width and half height.
        Vector3 forward = new Vector3(viewDirection).scl(-1);
        Vector3 right = new Vector3(forward).crs(Vector3.Z).nor();
        Vector3 up = new Vector3(right).crs(forward).nor();
        float wholeHalfWidth = wholeUnitDistance * (float) Math.tan(horizontalHalfAngle);
        float wholeHalfHeight = wholeUnitDistance * (float) Math.tan(verticalHalfAngle);
        Vector3 target = new Vector3(center).mulAdd(right, zoom.centerX() * wholeHalfWidth)
              .mulAdd(up, zoom.centerY() * wholeHalfHeight);

        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.position.set(target).mulAdd(viewDirection, distance);
        camera.up.set(Vector3.Z);
        camera.lookAt(target);
        camera.near = distance / 50;
        camera.far = distance * 4;
        camera.update();

        Vector3 lightPosition = direction(front, angle.yawDegrees() + LIGHT_YAW_OFFSET, LIGHT_ELEVATION);
        keyLight.set(keyLightColor, -lightPosition.x, -lightPosition.y, -lightPosition.z);
    }

    /**
     * @return The direction the unit faces, flat on the ground. Models are authored facing +Y; the placement turns
     *       that into board space, so it is read back from the placed transform rather than assumed.
     */
    private static Vector3 unitFront(GpuUnitInstance instance) {
        Vector3 front = new Vector3(0, 1, 0).rot(instance.transform);
        front.z = 0;
        if (front.isZero(1e-6f)) {
            front.set(0, 1, 0);
        }
        return front.nor();
    }

    /**
     * @param front          The unit's flat front direction
     * @param yawDegrees     How far round from the front, towards the unit's left
     * @param pitchDegrees   How far above level
     *
     * @return A unit vector from the unit's middle towards that point of view
     */
    static Vector3 direction(Vector3 front, float yawDegrees, float pitchDegrees) {
        Vector3 flat = new Vector3(front).rotate(Vector3.Z, yawDegrees);
        float pitch = pitchDegrees * MathUtils.degreesToRadians;
        return flat.scl(MathUtils.cos(pitch)).add(0, 0, MathUtils.sin(pitch)).nor();
    }

    Timings lastTimings() {
        return lastTimings;
    }

    private void ensureFrameBuffer(int width, int height) {
        if ((frameBuffer != null) && (frameBuffer.getWidth() == width) && (frameBuffer.getHeight() == height)) {
            return;
        }
        if (frameBuffer != null) {
            frameBuffer.dispose();
        }
        frameBuffer = createFrameBuffer(width, height);
    }

    /**
     * A frame buffer with a 24-bit depth buffer, as the board's own window has. libGDX's default is 16 bits, which
     * cannot keep small details (vents, visor slits, a Mek's teeth) apart from the armour a hair behind them: they
     * break into stripes or merge into blobs, worst when the unit is seen head on.
     */
    private static FrameBuffer createFrameBuffer(int width, int height) {
        try {
            GLFrameBuffer.FrameBufferBuilder builder = new GLFrameBuffer.FrameBufferBuilder(width, height);
            builder.addBasicColorTextureAttachment(Pixmap.Format.RGBA8888);
            builder.addDepthRenderBuffer(GL30.GL_DEPTH_COMPONENT24);
            return builder.build();
        } catch (IllegalStateException exception) {
            LOGGER.warn("[UnitPortrait] The graphics driver refused a 24-bit depth buffer; small details may flicker",
                  exception);
            return new FrameBuffer(Pixmap.Format.RGBA8888, width, height, true);
        }
    }

    /**
     * Shrinks the supersampled drawing to the requested size by averaging each block of pixels, and turns it the
     * right way up: OpenGL reads rows from the bottom, a picture starts at the top.
     */
    static BufferedImage shrink(Pixmap pixmap, int width, int height) {
        // One bulk copy out of the native buffer rather than a call per byte.
        ByteBuffer buffer = pixmap.getPixels();
        byte[] pixels = new byte[buffer.remaining()];
        buffer.get(pixels);
        int sourceWidth = pixmap.getWidth();
        int sourceHeight = pixmap.getHeight();
        int blockWidth = sourceWidth / width;
        int blockHeight = sourceHeight / height;
        int samples = blockWidth * blockHeight;
        int[] rgb = new int[width * height];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int blockRow = 0; blockRow < blockHeight; blockRow++) {
                    int sourceRow = sourceHeight - 1 - (row * blockHeight + blockRow);
                    for (int blockColumn = 0; blockColumn < blockWidth; blockColumn++) {
                        int offset = (sourceRow * sourceWidth + column * blockWidth + blockColumn) * 4;
                        red += pixels[offset] & 0xFF;
                        green += pixels[offset + 1] & 0xFF;
                        blue += pixels[offset + 2] & 0xFF;
                    }
                }
                rgb[row * width + column] = ((red / samples) << 16) | ((green / samples) << 8) | (blue / samples);
            }
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, width, height, rgb, 0, width);
        return image;
    }

    @Override
    public void dispose() {
        batch.dispose();
        camouflage.dispose();
        damageDisplay.dispose();
        library.dispose();
        if (frameBuffer != null) {
            frameBuffer.dispose();
        }
    }
}
