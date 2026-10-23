/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.BlendingAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.DepthTestAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.FloatAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import megamek.common.annotations.Nullable;

/**
 * A translucent copy of a shown unit model: the plotted route's ghost and a held aircraft's destination. The copy
 * shares the shown model's meshes and textures and owns only copies of its materials, so it costs one draw per
 * material and no GL object of its own. It is made again only when the shown instance or its build changes.
 */
final class GpuGhostModel {

    /**
     * How a ghost looks.
     *
     * @param colour     its lit colour
     * @param glow       the share of that colour that glows whatever the model's own colours
     * @param opacity    its opacity at rest
     * @param seeThrough whether it shows through scenery; otherwise it is depth-tested against the scene. A ghost never
     *                   writes depth.
     */
    record Look(Color colour, Color glow, float opacity, boolean seeThrough) { }

    private final Look look;
    private ModelInstance copy;
    private ModelInstance source;
    private long build;
    /** Each copied material's opacity at rest, which a caller may raise for a moment. */
    private float[] restOpacity = new float[0];

    GpuGhostModel(Look look) {
        this.look = look;
    }

    /**
     * @param shown the instance the view draws for the unit
     * @param build the unit models' build, which changes when their artwork reloads
     *
     * @return the ghost of {@code shown} as it looks at {@code build}; its transform is the caller's to set
     */
    ModelInstance copyOf(ModelInstance shown, long build) {
        if ((copy == null) || (source != shown) || (this.build != build)) {
            copy = new ModelInstance(shown);
            restOpacity = new float[copy.materials.size];
            for (int index = 0; index < copy.materials.size; index++) {
                Material material = copy.materials.get(index);
                apply(material, look);
                restOpacity[index] = ((BlendingAttribute) material.get(BlendingAttribute.Type)).opacity;
            }
            source = shown;
            this.build = build;
        }
        return copy;
    }

    /** @return the ghost last made, or {@code null} before the first */
    @Nullable ModelInstance current() {
        return copy;
    }

    /** @return a copied material's opacity at rest */
    float restOpacity(int materialIndex) {
        return restOpacity[materialIndex];
    }

    /** Forgets the copy; it owns no GL object, its meshes and textures are the shown model's. */
    void forget() {
        copy = null;
        source = null;
    }

    /**
     * Turns a copied material into a ghost's: a lit, translucent colour that keeps only a cut-out (alpha-tested)
     * texture, so that a flat sprite keeps its outline.
     */
    static void apply(Material material, Look look) {
        Attribute alphaTest = material.get(FloatAttribute.AlphaTest);
        Attribute cutOut = (alphaTest == null) ? null : material.get(TextureAttribute.Diffuse);
        Attribute cullFace = material.get(IntAttribute.CullFace);
        Attribute linearColors = material.get(GpuLinearColors.TYPE);
        material.clear();
        if (linearColors != null) {
            material.set(linearColors);
        }
        material.set(ColorAttribute.createDiffuse(GpuLinearColors.authored(material, look.colour())),
              ColorAttribute.createEmissive(GpuLinearColors.authored(material, look.glow())),
              new BlendingAttribute(look.opacity()),
              new DepthTestAttribute(look.seeThrough() ? GL20.GL_ALWAYS : GL20.GL_LEQUAL, false));
        if (cutOut != null) {
            material.set(alphaTest, cutOut);
        }
        if (cullFace != null) {
            material.set(cullFace);
        }
    }
}
