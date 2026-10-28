/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.FloatBuffer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.FloatArray;

/** Preintegrated GGX for the board's analytic sky and ground environment, without another scene render. */
final class GpuEnvironmentLighting extends Attribute implements Disposable {
    static final long TYPE = register("boardEnvironmentLighting");
    static final int SIZE = 128;
    static final int WIDTH = SIZE * 2;
    static final int HEIGHT = SIZE + 1;
    /** Physical apparent solar radius; the moon uses the same angular source footprint. */
    static final float SOURCE_RADIUS_SINE = (float) Math.sin(Math.toRadians(.266));
    /**
     * Geometric specular anti-aliasing (light-model.glsl); the tuning's Display page sets it for the window. It is
     * compiled in: an environment with this lighting carries {@link Antialiasing} while it is on.
     */
    static boolean specularAntialiasing = true;
    private final Color sky = new Color(), horizon = new Color();
    private Texture texture;
    private GpuLightingEnvironment image;
    private float shadowStrength = 1, imageGain = 1;
    /** The board's sky occlusion by its relief (borrowed), else this lighting's own neutral one (owned, made on use). */
    private GpuSkyVisibility skyVisibility;
    private GpuSkyVisibility neutralSky;

    GpuEnvironmentLighting() { super(TYPE); }

    /** The board's sky visibility, which its owner keeps and disposes; the lit programs read it with this lighting. */
    void setSkyVisibility(GpuSkyVisibility sky) { skyVisibility = sky; }

    GpuSkyVisibility skyVisibility() {
        if (skyVisibility != null) { return skyVisibility; }
        if (neutralSky == null) { neutralSky = new GpuSkyVisibility(); }
        return neutralSky;
    }

    /**
     * Marks an environment whose lit programs compile specular anti-aliasing (specularAntialiasingFlag,
     * GpuCloudShadow.prefix). It is part of the environment's mask, so a switch is a lighting change: shader selection
     * (DefaultShader.canRender), the terrain's material readiness and its family programs follow it, and the terrain
     * rebuilds behind its loading stage. It spends one of libGDX's 64 attribute types.
     */
    static final class Antialiasing extends Attribute {
        static final long TYPE = register("boardSpecularAntialiasing");

        Antialiasing() { super(TYPE); }

        @Override public Attribute copy() { return new Antialiasing(); }
        @Override public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    }

    /** Brings the marker of an environment with this lighting in line with the setting. */
    static void applyAntialiasing(Environment environment) {
        if (!specularAntialiasing) { environment.remove(Antialiasing.TYPE); }
        else if (!environment.has(Antialiasing.TYPE)) { environment.set(new Antialiasing()); }
    }

    void set(BoardAtmosphere.Lighting lighting) {
        sky.set(lighting.environmentSky()); horizon.set(lighting.environmentHorizon());
        shadowStrength = lighting.shadowStrength(); imageGain = lighting.environmentGain();
        if (image != lighting.environmentImage()) {
            image = lighting.environmentImage();
            if (texture != null) { texture.dispose(); texture = null; texture(); }
        }
    }

    private FloatBuffer pixels(int width, int height) {
        FloatBuffer pixels = arrayPixels(width, height);
        if (image != null) { image.write(pixels, width); }
        return pixels;
    }

    /** Uniform upper-hemisphere radiance has the same E/pi as this diffuse ambient color. */
    void set(Color ambient) { sky.set(ambient); horizon.set(ambient); }

    /** Copy the actual sky uniforms into a view's owned cache snapshot without exposing mutable colors. */
    void appendColors(FloatArray values) {
        values.add(sky.r, sky.g, sky.b);
        values.add(horizon.r, horizon.g, horizon.b);
        values.add(shadowStrength, imageGain);
        // The cache already compares floating-point snapshots; encode identity losslessly as two 16-bit words.
        int identity = System.identityHashCode(image);
        values.add(identity & 65535, identity >>> 16);
    }

    /** The atlas, made with the sky visibility's volume when a program is built, before its setters bind textures. */
    Texture texture() {
        if (GpuSkyVisibility.STRENGTH > 0) { skyVisibility().texture(); }
        if (texture == null) {
            texture = GpuColorTexture.linear(pixels(GpuLightingEnvironment.ATLAS_SIZE, GpuLightingEnvironment.ATLAS_SIZE),
                  GpuLightingEnvironment.ATLAS_SIZE, GpuLightingEnvironment.ATLAS_SIZE, false);
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        }
        return texture;
    }

    /** The lighting image for a backdrop that shows it: the atlas and gain the lit surfaces sample. */
    void bindImage(ShaderProgram shader, int unit) {
        // Creating the atlas binds it on the active unit: select this unit first, so it never replaces another sampler.
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0 + unit);
        texture().bind(unit);
        shader.setUniformi("u_environmentLut", unit);
        shader.setUniformf("u_environmentImage", image == null ? 0 : image.generated ? 2 : 1, imageGain);
    }

    static void register(DefaultShader shader) {
        GpuSkyVisibility.register(shader);
        shader.register("u_shadowStrength", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                target.set(id, light == null ? 1f : light.shadowStrength);
            }
        });
        shader.register("u_environmentImage", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                target.set(id, light == null || light.image == null ? 0f : light.image.generated ? 2f : 1f,
                      light == null ? 1f : light.imageGain);
            }
        });
        shader.register("u_directionalRadiusSine", new BaseShader.GlobalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                target.set(id, SOURCE_RADIUS_SINE);
            }
        });
        shader.register("u_environmentLut", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.texture()); }
            }
        });
        shader.register("u_environmentSky", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.sky.r, light.sky.g, light.sky.b); }
            }
        });
        shader.register("u_environmentHorizon", new BaseShader.LocalSetter() {
            @Override public void set(BaseShader target, int id, Renderable part, Attributes attributes) {
                var light = attributes.get(GpuEnvironmentLighting.class, TYPE);
                if (light != null) { target.set(id, light.horizon.r, light.horizon.g, light.horizon.b); }
            }
        });
    }

    /** Native-size table in the atlas's corner, padded at its edges; shader lookups explicitly use mip zero. */
    static FloatBuffer arrayPixels(int width, int height) {
        if (width < WIDTH || height < HEIGHT) { throw new IllegalArgumentException("Environment LUT layer is too small"); }
        var pixels = BufferUtils.newFloatBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = (Math.min(y, HEIGHT - 1) * WIDTH + Math.min(x, WIDTH - 1)) * 4;
                pixels.put(Table.PIXELS, at, 4);
            }
        }
        pixels.flip();
        return pixels;
    }

    private static final class Table { private static final float[] PIXELS = load(); }

    /** Generated by scripts/GenerateEnvironmentLighting.java; no numerical integration on the GL thread. */
    private static float[] load() {
        var resource = GpuEnvironmentLighting.class.getResourceAsStream("environment-lighting.bin");
        if (resource == null) { throw new IllegalStateException("Missing environment-lighting.bin"); }
        try (var input = new DataInputStream(new BufferedInputStream(resource))) {
            if (input.readInt() != 0x454C5554 || input.readInt() != WIDTH || input.readInt() != HEIGHT
                  || input.readFloat() != SOURCE_RADIUS_SINE) {
                throw new IOException("Environment LUT dimensions or source radius do not match the renderer");
            }
            float[] result = new float[WIDTH * HEIGHT * 4];
            for (int i = 0; i < result.length; i++) { result[i] = input.readFloat(); }
            if (input.read() != -1) { throw new IOException("Unexpected trailing environment LUT data"); }
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load environment-lighting.bin", failure);
        }
    }

    @Override public Attribute copy() {
        var copy = new GpuEnvironmentLighting(); copy.sky.set(sky); copy.horizon.set(horizon);
        copy.image = image; copy.imageGain = imageGain; copy.shadowStrength = shadowStrength;
        // The board's volume, borrowed; without one the copy makes and disposes its own neutral volume.
        copy.skyVisibility = skyVisibility;
        return copy;
    }
    @Override public int compareTo(Attribute other) { return Long.compare(type, other.type); }
    @Override public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        if (neutralSky != null) { neutralSky.dispose(); neutralSky = null; }
    }
}
