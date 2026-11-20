/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.GLTexture;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.glutils.PixmapTextureData;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;

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
 * The layers live on the card alone: the packer keeps rectangles, not pictures. The array grows by copying its layers on
 * the card into a deeper one (doubling while a board loads, a quarter more once it settled), and a finished rebuild
 * trims it to the layers its masks fill ({@link #trim}). Render thread only.
 */
final class GpuRoadMaskAtlas implements Disposable {
    /** A layer's side, and the texels a mask's cell adds to it: its edge doubled, one texel a side, as GpuTextures packs. */
    private static final int SIZE = 2048, BLEED = 2;

    /**
     * Where a mask lies: U offset (its layer plus its U in the layer: exact in a float, the U being a multiple of
     * 1/2048 under 1), V offset, and its size in the layer, as a coat's vertices carry a region (a_roadMaskRegion).
     */
    record Place(Texture texture, float u, float v, float width, float height) { }

    private Pages pages = new Pages();

    /** A chunk's use of these masks: those the layers do not hold yet are packed and uploaded now. */
    Use use(Collection<BoardScene.Pixels> masks) {
        if (pages.wasteful()) { pages = new Pages(); }
        return new Use(pages, masks);
    }

    /** The array a coat's stand-in texture names ({@link Place#texture}): the one its shaders sample. */
    static GLTexture array(Texture texture) { return ((Handle) texture).pages.array; }

    /** After a finished rebuild: the array keeps only the layers its masks fill, copied on the card. */
    void trim() { pages.trim(); }

    @Override
    public void dispose() { pages.dispose(); }

    private static long area(BoardScene.Pixels pixels) {
        return (long) (pixels.width() + BLEED) * (pixels.height() + BLEED);
    }

    /** One chunk's masks, released when the chunk is disposed. */
    static final class Use implements Disposable {
        private final Pages pages;
        private final Set<BoardScene.Pixels> held = new HashSet<>();

        private Use(Pages pages, Collection<BoardScene.Pixels> masks) {
            this.pages = pages;
            for (BoardScene.Pixels pixels : masks) {
                if (held.add(pixels)) { pages.acquire(pixels); }
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
            Gdx.gl30.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL30.GL_RGBA8, SIZE, SIZE, layers, 0, GL20.GL_RGBA,
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

        /** A mask and its doubled edge into a layer: its cell's corner at (x - 1, y - 1). */
        void upload(int layer, int x, int y, BoardScene.Pixels pixels) {
            Pixmap image = GpuTextures.pixmap(pixels, 1);
            try {
                bind();
                // RGBA rows are whole words: no unpack state to set.
                Gdx.gl30.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, x - 1, y - 1, layer, image.getWidth(), image.getHeight(), 1,
                      GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, image.getPixels());
            } finally {
                image.dispose();
            }
        }

        /**
         * This array's first {@code count} layers into {@code target}'s, on the card: each layer attached to a read
         * framebuffer of its own and copied into the bound array (GL 3.0, so the 4.1 floor and macOS too).
         */
        void copyInto(MaskArray target, int count) {
            IntBuffer bound = BufferUtils.newIntBuffer(16);
            Gdx.gl.glGetIntegerv(GL30.GL_READ_FRAMEBUFFER_BINDING, bound);
            int framebuffer = Gdx.gl.glGenFramebuffer();
            try {
                Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
                target.bind();
                for (int layer = 0; layer < count; layer++) {
                    Gdx.gl30.glFramebufferTextureLayer(GL30.GL_READ_FRAMEBUFFER, GL20.GL_COLOR_ATTACHMENT0, getTextureObjectHandle(), 0, layer);
                    Gdx.gl30.glCopyTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, 0, 0, SIZE, SIZE);
                }
            } finally {
                Gdx.gl.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, bound.get(0));
                Gdx.gl.glDeleteFramebuffer(framebuffer);
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
        final int layer, x, y;
        final Place place;
        BoardScene.Pixels pixels;
        int users;

        Slot(int layer, int x, int y, Place place, BoardScene.Pixels pixels) {
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.place = place;
            this.pixels = pixels;
        }
    }

    /** One set of layers and the slots in them. */
    private static final class Pages implements Disposable {
        private final Map<BoardScene.Pixels, Slot> slots = new HashMap<>();
        /** The slots no chunk uses, by size (width * 65536 + height): the next mask of that size is drawn into one. */
        private final Map<Integer, ArrayDeque<Slot>> free = new HashMap<>();
        private final List<Skyline> layers = new ArrayList<>();
        /** Made with the first layer and freed with the last mask (#clear), as the array is. */
        private Handle handle;
        private MaskArray array;
        /** Trimmed once: from then on it grows by a quarter, not twofold. */
        private boolean settled;
        private long packedArea, liveArea;

        boolean wasteful() {
            return packedArea > 2 * liveArea + (long) SIZE * SIZE;
        }

        private void acquire(BoardScene.Pixels pixels) {
            Slot slot = slots.get(pixels);
            if (slot == null) {
                ArrayDeque<Slot> spare = free.get(pixels.width() << 16 | pixels.height());
                slot = spare == null ? null : spare.poll();
                if (slot != null) {
                    slot.pixels = pixels;
                    array.upload(slot.layer, slot.x, slot.y, pixels);
                } else {
                    slot = pack(pixels);
                }
                slots.put(pixels, slot);
                liveArea += area(pixels);
            }
            slot.users++;
        }

        private void release(BoardScene.Pixels pixels) {
            Slot slot = slots.get(pixels);
            if (--slot.users > 0) { return; }
            slots.remove(pixels);
            liveArea -= area(pixels);
            free.computeIfAbsent(pixels.width() << 16 | pixels.height(), size -> new ArrayDeque<>()).add(slot);
            // Masks no chunk draws with keep no layers: a board without roads, the last chunk of an old set.
            if (slots.isEmpty()) { clear(); }
        }

        private Slot pack(BoardScene.Pixels pixels) {
            if (handle == null) { handle = new Handle(this); }
            int width = pixels.width() + BLEED, height = pixels.height() + BLEED, layer = -1;
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
            packedArea += area(pixels);
            if (array == null || layer >= array.getDepth()) {
                int depth = array == null ? Math.max(4, layer + 1)
                      : Math.max(layer + 1, settled ? array.getDepth() + Math.max(1, array.getDepth() / 4) : 2 * array.getDepth());
                resize(depth);
            }
            int x = cell[0] + 1, y = cell[1] + 1;
            array.upload(layer, x, y, pixels);
            Place place = new Place(handle, layer + x / (float) SIZE, y / (float) SIZE, pixels.width() / (float) SIZE,
                  pixels.height() / (float) SIZE);
            return new Slot(layer, x, y, place, pixels);
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
            packedArea = 0;
            settled = false;
        }

        @Override
        public void dispose() { clear(); }
    }
}
