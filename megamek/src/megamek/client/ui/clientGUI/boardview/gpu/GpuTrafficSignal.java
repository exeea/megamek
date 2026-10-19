/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.function.DoubleSupplier;

import com.badlogic.gdx.graphics.g3d.Attribute;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;

/** An out-of-service signal's amber lens. Its immutable marker survives copied and instanced material caches. */
final class GpuTrafficSignal extends Attribute {
    static final long TYPE = register("boardTrafficSignal");

    private GpuTrafficSignal() { super(TYPE); }

    /** Called on a placement's own materials, never on the shared GLB model. */
    static void mark(String asset, Material material) {
        if ((asset.equals("scenery/road/traffic-signal") || asset.equals("scenery/road/traffic-signal-gantry"))
              && material.id.equals("Amber")) {
            material.set(new GpuTrafficSignal());
        }
    }

    /** One second per cycle, half on and half off; every view reads its terrain's existing animation clock. */
    static float pulse(double seconds) { return seconds % 1 < .5 ? 1 : 0; }

    static void register(DefaultShader shader, DoubleSupplier clock) {
        shader.register("u_trafficSignalPulse", new BaseShader.LocalSetter() {
            @Override
            public void set(BaseShader target, int id, Renderable renderable, Attributes attributes) {
                // Zero is neutral, including model renderers that do not register this terrain-only uniform.
                target.set(id, attributes.has(TYPE) ? 1 + pulse(clock.getAsDouble()) : 0f);
            }
        });
    }

    @Override
    public Attribute copy() { return new GpuTrafficSignal(); }

    @Override
    public int compareTo(Attribute other) { return Long.compare(type, other.type); }
}
