/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.ScreenUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.Configuration;
import megamek.common.board.Board;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.opengl.GL11;

/** Real button, EDT capture, file edits, GL uploads and repeated disposal in a running battle view. */
@Tag("on-demand")
class GpuAssetReloadSmokeTest {
    @TempDir
    Path directory;

    @Test
    void reloadsEditedAssetsAndCanRetryWithoutReopeningTheBoard() throws Exception {
        File data = Configuration.dataDir();
        File images = Configuration.imagesDir();
        File fonts = Configuration.fontsDir();
        Path models = data.toPath().resolve("models");
        try (var paths = Files.walk(models)) {
            for (Path path : paths.toList()) {
                Path target = directory.resolve("models").resolve(models.relativize(path));
                if (Files.isDirectory(path)) { Files.createDirectories(target); }
                else { Files.copy(path, target); }
            }
        }
        var failure = new AtomicReference<Throwable>();
        Configuration.setImagesDir(images);
        Configuration.setFontsDir(fonts);
        Configuration.setDataDir(directory.toFile());
        try (var fixture = GpuBoardFixture.create(Board.createEmptyBoard(6, 6))) {
            var glb = new RigidGlbTest();
            glb.directory = Files.createDirectories(directory.resolve("models/reload"));
            glb.embedded("png");
            Files.writeString(glb.directory.resolve("component.json"), """
                  {"schema":2,"kind":"equipment","family":"laser","mesh":"mesh.glb",
                   "bounds":{"min":[0,0,0],"max":[4,4,4]},"rig":"rigid-v1",
                   "joints":{"root":"root"},"locations":{},"hardpoints":[],"emitters":[]}
                  """);
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                int step;
                GpuTerrain originalTerrain;
                Texture originalTexture;
                GpuUnitModels.ModularAsset originalUnit;
                ModelInstance originalInstance;
                float treeHeight, rockHeight, scatterHeight, unitHeight;
                BoardGeometry.Tuning tuning;
                Path tileset = directory.resolve("models/board/tileset/saxarba.tileset");
                byte[] tilesetBytes;
                Path rockMesh = directory.resolve("models/board/rocks/block-0.glb");
                byte[] rockBytes;
                long generation;
                Path shaders = directory.resolve("shaders");
                String compositeSource;
                String unitSource;
                Map<String, ShaderProgram> originalPrograms;

                @Override
                public void create() {
                    super.create();
                    boardCamera.setIsometric(false);
                    GpuBoardTestUi.stage().getRoot().<Slider>findActor("Unit scale").setValue(1.15f);
                }

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Asset reload must finish");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                        var root = GpuBoardTestUi.stage().getRoot();
                        TextButton button = root.findActor("tuning-reload-assets");
                        Label status = root.findActor("tuning-reload-status");
                        if (step == 0 && frames() >= 5 && !terrain().busy()) {
                            originalTerrain = terrain();
                            originalTexture = assets().material("terrain/rock");
                            treeHeight = assets().model("tree").calculateBoundingBox(new BoundingBox()).getHeight();
                            rockHeight = BoardRocks.rock(true, 0).height();
                            scatterHeight = BoardScatter.bush(0).height();
                            originalUnit = library().modular("reload/component.json");
                            assertNotNull(originalUnit);
                            unitHeight = originalUnit.model().calculateBoundingBox(new BoundingBox()).getHeight();
                            originalInstance = instances().get("1:-1");
                            assertNotNull(originalInstance);
                            tuning = BoardGeometry.tuning();
                            generation = fixture.source.takeFrame().boardGeneration();
                            originalPrograms = programs();
                            assertTrue(originalPrograms.keySet().stream().anyMatch(key -> key.startsWith("units")));
                            Files.createDirectories(shaders);
                            compositeSource = GpuShaderSource.read("atmosphere-composite.frag")
                                  .replace("gl_FragColor = vec4(color, 1.0);",
                                        "gl_FragColor = vec4(vec3(0.85, 0.15, 0.65) + color * 0.001, 1.0);");
                            unitSource = GpuShaderSource.read("unit-material.frag")
                                  .replace("return diffuse;", "return diffuse.bgr; // asset-reload-unit");
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), compositeSource);
                            Files.writeString(shaders.resolve("unit-material.frag"), unitSource);
                            Files.writeString(shaders.resolve("light-model.glsl"),
                                  GpuShaderSource.read("light-model.glsl") + "\n// asset-reload-include\n");
                            GpuAssetReloadTest.png(directory.resolve("models/board/textures/terrain/rock.png"), 0xff0000ff);
                            scaleMesh(directory.resolve("models/board/tree.glb"));
                            scaleMesh(directory.resolve("models/board/rocks/block-0.glb"));
                            scaleMesh(directory.resolve("models/board/scatter.glb"));
                            scaleMesh(glb.directory.resolve("mesh.glb"));
                            GpuBoardTestUi.click("tuning");
                            GpuBoardTestUi.stage().draw();
                            GpuBoardTestUi.click("tuning-reload-assets");
                            assertTrue(button.isDisabled());
                            step = 1;
                        } else if (step == 1 && !button.isDisabled()) {
                            assertEquals("Assets reloaded", status.getText().toString());
                            Map<String, ShaderProgram> reloaded = programs();
                            assertEquals(originalPrograms.keySet(), reloaded.keySet());
                            originalPrograms.forEach((name, shader) -> assertNotSame(shader, reloaded.get(name), name));
                            for (var entry : reloaded.entrySet()) {
                                assertTrue(entry.getValue().isCompiled(), entry.getKey());
                                if (entry.getKey().startsWith("units")) {
                                    assertTrue(entry.getValue().getFragmentShaderSource().contains("asset-reload-unit"));
                                    assertTrue(entry.getValue().getVertexShaderSource().contains("asset-reload-include"));
                                }
                            }
                            assertCompositePixels();
                            assertNotSame(originalTerrain, terrain());
                            assertEquals(0, originalTexture.getTextureObjectHandle(), "Old textures must be released");
                            Texture texture = assets().material("terrain/rock");
                            ByteBuffer pixels = ByteBuffer.allocateDirect(texture.getWidth() * texture.getHeight() * 4);
                            texture.bind();
                            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
                            assertEquals(0x0000ffff, pixels.getInt(0), "The new image must reach GPU memory");
                            assertEquals(treeHeight * 2, assets().model("tree").calculateBoundingBox(new BoundingBox()).getHeight(), .001f);
                            assertEquals(rockHeight * 2, BoardRocks.rock(true, 0).height(), .001f);
                            assertEquals(scatterHeight * 2, BoardScatter.bush(0).height(), .001f);
                            var unit = library().modular("reload/component.json");
                            assertNotSame(originalUnit, unit);
                            assertNotNull(unit);
                            assertEquals(unitHeight * 2, unit.model().calculateBoundingBox(new BoundingBox()).getHeight(), .001f);
                            assertNotSame(originalInstance, instances().get("1:-1"));
                            assertEquals(tuning, BoardGeometry.tuning());
                            assertFalse(boardCamera.isIsometric());
                            assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                            tilesetBytes = Files.readAllBytes(tileset);
                            Files.delete(tileset);
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 2;
                        } else if (step == 2 && !button.isDisabled()) {
                            assertTrue(status.getText().toString().startsWith("Reload failed"));
                            Files.write(tileset, tilesetBytes);
                            rockBytes = Files.readAllBytes(rockMesh);
                            Files.writeString(rockMesh, "unfinished GLB export");
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 3;
                        } else if (step == 3 && !button.isDisabled()) {
                            assertTrue(status.getText().toString().startsWith("Reload failed"));
                            // A failure after GPU disposal still leaves the panel usable and the board unpickable.
                            Gdx.input.getInputProcessor().mouseMoved(100, 200);
                            Gdx.input.getInputProcessor().touchDown(100, 200, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                            Gdx.input.getInputProcessor().touchUp(100, 200, 0, com.badlogic.gdx.Input.Buttons.LEFT);
                            Files.write(rockMesh, rockBytes);
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 4;
                        } else if (step == 4 && !button.isDisabled()) {
                            assertEquals("Assets reloaded", status.getText().toString());
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), "invalid shader source");
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 5;
                        } else if (step == 5 && !button.isDisabled()) {
                            assertTrue(status.getText().toString().startsWith("Reload failed"));
                            Files.writeString(shaders.resolve("atmosphere-composite.frag"), compositeSource);
                            Files.writeString(shaders.resolve("unit-material.frag"), "invalid shader source");
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 6;
                        } else if (step == 6 && !button.isDisabled()) {
                            assertTrue(status.getText().toString().startsWith("Reload failed"));
                            // This failure occurs inside the scene framebuffer, after a ModelBatch has begun.
                            assertEquals(0, GL11.glGetInteger(GL20.GL_FRAMEBUFFER_BINDING));
                            GpuBoardTestUi.stage().draw();
                            Files.writeString(shaders.resolve("unit-material.frag"), unitSource);
                            GpuBoardTestUi.click("tuning-reload-assets");
                            step = 7;
                        } else if (step == 7 && !button.isDisabled()) {
                            assertEquals("Assets reloaded", status.getText().toString());
                            assertCompositePixels();
                            assertEquals(tuning, BoardGeometry.tuning());
                            assertFalse(boardCamera.isIsometric());
                            assertEquals(generation, fixture.source.takeFrame().boardGeneration());
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) {
                        failure.set(error);
                        Gdx.app.exit();
                    }
                }

                GpuTerrain terrain() throws Exception { return (GpuTerrain) field(this, "terrain"); }
                GpuAssets assets() throws Exception { return (GpuAssets) field(terrain(), "assets"); }
                GpuUnitModels library() throws Exception { return (GpuUnitModels) field(this, "unitModels"); }

                Map<String, ShaderProgram> programs() throws Exception {
                    Map<String, ShaderProgram> result = new LinkedHashMap<>();
                    Object atmosphere = field(this, "atmosphere");
                    result.put("fog", (ShaderProgram) field(atmosphere, "fogShader"));
                    result.put("composite", (ShaderProgram) field(atmosphere, "compositeShader"));
                    result.put("outlines", (ShaderProgram) field(field(this, "unitVisibility"), "shader"));
                    result.put("hex text", (ShaderProgram) field(field(this, "hexText"), "shader"));
                    addPrograms(result, "units", (ModelBatch) field(this, "unitBatch"));
                    addPrograms(result, "terrain", (ModelBatch) field(terrain(), "batch"));
                    return result;
                }
                @SuppressWarnings("unchecked")
                Map<String, ModelInstance> instances() throws Exception {
                    return (Map<String, ModelInstance>) field(this, "unitInstances");
                }
            }, GpuBoardWindow.configuration(false));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
        } finally {
            Configuration.setDataDir(data);
            Configuration.setImagesDir(images);
            Configuration.setFontsDir(fonts);
            BoardRocks.reload();
            BoardScatter.reload();
            BoardGeometry.tune(BoardGeometry.DEFAULTS);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static void addPrograms(Map<String, ShaderProgram> result, String name, ModelBatch batch) throws Exception {
        int index = 0;
        for (Object shader : (Iterable<?>) field(batch.getShaderProvider(), "shaders")) {
            result.put(name + index++, (ShaderProgram) field(shader, "program"));
        }
    }

    /** The recompiled composite must actually reach the window, beyond merely changing cached source strings. */
    private static void assertCompositePixels() {
        byte[] pixels = ScreenUtils.getFrameBufferPixels(false);
        int edited = 0;
        for (int i = 0; i < pixels.length; i += 4) {
            if (Math.abs(Byte.toUnsignedInt(pixels[i]) - 217) <= 1
                  && Math.abs(Byte.toUnsignedInt(pixels[i + 1]) - 38) <= 1
                  && Math.abs(Byte.toUnsignedInt(pixels[i + 2]) - 166) <= 1) { edited++; }
        }
        assertTrue(edited > pixels.length / 40, "The edited composite should cover visible board pixels");
    }

    /** Edit authored node transforms while retaining the original GLB binary payload. */
    private static void scaleMesh(Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int length = input.getInt(12);
        ObjectMapper mapper = new ObjectMapper();
        var document = mapper.readTree(new String(bytes, 20, length, StandardCharsets.UTF_8));
        for (var node : document.get("nodes")) {
            if (!node.has("mesh")) { continue; }
            var scale = mapper.createArrayNode();
            for (int i = 0; i < 3; i++) { scale.add(2 * (node.has("scale") ? node.get("scale").get(i).asDouble() : 1)); }
            ((ObjectNode) node).set("scale", scale);
        }
        byte[] json = mapper.writeValueAsBytes(document);
        int padded = (json.length + 3) & ~3;
        ByteBuffer output = ByteBuffer.allocate(bytes.length - length + padded).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity()).putInt(padded).putInt(0x4e4f534a).put(json);
        while (output.position() < 20 + padded) { output.put((byte) ' '); }
        output.put(bytes, 20 + length, bytes.length - 20 - length);
        Files.write(file, output.array());
    }
}
