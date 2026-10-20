/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Mapped material response near the camera; distant rendering must become independent of fine detail maps. */
@Tag("on-demand")
class GpuMaterialLodSmokeTest {
    @Test
    void distantMaterialsIgnoreDetailMapsButKeepTheirColourAndHeat() {
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(960, 720);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var manager = new GpuShaderManager();
                try { manager.run(() -> checkMaterials(manager)); }
                catch (Throwable error) { failure.set(error); }
                finally { manager.close(); Gdx.app.exit(); }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Material LOD", failure.get()); }
    }

    @Test
    void authoredRoughnessFollowsProjectedAndMixedTerrainWithoutNormalDetail(
          @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        File previous = megamek.common.Configuration.dataDir();
        File images = megamek.common.Configuration.imagesDir(), fonts = megamek.common.Configuration.fontsDir();
        prepareRoughnessArtwork(previous.toPath().resolve("models/board"), directory.resolve("models/board"));
        var failure = new AtomicReference<Throwable>();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(600, 480);
        megamek.common.Configuration.setImagesDir(images);
        megamek.common.Configuration.setFontsDir(fonts);
        megamek.common.Configuration.setDataDir(directory.toFile());
        try {
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override public void create() {
                    var manager = new GpuShaderManager();
                    try { manager.run(() -> {
                        try { checkSpatialRoughness(manager); }
                        catch (Throwable error) { failure.set(error); }
                    }); }
                    catch (Throwable error) { failure.set(error); }
                    finally { try { manager.close(); } finally { Gdx.app.exit(); } }
                }
            }, config);
        } finally {
            megamek.common.Configuration.setDataDir(previous);
            megamek.common.Configuration.setImagesDir(images);
            megamek.common.Configuration.setFontsDir(fonts);
        }
        if (failure.get() != null) { throw new AssertionError("Projected authored roughness", failure.get()); }
    }

    /** Separate numerical stores carry identical bytes; the existing height path is the projection/blend oracle. */
    private static void prepareRoughnessArtwork(java.nio.file.Path source, java.nio.file.Path target) throws Exception {
        try (var paths = java.nio.file.Files.walk(source)) {
            for (var path : paths.toList()) {
                var copy = target.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) { java.nio.file.Files.createDirectories(copy); }
                else { java.nio.file.Files.copy(path, copy); }
            }
        }
        var sculpt = target.resolve("textures/terrain/surfaces");
        // Only these five ordinary roles appear in the scene; every factor is Godot's default 1, so each role's
        // roughness is its ORM map alone. Unrelated authored maps may have different resolutions.
        try (var files = java.nio.file.Files.list(sculpt)) {
            for (var file : files.filter(path -> path.toString().endsWith(".tres")).toList()) {
                String text = java.nio.file.Files.readString(file);
                java.nio.file.Files.writeString(file, text.replaceAll("(?m)^roughness = .*$", "roughness = 1.0"));
            }
        }
        var names = List.of("grass", "rock", "scree", "granite-contact", "soil-contact");
        for (int role = 0; role < names.size(); role++) {
            String name = names.get(role);
            assertTrue(java.nio.file.Files.exists(sculpt.resolve(name + ".tres")), "The actual grass/rock material role exists: " + name);
            var color = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            var height = new BufferedImage(512, 512, BufferedImage.TYPE_BYTE_GRAY);
            var shipped = javax.imageio.ImageIO.read(sculpt.resolve(name + "_orm.png").toFile());
            // The ORM map keeps its occlusion (R) and metallic (B); its roughness (G) becomes the varying review map.
            var orm = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
            // Distinct two-axis dyadic checker patterns. Every mip is binary until one averaging step
            // makes it uniformly 1/2; later levels average that same constant (no cumulative UNORM error).
            int xShift = 4 + role % 3, yShift = 4 + (role + 1) % 3;
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    int value = (((x >> xShift) ^ (y >> yShift) ^ (role / 3)) & 1) * 255;
                    color.setRGB(x, y, 0x789064);
                    height.getRaster().setSample(x, y, 0, value);
                    orm.setRGB(x, y, (shipped.getRGB(x, y) & 0xff00ff) | value << 8);
                }
            }
            assertTrue(javax.imageio.ImageIO.write(color, "png", sculpt.resolve(name + "_albedo.png").toFile()));
            assertTrue(javax.imageio.ImageIO.write(height, "png", sculpt.resolve(name + "_heightmap.png").toFile()));
            assertTrue(javax.imageio.ImageIO.write(orm, "png", sculpt.resolve(name + "_orm.png").toFile()));
            BoardMaterialFiles.loose(target.resolve("textures"), "terrain/surfaces/" + name, 512, 12 + role * 4);
        }
    }

    private static void checkSpatialRoughness(GpuShaderManager manager) throws Exception {
        String renderer = Gdx.gl.glGetString(GL20.GL_RENDERER);
        System.out.println("Spatial roughness renderer: " + renderer);
        String expectedRenderer = System.getProperty("megamek.gpu.expectedRenderer", "");
        assertTrue(renderer.contains(expectedRenderer), "Actual renderer must match " + expectedRenderer);
        String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String begin = "void main() {", boundary = "        if (ground || cliff || bedrock) {";
        String output = "fragColor = vec4(result, 1.0);";
        for (String anchor : List.of(begin, boundary, output)) {
            assertEquals(1, sculpt.split(java.util.regex.Pattern.quote(anchor), -1).length - 1,
                  "The probe must read the actual completed ordinary material: " + anchor);
        }
        sculpt = sculpt.replace(begin, begin + "\n    vec3 reviewBase = vec3(0.0);\n")
              .replace(boundary, "        reviewBase = vec3(roughness, materialHeight, grass);\n" + boundary)
              .replace(output, "fragColor = natural && (ground || cliff)"
                    + " ? vec4(reviewBase, ground ? .25 : .5) : vec4(0.0, 0.0, 0.0, 1.0);");
        var changed = manager.apply(Map.of("terrain-sculpt.frag", sculpt));
        assertTrue(changed.success(), changed.message());
        var terrain = new GpuTerrain();
        try (var surface = new GpuSceneTestOutput()) {
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            terrain.update(roughnessScene());
            var center = BoardGeometry.center(new Coords(3, 3), 1);
            var camera = new OrthographicCamera();
            camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(.09f);
            camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(.09f);
            camera.near = 1; camera.far = 3000;
            var report = new ArrayList<Map<String, Object>>();
            for (boolean angled : new boolean[] { false, true }) {
                camera.position.set(center).add(angled ? 500 : 0, angled ? -600 : 0, 800);
                camera.up.set(0, angled ? 0 : 1, angled ? 1 : 0);
                camera.lookAt(center); camera.update();
                float[] reference = null;
                for (boolean normals : new boolean[] { true, false, true }) {
                    terrain.setNormalMaps(normals);
                    render(surface, terrain, camera); // Complete first use before the exact frozen repeat.
                    float[] pixels = surface.linearPixels();
                    render(surface, terrain, camera);
                    assertArrayEquals(pixels, surface.linearPixels(), "Frozen provider output must repeat exactly");
                    int ground = 0, cliff = 0, mixed = 0;
                    float minimum = 1, maximum = 0, maxError = 0;
                    var levels = new java.util.HashSet<Integer>();
                    // Binary/half-valued dyadic mips: at most one R8 code, one half step for each output,
                    // plus float arithmetic. Convex sampling and the actual normalized role/family weights
                    // cannot amplify this bound. This is chosen from formats, never fitted to rendered error.
                    double tolerance = 1.0 / 255 + 2.0 / 1024 + .00001;
                    for (int i = 0; i < pixels.length; i += 4) {
                        for (int channel = 0; channel < 4; channel++) {
                            assertTrue(Float.isFinite(pixels[i + channel]), "Every diagnostic channel is finite");
                        }
                        if (reference != null) {
                            assertEquals(reference[i + 3], pixels[i + 3], 0, "Normal detail preserves every coverage tag");
                        }
                        if (pixels[i + 3] != .25f && pixels[i + 3] != .5f) { continue; }
                        if (pixels[i + 3] == .25f) { ground++; } else { cliff++; }
                        if (pixels[i + 2] > .05f && pixels[i + 2] < .95f) { mixed++; }
                        assertTrue(pixels[i] >= 0 && pixels[i] <= 1, "Raw authored roughness stays bounded");
                        assertEquals(pixels[i + 1], pixels[i], tolerance,
                              "Independent roughness array follows actual height projection/mix at pixel " + i / 4);
                        if (reference != null) {
                            for (int channel = 0; channel < 4; channel++) {
                                assertEquals(reference[i + channel], pixels[i + channel], 0,
                                      "Normal detail cannot change base roughness, height, mixture or coverage");
                            }
                        }
                        minimum = Math.min(minimum, pixels[i]); maximum = Math.max(maximum, pixels[i]);
                        maxError = Math.max(maxError, Math.abs(pixels[i] - pixels[i + 1]));
                        levels.add(Math.round(pixels[i] * 255));
                    }
                    assertTrue(ground > 100 && mixed > 100, "Actual ground and mixed grass/rock must be visible");
                    assertTrue(!angled || cliff > 100, "The angled frame must contain actual projected cliff samples");
                    assertTrue(maximum - minimum > .25 && levels.size() > 16,
                          "The authored map must remain spatial, not a scalar/average fallback");
                    report.add(Map.of("angled", angled, "normalMaps", normals, "ground", ground, "cliff", cliff,
                          "mixed", mixed, "min", minimum, "max", maximum, "levels", levels.size(), "maxError", maxError));
                    String destination = System.getProperty("megamek.gpu.screenshots");
                    if (destination != null && reference == null) {
                        File image = new File(destination, "spatial-roughness/" + (angled ? "angled" : "top") + ".png");
                        assertTrue(image.getParentFile().isDirectory() || image.getParentFile().mkdirs());
                        surface.display(); GpuReviewFrame.save(image);
                    }
                    if (reference == null) { reference = pixels; }
                }
            }
            System.out.println("Spatial roughness: " + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(report));
            String destination = System.getProperty("megamek.gpu.screenshots");
            if (destination != null) {
                var path = java.nio.file.Path.of(destination, "spatial-roughness", "samples.json");
                java.nio.file.Files.createDirectories(path.getParent());
                new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
            }
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally {
            try { terrain.dispose(); }
            finally { assertTrue(manager.apply(Map.of()).success(), "Restore production sources"); }
        }
    }

    private static BoardScene roughnessScene() {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        var mixed = new BoardSurfaceBlend.Cover(.5f, 0, 0, .5f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                var family = x < 3 ? BoardScene.Surface.GRASS : BoardScene.Surface.ROCK;
                tiles.add(new BoardScene.Tile(new Coords(x, y), x < 3 ? 3 : 0, -1, false, 0,
                      family, pixels, null, null, null, null, List.of(), List.of(), BoardLiquid.NONE, null, true,
                      BoardRoad.Kind.NONE, BoardFireSmoke.NONE, BoardScene.Biome.NONE, false, false, 0, false,
                      x == 2 || x == 3 ? mixed : BoardSurfaceBlend.solid(family)));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }

    private static void checkMaterials(GpuShaderManager manager) {
        var terrain = new GpuTerrain();
        try (var surface = new GpuSceneTestOutput()) {
            var camera = new OrthographicCamera();
            var center = BoardGeometry.center(new Coords(3, 3), 0);
            camera.position.set(center).add(0, 0, 5000);
            camera.direction.set(0, 0, -1);
            camera.up.set(0, 1, 0);
            camera.near = 1;
            camera.far = 10000;
            terrain.setAtmosphere(BoardAtmosphere.lighting(new BoardAtmosphere.Settings(13, 0, 0,
                  BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0)));
            var outcropFailures = new ArrayList<String>();
            for (String material : List.of("grass", "dirt", "sand", "rock", "concrete", "snow", "lunar", "fungus",
                  "desert", "mars", "volcano", "tropical", "crust", "lava")) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene(material));
                double closeDifference = 0;
                for (float footprint : new float[] { .1f, .8f, 2f }) {
                    camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(footprint);
                    camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(footprint);
                    camera.update();
                    assertTrue(manager.apply(Map.of()).success());
                    byte[] original = render(surface, terrain, camera);
                    if (Boolean.getBoolean("megamek.gpu.materialMigrationReview")) {
                        File output = new File(System.getProperty("megamek.gpu.screenshots"), "material-families");
                        assertTrue(output.isDirectory() || output.mkdirs());
                        GpuReviewFrame.save(new File(output, material + "-" + footprint + ".png"));
                    }
                    if (List.of("desert", "mars", "volcano", "dirt", "rock", "lunar").contains(material)
                          && footprint < 1f) {
                        checkPlateauOutcrops(surface, manager, terrain, camera, original, material, footprint, outcropFailures);
                    }
                    var changed = manager.apply(changedMaps(false));
                    assertTrue(changed.success(), changed.message());
                    byte[] altered = render(surface, terrain, camera);
                    String description = material + " at " + footprint + " m/pixel";
                    double difference = difference(original, altered);
                    System.out.println(description + ": detail-map difference " + difference);
                    if (footprint < .2f) {
                        closeDifference = difference;
                        // Molten emission dominates its reflected light; even a large normal change is subtle.
                        assertTrue(difference > .001, description + " still uses its normal map");
                        if (!material.equals("crust") && !material.equals("lava")) {
                            String lighting = GpuShaderSource.readDisk("surface-shading.glsl");
                            changed = manager.apply(Map.of("surface-shading.glsl", lighting.replace(
                                  "sheen = sun * surfaceReflectance(normal, light, view, roughness, f0) * multiscatter * s.cavity;",
                                  "sheen = vec3(0.0);")));
                            assertTrue(changed.success(), changed.message());
                            assertTrue(difference(original, render(surface, terrain, camera)) > .001,
                                  description + " reflects light even when dry");
                        }
                    } else if (footprint < 1f) {
                        // Small stone sides retain cliff detail after the surrounding flat top stops sampling.
                        assertTrue(difference < closeDifference * .1, description + " removes ground detail first");
                    } else {
                        assertArrayEquals(original, altered, description + " must not use its fine normals/AO");
                    }
                    if (footprint == 2f && (material.equals("crust") || material.equals("lava"))) {
                        changed = manager.apply(changedMaps(true));
                        assertTrue(changed.success(), changed.message());
                        assertArrayEquals(original, render(surface, terrain, camera), description + " skips surface maps too");
                    }
                }
            }
            // Both fungal skins need visible weathering, while retaining mauve lowlands and cyan raised crust.
            for (int elevation : new int[] { -3, 0, 3 }) {
                assertTrue(manager.apply(Map.of()).success());
                terrain.update(scene("fungus", elevation));
                camera.position.set(BoardGeometry.center(new Coords(3, 3), elevation)).add(0, 0, 5000);
                camera.viewportWidth = Gdx.graphics.getBackBufferWidth() * BoardRelief.metres(.4f);
                camera.viewportHeight = Gdx.graphics.getBackBufferHeight() * BoardRelief.metres(.4f);
                camera.update();
                byte[] original = render(surface, terrain, camera);
                String review = System.getProperty("megamek.gpu.screenshots");
                if (review != null) {
                    File output = new File(review, "fungal-elevations");
                    assertTrue(output.isDirectory() || output.mkdirs());
                    GpuReviewFrame.save(new File(output, "level-" + elevation + ".png"));
                }
                double weathered = visiblePatchFraction(surface, manager, terrain, camera, original, "fungus");
                System.out.println("fungus at level " + elevation + ": weathering on " + weathered + " of visible ground");
                if (weathered <= .05) { outcropFailures.add("Fungal weathering must remain visible at level " + elevation); }
                // Elevation selects a pigment, independent of the atmosphere's blue sky or direct-light color.
                // Read the completed material under neutral unit illumination while retaining its weathering.
                var paletteProbe = manager.apply(Map.of("terrain-sculpt.frag", albedoProbe()));
                assertTrue(paletteProbe.success(), paletteProbe.message());
                byte[] pigment = render(surface, terrain, camera);
                int mauve = 0, cyan = 0;
                for (int i = 0; i < pigment.length; i += 4) {
                    int red = pigment[i] & 255, green = pigment[i + 1] & 255;
                    if (red > green + 2) { mauve++; }
                    if (green > red + 2) { cyan++; }
                }
                assertTrue(elevation <= 0 ? mauve > cyan : cyan > mauve,
                      "Fungus must retain its elevation-specific pigment at level " + elevation
                            + ": mauve=" + mauve + ", cyan=" + cyan);
            }
            assertTrue(outcropFailures.isEmpty(), String.join("\n", outcropFailures));
            assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
        } finally { terrain.dispose(); }
    }

    /** Mineral caps must visibly break up a clear plateau at close and normal map zoom, with quiet ground left. */
    private static void checkPlateauOutcrops(GpuSceneTestOutput surface, GpuShaderManager manager, GpuTerrain terrain,
          OrthographicCamera camera, byte[] original, String family, float footprint, List<String> failures) {
        double visible = visiblePatchFraction(surface, manager, terrain, camera, original, family);
        String description = family + " at " + footprint + " m/pixel";
        System.out.println(description + ": outcrops on " + visible + " of visible ground");
        if (visible <= .05) { failures.add(description + " must retain visible bedrock patches (" + visible + ")"); }
        if (visible >= .55) { failures.add(description + " must retain broad areas of ground cover (" + visible + ")"); }
    }

    private static double visiblePatchFraction(GpuSceneTestOutput surface, GpuShaderManager manager, GpuTerrain terrain,
          OrthographicCamera camera, byte[] original, String family) {
        String material = GpuShaderSource.readDisk("terrain-materials.glsl");
        String coverOnly = material.replace("vec4 roles = vec4(cover,",
              "cover = 1.0; soil = 0.0; deposit = 0.0; dust = 0.0; vec4 roles = vec4(cover,");
        if (family.equals("rock")) {
            // Ground fines and exposed granite may have similar pigment. Read the final production role
            // weights instead of requiring an authored color gain, a light direction or a display contrast.
            // The third channel also verifies that the bound cover and bedrock use distinct map layers.
            String emission = "result.emission = vec3(0.0);";
            String roles = "result.emission = vec3(weights.w, weights.x, layers.z != layers.x ? 1.0 : 0.0);";
            assertTrue(material.contains(emission), "The probe reads completed natural-material roles");
            String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
            String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
            String output = "fragColor = vec4(result, 1.0);";
            assertTrue(sculpt.contains(result) && sculpt.contains(output), "The role probe uses the final scene writer");
            // Ground rock is opaque. An exact alpha tag excludes boulders, cliffs, clear color and other props;
            // alpha is not scaled, while linearPixels restores the production RGB storage units once.
            sculpt = sculpt.replace(result, "vec3 result = emission;")
                  .replace(output, "fragColor = vec4(result, ground ? .25 : 1.0);");
            assertTrue(manager.apply(Map.of("terrain-sculpt.frag", sculpt,
                  "terrain-materials.glsl", material.replace(emission, roles))).success());
            render(surface, terrain, camera);
            float[] selected = linearPixels(surface);
            assertTrue(manager.apply(Map.of("terrain-sculpt.frag", sculpt,
                  "terrain-materials.glsl", coverOnly.replace(emission, roles))).success());
            render(surface, terrain, camera);
            float[] cover = linearPixels(surface);
            int ground = 0, patches = 0;
            for (int i = 0; i < selected.length; i += 4) {
                for (int channel = 0; channel < 4; channel++) {
                    assertTrue(Float.isFinite(selected[i + channel]) && Float.isFinite(cover[i + channel]),
                          "Material-role diagnostics must remain finite before coverage classification");
                }
                if (selected[i + 3] != .25f) { continue; }
                ground++;
                assertEquals(.25f, cover[i + 3], 0, "The control retains the same visible geometry");
                assertEquals(1f, selected[i + 2], .001f, "Cover and bedrock bind distinct authored map layers");
                assertEquals(1f, cover[i + 2], .001f, "The control preserves the same map-role bindings");
                assertEquals(0f, cover[i], .00001f, "Forcing cover must remove bedrock contribution");
                assertEquals(1f, cover[i + 1], .001f, "Forcing cover must leave complete cover contribution");
                assertTrue(selected[i] >= 0 && selected[i] <= 1 && selected[i + 1] >= 0 && selected[i + 1] <= 1,
                      "Selected material weights must remain bounded");
                if (selected[i] > .5f) { patches++; }
            }
            assertTrue(ground > 100, "The rock scene must render visible material-role samples");
            return patches / (double) ground;
        }
        var changed = manager.apply(Map.of("terrain-materials.glsl", coverOnly));
        assertTrue(changed.success(), changed.message());
        byte[] hardpan = render(surface, terrain, camera);
        int ground = 0, patches = 0;
        for (int i = 0; i < original.length; i += 4) {
            int plain = (hardpan[i] & 255) + (hardpan[i + 1] & 255) + (hardpan[i + 2] & 255);
            if (plain <= 30) { continue; } // Exclude the clear color, but retain dark fungal lowlands.
            ground++;
            int contrast = 0;
            for (int channel = 0; channel < 3; channel++) {
                int difference = (original[i + channel] & 255) - (hardpan[i + channel] & 255);
                contrast += family.equals("desert") ? difference : Math.abs(difference);
            }
            // Fungal depressions are deliberately dark. Judge their contrast relative to their own skin;
            // mineral caps still need a clear absolute difference at normal map zoom.
            if (contrast > (family.equals("fungus") ? plain * .20 : 36)) { patches++; }
        }
        assertTrue(ground > 100, "The " + family + " scene must render visible ground");
        return patches / (double) ground;
    }

    private static String albedoProbe() {
        String sculpt = GpuShaderSource.readDisk("terrain-sculpt.frag");
        String result = "vec3 result = shadeSurface(surface, caustic, submerged);";
        assertTrue(sculpt.contains(result), "The palette probe must read the actual completed albedo");
        return sculpt.replace(result, result + "\nresult = surface.albedo;\n");
    }

    private static float[] linearPixels(GpuSceneTestOutput surface) {
        var target = surface.scene();
        target.begin();
        var buffer = BufferUtils.newFloatBuffer(target.getWidth() * target.getHeight() * 4);
        Gdx.gl.glReadPixels(0, 0, target.getWidth(), target.getHeight(), GL20.GL_RGBA, GL20.GL_FLOAT, buffer);
        float[] pixels = new float[buffer.capacity()];
        buffer.get(pixels);
        for (int i = 0; i < pixels.length; i++) {
            if (i % 4 != 3) { pixels[i] /= GpuSceneRadiance.SCALE; }
        }
        target.end();
        return pixels;
    }

    private static Map<String, String> changedMaps(boolean surfaces) {
        var result = new HashMap<String, String>();
        // C1 separates color and numerical arrays. Perturb only actual normal/AO reads, retaining their
        // projection, coverage and LOD branches; odd semantic IDs no longer pass through color helpers.
        for (String file : List.of("terrain-materials.glsl", "terrain-sculpt.frag")) {
            String source = GpuShaderSource.readDisk(file);
            String changed = source.replaceAll(
                  "\\b(?:materialTexel|fungusTexel|translatedTexel|cliffTexel)\\(u_terrainData,",
                  "changedNormalTexel(u_terrainData,");
            assertTrue(!source.equals(changed), file + " must alter actual numerical-array sample sites");
            result.put(file, changed);
        }
        String projection = GpuShaderSource.readDisk("terrain-projection.glsl");
        String normal = "vec4 normalTexel(float layer, vec2 uv) {";
        assertTrue(projection.contains(normal), "Projected normal-map diagnostic must be installed");
        result.put("terrain-projection.glsl", """
              vec4 changedNormalTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient) {
                  return vec4(.95, .05, .65, .1);
              }
              vec4 changedNormalTexel(sampler2DArray maps, float layer, vec2 uv, mat2 gradient, float variation) {
                  return changedNormalTexel(maps, layer, uv, gradient);
              }
              """ + projection.replace(normal, normal + " return vec4(.95, .05, .65, .1);"));
        String magma = GpuShaderSource.readDisk("terrain-magma.glsl");
        String magmaSample = "vec4 magmaTexel(float map, vec2 uv, vec2 dx, vec2 dy) {";
        assertTrue(magma.contains(magmaSample), "Volcanic numerical-map diagnostic must be installed");
        result.put("terrain-magma.glsl", magma.replace(magmaSample, magmaSample
              + " if (map == MAGMA_NORMAL) return vec4(.95, .05, .65, .1);"
              + (surfaces ? " if (map == MAGMA_SURFACE) return vec4(.1, .1, .1, .1);" : "")));
        return result;
    }

    private static byte[] render(GpuSceneTestOutput surface, GpuTerrain terrain, OrthographicCamera camera) {
        surface.draw(terrain, camera, false);
        return ScreenUtils.getFrameBufferPixels(false);
    }

    private static double difference(byte[] a, byte[] b) {
        long total = 0;
        for (int i = 0; i < a.length; i++) { total += Math.abs((a[i] & 255) - (b[i] & 255)); }
        return total / (double) a.length;
    }

    private static BoardScene scene(String material) {
        return scene(material, 0);
    }

    private static BoardScene scene(String material, int elevation) {
        var pixels = new BoardScene.Pixels(new BufferedImage(84, 72, BufferedImage.TYPE_INT_ARGB));
        BoardLiquid liquid = switch (material) {
            case "crust" -> new BoardLiquid(BoardLiquid.Kind.MAGMA_CRUST, "", 0);
            case "lava" -> new BoardLiquid(BoardLiquid.Kind.MAGMA, "", 0);
            default -> BoardLiquid.NONE;
        };
        var tiles = new ArrayList<BoardScene.Tile>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                tiles.add(new BoardScene.Tile(new Coords(x, y), elevation, -1, false, 0,
                      liquid.kind() == BoardLiquid.Kind.NONE ? BoardScene.Surface.valueOf(material.toUpperCase(Locale.ROOT))
                            : BoardScene.Surface.SAND,
                      pixels, null, null, null, null, List.of(), List.of(), liquid, null, true));
            }
        }
        return new BoardScene(0, 7, 7, tiles, List.of(), List.of(), -1, "", List.of());
    }
}
