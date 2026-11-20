/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.Disposable;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

/**
 * The board's road masks, each different picture once, in the layers of one texture array. Roads alike across a board
 * have the same masks ({@link GpuRoads.Masks} shares them by {@link GpuRoads#maskKey}), so the chunks draw with pieces
 * of one array where each chunk held its own atlas; and since every chunk's coats sample the one array, a chunk's coats
 * stay one draw ({@link GpuRoads#batchMaterial}). A chunk holds a {@link Use}: the masks it draws with, counted here.
 * <p>
 * A mask's place is its layer and its rectangle there, carried in the coat's vertices as an atlas region with the layer
 * added to its U offset ({@link Place}). A slot never moves while a chunk uses it, so an edit packs and uploads only the
 * masks it adds, each straight from its own pixels; a slot no chunk uses goes to the next mask of its size. When the
 * layers hold more than twice the masks in use (and a layer more), the chunks built next take a fresh array, and the old
 * one goes with the last chunk using it, the rule a chunk's own atlas compacted by (GpuTextures).
 * <p>
 * The layers live on the card: the packer keeps rectangles, not pictures. The array grows by copying its layers into a
 * deeper one (doubling while a board loads, then a quarter more where the copy stays on the card), and only a finished
 * rebuild trims it to the layers its masks fill ({@link #trim}): never a pan. The copy stays on the card where the
 * context is full, else passes through client memory for that moment ({@link MaskArray#copyInto}). Render thread only, but for {@link #prepare}.
 * <p>
 * The layers are BC4 (RGTC1: one channel in 4x4 blocks of 8 bytes; GL 3.0 core, so the 4.1 floor and macOS), an eighth
 * of RGBA8 a texel, still one sampler. A mask whose colour is one tint over all its texels (paint, a plain coat) keeps
 * its coverage alone and carries its tint in its place ({@link Place}); a mask whose colour is data (a heading, a place
 * across, a join's coordinates, soot) keeps its four channels as four cells side by side ({@link #encode}).
 */
final class GpuRoadMaskAtlas implements Disposable {
    /** A layer's side, and the texels a mask's cell adds to it: its edge doubled, one texel a side, as GpuTextures packs. */
    private static final int SIZE = 2048, BLEED = 2;
    /** The layers' format, and the bytes of one layer: 8 bytes a 4x4 block. */
    private static final int FORMAT = org.lwjgl.opengl.GL30.GL_COMPRESSED_RED_RGTC1, LAYER_BYTES = SIZE * SIZE / 2;

    /**
     * Where a mask lies, as a coat's vertices carry it (a_roadMaskRegion, flat): U offset (its layer plus its U in the
     * layer), V offset, and its size in the layer, each a multiple of 1/2048 under 1. A mask of one tint carries it in
     * the integer parts of the last three (1 + red, green, blue, each 0 to 255); a mask with data has 0 there, and its
     * red, green and blue cells lie right of, under, and right of and under its coverage (terrain-road.frag). Every
     * value stays exact in a float: an integer under 4096 and a multiple of 1/2048.
     */
    record Place(Texture texture, float u, float v, float width, float height) { }

    /**
     * A mask as its slot holds it: a rectangle of {@code width} x {@code height} texels (multiples of 4) as BC4 blocks,
     * row by row, and its tint (0xRRGGBB), or -1 where its colour is data and the rectangle holds its four channels.
     */
    record Cell(int width, int height, int tint, ByteBuffer blocks) { }

    private volatile Pages pages = new Pages();
    /** Masks a chunk being built will draw with, encoded on its worker ({@link #prepare}) until their upload. */
    private final Map<BoardScene.Pixels, Cell> encoded = new WeakHashMap<>();

    /** A chunk's use of these masks: those the layers do not hold yet are packed and uploaded now. */
    Use use(Collection<BoardScene.Pixels> masks) {
        if (pages.wasteful()) { pages = new Pages(); }
        return new Use(this, pages, masks);
    }

    /**
     * On a chunk's worker: encodes the masks it will draw with that the layers do not hold, so that their upload on the
     * render thread only copies blocks. A mask the render thread finds unencoded (released meanwhile) it encodes itself.
     */
    void prepare(Collection<BoardScene.Pixels> masks) {
        Set<BoardScene.Pixels> resident = pages.resident;
        for (BoardScene.Pixels pixels : masks) {
            if (resident.contains(pixels)) { continue; }
            synchronized (encoded) {
                if (encoded.containsKey(pixels)) { continue; }
            }
            Cell cell = encode(pixels);
            synchronized (encoded) { encoded.put(pixels, cell); }
        }
    }

    /** A mask's cell as a worker encoded it, no longer waiting; null where none did. */
    private Cell take(BoardScene.Pixels pixels) {
        synchronized (encoded) { return encoded.remove(pixels); }
    }

    /** The array a coat's stand-in texture names ({@link Place#texture}): the one its shaders sample. */
    static GLTexture array(Texture texture) { return ((Handle) texture).pages.array; }

    /** After a finished rebuild: the array keeps only the layers its masks fill, copied on the card. */
    void trim() { pages.trim(); }

    @Override
    public void dispose() { pages.dispose(); }

    /** A cell's side: a mask's texels and their doubled edge, to whole blocks. */
    private static int side(int texels) { return (texels + BLEED + 3) & ~3; }

    /**
     * A mask as its slot holds it, each channel in BC4 blocks: its coverage in one cell (the mask, its edge doubled,
     * then repeated to whole blocks), and where its colour is not one tint over all its texels, red right of it, green
     * under it and blue right of and under it.
     */
    static Cell encode(BoardScene.Pixels pixels) {
        int width = pixels.width(), height = pixels.height(), cw = side(width), ch = side(height);
        int[] rgba = new int[width * height];
        pixels.writeRgba(IntBuffer.wrap(rgba), 0, rgba.length);
        int tint = rgba[0] >>> 8;
        for (int value : rgba) {
            if (value >>> 8 != tint) {
                tint = -1;
                break;
            }
        }
        int cells = tint < 0 ? 2 : 1;
        ByteBuffer blocks = ByteBuffer.allocateDirect(cells * cw / 4 * cells * ch / 4 * 8).order(ByteOrder.LITTLE_ENDIAN);
        int[] block = new int[16];
        Bc4 encoder = new Bc4();
        for (int by = 0; by < cells * ch; by += 4) {
            for (int bx = 0; bx < cells * cw; bx += 4) {
                // RGBA's shift to each cell's channel: A, then R right of it, G under it, B right of and under it.
                int shift = bx < cw ? by < ch ? 0 : 16 : by < ch ? 24 : 8;
                for (int i = 0; i < 16; i++) {
                    int x = Math.clamp(bx % cw + (i & 3) - 1, 0, width - 1), y = Math.clamp(by % ch + (i >> 2) - 1, 0, height - 1);
                    block[i] = rgba[y * width + x] >>> shift & 255;
                }
                blocks.putLong(encoder.encode(block));
            }
        }
        return new Cell(cells * cw, cells * ch, tint, blocks.flip());
    }

    /**
     * BC4's encoder: a 4x4 block of one channel (0 to 255, row by row) as its 8 bytes, little-endian: two endpoints and
     * sixteen 3-bit indices. Both modes with the block's extremes as endpoints (eight values; six and exact 0 and 255,
     * for a coverage edge's empty and full texels), each refitted once by least squares; the one that errs less.
     */
    private static final class Bc4 {
        private final int[] palette = new int[8];
        private final int[] indices = new int[16];

        long encode(int[] values) {
            int min = 255, max = 0, innerMin = 255, innerMax = 0;
            for (int v : values) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                if (v != 0 && v != 255) {
                    innerMin = Math.min(innerMin, v);
                    innerMax = Math.max(innerMax, v);
                }
            }
            if (min == max) { return max | (long) min << 8; }
            long eight = fitted(values, max, min);
            long eightError = error;
            if (eightError == 0 || min > 0 && max < 255) { return eight; }
            if (innerMin > innerMax) { innerMin = innerMax = 0; }
            long six = fitted(values, innerMin, innerMax);
            return error < eightError ? six : eight;
        }

        /** The squared error of the last code {@link #fitted} returned. */
        private long error;

        /** Endpoints (e0, e1) with the nearest indices, and once more refitted to those indices; the better code. */
        private long fitted(int[] values, int e0, int e1) {
            long code = code(values, e0, e1);
            long first = error;
            boolean eight = e0 > e1;
            double aa = 0, ab = 0, bb = 0, av = 0, bv = 0;
            for (int i = 0; i < 16; i++) {
                int index = indices[i];
                if (!eight && index >= 6) { continue; }
                double t = index == 0 ? 0 : index == 1 ? 1 : (index - 1) / (eight ? 7.0 : 5.0);
                aa += (1 - t) * (1 - t);
                ab += (1 - t) * t;
                bb += t * t;
                av += (1 - t) * values[i];
                bv += t * values[i];
            }
            double det = aa * bb - ab * ab;
            if (Math.abs(det) < 1e-9) {
                error = first;
                return code;
            }
            int f0 = (int) Math.clamp(Math.round((av * bb - bv * ab) / det), 0, 255);
            int f1 = (int) Math.clamp(Math.round((bv * aa - av * ab) / det), 0, 255);
            // The endpoints' order is the mode: keep it.
            if (eight ? f0 <= f1 : f0 > f1) {
                error = first;
                return code;
            }
            long refit = code(values, f0, f1);
            if (error < first) { return refit; }
            error = first;
            return code;
        }

        /** The code of endpoints (e0, e1) and each value's nearest entry; its squared error in {@link #error}. */
        private long code(int[] values, int e0, int e1) {
            palette[0] = e0;
            palette[1] = e1;
            if (e0 > e1) {
                for (int i = 1; i < 7; i++) { palette[i + 1] = Math.round(((7 - i) * e0 + i * e1) / 7f); }
            } else {
                for (int i = 1; i < 5; i++) { palette[i + 1] = Math.round(((5 - i) * e0 + i * e1) / 5f); }
                palette[6] = 0;
                palette[7] = 255;
            }
            long bits = 0, total = 0;
            for (int i = 0; i < 16; i++) {
                int best = 0, bestError = Integer.MAX_VALUE;
                for (int p = 0; p < 8; p++) {
                    int d = values[i] - palette[p];
                    if (d * d < bestError) {
                        bestError = d * d;
                        best = p;
                    }
                }
                indices[i] = best;
                bits |= (long) best << (3 * i);
                total += bestError;
            }
            error = total;
            return e0 | (long) e1 << 8 | bits << 16;
        }
    }

    /** One chunk's masks, released when the chunk is disposed. */
    static final class Use implements Disposable {
        private final Pages pages;
        private final Set<BoardScene.Pixels> held = new HashSet<>();

        private Use(GpuRoadMaskAtlas atlas, Pages pages, Collection<BoardScene.Pixels> masks) {
            this.pages = pages;
            for (BoardScene.Pixels pixels : masks) {
                if (held.add(pixels)) { pages.acquire(atlas, pixels); }
            }
        }

        Place place(BoardScene.Pixels pixels) { return pages.slots.get(pixels).place; }

        @Override
        public void dispose() {
            held.forEach(pages::release);
            held.clear();
        }
    }

    /**
     * The 2D texture a coat's material carries for its mask (a texture attribute needs one): never sampled, it names
     * the array the coat's mask lies in. One a set of layers, so coats on one set batch together.
     */
    private static final class Handle extends Texture {
        final Pages pages;

        Handle(Pages pages) {
            super(new PixmapTextureData(new Pixmap(1, 1, Pixmap.Format.RGBA8888), null, false, true));
            this.pages = pages;
        }
    }

    /** The masks in layers: as a GL texture array. */
    private static final class MaskArray extends GLTexture {
        private final int layers;

        MaskArray(int layers) {
            super(GL30.GL_TEXTURE_2D_ARRAY);
            this.layers = layers;
            bind();
            Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, FORMAT, SIZE, SIZE, layers, 0, GL30.GL_RED,
                  GL20.GL_UNSIGNED_BYTE, null);
            // As the chunks' atlases sampled their masks: linear, no mips; a mask's edge is doubled into its margin.
            unsafeSetFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear, true);
            unsafeSetWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge, true);
        }

        @Override public int getWidth() { return SIZE; }
        @Override public int getHeight() { return SIZE; }
        @Override public int getDepth() { return layers; }
        @Override public boolean isManaged() { return false; }
        @Override protected void reload() { }

        /** A mask's cell into a layer, its corner at (x, y): multiples of 4, as its size. */
        void upload(int layer, int x, int y, Cell cell) {
            bind();
            GL13.glCompressedTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, x, y, layer, cell.width(), cell.height(), 1, FORMAT,
                  cell.blocks());
        }

        /**
         * This array's first {@code count} layers into {@code target}'s: on the card where the context is full
         * (glCopyImageSubData, OpenGL 4.3: Windows, Linux), else through client memory, read back whole
         * (glGetCompressedTexImage) and written in one call (GL 1.3: the 4.1 way, macOS). A compressed layer is no
         * framebuffer attachment, so glCopyTexSubImage3D cannot read it, and Intel's pixel-buffer round trip loses blocks.
         */
        void copyInto(MaskArray target, int count) {
            if (GpuIndirectInstances.fullContext()) {
                GL43.glCopyImageSubData(getTextureObjectHandle(), GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0,
                      target.getTextureObjectHandle(), GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, SIZE, SIZE, count);
                return;
            }
            ByteBuffer staging = MemoryUtil.memAlloc(layers * LAYER_BYTES);
            try {
                bind();
                GL13.glGetCompressedTexImage(GL30.GL_TEXTURE_2D_ARRAY, 0, staging);
                staging.limit(count * LAYER_BYTES);
                target.bind();
                GL13.glCompressedTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, SIZE, SIZE, count, FORMAT, staging);
            } finally {
                MemoryUtil.memFree(staging);
            }
        }
    }

    /** One layer's free room as a skyline: steps {x, top, width} left to right; a cell goes where its bottom is lowest. */
    private static final class Skyline {
        private final List<int[]> steps = new ArrayList<>(List.of(new int[] { 0, 0, SIZE }));

        /** Takes a cell: its corner {x, y}, or null where the layer has no room for it. */
        int[] take(int width, int height) {
            int best = -1, bestX = 0, bestY = Integer.MAX_VALUE;
            for (int i = 0; i < steps.size(); i++) {
                int x = steps.get(i)[0];
                if (x + width > SIZE) { break; }
                int y = 0;
                for (int j = i, covered = 0; covered < width; j++) {
                    y = Math.max(y, steps.get(j)[1]);
                    covered += steps.get(j)[2];
                }
                if (y + height <= SIZE && y < bestY) {
                    best = i;
                    bestX = x;
                    bestY = y;
                }
            }
            if (best < 0) { return null; }
            int end = bestX + width;
            while (best < steps.size() && steps.get(best)[0] < end) {
                int[] step = steps.get(best);
                int stepEnd = step[0] + step[2];
                if (stepEnd <= end) {
                    steps.remove(best);
                } else {
                    step[2] = stepEnd - end;
                    step[0] = end;
                    break;
                }
            }
            steps.add(best, new int[] { bestX, bestY + height, width });
            for (int i = 0; i + 1 < steps.size(); ) {
                if (steps.get(i)[1] == steps.get(i + 1)[1]) {
                    steps.get(i)[2] += steps.get(i + 1)[2];
                    steps.remove(i + 1);
                } else {
                    i++;
                }
            }
            return new int[] { bestX, bestY };
        }
    }

    private static final class Slot {
        /** Its layer, its corner and its size in texels (multiples of 4). */
        final int layer, x, y, width, height;
        Place place;
        int users;

        Slot(int layer, int x, int y, int width, int height) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** Holds {@code pixels}, drawn into this slot as {@code cell}: its place. */
        void hold(Handle handle, BoardScene.Pixels pixels, Cell cell) {
            float u = layer + (x + 1) / (float) SIZE, v = (y + 1) / (float) SIZE;
            float w = pixels.width() / (float) SIZE, h = pixels.height() / (float) SIZE;
            int tint = cell.tint();
            place = tint < 0 ? new Place(handle, u, v, w, h)
                  : new Place(handle, u, 1 + (tint >>> 16) + v, (tint >>> 8 & 255) + w, (tint & 255) + h);
        }
    }

    /** One set of layers and the slots in them. */
    private static final class Pages implements Disposable {
        private final Map<BoardScene.Pixels, Slot> slots = new HashMap<>();
        /** The masks {@link #slots} holds, for the workers ({@link GpuRoadMaskAtlas#prepare}). */
        final Set<BoardScene.Pixels> resident = ConcurrentHashMap.newKeySet();
        /** The slots no chunk uses, by size (width * 65536 + height): the next mask of that size is drawn into one. */
        private final Map<Integer, ArrayDeque<Slot>> free = new HashMap<>();
        private final List<Skyline> layers = new ArrayList<>();
        /** Made with the first layer and freed with the last mask (#clear), as the array is. */
        private Handle handle;
        private MaskArray array;
        /**
         * Trimmed once: from then on it grows by a quarter where its copy stays on the card, and still twofold on the 4.1
         * way, whose copy passes through client memory (copyInto), so that it copies rarely.
         */
        private boolean settled;
        private long packedArea, liveArea;

        boolean wasteful() {
            return packedArea > 2 * liveArea + (long) SIZE * SIZE;
        }

        private void acquire(GpuRoadMaskAtlas atlas, BoardScene.Pixels pixels) {
            Slot slot = slots.get(pixels);
            // A cell a worker encoded waits no longer, though another chunk may have uploaded its mask meanwhile.
            Cell cell = atlas.take(pixels);
            if (slot == null) {
                if (cell == null) { cell = encode(pixels); }
                ArrayDeque<Slot> spare = free.get(cell.width() << 16 | cell.height());
                slot = spare == null ? null : spare.poll();
                if (slot == null) { slot = pack(cell.width(), cell.height()); }
                array.upload(slot.layer, slot.x, slot.y, cell);
                slot.hold(handle, pixels, cell);
                slots.put(pixels, slot);
                resident.add(pixels);
                liveArea += (long) slot.width * slot.height;
            }
            slot.users++;
        }

        private void release(BoardScene.Pixels pixels) {
            Slot slot = slots.get(pixels);
            if (--slot.users > 0) { return; }
            slots.remove(pixels);
            resident.remove(pixels);
            liveArea -= (long) slot.width * slot.height;
            free.computeIfAbsent(slot.width << 16 | slot.height, size -> new ArrayDeque<>()).add(slot);
            // Masks no chunk draws with keep no layers: a board without roads, the last chunk of an old set.
            if (slots.isEmpty()) { clear(); }
        }

        /** A new slot of {@code width} x {@code height} texels: multiples of 4, so every corner in a layer is too. */
        private Slot pack(int width, int height) {
            if (handle == null) { handle = new Handle(this); }
            int layer = -1;
            int[] cell = null;
            for (int i = 0; i < layers.size() && cell == null; i++) {
                cell = layers.get(i).take(width, height);
                layer = i;
            }
            if (cell == null) {
                layers.add(new Skyline());
                layer = layers.size() - 1;
                cell = layers.get(layer).take(width, height);
            }
            packedArea += (long) width * height;
            if (array == null || layer >= array.getDepth()) {
                int depth = array == null ? Math.max(4, layer + 1)
                      : Math.max(layer + 1, settled && GpuIndirectInstances.fullContext()
                            ? array.getDepth() + Math.max(1, array.getDepth() / 4) : 2 * array.getDepth());
                resize(depth);
            }
            return new Slot(layer, cell[0], cell[1], width, height);
        }

        /** An array of {@code depth} layers holding this one's filled layers, copied on the card. */
        private void resize(int depth) {
            MaskArray next = new MaskArray(depth);
            if (array != null) {
                array.copyInto(next, Math.min(Math.min(array.getDepth(), depth), layers.size()));
                array.dispose();
            }
            array = next;
        }

        void trim() {
            if (array == null || layers.isEmpty() || array.getDepth() <= layers.size()) { return; }
            // Once settled, the spare layers a growth gave stay: trimming them would copy the array again, and so would
            // the next growth an edit needs.
            if (settled && array.getDepth() <= layers.size() + Math.max(1, layers.size() / 4)) { return; }
            resize(layers.size());
            settled = true;
        }

        private void clear() {
            if (array != null) { array.dispose(); }
            array = null;
            if (handle != null) { handle.dispose(); }
            handle = null;
            layers.clear();
            free.clear();
            resident.clear();
            packedArea = 0;
            settled = false;
        }

        @Override
        public void dispose() { clear(); }
    }
}
