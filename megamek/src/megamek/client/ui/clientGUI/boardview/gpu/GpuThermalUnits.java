/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.graphics.g3d.Attributes;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.Shader;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import com.badlogic.gdx.graphics.g3d.utils.DefaultShaderProvider;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Disposable;

/** Wireframe's unit pass. Borrows the current posed instances; never changes their materials or mesh buffers. */
final class GpuThermalUnits implements Disposable {
    /** Render-thread snapshot for this frame only, including the same bounds used for culling. */
    private record Sample(float intensity, float bottom, float inverseHeight, float centerX, float centerY,
          float inverseWidth, float inverseDepth) { }
    private record Draw(ModelInstance instance, Sample sample) { }

    private final List<Draw> draws = new ArrayList<>();
    private ModelBatch batch;

    void add(ModelInstance instance, BoardScene.Unit unit, BoundingBox bounds) {
        // The heat scale is a display range, not a second heat rule. Non-tracking units have a steady warm signature.
        int heat = unit.heat();
        float intensity = heat < 0 ? .58f : .12f + .88f * Math.clamp(heat / 30f, 0, 1);
        // A formation's horizontal bounds span multiple bodies: never heat only the figures near its center.
        float horizontal = unit.model() != null && unit.model().figures() > 1 ? 0 : 2;
        var sample = new Sample(intensity, bounds.min.z, bounds.getDepth() > .001f ? 1 / bounds.getDepth() : 0,
              bounds.getCenterX(), bounds.getCenterY(), horizontal / Math.max(.001f, bounds.getWidth()),
              horizontal / Math.max(.001f, bounds.getHeight()));
        draws.add(new Draw(instance, sample));
    }

    void render(Camera camera) {
        if (draws.isEmpty()) { return; }
        if (batch == null) {
            batch = new ModelBatch(GpuShaderManager.provider("Thermal units", GpuThermalUnits::provider), new GpuOpaqueSorter());
        }
        try {
            batch.begin(camera);
            for (Draw draw : draws) {
                Object previous = draw.instance().userData;
                try {
                    // ModelBatch captures this reference in each renderable. Restore the instance immediately so
                    // the existing team-color/own-hex outline pass keeps its own data, even when shader creation fails.
                    draw.instance().userData = draw.sample();
                    batch.render(draw.instance());
                } finally {
                    draw.instance().userData = previous;
                }
            }
            batch.end();
        } finally {
            draws.clear();
        }
    }

    private static DefaultShaderProvider provider() {
        return new DefaultShaderProvider(vertexSource(DefaultShader.getDefaultVertexShader()), GpuShaderSource.read("unit-thermal.frag")) {
            @Override
            protected Shader createShader(Renderable renderable) {
                return new DefaultShader(renderable, config, GpuGlsl.compile("GPU thermal unit",
                      DefaultShader.createPrefix(renderable, config), config.vertexShader, config.fragmentShader)) {
                    private final int thermal = register("u_thermal");
                    private final int shape = register("u_thermalShape");

                    @Override
                    public void render(Renderable part, Attributes attributes) {
                        Sample sample = (Sample) part.userData;
                        set(thermal, sample.intensity(), sample.bottom(), sample.inverseHeight());
                        set(shape, sample.centerX(), sample.centerY(), sample.inverseWidth(), sample.inverseDepth());
                        super.render(part, attributes);
                    }
                };
            }
        };
    }

    /** libGDX still owns transforms, skinning, normals and sprite UVs, exactly as for the shaded model. */
    static String vertexSource(String source) {
        source = GpuUnitShader.replaceOnce(GpuGlsl.libGdx(source, true), "void main() {",
              "out vec3 v_thermalPosition;\nvoid main() {", "vertex");
        return GpuUnitShader.replaceOnce(source, "gl_Position = u_projViewTrans * pos;",
              "v_thermalPosition = pos.xyz;\n    gl_Position = u_projViewTrans * pos;", "vertex");
    }

    @Override
    public void dispose() {
        draws.clear();
        if (batch != null) { batch.dispose(); batch = null; }
    }
}
