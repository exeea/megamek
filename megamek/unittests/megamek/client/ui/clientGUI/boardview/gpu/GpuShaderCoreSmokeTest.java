/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.VertexAttributes.Usage;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.Renderable;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.shaders.DepthShader;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.GdxRuntimeException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compile exported programs with no engine prefixes, including upstream lighting and depth integration. */
@Tag("on-demand")
class GpuShaderCoreSmokeTest {
    @TempDir
    Path directory;

    @Test
    void exportedProgramsCompileWithoutEnginePrefixesAndPreserveLibGdxDefaults() {
        var failure = new AtomicReference<Throwable>();
        String previousExport = System.getProperty(GpuGlsl.EXPORT_PROPERTY);
        System.setProperty(GpuGlsl.EXPORT_PROPERTY, directory.toString());
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try { checkPrograms(); }
                    catch (Throwable error) { failure.set(error); }
                    finally { Gdx.app.exit(); }
                }
            }, GpuBoardWindow.configuration(false));
        } finally {
            if (previousExport == null) { System.clearProperty(GpuGlsl.EXPORT_PROPERTY); }
            else { System.setProperty(GpuGlsl.EXPORT_PROPERTY, previousExport); }
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }

    private void checkPrograms() throws Exception {
        String vertexPrefix = ShaderProgram.prependVertexCode, fragmentPrefix = ShaderProgram.prependFragmentCode;
        for (String fragment : List.of("particles", "beams", "projectiles", "water-splash")) {
            compile("effects", fragment);
        }
        for (String pair : List.of("explosion", "missile", "terrain-effects", "hex-mask")) {
            compile(pair, pair);
        }
        for (var kind : GpuWeatherParticles.Kind.values()) { GpuWeatherParticles.shader(kind).dispose(); }
        for (String fragment : List.of("ocean-initial", "ocean-spectrum", "ocean-fft", "ocean-foam", "ocean-water-finish",
              "ocean-lava-finish")) {
            GpuOcean.program(fragment + ".frag").dispose();
        }
        for (String fragment : List.of("atmosphere-composite", "atmosphere-fog", "cloud-transmission",
              "terrain-effects-composite", "unit-visibility")) {
            GpuAtmosphere.shader(fragment + ".frag").dispose();
        }
        var model = new ModelBuilder().createBox(1, 1, 1,
              new Material(ColorAttribute.createDiffuse(1, 1, 1, 1)), Usage.Position | Usage.Normal);
        var units = GpuUnitShader.provider();
        var depth = GpuTreeInstances.depthProvider(new DepthShader.Config(null, GpuShaderSource.read("shadow-depth.frag")));
        try {
            Renderable part = new ModelInstance(model).getRenderable(new Renderable());
            part.environment = new Environment();
            part.environment.set(new ColorAttribute(ColorAttribute.AmbientLight, .2f, .2f, .2f, 1));
            part.environment.add(new DirectionalLight().set(1, 1, 1, 0, 0, -1));
            units.getShader(part);
            depth.getShader(part);
        } finally {
            units.dispose(); depth.dispose(); model.dispose();
        }
        assertEquals(vertexPrefix, ShaderProgram.prependVertexCode);
        assertEquals(fragmentPrefix, ShaderProgram.prependFragmentCode);

        // An editor consumes exactly these files, without any ShaderProgram.prepend*Code state.
        var legacy = Pattern.compile("\\b(attribute|varying|texture2D|texture2DLod|textureCube|gl_FragColor)\\b");
        try (var paths = Files.list(directory)) {
            var vertices = paths.filter(path -> path.toString().endsWith(".vert")).toList();
            assertTrue(vertices.size() >= 19, "Every material/effect pair was exported");
            ShaderProgram.prependVertexCode = "";
            ShaderProgram.prependFragmentCode = "";
            for (Path path : vertices) {
                String vertex = Files.readString(path);
                String fragment = Files.readString(path.resolveSibling(path.getFileName().toString().replace(".vert", ".frag")));
                assertFalse(legacy.matcher(vertex + fragment).find(), path.toString());
                var program = new ShaderProgram(vertex, fragment);
                try { assertTrue(program.isCompiled(), path + ": " + program.getLog()); }
                finally { program.dispose(); }
            }
        } finally {
            ShaderProgram.prependVertexCode = vertexPrefix;
            ShaderProgram.prependFragmentCode = fragmentPrefix;
        }
        assertThrows(GdxRuntimeException.class, () -> GpuGlsl.compile("invalid editor change", "invalid", "invalid"));
        assertEquals(vertexPrefix, ShaderProgram.prependVertexCode);
        assertEquals(fragmentPrefix, ShaderProgram.prependFragmentCode);
        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
    }

    private static void compile(String vertex, String fragment) {
        GpuGlsl.compile(fragment, GpuShaderSource.read(vertex + ".vert"), GpuEffectBatch.fragment(fragment + ".frag")).dispose();
    }
}
