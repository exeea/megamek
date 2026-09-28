/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/** Persistent board fire/smoke: one half-resolution pass, sorted batches and three projected-size LODs. */
final class GpuTerrainEffects implements Disposable {
    private static final int BATCH_SIZE = 256;
    private static final int STRIDE = 12;
    private static final float FLAME_LEVELS = 3;
    private static final float SMOKE_LEVELS = 8;
    private static final float SPREAD = 1.4f;
    private static final Comparator<Volume> BACK_TO_FRONT = Comparator.comparingDouble((Volume v) -> v.depth).reversed();
    private final Map<Coords, Volume> sources = new HashMap<>();
    private final List<Volume> visible = new ArrayList<>();
    private final float[] vertices = new float[BATCH_SIZE * 8 * STRIDE];
    private final Vector3 center = new Vector3();
    private final Vector3 sun = new Vector3();
    private final Motion motion = new Motion();
    private List<BoardScene.Tile> tiles;
    private int boardId = Integer.MIN_VALUE;
    private int revision = -1;
    private Mesh mesh, quad;
    private ShaderProgram shader, composite;
    private Texture noise;
    private FrameBuffer buffer;
    private final int[] lodCounts = new int[3];
    private int drawCalls;

    private static final class Volume {
        final Vector3 origin = new Vector3();
        BoardFireSmoke effect;
        float radius, height, depth;
        int lod = 2;
    }

    /** Integrated periodic advection avoids discontinuities when the scenario wind changes. */
    static final class Motion {
        final Vector3 offset = new Vector3();
        final Vector3 wind = new Vector3();
        private boolean initialized;

        void advance(BoardAtmosphere.Effects weather, float seconds) {
            float dt = Float.isFinite(seconds) ? MathUtils.clamp(seconds, 0, .1f) : 0;
            float x = MathUtils.sinDeg(weather.windDirection()) * weather.wind();
            float y = MathUtils.cosDeg(weather.windDirection()) * weather.wind();
            float blend = initialized ? 1 - (float) Math.exp(-dt * 3) : 1;
            wind.x = MathUtils.lerp(wind.x, x, blend);
            wind.y = MathUtils.lerp(wind.y, y, blend);
            initialized = true;
            offset.set(wrap(offset.x + wind.x * dt * .7f), wrap(offset.y + wind.y * dt * .7f),
                  wrap(offset.z + dt * .55f));
        }

        private static float wrap(float value) { return value - (float) Math.floor(value / 256) * 256; }

        void clear() { offset.setZero(); wind.setZero(); initialized = false; }
    }

    void update(BoardScene scene, BoardSurface.Cache surfaces, BoardAtmosphere.Effects wind, float seconds) {
        if (boardId != scene.boardId()) {
            sources.clear(); tiles = null; motion.clear(); boardId = scene.boardId();
        }
        motion.advance(wind, seconds);
        if (tiles == scene.tiles() && revision == BoardGeometry.revision()) { return; }
        Map<Coords, Volume> previous = new HashMap<>(sources);
        sources.clear();
        tiles = scene.tiles();
        revision = BoardGeometry.revision();
        for (BoardScene.Tile tile : tiles) {
            if (!tile.fireSmoke().present()) { continue; }
            Volume volume = previous.get(tile.coords());
            if (volume == null) { volume = new Volume(); }
            float x = BoardGeometry.centerX(tile.coords()), y = BoardGeometry.centerY(tile.coords());
            volume.origin.set(x, y, tile.liquid().present() ? BoardGeometry.surfaceZ(tile) : surfaces.get(scene, tile).height(x, y));
            volume.effect = tile.fireSmoke();
            // Ground sources overlap at shared edges/corners; smoke spreads farther as it rises.
            volume.radius = BoardGeometry.width() * .6f;
            float fuelHeight = tile.fireSmoke().fire() == 0 ? 0 : tile.features().stream()
                  .filter(feature -> feature.kind() == BoardScene.FeatureKind.TREE || feature.kind() == BoardScene.FeatureKind.BUILDING)
                  .map(BoardScene.Feature::height).max(Float::compare).orElse(0f);
            volume.height = BoardGeometry.level() * (SMOKE_LEVELS + fuelHeight);
            sources.put(tile.coords(), volume);
        }
    }

    int size() { return sources.size(); }
    int visibleCount() { return visible.size(); }
    int lodCount(int level) { return lodCounts[level]; }
    int drawCalls() { return drawCalls; }

    /** Borrow only this frame's clipped opacity; an empty/offscreen field must not outline units using stale smoke. */
    Texture opacityTexture() { return drawCalls == 0 || buffer == null ? null : buffer.getColorBufferTexture(); }

    /** Uses the existing three-level board-prop thresholds/hysteresis; geometry and gameplay do not change with LOD. */
    static int lod(float pixels, int previous) { return TreeLod.level(pixels, previous); }

    void render(Camera camera, GpuEffectDepth depth, Color light, Vector3 lightDirection) {
        visible.clear();
        java.util.Arrays.fill(lodCounts, 0);
        drawCalls = 0;
        float windStrength = motion.wind.len();
        for (Volume volume : sources.values()) {
            center.set(volume.origin).add(motion.wind.x * volume.height * .5f,
                  motion.wind.y * volume.height * .5f, volume.height * .5f);
            float radius = volume.radius * (1 + SPREAD) + volume.height * (.5f + windStrength * .5f);
            if (!camera.frustum.sphereInFrustum(center, radius)) { continue; }
            // Select LOD from the core diameter, excluding the soft joining margin.
            float pixels = BoardGeometry.width() * .86f * BoardCamera.pixelsPerUnit(camera, center);
            volume.lod = lod(pixels, volume.lod);
            volume.depth = center.dot(camera.direction);
            visible.add(volume);
            lodCounts[volume.lod]++;
        }
        if (visible.isEmpty()) { return; }
        visible.sort(BACK_TO_FRONT);
        initialize();
        Texture opaqueDepth = depth.capture();
        int width = Math.max(1, (depth.width + 1) / 2), height = Math.max(1, (depth.height + 1) / 2);
        if (buffer == null || buffer.getWidth() != width || buffer.getHeight() != height) {
            if (buffer != null) { buffer.dispose(); }
            buffer = GpuAtmosphere.buffer(width, height, false);
            buffer.getColorBufferTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        }
        try {
            buffer.begin();
            Gdx.gl.glClearColor(0, 0, 0, 0);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
            Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthMask(false);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glEnable(GL20.GL_CULL_FACE);
            Gdx.gl.glCullFace(GL20.GL_FRONT);
            opaqueDepth.bind(0);
            noise.bind(1);
            shader.bind();
            shader.setUniformi("u_depth", 0);
            shader.setUniformi("u_noise", 1);
            shader.setUniformMatrix("u_projView", camera.combined);
            shader.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
            shader.setUniformf("u_size", width, height);
            shader.setUniformf("u_wind", motion.wind);
            shader.setUniformf("u_offset", motion.offset);
            shader.setUniformf("u_light", light.r, light.g, light.b);
            shader.setUniformf("u_sun", sun.set(lightDirection).scl(-1));
            shader.setUniformf("u_heights", FLAME_LEVELS * BoardGeometry.level(), SMOKE_LEVELS * BoardGeometry.level());
            shader.setUniformf("u_spread", SPREAD);
            for (int start = 0; start < visible.size(); start += BATCH_SIZE) {
                int count = Math.min(BATCH_SIZE, visible.size() - start);
                upload(start, count);
                mesh.render(shader, GL20.GL_TRIANGLES, 0, count * 36);
                drawCalls++;
            }
            depth.restoreTarget();
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            buffer.getColorBufferTexture().bind(1);
            opaqueDepth.bind(0);
            composite.bind();
            composite.setUniformi("u_depth", 0);
            composite.setUniformi("u_effect", 1);
            composite.setUniformf("u_size", width, height);
            composite.setUniformf("u_viewport", depth.x, depth.y, depth.width, depth.height);
            composite.setUniformMatrix("u_inverseProjView", camera.invProjectionView);
            quad.render(composite, GL20.GL_TRIANGLES);
            drawCalls++;
        } finally {
            depth.restoreTarget();
            Gdx.gl.glDepthMask(true);
            Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
            Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
            Gdx.gl.glCullFace(GL20.GL_BACK);
            Gdx.gl.glDisable(GL20.GL_CULL_FACE);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            Gdx.gl.glDisable(GL20.GL_BLEND);
            Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        }
    }

    private void upload(int start, int count) {
        int offset = 0;
        for (int i = start; i < start + count; i++) {
            Volume volume = visible.get(i);
            for (int corner = 0; corner < 8; corner++) {
                vertices[offset++] = (corner == 1 || corner == 2 || corner == 5 || corner == 6) ? 1 : -1;
                vertices[offset++] = corner % 4 >= 2 ? 1 : -1;
                vertices[offset++] = corner >= 4 ? 1 : 0;
                vertices[offset++] = volume.origin.x;
                vertices[offset++] = volume.origin.y;
                vertices[offset++] = volume.origin.z;
                vertices[offset++] = volume.radius;
                vertices[offset++] = volume.height;
                vertices[offset++] = volume.effect.flame();
                vertices[offset++] = volume.effect.density();
                vertices[offset++] = volume.effect.palette();
                vertices[offset++] = volume.lod;
            }
        }
        mesh.setVertices(vertices, 0, offset);
    }

    private void initialize() {
        if (shader != null) { return; }
        try {
            shader = GpuShaderManager.program(() -> GpuGlsl.compile("GPU terrain fire/smoke",
                  GpuShaderSource.read("terrain-effects.vert"), GpuShaderSource.read("terrain-effects.frag")), next -> shader = next);
            composite = GpuShaderManager.program(() -> GpuAtmosphere.shader("terrain-effects-composite.frag"), next -> composite = next);
            quad = GpuAtmosphere.screenQuad();
            noise = GpuAtmosphere.noise();
            mesh = new Mesh(false, BATCH_SIZE * 8, BATCH_SIZE * 36, VertexAttribute.Position(),
                  new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_origin"),
                  new VertexAttribute(VertexAttributes.Usage.Generic, 4, "a_shape"),
                  new VertexAttribute(VertexAttributes.Usage.Generic, 1, "a_lod"));
            short[] indices = new short[BATCH_SIZE * 36];
            int[] cube = { 0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7,
                  0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7 };
            for (int i = 0; i < indices.length; i++) { indices[i] = (short) (i / 36 * 8 + cube[i % 36]); }
            mesh.setIndices(indices);
        } catch (RuntimeException failure) { dispose(); throw failure; }
    }

    @Override
    public void dispose() {
        if (mesh != null) { mesh.dispose(); mesh = null; }
        if (quad != null) { quad.dispose(); quad = null; }
        if (shader != null) { GpuShaderManager.dispose(shader); shader = null; }
        if (composite != null) { GpuShaderManager.dispose(composite); composite = null; }
        if (noise != null) { noise.dispose(); noise = null; }
        if (buffer != null) { buffer.dispose(); buffer = null; }
        sources.clear(); visible.clear(); tiles = null; motion.clear();
    }
}
