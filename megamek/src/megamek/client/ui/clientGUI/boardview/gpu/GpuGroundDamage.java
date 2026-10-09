/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.io.File;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureArray;
import com.badlogic.gdx.graphics.TextureArrayData;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.BufferUtils;
import megamek.common.Configuration;
import megamek.common.ResolvedAttack;
import megamek.common.board.Coords;
import megamek.logging.MMLogger;

/** Cosmetic ground history on the render thread. Neither marks nor their texture change the board or game rules. */
final class GpuGroundDamage implements Disposable {
    private static final MMLogger LOGGER = MMLogger.create(GpuGroundDamage.class);
    static final boolean COMBAT_SCARS = true;
    static final int TILE_SIZE = 256;
    static final int TILE_BORDER = 8;
    static final int TILE_INTERIOR = TILE_SIZE - 2 * TILE_BORDER;
    static final float TILE_METRES = 8;
    static final int STAMPS_PER_FRAME = 8;
    private static final int MAX_PENDING_STAMPS = 256;
    private static final int MAX_EVENTS = UnitPlayback.MAX_PENDING_EVENTS * 2;
    private static final int MAX_MARKS_PER_EVENT = 64;
    private static final int MAX_BRUSH_SIZE = 256;

    enum Style {
        LASER("laser-gouge"), PPC("ppc-gouge"), BALLISTIC("ballistic-gouge"),
        LASER_BLAST("laser-blast"), PPC_BLAST("ppc-blast"), BALLISTIC_BLAST("ballistic-blast"),
        BLAST("blast"), BURN("burn"), PLASMA("plasma-splash");

        private final String file;
        Style(String file) { this.file = file; }

        boolean cut() { return this == LASER || this == PPC || this == BALLISTIC; }
        boolean thermal() { return this == LASER || this == PPC || this == LASER_BLAST || this == PPC_BLAST || this == PLASMA; }
    }

    /** Miss endpoints and contact times are supplied by the existing attack effects, never rolled here. */
    record Impact(UnitAttack attack, String key, Vector3 origin, Vector3 point, String effect, ResolvedAttack.Shot shot) {
        Style style() {
            // Captured weapon flags take precedence over a mesh's generic energy/bullet emitter.
            if (shot != null) {
                if (shot.artillery()) { return Style.BLAST; }
                if (shot.plasma()) { return Style.PLASMA; }
                if (shot.ppc()) { return Style.PPC; }
            }
            return switch (effect) {
                case "laser" -> Style.LASER;
                case "ppc" -> Style.PPC;
                case "bullet" -> Style.BALLISTIC;
                case "flame" -> Style.BURN;
                default -> Style.BLAST;
            };
        }

        boolean cut() { return style().cut(); }

        /** Within 45 degrees of the receiving triangle's normal, use a round impact of the same material. */
        Style style(BoardSurface.Face face) {
            var normal = face.b().cpy().sub(face.a()).crs(face.c().x - face.a().x,
                  face.c().y - face.a().y, face.c().z - face.a().z);
            return style(normal);
        }

        Vector3 incoming() {
            var contact = attack == null ? null : attack.landscapeContact(point);
            return contact == null ? point.cpy().sub(origin) : contact.incoming().cpy();
        }

        Style style(Vector3 normal) {
            var style = style();
            if (!style.cut()) { return style; }
            var incoming = incoming();
            float lengths = incoming.len2() * normal.len2(), dot = incoming.dot(normal);
            // Squared cosine: no normalization or trigonometry, and triangle winding does not matter.
            if (!(lengths > 0) || 2 * dot * dot < lengths) { return style; }
            return switch (style) {
                case LASER -> Style.LASER_BLAST;
                case PPC -> Style.PPC_BLAST;
                case BALLISTIC -> Style.BALLISTIC_BLAST;
                default -> style;
            };
        }

        boolean burn() { return style() == Style.BURN; }

        boolean thermal() { return style().thermal(); }

        float radius() {
            return radius(style());
        }

        float radius(Style style) {
            // Zero-damage weapons still leave a visible scar; the captured game value stays unchanged.
            float damage = shot == null ? cut() ? 5 : 1 : (float) Math.max(1, shot.damagePerHit());
            if (style == Style.BLAST || style == Style.PLASMA) {
                // One power curve through 2.7 m diameter at 1 damage and 12 m at 25 damage.
                return BoardRelief.metres(1.35f * (float) Math.pow(damage, .4634086426));
            }
            // Gouges are 5:1. Their perpendicular blast diameter is twice the gouge width.
            float scale = switch (style) {
                case LASER_BLAST, PPC_BLAST, BALLISTIC_BLAST -> 2f / 5;
                default -> 1;
            };
            return BoardRelief.metres(1.5f * (float) Math.sqrt(damage) * scale);
        }

        float strength() {
            // A readable burn, lighter than an opaque crater; damage changes the footprint only.
            return burn() ? .65f : 1;
        }
    }

    private final Map<UUID, Set<String>> seen = new LinkedHashMap<>();
    private final Map<Style, List<Pixmap>> brushes = new EnumMap<>(Style.class);
    private record Stamp(int level, Vector3 point, float radius, float angle, Style style, int seed, float strength) { }
    private final ArrayDeque<Stamp> pending = new ArrayDeque<>();
    private BoardScene groundScene;
    private final Map<Integer, Tile> tiles = new HashMap<>();
    private final List<Pixmap> directory = new ArrayList<>();
    private final List<Pixmap> mipScratch = new ArrayList<>();
    private final Set<Tile> dirty = new HashSet<>();
    private TextureArray texture;
    private int boardId, columns, rows;
    private float worldWidth, worldHeight;
    private int tileColumns, tileRows, capacity, maxLayers;
    private float tileWorldSize;
    private boolean directoryDirty;
    private int marks;
    private int nextSurfaceKey = -1;

    /** CPU tiles are allocated only around impacts; their GPU layers have the same fixed ground scale. */
    record Tile(int layer, Pixmap pixels, IntBuffer texels) {
        Tile(int layer, Pixmap pixels) { this(layer, pixels, GpuGroundDamage.texels(pixels)); }
        // The buffer is a mutable view, not part of a tile's identity in the dirty set.
        @Override public boolean equals(Object other) { return other instanceof Tile tile && layer == tile.layer && pixels == tile.pixels; }
        @Override public int hashCode() { return 31 * layer + System.identityHashCode(pixels); }
    }

    private static IntBuffer texels(Pixmap pixels) {
        return pixels.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
    }

    TextureArray texture() { return texture; }
    float tileWorldSize() { return tileWorldSize; }
    int tileColumns() { return tileColumns; }
    int tileRows() { return tileRows; }
    int tileCount() { return tiles.size(); }
    static String shaderSource() {
        return "#define GROUND_DAMAGE_TILE " + TILE_SIZE + ".0\n#define GROUND_DAMAGE_INNER " + TILE_INTERIOR
              + ".0\n#define GROUND_DAMAGE_BORDER " + TILE_BORDER + ".0\n"
              + GpuShaderSource.read("terrain-damage-mask.glsl");
    }

    /** Even a disabled array sampler needs a binding distinct from the shader's 2D samplers. */
    TextureArray binding() {
        if (texture == null) { texture = new TextureArray(new TileData(1)); capacity = 1; }
        return texture;
    }
    float width() { return worldWidth; }
    float height() { return worldHeight; }
    int marks() { return marks; }

    /** New boards clear history; camera changes, chunk rebuilds and detail changes do not. */
    void update(BoardScene scene) {
        if (scene == null) { return; }
        float width = (scene.width() * .75f + .25f) * BoardGeometry.width();
        float height = (scene.height() + .5f) * BoardGeometry.height();
        if (boardId != scene.boardId() || columns != scene.width() || rows != scene.height()
              || width != worldWidth || height != worldHeight) {
            dispose();
            boardId = scene.boardId(); columns = scene.width(); rows = scene.height();
            worldWidth = width; worldHeight = height;
            if (COMBAT_SCARS) { allocate(); }
        }
        groundScene = scene;
    }

    void impact(BoardScene scene, Impact impact, Function<Coords, BoardTacticalGeometry.Surface> surfaces) {
        impact(scene, impact, surfaces, ignored -> { });
    }

    void impact(BoardScene scene, Impact impact, Function<Coords, BoardTacticalGeometry.Surface> surfaces,
          java.util.function.Consumer<Impact> vertical) {
        if (!COMBAT_SCARS || scene == null || impact.attack().event.boardId() != scene.boardId()) { return; }
        update(scene);
        Set<String> keys = seen.computeIfAbsent(impact.attack().event.result().id(), ignored -> new HashSet<>());
        if (seen.size() > MAX_EVENTS) { seen.remove(seen.keySet().iterator().next()); }
        if (keys.size() >= MAX_MARKS_PER_EVENT || !keys.add(impact.key())) { return; }
        Vector3 point = impact.point();
        var contact = impact.attack().landscapeContact(point);
        // A deformed cliff can project into its neighbour (or beyond the board's flat outline).
        var tile = contact == null ? BoardGeometry.tile(scene, point.x, point.y) : scene.tile(contact.surface().coords());
        if (tile == null || impact.attack().landscape == null) { return; }
        // The attack already picked its collision. Compare its endpoint with the installed top triangles;
        // another board-wide ray would rebuild geometry unnecessarily and could stall at the first impact.
        float lift = BoardRelief.metres(.1f);
        var surface = surfaces.apply(tile.coords());
        if (surface == null) { return; }
        var face = surface.top().stream().filter(candidate -> Math.abs(candidate.height(point.x, point.y) - point.z) < lift)
              .findFirst().orElse(null);
        if (face == null) { vertical.accept(impact); return; }
        if (tile.liquid().present() || tile.frozen()) { return; }
        var style = impact.style(face);
        if (brushes.get(style).isEmpty()) { return; }
        int seed = impact.attack().event.result().id().hashCode() ^ impact.key().hashCode();
        var incoming = impact.incoming();
        float dx = incoming.x, dy = incoming.y;
        float length = (float) Math.hypot(dx, dy);
        float angle = length < .001f ? UnitAttack.noise(seed) * MathUtils.PI2 : MathUtils.atan2(dy, dx);
        if (pending.size() >= MAX_PENDING_STAMPS) { pending.removeFirst(); }
        pending.addLast(new Stamp(tile.elevation(), point.cpy(), impact.radius(style), angle, style, seed, impact.strength()));
    }

    private void allocate() {
        // Decode at board setup, never in the middle of the first impact. No additional GPU samplers.
        for (var style : Style.values()) { brushes.put(style, loadBrush(style)); }
        tileWorldSize = BoardRelief.metres(TILE_METRES);
        tileColumns = (int) Math.ceil(worldWidth / tileWorldSize);
        tileRows = (int) Math.ceil(worldHeight / tileWorldSize);
        int entries = Math.multiplyExact(tileColumns, tileRows);
        for (int i = 0; i < Math.ceilDiv(entries, TILE_SIZE * TILE_SIZE); i++) { directory.add(blank()); }
        var limit = BufferUtils.newIntBuffer(1);
        Gdx.gl.glGetIntegerv(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS, limit);
        maxLayers = limit.get(0);
        directoryDirty = true;
    }

    private static Pixmap blank() {
        return blank(TILE_SIZE);
    }

    private static Pixmap blank(int size) {
        var result = new Pixmap(size, size, Pixmap.Format.RGBA8888);
        result.setBlending(Pixmap.Blending.None);
        result.setColor(0);
        result.fill();
        return result;
    }

    /** Vertical receivers share the same image layers, filtering, upload budget and material channels. */
    Tile surfaceTile() {
        if (directory.size() + tiles.size() >= maxLayers) { return null; }
        var tile = new Tile(directory.size() + tiles.size(), blank());
        tiles.put(nextSurfaceKey--, tile);
        return tile;
    }

    void changed(Tile tile) {
        dirty.add(tile);
        marks++;
    }

    record Brush(Pixmap image, IntBuffer texels, float aspect, float mirror, float strength) {
        int sample(float u, float v) {
            if (Math.abs(u) > 1 || Math.abs(v) > 1) { return 0; }
            return filtered(texels, image.getWidth(), image.getHeight(), (u * .5f + .5f) * image.getWidth() - .5f,
                  (.5f - v * mirror * .5f) * image.getHeight() - .5f);
        }
    }

    Brush brush(Style style, float radius, float strength, int seed) {
        var levels = brushes.get(style);
        if (levels == null || levels.isEmpty()) { return null; }
        int lod = 0;
        float diameter = 2 * radius * TILE_INTERIOR / tileWorldSize;
        while (lod + 1 < levels.size() && levels.get(lod).getWidth() > diameter * 2) { lod++; }
        return new Brush(levels.get(lod), texels(levels.get(lod)), (float) levels.getFirst().getHeight() / levels.getFirst().getWidth(),
              (seed & 1) == 0 ? 1 : -1, strength);
    }

    static void blend(Tile tile, int x, int y, int sample, float strength) {
        float alpha = (sample >>> 24) / 255f * strength;
        int index = y * TILE_SIZE + x, old = tile.texels().get(index), combined = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int value = Math.round((sample >>> shift & 255) * strength + (old >>> shift & 255) * (1 - alpha));
            combined |= Math.min(255, value) << shift;
        }
        tile.texels().put(index, combined);
    }

    private void stamp(Stamp mark) {
        float density = TILE_INTERIOR / tileWorldSize;
        float cos = MathUtils.cos(mark.angle()), sin = MathUtils.sin(mark.angle());
        float radius = mark.radius();
        var brush = brush(mark.style(), radius, mark.strength(), mark.seed());
        if (brush == null) { return; }
        // Brush dimensions describe the visible art, after empty authoring margins are removed at load.
        float halfHeight = radius * brush.aspect();
        float extentX = Math.abs(cos) * radius + Math.abs(sin) * halfHeight;
        float extentY = Math.abs(sin) * radius + Math.abs(cos) * halfHeight;
        var point = mark.point();
        float border = TILE_BORDER / density;
        int firstX = Math.max(0, (int) Math.floor((point.x - extentX - border) / tileWorldSize));
        int lastX = Math.min(tileColumns - 1, (int) Math.floor((point.x + extentX + border) / tileWorldSize));
        int firstY = Math.max(0, (int) Math.floor((-point.y - extentY - border) / tileWorldSize));
        int lastY = Math.min(tileRows - 1, (int) Math.floor((-point.y + extentY + border) / tileWorldSize));
        for (int ty = firstY; ty <= lastY; ty++) {
            for (int tx = firstX; tx <= lastX; tx++) {
                int key = ty * tileColumns + tx;
                Tile tile = tiles.get(key);
                float ox = tx * tileWorldSize, oy = ty * tileWorldSize;
                int x0 = Math.max(0, (int) Math.floor((point.x - extentX - ox) * density) + TILE_BORDER);
                int x1 = Math.min(TILE_SIZE - 1, (int) Math.ceil((point.x + extentX - ox) * density) + TILE_BORDER);
                int y0 = Math.max(0, (int) Math.floor((-point.y - extentY - oy) * density) + TILE_BORDER);
                int y1 = Math.min(TILE_SIZE - 1, (int) Math.ceil((-point.y + extentY - oy) * density) + TILE_BORDER);
                for (int y = y0; y <= y1; y++) {
                    for (int x = x0; x <= x1; x++) {
                        // Use global integer texel positions so adjoining tiles have identical border samples.
                        float wx = (tx * TILE_INTERIOR + x - TILE_BORDER + .5f) / density;
                        float wy = -(ty * TILE_INTERIOR + y - TILE_BORDER + .5f) / density;
                        float px = wx - point.x, py = wy - point.y;
                        float u = (px * cos + py * sin) / radius, v = (-px * sin + py * cos) / halfHeight;
                        int sample = brush.sample(u, v);
                        float alpha = (sample >>> 24) / 255f * mark.strength();
                        if (alpha < 1 / 255f) { continue; }
                        var receiver = BoardGeometry.tile(groundScene, wx, wy);
                        if (receiver == null || receiver.elevation() != mark.level() || receiver.liquid().present() || receiver.frozen()) { continue; }
                        if (tile == null) {
                            if (directory.size() + tiles.size() >= maxLayers) {
                                LOGGER.warn("Ground scars exhausted the GPU's {} texture-array layers", maxLayers);
                                return;
                            }
                            tile = new Tile(directory.size() + tiles.size(), blank());
                            tiles.put(key, tile);
                            int address = key % (TILE_SIZE * TILE_SIZE);
                            int layer = tile.layer();
                            directory.get(key / (TILE_SIZE * TILE_SIZE)).drawPixel(address % TILE_SIZE, address / TILE_SIZE,
                                  (layer & 255) << 24 | (layer >>> 8) << 16);
                            directoryDirty = true;
                        }
                        // Premultiplied coverage, authored luminance, gloss and relief; newer art blends over older art.
                        blend(tile, x, y, sample, mark.strength());
                    }
                }
                if (tile != null) { dirty.add(tile); }
            }
        }
    }

    /** Editable RGBA artwork: alpha is coverage; dark values are char/melt, light values disturbed debris. */
    private static List<Pixmap> loadBrush(Style style) {
        var file = new FileHandle(new File(Configuration.dataDir(), "models/board/textures/scars/" + style.file + ".png"));
        var levels = new ArrayList<Pixmap>();
        Pixmap source = null;
        try {
            source = new Pixmap(file);
            int left = source.getWidth(), top = source.getHeight(), right = -1, bottom = -1;
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // Almost-transparent export residue must not determine a weapon's visible size.
                    if ((source.getPixel(x, y) & 255) < 16) { continue; }
                    left = Math.min(left, x); right = Math.max(right, x);
                    top = Math.min(top, y); bottom = Math.max(bottom, y);
                }
            }
            if (right < left) { throw new IllegalArgumentException("Empty scar artwork"); }
            var mask = new Pixmap(right - left + 1, bottom - top + 1, Pixmap.Format.RGBA8888);
            mask.setBlending(Pixmap.Blending.None);
            levels.add(mask);
            float depth = switch (style) {
                case BURN -> 0;
                case PLASMA -> .18f;
                case LASER, LASER_BLAST -> .55f;
                case PPC, PPC_BLAST -> .65f;
                default -> 1;
            };
            for (int y = 0; y < mask.getHeight(); y++) {
                for (int x = 0; x < mask.getWidth(); x++) {
                    int color = source.getPixel(left + x, top + y);
                    float alpha = (color & 255) / 255f;
                    float shade = ((color >>> 24) * .2126f + (color >>> 16 & 255) * .7152f
                          + (color >>> 8 & 255) * .0722f) / 255;
                    float glass = style.thermal() ? alpha * (1 - .65f * shade) : 0;
                    float relief = alpha * (1 - shade) * depth;
                    // Premultiply coverage before filtering, avoiding dark fringes from transparent RGB.
                    mask.drawPixel(x, y, channel(alpha) << 24 | channel(alpha * shade) << 16 | channel(glass) << 8 | channel(relief));
                }
            }
            // Prefilter stamps as well as the final board mask: bilinear sampling a large source alone aliases.
            while (mask.getWidth() > 1 || mask.getHeight() > 1) {
                var next = new Pixmap(Math.max(1, mask.getWidth() / 2), Math.max(1, mask.getHeight() / 2), Pixmap.Format.RGBA8888);
                next.setBlending(Pixmap.Blending.None);
                levels.add(next);
                var sourceTexels = texels(mask);
                for (int y = 0; y < next.getHeight(); y++) {
                    for (int x = 0; x < next.getWidth(); x++) {
                        next.drawPixel(x, y, filtered(sourceTexels, mask.getWidth(), mask.getHeight(), (x + .5f) * mask.getWidth() / next.getWidth() - .5f,
                              (y + .5f) * mask.getHeight() / next.getHeight() - .5f));
                    }
                }
                mask = next;
            }
            while (levels.getFirst().getWidth() > MAX_BRUSH_SIZE || levels.getFirst().getHeight() > MAX_BRUSH_SIZE) {
                levels.removeFirst().dispose();
            }
            return List.copyOf(levels);
        } catch (RuntimeException error) {
            levels.forEach(Pixmap::dispose);
            LOGGER.warn("Could not load ground scar {}: {}", file, error.getMessage());
            return List.of();
        } finally {
            if (source != null) { source.dispose(); }
        }
    }

    private static int filtered(IntBuffer image, int width, int height, float x, float y) {
        x = MathUtils.clamp(x, 0, width - 1);
        y = MathUtils.clamp(y, 0, height - 1);
        int x0 = (int) x, y0 = (int) y;
        int x1 = Math.min(x0 + 1, width - 1), y1 = Math.min(y0 + 1, height - 1);
        int a = image.get(y0 * width + x0), b = image.get(y0 * width + x1), c = image.get(y1 * width + x0), d = image.get(y1 * width + x1);
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            result |= Math.round(MathUtils.lerp(MathUtils.lerp(a >>> shift & 255, b >>> shift & 255, x - x0),
                  MathUtils.lerp(c >>> shift & 255, d >>> shift & 255, x - x0), y - y0)) << shift;
        }
        return result;
    }

    private static int channel(float value) { return Math.clamp(Math.round(value * 255), 0, 255); }

    /** Upload only changed tiles before the terrain pass; settled frames and camera changes do no raster work. */
    void upload() {
        // Large salvos/instant playback must not rasterize every scar in one render frame. Retain newest arrivals
        // on overload; only compact stamp data waits here, never the attack's unit/model snapshots.
        for (int count = 0; count < STAMPS_PER_FRAME && !pending.isEmpty(); count++) {
            var mark = pending.removeFirst();
            stamp(mark);
            marks++;
        }
        if (directory.isEmpty() || !directoryDirty && dirty.isEmpty()) { return; }
        int required = directory.size() + tiles.size();
        if (texture == null || required > capacity) {
            // Geometric growth avoids reallocation on every impact. Empty board area consumes no image layers.
            capacity = Math.min(maxLayers, Math.max(required, Math.max(directory.size(), capacity * 2)));
            var previous = texture;
            texture = new TextureArray(new TileData(capacity));
            texture.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
            texture.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
            if (previous != null) { previous.dispose(); }
        } else {
            texture.bind();
            if (directoryDirty) {
                for (int i = 0; i < directory.size(); i++) { uploadTile(i, 0, directory.get(i)); }
            }
            for (var tile : dirty) { uploadImage(tile.layer(), tile.pixels()); }
        }
        directoryDirty = false;
        dirty.clear();
    }

    private static void uploadTile(int layer, int level, Pixmap pixels) {
        Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, 0, 0, layer, pixels.getWidth(), pixels.getHeight(), 1,
              GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels.getPixels());
    }

    /** Reuse one small scratch chain; a new hit must not rebuild every historical tile's mipmaps. */
    private void uploadImage(int layer, Pixmap pixels) {
        uploadTile(layer, 0, pixels);
        int level = 0;
        for (var next : mipScratch) {
            var source = pixels.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            var target = next.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
            int width = pixels.getWidth();
            for (int y = 0; y < next.getHeight(); y++) {
                for (int x = 0; x < next.getWidth(); x++) {
                    int at = 2 * (y * width + x);
                    int a = source.get(at), b = source.get(at + 1), c = source.get(at + width), d = source.get(at + width + 1);
                    // Average four premultiplied texels, two independent byte channels at a time.
                    int ga = ((a & 0xff00ff) + (b & 0xff00ff) + (c & 0xff00ff) + (d & 0xff00ff) + 0x20002) >>> 2;
                    int rb = ((a >>> 8 & 0xff00ff) + (b >>> 8 & 0xff00ff) + (c >>> 8 & 0xff00ff) + (d >>> 8 & 0xff00ff) + 0x20002) >>> 2;
                    target.put((ga & 0xff00ff) | (rb & 0xff00ff) << 8);
                }
            }
            uploadTile(layer, ++level, next);
            pixels = next;
        }
    }

    private final class TileData implements TextureArrayData {
        private final int layers;
        private boolean prepared;
        TileData(int layers) { this.layers = layers; }
        @Override public boolean isPrepared() { return prepared; }
        @Override public void prepare() { prepared = true; }
        @Override public int getWidth() { return TILE_SIZE; }
        @Override public int getHeight() { return TILE_SIZE; }
        @Override public int getDepth() { return layers; }
        @Override public boolean isManaged() { return false; }
        @Override public int getInternalFormat() { return GL20.GL_RGBA; }
        @Override public int getGLType() { return GL20.GL_UNSIGNED_BYTE; }
        @Override public void consumeTextureArrayData() {
            if (mipScratch.isEmpty()) {
                for (int size = TILE_SIZE / 2; size > 0; size /= 2) { mipScratch.add(blank(size)); }
            }
            // TextureArray allocates level zero; allocate the remaining levels once per capacity change.
            for (int level = 1; level <= mipScratch.size(); level++) {
                int size = TILE_SIZE >> level;
                Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, level, GL20.GL_RGBA, size, size, layers, 0,
                      GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, null);
            }
            if (directory.isEmpty()) {
                var empty = blank();
                try { uploadImage(0, empty); } finally { empty.dispose(); }
            }
            for (int i = 0; i < directory.size(); i++) { uploadTile(i, 0, directory.get(i)); }
            tiles.values().forEach(tile -> uploadImage(tile.layer(), tile.pixels()));
            prepared = false;
        }
    }

    @Override
    public void dispose() {
        if (texture != null) { texture.dispose(); }
        tiles.values().forEach(tile -> tile.pixels().dispose());
        tiles.clear();
        directory.forEach(Pixmap::dispose);
        directory.clear();
        mipScratch.forEach(Pixmap::dispose);
        mipScratch.clear();
        dirty.clear();
        brushes.values().forEach(levels -> levels.forEach(Pixmap::dispose));
        brushes.clear();
        texture = null; capacity = 0; seen.clear(); marks = 0;
        nextSurfaceKey = -1;
        pending.clear(); groundScene = null;
        worldWidth = 0; worldHeight = 0; columns = 0; rows = 0;
    }
}
