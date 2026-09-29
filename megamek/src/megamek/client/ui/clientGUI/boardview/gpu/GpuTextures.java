/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
    private record Entry(Images images, String name, TextureRegion region) { }

    private final Map<K, Entry> entries = new HashMap<>();
    private final Map<Texture, Texture> normals = new IdentityHashMap<>();
    private final boolean mipmaps;
    private PixmapPacker packer;
    private TextureAtlas atlas;
    private PixmapPacker normalPacker;
    private TextureAtlas normalAtlas;
    private int slotCount;
    private long packedArea;
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
        slotCount = 0;
        packedArea = 0;
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
        Map<K, Images> images = new HashMap<>();
        colors.forEach((key, pixels) -> {
            BoardScene.Pixels normal = normalImages.get(key);
            if (normal != null && (normal.width() != pixels.width() || normal.height() != pixels.height())) {
                throw new IllegalArgumentException("Normal map dimensions must match the ground artwork");
            }
            images.put(key, new Images(pixels, normal));
        });
        boolean normalMaps = !normalImages.isEmpty();
        if (images.isEmpty()) {
            Set<K> changed = Set.copyOf(entries.keySet());
            replacePages();
            return changed;
        }
        Map<Entry, Images> replacements = new IdentityHashMap<>();
        boolean sameLayout = atlas != null && (normalAtlas != null) == normalMaps && entries.keySet().equals(images.keySet())
              && images.entrySet().stream().allMatch(item -> {
                  Entry old = entries.get(item.getKey());
                  Images pixels = item.getValue();
                  Images shared = replacements.putIfAbsent(old, pixels);
                  // A shared slot can change in place only if all of its users still agree.
                  return old.images().color().width() == pixels.color().width()
                        && old.images().color().height() == pixels.color().height()
                        && (shared == null || shared.equals(pixels));
              });
        // Previously different images may become identical after an edit; merge those slots too.
        sameLayout &= new HashSet<>(replacements.values()).size() == replacements.size();
        // Pending terrain must not repaint the pages still displayed by the previous revision. Append changed
        // artwork to new slots; publication will switch every mesh whose UVs changed in the same frame.
        sameLayout &= !retainReplaced || replacements.entrySet().stream()
              .allMatch(item -> item.getKey().images().equals(item.getValue()));
        if (sameLayout) {
            Set<Texture> updated = new HashSet<>();
            Map<Entry, Entry> next = new IdentityHashMap<>();
            replacements.forEach((old, pixels) -> {
                if (!old.images().equals(pixels)) {
                    // PixmapPacker duplicates one edge pixel. Refresh it as well as the interior when a slot changes.
                    replace(packer, old.name(), old.region(), pixels.color(), false);
                    next.put(old, new Entry(pixels, old.name(), old.region()));
                    updated.add(old.region().getTexture());
                    if (normalMaps) {
                        TextureRegion normal = normalAtlas.findRegion(old.name());
                        replace(normalPacker, old.name(), normal,
                              pixels.normal() == null ? pixels.color() : pixels.normal(), pixels.normal() == null);
                        updated.add(normal.getTexture());
                    }
                }
            });
            entries.replaceAll((key, old) -> next.getOrDefault(old, old));
            if (mipmaps) {
                updated.forEach(texture -> {
                    texture.bind();
                    Gdx.gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
                });
            }
            return Set.of();
        }
        Map<K, Entry> before = new HashMap<>(entries);
        Map<Images, Entry> shared = new HashMap<>();
        entries.values().forEach(entry -> shared.put(entry.images(), entry));
        Set<Images> unique = new HashSet<>(images.values());
        long area = unique.stream().mapToLong(GpuTextures::area).sum();
        long addedArea = unique.stream().filter(pixels -> !shared.containsKey(pixels)).mapToLong(GpuTextures::area).sum();
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
            shared.clear();
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
        Map<Images, String> names = new HashMap<>();
        images.forEach((key, pixels) -> {
            if (shared.containsKey(pixels) || names.containsKey(pixels)) {
                return;
            }
            String name = "image" + slotCount++;
            pack(packer, name, pixels.color(), false);
            if (normalMaps) {
                // Identical sizes and insertion order give both atlases the same pages and UVs.
                // Deduplicate the pair: identical colors can carry different authored normal maps.
                pack(normalPacker, name, pixels.normal() == null ? pixels.color() : pixels.normal(), pixels.normal() == null);
            }
            names.put(pixels, name);
            packedArea += area(pixels);
        });
        if (!names.isEmpty()) {
            atlas = atlas(packer, atlas);
            if (normalMaps) {
                normalAtlas = atlas(normalPacker, normalAtlas);
                names.values().forEach(name -> normals.put(atlas.findRegion(name).getTexture(),
                      normalAtlas.findRegion(name).getTexture()));
            }
        }
        names.forEach((pixels, name) -> shared.put(pixels, new Entry(pixels, name, atlas.findRegion(name))));
        entries.clear();
        images.forEach((key, pixels) -> entries.put(key, shared.get(pixels)));
        Set<K> changed = new HashSet<>(before.keySet());
        changed.addAll(entries.keySet());
        changed.removeIf(key -> before.containsKey(key) && entries.containsKey(key)
              && before.get(key).region() == entries.get(key).region());
        return changed;
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
        Pixmap pixmap = pixmap(pixels, 1, flatNormal);
        try {
            // Keep the managed backing image in sync for context restoration as well.
            Pixmap page = target.getPage(name).getPixmap();
            page.setBlending(Pixmap.Blending.None);
            page.drawPixmap(pixmap, region.getRegionX() - 1, region.getRegionY() - 1);
            region.getTexture().bind();
            Gdx.gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, region.getRegionX() - 1, region.getRegionY() - 1,
                  pixmap.getWidth(), pixmap.getHeight(), pixmap.getGLFormat(), pixmap.getGLType(), pixmap.getPixels());
        } finally {
            pixmap.dispose();
        }
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
        return entries.get(key).region();
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
        slotCount = 0;
        packedArea = 0;
    }
}
