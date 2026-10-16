/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.glutils.GLOnlyTextureData;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

/**
 * Shared local light: installed lava, burning hexes and luminous fungi, and each frame's fireballs and muzzle blasts.
 * Every visible emitter is binned on the board plane: each grid cell lists the emitters whose reach overlaps it, so a
 * lit pixel evaluates only those (lava-lighting.glsl).
 */
final class GpuLavaLighting extends Attribute implements Disposable {
    static final long TYPE = register("boardLavaLighting");
    /** Texels per row of the list texture; lava-lighting.glsl addresses it with the same width. */
    private static final int ROW = 1024;
    /** Integers stored as floats stay exact below 2^24, which is also 16384 rows, the common maximum texture height. */
    private static final int MAX_TEXELS = 1 << 24;
    /** An emitter; {@code tile} indexes the scene's tiles for the field-of-view mask, or is negative for an effect. */
    record Source(int tile, Vector3 position, float power, Vector3 color, float size) { }
    /** A burning hex's flames: warmer and dimmer than molten lava, reaching about two hexes. */
    private static final Vector3 FIRE = new Vector3(2f, .8f, .22f);
    private final GpuAssets assets; // Borrowed from the same owner as the rendered material.
    private List<BoardScene.Tile> tiles;
    private List<Source> sources = List.of();
    private List<Source> fungus = List.of();
    /** This frame's effect lights, replaced whole by the render thread; shown wherever the effect is drawn. */
    private List<Source> effects = List.of();
    private float width;
    private float level;
    // The binned snapshot: replaced whole on the render thread when the visible emitters change, never mutated.
    private List<Source> binned = List.of();
    /** Whole rows of texels: one per grid cell (first entry texel, count), then two per entry (as in the shader). */
    private float[] list = new float[ROW * 4];
    private int columns;
    private int rows;
    private float originX;
    private float originY;
    private float cellsPerUnit;
    private long revision;
    // Owned GL copy of the list, uploaded at the first draw after a change.
    private Texture texture;
    private long uploaded = -1;

    GpuLavaLighting(GpuAssets assets) { super(TYPE); this.assets = assets; }

    /** Chunk installation replaces the whole derived prop-light snapshot together with its rendered meshes. */
    void setFungus(List<Source> sources) { fungus = List.copyOf(sources); }

    /** Each frame's fireballs and muzzle blasts (GpuAttackEffects.lights); empty when nothing burns. */
    void setEffects(List<Source> sources) { effects = sources; }

    /** Derive emitters from the installed snapshot; visibility comes from the same client's captured FoV. */
    void update(BoardScene scene) {
        update(scene, true);
    }

    void update(BoardScene scene, boolean showFungus) {
        if (scene == null) { bin(List.of()); return; }
        if (tiles != scene.tiles() || width != BoardGeometry.width() || level != BoardGeometry.level()) {
            tiles = scene.tiles();
            width = BoardGeometry.width();
            level = BoardGeometry.level();
            var next = new ArrayList<Source>();
            for (int i = 0; i < tiles.size(); i++) {
                var tile = tiles.get(i);
                if (tile.fireSmoke().fire() > 0) {
                    var flames = BoardGeometry.center(tile.coords(), tile.elevation());
                    flames.z += BoardRelief.metres(4);
                    next.add(new Source(i, flames, .2f + .08f * tile.fireSmoke().fire(), FIRE, width * .9f));
                }
                if (!tile.liquid().volcanic()) continue;
                var point = BoardGeometry.center(tile.coords(), tile.elevation());
                boolean molten = tile.liquid().molten();
                if (molten) point.z = BoardGeometry.waterZ(tile);
                point.z += BoardRelief.metres(.7f);
                // The same authored source as the surface, averaged once by its asset owner before shore attenuation.
                // The existing disk/falloff remains an area-light approximation; solid spill is unchanged.
                GpuAssets.LavaEmission emission = molten ? assets.lavaEmission() : null;
                if (molten && emission.red() == 0 && emission.green() == 0 && emission.blue() == 0) { continue; }
                Vector3 color = molten ? new Vector3(emission.red(), emission.green(), emission.blue())
                      : new Vector3(1.8f, .24f, .025f);
                next.add(new Source(i, point, molten ? 1f : .035f, color, width));
            }
            sources = List.copyOf(next);
        }
        var visible = new ArrayList<Source>();
        var fov = scene.fieldOfView();
        boolean masked = fov.width() == scene.width() && fov.height() == scene.height() && fov.active();
        for (List<Source> group : List.of(sources, showFungus ? fungus : List.<Source>of(), effects)) {
            for (Source source : group) {
                if (!masked || source.tile() < 0 || fov.hexes().get(source.tile()).hasLineOfSight()) {
                    visible.add(source);
                }
            }
        }
        bin(visible);
    }

    /** Lists each emitter in every cell its reach overlaps; the same emitters keep the current list and upload. */
    private void bin(List<Source> visible) {
        if (visible.size() == binned.size()) {
            int same = 0;
            while (same < visible.size() && visible.get(same) == binned.get(same)) { same++; }
            if (same == visible.size()) { return; }
        }
        binned = visible;
        revision++;
        if (visible.isEmpty()) {
            columns = 0;
            rows = 0;
            list = new float[ROW * 4];
            return;
        }
        float cell = BoardGeometry.width();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Source source : visible) {
            float reach = reach(source);
            minX = Math.min(minX, source.position().x - reach);
            minY = Math.min(minY, source.position().y - reach);
            maxX = Math.max(maxX, source.position().x + reach);
            maxY = Math.max(maxY, source.position().y + reach);
        }
        originX = minX;
        originY = minY;
        cellsPerUnit = 1 / cell;
        columns = (int) ((maxX - minX) / cell) + 1;
        rows = (int) ((maxY - minY) / cell) + 1;
        int cells = columns * rows;
        // Counts per cell first, then each cell's next free entry texel while filling.
        int[] next = new int[cells];
        int entries = 0;
        for (Source source : visible) { entries += overlapped(source, cell, at -> next[at]++); }
        // Entries start on an even texel, so each entry's two texels share a row.
        int start = cells + (cells & 1);
        long texels = start + 2L * entries;
        if (texels > MAX_TEXELS) { throw new IllegalStateException("Local light list exceeds " + MAX_TEXELS + " texels"); }
        float[] packed = new float[(int) ((texels + ROW - 1) / ROW) * ROW * 4];
        for (int at = 0, first = start; at < cells; at++) {
            int count = next[at];
            packed[at * 4] = first;
            packed[at * 4 + 1] = count;
            next[at] = first;
            first += 2 * count;
        }
        for (Source source : visible) {
            overlapped(source, cell, at -> {
                int texel = next[at] * 4;
                next[at] += 2;
                packed[texel] = source.position().x;
                packed[texel + 1] = source.position().y;
                packed[texel + 2] = source.position().z;
                packed[texel + 3] = source.power();
                packed[texel + 4] = source.color().x;
                packed[texel + 5] = source.color().y;
                packed[texel + 6] = source.color().z;
                packed[texel + 7] = source.size();
            });
        }
        list = packed;
    }

    /**
     * The source's light reaches exactly zero at twice its size (lava-lighting.glsl's reach term), in 3D and so also
     * on the board plane. The 0.1% margin keeps float rounding in the shader's cell and distance from excluding a
     * pixel the light still touches.
     */
    private static float reach(Source source) { return 2 * Math.max(source.size(), .001f) * 1.001f; }

    /** Visits each cell whose square the source's reach disk overlaps; returns the number of cells. */
    private int overlapped(Source source, float cell, IntConsumer action) {
        float reach = reach(source), x = source.position().x - originX, y = source.position().y - originY;
        int x0 = Math.max(0, (int) Math.floor((x - reach) / cell));
        int x1 = Math.min(columns - 1, (int) Math.floor((x + reach) / cell));
        int y0 = Math.max(0, (int) Math.floor((y - reach) / cell));
        int y1 = Math.min(rows - 1, (int) Math.floor((y + reach) / cell));
        int count = 0;
        for (int row = y0; row <= y1; row++) {
            float dy = Math.max(0, Math.max(row * cell - y, y - (row + 1) * cell));
            for (int column = x0; column <= x1; column++) {
                float dx = Math.max(0, Math.max(column * cell - x, x - (column + 1) * cell));
                if (dx * dx + dy * dy <= reach * reach) {
                    action.accept(row * columns + column);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The list texture, uploaded at the first draw after a change. Uploads use texture unit 0, which ModelBatch's
     * texture binder never assigns (its units start at 1), so the draw's tracked sampler bindings stay intact.
     */
    private Texture texture() {
        if (texture != null && uploaded == revision) { return texture; }
        int height = list.length / (ROW * 4);
        Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
        if (texture == null || texture.getHeight() < height) {
            if (texture != null) { texture.dispose(); }
            int capacity = 1;
            while (capacity < height) { capacity <<= 1; }
            texture = new Texture(new GLOnlyTextureData(ROW, capacity, 0, GL30.GL_RGBA32F, GL20.GL_RGBA, GL20.GL_FLOAT));
        }
        texture.bind();
        var pixels = BufferUtils.newFloatBuffer(list.length);
        pixels.put(list).flip();
        Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, ROW, height, GL20.GL_RGBA, GL20.GL_FLOAT, pixels);
        uploaded = revision;
        return texture;
    }

    static void register(DefaultShader shader) {
        int grid = shader.register("u_lavaGrid");
        shader.register("u_lavaCells", new BaseShader.LocalSetter() {
            private GpuLavaLighting previous;
            private long uploaded = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = attributes.get(GpuLavaLighting.class, TYPE);
                if (field == null) { target.set(id, 0, 0); previous = null; return; }
                if (field == previous && uploaded == field.revision) return;
                previous = field;
                uploaded = field.revision;
                target.set(id, field.columns, field.rows);
                target.set(grid, field.originX, field.originY, field.cellsPerUnit);
            }
        });
        shader.register("u_lavaLights", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = attributes.get(GpuLavaLighting.class, TYPE);
                // Without the attribute nothing is fetched, but a stale unit may now hold another sampler type and
                // invalidate the draw. Unit 0 is outside ModelBatch's binder.
                if (field != null) { target.set(id, field.texture()); } else { target.set(id, 0); }
            }
        });
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); texture = null; }
        uploaded = -1;
    }

    /** Shares the immutable snapshot; the copy uploads and owns its own texture. */
    @Override
    public Attribute copy() {
        var copy = new GpuLavaLighting(assets);
        copy.tiles = tiles;
        copy.sources = sources;
        copy.fungus = fungus;
        copy.effects = effects;
        copy.width = width;
        copy.level = level;
        copy.binned = binned;
        copy.list = list;
        copy.columns = columns;
        copy.rows = rows;
        copy.originX = originX;
        copy.originY = originY;
        copy.cellsPerUnit = cellsPerUnit;
        copy.revision = revision;
        return copy;
    }

    @Override
    public int compareTo(Attribute other) { return Long.compare(type, other.type); }
}
