/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.shaders.DefaultShader;
import megamek.common.Configuration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Real file edits and fallback order, independent of the launch directory or an OpenGL context. */
class GpuShaderReloadTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("shaderFiles")
    void rereadsOverridesAndCheckoutIncludesWithBundledFallback(String root, String name) throws Exception {
        File data = Configuration.dataDir();
        var previousFiles = Gdx.files;
        Configuration.setDataDir(directory.resolve("data").toFile());
        Gdx.files = spy(new Lwjgl3Files());
        doAnswer(call -> new FileHandle(directory.resolve((String) call.getArgument(0)).toFile()))
              .when(Gdx.files).local(anyString());
        try {
            String resource = "megamek/client/ui/clientGUI/boardview/gpu/" + name;
            String bundled = Gdx.files.classpath(resource).readString("UTF-8");
            assertEquals(bundled, GpuShaderSource.read(name));

            Path checkout = directory.resolve(root).resolve(resource);
            Files.createDirectories(checkout.getParent());
            Files.writeString(checkout, bundled + "\n// checkout edit\n");
            assertTrue(compose(name).contains("// checkout edit"));

            Path override = directory.resolve("data/shaders").resolve(name);
            Files.createDirectories(override.getParent());
            Files.writeString(override, bundled + "\n// first override\n");
            assertTrue(compose(name).contains("// first override"));
            Files.writeString(override, bundled + "\n// second override\n");
            assertTrue(compose(name).contains("// second override"), "Re-read the same filename without restarting");

            Files.delete(override);
            assertTrue(compose(name).contains("// checkout edit"));
            Files.delete(checkout);
            assertEquals(bundled, GpuShaderSource.read(name));

            var session = new GpuShaderManager();
            try {
                session.run(() -> {
                    assertTrue(session.capture(() -> compose(name)).files().contains(name), "Track reload dependencies");
                    assertTrue(session.apply(Map.of(name, bundled + "\n// live draft\n")).success());
                    assertTrue(compose(name).contains("// live draft"), "Composition must read the current editor draft");
                });
            } finally { session.close(); }
        } finally {
            Configuration.setDataDir(data);
            Gdx.files = previousFiles;
        }
    }

    private static Stream<Arguments> shaderFiles() {
        return Stream.of("resources", "megamek/resources").flatMap(root -> Stream.of("light-model.glsl",
              "tree-instances.glsl", "water-spray.glsl", "cloud-lighting.glsl", "cloud-surface.glsl",
              "linear-ambient.glsl", "linear-material.glsl", "linear-output.glsl", "road-mask.vert", "terrain-blend.vert",
              "ground-surface.glsl", "water-uniforms.glsl", "water-lighting.glsl", "water-pool.glsl", "water-interactions.glsl",
              "terrain-road.frag", "water-fall.frag", "water-spray.frag", "water-cut.frag",
              "weather-particles.vert", "weather-particles.frag", "weather-rain.glsl", "weather-snow.glsl",
              "weather-hail.glsl", "weather-sand.glsl", "particles-smoke.glsl", "particles-fire.glsl", "particles-jet.glsl",
              "terrain-projection.glsl", "terrain-concrete.glsl", "atmosphere-fov.glsl", "atmosphere-grade.glsl",
              "atmosphere-glare.glsl", "terrain-magma.glsl", "magma-solid.glsl", "magma-flow.glsl", "magma-lighting.glsl",
              "terrain-magma-solid.frag", "terrain-magma-flow.frag", "ocean-finish.glsl",
              "ocean-water-finish.frag", "ocean-lava-finish.frag")
              .map(name -> Arguments.of(root, name)));
    }

    private static String compose(String name) {
        return switch (name) {
            case "tree-instances.glsl" -> GpuTreeInstances.vertex(unitVertex());
            case "water-spray.glsl" -> GpuWaterfall.vertex(unitVertex());
            case "road-mask.vert" -> GpuRoads.vertex(unitVertex());
            case "terrain-blend.vert" -> GpuSurfaceBlend.vertex(unitVertex());
            case "cloud-lighting.glsl" -> GpuCloudShadow.fragment(
                  GpuUnitShader.linearFragment(DefaultShader.getDefaultFragmentShader()), false);
            case "cloud-surface.glsl", "ground-surface.glsl" -> GpuTerrain.litFragment("terrain-normal.frag");
            case "water-uniforms.glsl", "water-lighting.glsl", "water-pool.glsl", "water-interactions.glsl" ->
                  GpuTerrain.litFragment("water-surface.frag");
            case "terrain-road.frag", "water-fall.frag", "water-spray.frag", "water-cut.frag" -> GpuTerrain.litFragment(name);
            case "weather-particles.vert", "weather-particles.frag" -> GpuWeatherParticles.source(name, "weather-rain.glsl");
            case "weather-rain.glsl", "weather-snow.glsl", "weather-hail.glsl" ->
                  GpuWeatherParticles.source("weather-particles.vert", name) + GpuWeatherParticles.source("weather-particles.frag", name);
            case "weather-sand.glsl", "atmosphere-fov.glsl", "atmosphere-grade.glsl", "atmosphere-glare.glsl" ->
                  GpuAtmosphere.fragment("atmosphere-composite.frag");
            case "particles-smoke.glsl", "particles-fire.glsl", "particles-jet.glsl" -> GpuEffectBatch.fragment("particles.frag");
            case "terrain-projection.glsl", "terrain-concrete.glsl" -> GpuTerrain.litFragment("terrain-sculpt.frag");
            case "terrain-magma.glsl", "magma-solid.glsl" -> GpuTerrain.litFragment("terrain-sculpt.frag")
                  + GpuTerrain.litFragment("terrain-magma-solid.frag");
            case "magma-flow.glsl", "magma-lighting.glsl" -> GpuTerrain.litFragment("terrain-magma-flow.frag");
            case "terrain-magma-solid.frag", "terrain-magma-flow.frag" -> GpuTerrain.litFragment(name);
            case "ocean-finish.glsl" -> GpuOcean.fragment("ocean-water-finish.frag") + GpuOcean.fragment("ocean-lava-finish.frag");
            case "ocean-water-finish.frag", "ocean-lava-finish.frag" -> GpuOcean.fragment(name);
            case "linear-material.glsl", "linear-output.glsl" ->
                  GpuUnitShader.linearFragment(DefaultShader.getDefaultFragmentShader());
            default -> unitVertex();
        };
    }

    private static String unitVertex() {
        return GpuUnitShader.linearVertex(DefaultShader.getDefaultVertexShader());
    }
}
