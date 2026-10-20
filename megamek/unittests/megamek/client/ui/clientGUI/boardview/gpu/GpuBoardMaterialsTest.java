/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.g3d.model.data.ModelTexture;
import com.badlogic.gdx.utils.GdxNativesLoader;
import megamek.common.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Board materials in Material Maker's Godot 4 Standard export: reading, cataloguing and packing, without a GL context. */
class GpuBoardMaterialsTest {
    @TempDir Path directory;
    private File previousData;
    private Path textures;

    @BeforeEach
    void configureAssets() throws Exception {
        previousData = Configuration.dataDir();
        Configuration.setDataDir(directory.toFile());
        textures = Files.createDirectories(directory.resolve("models/board/textures"));
        BoardMaterialFiles.sculpt(textures, "test", image(4, 4, 1), image(4, 4, 2), 7.5f);
    }

    @AfterEach
    void restoreAssets() { Configuration.setDataDir(previousData); }

    @Test
    void readsMaterialMakerAndGodotEditorResources() throws Exception {
        var material = GodotMaterial.read(textures.resolve("terrain/surfaces/test.tres").toFile(), textures.toFile());
        assertEquals("test", material.name());
        assertEquals(textures.resolve("terrain/surfaces/test_albedo.png").toFile(), material.albedo());
        assertEquals(textures.resolve("terrain/surfaces/test_orm.png").toFile(), material.roughnessMap());
        assertEquals(material.roughnessMap(), material.occlusion());
        assertEquals(GodotMaterial.GREEN, material.roughnessChannel());
        assertEquals(GodotMaterial.RED, material.occlusionChannel());
        assertNull(material.emission(), "Disabled features have no map");

        Path folder = Files.createDirectories(textures.resolve("vegetation"));
        Files.writeString(folder.resolve("editor.tres"), """
              [gd_resource type="StandardMaterial3D" load_steps=3 format=3 uid="uid://b4x"]

              [ext_resource type="Texture2D" uid="uid://c1" path="res://vegetation/editor_albedo.png" id="1_ab"]
              [ext_resource type="Texture2D" path="editor_height.png" id="2_cd"]

              [sub_resource type="Gradient" id="Gradient_1"]
              offsets = PackedFloat32Array(0, 1)

              [resource]
              ; Godot writes only non-default properties.
              albedo_color = Color(0.5, 0.25, 1, 1)
              albedo_texture = ExtResource("1_ab")
              roughness = 0.4
              heightmap_enabled = true
              heightmap_flip_texture = true
              depth_texture = ExtResource("2_cd")
              emission_energy_multiplier = 3.5
              metadata/tags = [
              "a", "b"
              ]
              """);
        var editor = GodotMaterial.read(folder.resolve("editor.tres").toFile(), textures.toFile());
        assertEquals(folder.resolve("editor_albedo.png").toFile().getCanonicalFile(), editor.albedo().getCanonicalFile());
        assertEquals(folder.resolve("editor_height.png").toFile(), editor.heightmap(), "Material Maker's tessellated alias");
        assertTrue(editor.heightmapFlipped());
        assertArrayEquals(new float[] { .5f, .25f, 1, 1 }, editor.albedoColor());
        assertEquals(.4f, editor.roughness());
        assertEquals(3.5f, editor.emissionEnergy());
        assertEquals(5, editor.heightmapScale(), "Godot's default");
        assertNull(editor.normal());
    }

    /** As Godot reads them: parameters the resource omits take the shader's defaults; undeclared ones do not exist. */
    @Test
    void readsShaderMaterialsAsGodotDoes() throws Exception {
        Path folder = Files.createDirectories(textures.resolve("terrain/ice"));
        Files.writeString(folder.resolve("sheet.gdshader"), """
              // Comments, groups, global and instance uniforms are not material parameters.
              shader_type spatial;
              /* uniform float commented = 1.0; */
              global uniform float board_wind;
              instance uniform float variation = 0.5;
              group_uniforms surface;
              uniform vec4 albedo : source_color = vec4(0.5);
              uniform sampler2D texture_albedo : source_color, filter_linear_mipmap, repeat_enable;
              uniform sampler2D texture_orm : hint_roughness_g;
              uniform float roughness : hint_range(0.0, 1.0) = 0.25; // A default the resource omits.
              uniform highp float metallic;
              uniform float heightmap_scale = 12.5;
              uniform sampler2D texture_cracks : hint_default_black;
              void fragment() { ALBEDO = albedo.rgb; }
              """);
        // As Godot saves it: res:// paths, generated ids, values in any order.
        Files.writeString(folder.resolve("sheet.tres"), """
              [gd_resource type="ShaderMaterial" load_steps=4 format=3 uid="uid://ice"]

              [ext_resource type="Shader" path="res://terrain/ice/sheet.gdshader" id="1_enbsj"]
              [ext_resource type="Texture2D" path="res://terrain/ice/sheet_cracks.png" id="2_d0eaq"]
              [ext_resource type="Texture2D" path="sheet_albedo.png" id="3_hfjrh"]
              [ext_resource type="Texture2D" path="sheet_orm.png" id="4_5ap6k"]

              [resource]
              render_priority = 0
              shader = ExtResource("1_enbsj")
              shader_parameter/normal_scale = 3.0
              shader_parameter/texture_cracks = ExtResource("2_d0eaq")
              shader_parameter/texture_albedo = ExtResource("3_hfjrh")
              shader_parameter/texture_orm = ExtResource("4_5ap6k")
              shader_parameter/heightmap_scale = 0.509804
              """);
        var ice = GodotMaterial.read(folder.resolve("sheet.tres").toFile(), textures.toFile());
        assertEquals(GodotMaterial.Kind.SHADER, ice.kind);
        assertEquals(folder.resolve("sheet_albedo.png").toFile(), ice.albedo());
        File orm = folder.resolve("sheet_orm.png").toFile();
        assertEquals(orm, ice.occlusion());
        assertEquals(orm, ice.roughnessMap());
        assertEquals(orm, ice.metallicMap());
        assertEquals(GodotMaterial.RED, ice.occlusionChannel());
        assertEquals(GodotMaterial.GREEN, ice.roughnessChannel());
        assertEquals(GodotMaterial.BLUE, ice.metallicChannel());
        assertEquals(.25f, ice.roughness(), "The shader's default, which Godot does not save");
        assertEquals(0, ice.metallic(), "Declared without a default: Godot's zero");
        assertEquals(1, ice.normalScale(), "An undeclared parameter has no effect, whatever the resource says");
        assertEquals(.509804f, ice.heightmapScale());
        assertArrayEquals(new float[] { .5f, .5f, .5f, .5f }, ice.albedoColor());
        assertEquals(folder.resolve("sheet_cracks.png").toFile().getCanonicalFile(), ice.extra("cracks").getCanonicalFile());
        assertNull(ice.normal());
        assertNull(ice.emission());
        assertFalse(ice.heightmapFlipped());
        assertFalse(ice.declares("variation"));
        assertFalse(ice.declares("board_wind"));
        assertFalse(ice.declares("commented"));
        var error = assertThrows(IllegalArgumentException.class, () -> ice.extra("coverage"));
        assertTrue(error.getMessage().contains("shader_parameter/texture_coverage"), error.getMessage());

        // An editor export replaces the ShaderMaterial with Material Maker's StandardMaterial3D: refuse it.
        Files.writeString(folder.resolve("sheet.tres"), """
              [gd_resource type="StandardMaterial3D" format=2]
              [ext_resource path="sheet_albedo.png" type="Texture" id=1]
              [resource]
              albedo_texture = ExtResource( 1 )
              """);
        error = assertThrows(IllegalArgumentException.class,
              () -> GodotMaterial.read(folder.resolve("sheet.tres").toFile(), textures.toFile()));
        assertTrue(error.getMessage().contains("export.py --wrap"), error.getMessage());
    }

    @Test
    void rejectsResourcesTheBoardCannotRender() throws Exception {
        Path folder = Files.createDirectories(textures.resolve("scenery"));
        rejected(folder, "[gd_resource type=\"ORMMaterial3D\" format=3]\n[resource]\n", "StandardMaterial3D");
        rejected(folder, "[gd_resource type=\"StandardMaterial3D\" format=3]\n[resource]\nalbedo_texture = ExtResource(\"9\")\n",
              "Unknown ext_resource");
        rejected(folder, "[gd_resource type=\"StandardMaterial3D\" format=3]\n[resource]\nalbedo_texture = SubResource(\"1\")\n",
              "ext_resource image");
        rejected(folder, "[gd_resource type=\"StandardMaterial3D\" format=3]\n[ext_resource path=\"../../../escape.png\" "
              + "type=\"Texture\" id=1]\n[resource]\n", "textures folder");
        rejected(folder, "[gd_resource type=\"StandardMaterial3D\" format=3]\n[resource]\nroughness = rough\n", "finite");
        rejected(folder, "[gd_resource type=\"StandardMaterial3D\" format=3]\n[resource]\n", "albedo_texture");
        rejected(folder, "[gd_resource type=\"ShaderMaterial\" format=3]\n[resource]\n", "needs its shader");
        rejected(folder, "[gd_resource type=\"ShaderMaterial\" format=3]\n[ext_resource type=\"Shader\" path=\"absent.gdshader\" "
              + "id=\"1\"]\n[resource]\nshader = ExtResource(\"1\")\n", "Missing required board material shader");
    }

    private void rejected(Path folder, String text, String reason) throws Exception {
        Files.writeString(folder.resolve("bad.tres"), text);
        var error = assertThrows(IllegalArgumentException.class, () -> {
            var material = GodotMaterial.read(folder.resolve("bad.tres").toFile(), textures.toFile());
            material.roughness();
            material.albedo();
        });
        assertTrue(error.getMessage().contains(reason), error.getMessage());
    }

    @Test
    void looseMaterialsCoverMoreGroundWhenTheirTextureIsWider() throws Exception {
        var assets = new GpuAssets();
        assertEquals(7.5f, assets.sculptTile("test"));
        assertEquals(1, assets.coverage("terrain/surfaces/test"));
        // The artist exports twice the width at the same texel density: the repeat doubles, the catalogue is unchanged.
        String catalog = Files.readString(textures.resolve("materials.json"));
        BoardMaterialFiles.sculpt(textures, "test", image(8, 8, 3), image(8, 8, 4), 99);
        Files.writeString(textures.resolve("materials.json"), catalog);
        assertEquals(7.5f, assets.sculptTile("test"), "The live owner keeps its snapshot until asset reload");
        var reloaded = new GpuAssets();
        assertEquals(15, reloaded.sculptTile("test"));
        assertEquals(2, reloaded.coverage("terrain/surfaces/test"));
        assets.dispose();
        assertEquals(15, assets.sculptTile("test"), "Disposal releases cached definitions too");

        BoardMaterialFiles.sculpt(textures, "test", image(8, 4, 3), image(8, 4, 4), 7.5f);
        var error = assertThrows(IllegalArgumentException.class, () -> new GpuAssets().sculptTile("test"));
        assertTrue(error.getMessage().contains("square"), error.getMessage());
        BoardMaterialFiles.constrained(textures, "terrain/surfaces/test");
        error = assertThrows(IllegalArgumentException.class, () -> new GpuAssets().sculptTile("test"));
        assertTrue(error.getMessage().contains("not a loose material"), error.getMessage());
    }

    @Test
    void incompleteMaterialsReportTheMissingFileBeforeAnyUpload() throws Exception {
        missing("absent.tres", () -> new GpuAssets().sculptTile("absent"));
        for (String file : List.of("test_normal.png", "test_heightmap.png", "test.tres")) {
            BoardMaterialFiles.sculpt(textures, "test", image(4, 4, 1), image(4, 4, 2), 7.5f);
            Files.delete(textures.resolve("terrain/surfaces/" + file));
            missing(file, () -> new GpuAssets().sculptArray(List.of("test")));
        }
        BoardMaterialFiles.sculpt(textures, "test", image(4, 4, 1), image(4, 4, 2), 7.5f);
        Files.delete(textures.resolve("materials.json"));
        missing("materials.json", () -> new GpuAssets().sculptTile("test"));
    }

    private static void missing(String name, org.junit.jupiter.api.function.Executable load) {
        var error = assertThrows(RuntimeException.class, load);
        assertTrue(error.getMessage().contains("Missing required"), error.getMessage());
        assertTrue(error.getMessage().contains(name), error.getMessage());
    }

    @Test
    void packingReproducesTheRenderersLayoutsExactly() throws Exception {
        GdxNativesLoader.load();
        var color = image(16, 16, 5);
        var normal = image(16, 16, 6);
        BoardMaterialFiles.sculpt(textures, "test", color, normal, 7.5f);
        var sculpt = new GpuAssets().definition("terrain/surfaces/test");
        assertPixels(color, GpuMaterialMaps.pack(sculpt, GpuMaterialMaps.Layout.COLOR_HEIGHT), "colour and height");
        assertPixels(normal, GpuMaterialMaps.pack(sculpt, GpuMaterialMaps.Layout.NORMAL_OCCLUSION), "normal and occlusion");

        var surface = image(16, 16, 7);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) { surface.setRGB(x, y, surface.getRGB(x, y) & 0xffffff | 36 << 24); }
        }
        var opaque = BoardMaterialFiles.channels(image(16, 16, 8), 0, 1, 2, -1);
        var opaqueNormal = BoardMaterialFiles.channels(image(16, 16, 9), 0, 1, 2, -1);
        BoardMaterialFiles.relief(textures, "roads/gravel", opaque, opaqueNormal, surface);
        var gravel = new GpuAssets().definition("roads/gravel");
        assertPixels(opaque, GpuMaterialMaps.pack(gravel, GpuMaterialMaps.Layout.COLOR), "colour");
        assertPixels(opaqueNormal, GpuMaterialMaps.pack(gravel, GpuMaterialMaps.Layout.NORMAL), "normal");
        assertPixels(surface, GpuMaterialMaps.pack(gravel, GpuMaterialMaps.Layout.RELIEF), "relief surface");

        var heat = image(16, 16, 10);
        BoardMaterialFiles.relief(textures, "terrain/lava/lava", opaque, opaqueNormal, surface);
        BoardMaterialFiles.heat(textures, "terrain/lava/lava", heat, 5);
        assertPixels(heat, GpuMaterialMaps.pack(new GpuAssets().definition("terrain/lava/lava"),
              GpuMaterialMaps.Layout.MOLTEN_HEAT), "molten heat");

        // Mud keeps roughness in its normal's blue channel; the file's normal is complete.
        BoardMaterialFiles.sculpt(textures, "mud", color, normal, 64);
        Path mud = textures.resolve("terrain/surfaces");
        BoardMaterialFiles.write(mud, "mud_orm.png", BoardMaterialFiles.channels(normal, 3, 2, -2, -3));
        assertPixels(normal, GpuMaterialMaps.pack(new GpuAssets().definition("terrain/surfaces/mud"),
              GpuMaterialMaps.Layout.NORMAL_ROUGHNESS), "mud's packed roughness");
    }

    @Test
    void godotFactorsApplyWhilePacking() throws Exception {
        GdxNativesLoader.load();
        Path folder = textures.resolve("terrain/surfaces");
        var flat = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        flat.setRGB(0, 0, 0xff808080);
        flat.setRGB(1, 0, 0xffc8a0ff);
        BoardMaterialFiles.sculpt(textures, "test", image(2, 1, 1), flat, 1);
        String tres = Files.readString(folder.resolve("test.tres"));
        Files.writeString(folder.resolve("test.tres"), tres.replace("roughness = 1.0", "roughness = 0.5")
              .replace("normal_scale = 1.0", "normal_scale = 0.0")
              .replace("albedo_color = Color(1.0, 1.0, 1.0, 1.0)", "albedo_color = Color(1.0, 1.0, 1.0, 0.5)"));
        var material = new GpuAssets().definition("terrain/surfaces/test");
        Pixmap normal = GpuMaterialMaps.pack(material, GpuMaterialMaps.Layout.NORMAL);
        Pixmap surface = GpuMaterialMaps.pack(material, GpuMaterialMaps.Layout.METALLIC_ROUGHNESS);
        Pixmap color = GpuMaterialMaps.pack(material, GpuMaterialMaps.Layout.COLOR);
        try {
            assertEquals(0x8080ffff, normal.getPixel(1, 0), "A zero normal scale flattens the map");
            assertEquals(128, surface.getPixel(0, 0) >>> 16 & 255, "Roughness is the factor times the map");
            assertEquals(128, color.getPixel(0, 0) & 255, "Albedo alpha takes the albedo colour's alpha");
        } finally {
            normal.dispose();
            surface.dispose();
            color.dispose();
        }
    }

    @Test
    void authoredRoughnessFollowsGodotsFactorAndMap() throws Exception {
        GdxNativesLoader.load();
        Path folder = textures.resolve("terrain/surfaces");
        // As in Godot, a white channel at factor 1 is fully rough; nothing falls back to an estimate.
        var rough = roughness(folder, 255, 255, 1);
        assertEquals(1, rough.value());
        assertNull(rough.map());
        assertEquals(.92f, roughness(folder, 255, 255, .92f).value(), "A factor over a white map is a scalar");
        assertEquals(128 / 255f, roughness(folder, 128, 128, 1).value(), 1e-6, "A uniform map is a scalar");
        var map = roughness(folder, 40, 200, 1);
        assertEquals(120 / 255f, map.value(), 1e-6, "A map carries its mean");
        assertArrayEquals(new byte[] { 40, (byte) 200 }, map.map());
        assertEquals(2, map.width());
        var scaled = roughness(folder, 40, 200, .5f);
        assertArrayEquals(new byte[] { 20, 100 }, scaled.map(), "The factor scales the map");
        assertEquals(60 / 255f, scaled.value(), 1e-6);
    }

    @Test
    void roughnessMapsTakeTheLargestGrid() {
        GdxNativesLoader.load();
        var map = new GpuAssets.AuthoredRoughness(.5f, new byte[] { 90, 90, 90, 90 }, 2, 2);
        assertSame(map.map(), GpuAssets.onGrid(map, 2, 2), "A map on the grid is used as it is");
        byte[] resized = GpuAssets.onGrid(map, 4, 4);
        assertEquals(16, resized.length);
        for (byte value : resized) { assertEquals(90, value, "Resampling keeps a uniform map's value"); }
    }

    private GpuAssets.AuthoredRoughness roughness(Path folder, int first, int second, float factor) throws Exception {
        var normal = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        normal.setRGB(0, 0, 0xff8080ff);
        normal.setRGB(1, 0, 0xff8080ff);
        BoardMaterialFiles.sculpt(textures, "test", image(2, 1, 1), normal, 1);
        var orm = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        orm.setRGB(0, 0, 0xff0000 | first << 8);
        orm.setRGB(1, 0, 0xff0000 | second << 8);
        BoardMaterialFiles.write(folder, "test_orm.png", orm);
        String tres = Files.readString(folder.resolve("test.tres"));
        Files.writeString(folder.resolve("test.tres"), tres.replace("roughness = 1.0", "roughness = " + factor));
        return GpuAssets.authoredRoughness(new GpuAssets().definition("terrain/surfaces/test"));
    }

    @Test
    void lavaEmissionUsesTheEmissionMapAndEnergy() throws Exception {
        GdxNativesLoader.load();
        var heat = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        heat.setRGB(0, 0, 0x00ff0000); // Emitted RGB is independent of the coverage mask.
        heat.setRGB(1, 0, 0xff008040);
        var opaque = BoardMaterialFiles.channels(image(2, 1, 1), 0, 1, 2, -1);
        BoardMaterialFiles.relief(textures, "terrain/lava/lava", opaque, opaque, opaque);
        BoardMaterialFiles.heat(textures, "terrain/lava/lava", heat, 2);
        var emission = new GpuAssets().lavaEmission();
        assertEquals(2, emission.scale());
        assertEquals(1, emission.red(), 1e-7);
        assertEquals(128f / 255, emission.green(), 1e-7);
        assertEquals(64f / 255, emission.blue(), 1e-7);
        Path tres = textures.resolve("terrain/lava/lava.tres");
        Files.writeString(tres, Files.readString(tres).replace("shader_parameter/emission_energy = 2.0",
              "shader_parameter/emission_energy = -1.0"));
        assertThrows(IllegalArgumentException.class, () -> new GpuAssets().lavaEmission());
        Files.writeString(tres, Files.readString(tres).replace("shader_parameter/texture_emission", "; texture_emission"));
        assertThrows(IllegalArgumentException.class, () -> new GpuAssets().lavaEmission());
    }

    @Test
    void modelsTakeEachMapInTheBoardsLayoutForItsGltfRole() throws Exception {
        File folder = textures.resolve("terrain/surfaces").toFile();
        File root = textures.toFile();
        var color = GpuMaterialMaps.modelMap(new File(folder, "test_albedo.png"), ModelTexture.USAGE_DIFFUSE, root);
        assertNotNull(color);
        assertEquals("", color.suffix(), "Shared with the board's own draws of the material");
        assertNotNull(GpuMaterialMaps.modelMap(new File(folder, "test_normal.png"), ModelTexture.USAGE_NORMAL, root));
        assertEquals("#occlusion",
              GpuMaterialMaps.modelMap(new File(folder, "test_orm.png"), ModelTexture.USAGE_AMBIENT, root).suffix());
        assertNull(GpuMaterialMaps.modelMap(new File(folder, "test_albedo.png"), ModelTexture.USAGE_NORMAL, root),
              "A map named in another role loads as it is");
        assertNull(GpuMaterialMaps.modelMap(directory.resolve("elsewhere_albedo.png").toFile(), ModelTexture.USAGE_DIFFUSE,
              root), "Images outside the board's materials load as they are");
    }

    /** The shipped data: every material reads, names existing maps and is catalogued exactly once. */
    @Test
    void shippedMaterialsAreCompleteAndCatalogued() throws Exception {
        File shipped = new File(previousData, "models/board/textures");
        org.junit.jupiter.api.Assumptions.assumeTrue(new File(shipped, "materials.json").isFile(), "Staged board data");
        var catalog = GpuMaterialCatalog.read(new File(shipped, "materials.json"));
        Set<String> names = new HashSet<>();
        try (Stream<Path> files = Files.walk(shipped.toPath())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tres")).toList()) {
                var material = GodotMaterial.read(file.toFile(), shipped);
                String name = GpuMaterialMaps.name(material, shipped);
                names.add(name);
                for (File map : Stream.of(material.albedo(), material.normal(), material.roughnessMap(), material.occlusion(),
                      material.heightmap(), material.emission(), material.subsurface()).filter(java.util.Objects::nonNull).toList()) {
                    assertTrue(map.isFile(), map.toString());
                    GpuMaterialMaps.size(map);
                }
                assertTrue(catalog.isLoose(name) != catalog.constrainedNames().contains(name), "Catalogued once: " + name);
                File shader = new File(file.getParent().toFile(), material.name() + ".gdshader");
                assertEquals(shader.isFile(), material.kind == GodotMaterial.Kind.SHADER, "A shader beside it: " + name);
                if (shader.isFile()) { shaderMaterialIsComplete(material, shader); }
                if (catalog.isLoose(name)) {
                    int[] size = GpuMaterialMaps.size(material.albedo());
                    // Any width is valid (a wider export covers more ground); the maps must be square.
                    assertTrue(catalog.coverage(name, size[0], size[1]) > 0, name);
                    assertEquals(GpuMaterialMaps.sculpted(name), !Float.isNaN(catalog.loose(name).metres()), name);
                }
            }
        }
        var catalogued = new HashSet<>(catalog.looseNames());
        catalogued.addAll(catalog.constrainedNames());
        assertEquals(catalogued, names, "Every catalogued material exists");
        assertFalse(names.isEmpty());
        // The maps StandardMaterial3D has no slot for (docs/gpu-board-materials.md).
        for (String[] extra : new String[][] { { "terrain/lava/lava", "coverage" }, { "terrain/lava/crust", "glow" },
              { "terrain/ice/sheet", "cracks" } }) {
            var material = GodotMaterial.read(new File(shipped, extra[0] + ".tres"), shipped);
            assertTrue(material.extra(extra[1]).isFile(), extra[0]);
        }
    }

    /** The ShaderMaterial of its own shader: every map the shader declares is assigned, every value is declared. */
    private static void shaderMaterialIsComplete(GodotMaterial material, File shader) throws Exception {
        assertEquals(shader.getCanonicalFile(), material.texture("shader").getCanonicalFile(), material.file.toString());
        var code = Files.readString(shader.toPath());
        var maps = java.util.regex.Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)").matcher(code);
        while (maps.find()) {
            File map = material.texture("shader_parameter/" + maps.group(1));
            assertNotNull(map, material.file + " assigns " + maps.group(1));
            assertTrue(map.isFile(), map.toString());
        }
        for (String line : Files.readAllLines(material.file.toPath())) {
            if (line.startsWith("shader_parameter/")) {
                String parameter = line.substring("shader_parameter/".length(), line.indexOf('=')).strip();
                assertTrue(material.declares(parameter), material.file + ": " + shader.getName() + " declares " + parameter);
            }
        }
    }

    /** Reproducible noise in every channel, including alpha. */
    private static BufferedImage image(int width, int height, long seed) {
        var random = new Random(seed);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) { image.setRGB(x, y, random.nextInt()); }
        }
        return image;
    }

    private static void assertPixels(BufferedImage expected, Pixmap actual, String what) {
        try {
            assertEquals(expected.getWidth(), actual.getWidth(), what);
            for (int y = 0; y < expected.getHeight(); y++) {
                for (int x = 0; x < expected.getWidth(); x++) {
                    int argb = expected.getRGB(x, y);
                    assertEquals(argb << 8 | argb >>> 24, actual.getPixel(x, y), what + " at " + x + "," + y);
                }
            }
        } finally { actual.dispose(); }
    }
}
