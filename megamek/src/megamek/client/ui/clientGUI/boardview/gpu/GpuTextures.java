/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.GL30;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Disposable;

/** Shared atlas ownership. Pixel changes update existing slots without invalidating mesh UVs. */
final class GpuTextures<K> implements Disposable {
    private static final int ATLAS_BLEED = 2;
    private record Images(BoardScene.Pixels color, BoardScene.Pixels normal) { }

    /** One packed slot. Keys with equal artwork share it; {@code users} counts them. */
    private static final class Entry {
        final Images images;
        final String name;
        final TextureRegion region, normal;
        int users;

        Entry(Images images, String name, TextureRegion region, TextureRegion normal) {
            this.images = images;
            this.name = name;
            this.region = region;
            this.normal = normal;
        }
    }

    private final Map<K, Entry> entries = new HashMap<>();
    /** The slots some key uses, by artwork. A slot whose last key moved on stays packed until the pages are rebuilt. */
    private final Map<Images, Entry> slots = new HashMap<>();
    private final Map<Texture, Texture> normals = new IdentityHashMap<>();
    private final boolean mipmaps;
    private PixmapPacker packer;
    private TextureAtlas atlas;
    private PixmapPacker normalPacker;
    private TextureAtlas normalAtlas;
    private int slotCount;
    private long packedArea, liveArea;
    private boolean retainReplaced;
    private record Pages(TextureAtlas color, PixmapPacker colorPacker, TextureAtlas normal, PixmapPacker normalPacker)
          implements Disposable {
        @Override
        public void dispose() {
            for (Disposable resource : new Disposable[] { color, colorPacker, normal, normalPacker }) {
                if (resource != null) { resource.dispose(); }
            }
        }
    }
    private final List<Pages> retired = new ArrayList<>();
    private TextureAtlas publishedColor, publishedNormal;

    /** Terrain keeps old atlas pages alive until all meshes borrowing them have been retired. */
    void retainReplacedPages() { retainReplaced = true; }

    void publish() { publishedColor = atlas; publishedNormal = normalAtlas; }

    void releaseRetiredPages() {
        retired.removeIf(pages -> {
            if (pages.color() != null && pages.color() == publishedColor
                  || pages.normal() != null && pages.normal() == publishedNormal) { return false; }
            pages.dispose();
            return true;
        });
    }

    private void replacePages() {
        if (!retainReplaced) { dispose(); return; }
        Pages pages = new Pages(atlas, packer, normalAtlas, normalPacker);
        if (atlas != null && atlas == publishedColor || normalAtlas != null && normalAtlas == publishedNormal) {
            retired.add(pages);
        } else { pages.dispose(); }
        atlas = null;
        packer = null;
        normalAtlas = null;
        normalPacker = null;
        normals.clear();
        entries.clear();
        slots.clear();
        slotCount = 0;
        packedArea = 0;
        liveArea = 0;
    }

    GpuTextures() {
        this(false);
    }

    GpuTextures(boolean mipmaps) {
        this.mipmaps = mipmaps;
    }

    /** Returns true only when the atlas layout changes. Must run on the GL thread. */
    boolean update(Map<K, BoardScene.Pixels> images) {
        return update(images, Map.of());
    }

    /** Optional pre-generated normals share each color slot's placement and lifetime. */
    boolean update(Map<K, BoardScene.Pixels> colors, Map<K, BoardScene.Pixels> normalImages) {
        return !updateRegions(colors, normalImages).isEmpty();
    }

    /** Keys whose UVs or texture pages changed; unchanged meshes can keep their existing atlas references. */
    Set<K> updateRegions(Map<K, BoardScene.Pixels> colors, Map<K, BoardScene.Pixels> normalImages) {
        boolean normalMaps = !normalImages.isEmpty();
        if (colors.isEmpty()) {
            Set<K> changed = Set.copyOf(entries.keySet());
            replacePages();
            return changed;
        }
        // An edit hands back the same pixel objects for every slot it left alone. Those are told apart by reference,
        // so a large board costs one lookup per slot rather than a rebuilt index, and only the rest get a look.
        Map<K, Images> changed = atlas != null && (normalAtlas != null) == normalMaps
              && entries.size() == colors.size() ? changedSlots(colors, normalImages) : null;
        if (changed != null && changed.isEmpty()) { return Set.of(); }
        if (changed != null && retainReplaced) {
            Set<K> appended = append(changed, normalMaps);
            if (appended != null) { return appended; }
        }
        Map<K, Images> images = new HashMap<>();
        colors.forEach((key, pixels) -> images.put(key, images(pixels, normalImages.get(key))));
        Map<Entry, Images> replacements = new IdentityHashMap<>();
        boolean sameLayout = atlas != null && (normalAtlas != null) == normalMaps && entries.keySet().equals(images.keySet())
              && images.entrySet().stream().allMatch(item -> {
                  Entry old = entries.get(item.getKey());
                  Images pixels = item.getValue();
                  Images shared = replacements.putIfAbsent(old, pixels);
                  // A shared slot can change in place only if all of its users still agree.
                  return old.images.color().width() == pixels.color().width()
                        && old.images.color().height() == pixels.color().height()
                        && (shared == null || shared.equals(pixels));
              });
        // Previously different images may become identical after an edit; merge those slots too.
        sameLayout &= new HashSet<>(replacements.values()).size() == replacements.size();
        // Pending terrain must not repaint the pages still displayed by the previous revision. Append changed
        // artwork to new slots; publication will switch every mesh whose UVs changed in the same frame.
        sameLayout &= !retainReplaced || replacements.entrySet().stream()
              .allMatch(item -> item.getKey().images.equals(item.getValue()));
        if (sameLayout) {
            Set<Texture> updated = new HashSet<>();
            Map<Entry, Entry> next = new IdentityHashMap<>();
            replacements.forEach((old, pixels) -> {
                if (!old.images.equals(pixels)) {
                    // PixmapPacker duplicates one edge pixel. Refresh it as well as the interior when a slot changes.
                    replace(packer, old.name, old.region, pixels.color(), false);
                    Entry entry = new Entry(pixels, old.name, old.region, old.normal);
                    entry.users = old.users;
                    next.put(old, entry);
                    updated.add(old.region.getTexture());
                    if (normalMaps) {
                        replace(normalPacker, old.name, old.normal,
                              pixels.normal() == null ? pixels.color() : pixels.normal(), pixels.normal() == null);
                        updated.add(old.normal.getTexture());
                    }
                }
            });
            // Two slots may have traded artwork; clear every replaced one before indexing the new contents.
            next.keySet().forEach(old -> slots.remove(old.images));
            next.values().forEach(entry -> slots.put(entry.images, entry));
            entries.replaceAll((key, old) -> next.getOrDefault(old, old));
            if (mipmaps) { updated.forEach(GpuTextures::regenerateMipmaps); }
            return Set.of();
        }
        Map<K, Entry> before = new HashMap<>(entries);
        Set<Images> unique = new HashSet<>(images.values());
        long area = unique.stream().mapToLong(GpuTextures::area).sum();
        long addedArea = unique.stream().filter(pixels -> !slots.containsKey(pixels))
              .mapToLong(GpuTextures::area).sum();
        int imageWidth = unique.stream().mapToInt(pixels -> pixels.color().width()).max().orElse(0);
        int imageHeight = unique.stream().mapToInt(pixels -> pixels.color().height()).max().orElse(0);
        // Append new artwork without moving any live slot. Compact only when discarded artwork would more
        // than double the live storage (with one page of headroom), or the page format/size must change.
        boolean repack = atlas == null || (normalAtlas != null) != normalMaps
              || imageWidth + 3 * ATLAS_BLEED > packer.getPageWidth()
              || imageHeight + 3 * ATLAS_BLEED > packer.getPageHeight()
              || packedArea + addedArea > Math.max(2 * area, (long) packer.getPageWidth() * packer.getPageHeight());
        if (repack) {
            replacePages();
            int side = 128;
            while (side < 2048 && side * (long) side < area * 1.3) { side *= 2; }
            // Guillotine packing reserves two outer margins AND one margin on each packed rectangle.
            int width = Math.max(side, imageWidth + 3 * ATLAS_BLEED);
            int height = Math.max(side, imageHeight + 3 * ATLAS_BLEED);
            packer = new PixmapPacker(width, height, Pixmap.Format.RGBA8888, ATLAS_BLEED, true);
            if (normalMaps) {
                normalPacker = new PixmapPacker(width, height, Pixmap.Format.RGBA8888, ATLAS_BLEED, true);
            }
        }
        Map<Images, String> names = new LinkedHashMap<>();
        images.forEach((key, pixels) -> {
            if (!slots.containsKey(pixels) && !names.containsKey(pixels)) {
                names.put(pixels, pack(pixels, normalMaps));
            }
        });
        if (!names.isEmpty()) {
            atlas = atlas(packer, atlas);
            if (normalMaps) { normalAtlas = atlas(normalPacker, normalAtlas); }
            names.forEach((pixels, name) -> index(pixels, name, atlas.findRegion(name),
                  normalMaps ? normalAtlas.findRegion(name) : null));
        }
        // Rebind every key; a slot no key uses any more leaves the index, its pixels staying packed until a repack.
        entries.clear();
        slots.values().forEach(entry -> entry.users = 0);
        images.forEach((key, pixels) -> {
            Entry entry = slots.get(pixels);
            entry.users++;
            entries.put(key, entry);
        });
        slots.values().removeIf(entry -> entry.users == 0);
        liveArea = area;
        Set<K> moved = new HashSet<>(before.keySet());
        moved.addAll(entries.keySet());
        moved.removeIf(key -> before.containsKey(key) && entries.containsKey(key)
              && before.get(key).region == entries.get(key).region);
        return moved;
    }

    private static Images images(BoardScene.Pixels color, BoardScene.Pixels normal) {
        if (normal != null && (normal.width() != color.width() || normal.height() != color.height())) {
            throw new IllegalArgumentException("Normal map dimensions must match the ground artwork");
        }
        return new Images(color, normal);
    }

    /** The keys given other artwork than their slot holds, in the order given; {@code null} if a key is new. */
    private Map<K, Images> changedSlots(Map<K, BoardScene.Pixels> colors,
          Map<K, BoardScene.Pixels> normalImages) {
        Map<K, Images> changed = new LinkedHashMap<>();
        for (var item : colors.entrySet()) {
            Entry old = entries.get(item.getKey());
            if (old == null) { return null; }
            BoardScene.Pixels normal = normalImages.get(item.getKey());
            if (old.images.color() == item.getValue() && old.images.normal() == normal) { continue; }
            // The same artwork under another object is still the slot's artwork.
            Images images = images(item.getValue(), normal);
            if (!old.images.equals(images)) { changed.put(item.getKey(), images); }
        }
        return changed;
    }

    /**
     * Rebinds the changed keys to slots holding their new artwork, packing artwork no slot in use holds into the
     * pages on display: only those rectangles are uploaded, and the slots the keys vacate keep their pixels for
     * the meshes still showing them. Returns {@code null} when the pages must be rebuilt instead, which the full
     * update decides by the same rule.
     */
    private Set<K> append(Map<K, Images> changed, boolean normalMaps) {
        Map<Entry, Integer> arrivals = new IdentityHashMap<>();
        Set<Images> fresh = new HashSet<>();
        long addedArea = 0;
        for (var item : changed.entrySet()) {
            Images images = item.getValue();
            arrivals.merge(entries.get(item.getKey()), -1, Integer::sum);
            Entry target = slots.get(images);
            if (target != null) {
                arrivals.merge(target, 1, Integer::sum);
            } else if (images.color().width() + 3 * ATLAS_BLEED > packer.getPageWidth()
                  || images.color().height() + 3 * ATLAS_BLEED > packer.getPageHeight()) {
                return null;
            } else if (fresh.add(images)) {
                addedArea += area(images);
            }
        }
        long area = liveArea + addedArea;
        for (var item : arrivals.entrySet()) {
            if (item.getKey().users + item.getValue() == 0) { area -= area(item.getKey().images); }
        }
        if (packedArea + addedArea > Math.max(2 * area, (long) packer.getPageWidth() * packer.getPageHeight())) {
            return null;
        }
        Map<Images, String> names = new LinkedHashMap<>();
        for (Images images : changed.values()) {
            if (fresh.contains(images) && !names.containsKey(images)) { names.put(images, pack(images, normalMaps)); }
        }
        boolean opened = names.values().stream().anyMatch(name -> packer.getPage(name).getTexture() == null);
        if (opened) {
            // A further page gets its texture from the atlas, which also refreshes the pages packed into here.
            atlas = atlas(packer, atlas);
            if (normalMaps) { normalAtlas = atlas(normalPacker, normalAtlas); }
        }
        Set<Texture> updated = new HashSet<>();
        names.forEach((pixels, name) -> index(pixels, name,
              opened ? atlas.findRegion(name) : upload(packer, name, updated),
              !normalMaps ? null : opened ? normalAtlas.findRegion(name) : upload(normalPacker, name, updated)));
        if (mipmaps) { updated.forEach(GpuTextures::regenerateMipmaps); }
        changed.forEach((key, images) -> {
            entries.get(key).users--;
            Entry entry = slots.get(images);
            entry.users++;
            entries.put(key, entry);
        });
        for (Entry entry : arrivals.keySet()) {
            if (entry.users == 0) {
                slots.remove(entry.images);
                liveArea -= area(entry.images);
            }
        }
        return changed.keySet();
    }

    /** Packs artwork into the next slot of both packers and returns the slot's name. */
    private String pack(Images pixels, boolean normalMaps) {
        String name = "image" + slotCount++;
        pack(packer, name, pixels.color(), false);
        if (normalMaps) {
            // Identical sizes and insertion order give both atlases the same pages and UVs.
            // Deduplicate the pair: identical colors can carry different authored normal maps.
            pack(normalPacker, name, pixels.normal() == null ? pixels.color() : pixels.normal(), pixels.normal() == null);
        }
        packedArea += area(pixels);
        return name;
    }

    private void index(Images pixels, String name, TextureRegion region, TextureRegion normal) {
        if (normal != null) { normals.put(region.getTexture(), normal.getTexture()); }
        slots.put(pixels, new Entry(pixels, name, region, normal));
        liveArea += area(pixels);
    }

    private static long area(Images pixels) {
        return (long) (pixels.color().width() + ATLAS_BLEED) * (pixels.color().height() + ATLAS_BLEED);
    }

    private TextureAtlas atlas(PixmapPacker source, TextureAtlas result) {
        if (result == null) { result = new TextureAtlas(); }
        source.updateTextureAtlas(result, mipmaps ? Texture.TextureFilter.MipMapLinearLinear : Texture.TextureFilter.Linear,
              Texture.TextureFilter.Linear, mipmaps);
        if (mipmaps) {
            for (Texture texture : result.getTextures()) {
                texture.bind();
                // Keep the sampling footprint inside the artwork margin and duplicated atlas border.
                Gdx.gl.glTexParameterf(GL20.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 2);
            }
        }
        return result;
    }

    private static void pack(PixmapPacker target, String name, BoardScene.Pixels pixels, boolean flatNormal) {
        Pixmap pixmap = pixmap(pixels, 0, flatNormal);
        try {
            target.pack(name, pixmap);
        } finally {
            pixmap.dispose();
        }
    }

    private static void replace(PixmapPacker target, String name, TextureRegion region, BoardScene.Pixels pixels, boolean flatNormal) {
        Pixmap page = target.getPage(name).getPixmap();
        Pixmap pixmap = pixmap(pixels, 1, flatNormal);
        try {
            // Keep the managed backing image in sync for context restoration as well.
            page.setBlending(Pixmap.Blending.None);
            page.drawPixmap(pixmap, region.getRegionX() - 1, region.getRegionY() - 1);
        } finally {
            pixmap.dispose();
        }
        upload(page, region);
    }

    /** The region of a slot packed into a page already on display, after uploading just that slot to its texture. */
    private static TextureRegion upload(PixmapPacker source, String name, Set<Texture> updated) {
        PixmapPacker.Page page = source.getPage(name);
        PixmapPacker.Bounds rect = source.getRect(name).bounds;
        TextureRegion region = new TextureRegion(page.getTexture(), rect.x, rect.y, rect.width, rect.height);
        upload(page.getPixmap(), region);
        updated.add(region.getTexture());
        return region;
    }

    /** Uploads one slot and its duplicated border from the page's backing image; the rest of the page stays as it is. */
    private static void upload(Pixmap page, TextureRegion region) {
        int x = region.getRegionX() - 1, y = region.getRegionY() - 1;
        int width = region.getRegionWidth() + 2, height = region.getRegionHeight() + 2;
        Pixmap part = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            part.setBlending(Pixmap.Blending.None);
            part.drawPixmap(page, x, y, width, height, 0, 0, width, height);
            region.getTexture().bind();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, x, y, width, height, part.getGLFormat(), part.getGLType(),
                  part.getPixels());
        } finally {
            part.dispose();
        }
    }

    private static void regenerateMipmaps(Texture texture) {
        texture.bind();
        Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
    }

    static Pixmap pixmap(BoardScene.Pixels image, int border, boolean flatNormal) {
        Pixmap result = new Pixmap(image.width() + 2 * border, image.height() + 2 * border, Pixmap.Format.RGBA8888);
        result.setBlending(Pixmap.Blending.None);
        // One direct-buffer write loop instead of one JNI call per pixel. RGBA bytes have fixed order.
        var buffer = result.getPixels().duplicate().order(ByteOrder.BIG_ENDIAN).asIntBuffer();
        if (flatNormal) {
            while (buffer.hasRemaining()) { buffer.put(0x8080ffff); }
        } else if (border == 0) {
            image.writeRgba(buffer, 0, image.width() * image.height());
        } else {
            for (int y = -border; y < image.height() + border; y++) {
                int row = Math.clamp(y, 0, image.height() - 1) * image.width();
                for (int x = 0; x < border; x++) { buffer.put(image.rgba(row)); }
                image.writeRgba(buffer, row, image.width());
                for (int x = 0; x < border; x++) { buffer.put(image.rgba(row + image.width() - 1)); }
            }
        }
        return result;
    }

    TextureRegion region(K key) {
        return entries.get(key).region;
    }

    /** The normal page uses exactly the diffuse page's UVs, including its duplicated border and mip levels. */
    Texture normal(Texture diffuse) {
        return normals.get(diffuse);
    }

    @Override
    public void dispose() {
        publishedColor = null;
        publishedNormal = null;
        releaseRetiredPages();
        if (atlas != null) {
            atlas.dispose();
            atlas = null;
        }
        if (packer != null) {
            packer.dispose();
            packer = null;
        }
        if (normalAtlas != null) {
            normalAtlas.dispose();
            normalAtlas = null;
        }
        if (normalPacker != null) {
            normalPacker.dispose();
            normalPacker = null;
        }
        normals.clear();
        entries.clear();
        slots.clear();
        slotCount = 0;
        packedArea = 0;
        liveArea = 0;
    }
}
