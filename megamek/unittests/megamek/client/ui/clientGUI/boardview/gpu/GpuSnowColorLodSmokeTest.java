/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.shaders.BaseShader;
import com.badlogic.gdx.graphics.g3d.utils.BaseShaderProvider;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Fixed-footprint prelight A/B: normal-detail changes must not repaint authored snow. */
@Tag("on-demand")
class GpuSnowColorLodSmokeTest {
    @Test
    void snowPigmentRetainsItsAuthoredColorAcrossNormalDetail() throws Exception {
        String baselinePath = System.getProperty("megamek.gpu.snowBaseline");
        assumeTrue(baselinePath != null, "This paired review needs the saved predecessor terrain-sculpt.frag");
        String baseline = Files.readString(Path.of(baselinePath));
        String candidate = GpuShaderSource.readDisk("terrain-sculpt.frag");
        assertTrue(!baseline.equals(candidate), "Compare the saved predecessor with the actual candidate");
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(640, 480);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try {
                    manager.run(() -> {
                        try { review(manager, baseline, candidate); }
                        catch (Throwable error) { failure.set(error); }
                    });
                } finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Snow prelight LOD review", failure.get()); }
    }

    private static void review(GpuShaderManager manager, String baseline, String candidate) throws Exception {
        var terrain = new GpuTerrain();
        try (var output = new GpuSceneTestOutput()) {
            var camera = new OrthographicCamera();
            camera.position.set(BoardGeometry.center(new Coords(3, 3), 0)).add(0, 0, 5000);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            camera.near = 1;
            camera.far = 10000;
            camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(.1f);
            camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(.1f);
            camera.update();
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            for (String scene : List.of("snow", "mixed", "dirt")) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene(scene));
                output.draw(terrain, camera, false);
                var reference = new ArrayList<float[][]>();
                for (int version = 0; version < 2; version++) {
                    var changed = manager.apply(Map.of("terrain-sculpt.frag", probe(version == 0 ? baseline : candidate)));
                    assertTrue(changed.success(), changed.message());
                    output.draw(terrain, camera, false); // Material variants exist before setting test uniforms.
                    for (int level = 0; level < 3; level++) {
                        float detail = 1 - level * .5f;
                        float[][] channels = new float[5][];
                        for (int mode = 0; mode < channels.length; mode++) {
                            setProbe(terrain, detail, mode);
                            output.draw(terrain, camera, false);
                            channels[mode] = output.linearPixels();
                        }
                        if (version == 0) { reference.add(channels); }
                        else { compare(scene, detail, reference.get(level), channels); }
                    }
                }
            }
            assertTrue(manager.apply(Map.of()).success(), "Restore production sources");
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); }
    }

    private static void compare(String scene, float detail, float[][] before, float[][] after) {
        int covered = 0, snow = 0, mixed = 0, previousOverlay = 0, blue = 0, retainedOtherWear = 0, mixedWear = 0;
        double changedPigment = 0;
        for (int pixel = 0; pixel < after[0].length; pixel += 4) {
            for (int mode = 0; mode < after.length; mode++) {
                for (int channel = 0; channel < 4; channel++) {
                    assertTrue(Float.isFinite(after[mode][pixel + channel]) && Float.isFinite(before[mode][pixel + channel]));
                }
                assertEquals(before[mode][pixel + 3], after[mode][pixel + 3], 0, "Same visible geometry");
            }
            if (after[0][pixel + 3] != .25f) { continue; }
            covered++;
            // Surface/roughness, normal and palette selection are independent of the color correction.
            for (int mode = 1; mode <= 3; mode++) {
                for (int channel = 0; channel < 3; channel++) {
                    equalHalf(before[mode][pixel + channel], after[mode][pixel + channel], "Unchanged material channel");
                }
            }
            float weight = after[3][pixel];
            if (weight > .999f) {
                snow++;
                for (int channel = 0; channel < 3; channel++) {
                    equalHalf(1, after[4][pixel + channel], "Snow has no additional microcolor multiplier");
                }
                if (Math.abs(before[4][pixel] - 1) > .002f) { previousOverlay++; }
                if (after[0][pixel + 2] > after[0][pixel] + .002f) { blue++; }
            } else if (weight > .001f) {
                mixed++;
                if (weight > .1f && weight < .9f && Math.abs(after[4][pixel] - 1) > .002f) { mixedWear++; }
            }
            if (weight < .001f) {
                for (int channel = 0; channel < 3; channel++) {
                    equalHalf(before[0][pixel + channel], after[0][pixel + channel], "Non-snow pigment unchanged");
                }
                if (Math.abs(after[4][pixel] - 1) > .002f) { retainedOtherWear++; }
            }
            changedPigment += Math.abs(before[0][pixel] - after[0][pixel]);
        }
        assertTrue(covered > 1000, "Substantial actual ground coverage");
        if (scene.equals("snow")) {
            assertTrue(snow > 1000 && blue > 100, "Authored cool snow must remain visible");
            if (detail == 1) { assertTrue(previousOverlay > 100, "The baseline must exercise the removed overlay"); }
        }
        if (scene.equals("mixed")) {
            assertTrue(mixed > 100, "Exercise actual snow/non-snow blending");
            if (detail == 1) { assertTrue(mixedWear > 20, "Mixed pixels retain non-snow microcolor"); }
        }
        if (scene.equals("dirt") && detail == 1) { assertTrue(retainedOtherWear > 100, "Other material weathering remains"); }
        System.out.println("Snow color LOD " + scene + " detail=" + detail + " covered=" + covered + " snow=" + snow
              + " mixed=" + mixed + " cool=" + blue + " previousOverlay=" + previousOverlay
              + " otherWear=" + retainedOtherWear + " mixedWear=" + mixedWear + " meanRedChange=" + changedPigment / covered);
    }

    private static void equalHalf(float expected, float actual, String message) {
        float step = Math.max(0x1p-19f, Math.scalb(1f, Math.getExponent(expected) - 10));
        assertEquals(expected, actual, step * 1.01f, message);
    }

    private static String probe(String source) {
        source = replace(source, "void main() {", "uniform vec2 u_snowReview;\nvoid main() {");
        source = replace(source, "terrainMaterialLod(world, face);",
              "terrainMaterialLod(world, face); terrainNormalDetail = u_snowReview.x;");
        source = replace(source, "if (weathered > 0.0 && volcanic < .01) {",
              "vec3 reviewInput = albedo, reviewGain = vec3(1.0);\nif (weathered > 0.0 && volcanic < .01) {");
        source = replace(source, "groundDamage(world, face, grass, sand, exposed, albedo, normal, cavity, finish);",
              "reviewGain = albedo / max(reviewInput, vec3(.000001));\n"
                    + "groundDamage(world, face, grass, sand, exposed, albedo, normal, cavity, finish);");
        source = replace(source, "albedo = materialReflectance(albedo);",
              "albedo = materialReflectance(albedo); vec3 reviewPigment = albedo;");
        return replace(source, "fragColor = vec4(result, 1.0);", """
              float reviewSnow;
              #ifdef terrainBlendFlag
              reviewSnow = dot(materialCoverage, 1.0 - step(vec4(.5), abs(u_coverFamilies - 5.0)));
              #else
              reviewSnow = family(5.0) ? 1.0 : 0.0;
              #endif
              vec3 review = reviewPigment;
              if (u_snowReview.y > .5) review = vec3(roughness, cavity, materialHeight);
              if (u_snowReview.y > 1.5) review = normal * .5 + .5;
              if (u_snowReview.y > 2.5) review = vec3(reviewSnow, 0.0, 0.0);
              if (u_snowReview.y > 3.5) review = reviewGain;
              fragColor = vec4(review, ground ? .25 : 1.0);
              """);
    }

    private static String replace(String source, String anchor, String replacement) {
        assertEquals(1, source.split(java.util.regex.Pattern.quote(anchor), -1).length - 1, "Unique probe boundary");
        return source.replace(anchor, replacement);
    }

    private static void setProbe(GpuTerrain terrain, float detail, float mode) throws Exception {
        var batch = GpuTerrain.class.getDeclaredField("batch");
        batch.setAccessible(true);
        var provider = ((ModelBatch) batch.get(terrain)).getShaderProvider();
        if (provider instanceof GpuShaderProvider editable) { provider = editable.active(); }
        var shaders = BaseShaderProvider.class.getDeclaredField("shaders");
        shaders.setAccessible(true);
        int bound = 0;
        for (Object shader : (Iterable<?>) shaders.get(provider)) {
            var program = ((BaseShader) shader).program;
            int location = program.getUniformLocation("u_snowReview");
            if (location < 0) { continue; }
            program.bind(); program.setUniformf(location, detail, mode); bound++;
        }
        assertTrue(bound > 0, "Actual terrain programs receive the test-only uniform");
    }

    private static BoardScene scene(String name) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var material = name.equals("snow") || (name.equals("mixed") && x < 3)
                      ? BoardScene.Surface.SNOW : BoardScene.Surface.DIRT;
                tiles.add(new BoardScene.Tile(new Coords(x, y), 0, -1, false, 0, material, null, null, List.of(),
                      List.of(), BoardLiquid.NONE, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
