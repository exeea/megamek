/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.List;
import java.util.Random;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;

/** A bounded, GPU-animated particle pool. Shares the world's depth buffer and never covers the tactical UI. */
final class GpuWeatherParticles implements Disposable {
    private static final int BASE_PARTICLES = 768;
    private static final int PARTICLES = BASE_PARTICLES * 6;
    enum Kind {
        RAIN("weather-rain.glsl", 6), SNOW("weather-snow.glsl", 6), HAIL("weather-hail.glsl", 4);

        final String source;
        final int density;

        Kind(String source, int density) { this.source = source; this.density = density; }
    }

    private static final Kind[] KINDS = Kind.values();
    private final ShaderProgram[] shaders = new ShaderProgram[KINDS.length];
    private final Mesh mesh;
    private final Vector3 right = new Vector3();
    private final Vector3 origin = new Vector3();
    private final Vector3 extent = new Vector3();
    private List<BoardScene.Tile> tiles;
    private float level;
    private float bottom;
    private float top;

    GpuWeatherParticles() {
        float[] vertices = new float[PARTICLES * 4 * 5];
        short[] indices = new short[PARTICLES * 6];
        Random random = new Random(20260918);
        int[] corners = { 0, 1, 2, 2, 3, 0 };
        for (int particle = 0; particle < PARTICLES; particle++) {
            float x = random.nextFloat(), y = random.nextFloat(), z = random.nextFloat();
            for (int corner = 0; corner < 4; corner++) {
                int index = (particle * 4 + corner) * 5;
                vertices[index] = x;
                vertices[index + 1] = y;
                vertices[index + 2] = z;
                vertices[index + 3] = corner == 1 || corner == 2 ? 1 : -1;
                vertices[index + 4] = corner >= 2 ? 1 : -1;
            }
            for (int index = 0; index < 6; index++) {
                indices[particle * 6 + index] = (short) (particle * 4 + corners[index]);
            }
        }
        mesh = new Mesh(true, PARTICLES * 4, indices.length, VertexAttribute.Position(), VertexAttribute.TexCoords(0));
        mesh.setVertices(vertices);
        mesh.setIndices(indices);
        try {
            for (Kind kind : KINDS) {
                shaders[kind.ordinal()] = GpuShaderManager.program(() -> shader(kind), next -> shaders[kind.ordinal()] = next);
            }
        } catch (RuntimeException failure) {
            dispose();
            throw failure;
        }
    }

    static ShaderProgram shader(Kind kind) {
        return GpuGlsl.compile("GPU precipitation " + kind, source("weather-particles.vert", kind.source),
              source("weather-particles.frag", kind.source));
    }

    static String source(String stage, String condition) {
        return GpuShaderSource.read(stage).replace("// WEATHER_CONDITION", GpuShaderSource.read(condition));
    }

    void render(Camera camera, BoardScene scene, BoardAtmosphere.Effects effects, Color light, float clock) {
        if (!effects.hasParticles() || !bounds(camera, scene)) {
            return;
        }
        right.set(camera.direction).crs(camera.up).nor();
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glDisable(GL20.GL_CULL_FACE);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        try {
            float windX = MathUtils.sinDeg(effects.windDirection());
            float windY = MathUtils.cosDeg(effects.windDirection());
            float wind = effects.wind() * 5;
            for (Kind kind : KINDS) {
                float strength = switch (kind) {
                    case RAIN -> effects.rain();
                    case SNOW -> effects.snow();
                    case HAIL -> effects.hail();
                };
                if (strength > 0) {
                    ShaderProgram shader = shaders[kind.ordinal()];
                    shader.bind();
                    shader.setUniformMatrix("u_projView", camera.combined);
                    shader.setUniformf("u_origin", origin);
                    shader.setUniformf("u_extent", extent);
                    shader.setUniformf("u_right", right);
                    shader.setUniformf("u_up", camera.up);
                    shader.setUniformf("u_clock", clock);
                    shader.setUniformf("u_level", BoardGeometry.level());
                    shader.setUniformf("u_light", light.r, light.g, light.b);
                    shader.setUniformf("u_wind", windX * wind, windY * wind);
                    float density = strength * (1 + (kind.density - 1) * strength * strength);
                    int count = Math.max(1, Math.round(BASE_PARTICLES * density));
                    mesh.render(shader, GL20.GL_TRIANGLES, 0, count * 6);
                }
            }
        } finally {
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    /** Bound the volume to the board and the camera's footprint at both ends of the weather layer. */
    private boolean bounds(Camera camera, BoardScene scene) {
        if (tiles != scene.tiles() || level != BoardGeometry.level()) {
            tiles = scene.tiles();
            level = BoardGeometry.level();
            bottom = BoardGeometry.weatherBase(scene);
            top = Float.NEGATIVE_INFINITY;
            for (BoardScene.Tile tile : tiles) {
                float roof = tile.elevation();
                for (BoardScene.Feature feature : tile.features()) {
                    roof = Math.max(roof, tile.elevation() + feature.elevation() + feature.height());
                }
                top = Math.max(top, (roof + 12) * level);
            }
        }
        BoundingBox visible = BoardCamera.viewportBounds(camera, new BoundingBox(
              new Vector3(-BoardGeometry.width(), -(scene.height() + 1) * BoardGeometry.height(), bottom),
              new Vector3((scene.width() + 1) * BoardGeometry.width() * .75f, BoardGeometry.height(), top)));
        if (visible.getWidth() <= 0 || visible.getHeight() <= 0) {
            return false;
        }
        origin.set(visible.min);
        visible.getDimensions(extent);
        return true;
    }

    @Override
    public void dispose() {
        mesh.dispose();
        for (ShaderProgram shader : shaders) { GpuShaderManager.dispose(shader); }
    }
}
