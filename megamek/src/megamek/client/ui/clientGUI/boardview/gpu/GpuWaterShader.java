/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import megamek.common.board.Coords;

/**
 * Water material: palette, falling sheet and the receiving pool's impacts. Everything that varies across a hex
 * (depth, bank distance, current, rapids) comes from continuous inputs instead, so neighbouring hexes of any depth
 * or current share one seamless surface: the chunk's {@link Field}, per-vertex agitation and the shared detail map.
 */
final class GpuWaterShader extends Attribute {
    /** True derives water color from each pixel's depth; false restores the authored Saxarba GIF colors. */
    static final boolean USE_PROCEDURAL_WATER = true;
    /** Opacity of the authored GIF color; procedural water derives its opacity from the depth of each pixel. */
    static final float SURFACE_OPACITY = 0.48f;
    static final long TYPE = register("boardWaterSurface");
    /** Palette indices shared with water-optics.glsl and the submerged bed's vertex color. */
    static final int CLEAR = 0, MARS = 1, VOLCANO = 2, HAZARDOUS = 3;
    /** Depth encoded by the field and by the bed's vertex color, in levels; deeper water is uniformly deep. */
    static final float DEPTH_RANGE = 4;
    /** Signed bank distance encoded by the field, in hex widths; banks further away no longer shape the surface. */
    private static final float SHORE_RANGE = 0.4f;
    /** Currents are encoded in hex widths per second; the fastest approach to a fall stays well inside it. */
    private static final float CURRENT_RANGE = 1;
    /** Radius of the kernel that blends neighbouring currents and rapids, in hex widths. */
    private static final float BLEND_RADIUS = 0.9f;
    private static final int DETAIL_SIZE = 256;

    /** Where a fall lands: the middle of its mouth, into the pool, half the mouth's width, reach and drop height. */
    record Impact(Vector3 center, Vector3 inward, float halfWidth, float radius, float drop) { }

    private final int palette;
    private final boolean falling;
    private final boolean procedural;
    private final Field field;
    final List<Impact> impacts;

    GpuWaterShader(BoardScene scene, BoardSurface surface, boolean procedural, boolean falling, Field field) {
        super(TYPE);
        BoardScene.Tile tile = surface.tile;
        palette = palette(tile.liquid());
        this.falling = falling;
        this.procedural = procedural;
        this.field = field;
        List<Impact> hits = new ArrayList<>();
        if (!falling) {
            int segments = surface.water.size() / 6;
            for (int edge = 0; edge < 6; edge++) {
                BoardScene.Tile upstream = scene.tile(tile.coords().translated(BoardGeometry.edgeDirection(edge)));
                if (upstream == null || upstream.frozen() || !tile.liquid().connects(upstream.liquid())
                      || upstream.elevation() <= tile.elevation()) { continue; }
                Vector3 a = surface.water.get(edge * segments), b = surface.water.get(((edge + 1) % 6) * segments);
                Vector3 center = new Vector3(a).lerp(b, 0.5f);
                Vector3 inward = new Vector3(a).sub(b).crs(Vector3.Z).nor();
                float radius = (0.12f + 0.05f * (float) Math.sqrt(Math.min(4, upstream.elevation() - tile.elevation())))
                      * BoardGeometry.WIDTH;
                hits.add(new Impact(center, inward, a.dst(b) / 2, radius,
                      BoardGeometry.waterZ(upstream) - BoardGeometry.waterZ(tile)));
            }
        }
        impacts = List.copyOf(hits);
    }

    private GpuWaterShader(GpuWaterShader original) {
        super(TYPE);
        // The impacts are immutable and the chunk owns the field; copied materials share both.
        palette = original.palette;
        falling = original.falling;
        procedural = original.procedural;
        field = original.field;
        impacts = original.impacts;
    }

    static int palette(BoardLiquid liquid) {
        if (liquid.kind() == BoardLiquid.Kind.HAZARDOUS) { return HAZARDOUS; }
        return switch (liquid.theme()) {
            case "mars" -> MARS;
            case "volcano" -> VOLCANO;
            default -> CLEAR;
        };
    }

    static void register(DefaultShader shader) {
        shader.register("u_waterMaterial", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null) {
                    target.set(id, (float) water.palette, water.falling ? 1f : 0f, water.procedural ? 1f : 0f, 0f);
                }
            }
        });
        shader.register("u_waterField", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null && water.field != null) { target.set(id, water.field.texture); }
            }
        });
        shader.register("u_waterFieldMap", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null && water.field != null) {
                    Field field = water.field;
                    target.set(id, field.scaleX, field.scaleY, field.offsetX, field.offsetY);
                }
            }
        });
        shader.register("u_splashCount", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                var water = attributes.get(GpuWaterShader.class, TYPE);
                if (water != null) { target.set(id, water.impacts.size()); }
            }
        });
        for (int index = 0; index < 6; index++) {
            final int impactIndex = index;
            shader.register("u_splashEdges[" + index + "]", new BaseShader.LocalSetter() {
                @Override
                public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                    var water = attributes.get(GpuWaterShader.class, TYPE);
                    if (water != null && impactIndex < water.impacts.size()) {
                        Impact impact = water.impacts.get(impactIndex);
                        float scale = 1 / BoardGeometry.WIDTH;
                        target.set(id, impact.center().x * scale, impact.center().y * scale,
                              impact.inward().x * impact.radius() * scale, impact.inward().y * impact.radius() * scale);
                    }
                }
            });
        }
    }

    @Override
    public GpuWaterShader copy() { return new GpuWaterShader(this); }

    @Override
    public int hashCode() {
        int result = 31 * super.hashCode() + palette;
        result = 31 * result + (falling ? 1 : 0) + (procedural ? 2 : 0);
        result = 31 * result + System.identityHashCode(field);
        return 31 * result + impacts.hashCode();
    }

    @Override
    public int compareTo(Attribute other) {
        if (type != other.type) { return Long.compare(type, other.type); }
        GpuWaterShader water = (GpuWaterShader) other;
        int comparison = Integer.compare(palette, water.palette);
        if (comparison == 0) { comparison = Boolean.compare(falling, water.falling); }
        if (comparison == 0) { comparison = Boolean.compare(procedural, water.procedural); }
        if (comparison == 0) {
            comparison = Integer.compare(System.identityHashCode(field), System.identityHashCode(water.field));
        }
        if (comparison == 0) { comparison = Integer.compare(impacts.size(), water.impacts.size()); }
        for (int i = 0; comparison == 0 && i < impacts.size(); i++) {
            Impact a = impacts.get(i), b = water.impacts.get(i);
            comparison = Float.compare(a.center().x, b.center().x);
            if (comparison == 0) { comparison = Float.compare(a.center().y, b.center().y); }
            if (comparison == 0) { comparison = Float.compare(a.inward().x, b.inward().x); }
            if (comparison == 0) { comparison = Float.compare(a.inward().y, b.inward().y); }
            if (comparison == 0) { comparison = Float.compare(a.halfWidth(), b.halfWidth()); }
            if (comparison == 0) { comparison = Float.compare(a.radius(), b.radius()); }
        }
        return comparison;
    }

    /**
     * Shared tiling detail maps, built once: RG ripple slope, B foam noise, A caustic network. Every channel repeats
     * seamlessly, and mipmaps average the slopes, so distant water calms into a clean reflection instead of aliasing.
     */
    static Texture detailTexture() {
        int size = DETAIL_SIZE;
        float[] slopeX = new float[size * size], slopeY = new float[size * size];
        Random random = new Random(0x7761746572L);
        // Random-phase waves on integer wave vectors: a small wind-driven ripple spectrum that tiles in both
        // directions. Spread about one axis, the shader's downwind, crests run in short trains instead of bumps, yet
        // cross each other widely enough never to line up into ruled stripes.
        waves(random, 128, 3, 26, 2.2f, 1.3f, slopeX, slopeY);
        float[] foam = foam(random, size);
        float squared = 0;
        for (int i = 0; i < slopeX.length; i++) { squared += slopeX[i] * slopeX[i] + slopeY[i] * slopeY[i]; }
        // Three standard deviations fill the encoding; rarer, steeper facets saturate harmlessly.
        float range = 3 * (float) Math.sqrt(squared / (2 * slopeX.length));
        float low = Float.POSITIVE_INFINITY, high = Float.NEGATIVE_INFINITY;
        for (float value : foam) { low = Math.min(low, value); high = Math.max(high, value); }
        float[] caustics = caustics(random, size);
        Pixmap pixels = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        try {
            ByteBuffer buffer = pixels.getPixels();
            for (int i = 0; i < slopeX.length; i++) {
                buffer.put(i * 4, unit(0.5f + 0.5f * slopeX[i] / range));
                buffer.put(i * 4 + 1, unit(0.5f + 0.5f * slopeY[i] / range));
                // Contrast leaves foam free to break into patches instead of a uniform haze.
                float froth = (foam[i] - low) / (high - low);
                buffer.put(i * 4 + 2, unit(froth * froth * (3 - 2 * froth)));
                buffer.put(i * 4 + 3, unit(caustics[i]));
            }
            Texture texture = new Texture(pixels, true);
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
            return texture;
        } finally {
            pixels.dispose();
        }
    }

    /** Sums wave slopes as separable angle additions: two table lookups per wave and texel, no trigonometry. */
    private static void waves(Random random, int count, float shortest, float longest, float falloff, float spread,
          float[] slopeX, float[] slopeY) {
        int size = DETAIL_SIZE;
        float[] cosX = new float[size], sinX = new float[size], cosY = new float[size], sinY = new float[size];
        for (int wave = 0; wave < count; wave++) {
            // Log-uniform wave numbers, in cycles per tile, with amplitude falling off toward short ripples.
            double number = shortest * Math.pow(longest / shortest, random.nextDouble());
            // A wave and its opposite share one axis; spread is the half-angle around it, most waves near the axis.
            double angle = (random.nextDouble() + random.nextDouble() - 1) * spread;
            int kx = (int) Math.round(number * Math.cos(angle)), ky = (int) Math.round(number * Math.sin(angle));
            if (kx == 0 && ky == 0) { continue; }
            float amplitude = (float) Math.pow(Math.hypot(kx, ky), -falloff);
            double phase = random.nextDouble() * Math.PI * 2;
            for (int i = 0; i < size; i++) {
                double x = Math.PI * 2 * kx * i / size, y = Math.PI * 2 * ky * i / size + phase;
                cosX[i] = (float) Math.cos(x);
                sinX[i] = (float) Math.sin(x);
                cosY[i] = (float) Math.cos(y);
                sinY[i] = (float) Math.sin(y);
            }
            float gradientX = -amplitude * (float) (Math.PI * 2 * kx);
            float gradientY = -amplitude * (float) (Math.PI * 2 * ky);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float sine = sinX[x] * cosY[y] + cosX[x] * sinY[y];
                    slopeX[y * size + x] += gradientX * sine;
                    slopeY[y * size + x] += gradientY * sine;
                }
            }
        }
    }

    /**
     * Tiling gradient-noise fBm: soft, organic blobs with no preferred direction, so thresholded foam breaks into
     * rounded patches and strands instead of the diamonds that a few interfering plane waves would form.
     */
    private static float[] foam(Random random, int size) {
        float[] result = new float[size * size];
        float amplitude = 1;
        for (int cells = 8; cells <= 128; cells *= 2, amplitude *= 0.65f) {
            float[] gradientX = new float[cells * cells], gradientY = new float[cells * cells];
            for (int i = 0; i < gradientX.length; i++) {
                double angle = random.nextDouble() * Math.PI * 2;
                gradientX[i] = (float) Math.cos(angle);
                gradientY[i] = (float) Math.sin(angle);
            }
            float scale = cells / (float) size;
            for (int y = 0; y < size; y++) {
                int y0 = (int) (y * scale), y1 = (y0 + 1) % cells;
                float ty = y * scale - y0, sy = ty * ty * ty * (ty * (ty * 6 - 15) + 10);
                for (int x = 0; x < size; x++) {
                    int x0 = (int) (x * scale), x1 = (x0 + 1) % cells;
                    float tx = x * scale - x0, sx = tx * tx * tx * (tx * (tx * 6 - 15) + 10);
                    float a = gradientX[y0 * cells + x0] * tx + gradientY[y0 * cells + x0] * ty;
                    float b = gradientX[y0 * cells + x1] * (tx - 1) + gradientY[y0 * cells + x1] * ty;
                    float c = gradientX[y1 * cells + x0] * tx + gradientY[y1 * cells + x0] * (ty - 1);
                    float d = gradientX[y1 * cells + x1] * (tx - 1) + gradientY[y1 * cells + x1] * (ty - 1);
                    float top = a + (b - a) * sx, bottom = c + (d - c) * sx;
                    result[y * size + x] += amplitude * (top + (bottom - top) * sy);
                }
            }
        }
        return result;
    }

    /**
     * A tiling network of focused light: bright where two warped Voronoi cells meet. Two drifting copies are
     * combined by their minimum in the shader, so the web re-forms continuously like light under moving ripples.
     */
    private static float[] caustics(Random random, int size) {
        int cells = 8;
        float cell = size / (float) cells;
        float[] pointX = new float[cells * cells], pointY = new float[cells * cells];
        for (int i = 0; i < pointX.length; i++) {
            pointX[i] = (i % cells + 0.15f + 0.7f * random.nextFloat()) * cell;
            pointY[i] = (i / cells + 0.15f + 0.7f * random.nextFloat()) * cell;
        }
        float[] result = new float[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // A gentle periodic warp bends the straight cell borders into rounded filaments.
                float wx = x + 0.22f * cell * (float) Math.sin(Math.PI * 2 * (2 * y / (double) size + 0.13));
                float wy = y + 0.22f * cell * (float) Math.sin(Math.PI * 2 * (3 * x / (double) size + 0.61));
                int column = (int) Math.floor(wx / cell), row = (int) Math.floor(wy / cell);
                float first = Float.POSITIVE_INFINITY, second = Float.POSITIVE_INFINITY;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int c = Math.floorMod(column + dx, cells), r = Math.floorMod(row + dy, cells);
                        float px = pointX[r * cells + c] + (column + dx - c) * cell;
                        float py = pointY[r * cells + c] + (row + dy - r) * cell;
                        float distance = (float) Math.hypot(wx - px, wy - py);
                        if (distance < first) {
                            second = first;
                            first = distance;
                        } else if (distance < second) {
                            second = distance;
                        }
                    }
                }
                float border = (second - first) / cell;
                float line = 1 - Math.clamp(border / 0.2f, 0, 1);
                result[y * size + x] = line * line * (3 - 2 * line);
            }
        }
        return result;
    }

    private static byte unit(float value) {
        return (byte) Math.round(Math.clamp(value, 0, 1) * 255);
    }

    /**
     * One chunk's surface inputs on a world-aligned lattice shared by every chunk, so bilinear samples agree exactly
     * along chunk borders: R signed distance to the nearest bank (positive over water), G optical depth, BA current.
     * Built with the chunk's terrain and owned by it; nothing here changes per frame.
     */
    static final class Field implements Disposable {
        final Texture texture;
        final float scaleX, scaleY, offsetX, offsetY;
        private final Pools pools;

        private Field(Texture texture, float scaleX, float scaleY, float offsetX, float offsetY, Pools pools) {
            this.texture = texture;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.pools = pools;
        }

        /** Null when the chunk has no open water; surfaces of the chunk's own tiles are reused, not rebuilt. */
        static Field build(BoardScene scene, Map<Coords, BoardFlow.Current> currents,
              Map<Coords, BoardSurface> surfaces) {
            Pools pools = new Pools(scene, currents, surfaces);
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            for (BoardSurface surface : surfaces.values()) {
                Pool pool = pools.get(surface.tile.coords());
                if (pool == null) { continue; }
                minX = Math.min(minX, pool.minX);
                minY = Math.min(minY, pool.minY);
                maxX = Math.max(maxX, pool.maxX);
                maxY = Math.max(maxY, pool.maxY);
            }
            if (minX > maxX) { return null; }
            float spacing = BoardGeometry.HEIGHT / 16;
            int firstX = (int) Math.floor(minX / spacing) - 1, firstY = (int) Math.floor(minY / spacing) - 1;
            int width = (int) Math.ceil(maxX / spacing) - firstX + 2;
            int height = (int) Math.ceil(maxY / spacing) - firstY + 2;
            // Texels carry a one-texel ring beyond the texture, so the depth blur agrees across chunk borders too.
            int span = width + 2;
            Pool[] owners = new Pool[span * (height + 2)];
            Pixmap pixels = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            try {
                ByteBuffer buffer = pixels.getPixels();
                float[] sample = new float[4];
                for (int row = -1; row <= height; row++) {
                    for (int column = -1; column <= width; column++) {
                        Pool owner = pools.sample((firstX + column + 0.5f) * spacing, (firstY + row + 0.5f) * spacing,
                              sample);
                        owners[(row + 1) * span + column + 1] = owner;
                        if (row < 0 || row == height || column < 0 || column == width) { continue; }
                        int index = (row * width + column) * 4;
                        buffer.put(index, unit(0.5f + 0.5f * sample[0] / SHORE_RANGE));
                        buffer.put(index + 2, unit(0.5f + 0.5f * sample[1] / CURRENT_RANGE));
                        buffer.put(index + 3, unit(0.5f + 0.5f * sample[2] / CURRENT_RANGE));
                    }
                }
                float[] depth = depths(pools, owners, span, firstX - 1, firstY - 1, spacing);
                // A 1-2-1 tent softens underwater steps into the few metres a real column blurs them over.
                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        int center = (row + 1) * span + column + 1;
                        float blurred = 0;
                        for (int dy = -1; dy <= 1; dy++) {
                            int line = center + dy * span;
                            float across = depth[line - 1] + 2 * depth[line] + depth[line + 1];
                            blurred += dy == 0 ? 2 * across : across;
                        }
                        buffer.put((row * width + column) * 4 + 1, unit(blurred / 16 / DEPTH_RANGE));
                    }
                }
                Texture texture = new Texture(pixels);
                texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
                texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
                return new Field(texture, 1 / (spacing * width), 1 / (spacing * height),
                      -firstX / (float) width, -firstY / (float) height, pools);
            } finally {
                pixels.dispose();
            }
        }

        /**
         * Water depth in levels at every texel, over the bed of the pool that owns it. Each pool's bed triangles are
         * visited once and only over the texels they span, instead of searching every triangle for every texel.
         */
        private static float[] depths(Pools pools, Pool[] owners, int span, int firstX, int firstY, float spacing) {
            int rows = owners.length / span;
            float[] bed = new float[owners.length];
            Arrays.fill(bed, Float.NEGATIVE_INFINITY);
            for (Pool pool : pools.all()) {
                for (BoardSurface.Face face : pool.surface.faces) {
                    if (face.finish() == BoardSurface.Finish.ICE) { continue; }
                    Vector3 a = face.a(), b = face.b(), c = face.c();
                    float lowX = Math.min(a.x, Math.min(b.x, c.x)), highX = Math.max(a.x, Math.max(b.x, c.x));
                    float lowY = Math.min(a.y, Math.min(b.y, c.y)), highY = Math.max(a.y, Math.max(b.y, c.y));
                    int left = Math.max(0, (int) Math.ceil(lowX / spacing - 0.5f) - firstX);
                    int right = Math.min(span - 1, (int) Math.floor(highX / spacing - 0.5f) - firstX);
                    int top = Math.max(0, (int) Math.ceil(lowY / spacing - 0.5f) - firstY);
                    int bottom = Math.min(rows - 1, (int) Math.floor(highY / spacing - 0.5f) - firstY);
                    for (int row = top; row <= bottom; row++) {
                        for (int column = left; column <= right; column++) {
                            int index = row * span + column;
                            if (owners[index] != pool) { continue; }
                            bed[index] = Math.max(bed[index],
                                  face.height((firstX + column + 0.5f) * spacing, (firstY + row + 0.5f) * spacing));
                        }
                    }
                }
            }
            float[] depth = new float[owners.length];
            for (int i = 0; i < depth.length; i++) {
                Pool owner = owners[i];
                if (owner == null) { continue; }
                BoardScene.Tile tile = owner.surface.tile;
                float floor = Float.isFinite(bed[i]) ? bed[i] : BoardGeometry.groundZ(tile);
                depth[i] = Math.max(0, BoardGeometry.waterZ(tile) - floor) / BoardGeometry.LEVEL;
            }
            return depth;
        }

        /** Rapids and a pool's approach to its lip, blended across hexes exactly like the current. */
        float agitation(Vector3 point) {
            return pools.agitation(point.x, point.y);
        }

        @Override
        public void dispose() { texture.dispose(); }
    }

    /** Open water around one chunk, derived lazily from the shared topology; frozen and molten hexes are banks. */
    private static final class Pools {
        private final BoardScene scene;
        private final Map<Coords, BoardFlow.Current> currents;
        private final Map<Coords, BoardSurface> surfaces;
        private final Map<Coords, Pool> pools = new HashMap<>();
        /** Open water of the hex nearest the last sample and of its neighbours; lattice rows revisit each hex often. */
        private final Pool[] candidates = new Pool[7];
        private int count, nearColumn, nearRow = Integer.MIN_VALUE;
        private final float[] scratch = new float[4];

        Pools(BoardScene scene, Map<Coords, BoardFlow.Current> currents, Map<Coords, BoardSurface> surfaces) {
            this.scene = scene;
            this.currents = currents;
            this.surfaces = surfaces;
        }

        Pool get(Coords coords) {
            if (pools.containsKey(coords)) { return pools.get(coords); }
            BoardScene.Tile tile = scene.tile(coords);
            Pool pool = null;
            if (tile != null && tile.liquid().present() && !tile.liquid().molten() && !tile.frozen()) {
                BoardSurface surface = surfaces.get(coords);
                pool = new Pool(surface == null ? new BoardSurface(scene, tile) : surface,
                      currents.getOrDefault(coords, BoardFlow.Current.STILL));
            }
            pools.put(coords, pool);
            return pool;
        }

        /** Every pool looked up so far: all that any sample can land in. */
        List<Pool> all() {
            return pools.values().stream().filter(Objects::nonNull).toList();
        }

        /**
         * Signed bank distance in hex widths, current in hex widths per second and agitation; returns the pool
         * whose water covers the point, or null over land.
         */
        Pool sample(float x, float y, float[] result) {
            gather(x, y);
            float range = SHORE_RANGE * BoardGeometry.WIDTH;
            Pool inside = null;
            float nearest = range * range;
            for (int i = 0; i < count; i++) {
                Pool pool = candidates[i];
                if (inside == null && pool.contains(x, y)) { inside = pool; }
                nearest = pool.shoreDistance2(x, y, nearest);
            }
            float distance = (float) Math.sqrt(nearest) / BoardGeometry.WIDTH;
            result[0] = inside == null ? -distance : distance;
            blend(x, y, result);
            return inside;
        }

        float agitation(float x, float y) {
            gather(x, y);
            blend(x, y, scratch);
            return scratch[3];
        }

        /** Finds the hex whose centre is nearest, then its open water and its neighbours'. */
        private void gather(float x, float y) {
            float width = BoardGeometry.WIDTH, height = BoardGeometry.HEIGHT;
            int column = Math.round((x - width / 2) / (width * .75f)), bestColumn = column, bestRow = 0;
            float nearest = Float.POSITIVE_INFINITY;
            for (int c = column - 1; c <= column + 1; c++) {
                float offset = (c & 1) * height / 2;
                int row = Math.round((-y - height / 2 - offset) / height);
                float dx = x - (c * width * .75f + width / 2), dy = y + row * height + offset + height / 2;
                if (dx * dx + dy * dy < nearest) {
                    nearest = dx * dx + dy * dy;
                    bestColumn = c;
                    bestRow = row;
                }
            }
            if (bestColumn == nearColumn && bestRow == nearRow) { return; }
            nearColumn = bestColumn;
            nearRow = bestRow;
            Coords hex = new Coords(bestColumn, bestRow);
            count = 0;
            Pool own = get(hex);
            if (own != null) { candidates[count++] = own; }
            for (int direction = 0; direction < 6; direction++) {
                Pool pool = get(hex.translated(direction));
                if (pool != null) { candidates[count++] = pool; }
            }
        }

        /** Current and agitation of the gathered pools, blended by a smooth kernel around each hex centre. */
        private void blend(float x, float y, float[] result) {
            float radius2 = BLEND_RADIUS * BoardGeometry.WIDTH * BLEND_RADIUS * BoardGeometry.WIDTH;
            float weight = 0, currentX = 0, currentY = 0, agitation = 0;
            for (int i = 0; i < count; i++) {
                Pool pool = candidates[i];
                float dx = x - pool.centerX, dy = y - pool.centerY, distance2 = (dx * dx + dy * dy) / radius2;
                if (distance2 < 1) {
                    float w = (1 - distance2) * (1 - distance2);
                    weight += w;
                    currentX += w * pool.currentX;
                    currentY += w * pool.currentY;
                    agitation += w * pool.agitation;
                }
            }
            result[1] = weight > 0 ? currentX / weight : 0;
            result[2] = weight > 0 ? currentY / weight : 0;
            result[3] = weight > 0 ? agitation / weight : 0;
        }
    }

    /** One hex of open water: its full outline, the bank segments of that outline, current and agitation. */
    private static final class Pool {
        final BoardSurface surface;
        final float[] outline;
        final float[] banks;
        final float minX, minY, maxX, maxY, centerX, centerY;
        final float currentX, currentY, agitation;

        Pool(BoardSurface surface, BoardFlow.Current current) {
            this.surface = surface;
            List<Vector3> points = surface.outline;
            outline = new float[points.size() * 2];
            List<Float> segments = new ArrayList<>();
            float lowX = Float.POSITIVE_INFINITY, lowY = Float.POSITIVE_INFINITY;
            float highX = Float.NEGATIVE_INFINITY, highY = Float.NEGATIVE_INFINITY;
            int perEdge = points.size() / 6;
            for (int i = 0; i < points.size(); i++) {
                Vector3 a = points.get(i), b = points.get((i + 1) % points.size());
                outline[i * 2] = a.x;
                outline[i * 2 + 1] = a.y;
                lowX = Math.min(lowX, a.x);
                lowY = Math.min(lowY, a.y);
                highX = Math.max(highX, a.x);
                highY = Math.max(highY, a.y);
                // Open mouths continue into the next pool and falling lips into their sheet; neither is a bank.
                if (!surface.mouth(i / perEdge)) {
                    segments.addAll(List.of(a.x, a.y, b.x, b.y));
                }
            }
            banks = new float[segments.size()];
            for (int i = 0; i < banks.length; i++) { banks[i] = segments.get(i); }
            minX = lowX;
            minY = lowY;
            maxX = highX;
            maxY = highY;
            centerX = BoardGeometry.centerX(surface.tile.coords());
            centerY = BoardGeometry.centerY(surface.tile.coords());
            // Texture displacement per second, as BoardFlow reports it, becomes world velocity in hex widths.
            currentX = -current.u();
            currentY = current.v() * BoardGeometry.HEIGHT / BoardGeometry.WIDTH;
            float rapids = switch (surface.tile.liquid().rapids()) {
                case 1 -> .5f;
                case 2 -> .9f;
                default -> 0;
            };
            // A pool pouring over a lip churns as it accelerates, and so does any fast current.
            float approach = Math.clamp(((float) Math.hypot(currentX, currentY) - .12f) / .5f, 0, .6f);
            agitation = Math.max(rapids, Math.max(approach, surface.waterfalls.isEmpty() ? 0 : .2f));
        }

        boolean contains(float x, float y) {
            if (x < minX || x > maxX || y < minY || y > maxY) { return false; }
            boolean inside = false;
            for (int i = 0, j = outline.length - 2; i < outline.length; j = i, i += 2) {
                float xi = outline[i], yi = outline[i + 1], xj = outline[j], yj = outline[j + 1];
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) { inside = !inside; }
            }
            return inside;
        }

        /** Squared distance to the nearest bank segment, or the given bound when every bank is further away. */
        float shoreDistance2(float x, float y, float bound) {
            float reach = (float) Math.sqrt(bound);
            if (x < minX - reach || x > maxX + reach || y < minY - reach || y > maxY + reach) { return bound; }
            float result = bound;
            for (int i = 0; i < banks.length; i += 4) {
                float ax = banks[i], ay = banks[i + 1], dx = banks[i + 2] - ax, dy = banks[i + 3] - ay;
                float length2 = dx * dx + dy * dy;
                float t = length2 == 0 ? 0 : Math.clamp(((x - ax) * dx + (y - ay) * dy) / length2, 0, 1);
                float ex = ax + dx * t - x, ey = ay + dy * t - y;
                result = Math.min(result, ex * ex + ey * ey);
            }
            return result;
        }
    }
}
