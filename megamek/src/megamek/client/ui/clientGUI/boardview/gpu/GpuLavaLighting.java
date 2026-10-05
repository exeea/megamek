/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Vector3;

/** Shared bounded spill light from installed lava and luminous fungi. No new simulation or textures. */
final class GpuLavaLighting extends Attribute {
    static final long TYPE = register("boardLavaLighting");
    static final int MAX_LIGHTS = 32;
    record Source(int tile, Vector3 position, float power, Vector3 color, float size) { }
    private record Candidate(Source source, float distance) { }
    private List<BoardScene.Tile> tiles;
    private List<Source> sources = List.of();
    private List<Source> fungus = List.of();
    private final float[] lights = new float[MAX_LIGHTS * 4];
    private final float[] colors = new float[MAX_LIGHTS * 4];
    private int count;
    private long revision;
    private float width;
    private float level;

    GpuLavaLighting() { super(TYPE); }

    /** Chunk installation replaces the whole derived prop-light snapshot together with its rendered meshes. */
    void setFungus(List<Source> sources) { fungus = List.copyOf(sources); }

    /** Derive emitters from the installed snapshot; visibility comes from the same client's captured FoV. */
    void update(BoardScene scene, Camera camera) {
        update(scene, camera, true);
    }

    void update(BoardScene scene, Camera camera, boolean showFungus) {
        count = 0;
        revision++;
        if (scene == null) return;
        if (tiles != scene.tiles() || width != BoardGeometry.width() || level != BoardGeometry.level()) {
            tiles = scene.tiles();
            width = BoardGeometry.width();
            level = BoardGeometry.level();
            var next = new ArrayList<Source>();
            for (int i = 0; i < tiles.size(); i++) {
                var tile = tiles.get(i);
                if (!tile.liquid().volcanic()) continue;
                var point = BoardGeometry.center(tile.coords(), tile.elevation());
                if (tile.liquid().molten()) point.z = BoardGeometry.waterZ(tile);
                point.z += BoardRelief.metres(.7f);
                next.add(new Source(i, point, tile.liquid().molten() ? 1f : .035f,
                      new Vector3(1.8f, .24f, .025f), width));
            }
            sources = List.copyOf(next);
        }
        if (sources.isEmpty() && (!showFungus || fungus.isEmpty())) return;
        var candidates = new ArrayList<Candidate>();
        var fov = scene.fieldOfView();
        boolean masked = fov.width() == scene.width() && fov.height() == scene.height() && fov.active();
        for (List<Source> group : List.of(sources, showFungus ? fungus : List.<Source>of())) {
            for (Source source : group) {
                if (masked && !fov.hexes().get(source.tile()).hasLineOfSight()) continue;
                if (!camera.frustum.sphereInFrustum(source.position(), source.size() * 2)) continue;
                // Distance to the central view ray also works for the distant eye of an orthographic camera.
                var offset = new Vector3(source.position()).sub(camera.position);
                float along = offset.dot(camera.direction);
                float distance = Math.max(0, offset.len2() - along * along);
                // Tiny scatter glints must not consume the budget ahead of a nearby mature colony or lava field.
                candidates.add(new Candidate(source, distance / (source.size() * source.size() * source.power())));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        count = Math.min(MAX_LIGHTS, candidates.size());
        float fadeStart = count == MAX_LIGHTS ? candidates.get(MAX_LIGHTS - 8).distance() : 0;
        float fadeEnd = candidates.size() > MAX_LIGHTS ? candidates.get(MAX_LIGHTS).distance() : Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            Source source = candidates.get(i).source();
            int at = i * 4;
            lights[at] = source.position().x;
            lights[at + 1] = source.position().y;
            lights[at + 2] = source.position().z;
            // Fade the budget's outer ring instead of popping full-strength sources at its boundary.
            float fade = candidates.size() <= MAX_LIGHTS ? 1f
                  : Math.clamp((fadeEnd - candidates.get(i).distance()) / Math.max(1e-6f, fadeEnd - fadeStart), 0, 1);
            lights[at + 3] = source.power() * fade;
            colors[at] = source.color().x;
            colors[at + 1] = source.color().y;
            colors[at + 2] = source.color().z;
            colors[at + 3] = source.size();
        }
    }

    static void register(DefaultShader shader) {
        shader.register("u_lavaCount", new BaseShader.LocalSetter() {
            private GpuLavaLighting previous;
            private long uploaded = -1;

            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var field = attributes.get(GpuLavaLighting.class, TYPE);
                if (field == null) { target.set(id, 0); previous = null; return; }
                if (field == previous && uploaded == field.revision) return;
                previous = field;
                uploaded = field.revision;
                target.set(id, field.count);
                if (field.count > 0) {
                    target.program.setUniform4fv("u_lavaLights[0]", field.lights, 0, field.count * 4);
                    target.program.setUniform4fv("u_lavaColors[0]", field.colors, 0, field.count * 4);
                }
            }
        });
    }

    @Override
    public Attribute copy() {
        var copy = new GpuLavaLighting();
        copy.tiles = tiles;
        copy.sources = sources;
        copy.fungus = fungus;
        copy.count = count;
        copy.width = width;
        copy.level = level;
        copy.revision = revision;
        System.arraycopy(lights, 0, copy.lights, 0, lights.length);
        System.arraycopy(colors, 0, copy.colors, 0, colors.length);
        return copy;
    }

    @Override
    public int compareTo(Attribute other) { return Long.compare(type, other.type); }
}
