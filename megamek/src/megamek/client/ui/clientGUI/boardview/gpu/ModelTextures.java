/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.HashSet;
import java.util.Map;
import java.util.function.BiFunction;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.model.data.ModelData;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;

/** Models borrow textures from their library's cache; each cache alone owns their disposal. */
final class ModelTextures {
    private ModelTextures() { }

    static Model create(ModelData data, Map<String, Texture> cache, BiFunction<String, Boolean, Texture> external) {
        BiFunction<String, Boolean, Texture> load = (key, color) -> {
            var image = data instanceof RigidGlb.Data glb ? glb.images.get(key) : null;
            if (image == null) { return external.apply(key, color); }
            return cache.computeIfAbsent((color ? "color:" : "data:") + key, ignored -> {
                Texture texture;
                if (image.encoded() == null) {
                    var file = new com.badlogic.gdx.files.FileHandle(image.file());
                    texture = color ? GpuColorTexture.create(file, true) : GpuColorTexture.createData(file, true);
                } else if (color) {
                    texture = GpuColorTexture.create(image.encoded(), true);
                } else {
                    texture = GpuColorTexture.createData(image.encoded(), true);
                }
                texture.setFilter(filter(image.minFilter()), filter(image.magFilter()));
                texture.setWrap(wrap(image.wrapS()), wrap(image.wrapT()));
                return texture;
            });
        };
        var colors = new HashSet<String>();
        // Decode first, so an invalid image cannot strand meshes inside a partially constructed Model.
        for (var material : data.materials) {
            if (material.textures == null) { continue; }
            for (var texture : material.textures) {
                boolean color = color(texture.usage);
                if (color) { colors.add(texture.fileName); }
                load.apply(texture.fileName, color);
            }
        }
        if (data instanceof RigidGlb.Data glb) {
            glb.surfaces.values().stream().map(RigidGlb.Surface::map).filter(java.util.Objects::nonNull)
                  .forEach(key -> load.apply(key, false));
        }
        Model model = new Model(data, key -> load.apply(key, colors.contains(key)));
        // glTF permits one source image in both a color and a data role. Each role needs its own GPU storage.
        for (var source : data.materials) {
            if (source.textures == null) { continue; }
            var material = model.getMaterial(source.id);
            for (var texture : source.textures) {
                long type = switch (texture.usage) {
                    case ModelTexture.USAGE_DIFFUSE -> TextureAttribute.Diffuse;
                    case ModelTexture.USAGE_EMISSIVE -> TextureAttribute.Emissive;
                    case ModelTexture.USAGE_SPECULAR -> TextureAttribute.Specular;
                    case ModelTexture.USAGE_NORMAL -> TextureAttribute.Normal;
                    case ModelTexture.USAGE_AMBIENT -> TextureAttribute.Ambient;
                    case ModelTexture.USAGE_BUMP -> TextureAttribute.Bump;
                    case ModelTexture.USAGE_REFLECTION -> TextureAttribute.Reflection;
                    default -> 0;
                };
                var attribute = material.get(TextureAttribute.class, type);
                if (attribute != null) { attribute.textureDescription.texture = load.apply(texture.fileName, color(texture.usage)); }
            }
        }
        if (data instanceof RigidGlb.Data glb) {
            model.materials.forEach(material -> material.set(new GpuLinearColors()));
            glb.surfaces.forEach((id, surface) -> model.getMaterial(id).set(new GpuModelMaterial(
                  surface.roughness(), surface.metallic(), surface.map() == null ? null : load.apply(surface.map(), false))));
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

    private static boolean color(int usage) {
        return usage == ModelTexture.USAGE_DIFFUSE || usage == ModelTexture.USAGE_EMISSIVE
              || usage == ModelTexture.USAGE_SPECULAR || usage == ModelTexture.USAGE_REFLECTION;
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

}
