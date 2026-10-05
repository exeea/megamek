/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.Map;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;

/** Models borrow textures from their library's cache; each cache alone owns their disposal. */
final class ModelTextures {
    private ModelTextures() { }

    static Model create(ModelData data, Map<String, Texture> cache, Function<String, Texture> external) {
        com.badlogic.gdx.graphics.g3d.utils.TextureProvider provider = key -> {
            var image = data instanceof RigidGlb.Data glb ? glb.images.get(key) : null;
            if (image == null) { return external.apply(key); }
            return cache.computeIfAbsent(key, ignored -> {
                Texture texture = image.encoded() == null
                      ? new Texture(new com.badlogic.gdx.files.FileHandle(image.file()), true)
                      : new Texture(new Encoded(image.encoded()));
                texture.setFilter(filter(image.minFilter()), filter(image.magFilter()));
                texture.setWrap(wrap(image.wrapS()), wrap(image.wrapT()));
                return texture;
            });
        };
        // Decode first, so an invalid image cannot strand meshes inside a partially constructed Model.
        for (var material : data.materials) {
            if (material.textures == null) { continue; }
            for (var texture : material.textures) { provider.load(texture.fileName); }
        }
        Model model = new Model(data, provider);
        if (data instanceof RigidGlb.Data glb) {
            glb.alphaTests.forEach((id, cutoff) -> model.getMaterial(id).set(
                  new BlendingAttribute(false, GL20.GL_ONE, GL20.GL_ZERO, 1),
                  new FloatAttribute(FloatAttribute.AlphaTest, cutoff)));
        }
        var owned = model.getManagedDisposables().iterator();
        while (owned.hasNext()) {
            if (owned.next() instanceof Texture) { owned.remove(); }
        }
        return model;
    }

    private static Texture.TextureFilter filter(int value) {
        for (var filter : Texture.TextureFilter.values()) {
            if (filter.getGLEnum() == value) { return filter; }
        }
        throw new IllegalArgumentException("Unknown texture filter: " + value);
    }

    private static Texture.TextureWrap wrap(int value) {
        for (var wrap : Texture.TextureWrap.values()) {
            if (wrap.getGLEnum() == value) { return wrap; }
        }
        throw new IllegalArgumentException("Unknown texture wrap: " + value);
    }

    /** Decode on preparation, release native pixels after upload, retain encoded bytes for context restoration. */
    private static final class Encoded implements TextureData {
        private final byte[] bytes;
        private Pixmap pixels;
        private int width;
        private int height;
        private Pixmap.Format format;

        Encoded(byte[] bytes) { this.bytes = bytes; }
        @Override public TextureDataType getType() { return TextureDataType.Pixmap; }
        @Override public boolean isPrepared() { return pixels != null; }
        @Override public void prepare() {
            if (pixels != null) { throw new IllegalStateException("Texture is already prepared"); }
            pixels = new Pixmap(bytes, 0, bytes.length);
            width = pixels.getWidth();
            height = pixels.getHeight();
            format = pixels.getFormat();
        }
        @Override public Pixmap consumePixmap() {
            if (pixels == null) { throw new IllegalStateException("Texture is not prepared"); }
            Pixmap result = pixels;
            pixels = null;
            return result;
        }
        @Override public boolean disposePixmap() { return true; }
        @Override public void consumeCustomData(int target) { throw new UnsupportedOperationException("Pixmap texture"); }
        @Override public int getWidth() { return width; }
        @Override public int getHeight() { return height; }
        @Override public Pixmap.Format getFormat() { return format; }
        @Override public boolean useMipMaps() { return true; }
        @Override public boolean isManaged() { return true; }
    }
}
