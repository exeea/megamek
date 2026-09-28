/* Copyright (C) 2026 The MegaMek Team. SPDX-License-Identifier: GPL-3.0-or-later */
package megamek.client.ui.clientGUI.boardview.gpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Vector3;
import megamek.common.board.Coords;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Marsh slopes retain their material across levels; downhill seepage differs from a basin's uphill waterline. */
@Tag("on-demand")
class GpuMarshSlopeSmokeTest {
    @Test
    void connectedAndDrainingMarshBanksAcrossTerrainLods() throws Exception {
        var output = new File(System.getProperty("megamek.gpu.screenshots", "build/gpu-board-review"), "marsh-slopes");
        Files.createDirectories(output.toPath());
        var failure = new AtomicReference<Throwable>();
        var original = BoardGeometry.tuning();
        var originalLod = TerrainLod.tuning();
        boolean enabled = TerrainLod.enabled();
        var config = GpuBoardWindow.configuration(false);
        config.setWindowedMode(1280, 960);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                var terrain = new GpuTerrain();
                var frame = new GpuReviewFrame(new BoardAtmosphere.Settings(13, 0, 0,
                      BoardAtmosphere.STANDARD_GROUND_LAYER_HEIGHT, 0, 0));
                try {
                    GpuRiverTerrainSmokeTest.tune(.94f, true);
                    TerrainLod.setEnabled(true);
                    var camera = new BoardCamera();
                    camera.resize(1280, 960);
                    var plants = (GpuBiomeVegetation) field(terrain, "biomeVegetation");
                    var grass = (GpuGroundCover) field(terrain, "groundCover");
                    for (String contact : new String[] { "connected", "downhill", "uphill" }) {
                        var scene = scene(contact);
                        for (var lod : TerrainLod.values()) {
                            TerrainLod.tune(switch (lod) {
                                case FULL -> new TerrainLod.Tuning(1, 1);
                                case MEDIUM -> new TerrainLod.Tuning(100_000, 1);
                                case COARSE -> new TerrainLod.Tuning(100_000, 1000);
                                case DISTANT -> new TerrainLod.Tuning(100_000, 100_000);
                            });
                            camera.setIsometric(true);
                            camera.camera.zoom = .15f;
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            terrain.update(scene, camera.camera);
                            GpuTerrainLodSmokeTest.settle(terrain, null, scene, camera);
                            long deadline = System.nanoTime() + 30_000_000_000L;
                            do {
                                frame.render(terrain, camera, scene);
                                assertTrue(System.nanoTime() < deadline, "Marsh vegetation must settle");
                            } while (plants.busy() || grass.busy());
                            GpuReviewFrame.save(new File(output, contact + "-" + lod + "-iso.png"));
                            camera.setIsometric(false);
                            camera.center(BoardGeometry.center(new Coords(4, 4), 0));
                            frame.render(terrain, camera, scene);
                            GpuReviewFrame.save(new File(output, contact + "-" + lod + "-top.png"));
                        }
                    }
                    coverageChecks();
                    assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError());
                } catch (Throwable error) { failure.set(error); }
                finally {
                    terrain.dispose(); frame.dispose();
                    BoardGeometry.tune(original);
                    TerrainLod.tune(originalLod);
                    TerrainLod.setEnabled(enabled);
                    Gdx.app.exit();
                }
            }
        }, config);
        if (failure.get() != null) { throw new AssertionError("Marsh slope materials", failure.get()); }
    }

    private static BoardScene scene(String contact) {
        return BoardSurfaceBlendTest.scene(c -> BoardBiomeTest.tile(c,
              contact.equals("connected") || (c.getY() < 4) == contact.equals("downhill")
                    ? BoardScene.Biome.MARSH : BoardScene.Biome.NONE, c.getY() < 4 ? 1 : 0));
    }

    /** Sample the real GLSL mask, independently of lighting and vegetation hiding the bank. */
    private static void coverageChecks() {
        String code = Gdx.files.classpath("megamek/client/ui/clientGUI/boardview/gpu/terrain-biome-mask.glsl").readString();
        var shader = new ShaderProgram("""
              in vec3 a_position;
              out vec3 world;
              void main() {
                  world = a_position;
                  gl_Position = vec4((float(gl_VertexID) + .5) / 32.0 * 2.0 - 1.0, 0.0, 0.0, 1.0);
                  gl_PointSize = 1.0;
              }
              """, "uniform float u_levelHeight, u_metre;\nin vec3 world;\n" + code + """
              void main() {
                  vec4 cover, fringe;
                  biomeCoverage(world, 14.0, cover, fringe);
                  gl_FragColor = vec4(cover.y, fringe.y, 0.0, 1.0);
              }
              """);
        var mesh = new Mesh(true, 32, 0, VertexAttribute.Position());
        var target = new FrameBuffer(Pixmap.Format.RGBA8888, 32, 1, false);
        var mask = new GpuBiomeSurface();
        try {
            assertTrue(shader.isCompiled(), shader.getLog());
            float metre = BoardRelief.metres(1), level = BoardGeometry.level() / metre;
            Vector3 edge = BoardGeometry.center(new Coords(4, 3), 0)
                  .lerp(BoardGeometry.center(new Coords(4, 4), 0), .5f).scl(1 / metre);
            for (String contact : new String[] { "connected", "downhill", "uphill" }) {
                var scene = scene(contact);
                mask.update(scene);
                float[] points = new float[32 * 3];
                for (int i = 0; i < 32; i++) {
                    points[3 * i] = edge.x + (i - 15.5f) * .4f;
                    points[3 * i + 1] = edge.y;
                    points[3 * i + 2] = level * .5f;
                }
                mesh.setVertices(points);
                target.begin();
                Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
                Gdx.gl.glDisable(GL20.GL_BLEND);
                shader.bind();
                mask.texture().bind(0);
                shader.setUniformi("u_biomeHexes", 0);
                shader.setUniformf("u_biomeBoard", (float) scene.width(), (float) scene.height());
                shader.setUniformf("u_levelHeight", BoardGeometry.level());
                shader.setUniformf("u_metre", metre);
                mesh.render(shader, GL20.GL_POINTS);
                var pixels = Pixmap.createFromFrameBuffer(0, 0, 32, 1);
                target.end();
                try {
                    int minimum = 255, maximum = 0;
                    for (int i = 0; i < 32; i++) {
                        int sample = pixels.getPixel(i, 0);
                        assertEquals(0, sample >>> 24, "Connecting peat must not carry level pool support down the bank");
                        int peat = sample >>> 16 & 255;
                        minimum = Math.min(minimum, peat); maximum = Math.max(maximum, peat);
                    }
                    if (contact.equals("connected")) {
                        assertTrue(minimum > 225, "Adjacent marsh levels must retain peat across the intervening bank: " + minimum);
                    } else if (contact.equals("downhill")) {
                        assertTrue(maximum - minimum > 24, "A draining bank needs uneven seepage, not a constant height contour");
                    } else {
                        assertTrue(maximum < 20, "A lower marsh cannot saturate the higher bank like downhill drainage");
                    }
                } finally { pixels.dispose(); }
            }
        } finally { mask.dispose(); target.dispose(); mesh.dispose(); shader.dispose(); }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        var member = owner.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(owner);
    }
}
