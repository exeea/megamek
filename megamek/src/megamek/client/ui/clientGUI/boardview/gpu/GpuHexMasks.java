/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.utils.RenderContext;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import megamek.client.ui.clientGUI.boardview.BoardTactical;
import megamek.common.board.Coords;

/** Regular hex borders mask shared finished terrain, without clipping or retaining geometry per drawing command. */
final class GpuHexMasks implements Disposable {
    static final float DEPLOYMENT_TINT_ALPHA = .18f;
    private record Entry(BoardTactical.Fill fill, Coords coords, int layer) { }
    private record Layer(Texture color, Texture parameters, Set<Coords> chunks) implements Disposable {
        @Override
        public void dispose() { color.dispose(); parameters.dispose(); }
    }

    private final Map<Coords, GpuHexSurface> surfaces = new HashMap<>();
    private final Map<Coords, BoundingBox> bounds = new HashMap<>();
    private final Material material = new Material(new BlendingAttribute(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA));
    private Layer layer;
    private List<BoardTactical.Fill> previous = List.of();
    private BoardScene scene;
    private Function<Coords, BoardTacticalGeometry.Surface> terrain;
    private MaskShader shader;
    private int tuning = -1;
    private boolean tint;

    /**
     * A complete opaque regular-border pass has independent hex footprints. Translucent/overlapping borders and
     * interleaved artwork keep their original geometry and sorting centers, preserving painter order and clipping.
     */
    boolean update(BoardScene next, Function<Coords, BoardTacticalGeometry.Surface> finished) {
        return update(next, finished, true);
    }

    boolean update(BoardScene next, Function<Coords, BoardTacticalGeometry.Surface> finished, boolean enabled) {
        List<BoardTactical.Fill> commands = next.tactical().fills();
        List<Entry> entries = enabled ? entries(next, commands) : null;
        if (!next.tactical().walls().isEmpty() || !next.tactical().flatWalls().isEmpty()) { entries = null; }
        return update(next, commands, entries, finished, false);
    }

    /** Tint each captured deployment hex's complete terrain, including its slopes and cliff faces. */
    void updateDeployment(BoardScene next, Collection<BoardTactical.Fill> commands,
          Function<Coords, BoardTacticalGeometry.Surface> finished) {
        List<BoardTactical.Fill> captured = List.copyOf(commands);
        List<Entry> entries = new ArrayList<>();
        for (BoardTactical.Fill fill : captured) {
            Coords coords = BoardTacticalGeometry.borderCoords(next, fill.border());
            if (coords != null) { entries.add(new Entry(fill, coords, 0)); }
        }
        update(next, captured, entries, finished, true);
    }

    private boolean update(BoardScene next, List<BoardTactical.Fill> commands, List<Entry> entries,
          Function<Coords, BoardTacticalGeometry.Surface> finished, boolean tint) {
        if (entries == null || entries.isEmpty() || Gdx.gl30 == null) {
            clear();
            return false;
        }
        boolean reset = scene == null || scene.boardId() != next.boardId() || scene.width() != next.width()
              || scene.height() != next.height() || tuning != BoardGeometry.revision() || this.tint != tint;
        if (reset) { clear(); }
        if (scene != null && (scene.tiles() != next.tiles() || terrain != finished)) { bounds.clear(); }
        scene = next;
        terrain = finished;
        this.tint = tint;
        tuning = BoardGeometry.revision();
        surfaces.entrySet().removeIf(entry -> {
            if (entry.getValue().current(finished)) { return false; }
            entry.getValue().dispose();
            bounds.remove(entry.getKey());
            return true;
        });
        if (commands.equals(previous)) { return true; }
        Layer replacement = layer(next, entries, tint);
        if (layer != null) { layer.dispose(); }
        layer = replacement;
        previous = commands;
        Set<Coords> active = layer.chunks();
        bounds.keySet().retainAll(active);
        surfaces.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) { return false; }
            entry.getValue().dispose();
            return true;
        });
        return true;
    }

    private static List<Entry> entries(BoardScene scene, List<BoardTactical.Fill> commands) {
        List<Entry> result = new ArrayList<>();
        Set<Coords> owners = new HashSet<>();
        for (int index = 0; index < commands.size(); index++) {
            BoardTactical.Fill fill = commands.get(index);
            BoardTactical.HexBorder border = fill.border();
            if (border == null || border.floating() || fill.argb() >>> 24 != 255 || border.scale() != 1 || border.padding() < 0
                  || border.width() <= 0 || border.padding() + border.width() >= BoardGeometry.TILE_WIDTH * Math.sqrt(3) / 4) {
                return null;
            }
            Coords coords = BoardTacticalGeometry.borderCoords(scene, border);
            if (coords == null || !owners.add(coords)
                  || Math.abs(border.anchor().x() - BoardGeometry.centerX(coords) / BoardGeometry.HEX_SCALE) > .0001f
                  || Math.abs(border.anchor().y() + BoardGeometry.centerY(coords) / BoardGeometry.HEX_SCALE) > .0001f) {
                return null;
            }
            result.add(new Entry(fill, coords, index));
        }
        return result;
    }

    private static Layer layer(BoardScene scene, List<Entry> entries, boolean tint) {
        Pixmap colors = new Pixmap(scene.width(), scene.height(), Pixmap.Format.RGBA8888);
        colors.setBlending(Pixmap.Blending.None);
        float[] parameters = new float[scene.width() * scene.height() * 4];
        Set<Coords> chunks = new java.util.LinkedHashSet<>();
        Texture color = null, values = null;
        try {
            for (Entry entry : entries) {
                int x = entry.coords().getX(), y = entry.coords().getY(), argb = entry.fill().argb();
                // Match DefaultShader's packed vertex alpha, which reserves the low bit to avoid NaN floats.
                int alpha = tint ? Math.round((argb >>> 24) * DEPLOYMENT_TINT_ALPHA) : argb >>> 24;
                colors.drawPixel(x, y, (argb << 8) | (alpha & 254));
                int offset = (y * scene.width() + x) * 4;
                parameters[offset] = tint ? 0 : (float) entry.fill().border().padding();
                parameters[offset + 1] = tint ? BoardGeometry.TILE_WIDTH : (float) entry.fill().border().width();
                parameters[offset + 2] = tint ? 0 : BoardTacticalGeometry.layerLift(entry.layer());
                chunks.add(new Coords(x / GpuTerrain.CHUNK_SIZE, y / GpuTerrain.CHUNK_SIZE));
            }
            color = new Texture(colors);
            values = new Texture(scene.width(), scene.height(), Pixmap.Format.RGBA8888);
            values.bind();
            var buffer = BufferUtils.newFloatBuffer(parameters.length);
            buffer.put(parameters).flip();
            Gdx.gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, scene.width(), scene.height(), 0,
                  GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
            color.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            values.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
            return new Layer(color, values, chunks);
        } catch (RuntimeException | Error failure) {
            if (color != null) { color.dispose(); }
            if (values != null) { values.dispose(); }
            throw failure;
        } finally {
            colors.dispose();
        }
    }

    void submit(ModelBatch batch, Camera camera) {
        if (layer == null) { return; }
        if (shader == null) { shader = new MaskShader(); }
        for (Coords coords : layer.chunks()) {
            BoundingBox box = bounds.computeIfAbsent(coords, key -> GpuHexSurface.bounds(scene, key, terrain, tint));
            if (!camera.frustum.boundsInFrustum(box)) { continue; }
            GpuHexSurface surface = surfaces.computeIfAbsent(coords, key -> new GpuHexSurface(scene, key, terrain, tint));
            surface.submit(batch, camera, shader, material, layer);
        }
    }

    /** Uses the same transparent sorting and GL-state owner as ordinary tactical pages and unit icons. */
    private final class MaskShader implements Shader {
        private final ShaderProgram program;
        private RenderContext context;

        MaskShader() {
            String path = "megamek/client/ui/clientGUI/boardview/gpu/";
            program = new ShaderProgram(Gdx.files.classpath(path + "hex-mask.vert").readString("UTF-8"),
                  Gdx.files.classpath(path + "hex-mask.frag").readString("UTF-8"));
            if (!program.isCompiled()) {
                String log = program.getLog();
                program.dispose();
                throw new IllegalStateException("GPU hex mask shader: " + log);
            }
        }

        @Override
        public void init() { }

        @Override
        public void begin(Camera camera, RenderContext context) {
            this.context = context;
            program.bind();
            program.setUniformMatrix("u_projTrans", camera.combined);
            program.setUniformf("u_board", scene.width(), scene.height(), BoardGeometry.TILE_WIDTH, BoardGeometry.TILE_HEIGHT);
            program.setUniformf("u_hexScale", BoardGeometry.HEX_SCALE);
            context.setDepthTest(GL20.GL_LEQUAL, 0, tint ? GpuTactical.COPLANAR_DEPTH_FAR : 1);
            context.setDepthMask(false);
            context.setCullFace(GL20.GL_NONE);
            context.setBlending(true, GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        }

        @Override
        public void render(Renderable renderable) {
            Layer layer = (Layer) renderable.userData;
            program.setUniformi("u_color", context.textureBinder.bind(layer.color()));
            program.setUniformi("u_parameters", context.textureBinder.bind(layer.parameters()));
            renderable.meshPart.render(program);
        }

        @Override
        public void end() { }

        @Override
        public boolean canRender(Renderable renderable) { return renderable.shader == this; }

        @Override
        public int compareTo(Shader other) { return 0; }

        @Override
        public void dispose() { program.dispose(); }
    }

    boolean active() { return layer != null; }

    long bytes() { return surfaces.values().stream().mapToLong(GpuHexSurface::bytes).sum(); }

    private void clear() {
        if (layer != null) { layer.dispose(); }
        layer = null;
        surfaces.values().forEach(GpuHexSurface::dispose);
        surfaces.clear();
        bounds.clear();
        previous = List.of();
        scene = null;
        terrain = null;
    }

    @Override
    public void dispose() {
        clear();
        if (shader != null) { shader.dispose(); shader = null; }
    }
}
