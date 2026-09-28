/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static megamek.client.ui.clientGUI.boardview.gpu.GpuMixedUnitBenchmarkSmokeTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.utils.ScreenUtils;
import megamek.common.board.Board;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real editor documents/timer, board pixels, linked programs, shared includes and failure recovery. */
@Tag("on-demand")
class GpuShaderLiveSmokeTest {
    @Test
    void saveSurvivesSupersededPreviewsAndCompileErrorsCancelIt(@TempDir Path directory) throws Exception {
        record Preview(Map<String, String> sources, Consumer<GpuShaderManager.Result> completed) { }
        SwingUtilities.invokeAndWait(() -> {
            Path path = directory.resolve("fixture.frag");
            var previews = new ArrayList<Preview>();
            var editor = new GpuShaderEditor(Map.of("fixture.frag", new GpuShaderSource.FileSource("saved", path, false)),
                  "test", (sources, completed) -> previews.add(new Preview(sources, completed)), callback -> { });
            try {
                editor.open("fixture.frag");
                var documents = (Map<?, ?>) field(editor, "documents");
                var text = (RSyntaxTextArea) field(documents.get("fixture.frag"), "text");
                text.setText("first draft");
                editor.apply(true);
                text.setText("newer draft");
                editor.apply(false);
                previews.get(0).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertFalse(Files.exists(path), "An obsolete compilation cannot save newer unvalidated text");
                assertEquals("newer draft", previews.get(1).sources().get("fixture.frag"));
                previews.get(1).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertEquals("newer draft", Files.readString(path));

                text.setText("broken draft");
                editor.apply(true);
                previews.get(2).completed().accept(new GpuShaderManager.Result(false, "Preview unchanged", null, 0));
                assertEquals("newer draft", Files.readString(path));
                text.setText("fixed draft");
                editor.apply(false);
                previews.get(3).completed().accept(new GpuShaderManager.Result(true, "Compiled", null, 1));
                assertEquals("newer draft", Files.readString(path), "Fixing a rejected save only previews until Save is pressed again");
            } catch (Exception error) { throw new AssertionError(error); }
            finally { editor.close(); }
        });
    }

    @Test
    void typingUpdatesTheBoardAndInvalidEditsKeepTheLastWorkingPrograms() throws Exception {
        var failure = new AtomicReference<Throwable>();
        try (var fixture = GpuBoardFixture.create(Board.createEmptyBoard(6, 6))) {
            new Lwjgl3Application(new GpuBattleView(fixture.source) {
                final long deadline = System.nanoTime() + 180_000_000_000L;
                GpuShaderManager manager;
                GpuShaderEditor editor;
                GpuTerrain originalTerrain;
                ShaderProgram original, preview;
                Object units;
                String composite, light;
                int step;
                long revision;

                @Override
                public void render() {
                    try {
                        super.render();
                        assertTrue(System.nanoTime() < deadline, "Live shader editor step " + step + " timed out");
                        assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError(), "GL error at step " + step);
                        if (step == 0 && frames() >= 5 && !((GpuTerrain) field(this, "terrain")).busy()) {
                            originalTerrain = (GpuTerrain) field(this, "terrain");
                            manager = (GpuShaderManager) field(this, "shaderEdits");
                            original = compositeProgram();
                            composite = GpuShaderSource.readDisk("atmosphere-composite.frag");
                            light = GpuShaderSource.readDisk("light-model.glsl");
                            GpuBoardTestUi.click("tuning");
                            GpuBoardTestUi.click("tuning-edit-shaders");
                            step = 1;
                        } else if (step == 1) {
                            SwingUtilities.invokeAndWait(() -> {
                                try { editor = (GpuShaderEditor) field(manager, "editor"); }
                                catch (Exception error) { throw new AssertionError(error); }
                            });
                            if (editor == null) { return; }
                            revision = manager.revision();
                            edit("atmosphere-composite.frag", composite.replace("fragColor = vec4(color, 1.0);",
                                  "fragColor = vec4(0.85, 0.15, 0.65, 1.0);"));
                            step = 2;
                        } else if (step == 2 && manager.revision() > revision) {
                            assertSame(originalTerrain, field(this, "terrain"), "Typing must not rebuild terrain");
                            preview = compositeProgram();
                            assertNotSame(original, preview);
                            assertFalse(Gdx.gl.glIsProgram(original.getHandle()), "The replaced program is released");
                            assertPink();
                            checkRejectedChanges();
                            capture();
                            units = ((GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider()).active();
                            // A shared include prepares material replacements before the later composite fails.
                            edit("light-model.glsl", light + "\n// live-editor-shared-include\n");
                            edit("atmosphere-composite.frag", "#version 330 core\nthis is an unfinished edit");
                            step = 3;
                        } else if (step == 3 && editorStatus().startsWith("Preview unchanged")) {
                            assertSame(preview, compositeProgram());
                            assertTrue(Gdx.gl.glIsProgram(preview.getHandle()));
                            assertSame(units, ((GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider()).active());
                            assertPink();
                            revision = manager.revision();
                            edit("atmosphere-composite.frag", composite);
                            step = 4;
                        } else if (step == 4 && manager.revision() > revision) {
                            assertSame(originalTerrain, field(this, "terrain"));
                            assertNotSame(preview, compositeProgram());
                            assertFalse(Gdx.gl.glIsProgram(preview.getHandle()));
                            var provider = (GpuShaderProvider) ((ModelBatch) field(this, "unitBatch")).getShaderProvider();
                            assertNotSame(units, provider.active());
                            assertTrue(provider.sources().stream().allMatch(source ->
                                  source.vertex().contains("live-editor-shared-include")));
                            assertEquals(composite, GpuShaderSource.readDisk("atmosphere-composite.frag"));
                            checkInactiveEffect();
                            assertEquals(0, fixture.clicks.get(), "Editor never issues game commands");
                            Gdx.app.exit();
                        }
                    } catch (Throwable error) { failure.set(error); Gdx.app.exit(); }
                }

                ShaderProgram compositeProgram() throws Exception {
                    return (ShaderProgram) field(field(this, "atmosphere"), "compositeShader");
                }

                void edit(String name, String source) throws Exception {
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            editor.open(name);
                            var documents = (Map<?, ?>) field(editor, "documents");
                            ((RSyntaxTextArea) field(documents.get(name), "text")).setText(source);
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                }

                String editorStatus() throws Exception {
                    var status = new AtomicReference<String>();
                    SwingUtilities.invokeAndWait(() -> {
                        try { status.set(((JLabel) field(editor, "status")).getText()); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                    return status.get();
                }

                void assertPink() {
                    byte[] pixel = ScreenUtils.getFrameBufferPixels(Gdx.graphics.getBackBufferWidth() / 2,
                          Gdx.graphics.getBackBufferHeight() / 2, 1, 1, false);
                    assertEquals(217, Byte.toUnsignedInt(pixel[0]), 2);
                    assertEquals(38, Byte.toUnsignedInt(pixel[1]), 2);
                    assertEquals(166, Byte.toUnsignedInt(pixel[2]), 2);
                }

                void checkInactiveEffect() {
                    manager.run(() -> {
                        var result = manager.apply(Map.of("beams.frag", "invalid lazy effect"));
                        assertTrue(result.success(), result.message());
                        ShaderProgram[] effect = new ShaderProgram[1];
                        effect[0] = GpuShaderManager.program(() -> GpuGlsl.compile("beam fallback",
                              GpuShaderSource.read("effects.vert"), GpuShaderSource.read("beams.frag")), next -> effect[0] = next);
                        assertTrue(effect[0].isCompiled(), "An inactive invalid draft falls back when first drawn");
                        assertFalse(effect[0].getFragmentShaderSource().contains("invalid lazy effect"));
                        GpuShaderManager.dispose(effect[0]);
                    });
                }

                void checkRejectedChanges() {
                    manager.run(() -> {
                        int programs = ShaderProgram.getNumManagedShaderPrograms();
                        String wrongType = composite.replace("u_exposure", "u_exposure.x")
                              .replace("uniform float u_exposure.x", "uniform vec2 u_exposure");
                        var type = manager.apply(Map.of("atmosphere-composite.frag", wrongType));
                        assertFalse(type.success());
                        assertTrue(type.message().contains("uniform type"), type.message());
                        var link = manager.apply(Map.of("atmosphere.vert", """
                              #version 330 core
                              in vec2 a_position;
                              out vec3 v_uv;
                              void main() { v_uv = vec3(a_position, 0.0); gl_Position = vec4(a_position, 0.0, 1.0); }
                              """, "atmosphere-composite.frag", composite));
                        assertFalse(link.success());
                        assertTrue(link.message().contains("link"), link.message());
                        assertEquals(programs, ShaderProgram.getNumManagedShaderPrograms(), "Failed edits release candidates");
                    });
                }

                void capture() throws Exception {
                    Path folder = Path.of(System.getProperty("megamek.gpu.screenshots", "build/shader-editor"));
                    Files.createDirectories(folder);
                    var image = ScreenUtils.getFrameBufferPixmap(0, 0,
                          Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
                    try { PixmapIO.writePNG(new FileHandle(folder.resolve("shader-live-preview.png").toFile()), image); }
                    finally { image.dispose(); }
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            JFrame window = (JFrame) field(editor, "window");
                            var content = window.getContentPane();
                            BufferedImage snapshot = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
                            var graphics = snapshot.createGraphics();
                            try { content.printAll(graphics); }
                            finally { graphics.dispose(); }
                            ImageIO.write(snapshot, "png", folder.resolve("shader-editor.png").toFile());
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                }
            }, GpuBoardWindow.configuration(false));
        }
        if (failure.get() != null) { throw new AssertionError(failure.get()); }
    }
}
