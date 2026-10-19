/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;

/** Material and vertex RGB are already linear, independent of the material's lighting model. */
final class GpuLinearColors extends Attribute {
    static final long TYPE = register("boardLinearColors");

    GpuLinearColors() { super(TYPE); }

    /** An authored UI/palette color entering a material with this color contract. Alpha remains coverage. */
    static Color authored(Attributes material, Color color) {
        return material.has(TYPE) ? GpuColor.linear(color) : color;
    }

    @Override
    public Attribute copy() { return new GpuLinearColors(); }

    @Override
    public int compareTo(Attribute other) { return Long.compare(type, other.type); }
}
